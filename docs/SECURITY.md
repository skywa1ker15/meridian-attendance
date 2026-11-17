# Security architecture and OWASP review

Scope: Android app, FastAPI backend, administrator dashboard, deployment
(`deploy/`). Facts below come from the source where it existed when this document
was written (`backend/app/core/*`, `android/app/build.gradle.kts`, `deploy/*`) and
from the design contract (`ARCHITECTURE.md`, `API.md`). Rows marked **Design**
describe intended behaviour that the integrator must confirm against the final code
and tests. This review is a desk review. **No external penetration test has been
performed.**

## 1. Architecture summary

```
Phone (app) ──TLS 1.2+──► Caddy (HTTPS, HSTS, headers, body limit) ──► API (FastAPI, non-root, read-only FS) ──► PostgreSQL (internal network only)
Browser (dashboard, same origin) ──TLS──┘
API ──► FCM HTTP v1 / Play Integrity (Google, service-account auth, optional)
```

Trust boundaries: (1) the internet to Caddy; (2) Caddy to the API on the internal
Docker network; (3) the API to the database on a Docker network marked `internal`,
with no published port; (4) the phone itself, which is untrusted (employee-controlled).

## 2. Controls

### 2.1 Authentication
- **Passwords**: argon2id (`argon2-cffi`) with m = 64 MiB, t = 3, p = 4, a 16-byte salt
  and a 32-byte hash. Hashes are upgraded on login when parameters change
  (`needs_rehash`). For unknown users the code still verifies against a dummy hash,
  so login timing does not reveal whether an account exists. (`core/security.py`)
- **Password policy**: minimum length (`PASSWORD_MIN_LENGTH`, default 10), must not
  contain the e-mail or employee number, checked against a bundled common-password
  list on the server. Temporary passwords are 12 characters from an unambiguous
  alphabet. A password change is forced after an admin reset (`must_change_password`).
- **Lockout**: `LOGIN_MAX_FAILED_ATTEMPTS` (5) gives a `LOGIN_LOCKOUT_MINUTES` (15)
  lock (HTTP 423).
- **Access tokens**: JWT, HS256 by default (RS256 supported), lifetime 15 minutes.
  Claims: `sub`, `role`, `ver` (token version, which allows mass invalidation per
  user), `did` (device), `jti`, `iat`, `nbf`, `exp`, `typ=access`,
  `iss=meridian-attendance`. Decoding pins the algorithm list, requires
  `exp/sub/iat/typ` and checks issuer and `typ`.
- **Refresh tokens**: opaque, 48 random bytes, stored only as SHA-256. They are
  **rotated on every use**. Reusing a rotated token revokes the whole token family
  (`401 token_reused`). Lifetime 30 days. Logout revokes the token.
  Deactivation revokes all tokens and devices.
- **Password reset**: single-use hashed token valid for 30 minutes. The forgot
  endpoint always returns 202, so it does not reveal whether an account exists.
  It is rate-limited separately.
- **Secrets**: no credentials are hard-coded for staging or production. Settings
  validation refuses to start in staging or production when `SECRET_KEY` is shorter
  than 32 characters or contains a dev marker, when `DATABASE_URL` points at a dev
  database or localhost, or when `PUBLIC_BASE_URL` is not https.

### 2.2 Authorisation
- RBAC with an explicit server-side matrix (`core/permissions.py`): `employee`,
  `manager`, `admin`, `superadmin`. Roles come from the database record and are
  **never** taken from the client. Admins can assign roles up to `admin`. Only
  superadmins can grant `superadmin` or erase data.
- Object-level checks: employee endpoints are scoped to the caller (`/attendance/*`
  returns only the caller's own data). Admin endpoints require `admin:read` or
  stronger. **Design**: the integrator should confirm tests cover access to another
  user's resources (IDOR).
- The dashboard hides unavailable actions for convenience only. Enforcement is
  always on the server.

### 2.3 Device binding
- A device is registered at login with an installation UUID. By default one active
  device is allowed per user (`max_active_devices_per_user`). Optionally, a new
  device needs administrator approval. Revoked devices get `403 device_revoked`.
  The access token carries the device id (`did`).
- **Optional**: a Google Play Integrity verdict (`/devices/me/integrity`), recorded
  on the device. It can be made mandatory (`require_integrity_verdict`) once
  `PLAY_INTEGRITY_ENABLED` and a service account are configured.

### 2.4 Attendance integrity, replay and duplicate protection
- Each event and confirmation carries a client-generated UUID
  (`client_event_id`, `client_confirmation_id`) protected by a database unique
  constraint `(user_id, client_event_id)`. A replay returns the original result
  instead of creating a duplicate.
- Generic `Idempotency-Key` middleware stores responses keyed by
  (hash of the Authorization header, key, method, path) for 24 hours
  (`core/middleware.py`).
- Timestamp plausibility: events more than 120 s in the future are rejected, as are
  events older than 72 h. Online events whose device time differs from server time
  by more than 300 s go to review. Duplicate pending events within 600 s are merged.
  The official time is the server receipt time when the event is online and
  plausible. All thresholds are in the policy.
- Location outside radius + accuracy, a missing required Wi-Fi match, or a low
  verification score sends the event to **manager review**. It is never silently
  accepted, and an employee cannot self-approve.
- A database partial unique index allows only one open session per user.
- Limitation: a rooted phone can fake GPS (mock location) and Wi-Fi. Mitigations are
  Play Integrity, the Wi-Fi BSSID signal, manager review and audit. Mock location
  cannot be fully prevented on devices the employee controls (§5).

### 2.5 Transport and headers
- Caddy obtains and renews Let's Encrypt certificates automatically. HTTP is
  redirected to HTTPS. HSTS is set to 2 years with includeSubDomains (the API also
  sets 1-year HSTS when `PUBLIC_BASE_URL` is https).
- Headers set by Caddy and the API: `X-Content-Type-Options: nosniff`,
  `X-Frame-Options: DENY`, `Referrer-Policy: strict-origin-when-cross-origin`,
  `Permissions-Policy`, `Cross-Origin-Opener-Policy`, `Cross-Origin-Resource-Policy`,
  `Cache-Control: no-store` on `/api/`, and a strict CSP on `/admin`
  (`default-src 'self'`, `script-src 'self'`, `frame-ancestors 'none'`). The
  `Server` header is removed, and the request body limit is 2 MB.
- Android release builds: `ALLOW_CLEARTEXT=false`, and the build **fails** unless
  `MERIDIAN_API_BASE_URL` is `https://` and not localhost, 127.0.0.1 or 10.0.2.2.
  Certificate pinning has a hook but is off by default. Turning it on requires a
  pin-rotation plan (pin the Let's Encrypt intermediate CAs or keep a backup key).
- CORS is an allow-list (empty by default, because the dashboard is same-origin).

### 2.6 Rate limiting
Sliding windows per client IP per minute: login 10, forgot/reset 5, refresh 30,
attendance events/sync 60, everything else 300. The response is `429` with
`Retry-After`. Client IPs come from `X-Forwarded-For` only when
`TRUST_PROXY_HEADERS=true` (set by compose behind Caddy).
**Limitation**: the limiter is in memory and per process. With N gunicorn workers or
several API replicas, the effective limit is N times higher. The account lockout is
stored in the database and is unaffected. For multi-instance deployments, add
proxy-level limits or a shared store (`OPERATIONS.md` §6).

### 2.7 Input validation, injection, errors
- Pydantic v2 schemas validate every request body and query. SQLAlchemy 2 uses
  parameterised queries, and the design contains no raw string SQL.
- Errors use a uniform JSON shape (`{"error": {code, message, details}}`) and never
  include stack traces. Each response has an `X-Request-Id` that matches the log
  line.
- Logs never contain request bodies, passwords or tokens.

### 2.8 Audit
Every administrative mutation (employees, roles, devices, workplaces, policy,
approvals, corrections, exports, erasure) and security events (login failures,
lockouts, token reuse) are written to `audit_logs` with actor, IP, user agent and
before/after values. Audit logs are read-only from the dashboard and retained for
`retention_days_audit` (default 7 years).

### 2.9 Android client
- Tokens are encrypted with an AES-GCM key held in the **Android Keystore** and
  stored in DataStore. No database credentials and no secrets are in the APK. The
  API base URL is a build-time constant.
- R8 minification and resource shrinking in release builds. `isDebuggable=false`.
- The offline queue (Room) holds only the employee's own pending events.
  **Design**: the integrator should confirm whether Room is encrypted at rest. On
  modern Android, app-private storage is protected by file-based encryption.
- Signing: APK v2/v3/v4 signatures. The keystore is held only by Meridian and
  supplied to CI via secrets (`RELEASE.md`). No key is generated or committed by
  this project.

### 2.10 Infrastructure
The API container runs as non-root (UID 10001) with a read-only root filesystem,
`no-new-privileges` and all capabilities dropped. The DB uses scram-sha-256 and has
no host port. Secrets live in a 600-permission env file and a read-only mounted
`secrets/` directory. Backups are verified nightly. Images are built in CI and
published to GHCR on tags.

## 3. OWASP ASVS 4.0 / Top 10 (2021) mapping

Status: **Impl** = implemented in code read for this review. **Design** =
specified, to be confirmed by the integrator and tests. **Config** = needs Meridian
configuration. **Gap** = not implemented or partially implemented.

| OWASP Top 10 | ASVS | Control | Implementation | Status |
|--------------|------|---------|----------------|--------|
| A01 Broken access control | V4.1, V4.2 | Server-side RBAC, deny by default | `core/permissions.py`, per-endpoint permission dependency | Impl / Design (endpoint wiring) |
| A01 | V4.2.1 | IDOR protection | Employee endpoints scoped to the caller; admin endpoints require `admin:read` | Design — needs tests |
| A01 | V4.3 | Admin interface protection | Same auth plus role check; optional IP restriction via Caddy | Impl (Config for IP restriction) |
| A02 Cryptographic failures | V6.2, V2.4 | Password storage | argon2id 64 MiB/3/4 | Impl |
| A02 | V9.1 | TLS everywhere | Caddy auto-HTTPS + HSTS; app refuses http in release | Impl |
| A02 | V6.4 | Secret management | env file + mounted secrets, startup validation | Impl / Config |
| A03 Injection | V5.3 | Parameterised queries | SQLAlchemy ORM | Design (no raw SQL observed) |
| A03 | V5.1 | Input validation | Pydantic schemas | Impl |
| A04 Insecure design | V1, V11 | Detection ≠ attendance; review for doubtful events; replay protection | Event state machine, idempotency, policy thresholds | Design |
| A05 Security misconfiguration | V14.4 | Security headers, CSP | Caddy + `RequestContextMiddleware` | Impl |
| A05 | V14.1 | Hardened containers | non-root, read-only, cap_drop | Impl (needs Dockerfile UID 10001 — confirm) |
| A05 | V14.3 | No debug in production | `APP_ENV=production`, no stack traces, release `isDebuggable=false` | Impl |
| A05 | — | OpenAPI docs public | `/api/docs` reachable | Config (hide via Caddyfile if desired) |
| A06 Vulnerable components | V14.2 | Pinned versions, CI | `TOOLCHAIN.md`, CI builds | Gap — add Dependabot / `pip-audit` / OWASP dependency-check |
| A07 Identification & auth failures | V2.1, V2.2 | Password policy, lockout, rate limit | `security.py`, settings, middleware | Impl |
| A07 | V3.5 | Token handling | 15 min JWT, rotating refresh with reuse detection | Impl (auth_service) |
| A07 | V2.8 | MFA for administrators | — | **Gap** — recommended for admin/superadmin (TOTP) |
| A08 Software & data integrity | V10.3 | Signed app, signed images | APK v2-v4 signing, GHCR from CI | Impl / Config |
| A09 Logging & monitoring failures | V7.1, V7.2 | Structured logs, audit, alerts | JSON logs with request ids, `audit_logs`, Prometheus alerts | Impl / Config (alert receivers) |
| A10 SSRF | V12.6 | No user-controlled outbound URLs | Outbound calls only to Google APIs at fixed URLs | Impl |
| — | V8.3 | Sensitive data in responses | `Cache-Control: no-store` on API | Impl |
| — | V13.2 | Rate limiting | Sliding windows per IP | Impl (per-process limitation) |

## 4. OWASP MASVS 2.0 mapping (Android)

| MASVS | Control | Implementation | Status |
|-------|---------|----------------|--------|
| STORAGE-1 | Sensitive data stored securely | Tokens AES-GCM with Keystore key; no secrets in prefs | Design (Android code) |
| STORAGE-2 | No leakage via logs/backups | Debug-only HTTP logging; **confirm** `android:allowBackup=false` / data-extraction rules exclude tokens | Design — verify manifest |
| CRYPTO-1/2 | Strong crypto, Keystore | AES-GCM 256, Android Keystore | Design |
| AUTH-1 | Secure auth protocol | Server-side auth, short JWT, refresh rotation | Impl (server) |
| AUTH-2 | Local session handling | Logout clears tokens; revoked device forces re-login | Design |
| NETWORK-1 | TLS only | `usesCleartextTraffic=false` in release; https-only URL check | Impl (build) / Design (manifest) |
| NETWORK-2 | Certificate pinning | Hook present, disabled | Gap (optional; documented) |
| PLATFORM-1 | IPC / exported components | Receivers for geofence, boot and location must be non-exported or permission-protected | Design — verify manifest |
| PLATFORM-3 | UI data leakage | Consider `FLAG_SECURE` on screens with personal data | Gap (optional) |
| CODE-1 | Up-to-date platform | targetSdk 36, minSdk 26 | Impl |
| CODE-3 | Input validation | Server is authoritative; client validation for UX | Impl |
| RESILIENCE-1/2 | Tamper and root detection | Play Integrity optional; R8 obfuscation | Config (enable Play Integrity) |
| PRIVACY-1..4 | Data minimisation, transparency, user control | Geofencing only, no continuous tracking; privacy notice; export and correction | Impl / Design; legal review in `PRIVACY-POPIA.md` |

## 5. Threat model (STRIDE summary)

| # | Threat | Actor | Mitigation | Residual risk |
|---|--------|-------|-----------|---------------|
| T1 | Employee fakes presence with a mock-location app or emulator | Employee | Server-side geofence and accuracy check, Wi-Fi BSSID signal, Play Integrity (optional), manager review, audit trail, one device per user | **Medium.** Mock location on a rooted phone cannot be fully detected. Policy and HR process remain necessary. |
| T2 | Buddy punching (colleague confirms on someone else's phone) | Employee | Device binding, password per user. Optional: Android biometric prompt before confirm (not in scope) | Medium. Organisational control. |
| T3 | Replaying or duplicating submissions | Employee / attacker | Client ids + DB unique constraints, idempotency middleware, timestamp windows | Low |
| T4 | Manipulated device clock | Employee | Server receipt time is official when online; skew sends the event to review; max offline age | Low |
| T5 | Credential stuffing or brute force | External | Rate limits, lockout, argon2id, common-password check | Low–Medium (no MFA) |
| T6 | Stolen refresh token | External / malware | Rotation + reuse detection revokes the family; device revocation | Low |
| T7 | Privilege escalation by a manager or employee | Insider | Server RBAC; role from DB; superadmin-only grants; audit | Low |
| T8 | Admin account compromise | External | Lockout, audit, short sessions; recommend MFA and IP allow-list for `/admin` | **Medium** until MFA is added |
| T9 | Database exposure | External | DB not published; internal network; scram-sha-256; encrypted backups recommended | Low (depends on host security) |
| T10 | Backup theft | Insider / external | Backups at 600 permissions; off-site encryption recommended; retention | Medium until encrypted off-site storage is configured |
| T11 | Lost or stolen phone | — | Keystore-encrypted tokens; admin revokes device; screen lock is the user's responsibility | Low |
| T12 | Denial of service | External | Caddy body limit, rate limit; single host | Medium (single host; no WAF/CDN) |
| T13 | Excessive data collection or misuse of location | Insider | Collect only at transition and confirmation; RBAC; audit; retention pseudonymisation | Low–Medium (organisational) |
| T14 | Supply-chain compromise of dependencies | External | Pinned versions, CI builds | Medium until automated dependency scanning is added |

## 6. What Meridian must configure or decide

1. Generate production secrets (`SECRET_KEY`, DB password, `METRICS_TOKEN`) and store
   them in the company password manager (`deploy/README.md` §4).
2. Restrict SSH and the host. Enable unattended security updates. Encrypt disks and
   backups.
3. Decide whether to restrict `/admin` to office or VPN IP ranges. Add a `remote_ip`
   matcher to the Caddyfile if so.
4. Decide whether to hide `/api/docs` in production.
5. Enable Play Integrity (Google Cloud project + service account) and decide whether
   it is mandatory (`require_integrity_verdict`).
6. Decide on device-replacement approval and the number of devices per employee.
7. Configure alert receivers and an uptime check.
8. Hold the Android signing keystore securely, preferably with Play App Signing.
9. Commission an independent penetration test before or shortly after go-live.
10. Adopt the recommended improvements: MFA for administrator roles, automated
    dependency scanning (Dependabot / `pip-audit`), shared rate-limit store for
    multi-instance setups, optional certificate pinning, and an optional biometric
    prompt on confirmation.

## 7. Security testing

The brief requires executed security tests: unauthorised access, privilege
escalation, invalid tokens, replay, manipulated timestamps, insecure requests, SQL
injection and data exposure. These belong in `backend/tests/` and run in CI
(`.github/workflows/backend.yml`). **Integrator**: link the test modules and record
the last CI run result here. If any of these test categories is missing, list it as
a gap.

Reporting a vulnerability: e-mail Meridian IT security (address to be supplied) with
the request ID and steps to reproduce. Do not test against production without
written permission.

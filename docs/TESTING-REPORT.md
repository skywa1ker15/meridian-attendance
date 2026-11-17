# Testing report — Meridian Attendance v1.0.0

Date: 17 November 2026. Environment: Linux build sandbox, Python 3.13, PostgreSQL 16,
Chromium (Playwright 1.63), kotlinc 2.3.0, JDK 21. Every result below was produced by
actually running the command shown; nothing is reported from code reading alone.

## Summary

| Suite | Command | Result |
|-------|---------|--------|
| Backend unit/integration/security | `cd backend && pytest -q` | **120 passed**, 0 failed |
| Backend lint | `cd backend && ruff check .` | clean |
| Migrations | `alembic upgrade head && alembic downgrade base && alembic upgrade head && alembic check` | clean, no model drift |
| Production entrypoint | `backend/docker-entrypoint.sh` (run locally, not in a container) | migrates, starts gunicorn; `/healthz`, `/readyz`, `/admin/` → 200 |
| Dashboard module check | `cd admin-dashboard/tests && npm run check` | 25/25 modules load |
| Dashboard unit | `npm run test:unit` | 21/21 pass |
| Dashboard smoke (API down) | `npm run smoke` | 24/24 pass |
| Dashboard end-to-end (live backend) | `npm run test:e2e` | **26/26 pass** |
| Android core logic | `android/tools/core-check` | **60/60 pass** |
| Android source check | `android/tools/syntax-check` | OK (see limits below) |
| Android ↔ backend endpoints | OpenAPI vs `AttendanceApi.kt` | 33/33 endpoints exist |
| Android ↔ backend live round trip | `android/tools/contract-roundtrip/run` | **28/28 pass** |
| Live security spot checks | curl against running server | pass (see §4) |

## 1. Backend (pytest, real PostgreSQL)

Covers authentication (login, lockout, refresh rotation, reuse detection revoking the
token family, logout), password policy, device registration/replacement/revocation,
event submission (idempotent replay, duplicate window, future and stale timestamps,
clock skew, outside geofence, unconfirmed placeholder workplace, Wi-Fi required,
arrival with open session, departure without session), confirmation and session
maths (worked minutes, late flag), dismissal, expiry by the scheduler, sync batches,
manual requests, admin employee management and role rules, overview/live/exceptions
figures computed from seeded data, all 11 reports in all 4 formats, audit log entries,
notification inbox, and the security cases in §4.

## 2. Administrator dashboard (Playwright, Chromium, live backend)

Login and forced password change, every screen rendering, creating an employee and
seeing the one-time password, approving a pending detection, downloading a CSV
report, editing the workplace, an admin action appearing in the audit log with its
diff, a manager being refused employee creation (UI hidden and server 403), logout,
return-to-route after login, and 360 px layout.

## 3. Android

* `core-check` compiles the Android-free code (verification, exit debounce, outbox
  ordering/retry, error mapping, Johannesburg time formatting, validation) and the
  kotlinx-serialization DTOs with warnings as errors, then runs 60 tests including DTO
  decoding of the `docs/API.md` examples.
* `contract-roundtrip` acts as the phone against the live backend using the app's own
  DTOs and JSON settings: login with device registration, config, detect + confirm
  arrival, detect + confirm departure, offline sync batch and idempotent replay,
  history, session detail, manual request, notifications, FCM token upload, token
  refresh and reuse detection, and the manager live/review screens.
* `syntax-check` compiles all 86 Kotlin files and fails on syntax errors, duplicate
  declarations, unresolved project symbols, bad project imports and any error in the
  Android-free code; it also validates manifest/resource XML and all 289 resource
  references.

**Not verified here** (no access to Google Maven / Maven Central from the sandbox):
Gradle dependency resolution, type-checking of code that uses Android/AndroidX/Play
Services/Room/Compose, Room's compile-time query checks, R8, lint, 4 JVM tests and 3
instrumented tests. These run in `.github/workflows/android.yml`. Expect the first CI
build to surface some compile errors that need fixing; that is normal for code not yet
compiled against its libraries. See `docs/android/BUILD-VERIFICATION.md`.

## 4. Security tests

Unauthenticated → 401; employee calling admin endpoints → 403; manager escalating
to admin/superadmin → 403; forged `alg:none` token and tampered/expired JWTs → 401;
replayed refresh token → 401 `token_reused` and the whole family revoked; future and
stale device timestamps rejected, skewed ones sent to review; SQL-injection strings in
identifier/search fields are inert; malformed JSON → structured 422 without internals;
no stack traces in any error; login rate limit → 429 with `Retry-After`; security
headers (CSP, X-Frame-Options, nosniff, Referrer-Policy) present; idempotency-key replay
returns the original response; predictable passwords (`Password123!`, `Meridian2026!`)
rejected.

## 5. Defects found and fixed during integration

| # | Found by | Defect | Fix |
|---|----------|--------|-----|
| 1 | Android contract round trip | A genuine **departure** (located outside the geofence) was sent to review as `outside_geofence`, and `wifi_required` also applied to departures — every real clock-out would have needed a manager | Departures are now verified by being *outside*; a geofence departure still inside goes to review as `departure_still_inside`; Wi-Fi is an arrival-only requirement. 3 regression tests added; `docs/API.md` updated |
| 2 | Integrator review | Password policy accepted `Password123!`-style variants | Common/company words padded with digits/symbols, and low-variety passwords, rejected; 10 tests added |
| 3 | Dashboard e2e | Test fixtures used a reserved email domain and passwords containing the user's identifier (both correctly rejected by the server) | Test data fixed; no product change |
| 4 | Dashboard e2e | Audit-diff selector matched two elements | Test selector fixed |
| 5 | Backend agent | Empty `CORS_ORIGINS=` in env files crashed settings loading | Parses as empty list |
| 6 | Dashboard smoke | Employee profile dead-ended when loading failed | Back link added |
| 7 | Deploy config check | Compose expanded the backup loop's shell variables | Escaped |

## 6. Not tested (and why)

* **Physical-device behaviour** — geofence timing, dwell, exit debounce, battery
  optimisation by manufacturer, reboot, permission revocation, Wi-Fi SSID access.
  Requires phones at the Umhlanga office: `docs/PILOT-TEST-PLAN.md`.
* **Android build artefacts** — APK/AAB are produced by CI, not here.
* **Docker images and Caddy** — no Docker daemon in the sandbox; compose files pass
  `docker compose config`; run `caddy validate` once on the server.
* **FCM push, SMTP email, Play Integrity** — need Meridian's credentials.
* **XLSX/PDF downloads in the browser** — generation is tested in pytest; only CSV
  download is tested end-to-end.
* **Load testing** and an independent **penetration test** — recommended before
  company-wide rollout.

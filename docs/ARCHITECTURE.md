# Architecture — Meridian Group Employee Attendance System

## 1. System overview

```
┌──────────────────────────┐        HTTPS (JSON/REST)        ┌──────────────────────────────┐
│  Android app (Kotlin)    │ ───────────────────────────────▶ │  FastAPI backend (Python)    │
│  Jetpack Compose / M3    │ ◀─────────────────────────────── │  /api/v1  + /admin (SPA)     │
│  Geofencing, Wi‑Fi,      │   FCM push (data messages)       │  Verification engine         │
│  Room offline queue      │ ◀─────────── Firebase ◀───────── │  Scheduler (expiry, flags)   │
└──────────────────────────┘                                  │  Reports (CSV/XLSX/PDF)      │
                                                              └──────────────┬───────────────┘
┌──────────────────────────┐        HTTPS                                    │ SQLAlchemy / psycopg
│  Admin dashboard (web)   │ ───────────────────────────────▶                ▼
│  served from backend     │                                  ┌──────────────────────────────┐
└──────────────────────────┘                                  │  PostgreSQL 16               │
                                                              └──────────────────────────────┘
Reverse proxy / TLS: Caddy (automatic Let's Encrypt)   Containers: Docker Compose
```

The **server is authoritative** for all confirmed attendance. The phone only
*detects* and *asks*; management can see everything that was detected but not
confirmed.

## 2. Repository layout

```
meridian-attendance/
├── android/              Native Android app (Kotlin, Compose, Material 3)
├── backend/              FastAPI application, Alembic migrations, tests, Dockerfile
│   └── app/static/admin  Administrator dashboard (self‑contained SPA, no build step)
├── admin-dashboard/      → symlink/notes pointing at backend/app/static/admin (single source)
├── deploy/               Compose files, Caddyfile, env templates, backup & ops scripts
├── docs/                 All documentation (this file, API contract, manuals, security, POPIA…)
└── .github/workflows/    CI: backend tests, container image, Android APK/AAB builds
```

## 3. Backend

* **Stack**: Python 3.12+, FastAPI, SQLAlchemy 2.x (sync engine, psycopg 3), Alembic,
  Pydantic v2 / pydantic‑settings, PyJWT (HS256 by default, RS256 optional),
  argon2‑cffi (password hashing), openpyxl (XLSX), reportlab (PDF),
  prometheus‑client, google‑auth (FCM HTTP v1 & Play Integrity, optional).
* **Layering**: `api/` (routers, request/response only) → `services/` (business rules,
  transactions) → `models/` (SQLAlchemy) ; `schemas/` (Pydantic) ; `core/` (config,
  security, dependencies, rate limiting, logging, time helpers).
* **Verification engine** (`services/verification.py`): scores a detected event from
  signals — inside geofence (haversine distance vs radius + accuracy), accuracy ≤
  threshold, Wi‑Fi SSID/BSSID match, timestamp plausibility, device active, Play
  Integrity verdict. Produces a score ∈ [0,1] and a decision: `auto` (may be confirmed
  by the employee), `review` (needs a manager after confirmation) or `reject`.
* **Attendance engine** (`services/attendance.py`): state machine for events and
  sessions; idempotent submission; duplicate windows; session open/close rules;
  late/overtime computation using the policy and `Africa/Johannesburg`.
* **Scheduler** (`services/scheduler.py`): in‑process loop (every 60 s) — expires
  stale pending events, flags missing clock‑outs after `missing_clock_out_flag_local`,
  applies retention (location data pseudonymisation after `retention_days_location`).
  Can also be run as a one‑shot CLI (`python -m app.cli jobs run-once`) from cron.
* **Notifications** (`services/notifications.py`): every notification is stored in
  the inbox table and, when the device has an FCM token and FCM credentials are
  configured, sent as an FCM **data** message (HTTP v1). Without credentials the
  app still receives everything by polling / on open.
* **Security**: see `docs/SECURITY.md` — argon2id, access JWT 15 min, refresh token
  rotation with family revocation and reuse detection, account lockout, rate
  limiting middleware, RBAC via explicit permission matrix, audit log on every
  admin mutation, idempotency keys, replay protection (client ids + timestamp
  windows), secure headers, CORS allow‑list, no stack traces in responses.
* **Time**: everything stored `timestamptz` (UTC). `work_date` is derived in
  `Africa/Johannesburg` at the moment the official time is set.

## 4. Database (PostgreSQL)

Tables (all with `created_at`, most with `updated_at`):
`departments`, `users`, `devices`, `refresh_tokens`, `password_reset_tokens`,
`workplaces`, `attendance_policy` (singleton), `attendance_events`,
`attendance_sessions`, `manual_requests`, `notifications`, `audit_logs`,
`idempotency_keys`.

Key constraints:
* `users.employee_number` and `users.email` unique (case‑insensitive index on email).
* `devices (user_id, device_id)` unique; partial unique index: at most
  `max_active_devices_per_user` enforced in service + a partial unique index for the
  default of one active device per user is **not** used (policy may allow more);
  instead `status='active'` rows are counted inside a row‑locked transaction.
* `attendance_events (user_id, client_event_id)` unique → idempotency.
* `attendance_sessions`: partial unique index `one open session per user`
  (`WHERE status = 'open'`).
* `refresh_tokens.token_hash` unique; `family_id` indexed.
* Foreign keys everywhere with `ON DELETE RESTRICT` (attendance history is evidence;
  erasure is pseudonymisation, not deletion).
* Indexes on `(user_id, work_date)`, `(status)`, `(received_at)`, `(created_at)` for
  reports and dashboards.

## 5. Android application

* **Stack**: Kotlin 2.3, Jetpack Compose + Material 3 (BOM 2025.12.00), Navigation
  Compose, Room (KSP), WorkManager, DataStore, Retrofit + OkHttp + kotlinx‑serialization,
  Play Services Location (Geofencing API), Firebase Cloud Messaging, Android Keystore,
  Play Integrity (optional). Manual dependency injection via an application‑scoped
  `AppGraph` (no annotation‑processing DI, to keep the build simple and fast).
* **minSdk 26 / targetSdk 36 / compileSdk 36**.
* **Detection pipeline**:
  1. `WorkplaceConfigRepository` pulls `/config/workplaces` (cached in Room) and
     registers geofences (`GeofencingClient`, `DWELL` for arrival with
     `loiteringDelay = dwell_seconds`, `EXIT` for departure, `initialTrigger` DWELL).
  2. `GeofenceBroadcastReceiver` → enqueues `GeofenceTransitionWorker` (expedited).
  3. Worker re‑checks: for EXIT it waits `exit_debounce_seconds` and re‑reads the
     current location before concluding a departure (temporary GPS loss never clocks
     anybody out). It gathers signals (last location + accuracy, connected Wi‑Fi
     SSID/BSSID if permitted, battery), creates a **local pending event** in Room,
     attempts `POST /attendance/events`, and shows a confirmation notification.
  4. Employee taps → `ConfirmationScreen` → `POST …/confirm` (or queued offline).
  5. `SyncWorker` (periodic 15 min + on connectivity + on app open) replays the
     queue via `/attendance/sync` with idempotent client ids and refreshes today's
     summary; `BootReceiver` and `LocationProviderChangedReceiver` re‑register
     geofences after restart / location toggling.
* **Offline**: Room `pending_outbox` table; every action is a row with a client id;
  UI shows a sync status chip; nothing is marked confirmed until the server says so.
* **Security**: tokens encrypted with an AES‑GCM key in Android Keystore; TLS only
  (`usesCleartextTraffic=false`); certificate pinning hook (disabled by default, with
  instructions); no secrets in the APK; API base URL per build flavour.
* **Screens**: Splash, Login, Permission onboarding, Dashboard, Arrival confirmation,
  Departure confirmation, History, Attendance detail, Manual request, Notifications,
  Profile & settings, (+ Admin: pending reviews & live overview for managers).

## 6. Administrator dashboard

Single‑page application (vanilla ES2020 modules, no build step, no CDN) served at
`/admin/` by the backend. Hash‑routed screens: Overview, Employees, Employee
profile, Live attendance, Records, Pending confirmations, Exceptions, Reports &
analytics, Workplace configuration, Devices, Roles & permissions, Audit logs.
Charts are hand‑drawn SVG. Exports call the report endpoints with `format=`.

## 7. Environments

| Env | Database | API URL | Secrets |
|-----|----------|---------|---------|
| development | local Postgres / compose `db` | `http://10.0.2.2:8000` (emulator) or LAN IP | `.env` (never committed) |
| staging | managed Postgres | `https://attendance-staging.<domain>` | secret store / CI secrets |
| production | managed Postgres + backups | `https://attendance.<domain>` | secret store only |

The Android `release` build type only accepts an `https://` base URL
(`API_BASE_URL` Gradle property / CI secret); a build with `localhost` or `http://`
fails at configuration time.

## 8. Decisions & trade‑offs

| Decision | Why |
|----------|-----|
| Geofence DWELL instead of ENTER for arrival | Avoids events for people driving past the building |
| Debounced EXIT + location re‑check | Avoids spurious clock‑outs on GPS drift |
| Server time as official time when online | Device clocks can be manipulated |
| Confirmation required for both events | Spec; detection ≠ attendance |
| Pseudonymisation instead of deletion | Attendance records are employment evidence (POPIA allows retention for legitimate purposes; reviewed by Meridian legal) |
| Manual DI in Android | No KSP/Hilt plugin risk, fast builds, trivial to test |
| Vanilla JS dashboard served by the API | Zero build tooling, same origin (no CORS), easy to host |

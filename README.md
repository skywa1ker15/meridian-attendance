# Meridian Group Employee Attendance System

Geofence-based attendance for Meridian Group (Umhlanga, KwaZulu-Natal, South Africa).
Employee phones detect arrival at and departure from the registered workplace, ask the
employee to **confirm**, and the server records verified attendance. Management uses a
web dashboard for monitoring, review, reporting and configuration.

* Android app — Kotlin, Jetpack Compose, Material 3, Geofencing API, Room, WorkManager, FCM
* Backend — Python 3.12, FastAPI, SQLAlchemy 2, Alembic, PostgreSQL 16
* Administrator dashboard — static SPA served by the backend at `/admin/`
* Deployment — Docker Compose, Caddy (automatic HTTPS), GitHub Actions CI

Detection is never attendance: only an event the employee confirms (or a manager
approves) changes an attendance session. Time is stored in UTC and displayed in
`Africa/Johannesburg`.

## Where it runs

The app needs a central server for its database; it cannot be stored on GitHub.
[`docs/HOSTING.md`](docs/HOSTING.md) compares the three options (Render free tier for a
pilot, a South African server, or a machine at the office) and `render.yaml` makes the
pilot a one-click Blueprint deploy.

## Status matrix

Categories follow the project brief: **A** implemented and tested · **B** implemented,
awaiting real-world validation · **C** awaiting company-specific configuration ·
**D** not implemented. Full evidence: [`docs/TESTING-REPORT.md`](docs/TESTING-REPORT.md).

| Area | Status | Evidence / notes |
|------|--------|------------------|
| Backend API (auth, invitations, devices, attendance, sync, manual requests, notifications, admin, reports) | **A** | 232 pytest tests pass against PostgreSQL 16 |
| Database schema, constraints, migrations | **A** | Alembic upgrade → downgrade → upgrade verified; `alembic check` shows no drift |
| Verification engine, scheduler (expiry, missing clock-outs, retention), audit log | **A** | covered by pytest; scheduler advisory lock tested |
| Security controls (auth, refresh rotation + reuse detection, RBAC, rate limits, replay/idempotency, headers) | **A** | pytest security suite + live spot checks; OWASP mapping in `docs/SECURITY.md` |
| Reports: 11 types × JSON/CSV/XLSX/PDF | **A** | pytest generates every combination; CSV download tested in the browser |
| Administrator dashboard (all 12 screens + login/reset) | **A** | 26/26 Playwright end-to-end tests against the live backend; 21 unit tests; 24 smoke checks |
| Android ↔ backend contract | **A** | live round trip using the app's own DTOs: 63/63 checks (employee flow, Manage section, invitation → activation → login) |
| Android pure-Kotlin logic (verification, exit debounce, outbox, time, phone/password validation, deep links) | **A** | 83/83 tests via `android/tools/core-check` |
| Android app — full source (screens, detection, Room, sync, FCM, Keystore) | **B** | written and syntax/resource checked; **not compiled with Gradle yet** (sandbox had no Google/Maven access) |
| Android debug / release APK, AAB | **B / C** | built by `.github/workflows/android.yml` on first push; signed builds need Meridian's keystore + production URL |
| Geofence behaviour on real phones (arrival, departure, battery saver, reboot) | **B** | **not yet tested on devices** — `docs/PILOT-TEST-PLAN.md` |
| Push notifications (FCM) | **C** | implemented; needs Firebase project + service account. In-app inbox works without it |
| Employee invitations + password reset by emailed link | **B** | implemented and tested end to end against a live server; **C** for delivery — needs Meridian's Microsoft 365 app registration (`docs/EMAIL-SETUP.md`) |
| Email delivery (Microsoft Graph, SMTP fallback) | **C** | implemented; needs the Entra app registration. Until then admins issue temporary passwords or pass the invitation link on by hand |
| Production deployment | **C** | `deploy/` prepared and `docker compose config` validated; **not deployed** |
| Google Play internal testing / managed distribution | **C** | **not published** — needs Play Console account and approval |
| Workplace coordinates, Wi-Fi, employment policy, official logo | **C** | placeholders only (`docs/CONFIGURATION-REQUIRED.md`) |
| POPIA | **C** | technical measures implemented; legal review outstanding (`docs/PRIVACY-POPIA.md`) |
| Admin MFA, certificate pinning, Play Integrity enforcement, penetration test | **D** | recommended before or soon after go-live (`docs/SECURITY.md`) |

## Quick start (developer)

```bash
# 1. Backend + database
cp deploy/env/development.env.example deploy/env/development.env
docker compose -f deploy/docker-compose.dev.yml up --build
#    API: http://localhost:8000  (LAN: http://<your-ip>:8000)   Docs: /api/docs   Dashboard: /admin/

# 2. First administrator (or demo data)
deploy/scripts/create-first-admin.sh dev
docker compose -f deploy/docker-compose.dev.yml exec api python -m app.cli seed-demo   # development only

# 3. Android app: open android/ in Android Studio, run the `debug` variant on an emulator
#    (API base URL defaults to http://10.0.2.2:8000) or a phone on the same Wi-Fi
#    (-PMERIDIAN_DEBUG_API_BASE_URL=http://<your-ip>:8000).
```

Full instructions: `docs/INSTALLATION.md`.

## Repository layout

```
android/            Android application (Gradle, Kotlin)
backend/            FastAPI application, Alembic migrations, tests, Dockerfile
backend/app/static/admin   Administrator dashboard (served at /admin/)
admin-dashboard/    Dashboard tests (Node unit + Playwright e2e)
deploy/             Compose files, Caddyfile, env templates, scripts, monitoring
docs/               Documentation (index below)
.github/workflows/  CI: backend.yml, android.yml, dashboard.yml
```

## Documentation

| Document | Content |
|----------|---------|
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | System design, data model, detection pipeline, decisions |
| [docs/API.md](docs/API.md) | REST API contract (also live at `/api/docs`) |
| [docs/INSTALLATION.md](docs/INSTALLATION.md) | Developer setup: backend, dashboard, Android Studio, building the APK |
| [docs/DEPLOYMENT.md](docs/DEPLOYMENT.md) | The 10-step production rollout (backend → phones → monitoring) |
| [deploy/README.md](deploy/README.md) | Server-side deployment runbook |
| [docs/CONFIGURATION-REQUIRED.md](docs/CONFIGURATION-REQUIRED.md) | Checklist of every value Meridian must supply |
| [docs/ADMIN-MANUAL.md](docs/ADMIN-MANUAL.md) | Administrator and manager manual |
| [docs/EMPLOYEE-MANUAL.md](docs/EMPLOYEE-MANUAL.md) | Employee app manual |
| [docs/SECURITY.md](docs/SECURITY.md) | Security architecture, OWASP review, threat model |
| [docs/PRIVACY-POPIA.md](docs/PRIVACY-POPIA.md) | Data inventory, notices, retention, procedures, legal-review items |
| [docs/RELEASE.md](docs/RELEASE.md) | Versioning, signing, Play Console, distribution, store listing |
| [docs/PILOT-TEST-PLAN.md](docs/PILOT-TEST-PLAN.md) | Real-device pilot test plan (not yet executed) |
| [docs/OPERATIONS.md](docs/OPERATIONS.md) | Monitoring, backups, logs, scheduler, scaling |
| [docs/TROUBLESHOOTING.md](docs/TROUBLESHOOTING.md) | Symptoms → causes → fixes |
| [docs/TOOLCHAIN.md](docs/TOOLCHAIN.md) | Pinned versions |
| [docs/DESIGN.md](docs/DESIGN.md) | Design system |
| docs/android/, docs/admin-dashboard/ | Component-specific documentation (permissions, screens) |

## Important limitations (read before go-live)

* **No physical device testing has been performed.** Geofence behaviour on real
  phones at the Umhlanga office must be validated with `docs/PILOT-TEST-PLAN.md`.
* **No production deployment and no Play Store publication have happened.**
* The seeded workplace coordinates are **placeholders**; the policy values are
  software defaults, **not** Meridian's employment rules.
* The official Meridian logo was not supplied; a neutral placeholder monogram is used.
* Technical privacy controls exist, but **POPIA compliance requires review** by
  Meridian's Information Officer and legal advisers.

## Licence / ownership

Developed for Meridian Group. Internal use; no public licence granted.

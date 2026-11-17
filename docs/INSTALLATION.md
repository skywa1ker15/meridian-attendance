# Installation guide (developers)

How to set up a workstation to run and build every component. For production
hosting see `deploy/README.md`; for the end-to-end rollout see `DEPLOYMENT.md`.

## 1. Prerequisites

| Component | Requirement |
|-----------|-------------|
| Backend | Python 3.12+ (3.13 fine), PostgreSQL 16 (or Docker), `git` |
| Docker route | Docker Engine 24+ with the Compose plugin (`docker compose version`) |
| Android | Android Studio (Ladybug / 2024.2 or newer, bundled JDK 17 or 21), Android SDK Platform 36, Build-Tools 36.0.0, an emulator image with Google Play services (API 34–36) or a physical phone (Android 8.0+ with Google Play services) |
| Dashboard tests | Node.js 22, Chrome (Playwright downloads its own Chromium) |

Pinned versions: `TOOLCHAIN.md`.

## 2. Backend

### 2a. With Docker (recommended)

```bash
git clone <repository-url> meridian-attendance && cd meridian-attendance
cp deploy/env/development.env.example deploy/env/development.env
docker compose -f deploy/docker-compose.dev.yml up --build
```

* API: `http://localhost:8000`, OpenAPI UI `http://localhost:8000/api/docs`
* Dashboard: `http://localhost:8000/admin/`
* PostgreSQL: `127.0.0.1:5432`, user `meridian`, password `meridian_dev`, db `meridian_attendance`
* Source is mounted; uvicorn reloads on change. Migrations run on container start.
* Port 8000 is published on **all interfaces** so a phone on the same Wi-Fi reaches
  `http://<workstation-LAN-IP>:8000` (allow it through the workstation firewall).

First admin / demo data:

```bash
deploy/scripts/create-first-admin.sh dev
docker compose -f deploy/docker-compose.dev.yml exec api python -m app.cli seed-demo   # demo employees, departments, placeholder workplace; dev only
```

### 2b. Without Docker

```bash
cd backend
python -m venv .venv && source .venv/bin/activate      # Windows: .venv\Scripts\activate
pip install -r requirements.txt
# PostgreSQL running locally with the dev credentials above, or edit DATABASE_URL
cp ../deploy/env/development.env.example .env            # pydantic-settings reads ./.env (or ENV_FILE=path)
alembic upgrade head
python -m app.cli create-admin
uvicorn app.main:app --reload --host 0.0.0.0 --port 8000
```

### 2c. Tests and lint

```bash
cd backend
pip install ruff pytest
export DATABASE_URL=postgresql+psycopg://meridian:meridian_dev@127.0.0.1:5432/meridian_attendance_test   # a separate DB
export APP_ENV=test SECRET_KEY=test-only-0123456789abcdef0123456789abcdef RATE_LIMIT_ENABLED=false SCHEDULER_ENABLED=false
ruff check .
pytest -q
```

CI (`.github/workflows/backend.yml`) runs the same against a PostgreSQL 16 service.

## 3. Administrator dashboard

No build step: the SPA lives in `backend/app/static/admin` and is served by the API.
Edit the files, refresh the browser. Tests live in `admin-dashboard/`:

```bash
cd admin-dashboard
npm install && npx playwright install --with-deps chromium
npm test                                                   # unit tests
BASE_URL=http://127.0.0.1:8000 ADMIN_IDENTIFIER=<admin email> ADMIN_PASSWORD=<password> npm run test:e2e
```

The e2e tests expect a running backend with an administrator account (CI uses
`python -m app.cli seed-demo` in `APP_ENV=development`).

## 4. Android application

### 4a. Open and run (debug)

1. Android Studio > **Open** > select the `android/` directory (not the repo root).
2. Let Gradle sync (downloads AGP 8.13.2, Kotlin 2.3.0, dependencies — first sync
   needs internet and a few minutes).
3. Optional: copy the real `app/google-services.json` from the Firebase console into
   `android/app/` for push notifications. Without it the build prints a warning and
   the app polls for notifications. **Never commit this file.**
4. API URL for debug builds: default `http://10.0.2.2:8000` (emulator → host). For a
   physical phone on the same Wi-Fi set your workstation IP in
   `android/gradle.properties` (`MERIDIAN_DEBUG_API_BASE_URL=http://192.168.1.50:8000`)
   or pass `-PMERIDIAN_DEBUG_API_BASE_URL=...`. Debug builds allow cleartext HTTP;
   release builds do not.
5. Run the `app` configuration with the **debug** build variant on an emulator image
   that includes Google Play services (geofencing needs Play services) or on a phone
   with USB debugging enabled.

### 4b. Build the APK from the command line

```bash
cd android
./gradlew assembleDebug                     # → app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest lintDebug       # unit tests + lint reports in app/build/reports/
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Release builds require an **https** API URL and (for an installable artefact)
Meridian's signing keystore:

```bash
./gradlew assembleRelease bundleRelease \
  -PMERIDIAN_API_BASE_URL=https://attendance.<domain> \
  -PMERIDIAN_KEYSTORE_PATH=/secure/path/meridian-release.jks \
  -PMERIDIAN_KEYSTORE_PASSWORD='...' -PMERIDIAN_KEY_ALIAS=meridian -PMERIDIAN_KEY_PASSWORD='...' \
  -PMERIDIAN_VERSION_CODE=1 -PMERIDIAN_VERSION_NAME=1.0.0
# → app/build/outputs/apk/release/app-release.apk        (sideload / internal testing)
# → app/build/outputs/bundle/release/app-release.aab     (Google Play upload)
# → app/build/outputs/mapping/release/mapping.txt        (keep: de-obfuscates crash reports)
```

Without the keystore properties the release artefacts are produced **unsigned**
(cannot be installed) and the build prints a warning. The build **fails** if the URL
is missing, not https, or points at localhost/127.0.0.1/10.0.2.2. Put the `MERIDIAN_*`
values in `~/.gradle/gradle.properties` (outside the repo) rather than on the command
line to keep them out of shell history. See `RELEASE.md`.

### 4c. Where artefacts land

| Artefact | Path |
|----------|------|
| Debug APK | `android/app/build/outputs/apk/debug/app-debug.apk` |
| Release APK | `android/app/build/outputs/apk/release/app-release.apk` (or `app-release-unsigned.apk` when unsigned) |
| Release AAB | `android/app/build/outputs/bundle/release/app-release.aab` |
| ProGuard/R8 mapping | `android/app/build/outputs/mapping/release/mapping.txt` |
| Lint | `android/app/build/reports/lint-results-debug.html` |
| Unit test reports | `android/app/build/reports/tests/testDebugUnitTest/index.html` |
| CI artefacts | GitHub Actions run > Artifacts: `meridian-attendance-debug-apk`, `-release-apk`, `-release-aab`, `-release-mapping` |

### 4d. Build caveat

The environment in which this repository was authored had no access to Google's
Maven repository, so the Gradle build was **not** executed there (`TOOLCHAIN.md`).
The first full build must be run in Android Studio or by the `Android` GitHub
workflow; fix any dependency-resolution or compile issue it reports before
distributing an APK.

## 5. Instrumented tests (optional)

```bash
cd android
./gradlew connectedDebugAndroidTest       # needs a running emulator/phone
```

## 6. Common setup problems

| Problem | Fix |
|---------|-----|
| `docker compose` says `POSTGRES_PASSWORD` not set | You ran the production file; use `-f deploy/docker-compose.dev.yml` for development |
| API logs "DATABASE_URL points at a development database" | `APP_ENV` is staging/production with dev settings — set `APP_ENV=development` locally |
| Phone cannot reach `http://<ip>:8000` | Same Wi-Fi? Workstation firewall? Use the debug build (release refuses http) |
| Gradle sync: "Could not resolve com.android.tools.build:gradle:8.13.2" | No internet / proxy; configure Gradle proxy in `~/.gradle/gradle.properties` |
| "google-services.json not found" warning | Expected without Firebase; see `RELEASE.md` to add it |
| Geofence never triggers on the emulator | Use an image with Google Play services; set location via Extended controls and wait for the dwell time; ensure "Allow all the time" location permission |

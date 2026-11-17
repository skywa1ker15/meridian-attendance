# Toolchain & pinned versions

## Android (gradle/libs.versions.toml is the single source of truth)

| Component | Version | Notes |
|-----------|---------|-------|
| Android Gradle Plugin | 8.13.2 | Requires Gradle ≥ 8.13; supports compileSdk 36 |
| Gradle (wrapper) | 8.14.3 | `gradle/wrapper/gradle-wrapper.properties` |
| JDK | 17 (CI) / 21 works | AGP 8.x needs ≥ 17 |
| Kotlin | 2.3.0 | with `org.jetbrains.kotlin.plugin.compose` 2.3.0 and `plugin.serialization` 2.3.0 |
| KSP | 2.3.4 | Room compiler |
| compileSdk / targetSdk | 36 | Google Play requires target 36 for new apps since 31 Aug 2026 |
| minSdk | 26 | Android 8.0+ |
| Compose BOM | 2025.12.00 | Compose 1.10.0, Material 3 1.4.0 |
| Activity Compose | 1.10.1 | |
| Navigation Compose | 2.9.0 | |
| Lifecycle (runtime/viewmodel compose) | 2.10.0 | |
| Core KTX | 1.16.0 | Core Splashscreen 1.0.1 |
| Room | 2.8.3 | runtime, ktx, compiler (ksp) |
| WorkManager | 2.10.0 | work-runtime-ktx |
| DataStore (preferences) | 1.2.0 | |
| Retrofit | 2.11.0 | + `com.jakewharton.retrofit:retrofit2-kotlinx-serialization-converter:1.0.0` |
| OkHttp | 4.12.0 | logging-interceptor in debug only |
| kotlinx-serialization-json | 1.8.0 | |
| kotlinx-coroutines | 1.10.1 | |
| Play Services Location | 21.3.0 | Geofencing API |
| Firebase BOM | 34.0.0 | firebase-messaging; `google-services` plugin 4.4.4 applied only if `app/google-services.json` exists |
| Play Integrity | 1.4.0 | optional signal |
| Test | junit 4.13.2, androidx.test core 1.6.1 / runner 1.6.2 / rules 1.6.1 / ext-junit 1.2.1, espresso 3.6.1, kotlinx-coroutines-test 1.10.1, turbine 1.2.0, robolectric 4.14.1 | |

## Backend
Python 3.12+ (3.13 used in development), FastAPI ≥ 0.115, SQLAlchemy 2.0, Alembic 1.14,
psycopg 3 (binary), Pydantic 2 + pydantic-settings, PyJWT, argon2-cffi, openpyxl,
reportlab, prometheus-client, google-auth + requests (optional FCM / Play Integrity),
uvicorn (gunicorn + uvicorn workers in production). PostgreSQL 16.

## Dashboard
No toolchain: static ES2020 modules served by FastAPI. Tested with Playwright + Chrome.

## Build environment caveat (this delivery)
The development sandbox used to produce this repository had **no access to
`dl.google.com` / `maven.google.com` / Maven Central**, so the Android APK/AAB could
not be compiled there. The Kotlin sources were syntax‑checked with `kotlinc 2.3.0`,
the pure‑Kotlin core logic was compiled and its tests executed, and the Gradle
build is executed by the GitHub Actions workflow `.github/workflows/android.yml`
(or locally with Android Studio) — see `docs/RELEASE.md`.

# Android app — build & verification record

> **The APK/AAB was NOT built in the development sandbox.** That environment had no access to
> `maven.google.com`, `dl.google.com` or Maven Central, so Gradle could not resolve the Android Gradle
> Plugin, AndroidX, Compose, Room, WorkManager, Play services, Firebase, Retrofit or OkHttp. Everything below
> states exactly what *was* checked, how, and what still has to be proven by the first real Gradle build
> (CI or Android Studio).

## 1. What was verified, and how

| Check | Tool | Result | What it proves |
|-------|------|--------|----------------|
| Pure-Kotlin core compiles with **warnings as errors** | `android/tools/core-check` (kotlinc 2.3.0) | ✅ | `za.co.meridiangroup.attendance.core` (12 files) has no Android/library dependency and no type errors. |
| DTO layer compiles with the real **kotlinx-serialization compiler plugin** | same | ✅ | `data/remote/dto/*` + `data/remote/ApiJson.kt` are valid `@Serializable` classes (runtime 1.6.2 from the local Gradle distribution; the app pins 1.8.0 — same APIs). |
| **60 unit tests executed** | same (reflection runner, `kotlin.test` asserts) | ✅ 60/60 | See list below. |
| Whole-app static check (main + test + androidTest, 87 files) | `android/tools/syntax-check` | ✅ | No syntax errors; no duplicate declarations; every unqualified reference to a project symbol resolves; every member access on a project object (e.g. `JohannesburgTime.x`) resolves; every project `import` resolves; Android-free files error-free. |
| XML well-formedness (manifest + 15 resource files) | syntax-check | ✅ | Manifest and resources parse. |
| Resource & manifest cross-references | syntax-check | ✅ | Every `R.string/drawable/color/mipmap/xml` used in Kotlin (289 strings) exists; every `@string/@xml/@drawable/@mipmap/@color/@style` in XML exists; every manifest component class exists. |
| The static check really detects errors | manual self-test | ✅ | Injected a syntax error, a duplicate parameter and a misspelt `JohannesburgTime.workDateLng` call: all three were reported and the script exited 1. |

### Tests executed by `core-check`

* `VerificationTest` (8) — haversine distance, radius + accuracy rule, Wi-Fi SSID/BSSID matching and
  normalisation (`"<unknown ssid>"`, `02:00:00:00:00:00`), `wifi_required`, score, "likely review" prediction.
* `ExitDebounceTest` (9) — debounce not elapsed, DWELL after EXIT cancels, no fix / stale fix / poor accuracy
  never conclude a departure, drift back inside, accuracy margin, clear departure, re-check delay floor.
* `OutboxRulesTest` (9) — ordering by `created_at`/id, confirmation never before its event (even with clock
  skew), duplicate idempotency keys dropped, batch vs individual split, backoff, retry vs dead decisions for
  network/401/403/409/429/5xx, `device_pending_approval`, max attempts.
* `TimeAndWorkedTimeTest` (11) — parsing all API timestamp shapes, Africa/Johannesburg formatting, work date
  across midnight UTC, ISO output with `Z`, week/month bounds, worked-minutes formatting, hours ring, live minutes,
  expiry countdown.
* `ApiErrorsValidationEnumsTest` (13) — logout codes (`token_reused`, `device_revoked`, …), user messages incl.
  `Retry-After`, fallbacks, identifier/password/phone/reason/manual-request/profile-correction validation, enum
  fallbacks, DESIGN.md status colour mapping, permission gating, sync chip.
* `DtoContractTest` (10) — DTOs against the JSON examples in `docs/API.md`: login request/response, event body key
  set, today summary + session + event, sync request/response, confirm + manual-request bodies, workplaces incl.
  `coordinates_confirmed`, policy defaults, pagination `hasMore`, error envelope with `details.fields`.

### How `syntax-check` classifies compiler output (honest limits)

The whole app is compiled with kotlinc, with the kotlinx-serialization runtime + plugin and kotlinx-coroutines
on the classpath (the only libraries available offline). All other libraries are missing, so kotlinc reports
~3 800 `unresolved reference` errors for them. The script:

* **fails** on errors in the Android-free sources, syntax errors, duplicate declarations, unresolved *project*
  symbols/imports, and unresolved members of *project* objects;
* **allows** unresolved names that are imported from a library or are not project names;
* **reports, without failing,** ~550 follow-on errors (`cannot infer type`, `argument type mismatch`,
  "suspend function can only be called from a coroutine" inside `viewModelScope.launch {}` whose `launch` is
  unresolved, etc.) in `build/syntax-check/cascades.txt`. These were reviewed by hand during this delivery and
  every category traced back to a missing library type, but **they cannot be machine-distinguished from genuine
  type errors** until the real libraries are on the classpath.

## 2. What was NOT verified (must be proven by the first Gradle build)

* Gradle configuration, dependency resolution and the version catalogue (AGP 8.13.2, Kotlin 2.3.0, KSP 2.3.4,
  Compose BOM 2025.12.00, …) — not executed.
* Type-checking of everything that uses Android/AndroidX/Compose/Room/WorkManager/Play services/Firebase/
  Retrofit/OkHttp (data layer, detection, notifications, UI). Signatures were written against the documented
  APIs of the pinned versions but not compiled against them.
* Room/KSP: entity/DAO code generation and **compile-time SQL validation of the DAO queries**; schema export to
  `app/schemas/` (first build creates `1.json` — commit it).
* Compose compiler, manifest merger, resource linking (AAPT2), R8/ProGuard rules, lint.
* JVM tests needing libraries: `TokenAuthenticatorTest` (MockWebServer: single-flight refresh, retry, logout on
  `token_reused`), `ApiCallerTest`, `OutboxDaoTest` (Robolectric + Room), `DeepLinksTest` (Robolectric) — written,
  not run.
* Instrumented tests: `LoginScreenTest`, `OnboardingScreenTest`, `ConfirmationFlowTest` (fake API, in-memory
  Room) — written for CI, not run.
* Behaviour on real devices: geofence delivery latency, Doze/App Standby, OEM battery managers, Wi-Fi SSID
  access on Android 13+, notification permission flows, splash screen, dark theme.

## 3. Building for real

```bash
cd android
tools/core-check && tools/syntax-check     # optional offline sanity checks
./gradlew :app:testDebugUnitTest                            # JVM tests (core, DTO, auth, Room via Robolectric)
./gradlew :app:connectedDebugAndroidTest                    # instrumented tests (emulator/device)
./gradlew :app:assembleDebug -PMERIDIAN_DEBUG_API_BASE_URL=http://192.168.1.50:8000
./gradlew :app:bundleRelease -PMERIDIAN_API_BASE_URL=https://attendance.example.co.za
```

* **API URL** — `BuildConfig.API_BASE_URL`; the app appends `api/v1/`. Debug default `http://10.0.2.2:8000`
  (emulator → host); cleartext is allowed **only** in debug (`src/debug/res/xml/network_security_config.xml`).
  Release requires `MERIDIAN_API_BASE_URL` to be `https://` and not localhost/127.0.0.1/10.0.2.2: the build fails
  as soon as a release task is in the task graph (`app/build.gradle.kts`, verified by reading — not executed).
  Note that `./gradlew build` includes release tasks, so it also needs the URL.
* **Signing** — read only from `-P` properties, an uncommitted `android/keystore.properties`, or environment
  variables: `MERIDIAN_KEYSTORE_PATH`, `MERIDIAN_KEYSTORE_PASSWORD`, `MERIDIAN_KEY_ALIAS`, `MERIDIAN_KEY_PASSWORD`.
  `keystore.properties`, `*.jks` and `*.keystore` are git-ignored. Without them the release is built unsigned with
  a warning. Keep the upload key in the company secret store; enable Play App Signing.
* **Versioning** — `MERIDIAN_VERSION_CODE` / `MERIDIAN_VERSION_NAME` (gradle.properties defaults 1 / 1.0.0).
* **Firebase** — put the company's `app/google-services.json` in place (never committed); the plugin is applied
  only when it exists. Without it the app works and relies on polling (on open, every 15 min, after actions).
* **Certificate pinning** — hook in `ApiClientFactory.certificatePinner` (disabled). Add the production SPKI pins
  plus a backup pin before enabling, and rotate pins before certificates.
* **Branding** — the launcher/splash icon is a **neutral placeholder monogram "M" on navy**, not the Meridian
  Group logo (which was not supplied). Replace `res/drawable/ic_launcher_foreground.xml`,
  `ic_launcher_monochrome.xml`, `ic_splash.xml` and `ic_stat_attendance.xml` with official artwork before release.

## 4. Known gaps / decisions to confirm

* Manage screens are verified by CI (`assembleDebug`, unit tests, lint), by the DTO contract tests and by
  `tools/contract-roundtrip` against a live backend (52 checks: overview, live, review queue, exceptions,
  departments, devices, roles, audit, admin sessions and detail, manual requests, report preview, the employee
  directory including create/edit/role/reset/deactivate/reactivate, and workplace/policy PATCH bodies built by
  `WorkplacePayload.diff` / `PolicyPayload.diff`). Not yet verified: instrumented UI tests of these screens and
  a run on a physical phone.

* `coordinates_confirmed` is read from `/config/workplaces` but is not listed in `docs/API.md` §3. The app never
  registers geofences when it is `false`; if the backend omits it, the app treats coordinates as confirmed only
  when they are valid and non-zero. Backend + API.md should add the field.
* `confirmation_reminder_minutes` (local re-notification of pending confirmations) is not in the §8 policy
  object; the app defaults to 15 minutes when absent.
* The app is now standalone for administrators too: the **Manage** section covers every `docs/API.md` §7
  endpoint (overview, employees, departments, devices, records and corrections, review queue, manual requests,
  exceptions, reports with CSV/XLSX/PDF export, workplaces, policy, roles, audit log, broadcast) plus §0
  first-run setup. The web dashboard remains available and is unchanged.
* The backend address is a user setting (login screen → **Server**, also shown in Profile), persisted in
  DataStore and applied by an OkHttp interceptor, so Retrofit is built once; release builds refuse `http://`.
* Report and POPIA downloads are written to `cacheDir/exports/` and shared through the manifest's FileProvider
  (`${applicationId}.fileprovider`, `res/xml/file_paths.xml`).
* Battery optimisation: the app opens the system list (`ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS`) rather
  than requesting `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, which Google Play restricts.
* Play Integrity is a declared dependency and `POST /devices/me/integrity` is in the API interface, but no
  integrity token is requested yet (the backend marks it optional / `not_configured`).

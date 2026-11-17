# Release guide — Android app

Package name: `za.co.meridiangroup.attendance` · minSdk 26 · targetSdk/compileSdk 36.

**Status**: no signed release has been produced, and nothing has been uploaded to
Google Play. The release pipeline (`.github/workflows/android.yml`) and the
build-time safety checks (`android/app/build.gradle.kts`) are ready. They need
Meridian's keystore, the production API URL and (for Play) a Play Console account.
**Do not publish publicly without explicit written approval from Meridian Group.**

## 1. Versioning

| Field | Source | Rule |
|-------|--------|------|
| `versionName` | `MERIDIAN_VERSION_NAME` (gradle property or env), default `1.0.0` in `android/gradle.properties` | Semantic versioning `MAJOR.MINOR.PATCH`. Bump MAJOR for breaking API changes, MINOR for features, PATCH for fixes. |
| `versionCode` | `MERIDIAN_VERSION_CODE`, default `1` | Positive integer that **must increase** with every upload to Play. CI uses the workflow run number unless overridden. Recommended: `MAJOR*10000 + MINOR*100 + PATCH` (1.0.0 → 10000) for traceability. |
| Git tag | `v1.0.0` | Pushing a tag triggers the release job (and the backend image build). |
| Debug suffix | `-debug` | Debug builds are named `1.0.0-debug`. |

Release order when the API changes: deploy the backend first (it must stay
backward compatible with the oldest installed app), then roll out the app.

## 2. Signing

### 2.1 Generate the upload keystore (on Meridian's side only)

The development team never generates, receives, or commits Meridian's signing key.
An authorised Meridian person runs, on a trusted machine:

```bash
keytool -genkeypair -v \
  -keystore meridian-release.jks -storetype PKCS12 \
  -alias meridian-upload \
  -keyalg RSA -keysize 4096 -validity 10000 \
  -dname "CN=Meridian Group, OU=IT, O=Meridian Group, L=Umhlanga, ST=KwaZulu-Natal, C=ZA"
# Enter a strong store password (PKCS12 uses the same password for the key).

keytool -list -v -keystore meridian-release.jks -alias meridian-upload   # record SHA-1 / SHA-256 fingerprints
```

- Store `meridian-release.jks` and its password in the company password manager,
  plus an offline encrypted copy (for example in a safe). **If you lose it without
  Play App Signing, you cannot update the app.**
- Use **Play App Signing** (the default for new apps): Google holds the app-signing
  key, and this keystore becomes the *upload* key. If the upload key is lost, Google
  can reset it after a support request.
- For sideloaded APKs outside Play, the APK is signed with this key directly. All
  future sideloaded updates must use the same key.
- Register the SHA-256 fingerprint in Firebase (Project settings > Your apps) if you
  use FCM, and in the Play Integrity configuration.

### 2.2 Local signed build

The Gradle build reads signing values from gradle properties or environment
variables (`app/build.gradle.kts`):

```properties
# ~/.gradle/gradle.properties  (user home, NOT the repository)
MERIDIAN_KEYSTORE_PATH=/secure/path/meridian-release.jks
MERIDIAN_KEYSTORE_PASSWORD=********
MERIDIAN_KEY_ALIAS=meridian-upload
MERIDIAN_KEY_PASSWORD=********
MERIDIAN_API_BASE_URL=https://attendance.<domain>
```

```bash
cd android
./gradlew assembleRelease bundleRelease -PMERIDIAN_VERSION_CODE=10000 -PMERIDIAN_VERSION_NAME=1.0.0
"$ANDROID_HOME/build-tools/36.0.0/apksigner" verify --print-certs app/build/outputs/apk/release/app-release.apk
```

**`keystore.properties` format.** The CI job also writes this file (git-ignored)
for tools that expect the conventional format. The Gradle build itself uses the
`MERIDIAN_*` values above.

```properties
storeFile=/absolute/path/keystore.jks
storePassword=********
keyAlias=meridian-upload
keyPassword=********
```

Safety checks built into the release build:
- The build fails unless `MERIDIAN_API_BASE_URL` is `https://` and not localhost,
  127.0.0.1, 10.0.2.2 or 0.0.0.0. You cannot ship an APK that points at a
  developer's machine.
- Without complete signing values, the artefacts are **unsigned** and a warning is
  printed. Unsigned artefacts cannot be installed or uploaded.

### 2.3 CI signed build

Add these repository secrets (GitHub > Settings > Secrets and variables > Actions):

| Secret | Value |
|--------|-------|
| `API_BASE_URL` | `https://attendance.<domain>` |
| `ANDROID_KEYSTORE_BASE64` | `base64 -w0 meridian-release.jks` (macOS: `base64 -i meridian-release.jks`) |
| `ANDROID_KEYSTORE_PASSWORD` | store password |
| `ANDROID_KEY_ALIAS` | `meridian-upload` |
| `ANDROID_KEY_PASSWORD` | key password |
| `GOOGLE_SERVICES_JSON` (optional) | contents of `google-services.json` |
| `PLAY_SERVICE_ACCOUNT_JSON` (optional) | Play Console API service account key (§4.3) |

Push a tag (`git tag v1.0.0 && git push origin v1.0.0`) or run the workflow
manually (Actions > Android > Run workflow). Artefacts: `meridian-attendance-release-apk`,
`-release-aab` and `-release-mapping`. The job decodes the keystore on the runner
and deletes it at the end. Limit who can run workflows and read secrets, and
protect tags with a rule.

Keep the `mapping.txt` for every release. Play needs it to de-obfuscate crash
reports.

## 3. Firebase (push notifications, optional)

1. Create a Firebase project in the Firebase console and add an Android app with
   package `za.co.meridiangroup.attendance`. Add the SHA-256 fingerprints of the
   upload key and of the Play app-signing key (from Play Console > App integrity).
2. Download `google-services.json`, then put it at `android/app/google-services.json`
   (local) or in the `GOOGLE_SERVICES_JSON` secret (CI). It is never committed.
3. Google Cloud console > IAM > Service accounts: create a service account with the
   role *Firebase Cloud Messaging API Admin* and create a JSON key. Save it on the
   server as `deploy/secrets/fcm-service-account.json`, then set `FCM_PROJECT_ID`
   and `FCM_ENABLED=true`.

## 4. Distribution channels

### 4.1 Sideloaded release APK (pilot / no Play account)
Share the signed `app-release.apk` and its SHA-256 (`sha256sum`) through an
internal channel or MDM. Users allow "Install unknown apps" for the app they open
the file with. Updates are manual: install the new APK over the old one (same
signing key, higher versionCode). Suitable for the pilot.

### 4.2 Play Console setup
1. Create a Google Play developer account of type **Organisation** in Meridian's
   name. This needs a D-U-N-S number and verification, and costs a one-off USD 25.
   Organisation accounts are not subject to the closed-testing requirement that
   applies to new personal accounts.
2. Create app: name "Meridian Attendance", default language English (South Africa
   or United Kingdom), type App, free.
3. Complete **App content**: privacy policy URL (§7), app access (provide a test
   employee account for review), ads (none), content rating questionnaire, target
   audience (18+), data safety (§6), government app (no), and **Location
   permissions declaration** for background location (§6.2).
4. Enrol in Play App Signing and upload the first AAB.

### 4.3 Internal testing track
- Play Console > Testing > Internal testing > Create release > upload
  `app-release.aab` > release notes > Review > Roll out.
- Testers: create an e-mail list (up to 100), then share the opt-in link. Internal
  test builds are available within minutes and do not need full review.
- Automated upload: in Play Console > Users and permissions, invite a Google Cloud
  service account with "Release to testing tracks" permission. Store its JSON key
  in the `PLAY_SERVICE_ACCOUNT_JSON` secret, then run the Android workflow manually
  with *upload_to_play_internal* ticked (uses `r0adkll/upload-google-play`,
  track `internal`).

### 4.4 Managed Google Play (enterprise, recommended for company phones)
- Meridian needs an EMM/MDM bound to Managed Google Play (Microsoft Intune, Google
  Endpoint Management, Samsung Knox Manage, VMware Workspace ONE, and so on).
- Publish as a **private app**: Play Console > the app > Release > Setup > Advanced
  settings > **Managed Google Play** > add Meridian's organisation ID. Alternatively,
  publish from the EMM console's "private apps" page. Private apps are visible only
  to Meridian's organisation.
- In the MDM: assign the app (required install), and use managed configuration or
  policy to **pre-grant location ("Allow all the time") and notification
  permissions** where the MDM supports permission policies on fully managed
  devices. Also exempt the app from battery optimisation. This removes most
  manual setup on phones.
- BYOD with a work profile: the app runs in the work profile. Location permissions
  must still be granted by the user.

### 4.5 Production track (public)
Not planned. A public listing requires Meridian's explicit approval. A public app
that uses background location for employee attendance gets extra policy scrutiny;
the private / internal routes avoid public exposure.

## 5. Release notes v1.0.0

```
Meridian Attendance 1.0.0 — first release
• Automatic arrival and departure detection at the registered Meridian workplace (geofencing; no continuous tracking)
• Confirm or dismiss detected arrivals and departures; nothing is recorded without your confirmation or a manager's review
• Works offline: confirmations are queued and synchronised automatically
• Dashboard with today's status, clock-in/out times and hours worked
• Attendance history and details; manual requests for missed or incorrect times and profile corrections
• Notifications for confirmations, approvals and rejections
• Guided permission setup, including manufacturer battery settings
```

Short Play "What's new" (≤ 500 chars): "First release of Meridian Attendance: confirm
your arrival and departure at the office, view your history and submit corrections.
Works offline."

## 6. Data safety form

`docs/android/PERMISSIONS.md` (owned by the Android team) was **not present** when
this guide was written. The answers below come from the data inventory in
`PRIVACY-POPIA.md` §3 and `API.md`. **Integrator: reconcile them with the final
manifest and `docs/android/PERMISSIONS.md`.**

### 6.1 Answers

| Question | Answer |
|----------|--------|
| Does the app collect or share any of the required user data types? | Yes |
| Is all collected data encrypted in transit? | Yes (HTTPS only in release) |
| Can users request that data be deleted? | Yes. On request to Meridian (Information Officer); describe the procedure in the privacy policy. |
| Data shared with third parties? | No. Google FCM and the hosting provider act as service providers / operators, which Play does not count as "sharing". |

| Data type | Collected | Optional? | Purpose (Play categories) |
|-----------|-----------|-----------|---------------------------|
| Location — **Precise location** | Yes | Required for automatic detection (manual alternative exists) | App functionality |
| Location — Approximate location | Yes (implied by precise) | Same | App functionality |
| Personal info — Name | Yes | Required | App functionality, Account management |
| Personal info — Email address | Yes | Required | Account management |
| Personal info — Phone number | Yes, if entered | Optional | Account management |
| Personal info — User IDs (employee number) | Yes | Required | Account management, App functionality |
| App activity — Other user-generated content (manual request reasons, notes) | Yes | Optional | App functionality |
| App info and performance — Diagnostics (battery level, app version) | Yes | Required | App functionality, Analytics (troubleshooting) |
| Device or other IDs (installation id, FCM token) | Yes | Required | App functionality, Fraud prevention / security |
| Wi-Fi network name/BSSID | Not a separate Play category; covered under precise location if declared | Optional | App functionality |
| Photos, contacts, messages, calendar, files, health, financial, web history | No | — | — |

### 6.2 Background location declaration (Play Console > App content > Location permissions)
- Feature: "Automatically detects arrival at and departure from the employer's
  registered workplace so the employee can confirm attendance."
- Why background access is needed: geofence transitions occur while the app is not
  open, and the employee is then asked to confirm.
- Video: a ≤ 30-second screen recording showing the prominent disclosure screen,
  the permission prompt, a geofence notification and the confirmation screen.
  Record it on a test device during the pilot.
- **Prominent disclosure**: the onboarding must show the in-app disclosure before
  requesting background location. The text must mention location, that it is
  collected when the app is closed, and why.

## 7. Privacy policy requirements

Play requires a public URL (it does not have to be listed publicly elsewhere).
The policy must:
- Identify Meridian Group as the responsible party, with contact details and the
  Information Officer.
- List the data collected (`PRIVACY-POPIA.md` §3), including **background
  location**, and when it is collected.
- State the purposes, retention periods, who has access and which operators are
  used (Google FCM, hosting).
- Explain the rights to access, correction, deletion and objection, and how to
  exercise them. Mention the right to complain to the Information Regulator.
- Describe the security measures in general terms.
- Be consistent with the Data safety answers.

Host it, for example, at `https://attendance.<domain>/privacy` or on Meridian's
website. The wording must come from the approved notice in `PRIVACY-POPIA.md` §5
after legal review.

## 8. Store listing (for Play, including internal and private)

| Item | Content / requirement |
|------|----------------------|
| App name (≤ 30) | Meridian Attendance |
| Short description (≤ 80) | Confirm your arrival and departure at Meridian Group workplaces. |
| Full description (≤ 4000) | See below |
| App icon | 512 × 512 PNG, 32-bit, ≤ 1 MB, **from the official logo** |
| Feature graphic | 1024 × 500 PNG/JPEG, no transparency |
| Phone screenshots | 2–8, 16:9 or 9:16, min side 320 px, max 3840 px. Suggested: dashboard, arrival confirmation, history, manual request, permissions onboarding. Use demo data only. |
| Category | Business |
| Contact e-mail | Meridian IT support address |
| Privacy policy | URL from §7 |

Full description draft:

```
Meridian Attendance is the official attendance app for Meridian Group employees.

When you arrive at or leave a registered Meridian workplace, the app asks you to confirm. Your attendance is recorded only after you confirm, and you can see your own history at any time.

• Arrival and departure detection using Android geofencing — no continuous tracking
• Confirm or dismiss each detection
• Works without signal: confirmations are sent when you are back online
• Today's status, clock-in and clock-out times and hours worked
• Attendance history and details
• Request corrections for missed or incorrect times
• Notifications when your requests are reviewed

This app is for Meridian Group employees only. An account issued by Meridian Group is required.
```

## 9. Branding replacement (official logo not supplied)

The repository uses a neutral monogram placeholder (`DESIGN.md`). When Meridian
supplies the official logo (SVG preferred, or a 1024 × 1024 PNG with a transparent
background, plus brand colours if they differ):

1. **Android launcher icon**: Android Studio > `app` > New > Image Asset >
   Launcher Icons (Adaptive and Legacy). Foreground = logo (keep it inside the
   66 dp safe zone), background = `navy-700` or white. Also create a monochrome
   layer (Android 13 themed icons). This regenerates `res/mipmap-*` and
   `mipmap-anydpi-v26/ic_launcher*.xml`.
2. **Splash screen** (core-splashscreen): replace the drawable referenced by
   `windowSplashScreenAnimatedIcon` in `res/values/themes.xml` (and
   `values-night`) with a vector of the logo. The icon fits in a 240 dp circle
   (160 dp visible).
3. **Notification icon**: a white-on-transparent single-colour vector (24 dp).
   Do not use the full-colour logo.
4. **In-app logo** (login, splash): replace the placeholder vector drawable used by
   the login screen. Search `res/drawable` for the monogram.
5. **Colours**: if the brand palette differs, update the tokens in `DESIGN.md`, the
   Compose theme (`ui/theme`) and the dashboard CSS variables
   (`backend/app/static/admin/css/app.css`).
6. **Dashboard**: replace the monogram in `backend/app/static/admin` (favicon and
   header logo) with the SVG.
7. **Store assets**: 512 px icon and feature graphic (§8).
8. Rebuild, check light and dark themes, check that contrast is ≥ 4.5:1, and bump
   versionCode.

The exact resource file names belong to the Android and dashboard teams. Confirm
them in `docs/android/` and `docs/admin-dashboard/`.

## 10. Release checklist

- [ ] Backend for this version deployed and `/readyz` OK
- [ ] `MERIDIAN_VERSION_CODE` increased, `MERIDIAN_VERSION_NAME` set, tag created
- [ ] `API_BASE_URL` points at production (https)
- [ ] Signed with Meridian's key, and `apksigner verify` OK
- [ ] `google-services.json` from the production Firebase project (if FCM is used)
- [ ] Unit tests and lint green in CI
- [ ] Smoke test on at least two physical devices (login, confirm arrival, sync)
- [ ] `mapping.txt` archived with the release
- [ ] Release notes written
- [ ] Data safety form, privacy policy and permission declaration up to date
- [ ] Meridian approval for the target track recorded

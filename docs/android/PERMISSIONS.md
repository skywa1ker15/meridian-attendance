# Android permissions, purpose and Play Data Safety

All permissions are declared in `android/app/src/main/AndroidManifest.xml`. Runtime permissions are requested
step by step in the onboarding screen (`ui/onboarding/OnboardingScreen.kt`), each with a plain-language
explanation; the state is re-checked on every resume, so a permission revoked in Settings re-appears as a
dashboard banner ("Permissions needed → Fix permissions") and in Profile → Permissions.

| Permission | Type | Why the app needs it | If denied |
|------------|------|----------------------|-----------|
| `INTERNET` | normal | Talk to the Meridian backend over HTTPS. | App cannot work. |
| `ACCESS_NETWORK_STATE` | normal | Online/offline sync chip; trigger sync when connectivity returns. | Sync still runs via WorkManager. |
| `ACCESS_WIFI_STATE` | normal | Know whether Wi-Fi is on; read the connected network (with location/nearby permission). | No Wi-Fi signal. |
| `ACCESS_COARSE_LOCATION` | runtime | Required by Android alongside fine location (user may pick "approximate"). | — |
| `ACCESS_FINE_LOCATION` | runtime | Decide whether the phone is inside the workplace boundary (~150 m) at arrival/departure; attach location + accuracy to the detection. Approximate location is explicitly flagged as insufficient. | No automatic detection; manual requests only. |
| `ACCESS_BACKGROUND_LOCATION` | runtime (API 29+; via Settings on API 30+) | Geofence transitions happen while the app is closed. The app is only woken at boundary crossings; the route is never tracked. | No automatic detection. |
| `NEARBY_WIFI_DEVICES` (API 33+, **without** `neverForLocation`) | runtime | Read the SSID/BSSID of the *connected* network as extra evidence when the workplace has Wi-Fi verification enabled. Not "never for location" because the SSID is correlated with the workplace location. No scanning of other networks. | Wi-Fi shown as "Not checked"; events may need manager review if `wifi_required`. |
| `POST_NOTIFICATIONS` (API 33+) | runtime | Confirmation prompts, reminders, request reviews. | Pending confirmations are still shown in-app (dashboard card, inbox). |
| `RECEIVE_BOOT_COMPLETED` | normal | Re-register geofences and periodic sync after a reboot (geofences are cleared by reboots). | Detection resumes only when the app is next opened. |
| `FOREGROUND_SERVICE` | normal | Used only by WorkManager on Android < 12 to run the expedited detection worker briefly as a foreground service (no location type, no persistent service). | — |

Not requested: `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` (Play-restricted) — instead the battery-optimisation step
opens the system list with an explanation; `USE_FULL_SCREEN_INTENT`; `CAMERA`; contacts; storage; advertising ID
(Firebase Analytics collection is disabled in the manifest).

## Play Console "Data safety" — entries implied

| Data type (Play category) | Collected | Shared | Purpose | Optional? | Notes |
|---------------------------|-----------|--------|---------|-----------|-------|
| Location → **Precise location** | Yes | No | App functionality (attendance), Fraud prevention | Required for automatic detection; manual requests work without it | Sent only with a detection/confirmation; retained per `retention_days_location`, then pseudonymised. |
| Location → Approximate location | Yes (implied by precise) | No | App functionality | as above | |
| Personal info → Name, Email address, Phone number, Other info (employee number, department) | Yes | No | Account management, App functionality | No (employer account) | Phone editable; corrections via profile_correction requests. |
| App activity → Other user-generated content (notes, request reasons, dismissal reasons) | Yes | No | App functionality | Yes | |
| App info and performance → none | No | — | — | — | No analytics/crash SDK included. |
| Device or other IDs | Yes (installation UUID, FCM token, device model/OS/app version) | No | App functionality, Fraud prevention (one active device per employee) | No | Installation ID is random per install, not a hardware ID. |
| Wi-Fi network name/BSSID | Declared under **Location** (Play treats it as location-correlated) | No | Fraud prevention | Yes | Only when the workplace enables Wi-Fi verification. |

Security practices to declare: data encrypted in transit (TLS only, cleartext disabled in release);
users can request deletion/access through the employer (POPIA requests handled by Meridian HR / Information
Officer; backend supports export and pseudonymisation); tokens encrypted at rest with an Android Keystore
AES-GCM key and excluded from backups.

Because the app requests background location, Google Play requires the **background location declaration form**
and a video showing the prominent disclosure (onboarding step "Location all the time") and the feature
(arrival detection → confirmation notification). The app is intended for **managed/internal distribution** to
Meridian employees; the store listing and privacy policy URL must state that it is an employee attendance tool.

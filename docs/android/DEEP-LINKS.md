# Deep links: invitation and password-reset links

HR invites employees; nobody is handed a temporary password any more. The invitation email contains a
link that must open **this app** on the employee's phone, and the same mechanism is used for password
resets. This document explains how the app registers for those links, why they cannot be verified
Android App Links today, and what Meridian must do to make them verified later.

## The links

| Purpose | Link in the email | Fallback the web page fires |
| --- | --- | --- |
| Invitation | `https://<server>/app/invite?token=…` | `meridian://invite?token=…` |
| Password reset | `https://<server>/app/reset?token=…` | `meridian://reset?token=…` |

The backend serves a small web page at `/app/invite` and `/app/reset`. That page carries an
**"Open in the app"** button that navigates to the `meridian://` link, which always reaches the app
because a custom scheme needs no verification. The page is also the fallback for someone who opens
the email on a laptop.

Both forms are parsed by `core/AppLinks.kt` (pure Kotlin, unit-tested in
`app/src/test/.../core/ActivationValidationTest.kt`), and handled in `ui/navigation/AppNavigation.kt`
before the login screen is shown. The app's older notification scheme `meridianattendance://` is
accepted for these links too.

## Why the http(s) filter is not verified (`autoVerify="false"`)

Verified App Links bind the app to **one named host**: Android fetches
`https://<host>/.well-known/assetlinks.json` at install time and compares the signing fingerprint in
it with the APK's. Meridian's server address is a **setting** — each deployment points the app at its
own host (`ServerUrls`, the "Server" row on the login screen), and the pilot, staging and production
servers differ. A host that is unknown at build time cannot be listed in the manifest, so
`autoVerify="true"` would simply fail verification and the filter would be ignored.

The manifest therefore registers two filters (`app/src/main/AndroidManifest.xml`):

1. **`meridian://invite` / `meridian://reset`** — a custom scheme, always delivered to the app, with
   no verification and no chooser. This is what the web page's button uses, so there is always a path
   that works.
2. **`https`/`http` with `android:host="*"` and `pathPrefix="/app/invite"`, `/app/reset"`, and
   `android:autoVerify="false"`** — tapping the emailed link shows the Android chooser ("Open with:
   Chrome / Meridian Attendance"). The wildcard host is required: Android ignores `pathPrefix` when a
   filter does not specify a host, and a filter without a host would offer the app for *every* web
   link.

Consequence for users: the emailed link may open the browser first. The web page then tells them to
tap "Open in the app". Once Android has learned the preference ("Always"), the link opens the app
directly.

## Picking up the server from the link

A brand-new install does not know which server to talk to, and employees must never have to type an
address. When a link arrives (`AppRoot` in `ui/navigation/AppNavigation.kt`):

* the base URL is derived from the link's scheme, host, port and any path prefix in front of
  `/app/invite` (so `https://example.co.za/meridian/app/invite?token=…` yields the root
  `https://example.co.za/meridian/`);
* **no server configured and nobody signed in** → that server is stored and used immediately;
* **the link matches the configured server** → nothing changes;
* **a different server is configured** → a dialog names both servers and asks before switching; a
  switch signs the current session out, because tokens belong to one server;
* **custom-scheme link** (`meridian://…`, no host) → the configured server is used as-is.

`http://` links only yield a server in builds that allow cleartext (debug/LAN testing); release
builds ignore the address and keep the configured https server.

## Enabling true verified App Links later

When Meridian settles on a fixed public host (for example `attendance.meridiangroup.co.za`):

1. **Publish the Digital Asset Links file.** The backend serves
   `https://<host>/.well-known/assetlinks.json` from a setting (no app change needed), with
   `Content-Type: application/json`, over https, with no redirect:

   ```json
   [{
     "relation": ["delegate_permission/common.handle_all_urls"],
     "target": {
       "namespace": "android_app",
       "package_name": "za.co.meridiangroup.attendance",
       "sha256_cert_fingerprints": ["<SHA-256 of the release signing certificate>"]
     }
   }]
   ```

   The fingerprint is the **release** one (`keytool -list -v -keystore <release.jks>`, or the Play
   app-signing certificate from Play Console → Setup → App integrity when Play signs the app). Both
   can be listed if the app is also installed from a locally signed APK.

2. **Add a host-specific, verified filter to the manifest**, keeping the two existing filters so that
   other deployments and the custom scheme keep working:

   ```xml
   <intent-filter android:autoVerify="true">
       <action android:name="android.intent.action.VIEW" />
       <category android:name="android.intent.category.DEFAULT" />
       <category android:name="android.intent.category.BROWSABLE" />
       <data android:scheme="https" android:host="attendance.meridiangroup.co.za"
             android:pathPrefix="/app/invite" />
       <data android:scheme="https" android:host="attendance.meridiangroup.co.za"
             android:pathPrefix="/app/reset" />
   </intent-filter>
   ```

3. **Verify after release:** install the signed build and run
   `adb shell pm get-app-links za.co.meridiangroup.attendance` — the host should report `verified`.
   `adb shell am start -a android.intent.action.VIEW -d "https://<host>/app/invite?token=test"`
   should open the app without a chooser.

Until that is done, the custom-scheme button is the reliable route and is what the invitation email
and web page should keep pointing people at.

## Testing the links by hand

```bash
# invitation, custom scheme (always works)
adb shell am start -a android.intent.action.VIEW -d "meridian://invite?token=TOKEN"
# invitation, emailed https link (chooser)
adb shell am start -a android.intent.action.VIEW -d "https://attendance.example.co.za/app/invite?token=TOKEN"
# password reset
adb shell am start -a android.intent.action.VIEW -d "meridian://reset?token=TOKEN"
```

An invalid or expired token shows "This invitation link is no longer valid — ask your manager to send
a new one" (invitation) or the "Request a new link" action (reset); neither signs a logged-in
administrator out.

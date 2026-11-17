# Meridian Attendance — Employee guide

The app notices when you arrive at and leave the Meridian office in Umhlanga and
asks you to **confirm**. Your attendance is only recorded after you confirm. You can
always see your own history and correct mistakes.

**What the app does not do:** it does not follow you during the day. It does not
track your location outside the arrival and departure checks. It cannot read your
messages, photos, contacts or browsing. See "Your privacy" below.

## 1. Install

Your IT department will tell you which of these applies:
- **Google Play** (internal testing link or your work profile's Play Store): open the
  link or search the managed Play Store for *Meridian Attendance*, then tap
  **Install**.
- **APK file**: open the file IT sends you. If Android asks, allow your browser or
  Files app to "install unknown apps". If Play Protect asks to scan the app, allow
  the scan, then tap **Install**. Only install files that come from Meridian IT.

You need Android 8.0 or newer with Google Play services.

## 2. Sign in

1. Open **Meridian Attendance**.
2. Enter your **employee number** (or work e-mail) and the **temporary password**
   from your administrator.
3. Choose a new password: at least 10 characters, not containing your employee
   number or e-mail, and not a common password.
4. This phone is now registered as your attendance device. If you sign in on a new
   phone later, the old phone is signed out automatically, or your administrator
   approves the new phone first.

Forgot your password? Tap **Forgot password**. If e-mail is set up you will receive
a reset link that is valid for 30 minutes. Otherwise ask your administrator for a
new temporary password.

## 3. Allow the permissions (one-time setup)

The setup screen shows each item with a green tick when it is done.

| Permission | Why | What to choose |
|-----------|-----|----------------|
| Location | Lets Android notice when you enter or leave the office area | **Allow all the time** and **Precise**. "While using the app" is not enough, because arrivals happen while the app is closed. |
| Notifications | Asks you to confirm arrival and departure | **Allow** |
| Battery optimisation | Stops the phone from putting the app to sleep | **Allow** / **Unrestricted** |
| Nearby Wi-Fi / Wi-Fi state (if shown) | Optional extra check that you are on the office Wi-Fi | Allow. Only the office network name is compared. |

Some phones need extra steps (Samsung "Sleeping apps", Xiaomi "Autostart", Huawei
"App launch", Oppo "Background activity"). The setup screen links to the right
settings page. If something stays orange, ask IT. Step-by-step instructions are in
`DEPLOYMENT.md` §7.

Open the app at least once after installing and after each phone update.

## 4. Daily use

### Arriving
1. When you have been inside the office area for about two minutes, you get a
   notification: **"Arrival detected — please confirm"**.
2. Tap it, or open the app, and tap **Confirm arrival**. You may add a note.
3. The dashboard shows **Clocked in** in green with your clock-in time.

If the detection is wrong, for example you were only passing by, tap **Not me /
dismiss**.

### Leaving
1. After you have left the area for a few minutes, you get **"Departure detected —
   please confirm"**.
2. Tap **Confirm departure**. The dashboard shows **Clocked out** and your hours for
   the day.

Short GPS dropouts do not clock you out. The app waits and checks again before it
reports a departure.

### If you miss the notification
Pending confirmations stay on the app's home screen and in **Notifications** until
you act on them. If you leave a confirmation too long (12 hours by default), it
expires and goes to your manager for review. It is never recorded as attendance
without your confirmation or a manager's decision.

### Statuses
| Status | Meaning |
|--------|---------|
| Not started | No arrival today |
| Pending arrival / departure (orange) | Detected, waiting for you to confirm |
| Clocked in (green) | Arrival confirmed |
| Under review (orange) | Confirmed, but a manager must check it (for example, poor GPS or the phone was offline) |
| Clocked out | Departure confirmed |
| Missing clock-out (orange) | No departure confirmed that day. Submit a manual request. |

### No signal or no data
You can confirm while offline. The app stores the confirmation and sends it when
you are back online. A small sync indicator shows "waiting to sync". Your
confirmation is official only after the server accepts it. The time recorded is
the time of detection, and offline submissions may be reviewed by a manager.

## 5. History and details
**History** lists your sessions by day, with clock-in and clock-out times, worked
time, and late, manual and corrected flags. Tap a day to see the individual events
and their status.

## 6. Manual requests
Use **Manual request** when something was not recorded correctly:
- Missed clock-in or missed clock-out (phone off, battery dead, forgot)
- Full day (worked without your phone)
- Time correction (a recorded time is wrong)
- Profile correction (your name, e-mail, phone or department is wrong)

Choose the date and times, give a reason and submit. You can cancel the request
while it is pending. You are notified when a manager approves or rejects it, and
rejections include a note.

## 7. Profile and settings
View your details. Change your password. Turn push notifications on or off (pending
confirmations still appear in the app). Re-run the permission check. Sign out.

## 8. Troubleshooting

| Problem | Try this |
|---------|----------|
| No arrival notification | Open the app. Does the setup screen show everything green? Is location on? Is the app set to Unrestricted battery? Pending events may already be waiting on the home screen. If not, submit a manual request. |
| Notification arrives late | Normal on some phones. Android may delay background location by several minutes to save battery. Confirm when it arrives; the detection time is what counts. |
| Arrival detected while driving past | Tap "Not me / dismiss". |
| Departure detected while still at work | Do not confirm. Dismiss it. If it keeps happening, tell IT (the office area may need adjusting). |
| "Device revoked" or signed out | You signed in on another phone, or an administrator revoked this one. Sign in again or contact your administrator. |
| "Account locked" | Too many wrong passwords. Wait 15 minutes or ask your administrator. |
| Left phone at work overnight | The app will not clock you out without a departure, so the day shows "Missing clock-out". Submit a missed clock-out request. |
| Phone restarted or app updated | Open the app once so it re-registers the office area. |
| New phone | Install, then sign in. The old phone is signed out. |

Still stuck? Contact IT and quote the error code or request ID shown in the
message.

## 9. Your privacy

- Location is checked only at the moments Android reports entering or leaving the
  office area, and when you confirm. The app does not record a route or track you
  during the day or outside work.
- What is stored: confirmed arrival and departure times, the position and accuracy
  at those moments, distance from the office, the office Wi-Fi name if matched,
  your phone model and app version, and battery level at detection.
- Who can see it: you, your managers and authorised administrators. Every
  administrator action is logged.
- You may ask to see your data, have it corrected (Manual request > Profile
  correction) or ask about deletion. Meridian's privacy notice explains how. That
  notice is issued by Meridian and is not part of this guide.

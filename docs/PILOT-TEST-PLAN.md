# Pilot test plan — real devices at the Umhlanga office

> **Status: NOT EXECUTED.** No physical device testing has been performed at the
> Meridian office or anywhere else. Results must be recorded in §7 by the people who
> run the pilot. Until then, all on-device detection behaviour is classified as
> "implemented, awaiting real-world validation".

## 1. Objectives

1. Confirm that arrival and departure detection works reliably on the phone models
   Meridian employees use, under real Android background restrictions.
2. Tune the workplace settings (radius, accuracy threshold, dwell, exit debounce,
   Wi-Fi) using measured data.
3. Validate the offline, reboot and permission-revocation behaviour, and that
   nothing is recorded as attendance without confirmation.
4. Validate the end-to-end administration flow: pending review, manual requests and
   reports.

## 2. Preconditions

- [ ] Backend deployed on **staging** or production with a valid public
      certificate (Let's Encrypt staging certificates are rejected by phones)
- [ ] Real office coordinates, radius and Wi-Fi configured (`DEPLOYMENT.md` step 3)
- [ ] Policy values set as Meridian intends (they affect late and overtime results)
- [ ] Signed release APK (or Play internal testing build) pointing at that backend
- [ ] FCM configured (to test push) — otherwise mark push cases N/A
- [ ] 5–10 volunteers with accounts, briefed, and the privacy notice issued
- [ ] A pilot coordinator with dashboard access (manager role at least)
- [ ] The surrounding area mapped: entrances, parking, nearby roads, neighbouring
      buildings within ~300 m

## 3. Device matrix

Cover at least one device per row. Prefer the employees' actual phones.

| # | Manufacturer / model (example) | Android | Skin | Why |
|---|--------------------------------|---------|------|-----|
| D1 | Samsung Galaxy A-series (A15/A25/A35/A55) | 14–15 | One UI 6–7 | Most common in South Africa; aggressive "sleeping apps" |
| D2 | Samsung Galaxy S-series | 14–15 | One UI | Flagship behaviour |
| D3 | Xiaomi / Redmi Note | 13–15 | MIUI / HyperOS | Autostart and battery-saver restrictions |
| D4 | Huawei or Honor **with Google Mobile Services** | 12–14 | EMUI / MagicOS | App-launch manager. (Devices without GMS are unsupported and tested only to confirm the error message.) |
| D5 | Oppo / Realme / OnePlus | 13–15 | ColorOS / OxygenOS | Background activity restrictions |
| D6 | Google Pixel | 15–16 | Stock | Reference behaviour |
| D7 | Low-end device (2–3 GB RAM), e.g. Samsung A0x / Hisense / Mobicel with GMS | 12–13 | Various | Process kills under memory pressure |
| D8 | Oldest supported (Android 8–10) if staff still use one | 8–10 | Various | Permission model before "Allow all the time" was split |

Record for each device: model, Android version, security patch level, app version,
battery level at start, and whether the phone is personal or company-managed.

## 4. Test cases

Each case notes the expected result. "Pending" means visible in the app and in
Dashboard > Pending confirmations.

| ID | Scenario | Steps | Expected result |
|----|----------|-------|-----------------|
| TC-01 | Normal arrival | Arrive from outside the radius (≥ 500 m away), app closed, phone locked; walk in and stay | Arrival notification within dwell + Android latency (target ≤ 5 min; record the actual time). Pending arrival created. Confirm → Clocked in. The server time and official time are recorded. |
| TC-02 | Normal departure | Leave the building and go ≥ 500 m away | After the exit debounce, a departure notification appears. Confirm → Clocked out with worked hours. |
| TC-03 | Dwell / short visit | Enter the radius for less than the dwell time (e.g. 60 s), then leave | No arrival event (or a dismissed one). Nothing is recorded. |
| TC-04 | Pass-by | Drive or walk past the office along the nearest road without stopping | No arrival notification. If one occurs, record the distance and accuracy for tuning. |
| TC-05 | Phone left at work | Clock in, then leave the phone in the office overnight | No departure is recorded. The next day shows **Missing clock-out** under Exceptions. The employee submits a missed clock-out request; the manager approves it. |
| TC-06 | GPS loss inside the building | Stay deep inside the building or basement for 2 h | No departure event. No automatic clock-out. |
| TC-07 | Offline arrival | Turn on aeroplane mode (with Wi-Fi off) before arriving; confirm the arrival offline; reconnect 30 min later | The app shows "waiting to sync". After reconnection the event syncs with the original detection time and goes to `under_review` if skew rules apply. No duplicate is created. |
| TC-08 | Offline for long | Stay offline more than `max_offline_age_hours` (72 h) before syncing (can be simulated with a short policy value on staging) | The event is rejected as too old. The employee is told to submit a manual request. |
| TC-09 | Reboot | Clock in, reboot the phone and do not open the app; later leave the office | The departure is still detected (geofences re-registered by the boot receiver). Record whether the app must have been opened after install for this to work. |
| TC-10 | App force-stopped / swiped away | Settings > Force stop (and separately swipe from recents), then leave or arrive | Document the behaviour. A force-stop disables geofences until the app is next opened (Android behaviour); a swipe should not. The app should show a warning on the next open. |
| TC-11 | Battery saver | Turn on battery saver / ultra power saving before arriving | Record whether detection is delayed or missed per manufacturer. The pending event must remain if it was detected late. |
| TC-12 | Battery optimisation not exempted | Revert the app to "Optimised" / "Restricted" | Onboarding check shows orange. Record the detection reliability difference. |
| TC-13 | Location permission downgraded | Change to "While using the app" / "Deny" | The app shows the permission warning on open. No crash. A manual request is possible. |
| TC-14 | Location services off | Turn location off, then arrive | No detection. On turning location back on, the location-provider receiver re-registers geofences. The app warns the user. |
| TC-15 | Wi-Fi verification | With Wi-Fi verification enabled: (a) arrive on office Wi-Fi; (b) arrive with Wi-Fi off | (a) `wifi_verified=true`. (b) Event still confirmed, or `under_review` only if "Wi-Fi required". |
| TC-16 | Duplicate / double-tap | Tap Confirm repeatedly and quickly; kill the app mid-request | Exactly one confirmation, and the session is updated once. |
| TC-17 | Dismiss | On TC-04 or any false detection, tap "Not me" | Status `dismissed`. Visible under Exceptions. No attendance. |
| TC-18 | Expiry | Ignore an arrival notification for longer than `pending_expiry_hours` | Status `expired`. Visible to the manager. It is never recorded as confirmed. |
| TC-19 | Notification missed | Clear the notification without opening it | The pending confirmation is still on the app's home screen and in Notifications. |
| TC-20 | Push vs polling | With FCM on and off | With FCM, the reminder and review notifications arrive within ~1 min. Without FCM, they appear when the app is opened or synced. |
| TC-21 | Manager review | A manager approves an `under_review` event and rejects another | The employee is notified, the session is updated or unchanged, and an audit entry exists. |
| TC-22 | Manual request | Submit a missed clock-in, then a profile correction | Manager approval creates the session or applies the change, and the employee is notified. |
| TC-23 | Device replacement | Sign in on a second phone | The first phone is revoked (or the second is pending approval, per policy). The first phone shows "device revoked". |
| TC-24 | Time zone / clock | Set the phone clock 10 minutes wrong (manual time), then arrive online | The official time is server time. The event goes to review if the skew exceeds the tolerance. |
| TC-25 | Multi-day | Normal use for 5 consecutive working days | Daily and weekly reports match the volunteer's own log within ±5 min. |
| TC-26 | Battery impact | Record battery % at 07:00 and 17:00 on a normal day, with and without the app (if possible) | Attributable usage should be negligible. Settings > Battery shows the app's share. |
| TC-27 | Lunch break exit/re-entry | Leave the office for lunch (> debounce) and return | Record the behaviour: a departure and a new arrival prompt. Decide with Meridian whether breaks should be confirmed or dismissed (policy). |
| TC-28 | Unsupported device (no GMS) | Install on a Huawei device without Google services (if available) | The app explains that automatic detection is unavailable. Manual requests still work. |

## 5. Pass criteria

The pilot passes when, over at least **5 working days** and at least **5 devices
covering D1, D3, D5 and D6** (and D4 if Huawei/Honor phones are in use):

1. ≥ 95% of real arrivals produce a pending arrival within 10 minutes
   (median ≤ 5 min), after permissions are correctly set.
2. ≥ 95% of real departures produce a pending departure within 15 minutes.
3. **Zero** automatic clock-outs while the employee was still on site (TC-06).
4. **Zero** attendance records created without confirmation or manager approval.
5. Zero duplicate sessions or events from retries or offline sync (TC-07, TC-16).
6. Pass-by false arrivals (TC-04) ≤ 1 per device per week. All are dismissible.
7. Reports match the volunteers' manual logs within ±5 minutes for ≥ 95% of days.
8. No crashes (Play Console or `adb logcat` / ANR reports) in normal flows.
9. Every manufacturer-specific failure has a documented settings fix in
   `DEPLOYMENT.md` §7 and `TROUBLESHOOTING.md`.

If a criterion fails: tune the configuration (radius, dwell, debounce) first. Then
fix the app or backend, rebuild and repeat the affected cases.

## 6. Data to collect

Ask the volunteers to keep a simple log of actual arrival and departure times. Each
day, export the pending-confirmations and daily reports (CSV). For each event,
compare `detected_at`, `received_at`, `accuracy_m` and `distance_from_workplace_m`
with the volunteer's log. Use the accuracy distribution to set
`accuracy_threshold_m` and the radius.

## 7. Result log template

Copy one table per day.

```
Date: ____-__-__   Coordinator: __________   Backend: staging / production   App version: _____ (code ___)
Workplace config: radius __ m, accuracy __ m, dwell __ s, debounce __ s, Wi-Fi on/off

| Test | Device | Tester | Actual time | Detected at (app) | Notified at | Confirmed at | Accuracy m | Distance m | Result (Pass/Fail/N-A) | Notes / defect id |
|------|--------|--------|-------------|-------------------|-------------|--------------|------------|------------|------------------------|-------------------|
| TC-01 | D1 Samsung A35 / Android 15 | | 07:52 | | | | | | | |
| TC-02 | | | | | | | | | | |
```

Defect log:

```
| Defect id | Test | Device | Severity (blocker/major/minor) | Description | Steps | Status | Fixed in version |
|-----------|------|--------|--------------------------------|-------------|-------|--------|------------------|
```

Sign-off: the pilot coordinator, IT and an HR/management representative sign when
§5 is met. Attach the logs to the release record.

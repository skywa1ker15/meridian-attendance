# Detection pipeline — reliability and failure modes

Principle (docs/ARCHITECTURE.md §5, API.md): **detection is never attendance**. The phone detects, proposes and
asks; the employee confirms; the server decides (`confirmed` or `under_review`). Departures are never automatic.

## Pipeline as implemented

| Step | Code | Notes |
|------|------|-------|
| 1. Config | `ConfigRepository.refresh()` → `GET /config/workplaces` + `/config/policy`, cached in Room/DataStore | On login, app start, every 6 h from `SyncWorker`, after boot/location-on. |
| 2. Geofences | `GeofenceManager.registerOnly()` | Only workplaces with `active && coordinates_confirmed && (entry \|\| exit enabled)`. DWELL (`loiteringDelay = dwell_seconds`) for arrival, EXIT for departure, initial trigger DWELL, never-expire, radius ≥ 50 m. Re-registered when `config_version` changes. |
| 3. Transition | `GeofenceBroadcastReceiver` → `GeofenceTransitionWorker` (expedited) | ENTER ignored. |
| 4a. Arrival | DWELL → collect signals → `AttendanceRepository.recordDetection()` | Skipped if today is already `clocked_in` / `pending_arrival`. |
| 4b. Departure | EXIT → same worker re-enqueued with `initialDelay = exit_debounce_seconds` → fresh `getCurrentLocation` → `ExitDebounce.decide()` | Proposed only if still outside `radius + accuracy` with a fresh (≤ 2 min), usable (≤ 500 m accuracy) fix and no DWELL since the EXIT. |
| 5. Signals | `SignalCollector` | Location + accuracy, connected SSID/BSSID (API 31+: `NetworkCallback(FLAG_INCLUDE_LOCATION_INFO)`; only with permission), battery. Local pre-check `Verification.evaluate()` shows distance/Wi-Fi match and predicts review. |
| 6. Persist + submit | Room `events` row + `pending_outbox` row (kind `event`, `client_event_id`) **before** `POST /attendance/events` | If offline/5xx the row stays queued; `SyncWorker` replays via `/attendance/sync` with `offline_queued=true`. |
| 7. Ask | `AttendanceNotifier.showConfirmationRequired()` (high-importance channel, heads-up, deep link `meridianattendance://confirm/{ref}`) + `ReminderScheduler` every `confirmation_reminder_minutes` (default 15) while pending | Also surfaced in-app (dashboard card) from `GET /attendance/pending`, independent of push. |
| 8. Confirm / dismiss | `ConfirmationScreen` → `POST …/confirm` or `…/dismiss` (queued offline; `event_id` may be the `client_event_id`) | UI shows "Saved on your phone" — nothing is shown as confirmed until the server answers. |

## Failure modes

| Situation | Handling |
|-----------|----------|
| **Employee drives/walks past** | Arrival uses DWELL, not ENTER: the employee must stay inside for `dwell_seconds` (server-configurable, default 120 s). A short pass-through triggers nothing. |
| **GPS drift / temporary loss near the boundary** | EXIT is debounced for `exit_debounce_seconds` (default 300 s); a later EXIT replaces the pending re-check; a DWELL after the EXIT cancels it; the re-check needs a fresh, accurate fix that is outside `radius + accuracy`. No fix → no departure. |
| **Phone left at work / forgotten clock-out** | No EXIT → no departure proposal. The phone never invents a departure. The server flags `missing_clock_out` at `missing_clock_out_flag_local` (default 23:30) and management sees it; the employee can file a `missed_clock_out` manual request. |
| **Background location denied / only "while in use"** | Geofences are not registered (`registerOnly` returns false). Dashboard banner "Permissions needed" → onboarding. On API 30+ the onboarding opens app settings with instructions for "Allow all the time". Manual requests remain available. |
| **Approximate location only** | Treated as not granted; onboarding explains precise location is required. |
| **Location services switched off** | `LocationProviderChangedReceiver` posts a notification and the dashboard shows a banner with "Turn on location". Play services drops geofences (`GEOFENCE_NOT_AVAILABLE` → `GeofenceRefreshWorker` scheduled); when location is switched on, geofences are re-registered. |
| **Wi-Fi off / no Wi-Fi permission** | Wi-Fi is optional evidence: the detection is still submitted with `wifi: null`; the confirmation screen shows "Not checked". If the workplace has `wifi_required`, the server puts the event `under_review` (never silently rejected); the app warns "A manager may need to review this". |
| **Battery optimisation / OEM task killers** | Onboarding step explains and opens the system battery-optimisation list. Geofence delivery is done by Play services via a `PendingIntent` and expedited WorkManager jobs, which survive Doze better than services. Residual risk on aggressive OEM skins (documented to users; manual requests as fallback). |
| **Reboot** | Geofences are cleared by Android: `BootReceiver` (BOOT_COMPLETED, LOCKED_BOOT_COMPLETED, MY_PACKAGE_REPLACED) enqueues `GeofenceRefreshWorker` and re-schedules periodic sync. |
| **App killed / swiped away** | Geofences belong to Play services and keep firing into `GeofenceBroadcastReceiver` (a manifest receiver), which starts WorkManager. On every app start `onSessionActive()` and `MainActivity.onStart()` call `ensureGeofences()`. |
| **App update / data cleared** | MY_PACKAGE_REPLACED re-registers. Data cleared → logged out; login re-registers. |
| **Permission revoked later** | `PermissionChecker.snapshot()` re-evaluated on each resume (dashboard, profile, onboarding); banner shown; registration fails gracefully until fixed; re-running onboarding re-registers geofences. |
| **Offline at detection or confirmation** | Durable outbox (Room) with unique `(kind, client_id)`; `SyncWorker` (15-min periodic, expedited on connectivity return via `ConnectivityMonitor`, on app open, after each action) replays in order through `/attendance/sync`; exponential backoff (30 s → 1 h, 25 attempts). Confirmations/dismissals for events the server never saw reference the `client_event_id` and are always sent after their event (`OutboxRules.plan`). Items older than `max_offline_age_hours` are rejected by the server → marked failed, user notified, manual request suggested. |
| **Duplicate detections** | Local 10-minute collapse of pending events of the same type; server `duplicate_window_seconds` returns the existing event; every submission is idempotent by `client_event_id` / `client_confirmation_id` / `client_request_id`. |
| **Device clock wrong** | Official time is the server receive time when online and plausible; skewed or offline-queued detections go `under_review` (server rule). The app sends `detected_at` in UTC and `device_timezone`. |
| **Arrival while already clocked in** | Skipped locally if today's cached status is `clocked_in`/`pending_arrival`; the server answers `409 invalid_state` otherwise, which marks the local row failed (no retry loop). |
| **Departure without open session** | Skipped locally when today is `not_started`/`clocked_out`; if the cache is unknown it is proposed and the server routes it to `under_review`. |
| **Workplace coordinates not yet confirmed** | No geofences are registered (`coordinates_confirmed == false`); dashboard banner "Workplace not configured yet"; manual requests only. |
| **Session expired / token reused / device revoked** | Single-flight token refresh; on `token_reused`, `token_invalid`, `device_revoked`, `account_inactive` the app logs out, wipes local data and removes geofences. `device_pending_approval` keeps items queued (15-min backoff). |
| **Notifications disabled** | Pending confirmations are always listed on the dashboard (from `GET /attendance/pending` on open/sync) and in the inbox. |
| **Pending confirmation ignored** | Local reminders every `confirmation_reminder_minutes` until confirmed, dismissed or `expires_at`; afterwards the server marks it `expired` (still visible to management). |

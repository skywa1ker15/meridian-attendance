# Meridian Attendance — REST API Contract (v1)

This document is the **binding interface** between the Android application, the
administrator dashboard and the FastAPI backend. All three components are built
against it. Interactive OpenAPI documentation is also served by the backend at
`/api/docs` (Swagger UI) and `/api/redoc`.

* Base path: `/api/v1`
* Content type: `application/json; charset=utf-8`
* Timestamps: ISO‑8601 in **UTC** with a `Z` suffix, e.g. `2026-11-16T06:02:11Z`.
  Local (company‑facing) dates are calendar dates in `Africa/Johannesburg`
  formatted `YYYY-MM-DD` and are always labelled `work_date` / `local_*`.
* IDs: UUID v4 strings.
* Pagination: `?page=1&page_size=50` → `{ "items": [...], "total": 123, "page": 1, "page_size": 50 }`
* Authentication: `Authorization: Bearer <access_token>` (JWT, 15 min default).
* Every mutating request **may** carry `Idempotency-Key: <uuid>`; attendance
  endpoints additionally carry an explicit `client_event_id` /
  `client_confirmation_id` in the body which is the idempotency key for that
  record. Replaying a key returns the original result with `200` instead of
  creating a duplicate.

## Error format

```json
{ "error": { "code": "invalid_credentials", "message": "Email or password is incorrect.", "details": {} } }
```

| HTTP | code (examples) | Meaning |
|------|-----------------|---------|
| 400 | `validation_error`, `invalid_state` | Request malformed or not allowed in current state |
| 401 | `invalid_credentials`, `token_expired`, `token_invalid`, `token_reused` | Not authenticated |
| 403 | `forbidden`, `device_revoked`, `account_inactive`, `password_change_required` | Authenticated but not allowed |
| 404 | `not_found` | Resource does not exist (or not visible to caller) |
| 409 | `conflict`, `duplicate` | Unique constraint / duplicate submission |
| 422 | `validation_error` | Field validation failed (`details.fields`) |
| 423 | `account_locked` | Too many failed logins |
| 429 | `rate_limited` | Rate limit hit, `Retry-After` header set |
| 500 | `server_error` | Unexpected; never leaks internals |

## Enumerations

| Enum | Values |
|------|--------|
| `role` | `employee`, `manager`, `admin`, `superadmin` |
| `user_status` | `active`, `inactive` |
| `device_status` | `active`, `pending_approval`, `revoked` |
| `event_type` | `arrival`, `departure` |
| `event_source` | `geofence`, `wifi`, `manual`, `admin` |
| `event_status` | `pending`, `confirmed`, `under_review`, `rejected`, `expired`, `dismissed` |
| `geofence_transition` | `enter`, `dwell`, `exit`, `none` |
| `session_status` | `open`, `closed`, `missing_clock_out` |
| `request_type` | `missed_clock_in`, `missed_clock_out`, `full_day`, `correction`, `profile_correction` |
| `request_status` | `pending`, `approved`, `rejected`, `cancelled` |
| `notification_type` | `arrival_detected`, `departure_detected`, `clock_in_confirmation_required`, `clock_out_confirmation_required`, `attendance_recorded`, `attendance_submission_failed`, `manual_request_reviewed`, `correction_approved`, `correction_rejected`, `device_revoked`, `system` |
| `today_status` | `not_started`, `pending_arrival`, `clocked_in`, `pending_departure`, `clocked_out`, `missing_clock_out` |

Lifecycle of an attendance event:

```
geofence/wifi detection ──► POST /attendance/events ──► pending ──(employee confirms)──► confirmed  ──► session updated
                                                   │                                   └─► under_review ──(manager approves)──► confirmed
                                                   │                                                     └─(manager rejects)──► rejected
                                                   ├──(employee dismisses "not me")──► dismissed
                                                   └──(policy.pending_expiry_hours elapses)──► expired   (still visible to management)
```

Detection is **never** attendance. Only `confirmed` events change a session.

---

## 0. First-run setup (no authentication)

Lets the Android app create the very first administrator on an empty installation, so the system can be set up from a phone without server access.

`GET /setup/status` → `200 {"needs_first_admin": true, "setup_code_required": true}`
`needs_first_admin` is true while no `admin`/`superadmin` exists (employees do not count).

`POST /setup/first-admin` → `201` same body as `POST /auth/login` (the new superadmin is signed in; `device` registers the phone)
```json
{ "setup_code": "from the server's FIRST_ADMIN_SETUP_CODE", "employee_number": "MG-0001",
  "first_name": "Nomsa", "last_name": "Dlamini", "email": "nomsa.d@meridiangroup.co.za",
  "password": "…", "device": { …as login… } }
```
Errors: `409 setup_complete` (an admin already exists — permanently closed), `403 setup_disabled` (staging/production server without `FIRST_ADMIN_SETUP_CODE`), `403 invalid_setup_code`, `409 conflict` (email/number taken), `422 validation_error` (password policy). Shares the login rate limit; audit-logged.

## 1. Authentication

### POST `/auth/login`
```json
{
  "identifier": "e.g. email or employee number",
  "password": "string",
  "device": {                       // optional (omitted by the web dashboard)
    "device_id": "installation uuid generated once per install",
    "model": "SM-A546B", "manufacturer": "samsung", "os_version": "14",
    "app_version": "1.0.0 (1)", "fcm_token": "optional"
  }
}
```
Response `200`:
```json
{
  "access_token": "jwt", "refresh_token": "opaque", "token_type": "bearer",
  "expires_in": 900,
  "must_change_password": false,
  "user": { ...User },
  "device": { ...Device } | null
}
```
Errors: `401 invalid_credentials`, `403 account_inactive`, `403 device_revoked`, `423 account_locked`.
Rate limit: 10 / minute / IP, 5 failed attempts → 15 minute lock (configurable).

**User object**
```json
{
  "id": "uuid", "employee_number": "MG-0042", "first_name": "Thandi", "last_name": "Naidoo",
  "email": "t.naidoo@example.co.za", "phone": "+27 ...", "role": "employee",
  "status": "active", "department": { "id": "uuid", "name": "Finance", "code": "FIN" } | null,
  "must_change_password": false, "created_at": "...", "last_login_at": "..." | null,
  "permissions": ["attendance:self", ...]
}
```

**Device object**
```json
{ "id": "uuid", "device_id": "installation uuid", "model": "...", "manufacturer": "...",
  "os_version": "...", "app_version": "...", "status": "active",
  "registered_at": "...", "last_seen_at": "...", "integrity_verdict": "unknown|meets_device_integrity|failed|not_configured" }
```

### POST `/auth/refresh`  `{ "refresh_token": "..." }` → same shape as login (new pair).
Refresh tokens rotate on every use. Re‑use of an already‑rotated token revokes the
whole token family (`401 token_reused`) — the device must log in again.

### POST `/auth/logout` `{ "refresh_token": "..." }` → `204`.

### GET `/auth/me` → `{ "user": User, "device": Device|null }`

### PATCH `/auth/me` `{ "phone"?: string, "notification_preferences"?: { "push": bool } }` → User

### POST `/auth/password/change` `{ "current_password", "new_password" }` → `204`
Password policy: ≥ 10 characters, not equal to identifier, not in the 10 000 most
common passwords list, checked server‑side.

### POST `/auth/password/forgot` `{ "identifier" }` → `202` always (anti‑enumeration).
If SMTP is configured an email with a 30‑minute reset link/token is sent.
Administrators can also issue a temporary password from the dashboard.

### POST `/auth/password/reset` `{ "token", "new_password" }` → `204`

---

## 2. Devices

### POST `/devices/register`  (same `device` body as login) → Device
Policy `max_active_devices_per_user` (default 1). Registering a new device:
* if `device_replacement_requires_approval` is false (default): the previous device is
  revoked, the user is notified, audit entry written, new device `active`;
* otherwise the new device is `pending_approval` and attendance submissions are
  refused with `403 device_pending_approval` until an administrator approves it.

### GET `/devices/me` → Device
### POST `/devices/me/fcm-token` `{ "fcm_token" }` → `204`
### POST `/devices/me/integrity` `{ "integrity_token" }` → `{ "verdict": "..." }`
Optional Play Integrity verification (only when the backend has Google
credentials configured; otherwise returns `not_configured`).

---

## 3. Configuration for the app

### GET `/config/workplaces` → `{ "items": [Workplace], "config_version": 7 }`
```json
{
  "id": "uuid", "name": "Meridian Group — Umhlanga (PLACEHOLDER — set real coordinates)", "address": "…",
  "latitude": 0.0, "longitude": 0.0,               // PLACEHOLDERS until supplied by management
  "coordinates_confirmed": false,                   // clients MUST NOT register geofences while false
  "radius_m": 150, "accuracy_threshold_m": 100,
  "entry_detection_enabled": true, "exit_detection_enabled": true,
  "dwell_seconds": 120, "exit_debounce_seconds": 300,
  "wifi_verification_enabled": false, "wifi_ssid": null, "wifi_bssids": [],
  "wifi_required": false, "timezone": "Africa/Johannesburg", "active": true,
  "config_version": 1, "updated_at": "…"
}
```
While `coordinates_confirmed` is false the server cannot evaluate the geofence: confirmed
events go to `under_review` (`workplace_coordinates_unconfirmed`) unless a configured Wi‑Fi
match proves presence. The seed workplace is a placeholder (0,0) until management sets the
real coordinates in the dashboard and ticks *coordinates confirmed*.
### GET `/config/policy` → Policy (see §8) — read‑only for employees.

---

## 4. Attendance (employee)

### POST `/attendance/events` — submit a detected event
```json
{
  "client_event_id": "uuid (idempotency key)",
  "workplace_id": "uuid",
  "event_type": "arrival",
  "source": "geofence",
  "detected_at": "2026-11-16T06:02:11Z",
  "device_timezone": "Africa/Johannesburg",
  "geofence_transition": "dwell",
  "location": { "latitude": -29.7, "longitude": 31.08, "accuracy_m": 18.5 } | null,
  "wifi": { "ssid": "Meridian-Staff", "bssid": "aa:bb:cc:dd:ee:ff" } | null,
  "offline_queued": false,
  "battery_level": 73,
  "note": null
}
```
Response `201` (or `200` if `client_event_id` already exists):
```json
{ "event": Event, "today": TodaySummary }
```
**Event object**
```json
{
  "id": "uuid", "client_event_id": "uuid", "event_type": "arrival", "source": "geofence",
  "status": "pending", "workplace_id": "uuid", "workplace_name": "…",
  "detected_at": "… device time", "received_at": "… server time", "official_at": null,
  "confirmed_at": null, "expires_at": "…",
  "location": { "latitude": .., "longitude": .., "accuracy_m": .. } | null,
  "distance_from_workplace_m": 42.1 | null,
  "wifi_verified": false, "verification_score": 0.82,
  "verification": { "inside_geofence": true, "accuracy_ok": true, "wifi_matched": false,
                    "timestamp_plausible": true, "device_active": true, "notes": ["…"] },
  "review_reason": null, "reviewed_by": null, "reviewed_at": null, "review_note": null,
  "session_id": null, "offline_queued": false, "created_at": "…"
}
```
Server validation rules (all configurable in Policy):
* `detected_at` more than `max_future_skew_seconds` (default 120) in the future → `rejected` (`timestamp_implausible`).
* `detected_at` older than `max_offline_age_hours` (default 72) → `rejected`.
* If not `offline_queued` and |received_at − detected_at| > `online_skew_tolerance_seconds` (default 300) → `under_review` after confirmation.
* **Arrival** with a location outside `radius_m + accuracy_m` → `under_review` after confirmation (`outside_geofence`; never silently rejected).
* **Departure** is evidenced by being *outside* the geofence. A `geofence`‑source departure whose location is still inside → `under_review` (`departure_still_inside`; GPS drift or phone left at work — nobody is clocked out on contradictory evidence). A `manual` departure from inside the building is normal.
* `wifi_required` and no Wi‑Fi match → `under_review` (**arrivals only**; Wi‑Fi is not expected after leaving, and a departure still on the office Wi‑Fi is only noted for reviewers).
* Duplicate pending event of same type within `duplicate_window_seconds` (default 600) → the existing event is returned (`200`).
* An arrival while a session is already open → `409 invalid_state` with the open session; a departure without an open session → `under_review` (a manager decides).

### POST `/attendance/events/{event_id}/confirm`
```json
{ "client_confirmation_id": "uuid", "confirmed_at": "device time",
  "location": {...} | null, "wifi": {...} | null, "note": "optional" }
```
Response `200`: `{ "event": Event, "session": Session | null, "today": TodaySummary }`.
Result status is `confirmed` (session updated) or `under_review`.
Official time: `official_at = received_at` of the original detection when the
detection was online and plausible; otherwise `detected_at` (device time) is used
and the event goes `under_review`. Confirmation of an `expired` event → `400 invalid_state`.

### POST `/attendance/events/{event_id}/dismiss` `{ "reason": "not_at_work" | "wrong_detection" | "other", "note"?: "…" }` → Event (`dismissed`).

### GET `/attendance/pending` → `{ "items": [Event] }` (pending events for the caller).

### GET `/attendance/today` → TodaySummary
```json
{
  "work_date": "2026-11-16", "status": "clocked_in",
  "session": Session | null,
  "pending_events": [Event],
  "clock_in_local": "08:02", "clock_out_local": null,
  "worked_minutes_today": 312, "week_minutes": 1540, "month_minutes": 6230,
  "late_today": false, "unread_notifications": 2, "pending_requests": 1,
  "server_time": "…"
}
```
**Session object**
```json
{
  "id": "uuid", "work_date": "2026-11-16", "status": "open",
  "workplace_id": "uuid", "workplace_name": "…",
  "clock_in_at": "…UTC", "clock_out_at": null,
  "clock_in_local": "2026-11-16T08:02:00+02:00", "clock_out_local": null,
  "clock_in_event_id": "uuid", "clock_out_event_id": null,
  "worked_minutes": 312, "break_minutes": 0, "overtime_minutes": 0,
  "is_late": false, "late_minutes": 0, "is_manual": false,
  "corrected": false, "correction_note": null, "notes": null
}
```
### GET `/attendance/sessions?from=YYYY-MM-DD&to=YYYY-MM-DD&page=&page_size=` → paginated Sessions (newest first).
### GET `/attendance/sessions/{id}` → `{ "session": Session, "events": [Event] }`
### GET `/attendance/events?from=&to=&status=&page=` → paginated Events (own).

### POST `/attendance/sync` — offline queue replay (batch)
```json
{ "items": [
  { "kind": "event", "payload": { ...POST /attendance/events body } },
  { "kind": "confirmation", "event_id": "uuid|client_event_id", "payload": { ...confirm body } },
  { "kind": "dismissal", "event_id": "…", "payload": { "reason": "…" } }
] }
```
Response `200`: `{ "results": [ { "index": 0, "kind": "event", "client_id": "…", "ok": true, "status": 201, "event": Event } | { "index": 1, "ok": false, "status": 400, "error": {...} } ], "today": TodaySummary }`.
Items are processed in order; each is independently idempotent. The client may
reference an event by its `client_event_id` when it never received the server id.

---

## 5. Manual attendance requests

### POST `/attendance/manual-requests`
```json
{ "client_request_id": "uuid", "type": "missed_clock_out", "work_date": "2026-11-13",
  "requested_clock_in": "2026-11-13T06:00:00Z" | null, "requested_clock_out": "2026-11-13T15:05:00Z" | null,
  "reason": "Phone battery died", "session_id": "uuid" | null,
  "field": null, "requested_value": null }
```
(`profile_correction` uses `field` ∈ `first_name|last_name|email|phone|department` and `requested_value`.)
Response `201`: Request object:
```json
{ "id": "uuid", "type": "…", "status": "pending", "work_date": "…", "requested_clock_in": "…",
  "requested_clock_out": "…", "reason": "…", "field": null, "requested_value": null,
  "employee": { "id", "employee_number", "first_name", "last_name", "department": {…} },
  "reviewed_by": null, "reviewed_at": null, "review_note": null, "resulting_session_id": null,
  "created_at": "…" }
```
### GET `/attendance/manual-requests?status=&page=` → paginated (own).
### POST `/attendance/manual-requests/{id}/cancel` → Request (`cancelled`) — only while `pending`.

---

## 6. Notifications (in‑app inbox; also pushed via FCM when a token exists)

### GET `/notifications?unread_only=false&page=` → paginated
```json
{ "id": "uuid", "type": "clock_in_confirmation_required", "title": "…", "body": "…",
  "data": { "event_id": "…" }, "read_at": null, "created_at": "…" }
```
### GET `/notifications/unread-count` → `{ "count": 3 }`
### POST `/notifications/{id}/read` → `204`
### POST `/notifications/read-all` → `204`

FCM data message payload (all values strings):
`{"type": "<notification_type>", "notification_id": "...", "event_id": "...", "request_id": "...", "title": "...", "body": "..."}`

---

## 7. Administration (roles `manager`, `admin`, `superadmin`)

Permission matrix (server‑enforced; the dashboard only hides what is not allowed):

| Permission | manager | admin | superadmin |
|------------|:-------:|:-----:|:----------:|
| `admin:read` (overview, directory, records, live, exceptions, audit, reports) | ✔ | ✔ | ✔ |
| `attendance:review` (approve/reject pending & under‑review, manual requests, corrections) | ✔ | ✔ | ✔ |
| `reports:export` | ✔ | ✔ | ✔ |
| `employees:write` (create, edit, deactivate, reset password) | – | ✔ | ✔ |
| `devices:write` (approve/revoke) | – | ✔ | ✔ |
| `config:write` (workplaces, Wi‑Fi, policy, departments) | – | ✔ | ✔ |
| `roles:write` (change roles up to `admin`) | – | ✔ | ✔ |
| `roles:superadmin` (grant/revoke `superadmin`, system settings, data erasure) | – | – | ✔ |

### GET `/admin/overview?date=YYYY-MM-DD`
```json
{ "work_date": "…", "stats": { "total_employees": 48, "clocked_in": 31, "clocked_out": 9, "absent": 6,
  "late_arrivals": 4, "pending_confirmations": 2, "under_review": 1, "exceptions": 5, "pending_requests": 3,
  "pending_devices": 0 },
  "attendance_trend": [ { "work_date": "…", "present": 40, "late": 3, "absent": 5 } ],  // last 14 days
  "department_breakdown": [ { "department": "Finance", "present": 8, "total": 10 } ],
  "recent_activity": [ { "at": "…", "kind": "event_confirmed", "text": "Thandi Naidoo clocked in 08:02", "employee_id": "…" } ] }
```
Values are computed from the database — never fabricated.

### Employees
* `GET /admin/employees?search=&department_id=&status=&role=&page=&page_size=` → paginated User (+ `last_attendance_status`, `active_device`)
* `POST /admin/employees` `{ employee_number, first_name, last_name, email, phone?, department_id?, role?="employee", temporary_password? }` → `201 { "user": User, "temporary_password": "shown once" }`
* `GET /admin/employees/{id}` → `{ "user": User, "devices": [Device], "today": TodaySummary, "stats_30d": { "present_days", "late_days", "absent_days", "worked_minutes", "manual_requests" } }`
* `PATCH /admin/employees/{id}` `{ first_name?, last_name?, email?, phone?, department_id?, employee_number? }` → User
* `POST /admin/employees/{id}/deactivate` `{ "reason" }` → User (revokes devices & tokens)
* `POST /admin/employees/{id}/reactivate` → User
* `POST /admin/employees/{id}/reset-password` → `{ "temporary_password" }` (user must change on next login)
* `POST /admin/employees/{id}/role` `{ "role" }` → User (`roles:write`; `superadmin` needs `roles:superadmin`)
* `GET /admin/employees/{id}/export` → full JSON export of the employee's personal data (POPIA access request)
* `POST /admin/employees/{id}/erase` `{ "confirm_employee_number", "reason" }` → `202` — pseudonymises personal data after retention checks (`roles:superadmin`)

### Departments
`GET /admin/departments` → `{ "items": [ { "id", "name", "code", "active", "employee_count", "created_at" } ] }`,
`POST /admin/departments {name, code}` → `201` Department (code upper-cased), `PATCH /admin/departments/{id} {name?, code?, active?}` → Department

### Devices
* `GET /admin/devices?status=&user_id=&search=&page=` → paginated (Device + employee summary)
* `POST /admin/devices/{id}/approve` → Device
* `POST /admin/devices/{id}/revoke` `{ "reason" }` → Device

### Live attendance
`GET /admin/attendance/live?department_id=` → `{ "work_date": "…", "items": [ { "employee": {…}, "status": today_status, "clock_in_local": "08:02"|null, "clock_out_local": null, "worked_minutes": 120, "is_late": false, "pending_event_id": null, "last_event_at": "…" } ] }`

### Attendance records
* `GET /admin/attendance/sessions?from=&to=&employee_id=&department_id=&status=&late=&manual=&page=` → paginated Session (+ `employee` summary)
* `GET /admin/attendance/sessions/{id}` → `{ session, events, employee }`
* `PATCH /admin/attendance/sessions/{id}` `{ "clock_in_at"?, "clock_out_at"?, "note": "required reason" }` → Session (marks `corrected`, audit‑logged)
* `POST /admin/attendance/sessions` `{ "employee_id", "work_date", "clock_in_at", "clock_out_at"?, "note" }` → `201` Session (source `admin`)
* `GET /admin/attendance/events?status=&from=&to=&employee_id=&event_type=&page=` → paginated Event (+ `employee`)
* `POST /admin/attendance/events/{id}/approve` `{ "note" }` → Event (`confirmed`; session updated) — for `pending` or `under_review`
* `POST /admin/attendance/events/{id}/reject` `{ "note" }` → Event (`rejected`)

### Exceptions
`GET /admin/attendance/exceptions?from=&to=&department_id=&kind=&page=` → paginated
`{ "kind": "missing_clock_out"|"late_arrival"|"under_review"|"expired_confirmation"|"dismissed_detection"|"absent"|"rejected_event"|"outside_geofence", "work_date": "…", "employee": {…}, "session_id"?, "event_id"?, "detail": "…", "at": "…" }`

### Manual requests
* `GET /admin/manual-requests?status=&type=&employee_id=&from=&to=&page=` → paginated Request
* `POST /admin/manual-requests/{id}/approve` `{ "note"?, "clock_in_at"?, "clock_out_at"? }` → Request (creates/updates session; `profile_correction` applies the field change)
* `POST /admin/manual-requests/{id}/reject` `{ "note" }` → Request

### Reports
`GET /admin/reports/{report}?from=&to=&employee_id=&department_id=&status=&format=json|csv|xlsx|pdf`
`report` ∈ `daily`, `weekly`, `monthly`, `employee`, `department`, `late-arrivals`, `absences`, `missing-clock-outs`, `manual-attendance`, `pending-confirmations`, `working-hours`.
`format=json` → `{ "report": "daily", "from": "…", "to": "…", "generated_at": "…", "columns": [ { "key": "…", "label": "…" } ], "rows": [ {…} ], "summary": {…} }`.
Other formats stream a file (`Content-Disposition: attachment`).

### Workplace configuration
* `GET /admin/workplaces` → `{ items: [Workplace] }` (includes `wifi_bssids`)
* `POST /admin/workplaces` Workplace fields → `201`
* `PATCH /admin/workplaces/{id}` partial → Workplace (bumps `config_version`)
* `GET /admin/policy` / `PATCH /admin/policy` → Policy (§8)

### Roles
`GET /admin/roles` → `{ "roles": [ { "role": "manager", "permissions": [...], "description": "…", "user_count": 3 } ] }`

### Audit logs
`GET /admin/audit-logs?actor_id=&action=&entity_type=&entity_id=&from=&to=&search=&page=` → paginated
`{ "id", "created_at", "actor": { "id", "name", "employee_number", "role" } | null, "actor_type": "user|system|anonymous", "action": "employee.create", "entity_type": "user", "entity_id": "…", "before": {...}|null, "after": {...}|null, "ip": "…", "user_agent": "…" }`

### Notifications (admin)
`POST /admin/notifications/broadcast` `{ "title", "body", "department_id"? }` → `{ "sent": 42 }` (`admin`)

---

## 8. Policy object (`/config/policy`, `/admin/policy`)

```json
{
  "workday_start_local": "08:00", "workday_end_local": "17:00",
  "late_after_local": "08:15", "grace_minutes": 15,
  "standard_day_minutes": 480, "break_minutes": 60, "overtime_after_minutes": 480,
  "working_days": [1,2,3,4,5],
  "pending_expiry_hours": 12, "missing_clock_out_flag_local": "23:30",
  "max_future_skew_seconds": 120, "max_offline_age_hours": 72, "online_skew_tolerance_seconds": 300,
  "duplicate_window_seconds": 600, "official_time_source": "server_received",
  "min_verification_score_for_auto_confirm": 0.6,
  "max_active_devices_per_user": 1, "device_replacement_requires_approval": false,
  "require_integrity_verdict": false,
  "retention_days_attendance": 1825, "retention_days_location": 365, "retention_days_audit": 2555,
  "notification_quiet_hours": null,
  "updated_at": "…", "updated_by": "…"
}
```
Nothing in the policy is an assertion of Meridian's employment rules — all values are
**defaults to be reviewed by management**.

---

## 9. Health & operations
* `GET /healthz` → `{ "status": "ok", "version": "1.0.0" }` (no DB)
* `GET /readyz` → `{ "status": "ready", "database": "ok", "migrations": "head", "scheduler": "running|disabled" }` or `503`
* `GET /metrics` → Prometheus text format; when `METRICS_TOKEN` is set it requires
  `Authorization: Bearer <METRICS_TOKEN>`. Metrics: `meridian_http_requests_total{method,route,status}`,
  `meridian_http_request_duration_seconds` (histogram), `meridian_ready` (gauge 1/0).

# Administrator and manager manual

The administrator dashboard runs in a browser at `https://attendance.<domain>/admin/`.
Use a current Chrome, Edge or Firefox. No installation is needed.

Screen names follow the build specification (`ARCHITECTURE.md` §6). The dashboard
team's own notes and screenshots are in `docs/admin-dashboard/`. Where the exact
label of a button differs from this manual, trust the screen. The server enforces
permissions, and the dashboard hides actions your role cannot perform.

## 1. Roles

| Role | Can do |
|------|--------|
| `manager` | View every screen, review pending confirmations and under-review events, approve or reject manual requests and corrections, export reports |
| `admin` | Everything a manager can do, plus: create, edit and deactivate employees, reset passwords, approve or revoke devices, change workplace, Wi-Fi, policy and departments, assign roles up to `admin`, broadcast notifications |
| `superadmin` | Everything an admin can do, plus: grant or revoke `superadmin` and erase (pseudonymise) an employee's personal data |
| `employee` | No dashboard access. Uses the mobile app only. |

Keep the number of `admin` and `superadmin` accounts small. Every change made from
the dashboard is written to the audit log with your name, IP address and the
before and after values.

## 2. Signing in

1. Open `/admin/` and enter your e-mail or employee number and password.
2. On first login, or after a password reset, you must set a new password. It needs
   at least 10 characters, cannot contain your e-mail or employee number, and cannot
   be a common password.
3. Five failed attempts lock the account for 15 minutes. These are the defaults; an
   administrator can change them on the server.
4. Sessions expire after inactivity. Sign out from the user menu on shared computers.

Forgotten password: use "Forgot password" if e-mail (SMTP) is configured. Otherwise
ask another administrator to reset it.

## 3. Screens

### 3.1 Admin overview
These figures are computed live for the selected date in Africa/Johannesburg time:
total employees, currently clocked in, clocked out, absent, late arrivals, pending
confirmations, under review, exceptions, pending manual requests and pending device
approvals. Below them are a 14-day attendance trend, a department breakdown and
recent activity. Click a tile to open the matching filtered list.

### 3.2 Employee directory
Search by name, employee number or e-mail, and filter by department, status and
role. Each row shows the employee's attendance status today and their active device.
- **New employee** (admin): enter employee number, first and last name, e-mail,
  optional phone, department and role. The **temporary password is shown once**.
  Hand it over securely. The employee must change it at first login.

### 3.3 Employee profile
Shows personal details, devices, today's summary and 30-day statistics (present,
late and absent days, worked hours, manual requests). Actions (admin):
- **Edit** details. Changes are audit-logged.
- **Reset password** issues a new temporary password, shown once.
- **Deactivate** (a reason is required) blocks login and revokes all devices and
  sessions immediately. Attendance history is kept. **Reactivate** reverses it.
- **Change role**.
- **Export data** downloads a JSON file of everything held about the employee. Use
  it for POPIA access requests (`PRIVACY-POPIA.md` §7).
- **Erase** (superadmin only) pseudonymises personal data after retention checks.
  You must type the employee number to confirm. This cannot be undone.

### 3.4 Live attendance overview
Lists every active employee's status for today: not started, pending arrival,
clocked in, pending departure, clocked out or missing clock-out. It also shows
clock-in and clock-out times, worked minutes and late flags. Filter by department.
Use this list for roll-calls, for example during an evacuation. It shows the last
confirmed state, not a live location. The system does not track location
continuously.

### 3.5 Attendance records
Shows attendance sessions (one per employee per work day). Filter by date range,
employee, department, status, late and manual. Open a session to see its events,
including detection time, server receipt time, official time, location accuracy,
distance from the workplace, Wi-Fi match and verification notes.
- **Correct a session** (manager and above): change the clock-in or clock-out time.
  A reason is mandatory. The session is marked *corrected* and both the old and new
  values are kept in the audit log.
- **Add a session**: record attendance for an employee who had no phone. A reason is
  mandatory, and the session is marked as admin-sourced.

### 3.6 Pending confirmations
This screen lists events that were detected but not yet confirmed by the employee,
and events that the employee confirmed but the server sent to **review**. Common
review reasons:

| Reason | Typical cause | Suggested action |
|--------|---------------|------------------|
| Outside geofence (distance > radius + accuracy) | Poor GPS, or the employee was genuinely elsewhere | Check the distance and accuracy, and ask the employee if unclear |
| Timestamp skew / offline submission | Phone was offline, or the device clock is wrong | Accept if the device time is plausible |
| Wi-Fi required but not matched | Phone Wi-Fi was off | Accept if the location is fine |
| Departure without open session | Arrival was never confirmed | Approve or reject the departure, then add or correct the session |
| Low verification score | Several weak signals together | Judge case by case |

Actions: **Approve** (optional note) confirms the event and updates the session.
**Reject** (note required) marks it rejected, and the employee is notified. A
pending event that nobody confirms expires after `pending_expiry_hours` (default
12). It **stays visible** here and under Exceptions. It never becomes attendance on
its own.

Workflow for unconfirmed events:
1. Each morning, open this screen with the date set to yesterday.
2. For each expired or pending arrival, contact the employee or check other
   evidence. Then approve it, reject it, or ask the employee to submit a manual
   request.
3. Do not approve events in bulk without looking at them. The confirmation step
   exists so that a detection is not recorded as attendance automatically.

### 3.7 Attendance exceptions
A combined list of missing clock-outs, late arrivals, under-review and rejected
events, expired confirmations, dismissed detections ("not me"), absences, and
events outside the geofence. Filter by date, department and kind. Each item links
to the session or event where you deal with it.

### 3.8 Manual requests and corrections
Employees submit these from the app. Request types are missed clock-in, missed
clock-out, full day, time correction, and profile correction (name, e-mail, phone or
department).
- **Approve**: you may adjust the requested times before approving. The system
  creates or updates the session, or applies the profile change, and notifies the
  employee.
- **Reject**: a note is required, and the employee sees it.
- Employees can cancel their own request while it is pending.

### 3.9 Reports and analytics
Reports: daily, weekly, monthly, by employee, by department, late arrivals,
absences, missing clock-outs, manual attendance, pending confirmations, working
hours. Filters: date range, employee, department, status. Formats: on-screen,
**CSV**, **Excel (XLSX)** and **PDF**. Reports contain personal information. Store
downloaded files securely and delete them when you no longer need them.

Late, absence and overtime figures depend on the policy settings (§3.10). If the
policy is wrong, the reports are wrong.

### 3.10 Workplace configuration (admin)
- **Workplace**: name, address, latitude and longitude, radius (m), accuracy
  threshold (m), dwell seconds, exit debounce seconds, entry and exit detection
  toggles, active flag. The seeded coordinates are **placeholders**. Replace them
  with the real office coordinates before anyone relies on detection.
- **Wi-Fi verification**: enable, SSID, BSSIDs (one per access point,
  `aa:bb:cc:dd:ee:ff`), and "Wi-Fi required".
- **Policy**: working hours, late-after time, grace minutes, standard day, break,
  overtime threshold, working days, pending expiry, missing-clock-out flag time,
  timestamp tolerances, duplicate window, minimum verification score, devices per
  employee, device replacement approval, Play Integrity requirement, retention
  periods. Defaults are placeholders for Meridian policy.
- **Departments**: create, rename, deactivate.
Every save is audit-logged and increments the configuration version. Phones pick
up the new configuration on their next sync.

### 3.11 Device management (admin)
Lists registered phones with model, Android version, app version, status, last seen
time and integrity verdict.
- **Approve**: used when "device replacement requires approval" is on and an
  employee registers a new phone.
- **Revoke** (reason required): the phone can no longer submit attendance. Use this
  for lost or stolen phones and for departing employees.
By default each employee has one active device, and registering a new phone revokes
the old one automatically. That change is audit-logged and the employee is notified.

### 3.12 User roles and permissions
Read-only matrix of roles, their permissions and the number of users in each role.
Change a person's role from their Employee profile.

### 3.13 Audit logs
Search by actor, action, entity, date range or free text. Each entry shows who did
what, when and from which IP address, with before and after values. Audit records
cannot be edited from the dashboard. Retention is `retention_days_audit`.

### 3.14 Broadcast notification (admin)
Sends a title and message to all employees, or to one department, as an in-app
notification (and a push notification if FCM is configured). Do not use it for
personal or sensitive information.

## 4. Routine tasks

| When | Task |
|------|------|
| Daily (morning) | Overview → Pending confirmations (yesterday) → Exceptions (missing clock-outs) → Manual requests |
| Weekly | Weekly report; Devices: pending approvals and devices not seen for 7+ days |
| Monthly | Monthly and working-hours reports for payroll; audit log review for configuration and role changes |
| New employee | Employee directory → New employee → hand over the temporary password and the employee manual |
| Employee leaves | Deactivate (revokes devices). Erase only when the retention period allows (consult HR/legal) |
| Lost phone | Devices → Revoke. The employee installs the app on a new phone and signs in. |
| Office moves | Update the workplace coordinates and radius, then run a short pilot test (`PILOT-TEST-PLAN.md` TC-01/02) |

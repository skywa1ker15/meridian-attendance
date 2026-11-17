# Privacy and POPIA

**This document is not legal advice and does not state that the system complies
with the Protection of Personal Information Act 4 of 2013 (POPIA).** It describes
the technical and organisational measures built into the software. It also lists
the matters that Meridian Group's Information Officer and legal advisers must
review and decide. Compliance depends on how Meridian deploys and operates the
system, on its policies and notices, and on the lawful basis Meridian establishes.
Software features alone do not establish compliance.

## 1. Roles

| Role | Party | Note |
|------|-------|------|
| Responsible party | Meridian Group | Decides the purpose and means of processing |
| Information Officer | _to be named by Meridian_ | Must be registered with the Information Regulator |
| Operators | Hosting provider; Google (Firebase Cloud Messaging, Play Integrity, Play distribution); e-mail provider; off-site backup provider | Each needs a written operator agreement (POPIA s21). Some may process data outside South Africa (§9). |
| Data subjects | Employees and authorised managers | |

## 2. Purpose (purpose specification)

The only purpose is to **record verified workplace attendance** of Meridian
employees at registered workplaces (arrival, departure and hours worked), and to
manage corrections and the related reporting for attendance administration.

The following are **not** purposes and are not supported: monitoring employees'
movements during the day or outside work, productivity scoring, and marketing.
Using the data for any other purpose (for example disciplinary processes based on
location data) needs a separate decision by Meridian and legal review.

## 3. Data inventory and minimisation

| Category | Data | Collected when | Why needed | Where stored | Default retention |
|----------|------|----------------|------------|--------------|-------------------|
| Identity and employment | Employee number, first and last name, work e-mail, phone (optional), department, role, account status | Account creation by an administrator | Identify the employee; login; reporting | PostgreSQL `users`, `departments` | Duration of employment + attendance retention, then pseudonymised |
| Credentials | argon2id password hash; hashed refresh and reset tokens | Login, password change | Authentication | `users`, `refresh_tokens`, `password_reset_tokens` | Tokens expire (30 days / 30 min); hash until erasure |
| Device | Installation id (random UUID), model, manufacturer, Android version, app version, FCM token, integrity verdict, last seen | Login / device registration | Device binding, push delivery, support | `devices` | Until revoked + attendance retention |
| Attendance events | Event type, detection time (device), received time (server), official time, geofence transition, confirmation, status, review notes | At a geofence transition and at confirmation | The attendance record itself | `attendance_events` | `retention_days_attendance` (default 1 825 days ≈ 5 years) |
| **Location at event** | Latitude, longitude, accuracy (m), computed distance from the workplace | **Only** at the transition and at confirmation | Verifying that the event happened at the workplace | `attendance_events` | `retention_days_location` (default 365 days), then pseudonymised (coordinates removed, distance/flags kept) |
| Wi-Fi at event (optional) | SSID and BSSID of the **currently connected** network only, compared with the office network | At the event, if enabled and permitted | Additional verification signal | `attendance_events` | As location |
| Battery level | Percentage at detection | At the event | Diagnosing missed detections | `attendance_events` | As attendance |
| Sessions | Work date, clock-in/out, worked, break, overtime and late minutes, corrections | Derived on confirmation | Attendance reporting | `attendance_sessions` | As attendance |
| Requests | Manual attendance and profile correction requests, reasons, decisions | Employee submits | Corrections | `manual_requests` | As attendance |
| Notifications | Title, body, read status | System generated | Inform the employee | `notifications` | Recommended: 1 year (confirm with backend) |
| Audit log | Actor, action, entity, before/after values, IP, user agent | Admin actions and security events | Accountability, security | `audit_logs` | `retention_days_audit` (default 2 555 days ≈ 7 years) |
| Server logs | Request path, status, client IP, user id, request id | Every request | Operations, security | Docker logs (rotated, ~100 MB per container) | Days to weeks (rotation) |
| Backups | Full database copy | Nightly | Recovery | `deploy/backups/` (+ off-site if configured) | `BACKUP_RETENTION_DAYS` (default 35) |

**Not collected**: continuous or periodic location, routes, location outside
geofence transitions, contacts, photos, messages, browsing history, other apps,
scanned Wi-Fi networks (only the connected one), IMEI or hardware serials,
biometric data, health data, or special personal information (POPIA s26). The app
uses the Android Geofencing API, so Android, not the app, decides when an entry or
exit has occurred. The app does not run continuous GPS.

Minimisation choices:
- Location precision is kept at the moment of the event only. After
  `retention_days_location` the coordinates are removed and only the derived
  verification outcome (inside or outside, distance band) is kept.
- The Wi-Fi SSID and BSSID are compared with configured values. Store only the
  match result if Meridian prefers (integrator: confirm the backend option).
- Pseudonymisation is used for erasure instead of deletion, so that aggregated
  attendance figures remain consistent (§7.3).

## 4. Lawful basis (to be confirmed by Meridian)

Possible justifications under POPIA s11 include: necessity for the performance of
the employment contract (s11(1)(b)); compliance with a legal obligation, for
example records of working time under the Basic Conditions of Employment Act
(s11(1)(c)); and the legitimate interests of the responsible party (s11(1)(f)).
Consent (s11(1)(a)) is generally a weak basis in employment because it must be
voluntary and can be withdrawn.

**Meridian's Information Officer and legal advisers must determine and document the
lawful basis.** They must also decide what happens to an employee who declines
background location (for example, manual clock-in only). The software supports that
alternative through manual requests and admin-created sessions.

## 5. Location privacy notice (draft text for employees)

> Draft for review by Meridian's Information Officer and legal advisers before use.
> Items in [brackets] must be completed by Meridian.

**Meridian Attendance app — how we use your location**

Meridian Group ("Meridian") uses the Meridian Attendance app to record when you
arrive at and leave [the Meridian Group office in Umhlanga / list of workplaces].

*What the app collects.* When your phone's operating system reports that you have
entered or left the workplace area, and when you confirm an arrival or departure,
the app sends us: the time, your phone's location at that moment and its accuracy,
the name of the Wi-Fi network you are connected to (only to check if it is the
office network), your phone model and app version, and your battery level. We also
hold your name, employee number, work e-mail, department and the attendance records
created from your confirmations.

*What the app does not collect.* The app does not track your location continuously
or record where you go during the day or outside work. It does not access your
contacts, photos, messages or other apps.

*Why.* To record your attendance and working hours accurately and to deal with
corrections. [Lawful basis as determined by Meridian.]

*Your confirmation.* A detection is not recorded as attendance until you confirm it
or a manager reviews it.

*Who sees it.* You; your manager[s]; authorised administrators in [HR / IT]. Every
administrator action is logged. Service providers who host the system or deliver
notifications ([hosting provider], Google Firebase) process data on our behalf
under agreements. [State whether any of them processes data outside South Africa.]

*How long.* Attendance records: [5] years. Exact location coordinates: [12 months],
after which they are removed. Audit records: [7] years.

*Your rights.* You may ask to see your information, ask us to correct it (you can
also use *Manual request > Profile correction* in the app), object to processing,
or ask for deletion where the law allows. Contact the Information Officer at
[name, e-mail, phone]. You may complain to the Information Regulator
(inforeg.org.za).

*Choosing not to use location.* [Meridian to state the alternative, e.g. manual
attendance through your manager.]

The text shown in the app's permission onboarding must match the final approved
notice. The Android team holds the in-app wording (`docs/android/`).

## 6. Security safeguards (POPIA s19)

Summarised from `SECURITY.md`: TLS everywhere; argon2id passwords; short-lived
tokens with rotation; RBAC with least privilege; audit logging; device binding and
revocation; database not exposed to the network; secrets outside the code; backups
verified nightly; encrypted token storage on phones (Android Keystore).
Organisational measures (access reviews, staff training, operator agreements, disk
and backup encryption on the host) are Meridian's responsibility.

## 7. Data subject procedures

### 7.1 Access (POPIA s23)
1. The employee requests access from the Information Officer (or HR).
2. Verify the requester's identity.
3. An admin opens Dashboard > Employee profile > **Export data**, which downloads
   a JSON file of all personal information held. Add a readable summary if needed.
4. Deliver it securely within a reasonable time. Record the request, but do not put
   the data itself in the audit log (the export action is logged automatically).

### 7.2 Correction (s24)
- Profile data: the employee submits *Manual request > Profile correction* in the
  app, and an admin approves or rejects it with a note. An admin can also edit the
  profile directly.
- Attendance records: the employee submits a time-correction request. A manager
  approves it, or corrects the session with a mandatory reason. The original values
  are kept in the audit log, which is required for evidential integrity.

### 7.3 Deletion and erasure (s24, s14)
- On termination: **deactivate** the account immediately (revokes access and
  devices). Retain records for the retention period that Meridian's policy sets
  for employment records.
- When the retention period ends, or a valid deletion request is approved by the
  Information Officer, a **superadmin** uses Employee profile > **Erase**. This
  pseudonymises the person: names, e-mail, phone, device identifiers and location
  coordinates are replaced or removed, while the anonymous attendance aggregates
  stay. The action is audit-logged without the erased values.
- Automatic retention: the scheduler pseudonymises location data older than
  `retention_days_location`. **Integrator**: confirm whether automatic
  attendance-record and audit-log purging after `retention_days_attendance` and
  `retention_days_audit` is implemented, or if it is a manual procedure.
- Backups: erased data persists in backups until they expire
  (`BACKUP_RETENTION_DAYS`). Document this in the notice, and do not restore erased
  records into production from a backup without re-applying the erasure.
- Phones: signing out clears tokens and the local queue. Uninstalling removes all
  app data.

### 7.4 Objection (s11(3))
Handled by the Information Officer. The technical alternative is manual attendance
(admin-created sessions or manual requests) without the app.

## 8. Breach response procedure (POPIA s22)

1. **Detect and contain** (hour 0). Whoever suspects a breach (lost backup, leaked
   credential, unauthorised access found in audit logs, compromised server) reports
   it to IT and the Information Officer immediately. IT contains it: rotate secrets
   (`deploy/scripts/rotate-secrets.md`), revoke sessions and devices, isolate the
   host, and preserve logs and evidence (do not wipe).
2. **Assess** (within 24 h). Determine what personal information, whose, how many
   people, the time window, and whether it was encrypted. Sources are `audit_logs`,
   Caddy and API logs, and the host logs.
3. **Notify**. POPIA s22 requires notifying the **Information Regulator** and the
   **affected data subjects** as soon as reasonably possible after discovery,
   unless the identity of the data subjects cannot be established. Use the
   Regulator's prescribed form. Notify employees in writing with a description of
   the possible consequences, the measures taken, recommendations (for example,
   change your password), and the identity of the unauthorised person if known.
   Legal advisers decide the wording and timing.
4. **Remediate**. Fix the root cause, patch, review access, and restore from a clean
   backup if needed.
5. **Record and review**. Log the incident (facts, effects, actions) and hold a
   post-incident review within 2 weeks.

Contacts (Meridian to complete): Information Officer [ ], IT lead [ ], hosting
provider support [ ], legal adviser [ ].

## 9. Matters requiring legal and management review

1. The lawful basis for processing location data at attendance events, and whether
   consent, contract, legal obligation or legitimate interest applies (§4).
2. The final wording of the employee privacy notice and the in-app permission text
   (§5), and how it is delivered and acknowledged.
3. The alternative for employees who decline background location or do not have a
   suitable phone. Also: use of personal (BYOD) phones versus company phones, and
   any compensation for data and battery usage.
4. Retention periods for attendance records, location coordinates, audit logs,
   notifications, server logs and backups. Defaults are 5 years, 1 year, 7 years,
   [ ], rotation and 35 days.
5. Cross-border transfers (s72). Firebase Cloud Messaging and Play Integrity are
   Google services that may process device tokens and identifiers outside South
   Africa. The hosting and backup location must be chosen. Operator agreements
   (s20–21) are needed with all providers.
6. Whether attendance and location records may be used in disciplinary
   proceedings, and the evidential weight of a geofence detection versus a
   confirmation.
7. Prior authorisation (s57). Confirm with counsel whether any processing requires
   prior authorisation from the Information Regulator.
8. Consultation with employees or their representatives (labour-law requirements
   on workplace policies and monitoring), and an update to the employment contract
   or policy.
9. Whether a Personal Information Impact Assessment (PIIA) is required. It is
   recommended before go-live, and Information Officer guidance expects one.
10. Registration of the Information Officer with the Information Regulator.
11. The access matrix: which managers may see which departments. The software
    currently gives managers organisation-wide read access, and a department-scoped
    view would need a change. Confirm that this is acceptable.
12. Google Play **Data safety** declarations and the public privacy policy URL
    (`RELEASE.md` §7) must match this document.

## 10. Configuration related to privacy

| Setting | Where | Default |
|---------|-------|---------|
| `retention_days_attendance` | Dashboard > Policy | 1 825 |
| `retention_days_location` | Dashboard > Policy | 365 |
| `retention_days_audit` | Dashboard > Policy | 2 555 |
| Wi-Fi verification on/off | Dashboard > Workplace | off |
| Play Integrity | env `PLAY_INTEGRITY_ENABLED` | off |
| Push notifications (Google) | env `FCM_ENABLED` | off |
| Backup retention | env `BACKUP_RETENTION_DAYS` | 35 |
| Log level (DEBUG logs more detail) | env `LOG_LEVEL` | INFO |

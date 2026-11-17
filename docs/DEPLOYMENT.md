# Deployment guide — from empty server to employees using the app

This is the end-to-end rollout in the ten steps required by the project brief
(§17E). Server-side detail is in `deploy/README.md`; app release detail in
`RELEASE.md`; values Meridian must supply in `CONFIGURATION-REQUIRED.md`.

**None of these steps has been executed against Meridian infrastructure.** Each
step lists what to verify before moving on.

| Step | Owner | Verification |
|------|-------|--------------|
| 1. Deploy the backend | IT | `/readyz` returns 200 over HTTPS |
| 2. Configure the database | IT | backup file produced and restore tested on staging |
| 3. Configure the workplace | Admin + Facilities | geofence matches the office on the map |
| 4. Create the first administrator | IT | login at `/admin/`, password changed |
| 5. Register employees | HR/Admin | each employee has credentials and a department |
| 6. Install the app on phones | IT / employees | app opens, login works |
| 7. Configure permissions on phones | Employees with IT help | onboarding screen shows all green |
| 8. Test attendance detection | Pilot group | `PILOT-TEST-PLAN.md` pass criteria met |
| 9. Distribute | IT | all employees installed, old versions retired |
| 10. Monitor | IT | alerts reach a human; daily checklist in place |

---

## Step 1 — Deploy the backend

Follow `deploy/README.md` §2–§6: provision an Ubuntu host with Docker, point DNS
`attendance.<domain>` at it, fill `deploy/env/production.env`, then

```bash
docker compose --env-file deploy/env/production.env -f deploy/docker-compose.yml up -d
deploy/scripts/healthcheck.sh https://attendance.<domain>
```

Migrations run automatically from the API container entrypoint. Do a full dress
rehearsal on **staging** first (`deploy/README.md` §13).

## Step 2 — Configure the database

* Bundled PostgreSQL: nothing more to do — it is created by compose with the
  credentials from the env file and backed up nightly by the `backup` sidecar to
  `deploy/backups/`. Set `BACKUP_RCLONE_REMOTE` for an off-site copy.
* Managed PostgreSQL (recommended for production): create a PostgreSQL 16 instance
  with automated backups, create database `meridian_attendance` and a user, set
  `DATABASE_URL` (with `?sslmode=require`) and run only `api` and `caddy`.
* Verify: `docker compose ... exec backup /bin/sh /usr/local/bin/backup.sh` writes a
  file and prints `verification OK`; restore it on staging with
  `deploy/scripts/restore.sh`.

## Step 3 — Configure the company workplace

Log in at `https://attendance.<domain>/admin/` (after step 4) > **Workplace
configuration**.

1. The seeded workplace has **placeholder coordinates**. Replace with the real
   latitude/longitude of the Umhlanga office (authorised management supplies these;
   e.g. right-click the building in Google Maps > copy the coordinates).
2. Radius: start with 150 m for a standalone building; larger for an office park
   where parking is far from the entrance. The radius must exceed typical GPS
   accuracy at the site (check in the pilot: events show `accuracy_m`).
3. Accuracy threshold (default 100 m), dwell seconds (default 120 — prevents drive-by
   arrivals), exit debounce (default 300 — prevents clock-outs on GPS drift).
4. Entry and exit detection toggles.
5. Optional Wi-Fi verification: SSID and the access points' BSSIDs. Leave
   "Wi-Fi required" off initially.
6. **Policy** tab: workday start/end, late-after, grace, standard day, breaks, overtime
   threshold, working days, pending-confirmation expiry, missing-clock-out flag time,
   devices per employee, retention days. Enter Meridian's actual rules; the defaults
   are not Meridian policy.

Every change bumps `config_version`; phones pick it up on their next sync (≤ 15 min
or on app open) and re-register geofences.

## Step 4 — Create the first administrator

```bash
deploy/scripts/create-first-admin.sh production
```

Log in, change the temporary password. Then create the other administrators and
managers in Employees (role `admin` / `manager`). Keep at least two `superadmin`
accounts held by different people; store the recovery procedure (`deploy/scripts/`)
with IT.

## Step 5 — Register employees

Dashboard > **Employees** > New employee: employee number, names, e-mail, phone,
department, role (`employee`). A temporary password is shown once — hand it to the
employee through a secure channel (in person, or the company's password-delivery
practice). The employee must change it at first login. Departments are created under
Workplace configuration > Departments.

Before registering, ensure the location privacy notice has been issued
(`PRIVACY-POPIA.md` §5) — employees should know what is collected before they install.

## Step 6 — Install the Android application on phones

Choose one distribution channel (`RELEASE.md` has the details for each):

| Channel | When | How |
|---------|------|-----|
| **Sideload release APK** | Pilot, small groups, no Play account yet | Signed `app-release.apk` shared via internal file share / MDM; phone: Settings > Apps > Special access > Install unknown apps > allow the file manager/browser; open the APK; Play Protect may ask to scan — allow. Updates are manual. |
| **Google Play internal testing** | Up to 100 testers, fastest Play route | Upload the AAB to the internal track, add tester e-mails, send the opt-in link; updates arrive via Play. |
| **Managed Google Play (enterprise)** | Company-managed phones, private app | Publish as a private app to Meridian's organisation in Play; push via the MDM (Intune, Google Endpoint Management, Knox Manage...). Also allows pre-granting permissions and exempting the app from battery optimisation by policy. |

After install: open the app, sign in with employee number (or e-mail) and the
temporary password, set a new password.

## Step 7 — Configure permissions on phones

The app's onboarding screen walks the employee through these; IT should help on
the first day. Required state:

1. **Location → "Allow all the time"** (Android 10+ shows "While using" first; the
   app then sends the user to Settings for "all the time"). Precise location on.
2. **Notifications** allowed (Android 13+ prompt).
3. **Battery optimisation → Not optimised / Unrestricted** for the app.
4. Location services on; Google Location Accuracy on.
5. Wi-Fi scanning may stay on; it is only read when the employee is on the office
   network and only the SSID/BSSID is used.

Manufacturer-specific steps (Android 12–15; menu names vary by version):

| Manufacturer | Steps |
|--------------|-------|
| **Samsung (One UI)** | Settings > Apps > Meridian Attendance > Battery > **Unrestricted**. Settings > Battery > Background usage limits: make sure the app is **not** in "Sleeping apps" or "Deep sleeping apps"; turn off "Put unused apps to sleep" for this app. Settings > Apps > ⋮ > Special access > Optimise battery usage > All > app off. |
| **Xiaomi / Redmi / POCO (MIUI / HyperOS)** | Settings > Apps > Manage apps > Meridian Attendance > **Autostart ON**; Battery saver > **No restrictions**; Other permissions > "Display pop-up windows while running in the background" and "Show on lock screen" ON. Recents: lock the app (pull down on its card) so it is not killed. Security app > Boost speed: add to exceptions. |
| **Huawei / Honor (EMUI / MagicOS)** | Settings > Apps > Apps > Meridian Attendance > Battery / Power > **App launch: Manage manually** with Auto-launch, Secondary launch and Run in background ON. Settings > Battery > More settings > "Close apps after screen lock" exclude the app. Note: Huawei devices without Google Mobile Services (2019+) cannot run the geofencing API or FCM — see `TROUBLESHOOTING.md`. |
| **Oppo / Realme / OnePlus (ColorOS / OxygenOS)** | Settings > Apps > Meridian Attendance > Battery usage > **Allow background activity**, **Allow foreground activity**, "Allow auto launch" ON. Settings > Battery > More settings > "Optimise battery use" > app off. Recents: lock the app. |
| **Google Pixel / stock Android** | Settings > Apps > Meridian Attendance > Battery > **Unrestricted**. Nothing else. |
| **Vivo / iQOO** | i Manager > App manager > Autostart ON; Settings > Battery > Background power consumption management > Allow high background power consumption. |

Android 13+ also auto-revokes permissions and hibernates apps unused for months:
Settings > Apps > app > "Pause app activity if unused" **off**. A phone that is
restarted re-registers geofences when the app's boot receiver runs — the app must
have been opened at least once after installation.

## Step 8 — Test attendance detection

Run `PILOT-TEST-PLAN.md` with 5–10 volunteers across Samsung, Xiaomi, Huawei/Honor
(with GMS), Oppo/OnePlus and Pixel devices for at least five working days. Do not
roll out to all staff until the pass criteria in that plan are met and the geofence
radius/dwell settings have been tuned from the pilot data (the dashboard's Exceptions
and Pending screens show what detection produced).

## Step 9 — Distribute the application

* Send the installation guide (`EMPLOYEE-MANUAL.md` §1–§3) and the privacy notice to
  all employees.
* Use the chosen channel from step 6 for everyone; for sideload, publish the APK
  with its SHA-256 (`sha256sum app-release.apk`) so IT can verify downloads.
* Track in the dashboard > Devices which employees have registered a device; follow
  up with those who have not after a week.
* Retire old APKs: when the API changes, bump the app version and ask employees to
  update (Play channels update automatically).

## Step 10 — Monitor the production system

* Minimum: external uptime check on `https://attendance.<domain>/readyz`, daily
  `deploy/scripts/healthcheck.sh`, weekly look at Exceptions and Pending screens.
* Recommended: Prometheus/Alertmanager/Grafana stack
  (`deploy/monitoring/README.md`) with the alert rules provided.
* Daily/weekly/monthly operator checklist: `OPERATIONS.md`.
* Review audit logs monthly; test a backup restore quarterly; rotate secrets
  yearly (`deploy/scripts/rotate-secrets.md`).

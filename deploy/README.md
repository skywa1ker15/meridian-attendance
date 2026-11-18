# Production deployment — Meridian Attendance backend

This directory contains everything needed to run the backend (API + administrator
dashboard + PostgreSQL + HTTPS proxy + backups) on a single Docker host. The same
files, with one override, run staging.

**Status**: the configuration has been prepared and reviewed, but **no production or
staging deployment has been performed** — Meridian must supply the items in
§1 before the system can go live. Nothing here has been executed against a real
server or domain.

Contents

```
deploy/
├── docker-compose.yml             production stack (db, api, caddy, backup)
├── docker-compose.staging.yml     override for staging
├── docker-compose.dev.yml         local development (db + api with reload on :8000)
├── Caddyfile                      HTTPS, security headers, /metrics restriction
├── env/
│   ├── production.env.example     every backend setting, with comments (copy to production.env)
│   ├── staging.env.example
│   └── development.env.example
├── secrets/                       (create; git-ignored) fcm-service-account.json, play-integrity-service-account.json, jwt-*.pem
├── backups/                       (created by the backup sidecar) nightly dumps
├── scripts/
│   ├── backup.sh / restore.sh     pg_dump with retention + verified restore
│   ├── create-first-admin.sh      wraps `python -m app.cli create-admin`
│   ├── healthcheck.sh             external smoke test (/healthz, /readyz, headers, cert)
│   ├── rotate-secrets.md          rotation procedures
│   └── logs.md                    where logs are and how to read them
└── monitoring/                    prometheus.yml, alerts.yml, alertmanager.yml, optional compose stack
```

---

## 1. Information required to activate production

Collect these before starting. Nothing can be invented by the development team.

| # | Item | Used for | Where it goes |
|---|------|----------|---------------|
| 1 | **Public domain name** for the API/dashboard (e.g. `attendance.meridiangroup.co.za`) and the ability to create its DNS A/AAAA record | HTTPS certificate, app configuration, password-reset links | `DOMAIN`, `PUBLIC_BASE_URL` in `env/production.env`; `MERIDIAN_API_BASE_URL` for the Android release build |
| 2 | **Server**: Ubuntu 22.04/24.04 LTS (or any Linux with Docker) — 2 vCPU, 4 GB RAM, 40 GB SSD is sufficient for a few hundred employees; public IPv4; ports 80/443 open; SSH access for IT | Hosting | — |
| 3 | **Database credentials** — either generated for the bundled PostgreSQL container or the connection details of a managed PostgreSQL 16 (recommended for production: automated backups, point-in-time recovery) | `POSTGRES_*` or `DATABASE_URL` | `env/production.env` |
| 4 | **Microsoft 365 e-mail sending** — Entra app registration with Graph `Mail.Send`, see `docs/EMAIL-SETUP.md`; without it employees cannot be invited and administrators hand out temporary passwords | Invitation and password-reset e-mails | `EMAIL_PROVIDER`, `MS_GRAPH_*`, `EMAIL_SENDER_ADDRESS` (or `SMTP_*`) |
| 5 | **Firebase project** for package `za.co.meridiangroup.attendance`: (a) `google-services.json` for the Android build, (b) a service-account JSON key with the *Firebase Cloud Messaging API Admin* role, (c) the project id — optional; without it the app polls instead of receiving push | Push notifications | `android/app/google-services.json`; `deploy/secrets/fcm-service-account.json`; `FCM_PROJECT_ID`, `FCM_ENABLED=true` |
| 6 | **Google Play Console** developer account (organisation, USD 25 one-off) — needed only for Play distribution; sideloading/MDM does not need it | App distribution | `docs/RELEASE.md` |
| 7 | **Android signing keystore** generated and held by Meridian (never by the development team) | Signing the release APK/AAB | CI secrets or local `~/.gradle/gradle.properties` — `docs/RELEASE.md` |
| 8 | **Official Meridian Group logo** (SVG or 1024×1024 PNG) and brand colours if they differ from the design system | App icon, splash, dashboard, store listing | `docs/RELEASE.md` §Branding |
| 9 | **Workplace coordinates**: latitude/longitude of the Umhlanga office, desired geofence radius (m), street address, and whether entry/exit detection is enabled | Geofence | Dashboard > Workplace configuration (the seeded workplace is a **placeholder**) |
| 10 | **Company Wi-Fi** SSID and BSSIDs (access-point MAC addresses) if Wi-Fi verification is wanted | Additional verification signal | Dashboard > Workplace configuration |
| 11 | **Employment policy values**: workday start/end, late-after time, grace minutes, standard day minutes, break minutes, overtime threshold, working days, pending-confirmation expiry, missing-clock-out flag time, devices per employee, retention periods | Late/overtime computation, reports | Dashboard > Workplace configuration > Policy (defaults are **not** Meridian policy) |
| 12 | **Employee list** (employee number, names, e-mail, phone, department, role) | Account creation | Dashboard > Employees (or CSV import if provided by the backend) |
| 13 | **Information Officer** contact and the approved location-privacy notice wording | POPIA | `docs/PRIVACY-POPIA.md` |
| 14 | **Alert recipients** (e-mail / Teams / Slack webhook) and an ACME contact e-mail | Monitoring, certificate expiry notices | `ACME_EMAIL`, `monitoring/alertmanager.yml` |

`docs/CONFIGURATION-REQUIRED.md` is the single checklist version of this table.

---

## 2. Provision the host

```bash
# Ubuntu 24.04 LTS, as a sudo user
sudo apt-get update && sudo apt-get -y upgrade
sudo apt-get -y install ca-certificates curl git ufw unattended-upgrades jq

# Docker Engine + Compose plugin (official repository)
sudo install -m 0755 -d /etc/apt/keyrings
curl -fsSL https://download.docker.com/linux/ubuntu/gpg | sudo gpg --dearmor -o /etc/apt/keyrings/docker.gpg
echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.gpg] https://download.docker.com/linux/ubuntu $(. /etc/os-release && echo $VERSION_CODENAME) stable" | sudo tee /etc/apt/sources.list.d/docker.list
sudo apt-get update && sudo apt-get -y install docker-ce docker-ce-cli containerd.io docker-compose-plugin
sudo usermod -aG docker "$USER" && newgrp docker

# Firewall: SSH + HTTP/HTTPS only
sudo ufw default deny incoming && sudo ufw default allow outgoing
sudo ufw allow OpenSSH && sudo ufw allow 80/tcp && sudo ufw allow 443/tcp && sudo ufw allow 443/udp
sudo ufw enable

# Time (timestamps are UTC; host clock must be correct for TLS and JWT validation)
sudo timedatectl set-timezone UTC && timedatectl | grep -i "ntp service"

# Code
sudo mkdir -p /opt/meridian-attendance && sudo chown "$USER" /opt/meridian-attendance
git clone <repository-url> /opt/meridian-attendance
cd /opt/meridian-attendance
```

Hardening that is expected but outside this repository: SSH key-only login, fail2ban
or equivalent, automatic security updates (`unattended-upgrades` installed above),
disk encryption on the volume holding `/var/lib/docker` and `deploy/backups`.

## 3. DNS

Create an `A` record (and `AAAA` if the host has IPv6) for `attendance.<domain>`
pointing at the server's public IP. Wait until `dig +short attendance.<domain>`
returns it. Caddy obtains the certificate from Let's Encrypt automatically on first
start; this fails (and retries with back-off) if DNS is not yet correct or port 80
is blocked.

## 4. Environment

```bash
cd /opt/meridian-attendance
cp deploy/env/production.env.example deploy/env/production.env
chmod 600 deploy/env/production.env
mkdir -p deploy/secrets deploy/backups && chmod 700 deploy/secrets
# optional: copy Firebase / Play Integrity service-account JSON into deploy/secrets/
```

Edit `deploy/env/production.env` and replace **every** `<<...>>` placeholder. Generate
secrets with:

```bash
openssl rand -hex 32                     # SECRET_KEY
openssl rand -base64 32 | tr -d '/+='    # POSTGRES_PASSWORD
openssl rand -hex 24                     # METRICS_TOKEN
```

The backend refuses to start in production with a weak `SECRET_KEY`, a development
`DATABASE_URL` or a non-https `PUBLIC_BASE_URL`; the error is printed in
`docker compose logs api`.

Using a managed PostgreSQL instead of the `db` container: set `DATABASE_URL` in the
env file **and** remove the `DATABASE_URL:` line under `api.environment` in
`docker-compose.yml` (compose-level values override the env file), then start without
the `db` and `backup` services (`docker compose up -d api caddy`) and rely on the
provider's backups.

## 5. Build and start

```bash
cd /opt/meridian-attendance
export COMPOSE="docker compose --env-file deploy/env/production.env -f deploy/docker-compose.yml"

# Option A: build the image on the server
$COMPOSE build api
# Option B (preferred): use the image published by CI on tag pushes (see .github/workflows/backend.yml)
#   set API_IMAGE=ghcr.io/<org>/meridian-attendance-api:1.0.0 in the env file and run `$COMPOSE pull api`

$COMPOSE up -d
$COMPOSE ps              # all four services "healthy" after ~1 minute
$COMPOSE logs -f api     # watch migrations, then "Application startup complete"
```

Migrations run **automatically**: the API container's entrypoint executes
`alembic upgrade head` before starting the server; `/readyz` reports
`"migrations": "head"` only when the schema matches the code.

## 6. Verify

```bash
deploy/scripts/healthcheck.sh https://attendance.<domain>
curl -s https://attendance.<domain>/healthz     # {"status":"ok","version":"1.0.0"}
curl -s https://attendance.<domain>/readyz      # {"status":"ready","database":"ok","migrations":"head"}
```

Open `https://attendance.<domain>/admin/` — the login screen must load with a valid
certificate (padlock). `https://attendance.<domain>/api/docs` shows the OpenAPI UI
(consider hiding it on production — see the commented block in `Caddyfile`).

## 7. Create the first administrator

```bash
deploy/scripts/create-first-admin.sh production
```

Follow the prompts (employee number, names, e-mail). The generated temporary
password is printed once; log in at `/admin/`, change the password when prompted.
This account has the `superadmin` role. Create further administrators and managers
from the dashboard rather than with the CLI.

## 8. Configure the workplace, Wi-Fi and policy

1. Dashboard > **Workplace configuration**. The seeded workplace "Meridian Group —
   Umhlanga" carries **placeholder coordinates** — they are not the office. Enter the
   real latitude/longitude (decimal degrees, e.g. from Google Maps "What's here?"),
   radius (100–200 m is typical for an office building; smaller than the GPS
   accuracy threshold makes detection unreliable), accuracy threshold, dwell seconds
   and exit debounce seconds. Save. The app picks up the new `config_version` on its
   next sync and re-registers geofences.
2. Same screen > **Wi-Fi verification** (optional): SSID and the BSSIDs of the
   office access points. Keep `wifi_required` off unless every employee phone can
   see the office Wi-Fi — otherwise every arrival goes to review.
3. Same screen > **Policy**: set Meridian's working hours, grace period, overtime
   threshold, working days, retention periods. The defaults are software defaults,
   not Meridian's employment rules.

## 9. Register employees and distribute the app

Dashboard > Employees > New employee (department, role, temporary password shown
once). Distribution of the Android app: `docs/DEPLOYMENT.md` §6–7 and
`docs/RELEASE.md`.

## 10. Backups

* Nightly at `BACKUP_HOUR_UTC`:05 the `backup` sidecar writes
  `deploy/backups/meridian_<timestamp>Z.dump.gz`, verifies it, deletes dumps older
  than `BACKUP_RETENTION_DAYS`, and optionally copies it off-site (`BACKUP_RCLONE_REMOTE`).
* Manual backup: `$COMPOSE exec backup /bin/sh /usr/local/bin/backup.sh`
* Restore: `deploy/scripts/restore.sh deploy/backups/<file> production` (destructive; prompts).
* Also back up: `deploy/env/production.env`, `deploy/secrets/`, and the `caddy_data`
  volume (`docker run --rm -v meridian-attendance_caddy_data:/d -v $PWD:/out alpine tar czf /out/caddy_data.tgz -C /d .`).
* **Test a restore on staging at least once before go-live and quarterly thereafter.**
  Backups contain personal information: store them encrypted and apply the retention
  rules in `docs/PRIVACY-POPIA.md`.

## 11. Upgrades

```bash
cd /opt/meridian-attendance
$COMPOSE exec backup /bin/sh /usr/local/bin/backup.sh     # 1. backup first
git fetch --tags && git checkout v1.1.0                      # 2. new release tag
diff deploy/env/production.env.example deploy/env/production.env  # 3. check for new settings
$COMPOSE pull api || $COMPOSE build api                      # 4. new image
$COMPOSE up -d api                                           # 5. recreate; migrations run on start
$COMPOSE logs --tail 100 api && deploy/scripts/healthcheck.sh https://attendance.<domain>
```

Downtime is the container restart (seconds). Release the Android app **after** the
backend when an upgrade changes the API (`docs/RELEASE.md`); the API is versioned
under `/api/v1` and must stay backward compatible with the oldest app version still
installed.

## 12. Rollback

```bash
git checkout v1.0.0 && $COMPOSE up -d api        # previous image/code
```

If the new version applied a migration that the old code cannot run with, restore the
pre-upgrade backup instead: `deploy/scripts/restore.sh deploy/backups/<pre-upgrade file> production`
(then start the old version). Migrations are written to be additive where possible,
but always keep the pre-upgrade dump until the new version has run for a full day.

## 13. Staging

Identical procedure with a second host or a second compose project on the same host
(different `DOMAIN`, e.g. `attendance-staging.<domain>`):

```bash
cp deploy/env/staging.env.example deploy/env/staging.env && chmod 600 deploy/env/staging.env
docker compose --env-file deploy/env/staging.env -f deploy/docker-compose.yml -f deploy/docker-compose.staging.yml up -d
```

Staging defaults to the Let's Encrypt staging CA (untrusted certificate, so phones
will refuse to connect unless the real CA is used — set `ACME_CA` in `staging.env` to
the production directory when testing real phones). Seed demo data on staging only:
`docker compose ... exec api python -m app.cli seed-demo` (refused when `APP_ENV=production`).

## 14. Monitoring

See `deploy/monitoring/README.md` (uptime check on `/readyz` at minimum; Prometheus
stack optional) and `docs/OPERATIONS.md`.

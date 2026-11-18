# Troubleshooting

`COMPOSE` below means
`docker compose --env-file deploy/env/production.env -f deploy/docker-compose.yml`.
Log guidance is in `deploy/scripts/logs.md`. Every API error response carries an
`X-Request-Id`; search the API log for it.

## 1. Server and deployment

| Symptom | Likely cause | Fix |
|---------|--------------|-----|
| `api` container restarts; log says `SECRET_KEY must be a random value...` | Placeholder or weak secret in production or staging | `openssl rand -hex 32` → `SECRET_KEY`, then `$COMPOSE up -d api` |
| Log says `DATABASE_URL points at a development database` | Dev URL, `localhost` or `127.0.0.1` in staging/production | Use the compose-provided URL (host `db`) or the managed DB host |
| Log says `PUBLIC_BASE_URL must use https://` | Env value is http | Set `https://attendance.<domain>` |
| `/readyz` returns 503 with `database` not ok | DB down, wrong credentials, or network | `$COMPOSE ps db`, `$COMPOSE logs db`; check that `POSTGRES_PASSWORD` matches the password the volume was initialised with (changing the env value later does **not** change the DB password — see `rotate-secrets.md`) |
| `/readyz` reports migrations not at head | Migration failed at start-up | `$COMPOSE logs api` for the Alembic error; fix it, then `$COMPOSE up -d api`. If a migration half-applied, restore the pre-upgrade backup. |
| Browser shows a certificate error / Caddy log `obtaining certificate ... error` | DNS not yet pointing at the host, port 80/443 blocked, or LE rate limit | `dig +short <domain>`; `sudo ufw status`; cloud firewall/security group; wait for back-off; use the LE staging CA while experimenting |
| Phone cannot connect to staging | Staging uses the LE **staging** CA (untrusted) | Set `ACME_CA=https://acme-v02.api.letsencrypt.org/directory` in `staging.env` and recreate caddy |
| `502 Bad Gateway` from Caddy | API not healthy or restarting | `$COMPOSE ps`, `$COMPOSE logs api` |
| `/metrics` returns 403 | Request not from `METRICS_ALLOW_CIDR` | Scrape from the internal network (`api:8000`) or adjust the matcher in `Caddyfile` |
| `/metrics` returns 401 | `METRICS_TOKEN` set, scrape without the bearer token | Put the token in `deploy/monitoring/metrics-token` |
| All clients rate-limited together (429) | `TRUST_PROXY_HEADERS` false behind Caddy, so every request appears to come from the proxy IP | Compose sets it to true; check that nobody overrode it |
| Disk full | Backups, Docker images, logs | `df -h`; `docker system prune` (careful); lower `BACKUP_RETENTION_DAYS`; move backups off-site |
| Backup log `dump failed verification` | Disk full or DB error during the dump | Check disk; run `backup.sh` manually; fix before the next night |
| Invitation or password-reset e-mails not arriving | E-mail not configured, or the provider rejected the message | `GET /admin/email/status`, then `POST /admin/email/test`; API log for the Graph/SMTP error code; `docs/EMAIL-SETUP.md` §9; meanwhile admins resend the invitation or issue a temporary password from the dashboard |
| Employee cannot sign in, error says the account is not set up | The invitation was never accepted | Employee profile → Resend invitation (the old link stops working) |
| Push not delivered | `FCM_ENABLED=false`, missing service-account file, wrong project id, or stale FCM token | Check env and `deploy/secrets/`; API log for FCM errors; the app re-sends its token on open; the in-app inbox still works |

## 2. Dashboard

| Symptom | Fix |
|---------|-----|
| Blank page / CSP errors in the browser console | Do not inject third-party scripts. The dashboard is self-contained. Clear the cache after an upgrade. |
| "Forbidden" on an action | The role lacks the permission (`ADMIN-MANUAL.md` §1). Ask an admin. |
| Account locked (423) | Wait `LOGIN_LOCKOUT_MINUTES` or ask another admin to reset the password |
| Statistics look wrong (everyone late or absent) | Policy times (`late_after_local`, working days) or the timezone are not set to Meridian's values |
| Report export slow or times out | Narrow the date range; Caddy read timeout is 120 s |

## 3. Android app

| Symptom | Likely cause | Fix |
|---------|--------------|-----|
| No arrival notifications at all | Location not "Allow all the time"; notifications blocked; battery restricted; the app was force-stopped and never reopened; location services off | Open the app and complete every onboarding item; apply the manufacturer steps in `DEPLOYMENT.md` §7 |
| Notifications arrive many minutes late | Android batches background location (normal: up to several minutes); Doze; manufacturer battery manager | Unrestricted battery; lock the app in recents (Xiaomi/Oppo); the detection time is still recorded |
| Detected while driving past | Radius too large or dwell too short; road within the radius | Employee dismisses; admin reduces the radius or increases `dwell_seconds` (pilot data) |
| False departure while inside | GPS drift in a large building or basement | Increase `exit_debounce_seconds` and/or the radius; the employee must not confirm |
| Many events "under review: outside geofence" | Wrong workplace coordinates (placeholder still in place?) or radius smaller than GPS accuracy | Check the coordinates on a map; raise the radius or accuracy threshold |
| Every arrival under review with Wi-Fi reason | "Wi-Fi required" on, but phones not on the office Wi-Fi or SSID/BSSID wrong | Turn off "Wi-Fi required" or fix the BSSIDs (case/format `aa:bb:cc:dd:ee:ff`) |
| App says "device revoked" | Signed in on another phone, or an admin revoked it | Sign in again; the admin approves if replacement approval is on |
| App says "device pending approval" | Policy requires approval | Admin: Devices > Approve |
| "Token reused" → forced logout | Refresh token replayed (e.g. app data restored from a backup onto a second phone) | Sign in again; investigate if unexpected |
| Sync indicator stuck on "waiting" | No connectivity, server down, or events rejected | Check `/readyz`; open the app on a good connection; rejected items show an error with a reason |
| Event rejected "timestamp implausible" | Phone clock far off, or offline too long (> 72 h) | Turn on automatic date & time; submit a manual request |
| Huawei phone: no detection | Device without Google Mobile Services (no Geofencing API / FCM) | Not supported for automatic detection; use manual requests or a supported phone |
| After a phone update, nothing works | Permissions reset or app hibernated | Open the app; disable "Pause app activity if unused" |
| Release build fails: `Release build refused: MERIDIAN_API_BASE_URL is missing or invalid` | No https production URL given | Pass `-PMERIDIAN_API_BASE_URL=https://...` or set the CI secret `API_BASE_URL` |
| Release APK will not install ("App not installed") | Unsigned artefact, or signed with a different key than the installed version | Sign with Meridian's key; uninstall the old build if it was signed with a debug key |
| Debug build cannot reach the dev server from a phone | Phone and workstation on different networks; firewall; emulator alias 10.0.2.2 used on a real phone | Set `MERIDIAN_DEBUG_API_BASE_URL=http://<LAN-IP>:8000`; open port 8000 |

## 4. Collecting diagnostics from a phone

```bash
adb logcat -d | grep -i -E "meridian|geofence|WorkManager" > meridian-logcat.txt
adb shell dumpsys deviceidle whitelist | grep meridian          # battery exemption
adb shell dumpsys activity processes | grep -i meridian
adb shell cmd appops get za.co.meridiangroup.attendance           # granted ops
adb shell dumpsys jobscheduler | grep -A3 za.co.meridiangroup     # WorkManager jobs
```

Send only to IT. Logs may contain personal information.

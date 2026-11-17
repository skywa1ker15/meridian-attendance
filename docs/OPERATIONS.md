# Operations guide

For the people who run the production system after go-live. Deployment and
upgrades are in `deploy/README.md`.

```bash
cd /opt/meridian-attendance
export COMPOSE="docker compose --env-file deploy/env/production.env -f deploy/docker-compose.yml"
```

## 1. Routine checklist

| Frequency | Task | How |
|-----------|------|-----|
| Continuous | Uptime alert on `/readyz` | External monitor or Prometheus `MeridianApiDown` / `MeridianApiNotReady` |
| Daily | Containers healthy | `$COMPOSE ps` (all `healthy`) |
| Daily | Backup succeeded | `$COMPOSE logs --since 24h backup \| grep -E "verification OK\|FAILED"`; `ls -lh deploy/backups \| tail -3` |
| Daily (managers) | Pending confirmations, exceptions, manual requests | Dashboard (`ADMIN-MANUAL.md` §4) |
| Weekly | Error review | `$COMPOSE logs --no-log-prefix --since 7d api \| jq -c 'select(.status>=500)'` |
| Weekly | Disk space | `df -h /` (alert < 15%) |
| Weekly | Devices not seen for 7+ days | Dashboard > Devices (sort by last seen); follow up with employees |
| Monthly | Host security updates | `sudo apt-get update && sudo apt-get upgrade` (unattended-upgrades covers security); reboot in a quiet window (weekend night) |
| Monthly | Audit review | Dashboard > Audit logs: role changes, config changes, erasures, failed logins |
| Monthly | Image updates | Rebuild or pull to pick up base-image security fixes; `docker image prune` |
| Quarterly | **Restore test** | Restore the latest dump on staging with `deploy/scripts/restore.sh`; log in; check record counts |
| Quarterly | Access review | Who has `manager`/`admin`/`superadmin`; who has SSH and env-file access |
| Yearly | Secret rotation | `deploy/scripts/rotate-secrets.md` |
| Yearly | Retention and privacy review | With the Information Officer (`PRIVACY-POPIA.md`) |

## 2. Monitoring

See `deploy/monitoring/README.md`. Signals:

- **Availability**: `/healthz` (process up, no DB), `/readyz` (DB + migrations).
- **Metrics**: `/metrics` (Prometheus) for request rate, status codes and latency,
  plus PostgreSQL and host metrics via exporters.
- **Alerts** (`deploy/monitoring/alerts.yml`): API down or not ready, DB down, 5xx
  ratio > 2%, error bursts, p95 > 1.5 s, login brute force, disk < 15% / < 5%, memory,
  CPU, DB connections, backup older than 36 h, certificate expiring in under 14 days.
- **Errors**: optional Sentry (`SENTRY_DSN`).
- **Business signals** (dashboard): a sudden rise in expired or under-review events
  usually means a configuration problem (coordinates, Wi-Fi) or an app problem after
  an Android update, not employee behaviour.

## 3. Backups and recovery

| Item | Policy |
|------|--------|
| What | Full PostgreSQL dump (custom format, gzip), verified with `pg_restore --list` |
| When | Nightly at `BACKUP_HOUR_UTC`:05 (default 02:05 SAST) by the `backup` sidecar |
| Where | `deploy/backups/` on the host, plus off-site via `BACKUP_RCLONE_REMOTE` (strongly recommended: a host loss would otherwise lose the backups too) |
| Retention | `BACKUP_RETENTION_DAYS` (default 35), local and remote |
| Also back up | `deploy/env/*.env`, `deploy/secrets/`, the `caddy_data` volume — in the password manager or an encrypted store, **not** next to the DB dumps in the same bucket |
| Managed DB | Use the provider's automated backups and point-in-time recovery (PITR); keep the nightly logical dump as a second copy |
| RPO / RTO (targets to agree with Meridian) | RPO ≤ 24 h with nightly dumps (≤ 5 min with managed-DB PITR); RTO ≤ 4 h to rebuild a host from the repo + env + latest dump |

Disaster recovery on a new host: provision it (`deploy/README.md` §2), restore the
env file and secrets, `$COMPOSE up -d db`, run `deploy/scripts/restore.sh <latest dump>`,
then `$COMPOSE up -d`, and repoint DNS. Phones keep their offline queues and sync
when the API is back. Idempotent client ids prevent duplicates.

## 4. Log review

`deploy/scripts/logs.md`. Container logs rotate at 20 MB × 5 per service. Ship
them to central logging if longer retention is required. Logs contain user ids and
IP addresses (personal information), so keep retention short and access restricted.

## 5. Scheduler jobs

The API runs an in-process scheduler every `SCHEDULER_INTERVAL_SECONDS` (60 s) when
`SCHEDULER_ENABLED=true` (`ARCHITECTURE.md` §3):

| Job | Effect | Policy setting |
|-----|--------|----------------|
| Expire stale pending events | `pending` → `expired` (still visible to management; never attendance) | `pending_expiry_hours` |
| Confirmation reminders | Re-notify the employee about an unconfirmed event | `CONFIRMATION_REMINDER_MINUTES` |
| Flag missing clock-outs | Open sessions after the flag time become `missing_clock_out` | `missing_clock_out_flag_local` |
| Location retention | Pseudonymise event coordinates older than the retention period | `retention_days_location` |
| Idempotency key cleanup | Remove stored replay responses older than the TTL | `IDEMPOTENCY_TTL_HOURS` |

Rules:
- Exactly **one** process should run the scheduler. With several API replicas or
  workers, set `SCHEDULER_ENABLED=false` on all but one, or disable it everywhere
  and run `python -m app.cli jobs run-once` from host cron:
  ```cron
  * * * * * cd /opt/meridian-attendance && docker compose --env-file deploy/env/production.env -f deploy/docker-compose.yml exec -T api python -m app.cli jobs run-once >> /var/log/meridian-jobs.log 2>&1
  ```
  **Integrator**: confirm whether jobs take a database advisory lock. If they do,
  running them on several workers is safe; if they don't, keep to the single-runner
  rule above.
- After a long outage, the first run catches up: many events may expire at once.
  Review them in the dashboard.

## 6. Scaling

The single-host setup is sized for hundreds of employees. Load is bursty, at
arrival and departure times, and small (a few requests per employee per day plus a
sync every ~15 min).

| Need | Action |
|------|--------|
| More API throughput | Increase gunicorn workers in the image or command (≈ 2 × vCPU). **Note**: rate limits are per process, so effective limits multiply by the number of workers/replicas (`SECURITY.md` §2.6). Add Caddy-level or shared-store limits if this matters. |
| Several API replicas | Put the replicas behind Caddy (`reverse_proxy api-1:8000 api-2:8000`) or a load balancer; run the scheduler on one only; the in-memory idempotency store (if not DB-backed — integrator to confirm) must become shared |
| Database | Move to managed PostgreSQL 16 with PITR, set `DATABASE_URL`; tune `DB_POOL_SIZE` and `DB_MAX_OVERFLOW` so that (workers × (pool + overflow)) < `max_connections` |
| Several workplaces | Add workplaces in the dashboard. The app registers geofences for all active workplaces. Android allows 100 geofences per app. |
| Reporting load | Run large exports outside peak times; add read replicas if necessary |
| High availability | Two hosts + managed DB + load balancer. Not included in this configuration. |

## 7. Upgrades and changes

- Backend: `deploy/README.md` §11–12 (backup → pull/build → `up -d` → healthcheck;
  rollback via the previous tag or a backup restore).
- App: `RELEASE.md`. The backend goes first and must stay compatible with the
  oldest installed app version.
- Configuration (workplace, policy): through the dashboard. Changes are audit-logged
  and pushed to phones on their next sync. Tell employees about changes in
  advance.

## 8. Incident handling

1. Check `/readyz`, `$COMPOSE ps` and the API and Caddy logs.
2. Restart the affected service if needed (`$COMPOSE restart api`). Phones queue
   events offline during an outage, so no attendance is lost if the outage is shorter
   than `max_offline_age_hours`.
3. After recovery, review Pending and Exceptions for the outage window.
4. If personal information may be involved, follow `PRIVACY-POPIA.md` §8
   immediately.
5. Write a short incident record covering timeline, cause, impact and follow-ups.

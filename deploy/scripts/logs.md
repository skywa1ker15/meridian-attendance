# Logs — where they are and how to read them

All containers log to stdout/stderr; Docker keeps them as rotated JSON files
(20 MB x 5 per container, configured in `docker-compose.yml`). Nothing is written
inside the containers (the API filesystem is read-only).

```bash
cd /opt/meridian-attendance            # or wherever the repo is checked out
export COMPOSE="docker compose --env-file deploy/env/production.env -f deploy/docker-compose.yml"

$COMPOSE logs -f api                   # live API log (JSON lines)
$COMPOSE logs --since 1h api           # last hour
$COMPOSE logs --tail 200 caddy         # proxy access log (JSON), TLS/ACME messages
$COMPOSE logs db                       # PostgreSQL (slow queries >1 s are logged)
$COMPOSE logs backup                   # nightly backup results
```

## API log format

`LOG_JSON=true` (production) emits one JSON object per line
(`backend/app/core/logging.py`):

```json
{"ts":"2026-11-16T06:02:11.123Z","level":"INFO","logger":"meridian.http","msg":"POST /api/v1/attendance/events -> 201 (12.4 ms)",
 "request_id":"3f9c...","method":"POST","path":"/api/v1/attendance/events","status":201,"duration_ms":12.4,"client":"41.x.x.x"}
```

* `request_id` is also returned to the client as the `X-Request-Id` header. Ask an
  employee or administrator for the request id shown in an error dialog to find the
  exact log line.
* `/healthz`, `/readyz` and `/metrics` requests are not logged (noise).
* Request bodies, passwords and tokens are never logged. Logs do contain user ids
  and client IPs: they are personal information under POPIA — keep retention short
  (Docker rotation above) and restrict host access.

Useful one-liners (need `jq`):

```bash
$COMPOSE logs --no-log-prefix --since 24h api | jq -c 'select(.status >= 500)'          # server errors
$COMPOSE logs --no-log-prefix --since 24h api | jq -c 'select(.status == 429)'          # rate-limited clients
$COMPOSE logs --no-log-prefix --since 24h api | jq -c 'select(.request_id == "3f9c...")'
$COMPOSE logs --no-log-prefix --since 24h api | jq -r 'select(.duration_ms > 1000) | "\(.duration_ms) \(.path)"'
```

## Shipping logs elsewhere

For central retention use any Docker log driver or agent, for example:

* **Grafana Loki**: run `grafana/promtail` with the Docker service-discovery config and
  point it at the compose project label `com.docker.compose.project=meridian-attendance`.
* **journald**: change `logging.driver` to `journald` in `docker-compose.yml`, then
  `journalctl CONTAINER_NAME=meridian-attendance-api-1 -f`.
* **Sentry** (errors only): set `SENTRY_DSN` in the env file.

## What to look at when...

| Symptom | Look at |
|---------|---------|
| App shows "server unavailable" | `caddy` log for upstream errors; `api` healthcheck (`docker compose ps`) |
| Certificate problems | `caddy` log lines containing `acme` or `obtain` |
| Login failures / lockouts | `api` log: 401/423 on `/api/v1/auth/login`; `audit_logs` table (filter by action in the dashboard Audit logs screen) |
| Events stuck pending / not expiring | `api` log lines mentioning the scheduler; `SCHEDULER_ENABLED` must be true in exactly one instance |
| Slow dashboard | `db` log (statements over 1000 ms), `api` `duration_ms` |
| Push not delivered | `api` log lines mentioning FCM (response codes from the FCM HTTP v1 API); `FCM_ENABLED`, service-account file |

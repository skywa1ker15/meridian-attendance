# Monitoring

Three levels are prepared; use what fits Meridian's IT setup.

## 1. Minimum (no extra software): uptime monitor + health script

* Point an external uptime service (UptimeRobot, Better Stack, Pingdom, a Zabbix
  instance you already run, ...) at `https://attendance.<domain>/readyz`, expecting
  HTTP 200 and the string `"status":"ready"`, every 1–5 minutes, alerting IT by
  e-mail/SMS. `/readyz` returns 503 when the database is unreachable or migrations
  are not at head, so it covers the API and the DB.
* Add a host cron that runs `deploy/scripts/healthcheck.sh https://attendance.<domain>`
  daily and mails output on failure (it also checks certificate expiry).
* Check `docker compose ps` and the `backup` container log weekly (see
  `docs/OPERATIONS.md` checklist).

## 2. Recommended: Prometheus + Alertmanager + Grafana on the same host

```bash
cd /opt/meridian-attendance
echo "<METRICS_TOKEN from deploy/env/production.env>" > deploy/monitoring/metrics-token
chmod 600 deploy/monitoring/metrics-token
# edit deploy/monitoring/alertmanager.yml with a real receiver (e-mail / Teams / Slack webhook)
echo "GRAFANA_ADMIN_PASSWORD=$(openssl rand -base64 18)" >> deploy/env/production.env
docker compose --env-file deploy/env/production.env \
  -f deploy/docker-compose.yml -f deploy/monitoring/docker-compose.monitoring.yml up -d
ssh -L 3000:127.0.0.1:3000 <user>@<server>     # then open http://localhost:3000
```

In Grafana add a Prometheus data source (`http://prometheus:9090`) and import:

* Node Exporter Full — dashboard id 1860
* PostgreSQL Database — dashboard id 9628
* For the API, build a small dashboard from the API's own metrics (`/metrics`):
  requests per second by status, p95 latency, pending-events gauge if exposed, and
  `meridian_backup_last_success_timestamp_seconds`.

Alert rules in `alerts.yml` cover: API down, API not ready, DB down, 5xx ratio and
bursts, slow requests, login brute force, disk/memory/CPU, PostgreSQL connections,
stale backups, TLS expiry (needs blackbox-exporter or the health script).

**Integrator note**: the API HTTP metric names in `alerts.yml` must be confirmed
against the live `/metrics` output; the `up`/exporter rules work as-is.

## 3. Error tracking (optional)

Set `SENTRY_DSN` in the env file to send unhandled exceptions (with request id, no
request bodies) to a Sentry project. Not required for operation.

## What "normal" looks like

* `/readyz` 200 continuously; API p95 < 300 ms; 5xx = 0 over a day.
* A burst of `POST /api/v1/attendance/events` around 07:30–09:00 and 16:00–18:00 SAST
  (arrivals/departures); `/attendance/sync` calls every ~15 min per phone.
* `backup` log shows one `verification OK` per night; `deploy/backups/` has one file
  per day.

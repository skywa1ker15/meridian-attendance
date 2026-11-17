# Meridian Attendance — backend

FastAPI + SQLAlchemy 2 + PostgreSQL 16. Implements the REST contract in
[`../docs/API.md`](../docs/API.md) and serves the admin dashboard at `/admin/`.

## Layout
```
app/main.py          app factory (create_app) + module-level `app` for gunicorn
app/api/deps.py      get_db, get_current_user (JWT + token_version + device), require_permission
app/api/v1/          routers: auth, devices, config, attendance, notifications, admin
app/services/        business rules: verification, attendance, sync, manual_requests, devices,
                     employees, notifications (+fcm), reports, overview, scheduler, audit, email
app/core/            config, security, permissions, middleware, errors, time helpers
alembic/             migrations (0001 = initial schema)
app/cli.py           operations CLI
tests/               pytest suite (real PostgreSQL)
```

## Run locally
```bash
pip install -r requirements.txt -r requirements-dev.txt
cp .env.example .env            # set DATABASE_URL and SECRET_KEY
alembic upgrade head
python -m app.cli seed-policy   # policy row + PLACEHOLDER workplace (0,0, coordinates_confirmed=false)
python -m app.cli create-admin  # interactive; or --employee-number … --email … --first-name … --last-name … --generate
python -m app.cli seed-demo --admin-identifier admin@example.co.za --admin-password '<strong pw>'   # dev only
uvicorn app.main:app --reload   # http://localhost:8000/admin/  ·  API docs: /api/docs
```

## Tests
```bash
# uses TEST_DATABASE_URL, else DATABASE_URL when APP_ENV=test, else
# postgresql+psycopg://meridian:meridian_dev@127.0.0.1:5432/meridian_attendance_test
pytest -q
ruff check .
```
The suite migrates the test database with Alembic (downgrade base → upgrade head) and
truncates all tables between tests. It refuses to run against a database whose name does
not contain `test`.

## CLI (`python -m app.cli …`)
| Command | Purpose |
|---|---|
| `create-admin [--employee-number --email --first-name --last-name --role superadmin|admin --password|--generate]` | Create an administrator |
| `seed-policy` | Ensure the policy row and the placeholder workplace exist |
| `seed-demo [--admin-identifier X --admin-password Y] [--password P]` | Labelled demo data. Refused when `APP_ENV=production`; outside `development` needs `--i-know-this-is-demo-data` |
| `jobs run-once` | Run the scheduler jobs once (expiry, missing clock-outs, location retention, purges) |
| `revoke-all-sessions` | Sign everybody out (bump `token_version`, revoke refresh tokens) |

## Container
`Dockerfile` (multi-stage, UID/GID 10001, read-only-rootfs safe). `docker-entrypoint.sh`
runs `alembic upgrade head` (skip with `SKIP_MIGRATIONS=1`) then gunicorn with uvicorn
workers on `:8000` (`WEB_CONCURRENCY`, default 2). Arguments override the command, e.g.
`docker run … image python -m app.cli create-admin`.

## Operational notes
* The scheduler runs in every worker but takes a Postgres advisory lock per run, so jobs
  never run concurrently. Disable with `SCHEDULER_ENABLED=false` and use cron + `jobs run-once` instead.
* Rate limiting is in-memory per worker; put limits in the reverse proxy for multi-instance deployments.
* Prometheus metrics are per worker process (no multiprocess aggregation).
* Policy values are **defaults to be reviewed by Meridian management**, not company rules.

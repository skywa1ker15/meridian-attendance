#!/bin/sh
# Container entrypoint: apply database migrations, then start the API.
#   SKIP_MIGRATIONS=1   do not run `alembic upgrade head` (e.g. smoke tests without a DB,
#                       or when a separate release job migrates the database)
#   WEB_CONCURRENCY     gunicorn worker count (default 2)
#   Any arguments replace the default command (e.g. `python -m app.cli create-admin`).
set -eu

if [ "$#" -gt 0 ]; then
  exec "$@"
fi

if [ "${SKIP_MIGRATIONS:-0}" != "1" ]; then
  echo "entrypoint: applying database migrations (alembic upgrade head)"
  attempt=1
  until alembic upgrade head; do
    if [ "$attempt" -ge "${MIGRATION_RETRIES:-10}" ]; then
      echo "entrypoint: migrations failed after $attempt attempts" >&2
      exit 1
    fi
    echo "entrypoint: migration attempt $attempt failed; retrying in 3s" >&2
    attempt=$((attempt + 1))
    sleep 3
  done
else
  echo "entrypoint: SKIP_MIGRATIONS=1 — not running migrations"
fi

# When a trusted reverse proxy (Caddy/nginx) sets X-Forwarded-For, gunicorn must be
# told which source IPs to trust. In Docker the proxy's IP is the container bridge
# address (not 127.0.0.1), so when TRUST_PROXY_HEADERS is true we default to "*"
# (trust any private/bridge IP). Override with FORWARDED_ALLOW_IPS if needed.
if [ "${TRUST_PROXY_HEADERS:-false}" = "true" ]; then
  _FWD_IPS="${FORWARDED_ALLOW_IPS:-*}"
else
  _FWD_IPS="${FORWARDED_ALLOW_IPS:-127.0.0.1}"
fi

exec gunicorn app.main:app \
  --worker-class uvicorn.workers.UvicornWorker \
  --workers "${WEB_CONCURRENCY:-2}" \
  --bind "0.0.0.0:${PORT:-8000}" \
  --worker-tmp-dir /tmp \
  --timeout "${GUNICORN_TIMEOUT:-60}" \
  --graceful-timeout 30 \
  --keep-alive 5 \
  --forwarded-allow-ips "$_FWD_IPS" \
  --access-logfile - \
  --error-logfile -

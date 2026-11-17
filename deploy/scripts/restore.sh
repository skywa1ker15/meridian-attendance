#!/usr/bin/env bash
# Meridian Attendance — restore a PostgreSQL backup produced by backup.sh
#
#   deploy/scripts/restore.sh deploy/backups/meridian_20261116-000500Z.dump.gz [production|staging]
#
# What it does (interactively, with a confirmation prompt):
#   1. stops the api container (so no writes happen during the restore)
#   2. drops and recreates the target database inside the db container
#   3. pg_restore's the dump
#   4. starts the api again; the entrypoint runs `alembic upgrade head`, which is a no-op
#      when the dump is from the same version and applies migrations when restoring an
#      older dump into a newer release
#   5. prints /readyz
#
# This is DESTRUCTIVE: the current database content is replaced. Take a fresh backup
# first (deploy/scripts/backup.sh) if there is any chance you need it.
set -euo pipefail

DUMP="${1:?usage: restore.sh <dump.gz> [production|staging]}"
ENV_NAME="${2:-production}"
HERE="$(cd "$(dirname "$0")/.." && pwd)"
ENV_FILE="${HERE}/env/${ENV_NAME}.env"

if [[ ! -f "${DUMP}" ]]; then echo "dump not found: ${DUMP}" >&2; exit 1; fi
if [[ ! -f "${ENV_FILE}" ]]; then echo "env file not found: ${ENV_FILE}" >&2; exit 1; fi

COMPOSE=(docker compose --env-file "${ENV_FILE}" -f "${HERE}/docker-compose.yml")
if [[ "${ENV_NAME}" == "staging" ]]; then COMPOSE+=(-f "${HERE}/docker-compose.staging.yml"); fi

# shellcheck disable=SC1090
set -a; source "${ENV_FILE}"; set +a
DB="${POSTGRES_DB:?}"; USER_="${POSTGRES_USER:?}"

echo "About to REPLACE database '${DB}' (${ENV_NAME}) with ${DUMP}"
read -r -p "Type the database name to continue: " CONFIRM
[[ "${CONFIRM}" == "${DB}" ]] || { echo "aborted"; exit 1; }

echo "[restore] stopping api"
"${COMPOSE[@]}" stop api

echo "[restore] terminating connections and recreating ${DB}"
"${COMPOSE[@]}" exec -T db psql -U "${USER_}" -d postgres -v ON_ERROR_STOP=1 <<SQL
SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname = '${DB}' AND pid <> pg_backend_pid();
DROP DATABASE IF EXISTS "${DB}";
CREATE DATABASE "${DB}" OWNER "${USER_}";
SQL

echo "[restore] restoring"
gzip -dc "${DUMP}" | "${COMPOSE[@]}" exec -T db pg_restore -U "${USER_}" -d "${DB}" --no-owner --no-privileges --exit-on-error

echo "[restore] starting api (migrations run in the entrypoint)"
"${COMPOSE[@]}" up -d api

echo "[restore] waiting for /readyz"
for _ in $(seq 1 30); do
    if "${COMPOSE[@]}" exec -T api python -c "import urllib.request; print(urllib.request.urlopen('http://127.0.0.1:8000/readyz', timeout=5).read().decode())" 2>/dev/null; then
        echo "[restore] done"; exit 0
    fi
    sleep 3
done
echo "[restore] api did not become ready; check: ${COMPOSE[*]} logs api" >&2
exit 1

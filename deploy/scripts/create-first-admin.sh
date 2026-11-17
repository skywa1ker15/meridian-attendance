#!/usr/bin/env bash
# Meridian Attendance — create the first administrator (superadmin) account
#
#   deploy/scripts/create-first-admin.sh [production|staging|dev] [extra args for the CLI]
#
# Wraps:  docker compose exec api python -m app.cli create-admin
#
# The CLI prompts for employee number, first/last name, e-mail and a password (or
# generates a temporary one and prints it ONCE). The account is created with role
# `superadmin` and must_change_password=true when a temporary password is used.
# Run this exactly once after the first `compose up`; further administrators are
# created from the dashboard (Employees > New employee > role).
#
# Use `python -m app.cli create-admin --help` for non-interactive flags.
set -euo pipefail

ENV_NAME="${1:-production}"; shift || true
HERE="$(cd "$(dirname "$0")/.." && pwd)"

case "${ENV_NAME}" in
  dev|development)
    COMPOSE=(docker compose -f "${HERE}/docker-compose.dev.yml") ;;
  staging)
    COMPOSE=(docker compose --env-file "${HERE}/env/staging.env" -f "${HERE}/docker-compose.yml" -f "${HERE}/docker-compose.staging.yml") ;;
  production)
    COMPOSE=(docker compose --env-file "${HERE}/env/production.env" -f "${HERE}/docker-compose.yml") ;;
  *)
    echo "unknown environment: ${ENV_NAME}" >&2; exit 1 ;;
esac

# -it so the CLI can prompt; the printed temporary password is shown only once.
exec "${COMPOSE[@]}" exec -it api python -m app.cli create-admin "$@"

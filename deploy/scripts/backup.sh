#!/bin/sh
# Meridian Attendance — PostgreSQL backup
#
# Produces a compressed custom-format dump:  $BACKUP_DIR/meridian_<YYYYMMDD-HHMMSS>Z.dump.gz
# and removes dumps older than BACKUP_RETENTION_DAYS. Optionally copies the new dump
# off-site with rclone (BACKUP_RCLONE_REMOTE) — any S3-compatible bucket works.
#
# Runs inside the `backup` sidecar (deploy/docker-compose.yml) which has the postgres
# client tools. It can also be run from the host:
#
#   docker compose --env-file deploy/env/production.env -f deploy/docker-compose.yml \
#       exec backup /bin/sh /usr/local/bin/backup.sh
#
# or from a host cron with PG* variables exported and pg_dump 16 installed.
#
# Environment: PGHOST PGPORT PGDATABASE PGUSER PGPASSWORD (standard libpq variables)
#              BACKUP_DIR (default /backups)  BACKUP_RETENTION_DAYS (default 35)
#              BACKUP_RCLONE_REMOTE (optional, e.g. "s3backups:meridian-attendance/db")
#
# The dump contains PERSONAL INFORMATION (names, e-mail addresses, attendance and
# location records). Treat backup files and the off-site bucket as confidential
# (encrypt at rest, restrict access, same retention rules as the database — see
# docs/PRIVACY-POPIA.md).
set -eu

BACKUP_DIR="${BACKUP_DIR:-/backups}"
RETENTION_DAYS="${BACKUP_RETENTION_DAYS:-35}"
STAMP="$(date -u +%Y%m%d-%H%M%S)Z"
FILE="${BACKUP_DIR}/meridian_${STAMP}.dump.gz"
TMP="${FILE}.part"

mkdir -p "${BACKUP_DIR}"
umask 077

echo "[backup] $(date -u +%FT%TZ) starting pg_dump of ${PGDATABASE:-?} on ${PGHOST:-localhost}"
# -Fc = custom format (restorable with pg_restore, selective restore possible);
# piped through gzip so the file is portable and small.
pg_dump --format=custom --no-owner --no-privileges --compress=0 | gzip -6 > "${TMP}"
mv "${TMP}" "${FILE}"
SIZE="$(du -h "${FILE}" | cut -f1)"
echo "[backup] wrote ${FILE} (${SIZE})"

# Integrity check: a custom-format dump must list its TOC without error.
if gzip -dc "${FILE}" | pg_restore --list >/dev/null 2>&1; then
    echo "[backup] verification OK"
else
    echo "[backup] ERROR: dump failed verification" >&2
    exit 1
fi

# Retention
echo "[backup] removing dumps older than ${RETENTION_DAYS} days"
find "${BACKUP_DIR}" -name 'meridian_*.dump.gz' -type f -mtime "+${RETENTION_DAYS}" -print -delete || true

# Optional off-site copy
if [ -n "${BACKUP_RCLONE_REMOTE:-}" ]; then
    if command -v rclone >/dev/null 2>&1; then
        echo "[backup] copying to ${BACKUP_RCLONE_REMOTE}"
        rclone copy "${FILE}" "${BACKUP_RCLONE_REMOTE}" --s3-no-check-bucket
        # Apply the same retention remotely.
        rclone delete "${BACKUP_RCLONE_REMOTE}" --min-age "${RETENTION_DAYS}d" || true
    else
        echo "[backup] WARNING: BACKUP_RCLONE_REMOTE is set but rclone is not installed in this container." >&2
        echo "[backup]          Install rclone on the host and run the off-site copy from a host cron, or build a" >&2
        echo "[backup]          custom backup image (FROM postgres:16-alpine; RUN apk add rclone)." >&2
    fi
fi

# Expose success time for Prometheus (node-exporter textfile collector reads $BACKUP_DIR/*.prom).
{
  echo "# HELP meridian_backup_last_success_timestamp_seconds Unix time of the last verified backup."
  echo "# TYPE meridian_backup_last_success_timestamp_seconds gauge"
  echo "meridian_backup_last_success_timestamp_seconds $(date -u +%s)"
} > "${BACKUP_DIR}/meridian_backup.prom.tmp" && mv "${BACKUP_DIR}/meridian_backup.prom.tmp" "${BACKUP_DIR}/meridian_backup.prom"

echo "[backup] $(date -u +%FT%TZ) done"

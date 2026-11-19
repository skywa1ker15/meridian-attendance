"""In-process background jobs.

Runs every ``settings.scheduler_interval_seconds`` in a daemon thread when
``SCHEDULER_ENABLED`` is true. ``run_once`` is also used by the CLI
(``python -m app.cli jobs run-once``) so a cron job can replace the thread in
multi-instance deployments.
"""
from __future__ import annotations

import logging
import threading
from datetime import timedelta
from typing import Any

from sqlalchemy import delete, text

from app.core import timeutil
from app.core.config import get_settings
from app.db.session import get_engine, get_sessionmaker
from app.models import AuditLog, PasswordResetToken, RefreshToken
from app.services import attendance
from app.services.config_service import get_policy
from app.services.idempotency import IdempotencyStore

log = logging.getLogger("meridian.scheduler")


# Arbitrary, stable 64-bit key for pg_try_advisory_lock ("meridian-scheduler").
ADVISORY_LOCK_KEY = 0x4D45524944494E01


def run_once() -> dict[str, Any]:
    """Execute every maintenance job once and return counts.

    A transaction-level Postgres advisory lock guarantees that only one process (of many
    gunicorn workers / replicas / cron) runs the jobs at a time; others skip.
    The lock is automatically released when the transaction ends, so it cannot be
    held indefinitely if the process crashes (unlike session-level locks).
    """
    now = timeutil.now_utc()
    with get_engine().connect() as lock_conn:
        acquired = bool(lock_conn.execute(text("SELECT pg_try_advisory_xact_lock(:k)"), {"k": ADVISORY_LOCK_KEY}).scalar())
        if not acquired:
            log.info("scheduler run skipped: another process holds the lock")
            lock_conn.rollback()
            return {"ran_at": timeutil.utc_iso(now), "skipped": "locked"}
        result = _run_jobs(now)
        lock_conn.commit()  # releases the transaction-level advisory lock
        return result


def _run_jobs(now) -> dict[str, Any]:
    results: dict[str, Any] = {"ran_at": timeutil.utc_iso(now)}
    jobs = (
        ("expired_events", lambda db: attendance.expire_pending_events(db, now)),
        ("missing_clock_outs", lambda db: attendance.flag_missing_clock_outs(db, now)),
        ("locations_pseudonymised", lambda db: attendance.pseudonymise_locations(db, now)),
        ("tokens_purged", lambda db: _purge_tokens(db, now)),
        ("audit_purged", lambda db: _purge_audit(db, now)),
    )
    for name, job in jobs:
        try:
            with get_sessionmaker()() as db:
                results[name] = job(db)
        except Exception:  # noqa: BLE001 - one failing job must not stop the others
            log.exception("scheduler job %s failed", name)
            results[name] = "error"
    try:
        results["idempotency_purged"] = IdempotencyStore().purge_expired()
    except Exception:  # noqa: BLE001
        log.exception("idempotency purge failed")
        results["idempotency_purged"] = "error"
    log.info("scheduler run: %s", results)
    return results


def _purge_tokens(db, now) -> int:
    cutoff = now - timedelta(days=30)
    n = db.execute(delete(RefreshToken).where(RefreshToken.expires_at < cutoff)).rowcount or 0
    n += db.execute(delete(PasswordResetToken).where(PasswordResetToken.expires_at < cutoff)).rowcount or 0
    db.commit()
    return int(n)


def _purge_audit(db, now) -> int:
    policy = get_policy(db)
    cutoff = now - timedelta(days=policy.retention_days_audit)
    n = db.execute(delete(AuditLog).where(AuditLog.created_at < cutoff)).rowcount or 0
    db.commit()
    return int(n)


class Scheduler:
    def __init__(self, interval_seconds: int | None = None):
        self.interval = interval_seconds or get_settings().scheduler_interval_seconds
        self._stop = threading.Event()
        self._thread: threading.Thread | None = None
        self.last_result: dict[str, Any] | None = None

    def start(self) -> None:
        if self._thread and self._thread.is_alive():
            return
        self._stop.clear()
        self._thread = threading.Thread(target=self._loop, name="meridian-scheduler", daemon=True)
        self._thread.start()
        log.info("scheduler started (every %ss)", self.interval)

    def stop(self) -> None:
        self._stop.set()
        if self._thread:
            self._thread.join(timeout=5)
        log.info("scheduler stopped")

    def _loop(self) -> None:
        # First run shortly after start-up, then at the configured interval.
        if self._stop.wait(5):
            return
        while not self._stop.is_set():
            try:
                self.last_result = run_once()
            except Exception:  # noqa: BLE001
                log.exception("scheduler loop error")
            if self._stop.wait(self.interval):
                break

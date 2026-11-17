"""Database-backed store for the generic ``Idempotency-Key`` middleware."""
from __future__ import annotations

from datetime import timedelta

from sqlalchemy import delete, select

from app.core.config import get_settings
from app.core.timeutil import now_utc
from app.db.session import get_sessionmaker
from app.models import IdempotencyKey


class IdempotencyStore:
    def get(self, key: str, fingerprint: str) -> tuple[int, bytes, str] | None:
        with get_sessionmaker()() as db:
            row = db.execute(select(IdempotencyKey).where(IdempotencyKey.key == key, IdempotencyKey.fingerprint == fingerprint)).scalar_one_or_none()
            if row is None or row.expires_at < now_utc():
                return None
            return row.status_code, bytes(row.body), row.content_type

    def put(self, key: str, fingerprint: str, status_code: int, body: bytes, content_type: str) -> None:
        ttl = timedelta(hours=get_settings().idempotency_ttl_hours)
        with get_sessionmaker()() as db:
            db.merge(IdempotencyKey(key=key, fingerprint=fingerprint, status_code=status_code, body=body, content_type=content_type[:80], expires_at=now_utc() + ttl))
            db.commit()

    def purge_expired(self) -> int:
        with get_sessionmaker()() as db:
            result = db.execute(delete(IdempotencyKey).where(IdempotencyKey.expires_at < now_utc()))
            db.commit()
            return result.rowcount or 0

"""Audit logging. Every administrative mutation and every security-relevant
authentication event is recorded here. Entries are append-only."""
from __future__ import annotations

import logging
import uuid
from typing import Any

from sqlalchemy.orm import Session
from starlette.requests import Request

from app.core.config import get_settings
from app.core.middleware import client_ip
from app.models import AuditLog, User

log = logging.getLogger("meridian.audit")

_SENSITIVE_KEYS = {"password", "password_hash", "token", "refresh_token", "access_token", "fcm_token", "token_hash", "secret"}


def scrub(data: dict[str, Any] | None) -> dict[str, Any] | None:
    if data is None:
        return None
    return {k: ("***" if k in _SENSITIVE_KEYS else v) for k, v in data.items()}


def record(
    db: Session,
    *,
    action: str,
    actor: User | None,
    entity_type: str | None = None,
    entity_id: str | uuid.UUID | None = None,
    before: dict[str, Any] | None = None,
    after: dict[str, Any] | None = None,
    request: Request | None = None,
    actor_type: str | None = None,
) -> AuditLog:
    settings = get_settings()
    entry = AuditLog(
        actor_user_id=actor.id if actor else None,
        actor_type=actor_type or ("user" if actor else "anonymous"),
        action=action,
        entity_type=entity_type,
        entity_id=str(entity_id) if entity_id is not None else None,
        before=scrub(before),
        after=scrub(after),
        ip=client_ip(request, settings) if request is not None else None,
        user_agent=(request.headers.get("user-agent", "")[:256] if request is not None else None),
        request_id=getattr(request.state, "request_id", None) if request is not None else None,
    )
    db.add(entry)
    log.info("audit %s %s/%s by %s", action, entity_type, entity_id, actor.id if actor else actor_type, extra={"action": action, "user_id": str(actor.id) if actor else None})
    return entry

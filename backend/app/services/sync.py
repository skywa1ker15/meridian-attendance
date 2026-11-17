"""Offline queue replay: ordered, per-item idempotent batch processing."""
from __future__ import annotations

import logging
from typing import Any

from pydantic import ValidationError
from sqlalchemy.orm import Session
from starlette.requests import Request

from app.core.errors import ApiError
from app.models import Device, User
from app.schemas.attendance import (
    ConfirmIn,
    DismissIn,
    EventIn,
    SyncIn,
    event_dict,
    session_dict,
)
from app.services import attendance

log = logging.getLogger("meridian.sync")


def _validation_payload(exc: ValidationError) -> dict[str, Any]:
    fields = {".".join(str(p) for p in e.get("loc", [])) or "_": e.get("msg", "invalid") for e in exc.errors()}
    return {"code": "validation_error", "message": "Request validation failed.", "details": {"fields": fields}}


def process_batch(db: Session, user: User, device: Device | None, batch: SyncIn, request: Request | None) -> list[dict[str, Any]]:
    results: list[dict[str, Any]] = []
    for index, item in enumerate(batch.items):
        entry: dict[str, Any] = {"index": index, "kind": item.kind, "client_id": None, "ok": False, "status": 400}
        try:
            if item.kind == "event":
                data = EventIn.model_validate(item.payload)
                entry["client_id"] = data.client_event_id
                event, status = attendance.submit_event(db, user, device, data, request)
                entry.update(ok=True, status=status, event=event_dict(event))
            elif item.kind == "confirmation":
                if not item.event_id:
                    raise ApiError(422, "validation_error", "event_id is required for confirmations.", {"fields": {"event_id": "required"}})
                data = ConfirmIn.model_validate(item.payload)
                entry["client_id"] = data.client_confirmation_id
                event, session, _ = attendance.confirm_event(db, user, item.event_id, data, request)
                entry.update(ok=True, status=200, event=event_dict(event), session=session_dict(session) if session else None)
            else:
                if not item.event_id:
                    raise ApiError(422, "validation_error", "event_id is required for dismissals.", {"fields": {"event_id": "required"}})
                data = DismissIn.model_validate(item.payload)
                entry["client_id"] = item.event_id
                event = attendance.dismiss_event(db, user, item.event_id, data, request)
                entry.update(ok=True, status=200, event=event_dict(event))
        except ValidationError as exc:
            db.rollback()
            entry.update(ok=False, status=422, error=_validation_payload(exc))
        except ApiError as exc:
            db.rollback()
            entry.update(ok=False, status=exc.status_code, error=exc.to_payload()["error"])
        except Exception:  # noqa: BLE001 - one bad item must not break the batch
            db.rollback()
            log.exception("sync item %s failed", index)
            entry.update(ok=False, status=500, error={"code": "server_error", "message": "Item could not be processed.", "details": {}})
        results.append(entry)
    return results

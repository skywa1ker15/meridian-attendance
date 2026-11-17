"""In-app notification inbox + optional FCM push.

Every notification is stored. If the user has an active device with an FCM
token and FCM is configured, a data message is also sent; otherwise the row is
marked ``skipped`` / ``no_token`` and the app picks it up by polling.
"""
from __future__ import annotations

import uuid
from datetime import time
from typing import Any

from sqlalchemy import func, select, update
from sqlalchemy.orm import Session

from app.core import timeutil
from app.core.errors import ApiError
from app.models import AttendancePolicy, Device, Notification, User
from app.models import enums as E
from app.schemas.common import PageParams
from app.services import fcm


def _in_quiet_hours(policy: AttendancePolicy | None) -> bool:
    qh = policy.notification_quiet_hours if policy is not None else None
    if not qh or not isinstance(qh, dict) or not qh.get("start") or not qh.get("end"):
        return False
    try:
        start = timeutil.parse_hhmm(str(qh["start"]))
        end = timeutil.parse_hhmm(str(qh["end"]))
    except Exception:
        return False
    now_local: time = timeutil.to_local(timeutil.now_utc()).time()
    if start <= end:
        return start <= now_local < end
    return now_local >= start or now_local < end  # wraps midnight


def _push(db: Session, user: User, notification: Notification) -> None:
    if not user.notification_push:
        notification.push_status = "skipped"
        return
    policy = db.get(AttendancePolicy, 1)
    if _in_quiet_hours(policy) and notification.type not in ("clock_in_confirmation_required", "clock_out_confirmation_required"):
        notification.push_status = "skipped"
        return
    devices = db.execute(select(Device).where(Device.user_id == user.id, Device.status == "active", Device.fcm_token.isnot(None))).scalars().all()
    if not devices:
        notification.push_status = "no_token"
        return
    if not fcm.configured():
        notification.push_status = "skipped"
        return
    data = notification.data or {}
    payload = {
        "type": notification.type,
        "notification_id": str(notification.id),
        "event_id": str(data.get("event_id") or ""),
        "request_id": str(data.get("request_id") or ""),
        "title": notification.title,
        "body": notification.body,
    }
    statuses = {fcm.send_data_message(d.fcm_token, payload) for d in devices}  # type: ignore[arg-type]
    notification.push_status = "sent" if "sent" in statuses else ("failed" if "failed" in statuses else "skipped")
    if notification.push_status == "sent":
        notification.push_sent_at = timeutil.now_utc()


def notify(db: Session, user: User, type_: str, title: str, body: str, data: dict[str, Any] | None = None) -> Notification:
    """Create an inbox entry (and push). Does not commit."""
    if type_ not in E.NOTIFICATION_TYPES:
        raise ValueError(f"unknown notification type {type_}")
    n = Notification(id=uuid.uuid4(), user_id=user.id, type=type_, title=title[:160], body=body, data=data or {}, created_at=timeutil.now_utc())
    db.add(n)
    _push(db, user, n)
    return n


def list_notifications(db: Session, user: User, unread_only: bool, params: PageParams) -> tuple[list[Notification], int]:
    stmt = select(Notification).where(Notification.user_id == user.id)
    if unread_only:
        stmt = stmt.where(Notification.read_at.is_(None))
    total = db.execute(select(func.count()).select_from(stmt.subquery())).scalar_one()
    rows = db.execute(stmt.order_by(Notification.created_at.desc()).offset(params.offset).limit(params.page_size)).scalars().all()
    return list(rows), int(total)


def unread_count(db: Session, user: User) -> int:
    return int(db.execute(select(func.count()).select_from(Notification).where(Notification.user_id == user.id, Notification.read_at.is_(None))).scalar_one())


def mark_read(db: Session, user: User, notification_id: str) -> None:
    try:
        nid = uuid.UUID(str(notification_id))
    except ValueError as exc:
        raise ApiError(404, "not_found", "Notification not found.") from exc
    n = db.get(Notification, nid)
    if n is None or n.user_id != user.id:
        raise ApiError(404, "not_found", "Notification not found.")
    if n.read_at is None:
        n.read_at = timeutil.now_utc()
    db.commit()


def mark_all_read(db: Session, user: User) -> None:
    db.execute(update(Notification).where(Notification.user_id == user.id, Notification.read_at.is_(None)).values(read_at=timeutil.now_utc()))
    db.commit()


def broadcast(db: Session, title: str, body: str, department_id: uuid.UUID | None) -> int:
    stmt = select(User).where(User.status == "active", User.erased_at.is_(None))
    if department_id is not None:
        stmt = stmt.where(User.department_id == department_id)
    users = db.execute(stmt).scalars().all()
    for u in users:
        notify(db, u, "system", title, body, {})
    return len(users)

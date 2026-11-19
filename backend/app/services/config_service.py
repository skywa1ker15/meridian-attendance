"""Policy and workplace configuration services."""
from __future__ import annotations

import uuid
from typing import Any

from sqlalchemy import select
from sqlalchemy.orm import Session

from app.core.errors import ApiError
from app.models import AttendancePolicy, User, Workplace
from app.schemas.admin import (
    PolicyUpdateIn,
    WorkplaceIn,
    WorkplaceUpdateIn,
    policy_dict,
)
from app.schemas.attendance import workplace_dict
from app.services import audit


def get_policy(db: Session) -> AttendancePolicy:
    policy = db.get(AttendancePolicy, 1)
    if policy is None:
        policy = AttendancePolicy(id=1)
        db.add(policy)
        db.flush()
    return policy


def update_policy(db: Session, actor: User, data: PolicyUpdateIn, request=None) -> AttendancePolicy:
    policy = get_policy(db)
    before = policy_dict(policy)
    changes = data.model_dump(exclude_unset=True)
    for key, value in changes.items():
        setattr(policy, key, value)
    policy.updated_by = actor.id
    db.flush()
    audit.record(db, action="policy.update", actor=actor, entity_type="attendance_policy", entity_id="1", before={k: before.get(k) for k in changes}, after=changes, request=request)
    db.commit()
    return policy


def list_workplaces(db: Session, active_only: bool = False) -> list[Workplace]:
    stmt = select(Workplace).order_by(Workplace.created_at)
    if active_only:
        stmt = stmt.where(Workplace.active.is_(True))
    return list(db.execute(stmt).scalars())


def get_workplace(db: Session, workplace_id: str | uuid.UUID) -> Workplace:
    try:
        wid = uuid.UUID(str(workplace_id))
    except ValueError as exc:
        raise ApiError(404, "not_found", "Workplace not found.") from exc
    wp = db.get(Workplace, wid)
    if wp is None:
        raise ApiError(404, "not_found", "Workplace not found.")
    return wp


def _validate_coordinates(data) -> None:
    """Reject coordinates_confirmed=True when the coordinates are at null island (0,0)."""
    lat = getattr(data, "latitude", None)
    lng = getattr(data, "longitude", None)
    confirmed = getattr(data, "coordinates_confirmed", None)
    if confirmed and lat is not None and lng is not None and abs(lat) < 0.001 and abs(lng) < 0.001:
        raise ApiError(422, "validation_error", "Cannot confirm coordinates at (0, 0). Set the real workplace location first.", {"fields": {"coordinates_confirmed": "null_island"}})


def create_workplace(db: Session, actor: User, data: WorkplaceIn, request=None) -> Workplace:
    _validate_coordinates(data)
    wp = Workplace(**data.model_dump())
    db.add(wp)
    db.flush()
    audit.record(db, action="workplace.create", actor=actor, entity_type="workplace", entity_id=wp.id, after=workplace_dict(wp), request=request)
    db.commit()
    return wp


def update_workplace(db: Session, actor: User, workplace_id: str, data: WorkplaceUpdateIn, request=None) -> Workplace:
    wp = get_workplace(db, workplace_id)
    before = workplace_dict(wp)
    changes: dict[str, Any] = data.model_dump(exclude_unset=True)
    for key, value in changes.items():
        setattr(wp, key, value)
    _validate_coordinates(wp)
    wp.config_version = (wp.config_version or 1) + 1
    db.flush()
    audit.record(db, action="workplace.update", actor=actor, entity_type="workplace", entity_id=wp.id, before={k: before.get(k) for k in changes}, after=changes, request=request)
    db.commit()
    return wp


def config_version(db: Session) -> int:
    return sum(w.config_version for w in list_workplaces(db))

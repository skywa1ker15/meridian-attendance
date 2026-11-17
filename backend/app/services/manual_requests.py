"""Manual attendance requests (missed clock-in/out, full day, corrections,
profile corrections): employee lifecycle and administrator review."""
from __future__ import annotations

import uuid
from datetime import date

from sqlalchemy import func, select
from sqlalchemy.orm import Session, selectinload
from starlette.requests import Request

from app.core import timeutil
from app.core.errors import ApiError
from app.models import AttendanceSession, Department, ManualRequest, User
from app.schemas.admin import RequestApproveIn
from app.schemas.attendance import ManualRequestIn
from app.schemas.common import PageParams
from app.services import attendance, audit, notifications
from app.services.config_service import get_policy


def _get(db: Session, ref: str) -> ManualRequest:
    rid = attendance.parse_uuid(ref)
    row = db.execute(select(ManualRequest).options(selectinload(ManualRequest.user).selectinload(User.department)).where(ManualRequest.id == rid)).scalar_one_or_none() if rid else None
    if row is None:
        raise ApiError(404, "not_found", "Request not found.")
    return row


def create_request(db: Session, user: User, data: ManualRequestIn, request: Request | None) -> tuple[ManualRequest, bool]:
    existing = db.execute(select(ManualRequest).where(ManualRequest.user_id == user.id, ManualRequest.client_request_id == data.client_request_id)).scalar_one_or_none()
    if existing is not None:
        return existing, False
    session_id = None
    if data.session_id:
        sid = attendance.parse_uuid(data.session_id)
        session = db.get(AttendanceSession, sid) if sid else None
        if session is None or session.user_id != user.id:
            raise ApiError(404, "not_found", "Session not found.")
        session_id = session.id
    if data.work_date and data.work_date > timeutil.today_local():
        raise ApiError(422, "validation_error", "work_date cannot be in the future.", {"fields": {"work_date": "future"}})
    if data.type == "profile_correction" and data.field == "department":
        dep = db.execute(select(Department).where(func.lower(Department.code) == str(data.requested_value).lower().strip())).scalar_one_or_none()
        if dep is None:
            raise ApiError(422, "validation_error", "Unknown department code.", {"fields": {"requested_value": "unknown_department"}})
    req = ManualRequest(
        id=uuid.uuid4(),
        user_id=user.id,
        client_request_id=data.client_request_id,
        type=data.type,
        status="pending",
        work_date=data.work_date,
        requested_clock_in=data.requested_clock_in,
        requested_clock_out=data.requested_clock_out,
        reason=data.reason,
        session_id=session_id,
        field=data.field,
        requested_value=data.requested_value,
    )
    db.add(req)
    db.commit()
    db.refresh(req)
    return req, True


def list_requests(
    db: Session,
    params: PageParams,
    *,
    user_id: uuid.UUID | None = None,
    status: str | None = None,
    type_: str | None = None,
    date_from: date | None = None,
    date_to: date | None = None,
) -> tuple[list[ManualRequest], int]:
    stmt = select(ManualRequest).options(selectinload(ManualRequest.user).selectinload(User.department))
    if user_id is not None:
        stmt = stmt.where(ManualRequest.user_id == user_id)
    if status:
        stmt = stmt.where(ManualRequest.status == status)
    if type_:
        stmt = stmt.where(ManualRequest.type == type_)
    if date_from is not None:
        stmt = stmt.where(ManualRequest.created_at >= timeutil.local_day_bounds_utc(date_from)[0])
    if date_to is not None:
        stmt = stmt.where(ManualRequest.created_at < timeutil.local_day_bounds_utc(date_to)[1])
    total = db.execute(select(func.count()).select_from(stmt.order_by(None).subquery())).scalar_one()
    rows = db.execute(stmt.order_by(ManualRequest.created_at.desc()).offset(params.offset).limit(params.page_size)).scalars().all()
    return list(rows), int(total)


def cancel_request(db: Session, user: User, ref: str) -> ManualRequest:
    req = _get(db, ref)
    if req.user_id != user.id:
        raise ApiError(404, "not_found", "Request not found.")
    if req.status == "cancelled":
        return req
    if req.status != "pending":
        raise ApiError(400, "invalid_state", f"Only pending requests can be cancelled (status: {req.status}).")
    req.status = "cancelled"
    req.reviewed_at = timeutil.now_utc()
    db.commit()
    db.refresh(req)
    return req


def _apply_profile_correction(db: Session, req: ManualRequest, actor: User, request: Request | None) -> None:
    user = req.user
    before = {req.field: getattr(user, req.field) if req.field != "department" else (user.department.code if user.department else None)}
    if req.field == "department":
        dep = db.execute(select(Department).where(func.lower(Department.code) == str(req.requested_value).lower().strip())).scalar_one_or_none()
        if dep is None:
            raise ApiError(400, "invalid_state", "The requested department no longer exists.")
        user.department_id = dep.id
    elif req.field == "email":
        clash = db.execute(select(User).where(func.lower(User.email) == str(req.requested_value).lower(), User.id != user.id)).scalar_one_or_none()
        if clash is not None:
            raise ApiError(409, "conflict", "That email address is already in use.")
        user.email = str(req.requested_value).strip()
    else:
        setattr(user, req.field, str(req.requested_value).strip())
    audit.record(db, action="employee.update", actor=actor, entity_type="user", entity_id=user.id, before=before, after={req.field: req.requested_value, "via_request": str(req.id)}, request=request)


def approve_request(db: Session, actor: User, ref: str, data: RequestApproveIn, request: Request | None) -> ManualRequest:
    req = _get(db, ref)
    if req.status != "pending":
        raise ApiError(400, "invalid_state", f"Only pending requests can be approved (status: {req.status}).")
    now = timeutil.now_utc()
    policy = get_policy(db)
    user = req.user
    note = data.note
    clock_in = data.clock_in_at or req.requested_clock_in
    clock_out = data.clock_out_at or req.requested_clock_out
    session: AttendanceSession | None = None

    if req.type == "profile_correction":
        _apply_profile_correction(db, req, actor, request)
    else:
        session = db.get(AttendanceSession, req.session_id) if req.session_id else None
        if session is None and req.work_date is not None:
            session = db.execute(
                select(AttendanceSession).where(AttendanceSession.user_id == user.id, AttendanceSession.work_date == req.work_date).order_by(AttendanceSession.clock_in_at.desc())
            ).scalars().first()
        if session is not None:
            before = {"clock_in_at": timeutil.utc_iso(session.clock_in_at), "clock_out_at": timeutil.utc_iso(session.clock_out_at), "status": session.status}
            if req.type in ("missed_clock_in", "full_day", "correction") and clock_in is not None:
                session.clock_in_at = clock_in
                session.work_date = timeutil.local_date(clock_in)
            if req.type in ("missed_clock_out", "full_day", "correction") and clock_out is not None:
                session.clock_out_at = clock_out
                session.status = "closed"
            if session.clock_out_at is not None and session.clock_out_at <= session.clock_in_at:
                raise ApiError(400, "invalid_state", "The resulting clock-out would be before the clock-in.")
            if session.status == "missing_clock_out" and session.clock_out_at is not None:
                session.status = "closed"
            session.corrected = True
            session.corrected_by = actor.id
            session.correction_note = f"Manual request {req.type}: {req.reason}" + (f" — {note}" if note else "")
            compute = attendance.compute_session_metrics
            compute(session, policy)
            audit.record(db, action="attendance.session_correct", actor=actor, entity_type="attendance_session", entity_id=session.id, before=before, after={"clock_in_at": timeutil.utc_iso(session.clock_in_at), "clock_out_at": timeutil.utc_iso(session.clock_out_at), "status": session.status, "via_request": str(req.id)}, request=request)
        else:
            if clock_in is None:
                raise ApiError(400, "invalid_state", "No session exists for that day; provide clock_in_at to create one.", {"fields": {"clock_in_at": "required"}})
            session = attendance.create_session(
                db, actor, user=user, work_date=req.work_date or timeutil.local_date(clock_in), clock_in_at=clock_in, clock_out_at=clock_out, note=f"Manual request {req.type}: {req.reason}", is_manual=True, request=request, action="attendance.session_create_manual"
            )
            if session.status == "open" and (req.work_date or timeutil.local_date(clock_in)) < timeutil.today_local(now):
                session.status = "missing_clock_out"
                session.flagged_at = now
        req.resulting_session_id = session.id

    req.status = "approved"
    req.reviewed_by = actor.id
    req.reviewed_at = now
    req.review_note = note
    audit.record(db, action="manual_request.approve", actor=actor, entity_type="manual_request", entity_id=req.id, before={"status": "pending"}, after={"status": "approved", "note": note, "resulting_session_id": str(session.id) if session else None}, request=request)
    ntype = "correction_approved" if req.type in ("correction", "profile_correction") else "manual_request_reviewed"
    notifications.notify(db, user, ntype, "Request approved", f"Your {req.type.replace('_', ' ')} request was approved by {actor.full_name}." + (f" Note: {note}" if note else ""), {"request_id": str(req.id), "session_id": str(session.id) if session else None})
    db.commit()
    db.refresh(req)
    return req


def reject_request(db: Session, actor: User, ref: str, note: str, request: Request | None) -> ManualRequest:
    req = _get(db, ref)
    if req.status != "pending":
        raise ApiError(400, "invalid_state", f"Only pending requests can be rejected (status: {req.status}).")
    req.status = "rejected"
    req.reviewed_by = actor.id
    req.reviewed_at = timeutil.now_utc()
    req.review_note = note
    audit.record(db, action="manual_request.reject", actor=actor, entity_type="manual_request", entity_id=req.id, before={"status": "pending"}, after={"status": "rejected", "note": note}, request=request)
    ntype = "correction_rejected" if req.type in ("correction", "profile_correction") else "manual_request_reviewed"
    notifications.notify(db, req.user, ntype, "Request not approved", f"Your {req.type.replace('_', ' ')} request was not approved: {note}", {"request_id": str(req.id)})
    db.commit()
    db.refresh(req)
    return req

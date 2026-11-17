"""Attendance engine: event state machine, idempotent submission, duplicate
windows, session open/close, worked/late/overtime computation, today summary,
administrative review and corrections. See docs/API.md §4 and §7."""
from __future__ import annotations

import uuid
from datetime import date, datetime, timedelta
from typing import Any

from sqlalchemy import func, or_, select
from sqlalchemy.orm import Session, selectinload
from starlette.requests import Request

from app.core import timeutil
from app.core.errors import ApiError
from app.models import (
    AttendanceEvent,
    AttendancePolicy,
    AttendanceSession,
    Device,
    ManualRequest,
    Notification,
    User,
    Workplace,
)
from app.schemas.admin import SessionCorrectionIn, SessionCreateIn
from app.schemas.attendance import (
    ConfirmIn,
    DismissIn,
    EventIn,
    event_dict,
    session_dict,
)
from app.schemas.common import PageParams
from app.services import audit, notifications
from app.services import verification as V
from app.services.config_service import get_policy

# --------------------------------------------------------------------------------------
# helpers
# --------------------------------------------------------------------------------------


def parse_uuid(value: str | uuid.UUID | None) -> uuid.UUID | None:
    if value is None:
        return None
    if isinstance(value, uuid.UUID):
        return value
    try:
        return uuid.UUID(str(value))
    except (ValueError, AttributeError, TypeError):
        return None


def find_open_session(db: Session, user_id: uuid.UUID) -> AttendanceSession | None:
    return db.execute(select(AttendanceSession).where(AttendanceSession.user_id == user_id, AttendanceSession.status == "open")).scalar_one_or_none()


def compute_session_metrics(session: AttendanceSession, policy: AttendancePolicy) -> None:
    """Fill worked/break/overtime/late fields from clock times and the policy.

    * ``is_late``: clock-in later than ``late_after_local`` on a working day; ``late_minutes``
      counts from ``workday_start_local``.
    * ``break_minutes``: the policy break is deducted once the gross presence exceeds four hours.
    * ``overtime_minutes``: worked minutes beyond ``overtime_after_minutes``.
    """
    local_in = timeutil.to_local(session.clock_in_at)
    session.work_date = session.work_date or local_in.date()
    working_day = local_in.isoweekday() in (policy.working_days or [1, 2, 3, 4, 5])
    late_after = timeutil.local_datetime(session.work_date, policy.late_after_local)
    day_start = timeutil.local_datetime(session.work_date, policy.workday_start_local)
    session.is_late = bool(working_day and local_in > late_after)
    session.late_minutes = timeutil.minutes_between(day_start, local_in) if session.is_late else 0
    if session.clock_out_at is not None:
        gross = timeutil.minutes_between(session.clock_in_at, session.clock_out_at)
        session.break_minutes = policy.break_minutes if gross > 240 else 0
        session.worked_minutes = max(0, gross - session.break_minutes)
        session.overtime_minutes = max(0, session.worked_minutes - policy.overtime_after_minutes)
    else:
        session.break_minutes = 0
        session.worked_minutes = 0
        session.overtime_minutes = 0


def _get_workplace(db: Session, workplace_id: str) -> Workplace:
    wid = parse_uuid(workplace_id)
    wp = db.get(Workplace, wid) if wid else None
    if wp is None or not wp.active:
        raise ApiError(404, "not_found", "Workplace not found or inactive.")
    return wp


def get_own_event(db: Session, user: User, ref: str) -> AttendanceEvent:
    """Find an event of ``user`` by server id or by client_event_id."""
    stmt = select(AttendanceEvent).options(selectinload(AttendanceEvent.workplace)).where(AttendanceEvent.user_id == user.id)
    eid = parse_uuid(ref)
    if eid is not None:
        stmt = stmt.where(or_(AttendanceEvent.id == eid, AttendanceEvent.client_event_id == str(ref)))
    else:
        stmt = stmt.where(AttendanceEvent.client_event_id == str(ref)[:64])
    event = db.execute(stmt).scalars().first()
    if event is None:
        raise ApiError(404, "not_found", "Attendance event not found.")
    return event


def get_event(db: Session, ref: str) -> AttendanceEvent:
    eid = parse_uuid(ref)
    event = db.get(AttendanceEvent, eid) if eid else None
    if event is None:
        raise ApiError(404, "not_found", "Attendance event not found.")
    return event


def get_session(db: Session, ref: str) -> AttendanceSession:
    sid = parse_uuid(ref)
    session = db.get(AttendanceSession, sid) if sid else None
    if session is None:
        raise ApiError(404, "not_found", "Attendance session not found.")
    return session


# --------------------------------------------------------------------------------------
# submission
# --------------------------------------------------------------------------------------


def submit_event(db: Session, user: User, device: Device | None, data: EventIn, request: Request | None) -> tuple[AttendanceEvent, int]:
    """Store a detected event. Returns (event, http_status) where 200 means an
    existing event was returned (idempotent replay or duplicate window)."""
    existing = db.execute(select(AttendanceEvent).where(AttendanceEvent.user_id == user.id, AttendanceEvent.client_event_id == data.client_event_id)).scalar_one_or_none()
    if existing is not None:
        return existing, 200
    if device is not None and device.status == "pending_approval":
        raise ApiError(403, "device_pending_approval", "This device is awaiting administrator approval.")
    if device is not None and device.status == "revoked":
        raise ApiError(403, "device_revoked", "This device has been revoked.")

    now = timeutil.now_utc()
    policy = get_policy(db)
    workplace = _get_workplace(db, data.workplace_id)
    result = V.verify_event(policy, workplace, device, data, now)

    # Duplicate window: a pending event of the same type close in time → return it.
    if result.decision != "reject" and policy.duplicate_window_seconds > 0:
        window = timedelta(seconds=policy.duplicate_window_seconds)
        dup = db.execute(
            select(AttendanceEvent)
            .where(
                AttendanceEvent.user_id == user.id,
                AttendanceEvent.event_type == data.event_type,
                AttendanceEvent.status == "pending",
                AttendanceEvent.detected_at >= data.detected_at - window,
                AttendanceEvent.detected_at <= data.detected_at + window,
            )
            .order_by(AttendanceEvent.received_at.desc())
        ).scalars().first()
        if dup is not None:
            return dup, 200

    open_session = find_open_session(db, user.id)
    if result.decision != "reject":
        if data.event_type == "arrival" and open_session is not None:
            raise ApiError(409, "invalid_state", "A session is already open; confirm a departure first.", {"session": session_dict(open_session, now=now)})
        if data.event_type == "departure" and open_session is None:
            result.decision = "review"
            result.reason = result.reason or V.R_NO_OPEN_SESSION
            result.notes.append("departure without an open session; a manager must decide")

    event = AttendanceEvent(
        id=uuid.uuid4(),
        user_id=user.id,
        device_id=device.id if device else None,
        workplace_id=workplace.id,
        client_event_id=data.client_event_id,
        event_type=data.event_type,
        source=data.source,
        status="rejected" if result.decision == "reject" else "pending",
        geofence_transition=data.geofence_transition,
        detected_at=data.detected_at,
        device_timezone=data.device_timezone,
        received_at=now,
        expires_at=None if result.decision == "reject" else V.expires_at_for(policy, now),
        latitude=data.location.latitude if data.location else None,
        longitude=data.location.longitude if data.location else None,
        accuracy_m=data.location.accuracy_m if data.location else None,
        distance_from_workplace_m=result.distance_m,
        wifi_ssid=data.wifi.ssid if data.wifi else None,
        wifi_bssid=data.wifi.bssid if data.wifi else None,
        wifi_verified=result.wifi_verified,
        verification_score=result.score,
        verification=result.as_json(),
        offline_queued=data.offline_queued,
        battery_level=data.battery_level,
        note=data.note,
        review_reason=result.reason if result.decision != "auto" else None,
    )
    if result.decision == "reject":
        event.reviewed_at = now
        event.review_note = "Rejected automatically: " + "; ".join(result.notes)
    db.add(event)
    db.flush()

    if event.status == "rejected":
        notifications.notify(db, user, "attendance_submission_failed", "Attendance detection rejected", f"Your {data.event_type} could not be accepted: {result.notes[0] if result.notes else result.reason}.", {"event_id": str(event.id)})
    elif data.event_type == "arrival":
        notifications.notify(db, user, "clock_in_confirmation_required", "Confirm your arrival", f"We detected you at {workplace.name} at {timeutil.local_time_str(result.official_at)}. Tap to confirm your clock-in.", {"event_id": str(event.id)})
    else:
        notifications.notify(db, user, "clock_out_confirmation_required", "Confirm your departure", f"We detected you leaving {workplace.name} at {timeutil.local_time_str(result.official_at)}. Tap to confirm your clock-out.", {"event_id": str(event.id)})
    db.commit()
    db.refresh(event)
    return event, 201


# --------------------------------------------------------------------------------------
# confirmation / dismissal
# --------------------------------------------------------------------------------------


def apply_confirmed_event(db: Session, event: AttendanceEvent, policy: AttendancePolicy, now: datetime) -> AttendanceSession:
    """Apply a *confirmed* event to the user's session. Raises ApiError(400) when the
    state does not allow it (caller decides whether to route to review)."""
    official = event.official_at or event.received_at
    open_session = find_open_session(db, event.user_id)
    if event.event_type == "arrival":
        if open_session is not None:
            raise ApiError(400, "invalid_state", "A session is already open for this employee.", {"reason": V.R_SESSION_ALREADY_OPEN, "session_id": str(open_session.id)})
        session = AttendanceSession(
            id=uuid.uuid4(),
            user_id=event.user_id,
            workplace_id=event.workplace_id,
            work_date=timeutil.local_date(official),
            status="open",
            clock_in_at=official,
            clock_in_event_id=event.id,
            is_manual=False,
        )
        compute_session_metrics(session, policy)
        db.add(session)
        db.flush()
        event.session_id = session.id
        return session
    # departure
    if open_session is None:
        raise ApiError(400, "invalid_state", "There is no open session to close; create a manual session instead.", {"reason": V.R_NO_OPEN_SESSION})
    if official <= open_session.clock_in_at:
        raise ApiError(400, "invalid_state", "Departure time is before the clock-in time.", {"reason": "departure_before_arrival"})
    open_session.clock_out_at = official
    open_session.clock_out_event_id = event.id
    open_session.status = "closed"
    compute_session_metrics(open_session, policy)
    db.flush()
    event.session_id = open_session.id
    return open_session


def confirm_event(db: Session, user: User, ref: str, data: ConfirmIn, request: Request | None) -> tuple[AttendanceEvent, AttendanceSession | None, bool]:
    """Returns (event, session, replayed)."""
    event = get_own_event(db, user, ref)
    now = timeutil.now_utc()
    if event.client_confirmation_id and event.client_confirmation_id == data.client_confirmation_id:
        session = db.get(AttendanceSession, event.session_id) if event.session_id else None
        return event, session, True
    if event.status == "pending" and event.expires_at and event.expires_at < now:
        event.status = "expired"
        db.commit()
    if event.status != "pending":
        raise ApiError(400, "invalid_state", f"This event is {event.status} and can no longer be confirmed.", {"status": event.status})

    policy = get_policy(db)
    verification = dict(event.verification or {})
    decision = verification.get("decision", "review")
    official_at = datetime.fromisoformat(verification["official_at"]) if verification.get("official_at") else event.received_at
    event.official_at = timeutil.ensure_utc(official_at)
    event.confirmed_at = data.confirmed_at
    event.confirmation_received_at = now
    event.client_confirmation_id = data.client_confirmation_id
    if data.note:
        event.note = (event.note + "\n" if event.note else "") + data.note
    if data.location and event.latitude is None:
        event.latitude, event.longitude, event.accuracy_m = data.location.latitude, data.location.longitude, data.location.accuracy_m
    if data.wifi and not event.wifi_ssid:
        event.wifi_ssid, event.wifi_bssid = data.wifi.ssid, data.wifi.bssid

    session: AttendanceSession | None = None
    if decision == "auto":
        try:
            session = apply_confirmed_event(db, event, policy, now)
            event.status = "confirmed"
        except ApiError as exc:
            event.status = "under_review"
            event.review_reason = exc.details.get("reason", "invalid_state")
            verification.setdefault("notes", []).append(exc.message)
            event.verification = verification
    else:
        event.status = "under_review"
        event.review_reason = event.review_reason or verification.get("reason") or V.R_LOW_SCORE

    if event.status == "confirmed" and session is not None:
        verb = "Clocked in" if event.event_type == "arrival" else "Clocked out"
        notifications.notify(db, user, "attendance_recorded", f"{verb} at {timeutil.local_time_str(event.official_at)}", f"Your {event.event_type} on {session.work_date.isoformat()} was recorded.", {"event_id": str(event.id), "session_id": str(session.id)})
    else:
        notifications.notify(db, user, "system", "Attendance awaiting review", f"Your {event.event_type} confirmation needs a manager's review ({event.review_reason}).", {"event_id": str(event.id)})
    db.commit()
    db.refresh(event)
    return event, session, False


def dismiss_event(db: Session, user: User, ref: str, data: DismissIn, request: Request | None) -> AttendanceEvent:
    event = get_own_event(db, user, ref)
    if event.status == "dismissed":
        return event
    if event.status != "pending":
        raise ApiError(400, "invalid_state", f"This event is {event.status} and can no longer be dismissed.", {"status": event.status})
    event.status = "dismissed"
    event.dismiss_reason = data.reason
    event.reviewed_at = timeutil.now_utc()
    if data.note:
        event.note = (event.note + "\n" if event.note else "") + data.note
    db.commit()
    db.refresh(event)
    return event


# --------------------------------------------------------------------------------------
# queries
# --------------------------------------------------------------------------------------


def pending_events(db: Session, user: User) -> list[AttendanceEvent]:
    now = timeutil.now_utc()
    stmt = (
        select(AttendanceEvent)
        .options(selectinload(AttendanceEvent.workplace))
        .where(AttendanceEvent.user_id == user.id, AttendanceEvent.status == "pending", or_(AttendanceEvent.expires_at.is_(None), AttendanceEvent.expires_at > now))
        .order_by(AttendanceEvent.received_at.desc())
    )
    return list(db.execute(stmt).scalars())


def _sum_worked(db: Session, user_id: uuid.UUID, start: date, end: date, now: datetime) -> int:
    rows = db.execute(select(AttendanceSession).where(AttendanceSession.user_id == user_id, AttendanceSession.work_date >= start, AttendanceSession.work_date <= end)).scalars().all()
    total = 0
    for s in rows:
        if s.status == "open":
            total += timeutil.minutes_between(s.clock_in_at, now)
        else:
            total += s.worked_minutes
    return total


def today_summary(db: Session, user: User, now: datetime | None = None) -> dict[str, Any]:
    now = now or timeutil.now_utc()
    today = timeutil.local_date(now)
    open_session = find_open_session(db, user.id)
    today_sessions = list(
        db.execute(select(AttendanceSession).options(selectinload(AttendanceSession.workplace)).where(AttendanceSession.user_id == user.id, AttendanceSession.work_date == today).order_by(AttendanceSession.clock_in_at.desc())).scalars()
    )
    session = open_session or (today_sessions[0] if today_sessions else None)
    pending = pending_events(db, user)
    has_pending_departure = any(e.event_type == "departure" for e in pending)
    has_pending_arrival = any(e.event_type == "arrival" for e in pending)
    if session is not None and session.status == "open":
        status = "pending_departure" if has_pending_departure else "clocked_in"
    elif session is not None and session.status == "missing_clock_out":
        status = "missing_clock_out"
    elif session is not None and session.status == "closed":
        status = "clocked_out"
    elif has_pending_arrival:
        status = "pending_arrival"
    else:
        status = "not_started"
    unread = int(db.execute(select(func.count()).select_from(Notification).where(Notification.user_id == user.id, Notification.read_at.is_(None))).scalar_one())
    pending_requests = int(db.execute(select(func.count()).select_from(ManualRequest).where(ManualRequest.user_id == user.id, ManualRequest.status == "pending")).scalar_one())
    return {
        "work_date": today.isoformat(),
        "status": status,
        "session": session_dict(session, now=now) if session else None,
        "pending_events": [event_dict(e) for e in pending],
        "clock_in_local": timeutil.local_time_str(session.clock_in_at) if session else None,
        "clock_out_local": timeutil.local_time_str(session.clock_out_at) if session and session.clock_out_at else None,
        "worked_minutes_today": _sum_worked(db, user.id, today, today, now),
        "week_minutes": _sum_worked(db, user.id, timeutil.week_start(today), today, now),
        "month_minutes": _sum_worked(db, user.id, timeutil.month_start(today), today, now),
        "late_today": any(s.is_late for s in today_sessions),
        "unread_notifications": unread,
        "pending_requests": pending_requests,
        "server_time": timeutil.utc_iso(now),
    }


def _paginate(db: Session, stmt, params: PageParams, order_by) -> tuple[list, int]:
    total = db.execute(select(func.count()).select_from(stmt.order_by(None).subquery())).scalar_one()
    rows = db.execute(stmt.order_by(*(order_by if isinstance(order_by, tuple) else (order_by,))).offset(params.offset).limit(params.page_size)).scalars().all()
    return list(rows), int(total)


def list_sessions(
    db: Session,
    params: PageParams,
    *,
    user_id: uuid.UUID | None = None,
    date_from: date | None = None,
    date_to: date | None = None,
    department_id: uuid.UUID | None = None,
    status: str | None = None,
    late: bool | None = None,
    manual: bool | None = None,
) -> tuple[list[AttendanceSession], int]:
    stmt = select(AttendanceSession).options(selectinload(AttendanceSession.workplace), selectinload(AttendanceSession.user).selectinload(User.department))
    if user_id is not None:
        stmt = stmt.where(AttendanceSession.user_id == user_id)
    if date_from is not None:
        stmt = stmt.where(AttendanceSession.work_date >= date_from)
    if date_to is not None:
        stmt = stmt.where(AttendanceSession.work_date <= date_to)
    if department_id is not None:
        stmt = stmt.join(User, User.id == AttendanceSession.user_id).where(User.department_id == department_id)
    if status:
        stmt = stmt.where(AttendanceSession.status == status)
    if late is not None:
        stmt = stmt.where(AttendanceSession.is_late.is_(late))
    if manual is not None:
        stmt = stmt.where(AttendanceSession.is_manual.is_(manual))
    return _paginate(db, stmt, params, (AttendanceSession.work_date.desc(), AttendanceSession.clock_in_at.desc()))


def list_events(
    db: Session,
    params: PageParams,
    *,
    user_id: uuid.UUID | None = None,
    date_from: date | None = None,
    date_to: date | None = None,
    status: str | None = None,
    event_type: str | None = None,
    department_id: uuid.UUID | None = None,
) -> tuple[list[AttendanceEvent], int]:
    stmt = select(AttendanceEvent).options(selectinload(AttendanceEvent.workplace), selectinload(AttendanceEvent.user).selectinload(User.department))
    if user_id is not None:
        stmt = stmt.where(AttendanceEvent.user_id == user_id)
    if date_from is not None:
        stmt = stmt.where(AttendanceEvent.received_at >= timeutil.local_day_bounds_utc(date_from)[0])
    if date_to is not None:
        stmt = stmt.where(AttendanceEvent.received_at < timeutil.local_day_bounds_utc(date_to)[1])
    if status:
        statuses = [s for s in status.split(",") if s]
        stmt = stmt.where(AttendanceEvent.status.in_(statuses))
    if event_type:
        stmt = stmt.where(AttendanceEvent.event_type == event_type)
    if department_id is not None:
        stmt = stmt.join(User, User.id == AttendanceEvent.user_id).where(User.department_id == department_id)
    return _paginate(db, stmt, params, AttendanceEvent.received_at.desc())


def session_events(db: Session, session: AttendanceSession) -> list[AttendanceEvent]:
    stmt = (
        select(AttendanceEvent)
        .options(selectinload(AttendanceEvent.workplace))
        .where(or_(AttendanceEvent.session_id == session.id, AttendanceEvent.id.in_([i for i in (session.clock_in_event_id, session.clock_out_event_id) if i])))
        .order_by(AttendanceEvent.received_at)
    )
    return list(db.execute(stmt).scalars())


# --------------------------------------------------------------------------------------
# administration
# --------------------------------------------------------------------------------------


def admin_approve_event(db: Session, actor: User, ref: str, note: str | None, request: Request | None) -> AttendanceEvent:
    event = get_event(db, ref)
    if event.status not in ("pending", "under_review"):
        raise ApiError(400, "invalid_state", f"Only pending or under-review events can be approved (status: {event.status}).")
    now = timeutil.now_utc()
    policy = get_policy(db)
    if event.official_at is None:
        verification = event.verification or {}
        event.official_at = timeutil.ensure_utc(datetime.fromisoformat(verification["official_at"])) if verification.get("official_at") else event.received_at
    before = {"status": event.status}
    session = apply_confirmed_event(db, event, policy, now)  # raises invalid_state when impossible
    event.status = "confirmed"
    event.reviewed_by = actor.id
    event.reviewed_at = now
    event.review_note = note
    if event.confirmed_at is None:
        event.confirmed_at = now
    audit.record(db, action="attendance.event_approve", actor=actor, entity_type="attendance_event", entity_id=event.id, before=before, after={"status": "confirmed", "note": note, "session_id": str(session.id)}, request=request)
    user = db.get(User, event.user_id)
    notifications.notify(db, user, "attendance_recorded", "Attendance approved", f"Your {event.event_type} on {session.work_date.isoformat()} was approved by {actor.full_name}.", {"event_id": str(event.id), "session_id": str(session.id)})
    db.commit()
    db.refresh(event)
    return event


def admin_reject_event(db: Session, actor: User, ref: str, note: str, request: Request | None) -> AttendanceEvent:
    event = get_event(db, ref)
    if event.status not in ("pending", "under_review"):
        raise ApiError(400, "invalid_state", f"Only pending or under-review events can be rejected (status: {event.status}).")
    now = timeutil.now_utc()
    before = {"status": event.status}
    event.status = "rejected"
    event.reviewed_by = actor.id
    event.reviewed_at = now
    event.review_note = note
    audit.record(db, action="attendance.event_reject", actor=actor, entity_type="attendance_event", entity_id=event.id, before=before, after={"status": "rejected", "note": note}, request=request)
    user = db.get(User, event.user_id)
    notifications.notify(db, user, "correction_rejected", "Attendance not approved", f"Your {event.event_type} was not approved: {note}", {"event_id": str(event.id)})
    db.commit()
    db.refresh(event)
    return event


def correct_session(db: Session, actor: User, ref: str, data: SessionCorrectionIn, request: Request | None) -> AttendanceSession:
    session = get_session(db, ref)
    policy = get_policy(db)
    before = {"clock_in_at": timeutil.utc_iso(session.clock_in_at), "clock_out_at": timeutil.utc_iso(session.clock_out_at), "status": session.status}
    if data.clock_in_at is not None:
        session.clock_in_at = data.clock_in_at
        session.work_date = timeutil.local_date(data.clock_in_at)
    if data.clock_out_at is not None:
        session.clock_out_at = data.clock_out_at
        session.status = "closed"
    if session.clock_out_at is not None and session.clock_out_at <= session.clock_in_at:
        raise ApiError(422, "validation_error", "clock_out_at must be after clock_in_at.", {"fields": {"clock_out_at": "before_clock_in"}})
    if session.status == "open" and session.clock_out_at is not None:
        session.status = "closed"
    compute_session_metrics(session, policy)
    session.corrected = True
    session.corrected_by = actor.id
    session.correction_note = data.note
    audit.record(db, action="attendance.session_correct", actor=actor, entity_type="attendance_session", entity_id=session.id, before=before, after={"clock_in_at": timeutil.utc_iso(session.clock_in_at), "clock_out_at": timeutil.utc_iso(session.clock_out_at), "status": session.status, "note": data.note}, request=request)
    user = db.get(User, session.user_id)
    notifications.notify(db, user, "correction_approved", "Attendance record corrected", f"Your attendance for {session.work_date.isoformat()} was corrected by {actor.full_name}: {data.note}", {"session_id": str(session.id)})
    db.commit()
    db.refresh(session)
    return session


def create_session(
    db: Session,
    actor: User | None,
    *,
    user: User,
    work_date: date,
    clock_in_at: datetime,
    clock_out_at: datetime | None,
    note: str | None,
    is_manual: bool,
    workplace_id: uuid.UUID | None = None,
    request: Request | None = None,
    action: str = "attendance.session_create",
) -> AttendanceSession:
    policy = get_policy(db)
    if clock_out_at is not None and clock_out_at <= clock_in_at:
        raise ApiError(422, "validation_error", "clock_out_at must be after clock_in_at.", {"fields": {"clock_out_at": "before_clock_in"}})
    if clock_out_at is None and find_open_session(db, user.id) is not None:
        raise ApiError(409, "invalid_state", "The employee already has an open session.")
    if workplace_id is None:
        wp = db.execute(select(Workplace).where(Workplace.active.is_(True)).order_by(Workplace.created_at)).scalars().first()
        workplace_id = wp.id if wp else None
    session = AttendanceSession(
        id=uuid.uuid4(),
        user_id=user.id,
        workplace_id=workplace_id,
        work_date=work_date,
        status="closed" if clock_out_at else "open",
        clock_in_at=clock_in_at,
        clock_out_at=clock_out_at,
        is_manual=is_manual,
        notes=note,
    )
    compute_session_metrics(session, policy)
    db.add(session)
    db.flush()
    if actor is not None:
        audit.record(db, action=action, actor=actor, entity_type="attendance_session", entity_id=session.id, after={"employee_id": str(user.id), "work_date": work_date.isoformat(), "clock_in_at": timeutil.utc_iso(clock_in_at), "clock_out_at": timeutil.utc_iso(clock_out_at), "note": note}, request=request)
    return session


def admin_create_session(db: Session, actor: User, data: SessionCreateIn, request: Request | None) -> AttendanceSession:
    uid = parse_uuid(data.employee_id)
    user = db.get(User, uid) if uid else None
    if user is None:
        raise ApiError(404, "not_found", "Employee not found.")
    session = create_session(db, actor, user=user, work_date=data.work_date, clock_in_at=data.clock_in_at, clock_out_at=data.clock_out_at, note=data.note, is_manual=True, request=request)
    # Record the administrative action as an event of source "admin" for traceability.
    now = timeutil.now_utc()
    for etype, at in (("arrival", data.clock_in_at), ("departure", data.clock_out_at)):
        if at is None:
            continue
        ev = AttendanceEvent(
            id=uuid.uuid4(), user_id=user.id, workplace_id=session.workplace_id, client_event_id=f"admin-{uuid.uuid4().hex}", event_type=etype, source="admin", status="confirmed",
            detected_at=at, received_at=now, official_at=at, confirmed_at=now, confirmation_received_at=now, verification={"decision": "admin", "notes": [f"created by {actor.full_name}"]},
            verification_score=1.0, reviewed_by=actor.id, reviewed_at=now, review_note=data.note, session_id=session.id, note=data.note,
        )
        db.add(ev)
        db.flush()
        if etype == "arrival":
            session.clock_in_event_id = ev.id
        else:
            session.clock_out_event_id = ev.id
    notifications.notify(db, user, "attendance_recorded", "Attendance recorded by administrator", f"An attendance record for {data.work_date.isoformat()} was created by {actor.full_name}.", {"session_id": str(session.id)})
    db.commit()
    db.refresh(session)
    return session


# --------------------------------------------------------------------------------------
# scheduled jobs
# --------------------------------------------------------------------------------------


def expire_pending_events(db: Session, now: datetime | None = None) -> int:
    now = now or timeutil.now_utc()
    rows = db.execute(select(AttendanceEvent).where(AttendanceEvent.status == "pending", AttendanceEvent.expires_at.isnot(None), AttendanceEvent.expires_at < now)).scalars().all()
    for e in rows:
        e.status = "expired"
        e.reviewed_at = now
        user = db.get(User, e.user_id)
        if user is not None:
            notifications.notify(db, user, "system", "Confirmation expired", f"Your detected {e.event_type} on {timeutil.local_date(e.received_at).isoformat()} was not confirmed in time. Submit a manual request if it was correct.", {"event_id": str(e.id)})
        audit.record(db, action="attendance.event_expire", actor=None, actor_type="system", entity_type="attendance_event", entity_id=e.id, before={"status": "pending"}, after={"status": "expired"})
    db.commit()
    return len(rows)


def flag_missing_clock_outs(db: Session, now: datetime | None = None) -> int:
    """Open sessions whose work day passed ``missing_clock_out_flag_local`` become
    ``missing_clock_out`` (the employee can then file a manual request)."""
    now = now or timeutil.now_utc()
    policy = get_policy(db)
    rows = db.execute(select(AttendanceSession).where(AttendanceSession.status == "open")).scalars().all()
    count = 0
    for s in rows:
        cutoff = timeutil.local_datetime(s.work_date, policy.missing_clock_out_flag_local).astimezone(timeutil.UTC)
        if cutoff <= s.clock_in_at:
            cutoff = cutoff + timedelta(days=1)
        if now >= cutoff:
            s.status = "missing_clock_out"
            s.flagged_at = now
            compute_session_metrics(s, policy)
            user = db.get(User, s.user_id)
            if user is not None:
                notifications.notify(db, user, "system", "Missing clock-out", f"No clock-out was recorded for {s.work_date.isoformat()}. Please submit a manual request with your departure time.", {"session_id": str(s.id)})
            audit.record(db, action="attendance.session_flag_missing_clock_out", actor=None, actor_type="system", entity_type="attendance_session", entity_id=s.id, before={"status": "open"}, after={"status": "missing_clock_out"})
            count += 1
    db.commit()
    return count


def pseudonymise_locations(db: Session, now: datetime | None = None) -> int:
    """Drop precise coordinates from events older than ``retention_days_location``."""
    now = now or timeutil.now_utc()
    policy = get_policy(db)
    cutoff = now - timedelta(days=policy.retention_days_location)
    rows = db.execute(
        select(AttendanceEvent).where(AttendanceEvent.received_at < cutoff, AttendanceEvent.location_pseudonymised_at.is_(None), or_(AttendanceEvent.latitude.isnot(None), AttendanceEvent.wifi_bssid.isnot(None)))
    ).scalars().all()
    for e in rows:
        e.latitude = None
        e.longitude = None
        e.accuracy_m = None
        e.wifi_bssid = None
        e.location_pseudonymised_at = now
    db.commit()
    return len(rows)

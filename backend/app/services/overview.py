"""Dashboard queries: overview, live attendance, exceptions. Everything is
computed from the database for the requested day — nothing is fabricated."""
from __future__ import annotations

import uuid
from datetime import date, timedelta
from typing import Any

from sqlalchemy import func, select
from sqlalchemy.orm import Session, selectinload

from app.core import timeutil
from app.models import (
    AttendanceEvent,
    AttendanceSession,
    Department,
    Device,
    ManualRequest,
    User,
)
from app.schemas.auth import user_summary
from app.schemas.common import PageParams
from app.services.config_service import get_policy

EXCEPTION_KINDS = ("missing_clock_out", "late_arrival", "under_review", "expired_confirmation", "dismissed_detection", "absent", "rejected_event", "outside_geofence")


def _active_users(db: Session, department_id: uuid.UUID | None = None) -> list[User]:
    stmt = select(User).options(selectinload(User.department)).where(User.status == "active", User.erased_at.is_(None))
    if department_id is not None:
        stmt = stmt.where(User.department_id == department_id)
    return list(db.execute(stmt.order_by(User.last_name, User.first_name)).scalars())


def _sessions_for_day(db: Session, day: date) -> dict[uuid.UUID, AttendanceSession]:
    out: dict[uuid.UUID, AttendanceSession] = {}
    for s in db.execute(select(AttendanceSession).where(AttendanceSession.work_date == day).order_by(AttendanceSession.clock_in_at.desc())).scalars():
        out.setdefault(s.user_id, s)
    return out


def _is_working_day(db: Session, day: date) -> bool:
    return day.isoweekday() in (get_policy(db).working_days or [1, 2, 3, 4, 5])


def _employed_on(user: User, day: date) -> bool:
    created = timeutil.local_date(user.created_at) if user.created_at else day
    return created <= day


def overview(db: Session, day: date | None = None) -> dict[str, Any]:
    now = timeutil.now_utc()
    day = day or timeutil.local_date(now)
    users = _active_users(db)
    sessions = _sessions_for_day(db, day)
    start_utc, end_utc = timeutil.local_day_bounds_utc(day)
    events_today = list(db.execute(select(AttendanceEvent).options(selectinload(AttendanceEvent.user)).where(AttendanceEvent.received_at >= start_utc, AttendanceEvent.received_at < end_utc)).scalars())

    clocked_in = sum(1 for s in sessions.values() if s.status == "open")
    clocked_out = sum(1 for s in sessions.values() if s.status in ("closed", "missing_clock_out"))
    late = sum(1 for s in sessions.values() if s.is_late)
    employed = [u for u in users if _employed_on(u, day)]
    absent = sum(1 for u in employed if u.id not in sessions) if _is_working_day(db, day) else 0
    pending_conf = int(db.execute(select(func.count()).select_from(AttendanceEvent).where(AttendanceEvent.status == "pending")).scalar_one())
    under_review = int(db.execute(select(func.count()).select_from(AttendanceEvent).where(AttendanceEvent.status == "under_review")).scalar_one())
    pending_requests = int(db.execute(select(func.count()).select_from(ManualRequest).where(ManualRequest.status == "pending")).scalar_one())
    pending_devices = int(db.execute(select(func.count()).select_from(Device).where(Device.status == "pending_approval")).scalar_one())
    exceptions_today = len(_exception_rows(db, day, day, None, None))

    trend = []
    trend_users = users
    for d in timeutil.daterange(day - timedelta(days=13), day):
        day_sessions = _sessions_for_day(db, d)
        present = len({uid for uid in day_sessions})
        late_d = sum(1 for s in day_sessions.values() if s.is_late)
        absent_d = sum(1 for u in trend_users if _employed_on(u, d) and u.id not in day_sessions) if _is_working_day(db, d) else 0
        trend.append({"work_date": d.isoformat(), "present": present, "late": late_d, "absent": absent_d})

    departments = list(db.execute(select(Department).where(Department.active.is_(True)).order_by(Department.name)).scalars())
    breakdown = []
    for dep in departments:
        members = [u for u in employed if u.department_id == dep.id]
        if not members:
            continue
        breakdown.append({"department_id": str(dep.id), "department": dep.name, "present": sum(1 for u in members if u.id in sessions), "total": len(members)})
    unassigned = [u for u in employed if u.department_id is None]
    if unassigned:
        breakdown.append({"department_id": None, "department": "Unassigned", "present": sum(1 for u in unassigned if u.id in sessions), "total": len(unassigned)})

    recent = []
    for e in sorted(events_today, key=lambda x: x.reviewed_at or x.confirmation_received_at or x.received_at, reverse=True)[:15]:
        name = e.user.full_name if e.user else "Unknown"
        verb = "clocked in" if e.event_type == "arrival" else "clocked out"
        if e.status == "confirmed":
            text, at, kind = f"{name} {verb} {timeutil.local_time_str(e.official_at)}", e.confirmation_received_at or e.reviewed_at or e.received_at, "event_confirmed"
        elif e.status == "pending":
            text, at, kind = f"{name}: {e.event_type} detected, awaiting confirmation", e.received_at, "event_pending"
        elif e.status == "under_review":
            text, at, kind = f"{name}: {e.event_type} needs review ({e.review_reason})", e.confirmation_received_at or e.received_at, "event_under_review"
        else:
            text, at, kind = f"{name}: {e.event_type} {e.status}", e.reviewed_at or e.received_at, f"event_{e.status}"
        recent.append({"at": timeutil.utc_iso(at), "kind": kind, "text": text, "employee_id": str(e.user_id), "event_id": str(e.id)})

    return {
        "work_date": day.isoformat(),
        "generated_at": timeutil.utc_iso(now),
        "stats": {
            "total_employees": len(users),
            "clocked_in": clocked_in,
            "clocked_out": clocked_out,
            "absent": absent,
            "late_arrivals": late,
            "pending_confirmations": pending_conf,
            "under_review": under_review,
            "exceptions": exceptions_today,
            "pending_requests": pending_requests,
            "pending_devices": pending_devices,
        },
        "attendance_trend": trend,
        "department_breakdown": breakdown,
        "recent_activity": recent,
    }


def live(db: Session, department_id: uuid.UUID | None = None) -> dict[str, Any]:
    now = timeutil.now_utc()
    today = timeutil.local_date(now)
    users = _active_users(db, department_id)
    ids = [u.id for u in users]
    sessions = _sessions_for_day(db, today)
    open_sessions = {s.user_id: s for s in db.execute(select(AttendanceSession).where(AttendanceSession.status == "open")).scalars()}
    pending: dict[uuid.UUID, AttendanceEvent] = {}
    last_event: dict[uuid.UUID, AttendanceEvent] = {}
    if ids:
        for e in db.execute(select(AttendanceEvent).where(AttendanceEvent.user_id.in_(ids), AttendanceEvent.status == "pending").order_by(AttendanceEvent.received_at.desc())).scalars():
            pending.setdefault(e.user_id, e)
        for e in db.execute(select(AttendanceEvent).where(AttendanceEvent.user_id.in_(ids), AttendanceEvent.received_at >= timeutil.local_day_bounds_utc(today)[0]).order_by(AttendanceEvent.received_at.desc())).scalars():
            last_event.setdefault(e.user_id, e)
    items = []
    for u in users:
        s = open_sessions.get(u.id) or sessions.get(u.id)
        p = pending.get(u.id)
        if s is not None and s.status == "open":
            status = "pending_departure" if p is not None and p.event_type == "departure" else "clocked_in"
            worked = timeutil.minutes_between(s.clock_in_at, now)
        elif s is not None and s.status == "missing_clock_out":
            status, worked = "missing_clock_out", s.worked_minutes
        elif s is not None:
            status, worked = "clocked_out", s.worked_minutes
        elif p is not None and p.event_type == "arrival":
            status, worked = "pending_arrival", 0
        else:
            status, worked = "not_started", 0
        le = last_event.get(u.id)
        items.append(
            {
                "employee": user_summary(u),
                "status": status,
                "clock_in_local": timeutil.local_time_str(s.clock_in_at) if s else None,
                "clock_out_local": timeutil.local_time_str(s.clock_out_at) if s and s.clock_out_at else None,
                "worked_minutes": worked,
                "is_late": bool(s.is_late) if s else False,
                "session_id": str(s.id) if s else None,
                "pending_event_id": str(p.id) if p else None,
                "last_event_at": timeutil.utc_iso(le.received_at) if le else None,
            }
        )
    return {"work_date": today.isoformat(), "server_time": timeutil.utc_iso(now), "items": items}


def _exception_rows(db: Session, date_from: date, date_to: date, department_id: uuid.UUID | None, kind: str | None) -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []
    users = {u.id: u for u in _active_users(db, department_id)}
    user_ids = list(users)
    if not user_ids:
        return []
    wanted = {kind} if kind else set(EXCEPTION_KINDS)
    start_utc, end_utc = timeutil.local_day_bounds_utc(date_from)[0], timeutil.local_day_bounds_utc(date_to)[1]

    if wanted & {"missing_clock_out", "late_arrival"}:
        for s in db.execute(select(AttendanceSession).where(AttendanceSession.user_id.in_(user_ids), AttendanceSession.work_date >= date_from, AttendanceSession.work_date <= date_to)).scalars():
            u = users[s.user_id]
            if "missing_clock_out" in wanted and s.status == "missing_clock_out":
                rows.append({"kind": "missing_clock_out", "work_date": s.work_date.isoformat(), "employee": user_summary(u), "session_id": str(s.id), "event_id": None, "detail": f"Clocked in {timeutil.local_time_str(s.clock_in_at)} without a clock-out", "at": timeutil.utc_iso(s.flagged_at or s.clock_in_at)})
            if "late_arrival" in wanted and s.is_late:
                rows.append({"kind": "late_arrival", "work_date": s.work_date.isoformat(), "employee": user_summary(u), "session_id": str(s.id), "event_id": None, "detail": f"Arrived {timeutil.local_time_str(s.clock_in_at)} ({s.late_minutes} min late)", "at": timeutil.utc_iso(s.clock_in_at)})

    event_kinds = {"under_review": "under_review", "expired": "expired_confirmation", "dismissed": "dismissed_detection", "rejected": "rejected_event"}
    if wanted & (set(event_kinds.values()) | {"outside_geofence"}):
        for e in db.execute(select(AttendanceEvent).where(AttendanceEvent.user_id.in_(user_ids), AttendanceEvent.received_at >= start_utc, AttendanceEvent.received_at < end_utc)).scalars():
            u = users[e.user_id]
            wd = timeutil.local_date(e.official_at or e.received_at).isoformat()
            k = event_kinds.get(e.status)
            if k and k in wanted:
                detail = f"{e.event_type.capitalize()} {e.status.replace('_', ' ')}" + (f": {e.review_reason}" if e.review_reason else "") + (f" ({e.dismiss_reason})" if e.dismiss_reason else "")
                rows.append({"kind": k, "work_date": wd, "employee": user_summary(u), "session_id": str(e.session_id) if e.session_id else None, "event_id": str(e.id), "detail": detail, "at": timeutil.utc_iso(e.reviewed_at or e.confirmation_received_at or e.received_at)})
            if "outside_geofence" in wanted and (e.verification or {}).get("inside_geofence") is False and e.status not in ("rejected", "dismissed"):
                dist = f"{e.distance_from_workplace_m:.0f} m" if e.distance_from_workplace_m is not None else "unknown distance"
                rows.append({"kind": "outside_geofence", "work_date": wd, "employee": user_summary(u), "session_id": str(e.session_id) if e.session_id else None, "event_id": str(e.id), "detail": f"{e.event_type.capitalize()} detected outside the geofence ({dist})", "at": timeutil.utc_iso(e.received_at)})

    if "absent" in wanted:
        today = timeutil.today_local()
        for d in timeutil.daterange(date_from, min(date_to, today - timedelta(days=1))):
            if not _is_working_day(db, d):
                continue
            present = {row[0] for row in db.execute(select(AttendanceSession.user_id).where(AttendanceSession.work_date == d)).all()}
            for uid, u in users.items():
                if uid not in present and _employed_on(u, d):
                    rows.append({"kind": "absent", "work_date": d.isoformat(), "employee": user_summary(u), "session_id": None, "event_id": None, "detail": "No attendance recorded on a working day", "at": timeutil.utc_iso(timeutil.local_day_bounds_utc(d)[1])})

    rows.sort(key=lambda r: (r["at"] or "", r["kind"]), reverse=True)
    return rows


def exceptions(db: Session, params: PageParams, *, date_from: date | None, date_to: date | None, department_id: uuid.UUID | None, kind: str | None) -> tuple[list[dict[str, Any]], int]:
    today = timeutil.today_local()
    date_to = date_to or today
    date_from = date_from or (date_to - timedelta(days=6))
    rows = _exception_rows(db, date_from, date_to, department_id, kind)
    return rows[params.offset : params.offset + params.page_size], len(rows)

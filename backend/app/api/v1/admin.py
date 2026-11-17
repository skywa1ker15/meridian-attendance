"""/admin — management endpoints (roles manager / admin / superadmin).

Every mutation is authorised server-side via ``require_permission`` and written
to the audit log by the service layer.
"""
from __future__ import annotations

from datetime import date

from fastapi import APIRouter, Depends, Query, Request
from fastapi.responses import StreamingResponse
from sqlalchemy import func, select
from sqlalchemy.orm import Session, selectinload

from app.api.deps import (
    get_db,
    page_params,
    parse_uuid_filter,
    parse_uuid_or_404,
    require_permission,
)
from app.core import permissions as P
from app.core import timeutil
from app.core.errors import ApiError
from app.models import AuditLog, User
from app.schemas.admin import (
    BroadcastIn,
    DepartmentIn,
    DepartmentUpdateIn,
    EmployeeCreateIn,
    EmployeeUpdateIn,
    EraseIn,
    PolicyUpdateIn,
    RejectIn,
    RequestApproveIn,
    ReviewIn,
    RoleIn,
    SessionCorrectionIn,
    SessionCreateIn,
    WorkplaceIn,
    WorkplaceUpdateIn,
    audit_dict,
    policy_dict,
)
from app.schemas.attendance import (
    event_dict,
    request_dict,
    session_dict,
    workplace_dict,
)
from app.schemas.auth import user_dict, user_summary
from app.schemas.common import PageParams, ReasonIn, page_dict
from app.services import (
    attendance,
    audit,
    config_service,
    employees,
    manual_requests,
    notifications,
    overview,
    reports,
)
from app.services import devices as device_service

router = APIRouter(prefix="/admin", tags=["admin"])

READ = require_permission(P.P_ADMIN_READ)
REVIEW = require_permission(P.P_ATTENDANCE_REVIEW)
EXPORT = require_permission(P.P_REPORTS_EXPORT)
EMPLOYEES_WRITE = require_permission(P.P_EMPLOYEES_WRITE)
DEVICES_WRITE = require_permission(P.P_DEVICES_WRITE)
CONFIG_WRITE = require_permission(P.P_CONFIG_WRITE)
ROLES_WRITE = require_permission(P.P_ROLES_WRITE)
SUPERADMIN = require_permission(P.P_ROLES_SUPERADMIN)
BROADCAST = require_permission(P.P_NOTIFICATIONS_BROADCAST)


# --- overview / live / exceptions -----------------------------------------------------
@router.get("/overview")
def get_overview(day: date | None = Query(None, alias="date"), _: User = Depends(READ), db: Session = Depends(get_db)):
    return overview.overview(db, day)


@router.get("/attendance/live")
def live(department_id: str | None = Query(None, max_length=64), _: User = Depends(READ), db: Session = Depends(get_db)):
    return overview.live(db, parse_uuid_filter(department_id))


@router.get("/attendance/exceptions")
def exceptions(
    date_from: date | None = Query(None, alias="from"),
    date_to: date | None = Query(None, alias="to"),
    department_id: str | None = Query(None, max_length=64),
    kind: str | None = Query(None, max_length=40),
    params: PageParams = Depends(page_params),
    _: User = Depends(READ),
    db: Session = Depends(get_db),
):
    if kind and kind not in overview.EXCEPTION_KINDS:
        raise ApiError(422, "validation_error", "Unknown exception kind.", {"fields": {"kind": "invalid"}})
    rows, total = overview.exceptions(db, params, date_from=date_from, date_to=date_to, department_id=parse_uuid_filter(department_id), kind=kind)
    return page_dict(rows, total, params)


# --- employees --------------------------------------------------------------------------
@router.get("/employees")
def list_employees(
    search: str | None = Query(None, max_length=120),
    department_id: str | None = Query(None, max_length=64),
    status: str | None = Query(None, max_length=20),
    role: str | None = Query(None, max_length=20),
    params: PageParams = Depends(page_params),
    _: User = Depends(READ),
    db: Session = Depends(get_db),
):
    items, total = employees.list_employees(db, params, search=search, department_id=department_id, status=status, role=role)
    return page_dict(items, total, params)


@router.post("/employees", status_code=201)
def create_employee(body: EmployeeCreateIn, request: Request, actor: User = Depends(EMPLOYEES_WRITE), db: Session = Depends(get_db)):
    user, temp = employees.create_employee(db, actor, body, request)
    return {"user": user_dict(user), "temporary_password": temp}


@router.get("/employees/{employee_id}")
def employee_detail(employee_id: str, _: User = Depends(READ), db: Session = Depends(get_db)):
    return employees.employee_detail(db, employee_id)


@router.patch("/employees/{employee_id}")
def update_employee(employee_id: str, body: EmployeeUpdateIn, request: Request, actor: User = Depends(EMPLOYEES_WRITE), db: Session = Depends(get_db)):
    return user_dict(employees.update_employee(db, actor, employee_id, body, request))


@router.post("/employees/{employee_id}/deactivate")
def deactivate_employee(employee_id: str, body: ReasonIn, request: Request, actor: User = Depends(EMPLOYEES_WRITE), db: Session = Depends(get_db)):
    return user_dict(employees.deactivate_employee(db, actor, employee_id, body.reason, request))


@router.post("/employees/{employee_id}/reactivate")
def reactivate_employee(employee_id: str, request: Request, actor: User = Depends(EMPLOYEES_WRITE), db: Session = Depends(get_db)):
    return user_dict(employees.reactivate_employee(db, actor, employee_id, request))


@router.post("/employees/{employee_id}/reset-password")
def reset_password(employee_id: str, request: Request, actor: User = Depends(EMPLOYEES_WRITE), db: Session = Depends(get_db)):
    _, temp = employees.reset_password(db, actor, employee_id, request)
    return {"temporary_password": temp}


@router.post("/employees/{employee_id}/role")
def change_role(employee_id: str, body: RoleIn, request: Request, actor: User = Depends(ROLES_WRITE), db: Session = Depends(get_db)):
    return user_dict(employees.change_role(db, actor, employee_id, body.role, request))


@router.get("/employees/{employee_id}/export")
def export_employee(employee_id: str, request: Request, actor: User = Depends(EMPLOYEES_WRITE), db: Session = Depends(get_db)):
    return employees.export_employee(db, actor, employee_id, request)


@router.post("/employees/{employee_id}/erase", status_code=202)
def erase_employee(employee_id: str, body: EraseIn, request: Request, actor: User = Depends(SUPERADMIN), db: Session = Depends(get_db)):
    user = employees.erase_employee(db, actor, employee_id, body.confirm_employee_number, body.reason, request)
    return {"status": "erased", "user_id": str(user.id), "erased_at": user.erased_at.isoformat() if user.erased_at else None}


# --- departments ------------------------------------------------------------------------
@router.get("/departments")
def list_departments(_: User = Depends(READ), db: Session = Depends(get_db)):
    return {"items": employees.list_departments(db)}


@router.post("/departments", status_code=201)
def create_department(body: DepartmentIn, request: Request, actor: User = Depends(CONFIG_WRITE), db: Session = Depends(get_db)):
    dep = employees.create_department(db, actor, body, request)
    return employees.department_dict_full(dep, db)


@router.patch("/departments/{department_id}")
def update_department(department_id: str, body: DepartmentUpdateIn, request: Request, actor: User = Depends(CONFIG_WRITE), db: Session = Depends(get_db)):
    dep = employees.update_department(db, actor, department_id, body, request)
    return employees.department_dict_full(dep, db)


# --- devices ------------------------------------------------------------------------------
@router.get("/devices")
def list_devices(
    status: str | None = Query(None, max_length=20),
    user_id: str | None = Query(None, max_length=64),
    search: str | None = Query(None, max_length=120),
    params: PageParams = Depends(page_params),
    _: User = Depends(READ),
    db: Session = Depends(get_db),
):
    rows, total = device_service.list_devices(db, params, status, user_id, search)
    return page_dict([device_service.device_admin_dict(d) for d in rows], total, params)


@router.post("/devices/{device_id}/approve")
def approve_device(device_id: str, request: Request, actor: User = Depends(DEVICES_WRITE), db: Session = Depends(get_db)):
    return device_service.device_admin_dict(device_service.approve_device(db, actor, device_id, request))


@router.post("/devices/{device_id}/revoke")
def revoke_device(device_id: str, body: ReasonIn, request: Request, actor: User = Depends(DEVICES_WRITE), db: Session = Depends(get_db)):
    return device_service.device_admin_dict(device_service.revoke_device(db, actor, device_id, body.reason, request))


# --- attendance records ------------------------------------------------------------------
@router.get("/attendance/sessions")
def list_sessions(
    date_from: date | None = Query(None, alias="from"),
    date_to: date | None = Query(None, alias="to"),
    employee_id: str | None = Query(None, max_length=64),
    department_id: str | None = Query(None, max_length=64),
    status: str | None = Query(None, max_length=20),
    late: bool | None = Query(None),
    manual: bool | None = Query(None),
    params: PageParams = Depends(page_params),
    _: User = Depends(READ),
    db: Session = Depends(get_db),
):
    rows, total = attendance.list_sessions(db, params, user_id=parse_uuid_filter(employee_id), date_from=date_from, date_to=date_to, department_id=parse_uuid_filter(department_id), status=status, late=late, manual=manual)
    return page_dict([session_dict(s, include_employee=True) for s in rows], total, params)


@router.post("/attendance/sessions", status_code=201)
def create_session(body: SessionCreateIn, request: Request, actor: User = Depends(REVIEW), db: Session = Depends(get_db)):
    session = attendance.admin_create_session(db, actor, body, request)
    return session_dict(session, include_employee=True)


@router.get("/attendance/sessions/{session_id}")
def session_detail(session_id: str, _: User = Depends(READ), db: Session = Depends(get_db)):
    session = attendance.get_session(db, session_id)
    return {"session": session_dict(session), "events": [event_dict(e) for e in attendance.session_events(db, session)], "employee": user_summary(session.user)}


@router.patch("/attendance/sessions/{session_id}")
def correct_session(session_id: str, body: SessionCorrectionIn, request: Request, actor: User = Depends(REVIEW), db: Session = Depends(get_db)):
    return session_dict(attendance.correct_session(db, actor, session_id, body, request), include_employee=True)


@router.get("/attendance/events")
def list_events(
    status: str | None = Query(None, max_length=120),
    date_from: date | None = Query(None, alias="from"),
    date_to: date | None = Query(None, alias="to"),
    employee_id: str | None = Query(None, max_length=64),
    department_id: str | None = Query(None, max_length=64),
    event_type: str | None = Query(None, max_length=20),
    params: PageParams = Depends(page_params),
    _: User = Depends(READ),
    db: Session = Depends(get_db),
):
    rows, total = attendance.list_events(db, params, user_id=parse_uuid_filter(employee_id), date_from=date_from, date_to=date_to, status=status, event_type=event_type, department_id=parse_uuid_filter(department_id))
    return page_dict([event_dict(e, include_employee=True) for e in rows], total, params)


@router.post("/attendance/events/{event_id}/approve")
def approve_event(event_id: str, body: ReviewIn, request: Request, actor: User = Depends(REVIEW), db: Session = Depends(get_db)):
    return event_dict(attendance.admin_approve_event(db, actor, event_id, body.note, request), include_employee=True)


@router.post("/attendance/events/{event_id}/reject")
def reject_event(event_id: str, body: RejectIn, request: Request, actor: User = Depends(REVIEW), db: Session = Depends(get_db)):
    return event_dict(attendance.admin_reject_event(db, actor, event_id, body.note, request), include_employee=True)


# --- manual requests ----------------------------------------------------------------------
@router.get("/manual-requests")
def list_manual_requests(
    status: str | None = Query(None, max_length=20),
    type: str | None = Query(None, max_length=32),  # noqa: A002 - API field name
    employee_id: str | None = Query(None, max_length=64),
    date_from: date | None = Query(None, alias="from"),
    date_to: date | None = Query(None, alias="to"),
    params: PageParams = Depends(page_params),
    _: User = Depends(READ),
    db: Session = Depends(get_db),
):
    rows, total = manual_requests.list_requests(db, params, user_id=parse_uuid_filter(employee_id), status=status, type_=type, date_from=date_from, date_to=date_to)
    return page_dict([request_dict(r) for r in rows], total, params)


@router.post("/manual-requests/{request_id}/approve")
def approve_request(request_id: str, body: RequestApproveIn, request: Request, actor: User = Depends(REVIEW), db: Session = Depends(get_db)):
    return request_dict(manual_requests.approve_request(db, actor, request_id, body, request))


@router.post("/manual-requests/{request_id}/reject")
def reject_request(request_id: str, body: RejectIn, request: Request, actor: User = Depends(REVIEW), db: Session = Depends(get_db)):
    return request_dict(manual_requests.reject_request(db, actor, request_id, body.note, request))


# --- reports --------------------------------------------------------------------------------
@router.get("/reports/{report}")
def report(
    report: str,
    request: Request,
    date_from: date | None = Query(None, alias="from"),
    date_to: date | None = Query(None, alias="to"),
    employee_id: str | None = Query(None, max_length=64),
    department_id: str | None = Query(None, max_length=64),
    status: str | None = Query(None, max_length=32),
    format: str = Query("json", max_length=8),  # noqa: A002 - API field name
    actor: User = Depends(EXPORT),
    db: Session = Depends(get_db),
):
    if report not in reports.REPORT_TYPES:
        raise ApiError(404, "not_found", f"Unknown report '{report[:40]}'.")
    if format not in reports.FORMATS:
        raise ApiError(422, "validation_error", "format must be json, csv, xlsx or pdf.", {"fields": {"format": "invalid"}})
    d_from, d_to = reports.default_range(report, date_from, date_to)
    filters = reports.ReportFilters(d_from, d_to, parse_uuid_or_404(employee_id, "Employee"), parse_uuid_or_404(department_id, "Department"), status)
    result = reports.build(db, report, filters)
    if format != "json":
        audit.record(db, action="report.export", actor=actor, entity_type="report", entity_id=report, after={"format": format, "from": d_from.isoformat(), "to": d_to.isoformat(), "rows": len(result.rows)}, request=request)
        db.commit()
        body, media_type, filename = reports.render(result, format)
        return StreamingResponse(iter([body]), media_type=media_type, headers={"Content-Disposition": f'attachment; filename="{filename}"', "Content-Length": str(len(body)), "Cache-Control": "no-store"})
    return result.to_json()


# --- workplaces & policy ----------------------------------------------------------------------
@router.get("/workplaces")
def list_workplaces(_: User = Depends(READ), db: Session = Depends(get_db)):
    return {"items": [workplace_dict(w) for w in config_service.list_workplaces(db)]}


@router.post("/workplaces", status_code=201)
def create_workplace(body: WorkplaceIn, request: Request, actor: User = Depends(CONFIG_WRITE), db: Session = Depends(get_db)):
    return workplace_dict(config_service.create_workplace(db, actor, body, request))


@router.patch("/workplaces/{workplace_id}")
def update_workplace(workplace_id: str, body: WorkplaceUpdateIn, request: Request, actor: User = Depends(CONFIG_WRITE), db: Session = Depends(get_db)):
    return workplace_dict(config_service.update_workplace(db, actor, workplace_id, body, request))


@router.get("/policy")
def get_policy(_: User = Depends(READ), db: Session = Depends(get_db)):
    return policy_dict(config_service.get_policy(db))


@router.patch("/policy")
def update_policy(body: PolicyUpdateIn, request: Request, actor: User = Depends(CONFIG_WRITE), db: Session = Depends(get_db)):
    return policy_dict(config_service.update_policy(db, actor, body, request))


# --- roles ------------------------------------------------------------------------------------
@router.get("/roles")
def roles(_: User = Depends(READ), db: Session = Depends(get_db)):
    counts = dict(db.execute(select(User.role, func.count()).where(User.status == "active", User.erased_at.is_(None)).group_by(User.role)).all())
    return {"roles": [{"role": r, "permissions": P.permissions_for(r), "description": P.ROLE_DESCRIPTIONS[r], "user_count": int(counts.get(r, 0))} for r in P.ROLES]}


# --- audit logs --------------------------------------------------------------------------------
@router.get("/audit-logs")
def audit_logs(
    actor_id: str | None = Query(None, max_length=64),
    action: str | None = Query(None, max_length=64),
    entity_type: str | None = Query(None, max_length=48),
    entity_id: str | None = Query(None, max_length=64),
    date_from: date | None = Query(None, alias="from"),
    date_to: date | None = Query(None, alias="to"),
    search: str | None = Query(None, max_length=120),
    params: PageParams = Depends(page_params),
    _: User = Depends(READ),
    db: Session = Depends(get_db),
):
    stmt = select(AuditLog).options(selectinload(AuditLog.actor))
    if actor_id:
        stmt = stmt.where(AuditLog.actor_user_id == parse_uuid_filter(actor_id))
    if action:
        stmt = stmt.where(AuditLog.action.ilike(f"{action}%"))
    if entity_type:
        stmt = stmt.where(AuditLog.entity_type == entity_type)
    if entity_id:
        stmt = stmt.where(AuditLog.entity_id == entity_id)
    if date_from:
        stmt = stmt.where(AuditLog.created_at >= timeutil.local_day_bounds_utc(date_from)[0])
    if date_to:
        stmt = stmt.where(AuditLog.created_at < timeutil.local_day_bounds_utc(date_to)[1])
    if search:
        like = f"%{search.strip()}%"
        stmt = stmt.outerjoin(User, User.id == AuditLog.actor_user_id).where((AuditLog.action.ilike(like)) | (AuditLog.entity_id.ilike(like)) | (User.first_name.ilike(like)) | (User.last_name.ilike(like)) | (User.employee_number.ilike(like)))
    total = db.execute(select(func.count()).select_from(stmt.order_by(None).subquery())).scalar_one()
    rows = db.execute(stmt.order_by(AuditLog.created_at.desc()).offset(params.offset).limit(params.page_size)).scalars().all()
    return page_dict([audit_dict(a) for a in rows], int(total), params)


# --- notifications -------------------------------------------------------------------------------
@router.post("/notifications/broadcast")
def broadcast(body: BroadcastIn, request: Request, actor: User = Depends(BROADCAST), db: Session = Depends(get_db)):
    dep_id = parse_uuid_or_404(body.department_id, "Department")
    sent = notifications.broadcast(db, body.title, body.body, dep_id)
    audit.record(db, action="notification.broadcast", actor=actor, entity_type="notification", entity_id=None, after={"title": body.title, "department_id": body.department_id, "sent": sent}, request=request)
    db.commit()
    return {"sent": sent}


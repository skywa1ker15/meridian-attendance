"""Employee administration: directory, creation with one-time temporary password,
updates, deactivation, password reset, role changes, POPIA export and erasure."""
from __future__ import annotations

import uuid
from datetime import timedelta
from typing import Any

from sqlalchemy import func, or_, select
from sqlalchemy.orm import Session, selectinload
from starlette.requests import Request

from app.core import timeutil
from app.core.errors import ApiError
from app.core.permissions import ROLE_SUPERADMIN, can_assign_role
from app.core.security import (
    generate_temporary_password,
    hash_password,
    validate_password_policy,
)
from app.core.validation import normalise_sa_phone
from app.models import (
    AttendanceEvent,
    AttendanceSession,
    AuditLog,
    Department,
    Device,
    ManualRequest,
    Notification,
    RefreshToken,
    User,
)
from app.schemas.admin import (
    DepartmentIn,
    DepartmentUpdateIn,
    EmployeeCreateIn,
    EmployeeUpdateIn,
)
from app.schemas.attendance import event_dict, request_dict, session_dict
from app.schemas.auth import device_dict, user_dict
from app.schemas.common import PageParams
from app.services import attendance, audit, auth_service, invitations, notifications
from app.services import devices as device_service
from app.services import email as email_service


def get_user(db: Session, ref: str) -> User:
    uid = attendance.parse_uuid(ref)
    user = db.execute(select(User).options(selectinload(User.department)).where(User.id == uid)).scalar_one_or_none() if uid else None
    if user is None:
        raise ApiError(404, "not_found", "Employee not found.")
    return user


def _department(db: Session, department_id: str | None) -> Department | None:
    if department_id is None:
        return None
    did = attendance.parse_uuid(department_id)
    dep = db.get(Department, did) if did else None
    if dep is None:
        raise ApiError(422, "validation_error", "Unknown department.", {"fields": {"department_id": "unknown"}})
    return dep


def _check_unique(db: Session, *, employee_number: str | None, email: str | None, exclude: uuid.UUID | None = None) -> None:
    if employee_number is not None:
        stmt = select(User.id).where(User.employee_number == employee_number)
        if exclude:
            stmt = stmt.where(User.id != exclude)
        if db.execute(stmt).first():
            raise ApiError(409, "conflict", "That employee number is already in use.", {"fields": {"employee_number": "duplicate"}})
    if email is not None:
        stmt = select(User.id).where(func.lower(User.email) == email.lower())
        if exclude:
            stmt = stmt.where(User.id != exclude)
        if db.execute(stmt).first():
            raise ApiError(409, "conflict", "That email address is already in use.", {"fields": {"email": "duplicate"}})


def list_employees(db: Session, params: PageParams, *, search: str | None, department_id: str | None, status: str | None, role: str | None) -> tuple[list[dict[str, Any]], int]:
    stmt = select(User).options(selectinload(User.department)).where(User.erased_at.is_(None))
    if search:
        like = f"%{search.strip()}%"
        stmt = stmt.where(or_(User.first_name.ilike(like), User.last_name.ilike(like), User.email.ilike(like), User.employee_number.ilike(like), func.concat(User.first_name, " ", User.last_name).ilike(like)))
    if department_id:
        did = attendance.parse_uuid(department_id)
        if did is None:
            return [], 0
        stmt = stmt.where(User.department_id == did)
    if status:
        stmt = stmt.where(User.status == status)
    if role:
        stmt = stmt.where(User.role == role)
    total = db.execute(select(func.count()).select_from(stmt.order_by(None).subquery())).scalar_one()
    users = db.execute(stmt.order_by(User.last_name, User.first_name).offset(params.offset).limit(params.page_size)).scalars().all()
    if not users:
        return [], int(total)
    ids = [u.id for u in users]
    today = timeutil.today_local()
    latest_sessions: dict[uuid.UUID, AttendanceSession] = {}
    for s in db.execute(select(AttendanceSession).where(AttendanceSession.user_id.in_(ids), AttendanceSession.work_date == today).order_by(AttendanceSession.clock_in_at.desc())).scalars():
        latest_sessions.setdefault(s.user_id, s)
    open_sessions = {s.user_id: s for s in db.execute(select(AttendanceSession).where(AttendanceSession.user_id.in_(ids), AttendanceSession.status == "open")).scalars()}
    active_devices: dict[uuid.UUID, Device] = {}
    for d in db.execute(select(Device).where(Device.user_id.in_(ids), Device.status == "active").order_by(Device.registered_at.desc())).scalars():
        active_devices.setdefault(d.user_id, d)
    pending_users = {row[0] for row in db.execute(select(AttendanceEvent.user_id).where(AttendanceEvent.user_id.in_(ids), AttendanceEvent.status == "pending")).all()}
    items = []
    for u in users:
        d = user_dict(u)
        s = open_sessions.get(u.id) or latest_sessions.get(u.id)
        if s is not None and s.status == "open":
            st = "clocked_in"
        elif s is not None and s.status == "missing_clock_out":
            st = "missing_clock_out"
        elif s is not None:
            st = "clocked_out"
        elif u.id in pending_users:
            st = "pending_arrival"
        else:
            st = "not_started"
        d["last_attendance_status"] = st
        d["active_device"] = device_dict(active_devices.get(u.id))
        items.append(d)
    return items, int(total)


def create_employee(db: Session, actor: User, data: EmployeeCreateIn, request: Request | None) -> tuple[User, str | None, dict[str, Any] | None]:
    """Create an account.

    By default (email configured) the employee is *invited*: no password is set and
    a one-time link lets them choose their own password and mobile number. Without
    email — or when the caller passes ``send_invitation: false`` — the old one-time
    temporary password is issued instead.

    Returns ``(user, temporary_password | None, invitation | None)``.
    """
    if not can_assign_role(actor.role, data.role):
        raise ApiError(403, "forbidden", f"You are not allowed to assign the role '{data.role}'.")
    _check_unique(db, employee_number=data.employee_number, email=str(data.email))
    dep = _department(db, data.department_id)
    phone = normalise_sa_phone(data.phone)
    invite = data.send_invitation if data.send_invitation is not None else email_service.email_configured()
    if invite and data.temporary_password:
        raise ApiError(422, "validation_error", "Choose either an invitation or a temporary password, not both.", {"fields": {"temporary_password": "conflicts_with_invitation"}})

    temp: str | None = None
    if not invite:
        temp = data.temporary_password or generate_temporary_password()
        validate_password_policy(temp, [str(data.email), data.employee_number])
    user = User(
        id=uuid.uuid4(),
        employee_number=data.employee_number,
        first_name=data.first_name,
        last_name=data.last_name,
        email=str(data.email),
        phone=phone,
        department_id=dep.id if dep else None,
        role=data.role,
        status="active",
        password_hash=hash_password(temp) if temp else None,
        must_change_password=bool(temp),
    )
    db.add(user)
    db.flush()
    audit.record(db, action="employee.create", actor=actor, entity_type="user", entity_id=user.id, after={"employee_number": user.employee_number, "email": user.email, "role": user.role, "department_id": str(dep.id) if dep else None, "invited": invite}, request=request)
    db.commit()
    db.refresh(user)

    invitation: dict[str, Any] | None = None
    if invite:
        sent, expires_at, link = invitations.issue(db, user, actor, request)
        invitation = {"sent": sent, "expires_at": timeutil.utc_iso(expires_at), "link": link}
        db.refresh(user)
    return user, temp, invitation


def invite_employee(db: Session, actor: User, ref: str, request: Request | None) -> dict[str, Any]:
    """(Re-)issue an invitation, invalidating any earlier unused one."""
    user = get_user(db, ref)
    if user.erased_at is not None:
        raise ApiError(400, "invalid_state", "An erased account cannot be invited.")
    if user.status != "active":
        raise ApiError(400, "invalid_state", "Reactivate the account before inviting this employee.")
    if user.password_hash is not None:
        # The account is already usable; a reset link is the right tool there and it
        # keeps the existing password working until the employee uses it.
        raise ApiError(409, "invalid_state", "This employee already has a password. Use reset password instead.")
    sent, expires_at, link = invitations.issue(db, user, actor, request, audit_action="employee.invite_resent")
    return {"sent": sent, "expires_at": timeutil.utc_iso(expires_at), "link": link}


def update_employee(db: Session, actor: User, ref: str, data: EmployeeUpdateIn, request: Request | None) -> User:
    user = get_user(db, ref)
    changes = data.model_dump(exclude_unset=True)
    clear_department = changes.pop("clear_department", False)
    _check_unique(db, employee_number=changes.get("employee_number"), email=str(changes["email"]) if changes.get("email") else None, exclude=user.id)
    before = {k: getattr(user, k) for k in changes if k != "department_id"}
    if "department_id" in changes:
        before["department_id"] = str(user.department_id) if user.department_id else None
        dep = _department(db, changes["department_id"])
        user.department_id = dep.id if dep else None
        changes["department_id"] = str(dep.id) if dep else None
    elif clear_department:
        before["department_id"] = str(user.department_id) if user.department_id else None
        user.department_id = None
        changes["department_id"] = None
    if "phone" in changes:
        changes["phone"] = normalise_sa_phone(changes["phone"])
    for key in ("employee_number", "first_name", "last_name", "phone"):
        if key in changes:
            setattr(user, key, changes[key])
    if "email" in changes and changes["email"] is not None:
        user.email = str(changes["email"])
        changes["email"] = user.email
    audit.record(db, action="employee.update", actor=actor, entity_type="user", entity_id=user.id, before=before, after=changes, request=request)
    db.commit()
    db.refresh(user)
    return user


def deactivate_employee(db: Session, actor: User, ref: str, reason: str, request: Request | None) -> User:
    user = get_user(db, ref)
    if user.id == actor.id:
        raise ApiError(400, "invalid_state", "You cannot deactivate your own account.")
    if user.role == ROLE_SUPERADMIN and actor.role != ROLE_SUPERADMIN:
        raise ApiError(403, "forbidden", "Only a superadmin can deactivate a superadmin.")
    if user.status == "inactive":
        return user
    now = timeutil.now_utc()
    user.status = "inactive"
    user.deactivated_at = now
    user.deactivation_reason = reason
    auth_service.revoke_all_user_tokens(db, user, "deactivated")
    device_service.revoke_all_for_user(db, user, actor, "account deactivated", request)
    audit.record(db, action="employee.deactivate", actor=actor, entity_type="user", entity_id=user.id, before={"status": "active"}, after={"status": "inactive", "reason": reason}, request=request)
    db.commit()
    db.refresh(user)
    return user


def reactivate_employee(db: Session, actor: User, ref: str, request: Request | None) -> User:
    user = get_user(db, ref)
    if user.erased_at is not None:
        raise ApiError(400, "invalid_state", "An erased account cannot be reactivated.")
    if user.status == "active":
        return user
    user.status = "active"
    user.deactivated_at = None
    user.deactivation_reason = None
    user.failed_login_attempts = 0
    user.locked_until = None
    audit.record(db, action="employee.reactivate", actor=actor, entity_type="user", entity_id=user.id, before={"status": "inactive"}, after={"status": "active"}, request=request)
    db.commit()
    db.refresh(user)
    return user


def reset_password(db: Session, actor: User, ref: str, request: Request | None) -> tuple[User, str]:
    user = get_user(db, ref)
    if user.erased_at is not None or user.status != "active":
        raise ApiError(400, "invalid_state", "Only active accounts can receive a temporary password.")
    if user.role == ROLE_SUPERADMIN and actor.role != ROLE_SUPERADMIN:
        raise ApiError(403, "forbidden", "Only a superadmin can reset a superadmin's password.")
    temp = generate_temporary_password()
    user.password_hash = hash_password(temp)
    user.must_change_password = True
    user.failed_login_attempts = 0
    user.locked_until = None
    auth_service.revoke_all_user_tokens(db, user, "admin_password_reset")
    audit.record(db, action="employee.reset_password", actor=actor, entity_type="user", entity_id=user.id, request=request)
    notifications.notify(db, user, "system", "Password reset", f"An administrator ({actor.full_name}) issued you a temporary password. You must change it at next sign-in.", {})
    db.commit()
    return user, temp


def change_role(db: Session, actor: User, ref: str, role: str, request: Request | None) -> User:
    user = get_user(db, ref)
    if not can_assign_role(actor.role, role):
        raise ApiError(403, "forbidden", f"You are not allowed to assign the role '{role}'.")
    if user.role == ROLE_SUPERADMIN and actor.role != ROLE_SUPERADMIN:
        raise ApiError(403, "forbidden", "Only a superadmin can change a superadmin's role.")
    if user.id == actor.id and role != actor.role:
        raise ApiError(400, "invalid_state", "You cannot change your own role.")
    if user.role == role:
        return user
    before = {"role": user.role}
    user.role = role
    auth_service.revoke_all_user_tokens(db, user, "role_changed")  # forces new tokens with the new role
    audit.record(db, action="employee.role_change", actor=actor, entity_type="user", entity_id=user.id, before=before, after={"role": role}, request=request)
    db.commit()
    db.refresh(user)
    return user


def employee_detail(db: Session, ref: str) -> dict[str, Any]:
    user = get_user(db, ref)
    devices = list(db.execute(select(Device).where(Device.user_id == user.id).order_by(Device.registered_at.desc())).scalars())
    today = timeutil.today_local()
    start = today - timedelta(days=29)
    sessions = list(db.execute(select(AttendanceSession).where(AttendanceSession.user_id == user.id, AttendanceSession.work_date >= start, AttendanceSession.work_date <= today)).scalars())
    present_days = {s.work_date for s in sessions}
    policy_days = set(attendance.get_policy(db).working_days or [1, 2, 3, 4, 5])
    created_day = timeutil.local_date(user.created_at) if user.created_at else start
    working_days = [d for d in timeutil.daterange(max(start, created_day), today) if d.isoweekday() in policy_days]
    absent_days = [d for d in working_days if d not in present_days and d < today]
    manual = int(db.execute(select(func.count()).select_from(ManualRequest).where(ManualRequest.user_id == user.id, ManualRequest.created_at >= timeutil.local_day_bounds_utc(start)[0])).scalar_one())
    return {
        "user": user_dict(user),
        "devices": [device_dict(d) for d in devices],
        "today": attendance.today_summary(db, user),
        "stats_30d": {
            "present_days": len(present_days),
            "late_days": len({s.work_date for s in sessions if s.is_late}),
            "absent_days": len(absent_days),
            "worked_minutes": sum(s.worked_minutes for s in sessions),
            "overtime_minutes": sum(s.overtime_minutes for s in sessions),
            "manual_requests": manual,
        },
    }


def export_employee(db: Session, actor: User, ref: str, request: Request | None) -> dict[str, Any]:
    """POPIA access request: everything stored about the employee."""
    user = get_user(db, ref)
    sessions = db.execute(select(AttendanceSession).options(selectinload(AttendanceSession.workplace)).where(AttendanceSession.user_id == user.id).order_by(AttendanceSession.work_date)).scalars().all()
    events = db.execute(select(AttendanceEvent).options(selectinload(AttendanceEvent.workplace)).where(AttendanceEvent.user_id == user.id).order_by(AttendanceEvent.received_at)).scalars().all()
    requests_ = db.execute(select(ManualRequest).options(selectinload(ManualRequest.user)).where(ManualRequest.user_id == user.id).order_by(ManualRequest.created_at)).scalars().all()
    notes = db.execute(select(Notification).where(Notification.user_id == user.id).order_by(Notification.created_at)).scalars().all()
    devices = db.execute(select(Device).where(Device.user_id == user.id)).scalars().all()
    logins = db.execute(select(RefreshToken).where(RefreshToken.user_id == user.id).order_by(RefreshToken.issued_at)).scalars().all()
    audits = db.execute(select(AuditLog).options(selectinload(AuditLog.actor)).where(or_(AuditLog.entity_id == str(user.id), AuditLog.actor_user_id == user.id)).order_by(AuditLog.created_at)).scalars().all()
    audit.record(db, action="employee.export", actor=actor, entity_type="user", entity_id=user.id, request=request)
    db.commit()
    from app.schemas.admin import (
        audit_dict,  # local import to avoid a cycle at module import
    )

    return {
        "generated_at": timeutil.utc_iso(timeutil.now_utc()),
        "exported_by": {"id": str(actor.id), "name": actor.full_name},
        "user": user_dict(user),
        "devices": [device_dict(d) for d in devices],
        "sessions": [session_dict(s) for s in sessions],
        "events": [event_dict(e) for e in events],
        "manual_requests": [request_dict(r) for r in requests_],
        "notifications": [{"id": str(n.id), "type": n.type, "title": n.title, "body": n.body, "created_at": timeutil.utc_iso(n.created_at), "read_at": timeutil.utc_iso(n.read_at)} for n in notes],
        "sign_ins": [{"issued_at": timeutil.utc_iso(t.issued_at), "ip": t.client_ip, "user_agent": t.user_agent, "device_id": str(t.device_id) if t.device_id else None} for t in logins],
        "audit_logs": [audit_dict(a) for a in audits],
    }


def erase_employee(db: Session, actor: User, ref: str, confirm_employee_number: str, reason: str, request: Request | None) -> User:
    """Pseudonymise personal data. Attendance history is retained (employment
    evidence) but no longer linked to an identifiable person."""
    user = get_user(db, ref)
    if user.employee_number != confirm_employee_number:
        raise ApiError(422, "validation_error", "confirm_employee_number does not match.", {"fields": {"confirm_employee_number": "mismatch"}})
    if user.id == actor.id:
        raise ApiError(400, "invalid_state", "You cannot erase your own account.")
    if user.status == "active":
        raise ApiError(400, "invalid_state", "Deactivate the account before erasing it.")
    if user.erased_at is not None:
        return user
    now = timeutil.now_utc()
    token = uuid.uuid4().hex[:12]
    original_number = user.employee_number
    user.first_name = "Erased"
    user.last_name = f"User {token[:6]}"
    user.email = f"erased-{token}@erased.invalid"
    user.phone = None
    user.employee_number = f"ERASED-{token}"
    user.password_hash = None
    user.must_change_password = False
    user.notification_push = False
    user.erased_at = now
    user.deactivation_reason = None
    auth_service.revoke_all_user_tokens(db, user, "erased")
    device_service.revoke_all_for_user(db, user, actor, "account erased", request)
    for d in db.execute(select(Device).where(Device.user_id == user.id)).scalars():
        d.device_id = f"erased-{uuid.uuid4().hex}"
        d.model = None
        d.manufacturer = None
        d.fcm_token = None
    for e in db.execute(select(AttendanceEvent).where(AttendanceEvent.user_id == user.id)).scalars():
        e.latitude = None
        e.longitude = None
        e.accuracy_m = None
        e.wifi_bssid = None
        e.note = None
        e.location_pseudonymised_at = now
    for r in db.execute(select(ManualRequest).where(ManualRequest.user_id == user.id)).scalars():
        r.reason = "[erased]"
        r.requested_value = None
    for n in db.execute(select(Notification).where(Notification.user_id == user.id)).scalars():
        db.delete(n)
    for t in db.execute(select(RefreshToken).where(RefreshToken.user_id == user.id)).scalars():
        t.client_ip = None
        t.user_agent = None
    for a in db.execute(select(AuditLog).where(AuditLog.entity_type == "user", AuditLog.entity_id == str(user.id))).scalars():
        a.before = {"erased": True} if a.before else None
        a.after = {"erased": True} if a.after else None
        a.ip = None
        a.user_agent = None
    audit.record(db, action="employee.erase", actor=actor, entity_type="user", entity_id=user.id, after={"reason": reason, "original_employee_number_hash": _short_hash(original_number)}, request=request)
    db.commit()
    db.refresh(user)
    return user


def _short_hash(value: str) -> str:
    import hashlib

    return hashlib.sha256(value.encode()).hexdigest()[:16]


# --- departments -----------------------------------------------------------------
def list_departments(db: Session) -> list[dict[str, Any]]:
    rows = db.execute(select(Department, func.count(User.id)).outerjoin(User, (User.department_id == Department.id) & (User.status == "active") & User.erased_at.is_(None)).group_by(Department.id).order_by(Department.name)).all()
    return [{"id": str(d.id), "name": d.name, "code": d.code, "active": d.active, "employee_count": int(c), "created_at": timeutil.utc_iso(d.created_at)} for d, c in rows]


def create_department(db: Session, actor: User, data: DepartmentIn, request: Request | None) -> Department:
    code = data.code.strip().upper()
    if db.execute(select(Department.id).where(or_(func.lower(Department.name) == data.name.lower(), Department.code == code))).first():
        raise ApiError(409, "conflict", "A department with that name or code already exists.")
    dep = Department(id=uuid.uuid4(), name=data.name, code=code, active=True)
    db.add(dep)
    db.flush()
    audit.record(db, action="department.create", actor=actor, entity_type="department", entity_id=dep.id, after={"name": dep.name, "code": dep.code}, request=request)
    db.commit()
    return dep


def update_department(db: Session, actor: User, ref: str, data: DepartmentUpdateIn, request: Request | None) -> Department:
    did = attendance.parse_uuid(ref)
    dep = db.get(Department, did) if did else None
    if dep is None:
        raise ApiError(404, "not_found", "Department not found.")
    changes = data.model_dump(exclude_unset=True)
    before = {k: getattr(dep, k) for k in changes}
    if "code" in changes and changes["code"] is not None:
        changes["code"] = changes["code"].strip().upper()
    for k in ("name", "code"):
        if changes.get(k) is not None:
            clash = db.execute(select(Department.id).where(Department.id != dep.id, (func.lower(Department.name) == changes[k].lower()) if k == "name" else (Department.code == changes[k]))).first()
            if clash:
                raise ApiError(409, "conflict", f"A department with that {k} already exists.")
            setattr(dep, k, changes[k])
    if changes.get("active") is not None:
        dep.active = changes["active"]
    audit.record(db, action="department.update", actor=actor, entity_type="department", entity_id=dep.id, before=before, after=changes, request=request)
    db.commit()
    return dep


def department_dict_full(dep: Department, db: Session) -> dict[str, Any]:
    count = int(db.execute(select(func.count()).select_from(User).where(User.department_id == dep.id, User.status == "active", User.erased_at.is_(None))).scalar_one())
    return {"id": str(dep.id), "name": dep.name, "code": dep.code, "active": dep.active, "employee_count": count, "created_at": timeutil.utc_iso(dep.created_at)}

"""Device registration, replacement, approval and revocation."""
from __future__ import annotations

import uuid
from typing import Any

from sqlalchemy import func, or_, select, update
from sqlalchemy.orm import Session
from starlette.requests import Request

from app.core import timeutil
from app.core.config import get_settings
from app.core.errors import ApiError
from app.core.validation import escape_like
from app.models import AttendancePolicy, Device, RefreshToken, User
from app.schemas.auth import DeviceIn, device_dict
from app.schemas.common import PageParams
from app.services import audit, notifications


def _policy(db: Session) -> AttendancePolicy:
    policy = db.get(AttendancePolicy, 1)
    if policy is None:
        policy = AttendancePolicy(id=1)
        db.add(policy)
        db.flush()
    return policy


def _active_devices(db: Session, user: User) -> list[Device]:
    return list(db.execute(select(Device).where(Device.user_id == user.id, Device.status == "active").order_by(Device.registered_at)).scalars())


def _revoke_row(db: Session, device: Device, actor: User | None, reason: str, now, notify_user: bool = True) -> None:
    device.status = "revoked"
    device.revoked_at = now
    device.revoked_by = actor.id if actor else None
    device.revoke_reason = reason[:1000]
    device.fcm_token = None
    db.execute(update(RefreshToken).where(RefreshToken.device_id == device.id, RefreshToken.revoked_at.is_(None)).values(revoked_at=now, revoke_reason="device_revoked"))
    if notify_user:
        user = db.get(User, device.user_id)
        if user is not None:
            notifications.notify(db, user, "device_revoked", "Device access removed", f"Your device {device.model or device.device_id} no longer has access to the attendance app. Reason: {reason}", {"device_id": str(device.id)})


def register_device(db: Session, user: User, data: DeviceIn, request: Request | None = None) -> Device:
    """Register (or refresh) a device for ``user``. Does not commit.

    * Known (user, device_id): metadata refreshed; revoked devices are returned as-is
      so the caller can refuse them.
    * New device within ``max_active_devices_per_user`` → ``active``.
    * New device over the limit → either replaces the oldest active device (revoked,
      user notified, audit entry) or becomes ``pending_approval`` when the policy
      requires administrator approval.
    """
    now = timeutil.now_utc()
    # Lock the user row so concurrent registrations count active devices consistently.
    db.execute(select(User.id).where(User.id == user.id).with_for_update())
    existing = db.execute(select(Device).where(Device.user_id == user.id, Device.device_id == data.device_id)).scalar_one_or_none()
    if existing is not None:
        if existing.status != "revoked":
            existing.model = data.model or existing.model
            existing.manufacturer = data.manufacturer or existing.manufacturer
            existing.os_version = data.os_version or existing.os_version
            existing.app_version = data.app_version or existing.app_version
            if data.fcm_token:
                existing.fcm_token = data.fcm_token
            existing.last_seen_at = now
        return existing

    policy = _policy(db)
    device = Device(
        id=uuid.uuid4(),
        user_id=user.id,
        device_id=data.device_id,
        model=data.model,
        manufacturer=data.manufacturer,
        os_version=data.os_version,
        app_version=data.app_version,
        fcm_token=data.fcm_token,
        registered_at=now,
        last_seen_at=now,
        status="active",
    )
    active = _active_devices(db, user)
    if len(active) >= policy.max_active_devices_per_user:
        if policy.device_replacement_requires_approval:
            device.status = "pending_approval"
            db.add(device)
            db.flush()
            audit.record(db, action="device.register_pending", actor=user, entity_type="device", entity_id=device.id, after={"device_id": device.device_id, "model": device.model, "status": device.status}, request=request)
            return device
        # Replace the oldest active device(s) so that the new one fits within the limit.
        excess = len(active) - policy.max_active_devices_per_user + 1
        for old in active[:excess]:
            _revoke_row(db, old, user, "replaced by a new device registration", now)
            audit.record(db, action="device.replaced", actor=user, entity_type="device", entity_id=old.id, before={"status": "active"}, after={"status": "revoked", "replaced_by_device_id": data.device_id}, request=request)
    db.add(device)
    db.flush()
    audit.record(db, action="device.register", actor=user, entity_type="device", entity_id=device.id, after={"device_id": device.device_id, "model": device.model, "status": device.status}, request=request)
    return device


def register_and_commit(db: Session, user: User, data: DeviceIn, request: Request | None = None) -> Device:
    device = register_device(db, user, data, request)
    db.commit()
    if device.status == "revoked":
        raise ApiError(403, "device_revoked", "This device has been revoked. Contact your administrator.")
    return device


def get_device(db: Session, device_id: str | uuid.UUID) -> Device:
    try:
        did = uuid.UUID(str(device_id))
    except ValueError as exc:
        raise ApiError(404, "not_found", "Device not found.") from exc
    device = db.get(Device, did)
    if device is None:
        raise ApiError(404, "not_found", "Device not found.")
    return device


def set_fcm_token(db: Session, device: Device, token: str) -> None:
    device.fcm_token = token
    device.last_seen_at = timeutil.now_utc()
    db.commit()


def record_integrity(db: Session, device: Device, integrity_token: str) -> str:
    """Optional Play Integrity verification. Without Google credentials → not_configured."""
    settings = get_settings()
    verdict = "not_configured"
    if settings.play_integrity_enabled and settings.play_integrity_service_account_file:
        verdict = _decode_integrity_token(integrity_token, settings)
    device.integrity_verdict = verdict
    device.integrity_checked_at = timeutil.now_utc()
    db.commit()
    return verdict


def _decode_integrity_token(token: str, settings) -> str:  # pragma: no cover - needs Google credentials
    try:
        import requests
        from google.auth.transport.requests import Request as GoogleRequest
        from google.oauth2 import service_account

        creds = service_account.Credentials.from_service_account_file(settings.play_integrity_service_account_file, scopes=["https://www.googleapis.com/auth/playintegrity"])
        creds.refresh(GoogleRequest())
        url = f"https://playintegrity.googleapis.com/v1/{settings.play_integrity_package_name}:decodeIntegrityToken"
        resp = requests.post(url, json={"integrity_token": token}, headers={"Authorization": f"Bearer {creds.token}"}, timeout=10)
        if resp.status_code != 200:
            return "failed"
        verdicts = resp.json().get("tokenPayloadExternal", {}).get("deviceIntegrity", {}).get("deviceRecognitionVerdict", [])
        return "meets_device_integrity" if "MEETS_DEVICE_INTEGRITY" in verdicts else "failed"
    except Exception:
        return "failed"


# --- administration -----------------------------------------------------------
def list_devices(db: Session, params: PageParams, status: str | None, user_id: str | None, search: str | None) -> tuple[list[Device], int]:
    stmt = select(Device).join(User, User.id == Device.user_id)
    if status:
        stmt = stmt.where(Device.status == status)
    if user_id:
        try:
            stmt = stmt.where(Device.user_id == uuid.UUID(user_id))
        except ValueError:
            return [], 0
    if search:
        like = f"%{escape_like(search.strip())}%"
        stmt = stmt.where(or_(User.first_name.ilike(like), User.last_name.ilike(like), User.employee_number.ilike(like), Device.model.ilike(like), Device.device_id.ilike(like)))
    total = db.execute(select(func.count()).select_from(stmt.subquery())).scalar_one()
    rows = db.execute(stmt.order_by(Device.registered_at.desc()).offset(params.offset).limit(params.page_size)).scalars().all()
    return list(rows), int(total)


def approve_device(db: Session, actor: User, device_id: str, request: Request | None) -> Device:
    device = get_device(db, device_id)
    if device.status != "pending_approval":
        raise ApiError(400, "invalid_state", "Only devices pending approval can be approved.")
    now = timeutil.now_utc()
    user = db.get(User, device.user_id)
    policy = _policy(db)
    active = [d for d in _active_devices(db, user) if d.id != device.id]
    excess = len(active) - policy.max_active_devices_per_user + 1
    for old in active[: max(0, excess)]:
        _revoke_row(db, old, actor, "replaced by an approved device", now)
        audit.record(db, action="device.replaced", actor=actor, entity_type="device", entity_id=old.id, before={"status": "active"}, after={"status": "revoked"}, request=request)
    device.status = "active"
    audit.record(db, action="device.approve", actor=actor, entity_type="device", entity_id=device.id, before={"status": "pending_approval"}, after={"status": "active"}, request=request)
    notifications.notify(db, user, "system", "Device approved", "Your new device has been approved for attendance.", {"device_id": str(device.id)})
    db.commit()
    return device


def revoke_device(db: Session, actor: User, device_id: str, reason: str, request: Request | None) -> Device:
    device = get_device(db, device_id)
    if device.status == "revoked":
        return device
    now = timeutil.now_utc()
    before = {"status": device.status}
    _revoke_row(db, device, actor, reason, now)
    audit.record(db, action="device.revoke", actor=actor, entity_type="device", entity_id=device.id, before=before, after={"status": "revoked", "reason": reason}, request=request)
    db.commit()
    return device


def revoke_all_for_user(db: Session, user: User, actor: User | None, reason: str, request: Request | None = None) -> int:
    now = timeutil.now_utc()
    count = 0
    for d in db.execute(select(Device).where(Device.user_id == user.id, Device.status != "revoked")).scalars():
        _revoke_row(db, d, actor, reason, now, notify_user=False)
        audit.record(db, action="device.revoke", actor=actor, entity_type="device", entity_id=d.id, after={"status": "revoked", "reason": reason}, request=request, actor_type=None if actor else "system")
        count += 1
    return count


def device_admin_dict(device: Device) -> dict[str, Any]:
    d = device_dict(device) or {}
    user = device.user
    d["employee"] = {
        "id": str(user.id),
        "employee_number": user.employee_number,
        "first_name": user.first_name,
        "last_name": user.last_name,
        "full_name": user.full_name,
        "department": {"id": str(user.department.id), "name": user.department.name, "code": user.department.code} if user.department else None,
    } if user else None
    return d

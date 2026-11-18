"""Authentication, user, device and profile schemas."""
from __future__ import annotations

from typing import Any

from pydantic import EmailStr, Field

from app.core.permissions import permissions_for
from app.core.timeutil import utc_iso
from app.models import Device, User
from app.schemas.common import ApiModel


class DeviceIn(ApiModel):
    device_id: str = Field(..., min_length=8, max_length=128)
    model: str | None = Field(None, max_length=120)
    manufacturer: str | None = Field(None, max_length=120)
    os_version: str | None = Field(None, max_length=40)
    app_version: str | None = Field(None, max_length=40)
    fcm_token: str | None = Field(None, max_length=512)


class LoginIn(ApiModel):
    identifier: str = Field(..., min_length=1, max_length=254)
    password: str = Field(..., min_length=1, max_length=256)
    device: DeviceIn | None = None


class RefreshIn(ApiModel):
    refresh_token: str = Field(..., min_length=16, max_length=512)


class LogoutIn(ApiModel):
    refresh_token: str = Field(..., min_length=16, max_length=512)


class ChangePasswordIn(ApiModel):
    current_password: str = Field(..., min_length=1, max_length=256)
    new_password: str = Field(..., min_length=1, max_length=256)


class ForgotPasswordIn(ApiModel):
    identifier: str = Field(..., min_length=1, max_length=254)


class ResetPasswordIn(ApiModel):
    token: str = Field(..., min_length=16, max_length=512)
    new_password: str = Field(..., min_length=1, max_length=256)


class InvitationAcceptIn(ApiModel):
    # Length is not validated here: a malformed token must come back as
    # 400 token_invalid like any other bad one, not as a 422.
    token: str = Field(..., min_length=1, max_length=512)
    phone: str = Field(..., min_length=6, max_length=32)
    password: str = Field(..., min_length=1, max_length=256)
    device: DeviceIn | None = None


class NotificationPreferencesIn(ApiModel):
    push: bool | None = None


class UpdateMeIn(ApiModel):
    phone: str | None = Field(None, max_length=32)
    notification_preferences: NotificationPreferencesIn | None = None


class FcmTokenIn(ApiModel):
    fcm_token: str = Field(..., min_length=8, max_length=512)


class IntegrityIn(ApiModel):
    integrity_token: str = Field(..., min_length=8, max_length=8192)


# --- serializers -------------------------------------------------------------
def department_dict(dep) -> dict[str, Any] | None:
    if dep is None:
        return None
    return {"id": str(dep.id), "name": dep.name, "code": dep.code, "active": dep.active}


def user_summary(user: User) -> dict[str, Any]:
    return {
        "id": str(user.id),
        "employee_number": user.employee_number,
        "first_name": user.first_name,
        "last_name": user.last_name,
        "full_name": user.full_name,
        "department": department_dict(user.department),
        "role": user.role,
        "status": user.status,
    }


def user_dict(user: User) -> dict[str, Any]:
    return {
        "id": str(user.id),
        "employee_number": user.employee_number,
        "first_name": user.first_name,
        "last_name": user.last_name,
        "full_name": user.full_name,
        "email": user.email,
        "phone": user.phone,
        "role": user.role,
        "status": user.status,
        "department": department_dict(user.department),
        "must_change_password": user.must_change_password,
        "notification_preferences": {"push": user.notification_push},
        "created_at": utc_iso(user.created_at),
        "last_login_at": utc_iso(user.last_login_at),
        "deactivated_at": utc_iso(user.deactivated_at),
        "permissions": permissions_for(user.role),
    }


def device_dict(device: Device | None) -> dict[str, Any] | None:
    if device is None:
        return None
    return {
        "id": str(device.id),
        "device_id": device.device_id,
        "model": device.model,
        "manufacturer": device.manufacturer,
        "os_version": device.os_version,
        "app_version": device.app_version,
        "status": device.status,
        "has_fcm_token": bool(device.fcm_token),
        "registered_at": utc_iso(device.registered_at),
        "last_seen_at": utc_iso(device.last_seen_at),
        "revoked_at": utc_iso(device.revoked_at),
        "revoke_reason": device.revoke_reason,
        "integrity_verdict": device.integrity_verdict,
    }


EmailAddress = EmailStr

"""Administrator request schemas."""
from __future__ import annotations

import re
from datetime import date, datetime
from typing import Any, Literal

from pydantic import EmailStr, Field, field_validator, model_validator

from app.core.timeutil import ensure_utc, utc_iso
from app.models import AttendancePolicy, AuditLog
from app.schemas.common import ApiModel

HHMM = r"^([01]\d|2[0-3]):[0-5]\d$"
_BSSID_RE = re.compile(r"([0-9a-f]{2}:){5}[0-9a-f]{2}")


def normalise_bssids(values: list[str]) -> list[str]:
    out: list[str] = []
    for b in values:
        b = b.strip().lower()
        if not _BSSID_RE.fullmatch(b):
            raise ValueError(f"invalid BSSID: {b}")
        if b not in out:
            out.append(b)
    return out


class EmployeeCreateIn(ApiModel):
    employee_number: str = Field(..., min_length=1, max_length=40)
    first_name: str = Field(..., min_length=1, max_length=80)
    last_name: str = Field(..., min_length=1, max_length=80)
    email: EmailStr
    phone: str | None = Field(None, max_length=32)
    department_id: str | None = None
    role: Literal["employee", "manager", "admin", "superadmin"] = "employee"
    temporary_password: str | None = Field(None, min_length=1, max_length=256)
    # None = decide server-side: invite when email sending is configured, otherwise
    # fall back to a one-time temporary password.
    send_invitation: bool | None = None


class EmployeeUpdateIn(ApiModel):
    employee_number: str | None = Field(None, min_length=1, max_length=40)
    first_name: str | None = Field(None, min_length=1, max_length=80)
    last_name: str | None = Field(None, min_length=1, max_length=80)
    email: EmailStr | None = None
    phone: str | None = Field(None, max_length=32)
    department_id: str | None = None
    clear_department: bool = False


class RoleIn(ApiModel):
    role: Literal["employee", "manager", "admin", "superadmin"]


class EraseIn(ApiModel):
    confirm_employee_number: str = Field(..., min_length=1, max_length=40)
    reason: str = Field(..., min_length=3, max_length=1000)


class DepartmentIn(ApiModel):
    name: str = Field(..., min_length=1, max_length=120)
    code: str = Field(..., min_length=1, max_length=20)


class DepartmentUpdateIn(ApiModel):
    name: str | None = Field(None, min_length=1, max_length=120)
    code: str | None = Field(None, min_length=1, max_length=20)
    active: bool | None = None


class SessionCorrectionIn(ApiModel):
    clock_in_at: datetime | None = None
    clock_out_at: datetime | None = None
    note: str = Field(..., min_length=3, max_length=1000)

    @field_validator("clock_in_at", "clock_out_at")
    @classmethod
    def _aware(cls, v: datetime | None) -> datetime | None:
        return ensure_utc(v) if v else v

    @model_validator(mode="after")
    def _any(self):
        if self.clock_in_at is None and self.clock_out_at is None:
            raise ValueError("Provide clock_in_at and/or clock_out_at")
        return self


class SessionCreateIn(ApiModel):
    employee_id: str
    work_date: date
    clock_in_at: datetime
    clock_out_at: datetime | None = None
    note: str = Field(..., min_length=3, max_length=1000)

    @field_validator("clock_in_at", "clock_out_at")
    @classmethod
    def _aware(cls, v: datetime | None) -> datetime | None:
        return ensure_utc(v) if v else v


class ReviewIn(ApiModel):
    note: str | None = Field(None, max_length=1000)


class RejectIn(ApiModel):
    note: str = Field(..., min_length=2, max_length=1000)


class RequestApproveIn(ApiModel):
    note: str | None = Field(None, max_length=1000)
    clock_in_at: datetime | None = None
    clock_out_at: datetime | None = None

    @field_validator("clock_in_at", "clock_out_at")
    @classmethod
    def _aware(cls, v: datetime | None) -> datetime | None:
        return ensure_utc(v) if v else v


class WorkplaceIn(ApiModel):
    name: str = Field(..., min_length=1, max_length=120)
    address: str | None = Field(None, max_length=500)
    latitude: float = Field(..., ge=-90, le=90)
    longitude: float = Field(..., ge=-180, le=180)
    coordinates_confirmed: bool = False
    radius_m: int = Field(150, ge=30, le=5000)
    accuracy_threshold_m: int = Field(100, ge=5, le=2000)
    entry_detection_enabled: bool = True
    exit_detection_enabled: bool = True
    dwell_seconds: int = Field(120, ge=0, le=3600)
    exit_debounce_seconds: int = Field(300, ge=0, le=7200)
    wifi_verification_enabled: bool = False
    wifi_ssid: str | None = Field(None, max_length=64)
    wifi_bssids: list[str] = Field(default_factory=list, max_length=100)
    wifi_required: bool = False
    timezone: str = Field("Africa/Johannesburg", max_length=64)
    active: bool = True

    @field_validator("wifi_bssids")
    @classmethod
    def _bssids(cls, v: list[str]) -> list[str]:
        return normalise_bssids(v)


class WorkplaceUpdateIn(ApiModel):
    name: str | None = Field(None, min_length=1, max_length=120)
    address: str | None = Field(None, max_length=500)
    latitude: float | None = Field(None, ge=-90, le=90)
    longitude: float | None = Field(None, ge=-180, le=180)
    coordinates_confirmed: bool | None = None
    radius_m: int | None = Field(None, ge=30, le=5000)
    accuracy_threshold_m: int | None = Field(None, ge=5, le=2000)
    entry_detection_enabled: bool | None = None
    exit_detection_enabled: bool | None = None
    dwell_seconds: int | None = Field(None, ge=0, le=3600)
    exit_debounce_seconds: int | None = Field(None, ge=0, le=7200)
    wifi_verification_enabled: bool | None = None
    wifi_ssid: str | None = Field(None, max_length=64)
    wifi_bssids: list[str] | None = Field(None, max_length=100)
    wifi_required: bool | None = None
    timezone: str | None = Field(None, max_length=64)
    active: bool | None = None

    @field_validator("wifi_bssids")
    @classmethod
    def _bssids(cls, v: list[str] | None) -> list[str] | None:
        return normalise_bssids(v) if v is not None else None


class PolicyUpdateIn(ApiModel):
    workday_start_local: str | None = Field(None, pattern=HHMM)
    workday_end_local: str | None = Field(None, pattern=HHMM)
    late_after_local: str | None = Field(None, pattern=HHMM)
    grace_minutes: int | None = Field(None, ge=0, le=240)
    standard_day_minutes: int | None = Field(None, ge=60, le=1440)
    break_minutes: int | None = Field(None, ge=0, le=480)
    overtime_after_minutes: int | None = Field(None, ge=60, le=1440)
    working_days: list[int] | None = None
    pending_expiry_hours: int | None = Field(None, ge=1, le=168)
    missing_clock_out_flag_local: str | None = Field(None, pattern=HHMM)
    max_future_skew_seconds: int | None = Field(None, ge=0, le=3600)
    max_offline_age_hours: int | None = Field(None, ge=1, le=720)
    online_skew_tolerance_seconds: int | None = Field(None, ge=0, le=86400)
    duplicate_window_seconds: int | None = Field(None, ge=0, le=86400)
    official_time_source: Literal["server_received", "device_detected"] | None = None
    min_verification_score_for_auto_confirm: float | None = Field(None, ge=0, le=1)
    max_active_devices_per_user: int | None = Field(None, ge=1, le=5)
    device_replacement_requires_approval: bool | None = None
    require_integrity_verdict: bool | None = None
    retention_days_attendance: int | None = Field(None, ge=30, le=3650)
    retention_days_location: int | None = Field(None, ge=1, le=3650)
    retention_days_audit: int | None = Field(None, ge=30, le=3650)
    notification_quiet_hours: dict[str, Any] | None = None

    @field_validator("working_days")
    @classmethod
    def _days(cls, v):
        if v is None:
            return v
        if not v or any((not isinstance(d, int)) or d < 1 or d > 7 for d in v):
            raise ValueError("working_days must contain ISO weekday numbers 1-7")
        return sorted(set(v))


class BroadcastIn(ApiModel):
    title: str = Field(..., min_length=1, max_length=160)
    body: str = Field(..., min_length=1, max_length=2000)
    department_id: str | None = None


def policy_dict(p: AttendancePolicy) -> dict[str, Any]:
    return {
        "workday_start_local": p.workday_start_local,
        "workday_end_local": p.workday_end_local,
        "late_after_local": p.late_after_local,
        "grace_minutes": p.grace_minutes,
        "standard_day_minutes": p.standard_day_minutes,
        "break_minutes": p.break_minutes,
        "overtime_after_minutes": p.overtime_after_minutes,
        "working_days": list(p.working_days or []),
        "pending_expiry_hours": p.pending_expiry_hours,
        "missing_clock_out_flag_local": p.missing_clock_out_flag_local,
        "max_future_skew_seconds": p.max_future_skew_seconds,
        "max_offline_age_hours": p.max_offline_age_hours,
        "online_skew_tolerance_seconds": p.online_skew_tolerance_seconds,
        "duplicate_window_seconds": p.duplicate_window_seconds,
        "official_time_source": p.official_time_source,
        "min_verification_score_for_auto_confirm": p.min_verification_score_for_auto_confirm,
        "max_active_devices_per_user": p.max_active_devices_per_user,
        "device_replacement_requires_approval": p.device_replacement_requires_approval,
        "require_integrity_verdict": p.require_integrity_verdict,
        "retention_days_attendance": p.retention_days_attendance,
        "retention_days_location": p.retention_days_location,
        "retention_days_audit": p.retention_days_audit,
        "notification_quiet_hours": p.notification_quiet_hours,
        "updated_at": utc_iso(p.updated_at),
        "updated_by": str(p.updated_by) if p.updated_by else None,
        "note": "Defaults — review with Meridian management. These are not Meridian's employment policies.",
    }


def audit_dict(a: AuditLog) -> dict[str, Any]:
    actor = None
    if a.actor is not None:
        actor = {"id": str(a.actor.id), "name": a.actor.full_name, "employee_number": a.actor.employee_number, "role": a.actor.role}
    return {
        "id": str(a.id),
        "created_at": utc_iso(a.created_at),
        "actor": actor,
        "actor_type": a.actor_type,
        "action": a.action,
        "entity_type": a.entity_type,
        "entity_id": a.entity_id,
        "before": a.before,
        "after": a.after,
        "ip": a.ip,
        "user_agent": a.user_agent,
        "request_id": a.request_id,
    }

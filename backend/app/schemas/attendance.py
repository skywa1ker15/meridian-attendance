"""Attendance, session, manual request and notification schemas."""
from __future__ import annotations

from datetime import date, datetime
from typing import Any, Literal

from pydantic import Field, field_validator, model_validator

from app.core.timeutil import ensure_utc, local_iso, local_time_str, utc_iso
from app.models import (
    AttendanceEvent,
    AttendanceSession,
    ManualRequest,
    Notification,
    Workplace,
)
from app.models import enums as E
from app.schemas.auth import user_summary
from app.schemas.common import ApiModel, LocationIn, WifiIn


class EventIn(ApiModel):
    client_event_id: str = Field(..., min_length=8, max_length=64)
    workplace_id: str = Field(..., min_length=8, max_length=64)
    event_type: Literal["arrival", "departure"]
    source: Literal["geofence", "wifi", "manual"] = "geofence"
    detected_at: datetime
    device_timezone: str | None = Field(None, max_length=64)
    geofence_transition: Literal["enter", "dwell", "exit", "none"] = "none"
    location: LocationIn | None = None
    wifi: WifiIn | None = None
    offline_queued: bool = False
    battery_level: int | None = Field(None, ge=0, le=100)
    note: str | None = Field(None, max_length=1000)

    @field_validator("detected_at")
    @classmethod
    def _aware(cls, v: datetime) -> datetime:
        return ensure_utc(v)


class ConfirmIn(ApiModel):
    client_confirmation_id: str = Field(..., min_length=8, max_length=64)
    confirmed_at: datetime
    location: LocationIn | None = None
    wifi: WifiIn | None = None
    note: str | None = Field(None, max_length=1000)

    @field_validator("confirmed_at")
    @classmethod
    def _aware(cls, v: datetime) -> datetime:
        return ensure_utc(v)


class DismissIn(ApiModel):
    reason: Literal["not_at_work", "wrong_detection", "other"]
    note: str | None = Field(None, max_length=1000)


class SyncItemIn(ApiModel):
    kind: Literal["event", "confirmation", "dismissal"]
    event_id: str | None = Field(None, max_length=64)
    payload: dict[str, Any]


class SyncIn(ApiModel):
    items: list[SyncItemIn] = Field(..., max_length=200)


class ManualRequestIn(ApiModel):
    client_request_id: str = Field(..., min_length=8, max_length=64)
    type: Literal["missed_clock_in", "missed_clock_out", "full_day", "correction", "profile_correction"]
    work_date: date | None = None
    requested_clock_in: datetime | None = None
    requested_clock_out: datetime | None = None
    reason: str = Field(..., min_length=3, max_length=1000)
    session_id: str | None = Field(None, max_length=64)
    field: Literal["first_name", "last_name", "email", "phone", "department"] | None = None
    requested_value: str | None = Field(None, max_length=254)

    @field_validator("requested_clock_in", "requested_clock_out")
    @classmethod
    def _aware(cls, v: datetime | None) -> datetime | None:
        return ensure_utc(v) if v else v

    @model_validator(mode="after")
    def _consistency(self):
        if self.type == "profile_correction":
            if not self.field or self.requested_value is None:
                raise ValueError("profile_correction requires field and requested_value")
            return self
        if self.work_date is None:
            raise ValueError("work_date is required")
        if self.type in ("missed_clock_in", "full_day", "correction") and self.requested_clock_in is None:
            raise ValueError("requested_clock_in is required for this request type")
        if self.type in ("missed_clock_out", "full_day") and self.requested_clock_out is None:
            raise ValueError("requested_clock_out is required for this request type")
        if self.requested_clock_in and self.requested_clock_out and self.requested_clock_out <= self.requested_clock_in:
            raise ValueError("requested_clock_out must be after requested_clock_in")
        return self


# --- serializers -------------------------------------------------------------
def workplace_dict(w: Workplace, include_bssids: bool = True) -> dict[str, Any]:
    return {
        "id": str(w.id),
        "name": w.name,
        "address": w.address,
        "latitude": w.latitude,
        "longitude": w.longitude,
        "coordinates_confirmed": w.coordinates_confirmed,
        "radius_m": w.radius_m,
        "accuracy_threshold_m": w.accuracy_threshold_m,
        "entry_detection_enabled": w.entry_detection_enabled,
        "exit_detection_enabled": w.exit_detection_enabled,
        "dwell_seconds": w.dwell_seconds,
        "exit_debounce_seconds": w.exit_debounce_seconds,
        "wifi_verification_enabled": w.wifi_verification_enabled,
        "wifi_ssid": w.wifi_ssid,
        "wifi_bssids": list(w.wifi_bssids or []) if include_bssids else [],
        "wifi_required": w.wifi_required,
        "timezone": w.timezone,
        "active": w.active,
        "config_version": w.config_version,
        "updated_at": utc_iso(w.updated_at),
    }


def event_dict(e: AttendanceEvent, include_employee: bool = False) -> dict[str, Any]:
    loc = None
    if e.latitude is not None and e.longitude is not None:
        loc = {"latitude": e.latitude, "longitude": e.longitude, "accuracy_m": e.accuracy_m}
    d = {
        "id": str(e.id),
        "client_event_id": e.client_event_id,
        "event_type": e.event_type,
        "source": e.source,
        "status": e.status,
        "workplace_id": str(e.workplace_id) if e.workplace_id else None,
        "workplace_name": e.workplace.name if e.workplace else None,
        "geofence_transition": e.geofence_transition,
        "detected_at": utc_iso(e.detected_at),
        "detected_local": local_iso(e.detected_at),
        "received_at": utc_iso(e.received_at),
        "official_at": utc_iso(e.official_at),
        "official_local": local_iso(e.official_at),
        "confirmed_at": utc_iso(e.confirmed_at),
        "expires_at": utc_iso(e.expires_at),
        "location": loc,
        "distance_from_workplace_m": round(e.distance_from_workplace_m, 1) if e.distance_from_workplace_m is not None else None,
        "wifi_verified": e.wifi_verified,
        "wifi_ssid": e.wifi_ssid,
        "verification_score": round(e.verification_score, 3),
        "verification": e.verification or {},
        "offline_queued": e.offline_queued,
        "battery_level": e.battery_level,
        "note": e.note,
        "review_reason": e.review_reason,
        "reviewed_by": str(e.reviewed_by) if e.reviewed_by else None,
        "reviewed_at": utc_iso(e.reviewed_at),
        "review_note": e.review_note,
        "dismiss_reason": e.dismiss_reason,
        "session_id": str(e.session_id) if e.session_id else None,
        "created_at": utc_iso(e.created_at),
    }
    if include_employee and e.user is not None:
        d["employee"] = user_summary(e.user)
    return d


def session_dict(s: AttendanceSession, include_employee: bool = False, now: datetime | None = None) -> dict[str, Any]:
    worked = s.worked_minutes
    if s.status == "open" and now is not None:
        worked = max(0, int((ensure_utc(now) - ensure_utc(s.clock_in_at)).total_seconds() // 60))
    d = {
        "id": str(s.id),
        "work_date": s.work_date.isoformat(),
        "status": s.status,
        "workplace_id": str(s.workplace_id) if s.workplace_id else None,
        "workplace_name": s.workplace.name if s.workplace else None,
        "clock_in_at": utc_iso(s.clock_in_at),
        "clock_out_at": utc_iso(s.clock_out_at),
        "clock_in_local": local_iso(s.clock_in_at),
        "clock_out_local": local_iso(s.clock_out_at),
        "clock_in_time": local_time_str(s.clock_in_at),
        "clock_out_time": local_time_str(s.clock_out_at),
        "clock_in_event_id": str(s.clock_in_event_id) if s.clock_in_event_id else None,
        "clock_out_event_id": str(s.clock_out_event_id) if s.clock_out_event_id else None,
        "worked_minutes": worked,
        "break_minutes": s.break_minutes,
        "overtime_minutes": s.overtime_minutes,
        "is_late": s.is_late,
        "late_minutes": s.late_minutes,
        "is_manual": s.is_manual,
        "corrected": s.corrected,
        "correction_note": s.correction_note,
        "notes": s.notes,
        "created_at": utc_iso(s.created_at),
        "updated_at": utc_iso(s.updated_at),
    }
    if include_employee and s.user is not None:
        d["employee"] = user_summary(s.user)
    return d


def request_dict(r: ManualRequest) -> dict[str, Any]:
    return {
        "id": str(r.id),
        "client_request_id": r.client_request_id,
        "type": r.type,
        "status": r.status,
        "work_date": r.work_date.isoformat() if r.work_date else None,
        "requested_clock_in": utc_iso(r.requested_clock_in),
        "requested_clock_out": utc_iso(r.requested_clock_out),
        "requested_clock_in_local": local_iso(r.requested_clock_in),
        "requested_clock_out_local": local_iso(r.requested_clock_out),
        "reason": r.reason,
        "session_id": str(r.session_id) if r.session_id else None,
        "field": r.field,
        "requested_value": r.requested_value,
        "employee": user_summary(r.user) if r.user else None,
        "reviewed_by": str(r.reviewed_by) if r.reviewed_by else None,
        "reviewed_at": utc_iso(r.reviewed_at),
        "review_note": r.review_note,
        "resulting_session_id": str(r.resulting_session_id) if r.resulting_session_id else None,
        "created_at": utc_iso(r.created_at),
    }


def notification_dict(n: Notification) -> dict[str, Any]:
    return {
        "id": str(n.id),
        "type": n.type,
        "title": n.title,
        "body": n.body,
        "data": n.data or {},
        "read_at": utc_iso(n.read_at),
        "created_at": utc_iso(n.created_at),
    }


assert set(E.NOTIFICATION_TYPES)  # keep import used for linters

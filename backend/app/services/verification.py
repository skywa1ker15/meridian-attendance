"""Verification engine: turns the signals of a detected event into a score and a
decision (see docs/ARCHITECTURE.md §3 and docs/API.md §4).

Decisions
* ``auto``   — the employee may confirm; confirmation records attendance.
* ``review`` — the employee may confirm; the event then waits for a manager.
* ``reject`` — the event is stored as ``rejected`` (implausible timestamp, stale).

The engine is pure: it never touches the database so it can be unit-tested.
"""
from __future__ import annotations

from dataclasses import asdict, dataclass, field
from datetime import datetime, timedelta
from typing import Any

from app.core.geo import haversine_m, valid_coordinates
from app.models import AttendancePolicy, Device, Workplace
from app.schemas.attendance import EventIn

# Review / reject reason codes (stored in attendance_events.review_reason)
R_TIMESTAMP_IMPLAUSIBLE = "timestamp_implausible"
R_STALE_OFFLINE = "stale_offline"
R_CLOCK_SKEW = "clock_skew"
R_OFFLINE = "offline_submission"
R_OUTSIDE_GEOFENCE = "outside_geofence"
R_DEPARTURE_STILL_INSIDE = "departure_still_inside"
R_NO_LOCATION = "no_location"
R_WIFI_REQUIRED = "wifi_required"
R_WORKPLACE_UNCONFIRMED = "workplace_coordinates_unconfirmed"
R_DEVICE_INACTIVE = "device_inactive"
R_INTEGRITY = "device_integrity"
R_LOW_SCORE = "low_verification_score"
R_NO_OPEN_SESSION = "no_open_session"
R_SESSION_ALREADY_OPEN = "session_already_open"
R_MANUAL_SOURCE = "manual_source"


@dataclass
class VerificationResult:
    score: float
    decision: str  # auto | review | reject
    reason: str | None
    inside_geofence: bool | None
    accuracy_ok: bool | None
    wifi_matched: bool | None
    timestamp_plausible: bool
    device_active: bool
    distance_m: float | None
    wifi_verified: bool
    official_at: datetime
    official_source: str  # server_received | device_detected
    notes: list[str] = field(default_factory=list)

    def as_json(self) -> dict[str, Any]:
        d = asdict(self)
        d["official_at"] = self.official_at.isoformat()
        return d


def _wifi_match(workplace: Workplace, ssid: str | None, bssid: str | None) -> bool:
    if bssid and workplace.wifi_bssids:
        if bssid.strip().lower() in {str(b).lower() for b in workplace.wifi_bssids}:
            return True
    if ssid and workplace.wifi_ssid:
        return ssid.strip().lower() == workplace.wifi_ssid.strip().lower()
    return False


def verify_event(policy: AttendancePolicy, workplace: Workplace, device: Device | None, data: EventIn, received_at: datetime) -> VerificationResult:
    notes: list[str] = []
    hard_reject: str | None = None
    review_reasons: list[str] = []

    # --- timestamp plausibility -------------------------------------------------
    detected = data.detected_at
    skew = (received_at - detected).total_seconds()  # positive when detected in the past
    timestamp_plausible = True
    if skew < -policy.max_future_skew_seconds:
        timestamp_plausible = False
        hard_reject = R_TIMESTAMP_IMPLAUSIBLE
        notes.append(f"detected_at is {int(-skew)}s in the future (limit {policy.max_future_skew_seconds}s)")
    elif skew > policy.max_offline_age_hours * 3600:
        timestamp_plausible = False
        hard_reject = R_STALE_OFFLINE
        notes.append(f"detected_at is older than {policy.max_offline_age_hours}h")
    elif not data.offline_queued and abs(skew) > policy.online_skew_tolerance_seconds:
        timestamp_plausible = False
        review_reasons.append(R_CLOCK_SKEW)
        notes.append(f"online submission but device clock differs from server by {int(abs(skew))}s")

    # Official time: server receipt time for online + plausible detections, else device time.
    if policy.official_time_source == "server_received" and not data.offline_queued and timestamp_plausible:
        official_at, official_source = received_at, "server_received"
    else:
        official_at, official_source = detected, "device_detected"
        if data.offline_queued and hard_reject is None:
            review_reasons.append(R_OFFLINE)
            notes.append("offline-queued submission uses device time; needs review")

    # --- geofence ------------------------------------------------------------------
    is_departure = data.event_type == "departure"
    inside: bool | None = None
    location_consistent = False
    accuracy_ok: bool | None = None
    distance: float | None = None
    loc = data.location
    if not workplace.coordinates_confirmed or not valid_coordinates(workplace.latitude, workplace.longitude):
        notes.append("workplace coordinates not confirmed; geofence cannot be verified")
        if loc is None:
            review_reasons.append(R_WORKPLACE_UNCONFIRMED)
    elif loc is None:
        inside = False
        if data.source in ("geofence", "manual"):
            review_reasons.append(R_NO_LOCATION)
            notes.append("no location supplied")
    else:
        distance = haversine_m(loc.latitude, loc.longitude, workplace.latitude, workplace.longitude)
        accuracy = loc.accuracy_m if loc.accuracy_m is not None else workplace.accuracy_threshold_m
        accuracy_ok = accuracy <= workplace.accuracy_threshold_m
        inside = distance <= workplace.radius_m + accuracy
        where = f"{distance:.0f} m from workplace (radius {workplace.radius_m} m + accuracy {accuracy:.0f} m)"
        if is_departure:
            # A departure is evidenced by being OUTSIDE the geofence. A geofence exit that still
            # reports a position inside the fence is contradictory (GPS drift, phone left at work).
            # A manual "clock out now" from inside the building is normal and is not penalised.
            location_consistent = (not inside) or data.source == "manual"
            if not location_consistent:
                review_reasons.append(R_DEPARTURE_STILL_INSIDE)
                notes.append(f"departure reported while still inside the geofence: {where}")
        else:
            location_consistent = inside
            if not inside:
                review_reasons.append(R_OUTSIDE_GEOFENCE)
                notes.append(where)
        if not accuracy_ok:
            notes.append(f"location accuracy {accuracy:.0f} m exceeds threshold {workplace.accuracy_threshold_m} m")

    # --- Wi-Fi ------------------------------------------------------------------------
    wifi_matched: bool | None = None
    wifi_verified = False
    if workplace.wifi_verification_enabled and is_departure:
        # Someone who has left is not expected to be on the office network; Wi-Fi is an
        # arrival signal only. A departure still on the office Wi-Fi is noted for reviewers.
        if data.wifi and _wifi_match(workplace, data.wifi.ssid, data.wifi.bssid):
            notes.append("departure reported while still connected to the workplace Wi-Fi")
    elif workplace.wifi_verification_enabled:
        wifi_matched = _wifi_match(workplace, data.wifi.ssid if data.wifi else None, data.wifi.bssid if data.wifi else None)
        wifi_verified = wifi_matched
        if workplace.wifi_required and not wifi_matched:
            review_reasons.append(R_WIFI_REQUIRED)
            notes.append("workplace requires a Wi-Fi match")
        if wifi_matched and inside is None:
            # Wi-Fi proves presence even when the geofence cannot be evaluated.
            review_reasons = [r for r in review_reasons if r != R_WORKPLACE_UNCONFIRMED]
    elif data.wifi and (data.wifi.ssid or data.wifi.bssid):
        notes.append("Wi-Fi signal supplied but Wi-Fi verification is disabled for this workplace")

    # --- device --------------------------------------------------------------------
    device_active = device is not None and device.status == "active"
    if not device_active:
        review_reasons.append(R_DEVICE_INACTIVE)
        notes.append("submitted without an active registered device")
    if policy.require_integrity_verdict and (device is None or device.integrity_verdict != "meets_device_integrity"):
        review_reasons.append(R_INTEGRITY)
        notes.append("device integrity verdict required but not met")

    if data.source == "manual":
        notes.append("manually initiated by the employee")

    # --- score ----------------------------------------------------------------------
    components: list[tuple[float, float]] = []  # (weight, value)
    if inside is not None:
        components.append((0.45, 1.0 if location_consistent else 0.0))
        components.append((0.15, 1.0 if accuracy_ok else 0.4))
    if wifi_matched is not None:
        components.append((0.25, 1.0 if wifi_matched else 0.0))
    components.append((0.10, 1.0 if timestamp_plausible else 0.0))
    components.append((0.10, 1.0 if device_active else 0.0))
    total_w = sum(w for w, _ in components)
    score = round(sum(w * v for w, v in components) / total_w, 3) if total_w else 0.0
    if inside is None and wifi_matched is None:
        score = min(score, 0.5)  # nothing proves presence

    if hard_reject:
        decision, reason = "reject", hard_reject
    elif review_reasons:
        decision, reason = "review", review_reasons[0]
    elif score < policy.min_verification_score_for_auto_confirm:
        decision, reason = "review", R_LOW_SCORE
        notes.append(f"score {score} below auto-confirm threshold {policy.min_verification_score_for_auto_confirm}")
    else:
        decision, reason = "auto", None

    return VerificationResult(
        score=score,
        decision=decision,
        reason=reason,
        inside_geofence=inside,
        accuracy_ok=accuracy_ok,
        wifi_matched=wifi_matched,
        timestamp_plausible=timestamp_plausible,
        device_active=device_active,
        distance_m=distance,
        wifi_verified=wifi_verified,
        official_at=official_at,
        official_source=official_source,
        notes=notes,
    )


def expires_at_for(policy: AttendancePolicy, received_at: datetime) -> datetime:
    return received_at + timedelta(hours=policy.pending_expiry_hours)

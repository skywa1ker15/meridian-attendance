"""String enumerations stored as VARCHAR with CHECK constraints (easy to evolve)."""
from __future__ import annotations

ROLES = ("employee", "manager", "admin", "superadmin")
USER_STATUSES = ("active", "inactive")
DEVICE_STATUSES = ("active", "pending_approval", "revoked")
EVENT_TYPES = ("arrival", "departure")
EVENT_SOURCES = ("geofence", "wifi", "manual", "admin")
EVENT_STATUSES = ("pending", "confirmed", "under_review", "rejected", "expired", "dismissed")
GEOFENCE_TRANSITIONS = ("enter", "dwell", "exit", "none")
SESSION_STATUSES = ("open", "closed", "missing_clock_out")
REQUEST_TYPES = ("missed_clock_in", "missed_clock_out", "full_day", "correction", "profile_correction")
REQUEST_STATUSES = ("pending", "approved", "rejected", "cancelled")
NOTIFICATION_TYPES = (
    "arrival_detected",
    "departure_detected",
    "clock_in_confirmation_required",
    "clock_out_confirmation_required",
    "attendance_recorded",
    "attendance_submission_failed",
    "manual_request_reviewed",
    "correction_approved",
    "correction_rejected",
    "device_revoked",
    "system",
)
TODAY_STATUSES = ("not_started", "pending_arrival", "clocked_in", "pending_departure", "clocked_out", "missing_clock_out")
INTEGRITY_VERDICTS = ("unknown", "meets_device_integrity", "failed", "not_configured")
PROFILE_CORRECTION_FIELDS = ("first_name", "last_name", "email", "phone", "department")

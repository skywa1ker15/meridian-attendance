"""Authentication: login with lockout, refresh-token rotation with reuse detection,
password change/reset, logout."""
from __future__ import annotations

import logging
import uuid
from datetime import timedelta
from typing import Any

from sqlalchemy import func, select, update
from sqlalchemy.orm import Session
from starlette.requests import Request

from app.core.config import get_settings
from app.core.errors import ApiError
from app.core.middleware import client_ip
from app.core.security import (
    create_access_token,
    generate_opaque_token,
    hash_password,
    hash_token,
    needs_rehash,
    validate_password_policy,
    verify_password,
)
from app.core.timeutil import now_utc
from app.models import Device, PasswordResetToken, RefreshToken, User
from app.schemas.auth import DeviceIn, device_dict, user_dict
from app.services import audit
from app.services import devices as device_service
from app.services import email as email_service

log = logging.getLogger("meridian.auth")


def find_user_by_identifier(db: Session, identifier: str) -> User | None:
    ident = identifier.strip()
    if not ident:
        return None
    stmt = select(User).where((func.lower(User.email) == ident.lower()) | (User.employee_number == ident))
    return db.execute(stmt).scalars().first()


def _issue_tokens(db: Session, user: User, device: Device | None, request: Request | None, family_id: uuid.UUID | None = None) -> dict[str, Any]:
    settings = get_settings()
    now = now_utc()
    access, expires_in = create_access_token(user_id=user.id, role=user.role, token_version=user.token_version, device_id=device.id if device else None, settings=settings, now=now)
    raw_refresh = generate_opaque_token()
    token = RefreshToken(
        user_id=user.id,
        device_id=device.id if device else None,
        token_hash=hash_token(raw_refresh),
        family_id=family_id or uuid.uuid4(),
        issued_at=now,
        expires_at=now + timedelta(days=settings.refresh_token_days),
        client_ip=client_ip(request, settings) if request else None,
        user_agent=(request.headers.get("user-agent", "")[:256] if request else None),
    )
    db.add(token)
    db.flush()
    return {
        "access_token": access,
        "refresh_token": raw_refresh,
        "token_type": "bearer",
        "expires_in": expires_in,
        "must_change_password": user.must_change_password,
        "user": user_dict(user),
        "device": device_dict(device),
        "_refresh_token_row": token,
    }


def login(db: Session, identifier: str, password: str, device_in: DeviceIn | None, request: Request | None) -> dict[str, Any]:
    settings = get_settings()
    now = now_utc()
    user = find_user_by_identifier(db, identifier)
    if user is None or user.erased_at is not None:
        verify_password(password, None)  # constant-time-ish
        audit.record(db, action="auth.login_failed", actor=None, entity_type="user", entity_id=None, after={"identifier": identifier[:64], "reason": "unknown_user"}, request=request)
        db.commit()
        raise ApiError(401, "invalid_credentials", "Email/employee number or password is incorrect.")

    if user.locked_until and user.locked_until > now:
        retry = int((user.locked_until - now).total_seconds()) + 1
        raise ApiError(423, "account_locked", "Too many failed attempts. Try again later.", {"retry_after": retry}, headers={"Retry-After": str(retry)})

    if user.password_hash is None:
        # The account was created by invitation and has never been activated. Telling the
        # caller so does reveal that the identifier exists — but they had to know the exact
        # email or employee number to get here, which the bad-password path already confirms
        # only by timing. The alternative (a generic "wrong password" for an account that has
        # no password) sends employees to the helpdesk, so the trade-off is deliberate.
        audit.record(db, action="auth.login_failed", actor=None, entity_type="user", entity_id=user.id, after={"reason": "invitation_pending"}, request=request)
        db.commit()
        raise ApiError(401, "invitation_pending", "Your account hasn't been set up yet. Open the invitation link we emailed you, or ask your manager to resend it.")

    if not verify_password(password, user.password_hash):
        user.failed_login_attempts = (user.failed_login_attempts or 0) + 1
        reason = "bad_password"
        if user.failed_login_attempts >= settings.login_max_failed_attempts:
            user.locked_until = now + timedelta(minutes=settings.login_lockout_minutes)
            user.failed_login_attempts = 0
            reason = "locked"
        audit.record(db, action="auth.login_failed", actor=None, entity_type="user", entity_id=user.id, after={"reason": reason}, request=request)
        db.commit()
        if reason == "locked":
            raise ApiError(423, "account_locked", "Too many failed attempts. Try again later.", {"retry_after": settings.login_lockout_minutes * 60})
        raise ApiError(401, "invalid_credentials", "Email/employee number or password is incorrect.")

    if user.status != "active":
        audit.record(db, action="auth.login_failed", actor=None, entity_type="user", entity_id=user.id, after={"reason": "inactive"}, request=request)
        db.commit()
        raise ApiError(403, "account_inactive", "This account has been deactivated. Contact your administrator.")

    if needs_rehash(user.password_hash or ""):
        user.password_hash = hash_password(password)

    user.failed_login_attempts = 0
    user.locked_until = None
    user.last_login_at = now

    device: Device | None = None
    if device_in is not None:
        device = device_service.register_device(db, user, device_in, request=request)
        if device.status == "revoked":
            db.commit()
            raise ApiError(403, "device_revoked", "This device has been revoked. Contact your administrator.")
        device.last_seen_at = now

    result = _issue_tokens(db, user, device, request)
    audit.record(db, action="auth.login", actor=user, entity_type="user", entity_id=user.id, after={"device_id": str(device.id) if device else None}, request=request)
    db.commit()
    result.pop("_refresh_token_row", None)
    return result


def refresh(db: Session, raw_refresh: str, request: Request | None) -> dict[str, Any]:
    now = now_utc()
    token_hash = hash_token(raw_refresh)
    row = db.execute(select(RefreshToken).where(RefreshToken.token_hash == token_hash).with_for_update()).scalar_one_or_none()
    if row is None:
        raise ApiError(401, "token_invalid", "Refresh token is invalid.")
    if row.revoked_at is not None or row.used_at is not None:
        # Reuse of a rotated/revoked token: revoke the whole family (theft detection).
        db.execute(update(RefreshToken).where(RefreshToken.family_id == row.family_id, RefreshToken.revoked_at.is_(None)).values(revoked_at=now, revoke_reason="reuse_detected"))
        audit.record(db, action="auth.refresh_reuse_detected", actor=None, entity_type="user", entity_id=row.user_id, after={"family_id": str(row.family_id)}, request=request, actor_type="anonymous")
        db.commit()
        raise ApiError(401, "token_reused", "Refresh token was already used. Please sign in again.")
    if row.expires_at < now:
        row.revoked_at = now
        row.revoke_reason = "expired"
        db.commit()
        raise ApiError(401, "token_expired", "Refresh token has expired. Please sign in again.")

    user = db.get(User, row.user_id)
    if user is None or user.status != "active" or user.erased_at is not None:
        row.revoked_at = now
        row.revoke_reason = "user_inactive"
        db.commit()
        raise ApiError(403, "account_inactive", "This account is not active.")

    device = db.get(Device, row.device_id) if row.device_id else None
    if device is not None:
        if device.status == "revoked":
            row.revoked_at = now
            row.revoke_reason = "device_revoked"
            db.commit()
            raise ApiError(403, "device_revoked", "This device has been revoked. Contact your administrator.")
        device.last_seen_at = now

    row.used_at = now
    result = _issue_tokens(db, user, device, request, family_id=row.family_id)
    row.replaced_by = result["_refresh_token_row"].id
    db.commit()
    result.pop("_refresh_token_row", None)
    return result


def logout(db: Session, raw_refresh: str, user: User | None, request: Request | None) -> None:
    now = now_utc()
    row = db.execute(select(RefreshToken).where(RefreshToken.token_hash == hash_token(raw_refresh))).scalar_one_or_none()
    if row is not None and row.revoked_at is None:
        db.execute(update(RefreshToken).where(RefreshToken.family_id == row.family_id, RefreshToken.revoked_at.is_(None)).values(revoked_at=now, revoke_reason="logout"))
        audit.record(db, action="auth.logout", actor=user, entity_type="user", entity_id=row.user_id, request=request)
    db.commit()


def revoke_all_user_tokens(db: Session, user: User, reason: str) -> None:
    now = now_utc()
    db.execute(update(RefreshToken).where(RefreshToken.user_id == user.id, RefreshToken.revoked_at.is_(None)).values(revoked_at=now, revoke_reason=reason))
    user.token_version = (user.token_version or 1) + 1  # invalidates outstanding access tokens


def change_password(db: Session, user: User, current_password: str, new_password: str, request: Request | None) -> None:
    if not verify_password(current_password, user.password_hash):
        raise ApiError(401, "invalid_credentials", "Current password is incorrect.")
    validate_password_policy(new_password, [user.email, user.employee_number, user.first_name, user.last_name])
    if verify_password(new_password, user.password_hash):
        raise ApiError(422, "validation_error", "New password must differ from the current password.", {"fields": {"new_password": "same_as_current"}})
    user.password_hash = hash_password(new_password)
    user.must_change_password = False
    revoke_all_user_tokens(db, user, "password_changed")
    audit.record(db, action="auth.password_changed", actor=user, entity_type="user", entity_id=user.id, request=request)
    db.commit()


def forgot_password(db: Session, identifier: str, request: Request | None) -> None:
    """Always succeeds (anti-enumeration). Sends a reset link when SMTP is configured."""
    settings = get_settings()
    user = find_user_by_identifier(db, identifier)
    if user is None or user.status != "active" or user.erased_at is not None:
        return
    raw = generate_opaque_token(32)
    db.add(PasswordResetToken(user_id=user.id, token_hash=hash_token(raw), expires_at=now_utc() + timedelta(minutes=settings.password_reset_minutes)))
    audit.record(db, action="auth.password_reset_requested", actor=None, entity_type="user", entity_id=user.id, request=request)
    db.commit()
    link = settings.password_reset_url_template.format(public_base_url=settings.public_base_url.rstrip("/"), token=raw)
    subject, text_body, html_body = email_service.password_reset_message(first_name=user.first_name, link=link, expiry_minutes=settings.password_reset_minutes)
    email_service.send_email(to=user.email, subject=subject, text_body=text_body, html_body=html_body)


def reset_password(db: Session, raw_token: str, new_password: str, request: Request | None) -> None:
    now = now_utc()
    row = db.execute(select(PasswordResetToken).where(PasswordResetToken.token_hash == hash_token(raw_token))).scalar_one_or_none()
    if row is None or row.used_at is not None or row.expires_at < now:
        raise ApiError(400, "token_invalid", "This reset link is invalid or has expired.")
    user = db.get(User, row.user_id)
    if user is None or user.status != "active":
        raise ApiError(400, "token_invalid", "This reset link is invalid or has expired.")
    validate_password_policy(new_password, [user.email, user.employee_number, user.first_name, user.last_name])
    user.password_hash = hash_password(new_password)
    user.must_change_password = False
    user.failed_login_attempts = 0
    user.locked_until = None
    row.used_at = now
    revoke_all_user_tokens(db, user, "password_reset")
    audit.record(db, action="auth.password_reset", actor=user, entity_type="user", entity_id=user.id, request=request)
    db.commit()

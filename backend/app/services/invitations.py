"""Employee invitations.

An administrator creates the account; the employee receives a one-time link by
email and chooses their own password and mobile number. Until that happens the
account has no password hash at all, so nobody can sign in as them.
"""
from __future__ import annotations

from datetime import datetime, timedelta
from typing import Any

from sqlalchemy import select, update
from sqlalchemy.orm import Session, selectinload
from starlette.requests import Request

from app.core.config import get_settings
from app.core.errors import ApiError
from app.core.security import generate_opaque_token, hash_password, hash_token, validate_password_policy
from app.core.timeutil import now_utc, utc_iso
from app.core.validation import normalise_sa_phone
from app.models import InvitationToken, User
from app.schemas.auth import DeviceIn, department_dict
from app.services import audit, auth_service
from app.services import email as email_service

TOKEN_INVALID = "This invitation link is invalid, has expired or has already been used."


def invalidate_outstanding(db: Session, user: User) -> None:
    """Mark every unused invitation for ``user`` as spent."""
    db.execute(
        update(InvitationToken)
        .where(InvitationToken.user_id == user.id, InvitationToken.used_at.is_(None))
        .values(used_at=now_utc())
    )


def issue(db: Session, user: User, actor: User | None, request: Request | None, *, audit_action: str = "employee.invited") -> tuple[bool, datetime, str | None]:
    """Create (and email) a fresh invitation, invalidating any earlier unused one.

    Returns ``(sent, expires_at, link)``. ``sent`` is False when no email provider is
    configured or the provider refused the message; in that case ``link`` carries the
    invitation URL so the administrator who made the request can pass it on another way
    (read out in person, company chat). Without it an invited employee would be stranded:
    they have no password and no email. When the message was sent, ``link`` is None —
    the employee has it, and it does not belong in an API response or a log.
    """
    settings = get_settings()
    invalidate_outstanding(db, user)
    raw = generate_opaque_token(32)
    expires_at = now_utc() + timedelta(days=settings.invitation_expiry_days)
    db.add(
        InvitationToken(
            user_id=user.id,
            token_hash=hash_token(raw),
            created_by_id=actor.id if actor else None,
            expires_at=expires_at,
        )
    )
    audit.record(db, action=audit_action, actor=actor, entity_type="user", entity_id=user.id, after={"expires_at": utc_iso(expires_at)}, request=request)
    db.commit()

    link = settings.invitation_url_template.format(public_base_url=settings.public_base_url.rstrip("/"), token=raw)
    subject, text_body, html_body = email_service.invitation_message(first_name=user.first_name, link=link, expiry_days=settings.invitation_expiry_days)
    sent = email_service.send_email(to=user.email, subject=subject, text_body=text_body, html_body=html_body)
    return sent, expires_at, (None if sent else link)


def _load(db: Session, raw_token: str) -> tuple[InvitationToken, User]:
    row = db.execute(select(InvitationToken).where(InvitationToken.token_hash == hash_token(raw_token))).scalar_one_or_none()
    if row is None or row.used_at is not None or row.expires_at < now_utc():
        raise ApiError(400, "token_invalid", TOKEN_INVALID)
    user = db.execute(select(User).options(selectinload(User.department)).where(User.id == row.user_id)).scalar_one_or_none()
    if user is None or user.status != "active" or user.erased_at is not None:
        raise ApiError(400, "token_invalid", TOKEN_INVALID)
    return row, user


def details(db: Session, raw_token: str) -> dict[str, Any]:
    """What the app shows on the activation screen. Nothing is revealed for a bad token."""
    row, user = _load(db, raw_token)
    dep = department_dict(user.department)
    return {
        "employee_number": user.employee_number,
        "first_name": user.first_name,
        "last_name": user.last_name,
        "email": user.email,
        "phone": user.phone,
        "department": {"id": dep["id"], "name": dep["name"]} if dep else None,
        "expires_at": utc_iso(row.expires_at),
    }


def accept(db: Session, raw_token: str, phone: str, password: str, device_in: DeviceIn | None, request: Request | None) -> dict[str, Any]:
    row, user = _load(db, raw_token)
    normalised = normalise_sa_phone(phone)
    if not normalised:
        raise ApiError(422, "validation_error", "A mobile number is required.", {"fields": {"phone": "required"}})
    validate_password_policy(password, [user.email, user.employee_number, user.first_name, user.last_name])

    user.password_hash = hash_password(password)
    user.phone = normalised
    user.must_change_password = False
    user.failed_login_attempts = 0
    user.locked_until = None
    row.used_at = now_utc()
    invalidate_outstanding(db, user)
    audit.record(db, action="auth.invitation_accepted", actor=user, entity_type="user", entity_id=user.id, after={"phone": normalised}, request=request)
    db.commit()

    # Same body as POST /auth/login, by construction: the app can reuse its login handling.
    return auth_service.login(db, user.employee_number, password, device_in, request)

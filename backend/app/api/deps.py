"""FastAPI dependencies: database session, current user (JWT), permissions."""
from __future__ import annotations

import uuid
from collections.abc import Callable, Generator
from datetime import timedelta

from fastapi import Depends, Query, Request
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from sqlalchemy import select
from sqlalchemy.orm import Session, selectinload

from app.core import timeutil
from app.core.errors import ApiError
from app.core.permissions import has_permission
from app.core.security import decode_access_token
from app.db.session import db_session
from app.models import Device, User
from app.schemas.common import PageParams

_bearer = HTTPBearer(auto_error=False)

# Paths an authenticated user may call even while a password change is required.
_PASSWORD_CHANGE_ALLOWED = ("/api/v1/auth/password/change", "/api/v1/auth/me", "/api/v1/auth/logout", "/api/v1/auth/refresh")


def get_db() -> Generator[Session, None, None]:
    yield from db_session()


def get_current_user(request: Request, db: Session = Depends(get_db), creds: HTTPAuthorizationCredentials | None = Depends(_bearer)) -> User:
    if creds is None or creds.scheme.lower() != "bearer" or not creds.credentials:
        raise ApiError(401, "token_invalid", "Authentication required.")
    payload = decode_access_token(creds.credentials)
    try:
        user_id = uuid.UUID(str(payload.get("sub")))
    except ValueError as exc:
        raise ApiError(401, "token_invalid", "Access token is invalid.") from exc
    user = db.execute(select(User).options(selectinload(User.department)).where(User.id == user_id)).scalar_one_or_none()
    if user is None or user.erased_at is not None:
        raise ApiError(401, "token_invalid", "Access token is invalid.")
    if int(payload.get("ver", -1)) != int(user.token_version):
        raise ApiError(401, "token_invalid", "Session is no longer valid. Please sign in again.")
    if user.status != "active":
        raise ApiError(403, "account_inactive", "This account has been deactivated.")
    device: Device | None = None
    did = payload.get("did")
    if did:
        try:
            device = db.get(Device, uuid.UUID(str(did)))
        except ValueError:
            device = None
        if device is None or device.user_id != user.id:
            raise ApiError(401, "token_invalid", "Access token is invalid.")
        if device.status == "revoked":
            raise ApiError(403, "device_revoked", "This device has been revoked. Contact your administrator.")
        now = timeutil.now_utc()
        if device.last_seen_at is None or now - device.last_seen_at > timedelta(minutes=1):
            device.last_seen_at = now
            db.commit()
    if user.must_change_password and not request.url.path.startswith(_PASSWORD_CHANGE_ALLOWED):
        raise ApiError(403, "password_change_required", "You must change your password before continuing.")
    request.state.user = user
    request.state.device = device
    return user


def get_current_device(request: Request, user: User = Depends(get_current_user)) -> Device | None:
    return getattr(request.state, "device", None)


def require_permission(*permissions: str) -> Callable[..., User]:
    """Dependency factory: the user must hold every listed permission (server-side role)."""

    def _dep(user: User = Depends(get_current_user)) -> User:
        for p in permissions:
            if not has_permission(user.role, p):
                raise ApiError(403, "forbidden", "You do not have permission to perform this action.", {"required": list(permissions)})
        return user

    return _dep


def page_params(page: int = Query(1, ge=1), page_size: int = Query(50, ge=1, le=200)) -> PageParams:
    return PageParams(page=page, page_size=page_size)


def parse_uuid_or_404(value: str | None, what: str = "Resource") -> uuid.UUID | None:
    if value is None or value == "":
        return None
    try:
        return uuid.UUID(str(value))
    except ValueError as exc:
        raise ApiError(404, "not_found", f"{what} not found.") from exc


def parse_uuid_filter(value: str | None) -> uuid.UUID | None:
    """For list filters: an unparsable id simply matches nothing (returns a nil UUID)."""
    if value is None or value == "":
        return None
    try:
        return uuid.UUID(str(value))
    except ValueError:
        return uuid.UUID(int=0)

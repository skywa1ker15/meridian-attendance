"""/setup — first-run bootstrap so the Android app can stand alone.

On a brand-new installation there is no administrator, so nobody can sign in to manage the
system. These endpoints let the first administrator be created from the app itself:

* ``GET  /setup/status``       → ``{"needs_first_admin": bool, "setup_code_required": bool}``
* ``POST /setup/first-admin``  → creates a superadmin and signs them in (same body as login's response)

Safety:
* Permanently closed (409 ``setup_complete``) as soon as any admin/superadmin exists.
* In staging/production it only works when ``FIRST_ADMIN_SETUP_CODE`` is configured on the server,
  and the caller must supply that code (constant-time compare). This stops a stranger who finds an
  empty server from claiming it.
* Creation runs under a transaction-level advisory lock so two simultaneous requests cannot both win.
* Shares the login rate-limit bucket and is audit-logged.
"""
from __future__ import annotations

import hmac
import uuid

from fastapi import APIRouter, Depends, Request
from pydantic import EmailStr, Field
from sqlalchemy import func, select, text
from sqlalchemy.orm import Session

from app.api.deps import get_db
from app.core.config import get_settings
from app.core.errors import ApiError
from app.core.security import hash_password, validate_password_policy
from app.models import User
from app.schemas.auth import DeviceIn
from app.schemas.common import ApiModel
from app.services import audit, auth_service

router = APIRouter(prefix="/setup", tags=["setup"])

_ADMIN_ROLES = ("admin", "superadmin")
_SETUP_LOCK_KEY = 0x4D45524944  # "MERID"


class FirstAdminIn(ApiModel):
    setup_code: str | None = Field(None, max_length=200)
    employee_number: str = Field(..., min_length=1, max_length=32, pattern=r"^[A-Za-z0-9][A-Za-z0-9._-]*$")
    first_name: str = Field(..., min_length=1, max_length=100)
    last_name: str = Field(..., min_length=1, max_length=100)
    email: EmailStr
    password: str = Field(..., min_length=1, max_length=256)
    device: DeviceIn | None = None


def _admin_exists(db: Session) -> bool:
    return (db.scalar(select(func.count()).select_from(User).where(User.role.in_(_ADMIN_ROLES))) or 0) > 0


def _code_required() -> bool:
    s = get_settings()
    return bool(s.first_admin_setup_code) or s.app_env in ("staging", "production")


@router.get("/status")
def status(db: Session = Depends(get_db)):
    return {"needs_first_admin": not _admin_exists(db), "setup_code_required": _code_required()}


@router.post("/first-admin", status_code=201)
def first_admin(body: FirstAdminIn, request: Request, db: Session = Depends(get_db)):
    settings = get_settings()
    db.execute(text("SELECT pg_advisory_xact_lock(:k)"), {"k": _SETUP_LOCK_KEY})
    if _admin_exists(db):
        raise ApiError(409, "setup_complete", "This system already has an administrator. Sign in instead.")
    if _code_required():
        configured = settings.first_admin_setup_code
        if not configured:
            raise ApiError(403, "setup_disabled", "First-time setup is not enabled on this server. Ask your IT team to set FIRST_ADMIN_SETUP_CODE.")
        if not body.setup_code or not hmac.compare_digest(body.setup_code.strip().encode(), configured.strip().encode()):
            audit.record(db, action="setup.first_admin_failed", actor=None, entity_type="user", entity_id=None, after={"reason": "bad_setup_code"}, request=request)
            db.commit()
            raise ApiError(403, "invalid_setup_code", "The setup code is not correct.")

    email = str(body.email).lower()
    number = body.employee_number.strip().upper()
    clash = db.scalar(select(User).where((func.lower(User.email) == email) | (func.upper(User.employee_number) == number)))
    if clash is not None:
        raise ApiError(409, "conflict", "That email or employee number is already in use.")
    validate_password_policy(body.password, [email, number, body.first_name, body.last_name])

    user = User(
        id=uuid.uuid4(), employee_number=number, email=email, first_name=body.first_name.strip(), last_name=body.last_name.strip(),
        role="superadmin", status="active", password_hash=hash_password(body.password), must_change_password=False,
    )
    db.add(user)
    db.flush()
    audit.record(db, action="setup.first_admin_created", actor=user, entity_type="user", entity_id=user.id, after={"employee_number": number, "email": email, "role": "superadmin"}, request=request)
    db.commit()
    return auth_service.login(db, email, body.password, body.device, request)

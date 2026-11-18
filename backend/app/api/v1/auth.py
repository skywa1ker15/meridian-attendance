"""/auth — login, refresh, logout, profile, password management."""
from __future__ import annotations

from fastapi import APIRouter, Depends, Query, Request, Response
from sqlalchemy.orm import Session

from app.api.deps import get_current_device, get_current_user, get_db
from app.core.validation import normalise_sa_phone
from app.models import Device, User
from app.schemas.auth import (
    ChangePasswordIn,
    ForgotPasswordIn,
    InvitationAcceptIn,
    LoginIn,
    LogoutIn,
    RefreshIn,
    ResetPasswordIn,
    UpdateMeIn,
    device_dict,
    user_dict,
)
from app.services import audit, auth_service, invitations

router = APIRouter(prefix="/auth", tags=["auth"])


@router.post("/login")
def login(body: LoginIn, request: Request, db: Session = Depends(get_db)):
    return auth_service.login(db, body.identifier, body.password, body.device, request)


@router.get("/invitation")
def invitation_details(token: str = Query(..., min_length=16, max_length=512), db: Session = Depends(get_db)):
    """What the invited employee sees before activating. Reveals nothing for a bad token."""
    return invitations.details(db, token)


@router.post("/invitation/accept", status_code=201)
def accept_invitation(body: InvitationAcceptIn, request: Request, db: Session = Depends(get_db)):
    return invitations.accept(db, body.token, body.phone, body.password, body.device, request)


@router.post("/refresh")
def refresh(body: RefreshIn, request: Request, db: Session = Depends(get_db)):
    return auth_service.refresh(db, body.refresh_token, request)


@router.post("/logout", status_code=204, response_class=Response)
def logout(body: LogoutIn, request: Request, db: Session = Depends(get_db)):
    # Logout works with just the refresh token (the access token may already be expired).
    auth_service.logout(db, body.refresh_token, None, request)
    return Response(status_code=204)


@router.get("/me")
def me(user: User = Depends(get_current_user), device: Device | None = Depends(get_current_device)):
    return {"user": user_dict(user), "device": device_dict(device)}


@router.patch("/me")
def update_me(body: UpdateMeIn, request: Request, user: User = Depends(get_current_user), db: Session = Depends(get_db)):
    changes = body.model_dump(exclude_unset=True)
    before = {"phone": user.phone, "notification_push": user.notification_push}
    if "phone" in changes:
        user.phone = normalise_sa_phone(changes["phone"])
    if body.notification_preferences is not None and body.notification_preferences.push is not None:
        user.notification_push = body.notification_preferences.push
    audit.record(db, action="profile.update", actor=user, entity_type="user", entity_id=user.id, before=before, after={"phone": user.phone, "notification_push": user.notification_push}, request=request)
    db.commit()
    db.refresh(user)
    return user_dict(user)


@router.post("/password/change", status_code=204, response_class=Response)
def change_password(body: ChangePasswordIn, request: Request, user: User = Depends(get_current_user), db: Session = Depends(get_db)):
    auth_service.change_password(db, user, body.current_password, body.new_password, request)
    return Response(status_code=204)


@router.post("/password/forgot", status_code=202)
def forgot_password(body: ForgotPasswordIn, request: Request, db: Session = Depends(get_db)):
    auth_service.forgot_password(db, body.identifier, request)
    return {"status": "accepted", "message": "If the account exists, password reset instructions have been sent."}


@router.post("/password/reset", status_code=204, response_class=Response)
def reset_password(body: ResetPasswordIn, request: Request, db: Session = Depends(get_db)):
    auth_service.reset_password(db, body.token, body.new_password, request)
    return Response(status_code=204)

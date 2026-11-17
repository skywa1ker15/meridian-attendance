"""/devices — registration and per-device settings for the mobile app."""
from __future__ import annotations

from fastapi import APIRouter, Depends, Request, Response
from sqlalchemy.orm import Session

from app.api.deps import get_current_device, get_current_user, get_db
from app.core.errors import ApiError
from app.models import Device, User
from app.schemas.auth import DeviceIn, FcmTokenIn, IntegrityIn, device_dict
from app.services import devices as device_service

router = APIRouter(prefix="/devices", tags=["devices"])


def _require_device(device: Device | None) -> Device:
    if device is None:
        raise ApiError(400, "invalid_state", "This token is not bound to a registered device.")
    return device


@router.post("/register")
def register(body: DeviceIn, request: Request, response: Response, user: User = Depends(get_current_user), db: Session = Depends(get_db)):
    device = device_service.register_and_commit(db, user, body, request)
    response.status_code = 201 if device.registered_at and (device.last_seen_at == device.registered_at) else 200
    return device_dict(device)


@router.get("/me")
def my_device(device: Device | None = Depends(get_current_device)):
    return device_dict(_require_device(device))


@router.post("/me/fcm-token", status_code=204, response_class=Response)
def fcm_token(body: FcmTokenIn, device: Device | None = Depends(get_current_device), db: Session = Depends(get_db)):
    device_service.set_fcm_token(db, _require_device(device), body.fcm_token)
    return Response(status_code=204)


@router.post("/me/integrity")
def integrity(body: IntegrityIn, device: Device | None = Depends(get_current_device), db: Session = Depends(get_db)):
    verdict = device_service.record_integrity(db, _require_device(device), body.integrity_token)
    return {"verdict": verdict}

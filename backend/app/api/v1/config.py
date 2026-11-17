"""/config — read-only configuration for the mobile app."""
from __future__ import annotations

from fastapi import APIRouter, Depends
from sqlalchemy.orm import Session

from app.api.deps import get_current_user, get_db
from app.models import User
from app.schemas.admin import policy_dict
from app.schemas.attendance import workplace_dict
from app.services import config_service

router = APIRouter(prefix="/config", tags=["config"])


@router.get("/workplaces")
def workplaces(_: User = Depends(get_current_user), db: Session = Depends(get_db)):
    items = config_service.list_workplaces(db, active_only=True)
    return {"items": [workplace_dict(w) for w in items], "config_version": config_service.config_version(db)}


@router.get("/policy")
def policy(_: User = Depends(get_current_user), db: Session = Depends(get_db)):
    return policy_dict(config_service.get_policy(db))

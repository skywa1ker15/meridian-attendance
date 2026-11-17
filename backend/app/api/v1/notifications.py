"""/notifications — in-app inbox."""
from __future__ import annotations

from fastapi import APIRouter, Depends, Query, Response
from sqlalchemy.orm import Session

from app.api.deps import get_current_user, get_db, page_params
from app.models import User
from app.schemas.attendance import notification_dict
from app.schemas.common import PageParams, page_dict
from app.services import notifications

router = APIRouter(prefix="/notifications", tags=["notifications"])


@router.get("")
def list_notifications(unread_only: bool = Query(False), params: PageParams = Depends(page_params), user: User = Depends(get_current_user), db: Session = Depends(get_db)):
    rows, total = notifications.list_notifications(db, user, unread_only, params)
    return page_dict([notification_dict(n) for n in rows], total, params)


@router.get("/unread-count")
def unread_count(user: User = Depends(get_current_user), db: Session = Depends(get_db)):
    return {"count": notifications.unread_count(db, user)}


@router.post("/read-all", status_code=204, response_class=Response)
def read_all(user: User = Depends(get_current_user), db: Session = Depends(get_db)):
    notifications.mark_all_read(db, user)
    return Response(status_code=204)


@router.post("/{notification_id}/read", status_code=204, response_class=Response)
def mark_read(notification_id: str, user: User = Depends(get_current_user), db: Session = Depends(get_db)):
    notifications.mark_read(db, user, notification_id)
    return Response(status_code=204)

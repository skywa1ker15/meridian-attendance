"""/attendance — employee-facing attendance endpoints."""
from __future__ import annotations

from datetime import date

from fastapi import APIRouter, Depends, Query, Request, Response
from sqlalchemy.orm import Session

from app.api.deps import get_current_device, get_current_user, get_db, page_params
from app.core.errors import ApiError
from app.models import Device, User
from app.schemas.attendance import (
    ConfirmIn,
    DismissIn,
    EventIn,
    ManualRequestIn,
    SyncIn,
    event_dict,
    request_dict,
    session_dict,
)
from app.schemas.common import PageParams, page_dict
from app.services import attendance, manual_requests, sync

router = APIRouter(prefix="/attendance", tags=["attendance"])


@router.post("/events")
def submit_event(body: EventIn, request: Request, response: Response, user: User = Depends(get_current_user), device: Device | None = Depends(get_current_device), db: Session = Depends(get_db)):
    event, status = attendance.submit_event(db, user, device, body, request)
    response.status_code = status
    return {"event": event_dict(event), "today": attendance.today_summary(db, user)}


@router.post("/events/{event_id}/confirm")
def confirm_event(event_id: str, body: ConfirmIn, request: Request, user: User = Depends(get_current_user), db: Session = Depends(get_db)):
    event, session, _ = attendance.confirm_event(db, user, event_id, body, request)
    return {"event": event_dict(event), "session": session_dict(session) if session else None, "today": attendance.today_summary(db, user)}


@router.post("/events/{event_id}/dismiss")
def dismiss_event(event_id: str, body: DismissIn, request: Request, user: User = Depends(get_current_user), db: Session = Depends(get_db)):
    return event_dict(attendance.dismiss_event(db, user, event_id, body, request))


@router.get("/pending")
def pending(user: User = Depends(get_current_user), db: Session = Depends(get_db)):
    return {"items": [event_dict(e) for e in attendance.pending_events(db, user)]}


@router.get("/today")
def today(user: User = Depends(get_current_user), db: Session = Depends(get_db)):
    return attendance.today_summary(db, user)


@router.get("/sessions")
def sessions(
    date_from: date | None = Query(None, alias="from"),
    date_to: date | None = Query(None, alias="to"),
    params: PageParams = Depends(page_params),
    user: User = Depends(get_current_user),
    db: Session = Depends(get_db),
):
    rows, total = attendance.list_sessions(db, params, user_id=user.id, date_from=date_from, date_to=date_to)
    return page_dict([session_dict(s) for s in rows], total, params)


@router.get("/sessions/{session_id}")
def session_detail(session_id: str, user: User = Depends(get_current_user), db: Session = Depends(get_db)):
    session = attendance.get_session(db, session_id)
    if session.user_id != user.id:
        raise ApiError(404, "not_found", "Attendance session not found.")
    return {"session": session_dict(session), "events": [event_dict(e) for e in attendance.session_events(db, session)]}


@router.get("/events")
def events(
    date_from: date | None = Query(None, alias="from"),
    date_to: date | None = Query(None, alias="to"),
    status: str | None = Query(None, max_length=120),
    params: PageParams = Depends(page_params),
    user: User = Depends(get_current_user),
    db: Session = Depends(get_db),
):
    rows, total = attendance.list_events(db, params, user_id=user.id, date_from=date_from, date_to=date_to, status=status)
    return page_dict([event_dict(e) for e in rows], total, params)


@router.post("/sync")
def sync_batch(body: SyncIn, request: Request, user: User = Depends(get_current_user), device: Device | None = Depends(get_current_device), db: Session = Depends(get_db)):
    results = sync.process_batch(db, user, device, body, request)
    return {"results": results, "today": attendance.today_summary(db, user)}


# --- manual requests -----------------------------------------------------------------
@router.post("/manual-requests")
def create_manual_request(body: ManualRequestIn, request: Request, response: Response, user: User = Depends(get_current_user), db: Session = Depends(get_db)):
    req, created = manual_requests.create_request(db, user, body, request)
    response.status_code = 201 if created else 200
    return request_dict(req)


@router.get("/manual-requests")
def list_manual_requests(status: str | None = Query(None, max_length=20), params: PageParams = Depends(page_params), user: User = Depends(get_current_user), db: Session = Depends(get_db)):
    rows, total = manual_requests.list_requests(db, params, user_id=user.id, status=status)
    return page_dict([request_dict(r) for r in rows], total, params)


@router.post("/manual-requests/{request_id}/cancel")
def cancel_manual_request(request_id: str, user: User = Depends(get_current_user), db: Session = Depends(get_db)):
    return request_dict(manual_requests.cancel_request(db, user, request_id))

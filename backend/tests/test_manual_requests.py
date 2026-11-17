from __future__ import annotations

import uuid
from datetime import date

from sqlalchemy import select

from app.models import AttendanceSession, AuditLog
from tests.conftest import auth, make_department

FRIDAY = date(2026, 11, 13)


def req_body(**kw):
    body = {"client_request_id": str(uuid.uuid4()), "type": "full_day", "work_date": FRIDAY.isoformat(), "requested_clock_in": "2026-11-13T06:00:00Z", "requested_clock_out": "2026-11-13T15:05:00Z", "reason": "Phone battery died"}
    body.update(kw)
    return body


def test_create_list_cancel(client, emp_tokens, clock):
    clock.local(date(2026, 11, 16), "09:00")
    h = auth(emp_tokens)
    body = req_body()
    r = client.post("/api/v1/attendance/manual-requests", json=body, headers=h)
    assert r.status_code == 201 and r.json()["status"] == "pending" and r.json()["employee"]["employee_number"] == "MG-0042"
    assert client.post("/api/v1/attendance/manual-requests", json=body, headers=h).status_code == 200  # idempotent
    assert client.get("/api/v1/attendance/manual-requests?status=pending", headers=h).json()["total"] == 1
    assert client.get("/api/v1/attendance/today", headers=h).json()["pending_requests"] == 1
    rid = r.json()["id"]
    assert client.post(f"/api/v1/attendance/manual-requests/{rid}/cancel", headers=h).json()["status"] == "cancelled"


def test_validation(client, emp_tokens, clock):
    clock.local(date(2026, 11, 16), "09:00")
    h = auth(emp_tokens)
    assert client.post("/api/v1/attendance/manual-requests", json=req_body(requested_clock_in=None), headers=h).status_code == 422
    assert client.post("/api/v1/attendance/manual-requests", json=req_body(work_date="2026-12-30"), headers=h).status_code == 422


def test_admin_approve_creates_session(client, emp_tokens, manager_tokens, db, clock):
    clock.local(date(2026, 11, 16), "09:00")
    rid = client.post("/api/v1/attendance/manual-requests", json=req_body(), headers=auth(emp_tokens)).json()["id"]
    r = client.post(f"/api/v1/admin/manual-requests/{rid}/approve", json={"note": "OK"}, headers=auth(manager_tokens))
    assert r.status_code == 200 and r.json()["status"] == "approved" and r.json()["resulting_session_id"]
    s = db.get(AttendanceSession, uuid.UUID(r.json()["resulting_session_id"]))
    # 08:00 → 17:05 local = 545 gross − 60 break
    assert s.is_manual and s.status == "closed" and s.worked_minutes == 485 and s.work_date == FRIDAY
    assert db.execute(select(AuditLog).where(AuditLog.action == "manual_request.approve")).first()
    assert client.post(f"/api/v1/admin/manual-requests/{rid}/approve", json={}, headers=auth(manager_tokens)).status_code == 400
    notes = client.get("/api/v1/notifications", headers=auth(emp_tokens)).json()["items"]
    assert any(n["type"] == "manual_request_reviewed" for n in notes)


def test_missed_clock_out_closes_flagged_session(client, emp_tokens, manager_tokens, db, employee, clock):
    clock.local(date(2026, 11, 16), "09:00")
    from app.core import timeutil

    s = AttendanceSession(id=uuid.uuid4(), user_id=employee.id, work_date=FRIDAY, status="missing_clock_out", clock_in_at=timeutil.local_datetime(FRIDAY, "08:00"))
    db.add(s)
    db.commit()
    body = req_body(type="missed_clock_out", requested_clock_in=None, session_id=str(s.id))
    rid = client.post("/api/v1/attendance/manual-requests", json=body, headers=auth(emp_tokens)).json()["id"]
    r = client.post(f"/api/v1/admin/manual-requests/{rid}/approve", json={}, headers=auth(manager_tokens))
    assert r.json()["resulting_session_id"] == str(s.id)
    db.expire_all()
    assert db.get(AttendanceSession, s.id).status == "closed"


def test_reject(client, emp_tokens, manager_tokens, clock):
    clock.local(date(2026, 11, 16), "09:00")
    rid = client.post("/api/v1/attendance/manual-requests", json=req_body(), headers=auth(emp_tokens)).json()["id"]
    assert client.post(f"/api/v1/admin/manual-requests/{rid}/reject", json={}, headers=auth(manager_tokens)).status_code == 422  # note required
    r = client.post(f"/api/v1/admin/manual-requests/{rid}/reject", json={"note": "No evidence"}, headers=auth(manager_tokens))
    assert r.json()["status"] == "rejected" and r.json()["review_note"] == "No evidence"
    assert client.post(f"/api/v1/attendance/manual-requests/{rid}/cancel", headers=auth(emp_tokens)).status_code == 400


def test_profile_correction(client, emp_tokens, admin_tokens, db, employee):
    make_department(db, "Operations", "OPS")
    body = {"client_request_id": str(uuid.uuid4()), "type": "profile_correction", "reason": "Moved team", "field": "department", "requested_value": "ops"}
    rid = client.post("/api/v1/attendance/manual-requests", json=body, headers=auth(emp_tokens)).json()["id"]
    client.post(f"/api/v1/admin/manual-requests/{rid}/approve", json={}, headers=auth(admin_tokens))
    assert client.get("/api/v1/auth/me", headers=auth(emp_tokens)).json()["user"]["department"]["code"] == "OPS"


def test_employee_cannot_see_others_requests(client, emp_tokens, db, clock):
    from tests.conftest import login, make_user

    clock.local(date(2026, 11, 16), "09:00")
    other = make_user(db)
    t = login(client, other.email)
    rid = client.post("/api/v1/attendance/manual-requests", json=req_body(), headers=auth(t)).json()["id"]
    assert client.post(f"/api/v1/attendance/manual-requests/{rid}/cancel", headers=auth(emp_tokens)).status_code == 404
    assert client.get("/api/v1/attendance/manual-requests", headers=auth(emp_tokens)).json()["total"] == 0

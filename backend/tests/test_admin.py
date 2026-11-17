from __future__ import annotations

import uuid
from datetime import date, datetime, timedelta

import pytest
from openpyxl import load_workbook
from sqlalchemy import select, update

from app.core import timeutil
from app.models import (
    AttendanceEvent,
    AttendanceSession,
    AuditLog,
    Device,
    RefreshToken,
    User,
)
from app.services.reports import REPORT_TYPES
from tests.conftest import (
    PASSWORD,
    auth,
    confirm_body,
    event_body,
    login,
    make_department,
    make_user,
)

MONDAY = date(2026, 11, 16)


# --- employees ------------------------------------------------------------------------------
def test_employee_crud_and_audit(client, admin_tokens, db):
    h = auth(admin_tokens)
    dep = client.post("/api/v1/admin/departments", json={"name": "Finance", "code": "fin"}, headers=h).json()
    assert dep["code"] == "FIN"
    r = client.post("/api/v1/admin/employees", json={"employee_number": "MG-0100", "first_name": "Lerato", "last_name": "Dlamini", "email": "l.dlamini@example.co.za", "department_id": dep["id"]}, headers=h)
    assert r.status_code == 201
    temp = r.json()["temporary_password"]
    uid = r.json()["user"]["id"]
    assert r.json()["user"]["must_change_password"] is True and len(temp) >= 12
    assert client.post("/api/v1/admin/employees", json={"employee_number": "MG-0100", "first_name": "X", "last_name": "Y", "email": "other@example.co.za"}, headers=h).status_code == 409
    t = login(client, "MG-0100", temp)
    assert t["must_change_password"] is True

    r = client.patch(f"/api/v1/admin/employees/{uid}", json={"phone": "+27 82 000 0000", "last_name": "Dlamini-Zulu"}, headers=h)
    assert r.json()["last_name"] == "Dlamini-Zulu"
    lst = client.get("/api/v1/admin/employees?search=zulu", headers=h).json()
    assert lst["total"] == 1 and lst["items"][0]["last_attendance_status"] == "not_started"
    detail = client.get(f"/api/v1/admin/employees/{uid}", headers=h).json()
    assert set(detail) >= {"user", "devices", "today", "stats_30d"}

    r = client.post(f"/api/v1/admin/employees/{uid}/reset-password", headers=h)
    new_temp = r.json()["temporary_password"]
    assert new_temp != temp
    login(client, "MG-0100", new_temp)

    actions = {a for (a,) in db.execute(select(AuditLog.action)).all()}
    assert {"department.create", "employee.create", "employee.update", "employee.reset_password"} <= actions


def test_deactivate_revokes_devices_and_tokens(client, admin_tokens, emp_tokens, employee, db):
    r = client.post(f"/api/v1/admin/employees/{employee.id}/deactivate", json={"reason": "Resigned"}, headers=auth(admin_tokens))
    assert r.status_code == 200 and r.json()["status"] == "inactive"
    db.expire_all()
    assert all(d.status == "revoked" for d in db.execute(select(Device).where(Device.user_id == employee.id)).scalars())
    assert all(t.revoked_at for t in db.execute(select(RefreshToken).where(RefreshToken.user_id == employee.id)).scalars())
    assert client.get("/api/v1/attendance/today", headers=auth(emp_tokens)).status_code == 401
    assert client.post("/api/v1/auth/refresh", json={"refresh_token": emp_tokens["refresh_token"]}).status_code in (401, 403)
    r = client.post(f"/api/v1/admin/employees/{employee.id}/reactivate", headers=auth(admin_tokens))
    assert r.json()["status"] == "active"


def test_manager_cannot_write_employees(client, manager_tokens, employee):
    h = auth(manager_tokens)
    assert client.get("/api/v1/admin/employees", headers=h).status_code == 200
    for method, url, body in [
        ("post", "/api/v1/admin/employees", {"employee_number": "X1", "first_name": "a", "last_name": "b", "email": "x1@example.co.za"}),
        ("patch", f"/api/v1/admin/employees/{employee.id}", {"first_name": "Hacked"}),
        ("post", f"/api/v1/admin/employees/{employee.id}/deactivate", {"reason": "nope"}),
        ("post", f"/api/v1/admin/employees/{employee.id}/reset-password", None),
        ("post", f"/api/v1/admin/employees/{employee.id}/role", {"role": "admin"}),
        ("patch", "/api/v1/admin/policy", {"grace_minutes": 5}),
        ("post", "/api/v1/admin/notifications/broadcast", {"title": "t", "body": "b"}),
    ]:
        r = getattr(client, method)(url, json=body, headers=h)
        assert r.status_code == 403, url


def test_role_rules(client, admin_tokens, superadmin_tokens, employee, db):
    h = auth(admin_tokens)
    assert client.post(f"/api/v1/admin/employees/{employee.id}/role", json={"role": "manager"}, headers=h).json()["role"] == "manager"
    r = client.post(f"/api/v1/admin/employees/{employee.id}/role", json={"role": "superadmin"}, headers=h)
    assert r.status_code == 403
    r = client.post("/api/v1/admin/employees", json={"employee_number": "MG-S", "first_name": "a", "last_name": "b", "email": "s@example.co.za", "role": "superadmin"}, headers=h)
    assert r.status_code == 403
    r = client.post(f"/api/v1/admin/employees/{employee.id}/role", json={"role": "superadmin"}, headers=auth(superadmin_tokens))
    assert r.json()["role"] == "superadmin"
    sup = db.execute(select(User).where(User.employee_number == "MG-SUP")).scalar_one()
    assert client.post(f"/api/v1/admin/employees/{sup.id}/deactivate", json={"reason": "try"}, headers=h).status_code == 403
    roles = client.get("/api/v1/admin/roles", headers=h).json()["roles"]
    assert {r["role"]: r["user_count"] for r in roles}["superadmin"] == 2


def test_export_and_erase(client, admin_tokens, superadmin_tokens, employee, emp_tokens, workplace, clock, db):
    clock.local(MONDAY, "08:00")
    client.post("/api/v1/attendance/events", json=event_body(workplace), headers=auth(emp_tokens))
    exp = client.get(f"/api/v1/admin/employees/{employee.id}/export", headers=auth(admin_tokens)).json()
    assert exp["user"]["email"] == employee.email and len(exp["events"]) == 1 and exp["devices"]
    # admin cannot erase; superadmin must deactivate first and confirm the number
    body = {"confirm_employee_number": "MG-0042", "reason": "POPIA erasure request"}
    assert client.post(f"/api/v1/admin/employees/{employee.id}/erase", json=body, headers=auth(admin_tokens)).status_code == 403
    assert client.post(f"/api/v1/admin/employees/{employee.id}/erase", json=body, headers=auth(superadmin_tokens)).status_code == 400
    client.post(f"/api/v1/admin/employees/{employee.id}/deactivate", json={"reason": "left"}, headers=auth(admin_tokens))
    assert client.post(f"/api/v1/admin/employees/{employee.id}/erase", json={**body, "confirm_employee_number": "WRONG"}, headers=auth(superadmin_tokens)).status_code == 422
    assert client.post(f"/api/v1/admin/employees/{employee.id}/erase", json=body, headers=auth(superadmin_tokens)).status_code == 202
    db.expire_all()
    u = db.get(User, employee.id)
    assert u.erased_at and u.first_name == "Erased" and "MG-0042" not in u.employee_number and u.password_hash is None
    e = db.execute(select(AttendanceEvent)).scalar_one()
    assert e.latitude is None and e.user_id == employee.id  # history retained, pseudonymised


# --- overview / live / exceptions --------------------------------------------------------------
@pytest.fixture
def seeded(db, workplace):
    """Four employees employed since well before the period; deterministic sessions."""
    dep = make_department(db)
    users = [make_user(db, number=f"E-{i}", first=f"Emp{i}", last="Seed", department=dep if i < 3 else None) for i in range(4)]
    db.execute(update(User).values(created_at=datetime(2026, 1, 1, tzinfo=timeutil.UTC)))
    db.commit()

    def sess(u, day, cin, cout=None, status="closed"):
        s = AttendanceSession(id=uuid.uuid4(), user_id=u.id, workplace_id=workplace.id, work_date=day, status=status, clock_in_at=timeutil.local_datetime(day, cin), clock_out_at=timeutil.local_datetime(day, cout) if cout else None)
        from app.services.attendance import compute_session_metrics
        from app.services.config_service import get_policy

        compute_session_metrics(s, get_policy(db))
        db.add(s)

    # Monday: u0 on time & still in, u1 late & out, u2 absent, u3 missing clock-out flagged
    sess(users[0], MONDAY, "07:55", status="open")
    sess(users[1], MONDAY, "08:30", "17:00")
    sess(users[3], MONDAY, "08:00", status="missing_clock_out")
    # Friday before: everyone except u3
    fri = MONDAY - timedelta(days=3)
    for u in users[:3]:
        sess(u, fri, "08:00", "16:00")
    db.commit()
    return users


def test_overview_numbers(client, manager_tokens, seeded, clock):
    clock.local(MONDAY, "12:00")
    ov = client.get("/api/v1/admin/overview", headers=auth(manager_tokens)).json()
    st = ov["stats"]
    # 4 seeded + the manager account (5 active users); manager has no session → absent
    assert st["total_employees"] == 5
    assert st["clocked_in"] == 1 and st["clocked_out"] == 2 and st["late_arrivals"] == 1 and st["absent"] == 2
    trend = {t["work_date"]: t for t in ov["attendance_trend"]}
    assert len(ov["attendance_trend"]) == 14
    assert trend["2026-11-16"]["present"] == 3 and trend["2026-11-13"]["present"] == 3
    assert trend["2026-11-15"]["absent"] == 0  # Sunday is not a working day
    fin = next(d for d in ov["department_breakdown"] if d["department"] == "Finance")
    assert fin == {"department_id": fin["department_id"], "department": "Finance", "present": 2, "total": 3}


def test_live_and_exceptions(client, manager_tokens, seeded, clock):
    clock.local(MONDAY, "12:00")
    h = auth(manager_tokens)
    live = {i["employee"]["employee_number"]: i for i in client.get("/api/v1/admin/attendance/live", headers=h).json()["items"]}
    assert live["E-0"]["status"] == "clocked_in" and live["E-0"]["worked_minutes"] == 245
    assert live["E-1"]["status"] == "clocked_out" and live["E-1"]["is_late"] is True
    assert live["E-2"]["status"] == "not_started" and live["E-3"]["status"] == "missing_clock_out"
    ex = client.get("/api/v1/admin/attendance/exceptions?from=2026-11-13&to=2026-11-16", headers=h).json()
    kinds = [(i["kind"], i["employee"]["employee_number"]) for i in ex["items"]]
    assert ("late_arrival", "E-1") in kinds and ("missing_clock_out", "E-3") in kinds and ("absent", "E-3") in kinds
    only_absent = client.get("/api/v1/admin/attendance/exceptions?from=2026-11-13&to=2026-11-16&kind=absent", headers=h).json()
    # Monday is "today" → not yet counted as absence; Friday: E-3 and the manager
    assert {i["employee"]["employee_number"] for i in only_absent["items"]} == {"E-3", "MG-MGR"}


def test_admin_session_create_and_correct(client, manager_tokens, employee, clock, db):
    clock.local(MONDAY, "12:00")
    h = auth(manager_tokens)
    body = {"employee_id": str(employee.id), "work_date": "2026-11-13", "clock_in_at": "2026-11-13T06:00:00Z", "clock_out_at": "2026-11-13T14:00:00Z", "note": "Forgot phone"}
    r = client.post("/api/v1/admin/attendance/sessions", json=body, headers=h)
    assert r.status_code == 201 and r.json()["is_manual"] is True and r.json()["worked_minutes"] == 420
    sid = r.json()["id"]
    r = client.patch(f"/api/v1/admin/attendance/sessions/{sid}", json={"clock_out_at": "2026-11-13T15:00:00Z", "note": "Correct time per supervisor"}, headers=h)
    assert r.json()["corrected"] is True and r.json()["worked_minutes"] == 480
    assert client.patch(f"/api/v1/admin/attendance/sessions/{sid}", json={"clock_out_at": "2026-11-13T15:00:00Z"}, headers=h).status_code == 422
    detail = client.get(f"/api/v1/admin/attendance/sessions/{sid}", headers=h).json()
    assert len(detail["events"]) == 2 and detail["employee"]["employee_number"] == "MG-0042"
    assert client.get("/api/v1/admin/attendance/sessions?manual=true", headers=h).json()["total"] == 1
    assert db.execute(select(AuditLog).where(AuditLog.action == "attendance.session_correct")).first()


# --- reports ----------------------------------------------------------------------------------------
@pytest.mark.parametrize("report", REPORT_TYPES)
def test_reports_all_formats(client, manager_tokens, seeded, clock, report):
    clock.local(MONDAY, "12:00")
    h = auth(manager_tokens)
    q = "from=2026-11-09&to=2026-11-16"
    j = client.get(f"/api/v1/admin/reports/{report}?{q}&format=json", headers=h)
    assert j.status_code == 200, j.text
    data = j.json()
    assert data["report"] == report and data["columns"] and isinstance(data["rows"], list) and "summary" in data
    csv_r = client.get(f"/api/v1/admin/reports/{report}?{q}&format=csv", headers=h)
    assert csv_r.status_code == 200 and "attachment" in csv_r.headers["content-disposition"]
    assert csv_r.content.decode("utf-8-sig").splitlines()[0].split(",")[0] == data["columns"][0]["label"].split(",")[0]
    assert len(csv_r.content.decode("utf-8-sig").splitlines()) >= 1 + len(data["rows"])
    x = client.get(f"/api/v1/admin/reports/{report}?{q}&format=xlsx", headers=h)
    assert x.status_code == 200 and x.content[:2] == b"PK"
    import io

    wb = load_workbook(io.BytesIO(x.content))
    assert wb.active.max_row == 1 + len(data["rows"])
    p = client.get(f"/api/v1/admin/reports/{report}?{q}&format=pdf", headers=h)
    assert p.status_code == 200 and p.content[:5] == b"%PDF-"


def test_report_values(client, manager_tokens, seeded, clock):
    clock.local(MONDAY, "12:00")
    h = auth(manager_tokens)
    late = client.get("/api/v1/admin/reports/late-arrivals?from=2026-11-16&to=2026-11-16", headers=h).json()
    assert [r["employee_number"] for r in late["rows"]] == ["E-1"] and late["rows"][0]["late_minutes"] == 30
    daily = client.get("/api/v1/admin/reports/daily?from=2026-11-13&to=2026-11-13", headers=h).json()
    assert daily["summary"]["sessions"] == 3 and daily["summary"]["worked_minutes"] == 3 * 420
    assert client.get("/api/v1/admin/reports/nope", headers=h).status_code == 404
    assert client.get("/api/v1/admin/reports/daily?format=exe", headers=h).status_code == 422


# --- config ---------------------------------------------------------------------------------------
def test_workplace_and_policy(client, admin_tokens, emp_tokens, db):
    from app.cli import PLACEHOLDER_WORKPLACE_NAME, ensure_policy_and_workplace

    ensure_policy_and_workplace(db)
    h = auth(admin_tokens)
    items = client.get("/api/v1/admin/workplaces", headers=h).json()["items"]
    assert items[0]["name"] == PLACEHOLDER_WORKPLACE_NAME and items[0]["coordinates_confirmed"] is False and items[0]["latitude"] == 0.0
    cfg = client.get("/api/v1/config/workplaces", headers=auth(emp_tokens)).json()
    v1 = cfg["config_version"]
    r = client.patch(f"/api/v1/admin/workplaces/{items[0]['id']}", json={"wifi_bssids": ["AA:BB:CC:DD:EE:01"], "wifi_verification_enabled": True}, headers=h)
    assert r.status_code == 200 and r.json()["wifi_bssids"] == ["aa:bb:cc:dd:ee:01"]
    assert client.patch(f"/api/v1/admin/workplaces/{items[0]['id']}", json={"wifi_bssids": ["nonsense"]}, headers=h).status_code == 422
    assert client.get("/api/v1/config/workplaces", headers=auth(emp_tokens)).json()["config_version"] == v1 + 1
    r = client.post("/api/v1/admin/workplaces", json={"name": "Second site", "latitude": -29.0, "longitude": 31.0}, headers=h)
    assert r.status_code == 201 and r.json()["coordinates_confirmed"] is False
    p = client.patch("/api/v1/admin/policy", json={"grace_minutes": 10, "working_days": [1, 2, 3, 4, 5, 6]}, headers=h).json()
    assert p["grace_minutes"] == 10 and p["working_days"] == [1, 2, 3, 4, 5, 6]
    assert client.get("/api/v1/config/policy", headers=auth(emp_tokens)).json()["grace_minutes"] == 10
    assert client.patch("/api/v1/admin/policy", json={"late_after_local": "25:99"}, headers=h).status_code == 422


def test_audit_log_listing(client, admin_tokens, employee):
    h = auth(admin_tokens)
    client.post(f"/api/v1/admin/employees/{employee.id}/reset-password", headers=h)
    r = client.get("/api/v1/admin/audit-logs?action=employee.", headers=h).json()
    assert r["total"] >= 1 and r["items"][0]["actor"]["employee_number"] == "MG-ADM"
    r = client.get(f"/api/v1/admin/audit-logs?entity_id={employee.id}", headers=h).json()
    assert any(i["action"] == "employee.reset_password" for i in r["items"])


# --- notifications ----------------------------------------------------------------------------------
def test_notifications_inbox_and_broadcast(client, admin_tokens, emp_tokens, workplace, clock):
    clock.local(MONDAY, "08:00")
    h = auth(emp_tokens)
    client.post("/api/v1/attendance/events", json=event_body(workplace), headers=h)
    r = client.post("/api/v1/admin/notifications/broadcast", json={"title": "Office closed", "body": "Public holiday"}, headers=auth(admin_tokens))
    assert r.json()["sent"] == 2  # admin + employee
    assert client.get("/api/v1/notifications/unread-count", headers=h).json() == {"count": 2}
    items = client.get("/api/v1/notifications", headers=h).json()["items"]
    assert {i["type"] for i in items} == {"clock_in_confirmation_required", "system"}
    assert items[-1]["data"].get("event_id")
    assert client.post(f"/api/v1/notifications/{items[0]['id']}/read", headers=h).status_code == 204
    assert client.get("/api/v1/notifications?unread_only=true", headers=h).json()["total"] == 1
    assert client.post("/api/v1/notifications/read-all", headers=h).status_code == 204
    assert client.get("/api/v1/attendance/today", headers=h).json()["unread_notifications"] == 0
    other = client.get("/api/v1/notifications", headers=auth(admin_tokens)).json()["items"][0]
    assert client.post(f"/api/v1/notifications/{other['id']}/read", headers=h).status_code == 404


def test_confirmed_event_appears_in_admin_events(client, manager_tokens, emp_tokens, workplace, clock):
    clock.local(MONDAY, "08:00")
    ev = client.post("/api/v1/attendance/events", json=event_body(workplace), headers=auth(emp_tokens)).json()["event"]
    client.post(f"/api/v1/attendance/events/{ev['id']}/confirm", json=confirm_body(), headers=auth(emp_tokens))
    r = client.get("/api/v1/admin/attendance/events?status=confirmed", headers=auth(manager_tokens)).json()
    assert r["total"] == 1 and r["items"][0]["employee"]["employee_number"] == "MG-0042"
    assert PASSWORD  # keep import used

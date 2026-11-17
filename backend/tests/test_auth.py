from __future__ import annotations

from sqlalchemy import select

from app.models import AuditLog, PasswordResetToken, RefreshToken, User
from tests.conftest import PASSWORD, auth, device_body, login, make_user


def test_login_returns_tokens_user_and_device(client, employee):
    data = login(client, employee.email, device=device_body())
    assert data["token_type"] == "bearer" and data["expires_in"] == 900
    assert data["user"]["employee_number"] == "MG-0042"
    assert "attendance:self" in data["user"]["permissions"]
    assert data["device"]["status"] == "active"
    me = client.get("/api/v1/auth/me", headers=auth(data)).json()
    assert me["user"]["id"] == data["user"]["id"] and me["device"]["id"] == data["device"]["id"]


def test_login_by_employee_number_without_device(client, employee):
    data = login(client, "MG-0042")
    assert data["device"] is None


def test_invalid_credentials_and_unknown_user_look_identical(client, employee):
    a = client.post("/api/v1/auth/login", json={"identifier": employee.email, "password": "wrong-password-1"})
    b = client.post("/api/v1/auth/login", json={"identifier": "nobody@example.test", "password": "wrong-password-1"})
    assert a.status_code == b.status_code == 401
    assert a.json()["error"]["code"] == b.json()["error"]["code"] == "invalid_credentials"
    assert a.json()["error"]["message"] == b.json()["error"]["message"]


def test_lockout_after_failed_attempts(client, employee):
    for _ in range(4):
        assert client.post("/api/v1/auth/login", json={"identifier": employee.email, "password": "bad-password-x"}).status_code == 401
    r = client.post("/api/v1/auth/login", json={"identifier": employee.email, "password": "bad-password-x"})
    assert r.status_code == 423 and r.json()["error"]["code"] == "account_locked"
    # even the correct password is refused while locked
    r = client.post("/api/v1/auth/login", json={"identifier": employee.email, "password": PASSWORD})
    assert r.status_code == 423 and "Retry-After" in r.headers


def test_inactive_account_cannot_login(client, db):
    u = make_user(db, status="inactive")
    r = client.post("/api/v1/auth/login", json={"identifier": u.email, "password": PASSWORD})
    assert r.status_code == 403 and r.json()["error"]["code"] == "account_inactive"


def test_refresh_rotation_and_reuse_detection(client, employee, db):
    first = login(client, employee.email, device=device_body())
    r = client.post("/api/v1/auth/refresh", json={"refresh_token": first["refresh_token"]})
    assert r.status_code == 200
    second = r.json()
    assert second["refresh_token"] != first["refresh_token"]
    # replaying the rotated token → reuse detected, whole family revoked
    r = client.post("/api/v1/auth/refresh", json={"refresh_token": first["refresh_token"]})
    assert r.status_code == 401 and r.json()["error"]["code"] == "token_reused"
    r = client.post("/api/v1/auth/refresh", json={"refresh_token": second["refresh_token"]})
    assert r.status_code == 401
    rows = db.execute(select(RefreshToken)).scalars().all()
    assert rows and all(t.revoked_at is not None for t in rows)
    assert db.execute(select(AuditLog).where(AuditLog.action == "auth.refresh_reuse_detected")).first()


def test_logout_revokes_refresh_family(client, employee):
    t = login(client, employee.email)
    assert client.post("/api/v1/auth/logout", json={"refresh_token": t["refresh_token"]}).status_code == 204
    r = client.post("/api/v1/auth/refresh", json={"refresh_token": t["refresh_token"]})
    assert r.status_code == 401


def test_password_change_policy(client, employee):
    t = login(client, employee.email)
    h = auth(t)
    r = client.post("/api/v1/auth/password/change", json={"current_password": "wrong-one-xx", "new_password": "Another-Strong-Pass-7"}, headers=h)
    assert r.status_code == 401
    for bad in ("short1", "qwertyuiop", "1234567890", f"{employee.employee_number}-abcdefgh"):
        r = client.post("/api/v1/auth/password/change", json={"current_password": PASSWORD, "new_password": bad}, headers=h)
        assert r.status_code == 422, bad
    r = client.post("/api/v1/auth/password/change", json={"current_password": PASSWORD, "new_password": PASSWORD}, headers=h)
    assert r.status_code == 422
    r = client.post("/api/v1/auth/password/change", json={"current_password": PASSWORD, "new_password": "Another-Strong-Pass-7"}, headers=h)
    assert r.status_code == 204
    # old access token invalidated (token_version bumped), new password works
    assert client.get("/api/v1/auth/me", headers=h).status_code == 401
    login(client, employee.email, "Another-Strong-Pass-7")


def test_must_change_password_blocks_other_endpoints(client, db):
    u = make_user(db, must_change=True)
    t = login(client, u.email)
    assert t["must_change_password"] is True
    r = client.get("/api/v1/attendance/today", headers=auth(t))
    assert r.status_code == 403 and r.json()["error"]["code"] == "password_change_required"
    assert client.get("/api/v1/auth/me", headers=auth(t)).status_code == 200


def test_forgot_always_202_and_reset_flow(client, employee, db, monkeypatch):
    sent = {}
    from app.services import email as email_service

    monkeypatch.setattr(email_service, "send_email", lambda **kw: sent.update(kw) or True)
    assert client.post("/api/v1/auth/password/forgot", json={"identifier": "nobody@example.test"}).status_code == 202
    assert not sent
    assert client.post("/api/v1/auth/password/forgot", json={"identifier": employee.email}).status_code == 202
    assert sent["to"] == employee.email
    token = sent["body"].split("token=")[1].split()[0]
    assert db.execute(select(PasswordResetToken)).scalar_one().token_hash != token  # stored hashed
    assert client.post("/api/v1/auth/password/reset", json={"token": token, "new_password": "Brand-New-Secret-42"}).status_code == 204
    assert client.post("/api/v1/auth/password/reset", json={"token": token, "new_password": "Brand-New-Secret-43"}).status_code == 400
    login(client, employee.email, "Brand-New-Secret-42")


def test_patch_me(client, emp_tokens):
    r = client.patch("/api/v1/auth/me", json={"phone": "+27 31 000 0000", "notification_preferences": {"push": False}}, headers=auth(emp_tokens))
    assert r.status_code == 200
    assert r.json()["phone"] == "+27 31 000 0000" and r.json()["notification_preferences"]["push"] is False


def test_client_cannot_escalate_role_via_patch_me(client, emp_tokens, db, employee):
    r = client.patch("/api/v1/auth/me", json={"role": "superadmin"}, headers=auth(emp_tokens))
    assert r.status_code == 422
    db.expire_all()
    assert db.get(User, employee.id).role == "employee"

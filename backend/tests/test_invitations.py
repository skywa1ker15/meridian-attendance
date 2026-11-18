"""Invitation flow: an administrator creates the account, the employee activates it."""
from __future__ import annotations

import uuid

import pytest
from sqlalchemy import select

from app.models import InvitationToken, User
from app.services import email as email_service
from tests.conftest import PASSWORD, auth, device_body, login, make_department, make_user

NEW_PASSWORD = "Umlazi-Riverbank-77"


@pytest.fixture
def mail(monkeypatch):
    """Pretend an email provider is configured and capture what would be sent."""
    outbox: list[dict] = []
    monkeypatch.setattr(email_service, "email_configured", lambda *a, **k: True)
    monkeypatch.setattr(email_service, "active_provider", lambda *a, **k: "graph")
    monkeypatch.setattr(email_service, "send_email", lambda **kw: bool(outbox.append(kw)) or True)
    return outbox


def token_from(outbox: list[dict]) -> str:
    return outbox[-1]["text_body"].split("token=")[1].split()[0]


def create_employee(client, admin_tokens, **overrides) -> dict:
    body = {
        "employee_number": f"MG-{uuid.uuid4().hex[:5].upper()}",
        "first_name": "Sipho",
        "last_name": "Khumalo",
        "email": f"{uuid.uuid4().hex[:8]}@example.co.za",
        **overrides,
    }
    r = client.post("/api/v1/admin/employees", json=body, headers=auth(admin_tokens))
    assert r.status_code == 201, r.text
    return r.json()


# --- creation ----------------------------------------------------------------
def test_create_invites_when_email_configured(client, admin_tokens, db, mail):
    created = create_employee(client, admin_tokens)
    assert "temporary_password" not in created
    assert created["invitation"]["sent"] is True and created["invitation"]["expires_at"]
    assert created["user"]["must_change_password"] is False and created["user"]["status"] == "active"

    user = db.get(User, uuid.UUID(created["user"]["id"]))
    assert user.password_hash is None
    assert len(mail) == 1 and mail[0]["to"] == user.email
    assert mail[0]["subject"] == "Set up your Meridian Attendance account"
    assert user.first_name in mail[0]["text_body"] and "tell your manager" in mail[0]["text_body"]
    assert "<a href=" in mail[0]["html_body"]
    assert db.execute(select(InvitationToken).where(InvitationToken.user_id == user.id)).scalar_one().token_hash != token_from(mail)


def test_create_without_email_falls_back_to_temporary_password(client, admin_tokens, db):
    created = create_employee(client, admin_tokens)
    assert created["invitation"] is None and created["temporary_password"]
    user = db.get(User, uuid.UUID(created["user"]["id"]))
    assert user.password_hash is not None and user.must_change_password is True


def test_create_can_opt_out_of_the_invitation(client, admin_tokens, mail):
    created = create_employee(client, admin_tokens, send_invitation=False)
    assert created["invitation"] is None and created["temporary_password"]
    assert mail == []


def test_create_normalises_the_phone(client, admin_tokens, mail):
    created = create_employee(client, admin_tokens, phone="082 123 4567")
    assert created["user"]["phone"] == "+27821234567"


def test_create_rejects_a_rubbish_phone(client, admin_tokens, mail):
    r = client.post(
        "/api/v1/admin/employees",
        json={"employee_number": "MG-BAD1", "first_name": "A", "last_name": "B", "email": "bad@example.co.za", "phone": "12345"},
        headers=auth(admin_tokens),
    )
    assert r.status_code == 422 and r.json()["error"]["details"]["fields"]["phone"] == "invalid_phone"


# --- the invitation page -----------------------------------------------------
def test_invitation_details(client, admin_tokens, db, mail):
    dep = make_department(db, name="Logistics", code="LOG")
    created = create_employee(client, admin_tokens, department_id=str(dep.id))
    r = client.get("/api/v1/auth/invitation", params={"token": token_from(mail)})
    assert r.status_code == 200
    body = r.json()
    assert body["employee_number"] == created["user"]["employee_number"]
    assert body["first_name"] == "Sipho" and body["last_name"] == "Khumalo"
    assert body["email"] == created["user"]["email"] and body["phone"] is None
    assert body["department"] == {"id": str(dep.id), "name": "Logistics"}
    assert body["expires_at"]


def test_invitation_details_reveal_nothing_for_a_bad_token(client):
    r = client.get("/api/v1/auth/invitation", params={"token": "x" * 40})
    assert r.status_code == 400 and r.json()["error"]["code"] == "token_invalid"
    assert "@" not in r.json()["error"]["message"]


# --- acceptance --------------------------------------------------------------
def accept(client, token, *, phone="082 123 4567", password=NEW_PASSWORD, device=True):
    body = {"token": token, "phone": phone, "password": password}
    if device:
        body["device"] = device_body()
    return client.post("/api/v1/auth/invitation/accept", json=body)


def test_accept_happy_path(client, admin_tokens, db, mail):
    created = create_employee(client, admin_tokens)
    r = accept(client, token_from(mail))
    assert r.status_code == 201, r.text
    body = r.json()
    assert set(body) >= {"access_token", "refresh_token", "token_type", "expires_in", "user", "device"}
    assert body["user"]["phone"] == "+27821234567"
    assert body["user"]["must_change_password"] is False
    assert body["device"]["status"] == "active"

    user = db.get(User, uuid.UUID(created["user"]["id"]))
    db.refresh(user)
    assert user.password_hash is not None
    assert db.execute(select(InvitationToken).where(InvitationToken.user_id == user.id)).scalar_one().used_at is not None
    # The returned access token works.
    assert client.get("/api/v1/auth/me", headers=auth(body)).status_code == 200


def test_accept_without_a_device(client, admin_tokens, mail):
    create_employee(client, admin_tokens)
    r = accept(client, token_from(mail), device=False)
    assert r.status_code == 201 and r.json()["device"] is None


def test_accept_twice_fails(client, admin_tokens, mail):
    create_employee(client, admin_tokens)
    token = token_from(mail)
    assert accept(client, token).status_code == 201
    second = accept(client, token, password="Another-Strong-Pass-5")
    assert second.status_code == 400 and second.json()["error"]["code"] == "token_invalid"


def test_accept_with_an_unknown_token(client):
    r = accept(client, "y" * 43)
    assert r.status_code == 400 and r.json()["error"]["code"] == "token_invalid"


def test_accept_with_an_expired_token(client, admin_tokens, db, mail):
    create_employee(client, admin_tokens)
    row = db.execute(select(InvitationToken)).scalar_one()
    row.expires_at = row.created_at.replace(year=row.created_at.year - 1)
    db.commit()
    r = accept(client, token_from(mail))
    assert r.status_code == 400 and r.json()["error"]["code"] == "token_invalid"


def test_accept_rejects_a_weak_password(client, admin_tokens, mail):
    create_employee(client, admin_tokens)
    r = accept(client, token_from(mail), password="Password123")
    assert r.status_code == 422 and r.json()["error"]["code"] == "validation_error"
    assert "new_password" in r.json()["error"]["details"]["fields"]


def test_accept_rejects_a_password_containing_the_employee_number(client, admin_tokens, mail):
    created = create_employee(client, admin_tokens)
    r = accept(client, token_from(mail), password=f"xy-{created['user']['employee_number']}-zz")
    assert r.status_code == 422


def test_accept_rejects_a_bad_phone(client, admin_tokens, mail):
    create_employee(client, admin_tokens)
    r = accept(client, token_from(mail), phone="12345678")
    assert r.status_code == 422 and r.json()["error"]["details"]["fields"]["phone"] == "invalid_phone"


def test_accept_for_a_deactivated_account_fails(client, admin_tokens, db, mail):
    created = create_employee(client, admin_tokens)
    r = client.post(f"/api/v1/admin/employees/{created['user']['id']}/deactivate", json={"reason": "left"}, headers=auth(admin_tokens))
    assert r.status_code == 200
    r = accept(client, token_from(mail))
    assert r.status_code == 400 and r.json()["error"]["code"] == "token_invalid"


# --- login before / after activation -----------------------------------------
def test_login_before_activation_reports_invitation_pending(client, admin_tokens, mail):
    created = create_employee(client, admin_tokens)
    r = client.post("/api/v1/auth/login", json={"identifier": created["user"]["email"], "password": PASSWORD})
    assert r.status_code == 401
    assert r.json()["error"]["code"] == "invitation_pending"
    assert "invitation link" in r.json()["error"]["message"]


def test_login_works_after_activation(client, admin_tokens, mail):
    created = create_employee(client, admin_tokens)
    assert accept(client, token_from(mail)).status_code == 201
    tokens = login(client, created["user"]["email"], NEW_PASSWORD, device=device_body())
    assert tokens["user"]["phone"] == "+27821234567"


def test_unknown_identifier_still_looks_like_bad_credentials(client):
    r = client.post("/api/v1/auth/login", json={"identifier": "ghost@example.co.za", "password": PASSWORD})
    assert r.status_code == 401 and r.json()["error"]["code"] == "invalid_credentials"


# --- re-invitation -----------------------------------------------------------
def test_reinvite_invalidates_the_previous_token(client, admin_tokens, db, mail):
    created = create_employee(client, admin_tokens)
    first = token_from(mail)
    r = client.post(f"/api/v1/admin/employees/{created['user']['id']}/invite", headers=auth(admin_tokens))
    assert r.status_code == 200 and r.json()["sent"] is True and r.json()["expires_at"]
    second = token_from(mail)
    assert second != first
    assert accept(client, first).status_code == 400
    assert accept(client, second).status_code == 201
    assert db.execute(select(InvitationToken).where(InvitationToken.used_at.is_(None))).first() is None


def test_reinvite_refused_once_a_password_exists(client, admin_tokens, db, mail):
    created = create_employee(client, admin_tokens)
    assert accept(client, token_from(mail)).status_code == 201
    r = client.post(f"/api/v1/admin/employees/{created['user']['id']}/invite", headers=auth(admin_tokens))
    assert r.status_code == 409 and r.json()["error"]["code"] == "invalid_state"


def test_reinvite_refused_for_a_deactivated_account(client, admin_tokens, mail):
    created = create_employee(client, admin_tokens)
    client.post(f"/api/v1/admin/employees/{created['user']['id']}/deactivate", json={"reason": "left"}, headers=auth(admin_tokens))
    r = client.post(f"/api/v1/admin/employees/{created['user']['id']}/invite", headers=auth(admin_tokens))
    assert r.status_code == 400 and r.json()["error"]["code"] == "invalid_state"


def test_invite_requires_employees_write(client, emp_tokens, db, mail, admin_tokens):
    created = create_employee(client, admin_tokens)
    r = client.post(f"/api/v1/admin/employees/{created['user']['id']}/invite", headers=auth(emp_tokens))
    assert r.status_code == 403


def test_invite_reports_not_sent_when_email_is_unavailable(client, admin_tokens, db, monkeypatch):
    """Without email the administrator still gets a token issued, and is told nothing went out."""
    user = make_user(db, number="MG-NOPW", email="nopw@example.co.za")
    user.password_hash = None
    db.commit()
    r = client.post(f"/api/v1/admin/employees/{user.id}/invite", headers=auth(admin_tokens))
    assert r.status_code == 200 and r.json()["sent"] is False
    assert db.execute(select(InvitationToken).where(InvitationToken.user_id == user.id)).scalar_one() is not None

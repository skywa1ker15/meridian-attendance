"""First-run setup from the Android app (POST /setup/first-admin)."""
import pytest

from app.core.config import get_settings
from tests.conftest import device_body, make_user

BODY = {"employee_number": "mg-0001", "first_name": "Nomsa", "last_name": "Dlamini", "email": "nomsa.d@example.co.za", "password": "Tidal-Lamp-Orbit-94"}


@pytest.fixture
def settings_override(monkeypatch):
    s = get_settings()
    def apply(**kw):
        for k, v in kw.items():
            monkeypatch.setattr(s, k, v)
    return apply


def test_status_reports_empty_system(client):
    r = client.get("/api/v1/setup/status").json()
    assert r["needs_first_admin"] is True


def test_first_admin_created_and_signed_in_with_device(client, db):
    r = client.post("/api/v1/setup/first-admin", json={**BODY, "device": device_body()})
    assert r.status_code == 201, r.text
    body = r.json()
    assert body["user"]["role"] == "superadmin" and body["user"]["employee_number"] == "MG-0001"
    assert body["access_token"] and body["refresh_token"] and body["device"] is not None
    me = client.get("/api/v1/auth/me", headers={"Authorization": f"Bearer {body['access_token']}"}).json()
    assert me["user"]["email"] == "nomsa.d@example.co.za"
    assert client.get("/api/v1/setup/status").json()["needs_first_admin"] is False
    # Endpoint is now closed for good.
    again = client.post("/api/v1/setup/first-admin", json={**BODY, "employee_number": "X-2", "email": "x2@example.co.za"})
    assert again.status_code == 409 and again.json()["error"]["code"] == "setup_complete"


def test_closed_when_any_admin_exists(client, db):
    make_user(db, role="admin")
    r = client.post("/api/v1/setup/first-admin", json=BODY)
    assert r.status_code == 409


def test_employees_do_not_close_setup(client, db):
    make_user(db, role="employee")
    assert client.get("/api/v1/setup/status").json()["needs_first_admin"] is True


def test_setup_code_enforced_when_configured(client, settings_override):
    settings_override(first_admin_setup_code="Meridian-Setup-7731")
    assert client.get("/api/v1/setup/status").json()["setup_code_required"] is True
    bad = client.post("/api/v1/setup/first-admin", json={**BODY, "setup_code": "guess"})
    assert bad.status_code == 403 and bad.json()["error"]["code"] == "invalid_setup_code"
    missing = client.post("/api/v1/setup/first-admin", json=BODY)
    assert missing.status_code == 403
    ok = client.post("/api/v1/setup/first-admin", json={**BODY, "setup_code": " Meridian-Setup-7731 "})
    assert ok.status_code == 201


def test_production_without_configured_code_refuses(client, settings_override):
    settings_override(app_env="production", first_admin_setup_code=None)
    r = client.post("/api/v1/setup/first-admin", json={**BODY, "setup_code": "anything"})
    assert r.status_code == 403 and r.json()["error"]["code"] == "setup_disabled"


def test_weak_password_rejected(client):
    r = client.post("/api/v1/setup/first-admin", json={**BODY, "password": "Password123!"})
    assert r.status_code == 422


def test_cannot_inject_role(client):
    r = client.post("/api/v1/setup/first-admin", json={**BODY, "role": "employee"})
    # Unknown fields are rejected or ignored; either way the account is the setup superadmin or refused.
    assert r.status_code in (201, 422)
    if r.status_code == 201:
        assert r.json()["user"]["role"] == "superadmin"

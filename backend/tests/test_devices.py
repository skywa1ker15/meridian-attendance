from __future__ import annotations

from sqlalchemy import select

from app.models import Device, Notification
from app.services.config_service import get_policy
from tests.conftest import auth, device_body, event_body, login


def test_register_same_device_is_idempotent(client, employee):
    body = device_body("install-aaaaaaaa")
    t1 = login(client, employee.email, device=body)
    t2 = login(client, employee.email, device=body)
    assert t1["device"]["id"] == t2["device"]["id"]


def test_new_device_replaces_old_by_default(client, employee, db):
    old = login(client, employee.email, device=device_body("install-old-0001"))
    new = login(client, employee.email, device=device_body("install-new-0002"))
    assert new["device"]["status"] == "active"
    db.expire_all()
    statuses = {d.device_id: d.status for d in db.execute(select(Device)).scalars()}
    assert statuses == {"install-old-0001": "revoked", "install-new-0002": "active"}
    # old device's access token is refused, its refresh token revoked
    r = client.get("/api/v1/auth/me", headers=auth(old))
    assert r.status_code == 403 and r.json()["error"]["code"] == "device_revoked"
    assert client.post("/api/v1/auth/refresh", json={"refresh_token": old["refresh_token"]}).status_code in (401, 403)
    assert db.execute(select(Notification).where(Notification.type == "device_revoked")).first()
    # logging in again from the revoked device is refused
    r = client.post("/api/v1/auth/login", json={"identifier": employee.email, "password": "Correct-Horse-Battery-9", "device": device_body("install-old-0001")})
    assert r.status_code == 403 and r.json()["error"]["code"] == "device_revoked"


def test_replacement_requiring_approval(client, employee, db, workplace, admin_tokens):
    policy = get_policy(db)
    policy.device_replacement_requires_approval = True
    db.commit()
    login(client, employee.email, device=device_body("install-first-01"))
    second = login(client, employee.email, device=device_body("install-second-2"))
    assert second["device"]["status"] == "pending_approval"
    r = client.post("/api/v1/attendance/events", json=event_body(workplace), headers=auth(second))
    assert r.status_code == 403 and r.json()["error"]["code"] == "device_pending_approval"
    pending = client.get("/api/v1/admin/devices?status=pending_approval", headers=auth(admin_tokens)).json()
    assert pending["total"] == 1
    r = client.post(f"/api/v1/admin/devices/{second['device']['id']}/approve", headers=auth(admin_tokens))
    assert r.status_code == 200 and r.json()["status"] == "active"
    db.expire_all()
    assert db.execute(select(Device).where(Device.device_id == "install-first-01")).scalar_one().status == "revoked"
    assert client.post("/api/v1/attendance/events", json=event_body(workplace), headers=auth(second)).status_code == 201


def test_admin_revoke_device(client, emp_tokens, admin_tokens):
    did = emp_tokens["device"]["id"]
    r = client.post(f"/api/v1/admin/devices/{did}/revoke", json={"reason": "Lost phone"}, headers=auth(admin_tokens))
    assert r.status_code == 200 and r.json()["status"] == "revoked"
    assert client.get("/api/v1/attendance/today", headers=auth(emp_tokens)).json()["error"]["code"] == "device_revoked"


def test_device_me_fcm_and_integrity(client, emp_tokens, db):
    h = auth(emp_tokens)
    assert client.get("/api/v1/devices/me", headers=h).json()["id"] == emp_tokens["device"]["id"]
    assert client.post("/api/v1/devices/me/fcm-token", json={"fcm_token": "fcm-token-abcdefgh"}, headers=h).status_code == 204
    assert client.get("/api/v1/devices/me", headers=h).json()["has_fcm_token"] is True
    r = client.post("/api/v1/devices/me/integrity", json={"integrity_token": "integrity-xyz-123"}, headers=h)
    assert r.json() == {"verdict": "not_configured"}


def test_register_endpoint(client, employee):
    t = login(client, employee.email)
    r = client.post("/api/v1/devices/register", json=device_body("install-reg-0001"), headers=auth(t))
    assert r.status_code in (200, 201) and r.json()["status"] == "active"

from __future__ import annotations

import time
import uuid
from datetime import date, timedelta

import jwt
import pytest
from fastapi.testclient import TestClient
from sqlalchemy import func, select

from app.core.config import Settings, get_settings
from app.models import Department, User
from tests.conftest import PASSWORD, auth, event_body, login

MONDAY = date(2026, 11, 16)

PROTECTED = [
    ("get", "/api/v1/auth/me"),
    ("get", "/api/v1/attendance/today"),
    ("get", "/api/v1/attendance/pending"),
    ("get", "/api/v1/config/workplaces"),
    ("get", "/api/v1/notifications"),
    ("get", "/api/v1/devices/me"),
    ("get", "/api/v1/admin/overview"),
    ("get", "/api/v1/admin/employees"),
    ("get", "/api/v1/admin/reports/daily"),
]
ADMIN_ONLY = [
    ("get", "/api/v1/admin/overview"),
    ("get", "/api/v1/admin/employees"),
    ("get", "/api/v1/admin/attendance/live"),
    ("get", "/api/v1/admin/attendance/exceptions"),
    ("get", "/api/v1/admin/audit-logs"),
    ("get", "/api/v1/admin/reports/daily?format=csv"),
    ("get", "/api/v1/admin/policy"),
    ("get", "/api/v1/admin/devices"),
    ("post", "/api/v1/admin/departments"),
    ("post", "/api/v1/admin/notifications/broadcast"),
]


@pytest.mark.parametrize("method,url", PROTECTED)
def test_unauthenticated_is_401(client, method, url):
    r = getattr(client, method)(url)
    assert r.status_code == 401 and r.json()["error"]["code"] == "token_invalid"


@pytest.mark.parametrize("method,url", ADMIN_ONLY)
def test_employee_calling_admin_is_403(client, emp_tokens, method, url):
    kwargs = {"headers": auth(emp_tokens)}
    if method == "post":
        kwargs["json"] = {"name": "x", "code": "x", "title": "t", "body": "b"}
    r = getattr(client, method)(url, **kwargs)
    assert r.status_code == 403 and r.json()["error"]["code"] == "forbidden"


def test_tampered_and_expired_jwt(client, emp_tokens, employee):
    token = emp_tokens["access_token"]
    head, payload, sig = token.split(".")
    tampered = ".".join([head, payload, sig[:-4] + ("AAAA" if sig[-4:] != "AAAA" else "BBBB")])
    r = client.get("/api/v1/auth/me", headers={"Authorization": f"Bearer {tampered}"})
    assert r.status_code == 401 and r.json()["error"]["code"] == "token_invalid"
    # forged with the wrong key and an elevated role claim
    forged = jwt.encode({"sub": str(employee.id), "role": "superadmin", "ver": 1, "typ": "access", "iss": "meridian-attendance", "iat": int(time.time()), "exp": int(time.time()) + 600}, "not-the-key-" * 4, algorithm="HS256")
    assert client.get("/api/v1/admin/overview", headers={"Authorization": f"Bearer {forged}"}).status_code == 401
    # alg=none
    none_tok = jwt.encode({"sub": str(employee.id), "typ": "access"}, None, algorithm="none")
    assert client.get("/api/v1/auth/me", headers={"Authorization": f"Bearer {none_tok}"}).status_code == 401
    s = get_settings()
    expired = jwt.encode({"sub": str(employee.id), "role": "employee", "ver": 1, "typ": "access", "iss": "meridian-attendance", "iat": int(time.time()) - 3600, "exp": int(time.time()) - 1800}, s.secret_key, algorithm="HS256")
    r = client.get("/api/v1/auth/me", headers={"Authorization": f"Bearer {expired}"})
    assert r.status_code == 401 and r.json()["error"]["code"] == "token_expired"


def test_role_claim_in_jwt_is_not_trusted(client, employee):
    s = get_settings()
    tok = jwt.encode({"sub": str(employee.id), "role": "superadmin", "ver": 1, "typ": "access", "iss": "meridian-attendance", "iat": int(time.time()), "exp": int(time.time()) + 600}, s.secret_key, algorithm="HS256")
    assert client.get("/api/v1/auth/me", headers={"Authorization": f"Bearer {tok}"}).status_code == 200
    assert client.get("/api/v1/admin/overview", headers={"Authorization": f"Bearer {tok}"}).status_code == 403


def test_refresh_token_replay_revokes_family(client, employee):
    t = login(client, employee.email)
    new = client.post("/api/v1/auth/refresh", json={"refresh_token": t["refresh_token"]}).json()
    assert client.post("/api/v1/auth/refresh", json={"refresh_token": t["refresh_token"]}).json()["error"]["code"] == "token_reused"
    assert client.post("/api/v1/auth/refresh", json={"refresh_token": new["refresh_token"]}).status_code == 401


def test_manipulated_timestamps(client, emp_tokens, workplace, clock):
    now = clock.local(MONDAY, "08:00")
    h = auth(emp_tokens)
    far_future = client.post("/api/v1/attendance/events", json=event_body(workplace, detected_at=now + timedelta(days=1)), headers=h).json()["event"]
    assert far_future["status"] == "rejected"
    # back-dated "online" event cannot claim an early clock-in: official time is server time or it goes to review
    back = event_body(workplace, detected_at=now - timedelta(hours=2))
    ev = client.post("/api/v1/attendance/events", json=back, headers=h).json()["event"]
    r = client.post(f"/api/v1/attendance/events/{ev['id']}/confirm", json={"client_confirmation_id": str(uuid.uuid4()), "confirmed_at": "2026-11-16T04:00:00Z"}, headers=h).json()
    assert r["event"]["status"] == "under_review" and r["session"] is None
    # garbage timestamp
    bad = event_body(workplace)
    bad["detected_at"] = "not-a-date"
    assert client.post("/api/v1/attendance/events", json=bad, headers=h).status_code == 422


INJECTIONS = ["' OR '1'='1", "'; DROP TABLE users; --", "%' UNION SELECT password_hash FROM users --", "\\x00", "1; SELECT pg_sleep(5)"]


@pytest.mark.parametrize("payload", INJECTIONS)
def test_sql_injection_strings_are_harmless(client, admin_tokens, db, payload):
    h = auth(admin_tokens)
    r = client.get("/api/v1/admin/employees", params={"search": payload}, headers=h)
    assert r.status_code == 200 and r.json()["total"] == 0
    assert client.get("/api/v1/admin/audit-logs", params={"search": payload}, headers=h).status_code == 200
    assert client.get("/api/v1/admin/devices", params={"search": payload}, headers=h).status_code == 200
    assert client.get(f"/api/v1/admin/employees/{payload}", headers=h).status_code in (404, 422)
    assert client.get("/api/v1/admin/attendance/sessions", params={"employee_id": payload}, headers=h).json()["total"] == 0
    r = client.post("/api/v1/auth/login", json={"identifier": payload, "password": payload})
    assert r.status_code == 401
    assert db.execute(select(func.count()).select_from(User)).scalar_one() == 1


def test_errors_never_contain_stack_traces(client, emp_tokens, monkeypatch):
    from app.services import attendance

    def boom(*a, **k):
        raise RuntimeError("SECRET internal detail /etc/passwd")

    monkeypatch.setattr(attendance, "today_summary", boom)
    r = client.get("/api/v1/attendance/today", headers=auth(emp_tokens))
    assert r.status_code == 500
    assert r.json()["error"]["code"] == "server_error"
    assert "SECRET" not in r.text and "Traceback" not in r.text and "RuntimeError" not in r.text
    r = client.post("/api/v1/auth/login", content=b"{not json", headers={"Content-Type": "application/json"})
    assert r.status_code == 422 and "Traceback" not in r.text
    assert client.get("/api/v1/does-not-exist").json()["error"]["code"] == "not_found"


def test_login_rate_limit_429_with_retry_after(db):
    from app.main import create_app

    settings = get_settings().model_copy(update={"rate_limit_login_per_minute": 3})
    with TestClient(create_app(settings), raise_server_exceptions=False) as c:
        codes = [c.post("/api/v1/auth/login", json={"identifier": "x@example.co.za", "password": "whatever-123"}).status_code for _ in range(4)]
        assert codes[:3] == [401, 401, 401] and codes[3] == 429
        r = c.post("/api/v1/auth/login", json={"identifier": "x@example.co.za", "password": "whatever-123"})
        assert r.json()["error"]["code"] == "rate_limited" and int(r.headers["Retry-After"]) >= 1


def test_secure_headers(client):
    r = client.get("/healthz")
    for h in ("X-Content-Type-Options", "X-Frame-Options", "Referrer-Policy", "X-Request-Id"):
        assert h in r.headers
    r = client.get("/api/v1/auth/me")
    assert r.headers["Cache-Control"] == "no-store"
    r = client.get("/admin/")
    assert r.status_code == 200 and "default-src 'self'" in r.headers["Content-Security-Policy"]


def test_cors_allow_list(db):
    from app.main import create_app

    settings = get_settings().model_copy(update={"cors_origins": ["https://dashboard.example.co.za"]})
    with TestClient(create_app(settings)) as c:
        ok = c.options("/api/v1/auth/login", headers={"Origin": "https://dashboard.example.co.za", "Access-Control-Request-Method": "POST"})
        assert ok.headers.get("access-control-allow-origin") == "https://dashboard.example.co.za"
        bad = c.options("/api/v1/auth/login", headers={"Origin": "https://evil.example", "Access-Control-Request-Method": "POST"})
        assert "access-control-allow-origin" not in bad.headers


def test_idempotency_key_replay(client, admin_tokens, db):
    h = {**auth(admin_tokens), "Idempotency-Key": str(uuid.uuid4())}
    a = client.post("/api/v1/admin/departments", json={"name": "Legal", "code": "LEG"}, headers=h)
    b = client.post("/api/v1/admin/departments", json={"name": "Legal", "code": "LEG"}, headers=h)
    assert a.status_code == b.status_code == 201 and a.json() == b.json()
    assert b.headers.get("Idempotent-Replayed") == "true"
    assert db.execute(select(func.count()).select_from(Department)).scalar_one() == 1
    c = client.post("/api/v1/admin/departments", json={"name": "Legal", "code": "LEG"}, headers=auth(admin_tokens))
    assert c.status_code == 409


def test_insecure_production_config_refused():
    from app.main import create_app

    bad = Settings(app_env="production", secret_key="dev-only-change-me-0000000000000000000000000000", database_url="postgresql+psycopg://meridian:meridian_dev@127.0.0.1/x", public_base_url="http://x")
    with pytest.raises(RuntimeError):
        create_app(bad)
    good = Settings(app_env="production", secret_key="Zr8k" * 12, database_url="postgresql+psycopg://app:pw@db.internal:5432/meridian", public_base_url="https://attendance.example.co.za", scheduler_enabled=False)
    assert create_app(good) is not None


def test_employee_cannot_touch_other_employees_records(client, emp_tokens, db, workplace, clock):
    from tests.conftest import make_user

    clock.local(MONDAY, "08:00")
    other = make_user(db)
    ot = login(client, other.email, PASSWORD)
    ev = client.post("/api/v1/attendance/events", json=event_body(workplace), headers=auth(ot)).json()["event"]
    h = auth(emp_tokens)
    assert client.post(f"/api/v1/attendance/events/{ev['id']}/confirm", json={"client_confirmation_id": str(uuid.uuid4()), "confirmed_at": "2026-11-16T06:00:00Z"}, headers=h).status_code == 404
    assert client.post(f"/api/v1/attendance/events/{ev['id']}/dismiss", json={"reason": "other"}, headers=h).status_code == 404


def test_health_ready_metrics_and_spa(client):
    assert client.get("/healthz").json()["status"] == "ok"
    r = client.get("/readyz")
    assert r.status_code == 200 and r.json() == {"status": "ready", "database": "ok", "migrations": "head", "scheduler": "disabled"}
    assert "meridian_http_requests_total" in client.get("/metrics").text
    assert client.get("/admin/").text.lower().startswith("<!doctype html")
    assert client.get("/admin/some/deep/route").status_code == 200  # index.html fallback
    assert client.get("/admin/js/api.js").headers["content-type"].startswith(("text/javascript", "application/javascript"))
    assert client.get("/admin/../app/core/config.py").status_code in (200, 404)
    assert "SECRET_KEY" not in client.get("/admin/..%2f..%2fcore%2fconfig.py").text
    assert client.get("/api/docs").status_code == 200


def test_scheduler_skips_when_lock_held():
    from sqlalchemy import text

    from app.db.session import get_engine
    from app.services.scheduler import ADVISORY_LOCK_KEY, run_once

    with get_engine().connect() as other:
        assert other.execute(text("SELECT pg_try_advisory_lock(:k)"), {"k": ADVISORY_LOCK_KEY}).scalar()
        assert run_once()["skipped"] == "locked"
        other.execute(text("SELECT pg_advisory_unlock(:k)"), {"k": ADVISORY_LOCK_KEY})
    assert "skipped" not in run_once()


def test_metrics_token_and_names(db):
    from app.main import create_app

    settings = get_settings().model_copy(update={"metrics_token": "metrics-test-token-123"})
    with TestClient(create_app(settings)) as c:
        assert c.get("/metrics").status_code == 401
        assert c.get("/metrics", headers={"Authorization": "Bearer wrong"}).status_code == 401
        body = c.get("/metrics", headers={"Authorization": "Bearer metrics-test-token-123"}).text
        for name in ("meridian_http_requests_total", "meridian_http_request_duration_seconds", "meridian_ready 1.0"):
            assert name in body


def test_cli_revoke_all_sessions(client, employee):
    from app.cli import main

    t = login(client, employee.email)
    assert main(["revoke-all-sessions"]) == 0
    assert client.get("/api/v1/auth/me", headers=auth(t)).status_code == 401
    assert client.post("/api/v1/auth/refresh", json={"refresh_token": t["refresh_token"]}).status_code == 401

"""Email delivery: provider selection, the Microsoft Graph sender and the admin endpoints."""
from __future__ import annotations

import base64
import json

import httpx
import pytest

from app.core.config import Settings, get_settings
from app.services import email as email_service
from tests.conftest import auth

GRAPH = {
    "ms_graph_tenant_id": "11111111-2222-3333-4444-555555555555",
    "ms_graph_client_id": "66666666-7777-8888-9999-000000000000",
    "ms_graph_client_secret": "not-a-real-secret",
    "email_sender_address": "attendance@meridiangroup.co.za",
}
SMTP = {"smtp_host": "smtp.example.co.za", "smtp_from": "attendance@meridiangroup.co.za"}


def settings(**overrides) -> Settings:
    return get_settings().model_copy(update=overrides)


# --- provider selection matrix ------------------------------------------------
@pytest.mark.parametrize(
    "provider,config,expected",
    [
        ("auto", {}, "none"),
        ("auto", SMTP, "smtp"),
        ("auto", GRAPH, "graph"),
        ("auto", {**GRAPH, **SMTP}, "graph"),  # Graph wins when both are configured
        ("graph", GRAPH, "graph"),
        ("graph", SMTP, "none"),  # explicitly Graph but not configured: send nothing
        ("graph", {}, "none"),
        ("smtp", SMTP, "smtp"),
        ("smtp", GRAPH, "none"),
        ("none", {**GRAPH, **SMTP}, "none"),
    ],
)
def test_provider_selection(provider, config, expected):
    s = settings(email_provider=provider, **config)
    assert email_service.active_provider(s) == expected
    assert email_service.email_configured(s) is (expected != "none")


def test_status_payload():
    s = settings(email_provider="auto", **GRAPH)
    assert email_service.status(s) == {"provider": "graph", "configured": True, "sender": GRAPH["email_sender_address"]}
    assert email_service.status(settings(email_provider="none"))["sender"] == ""


def test_graph_needs_every_setting():
    for missing in GRAPH:
        partial = {k: v for k, v in GRAPH.items() if k != missing}
        assert email_service.graph_configured(settings(email_provider="auto", **partial)) is False


# --- Microsoft Graph sender ---------------------------------------------------
class GraphStub:
    """Monkeypatched httpx transport standing in for login.microsoftonline.com + Graph."""

    def __init__(self, *, send_status: int = 202, token_status: int = 200, expires_in: int = 3600):
        self.send_status = send_status
        self.token_status = token_status
        self.expires_in = expires_in
        self.token_requests: list[dict] = []
        self.sends: list[httpx.Request] = []
        self.send_statuses: list[int] = []

    def handler(self, request: httpx.Request) -> httpx.Response:
        if "login.microsoftonline.com" in request.url.host:
            self.token_requests.append(dict(httpx.QueryParams(request.content.decode())))
            if self.token_status != 200:
                return httpx.Response(self.token_status, json={"error": "invalid_client"})
            return httpx.Response(200, json={"access_token": f"tok-{len(self.token_requests)}", "expires_in": self.expires_in, "token_type": "Bearer"})
        self.sends.append(request)
        status = self.send_statuses.pop(0) if self.send_statuses else self.send_status
        if status == 429:
            return httpx.Response(429, json={"error": {"code": "ApplicationThrottled"}}, headers={"Retry-After": "12"})
        if status == 401:
            return httpx.Response(401, json={"error": {"code": "InvalidAuthenticationToken"}})
        return httpx.Response(status)


@pytest.fixture
def graph(monkeypatch):
    stub = GraphStub()
    real_client = httpx.Client

    def fake_client(*args, **kwargs):
        kwargs.pop("timeout", None)
        return real_client(*args, transport=httpx.MockTransport(stub.handler), **kwargs)

    monkeypatch.setattr(email_service.httpx, "Client", fake_client)
    monkeypatch.setattr(email_service, "get_settings", lambda: settings(email_provider="graph", **GRAPH))
    email_service.reset_token_cache()
    yield stub
    email_service.reset_token_cache()


def sent_mime(request: httpx.Request) -> str:
    return base64.b64decode(request.content).decode("utf-8", "replace")


def test_graph_builds_the_expected_request(graph):
    assert email_service.send_email(to="thandi@example.co.za", subject="Hello", text_body="Plain words", html_body="<p>Rich words</p>") is True

    assert graph.token_requests == [{
        "client_id": GRAPH["ms_graph_client_id"],
        "client_secret": GRAPH["ms_graph_client_secret"],
        "scope": "https://graph.microsoft.com/.default",
        "grant_type": "client_credentials",
    }]
    request = graph.sends[0]
    assert str(request.url) == f"https://graph.microsoft.com/v1.0/users/{GRAPH['email_sender_address']}/sendMail"
    assert request.headers["authorization"] == "Bearer tok-1"
    mime = sent_mime(request)
    assert "Subject: Hello" in mime
    assert "To: thandi@example.co.za" in mime
    assert f"Meridian Attendance <{GRAPH['email_sender_address']}>" in mime
    assert "multipart/alternative" in mime
    assert "Plain words" in mime and "Rich words" in mime


def test_graph_caches_the_token_across_sends(graph):
    for _ in range(3):
        assert email_service.send_email(to="a@example.co.za", subject="s", text_body="t") is True
    assert len(graph.token_requests) == 1
    assert len(graph.sends) == 3


def test_graph_refreshes_a_token_that_is_about_to_expire(graph):
    graph.expires_in = 60  # inside the safety margin, so it is never considered fresh
    email_service.send_email(to="a@example.co.za", subject="s", text_body="t")
    email_service.send_email(to="a@example.co.za", subject="s", text_body="t")
    assert len(graph.token_requests) == 2


def test_graph_retries_once_after_a_401(graph):
    graph.send_statuses = [401, 202]
    assert email_service.send_email(to="a@example.co.za", subject="s", text_body="t") is True
    assert len(graph.sends) == 2
    assert graph.sends[0].headers["authorization"] == "Bearer tok-1"
    assert graph.sends[1].headers["authorization"] == "Bearer tok-2"


def test_graph_gives_up_after_a_second_401(graph):
    graph.send_statuses = [401, 401]
    assert email_service.send_email(to="a@example.co.za", subject="s", text_body="t") is False


def test_graph_throttling_is_reported_not_raised(graph, caplog):
    graph.send_status = 429
    assert email_service.send_email(to="a@example.co.za", subject="s", text_body="t") is False
    assert "ApplicationThrottled" in caplog.text and "retry after 12s" in caplog.text


def test_graph_token_failure_never_logs_the_secret(graph, caplog):
    graph.token_status = 401
    assert email_service.send_email(to="a@example.co.za", subject="s", text_body="t") is False
    assert GRAPH["ms_graph_client_secret"] not in caplog.text
    assert "invalid_client" in caplog.text


# --- SMTP path ----------------------------------------------------------------
def test_smtp_path_sends_multipart(monkeypatch):
    captured = {}

    class FakeSMTP:
        def __init__(self, host, port, timeout=None):
            captured["host"], captured["port"] = host, port

        def __enter__(self):
            return self

        def __exit__(self, *a):
            return False

        def starttls(self):
            captured["tls"] = True

        def login(self, user, password):
            captured["login"] = user

        def send_message(self, msg):
            captured["msg"] = msg

    monkeypatch.setattr(email_service.smtplib, "SMTP", FakeSMTP)
    monkeypatch.setattr(email_service, "get_settings", lambda: settings(email_provider="smtp", smtp_username="relay", smtp_password="x", **SMTP))
    assert email_service.send_email(to="b@example.co.za", subject="Subject line", text_body="Plain", html_body="<p>Rich</p>") is True
    assert captured["host"] == SMTP["smtp_host"] and captured["port"] == 587 and captured["tls"] is True
    msg = captured["msg"]
    assert msg["To"] == "b@example.co.za" and msg["Subject"] == "Subject line"
    assert msg.get_content_type() == "multipart/alternative"
    assert {p.get_content_type() for p in msg.iter_parts()} == {"text/plain", "text/html"}


def test_nothing_configured_returns_false(monkeypatch):
    monkeypatch.setattr(email_service, "get_settings", lambda: settings(email_provider="auto", smtp_host=None, ms_graph_tenant_id=None))
    assert email_service.send_email(to="c@example.co.za", subject="s", text_body="t") is False


def test_html_is_derived_when_only_text_is_supplied(monkeypatch):
    captured = {}
    monkeypatch.setattr(email_service, "_send_via_smtp", lambda s, **kw: bool(captured.update(kw)) or True)
    monkeypatch.setattr(email_service, "get_settings", lambda: settings(email_provider="smtp", **SMTP))
    email_service.send_email(to="d@example.co.za", subject="s", text_body="First line\n\nSecond <b>line</b>")
    assert "&lt;b&gt;" in captured["html_body"] and "<!doctype html>" in captured["html_body"]


# --- message templates --------------------------------------------------------
def test_templates_carry_the_required_wording():
    subject, text, html = email_service.invitation_message(first_name="Thandi", link="https://x.test/app/invite?token=abc", expiry_days=7)
    assert subject == "Set up your Meridian Attendance account"
    for body in (text, html):
        assert "Thandi" in body and "7 days" in body and "tell your manager" in body
    assert "https://x.test/app/invite?token=abc" in text and "https://x.test/app/invite?token=abc" in html

    subject, text, html = email_service.password_reset_message(first_name="Thandi", link="https://x.test/app/reset?token=abc", expiry_minutes=30)
    assert subject == "Reset your Meridian Attendance password"
    for body in (text, html):
        assert "Thandi" in body and "30 minutes" in body and "tell your manager" in body


# --- admin endpoints ----------------------------------------------------------
def test_email_status_endpoint(client, admin_tokens):
    r = client.get("/api/v1/admin/email/status", headers=auth(admin_tokens))
    assert r.status_code == 200
    assert r.json() == {"provider": "none", "configured": False, "sender": ""}


def test_email_status_requires_permission(client, emp_tokens, manager_tokens):
    assert client.get("/api/v1/admin/email/status", headers=auth(emp_tokens)).status_code == 403
    assert client.get("/api/v1/admin/email/status", headers=auth(manager_tokens)).status_code == 403
    assert client.get("/api/v1/admin/email/status").status_code == 401


def test_email_test_without_configuration(client, admin_tokens):
    r = client.post("/api/v1/admin/email/test", headers=auth(admin_tokens))
    assert r.status_code == 200 and r.json()["sent"] is False
    assert "not configured" in r.json()["detail"]


def test_email_test_sends_to_the_caller(client, admin_tokens, admin_user, monkeypatch):
    outbox = []
    monkeypatch.setattr(email_service, "email_configured", lambda *a, **k: True)
    monkeypatch.setattr(email_service, "active_provider", lambda *a, **k: "graph")
    monkeypatch.setattr(email_service, "send_email", lambda **kw: bool(outbox.append(kw)) or True)
    r = client.post("/api/v1/admin/email/test", headers=auth(admin_tokens))
    assert r.status_code == 200 and r.json()["sent"] is True
    assert outbox[0]["to"] == admin_user.email and admin_user.first_name in outbox[0]["text_body"]
    assert "graph" in r.json()["detail"]


def test_email_test_requires_permission(client, emp_tokens):
    assert client.post("/api/v1/admin/email/test", headers=auth(emp_tokens)).status_code == 403


# --- deep links ---------------------------------------------------------------
def test_invite_page(client):
    r = client.get("/app/invite", params={"token": "abc123xyz"})
    assert r.status_code == 200 and r.headers["content-type"].startswith("text/html")
    assert "Open in the Meridian Attendance app" in r.text
    assert "abc123xyz" in r.text and "intent://invite?token=abc123xyz" in r.text


def test_reset_page_falls_back_to_the_dashboard(client):
    r = client.get("/app/reset", params={"token": "abc123xyz"})
    assert r.status_code == 200
    assert "Open in the Meridian Attendance app" in r.text
    assert "Continue in your browser" in r.text
    assert "/admin/#/reset-password?token=abc123xyz" in r.text


def test_deep_link_without_a_token(client):
    r = client.get("/app/invite")
    assert r.status_code == 200 and "missing its token" in r.text


def test_assetlinks_404_when_unset(client):
    assert client.get("/.well-known/assetlinks.json").status_code == 404


def test_assetlinks_served_when_set(client, app, monkeypatch):
    payload = [{"relation": ["delegate_permission/common.handle_all_urls"], "target": {"namespace": "android_app", "package_name": "za.co.meridiangroup.attendance", "sha256_cert_fingerprints": ["AA:BB"]}}]
    monkeypatch.setattr(app.state.settings, "android_asset_links_json", json.dumps(payload))
    r = client.get("/.well-known/assetlinks.json")
    assert r.status_code == 200 and r.json() == payload


def test_assetlinks_404_when_the_json_is_broken(client, app, monkeypatch):
    monkeypatch.setattr(app.state.settings, "android_asset_links_json", "{not json")
    assert client.get("/.well-known/assetlinks.json").status_code == 404

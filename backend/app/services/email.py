"""Outbound email (invitations, password reset).

Two senders are supported:

* **Microsoft Graph** (primary for Meridian's Microsoft 365) — client-credentials
  OAuth2 plus ``POST /v1.0/users/{sender}/sendMail``. Microsoft disables SMTP basic
  authentication by default for existing tenants at the end of December 2026 and
  newer tenants cannot turn it on at all, so Graph is the path that keeps working.
* **SMTP** — unchanged, for any other provider (or a relay that uses an app password).

When nothing is configured the message is logged (without the secret link) and
``False`` is returned, so the administrator temporary-password path remains the
fallback. Secrets and access tokens are never logged.
"""
from __future__ import annotations

import base64
import logging
import smtplib
import threading
from email.message import EmailMessage
from typing import Any
from urllib.parse import quote

import httpx

from app.core.config import Settings, get_settings
from app.core.timeutil import now_utc

log = logging.getLogger("meridian.email")

GRAPH_SCOPE = "https://graph.microsoft.com/.default"
GRAPH_TOKEN_URL = "https://login.microsoftonline.com/{tenant}/oauth2/v2.0/token"
GRAPH_SENDMAIL_URL = "https://graph.microsoft.com/v1.0/users/{sender}/sendMail"
# Refresh a little before the token actually expires so an in-flight send never
# races the expiry.
TOKEN_SAFETY_MARGIN_SECONDS = 120


# --- provider selection -------------------------------------------------------
def graph_configured(settings: Settings | None = None) -> bool:
    s = settings or get_settings()
    return bool(s.ms_graph_tenant_id and s.ms_graph_client_id and s.ms_graph_client_secret and s.email_sender_address)


def smtp_configured(settings: Settings | None = None) -> bool:
    return bool((settings or get_settings()).smtp_host)


def active_provider(settings: Settings | None = None) -> str:
    """Return ``graph``, ``smtp`` or ``none`` for the current configuration."""
    s = settings or get_settings()
    if s.email_provider == "none":
        return "none"
    if s.email_provider == "graph":
        return "graph" if graph_configured(s) else "none"
    if s.email_provider == "smtp":
        return "smtp" if smtp_configured(s) else "none"
    if graph_configured(s):
        return "graph"
    if smtp_configured(s):
        return "smtp"
    return "none"


def email_configured(settings: Settings | None = None) -> bool:
    return active_provider(settings) != "none"


def sender_address(settings: Settings | None = None) -> str:
    s = settings or get_settings()
    if active_provider(s) == "graph":
        return s.email_sender_address or ""
    return s.email_sender_address or s.smtp_from


def status(settings: Settings | None = None) -> dict[str, Any]:
    s = settings or get_settings()
    provider = active_provider(s)
    return {"provider": provider, "configured": provider != "none", "sender": sender_address(s) if provider != "none" else ""}


# --- Microsoft Graph ----------------------------------------------------------
class _TokenCache:
    """Process-wide cache of the Graph application token."""

    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._token: str | None = None
        self._expires_at: float = 0.0
        self._key: tuple[str, str] | None = None

    def clear(self) -> None:
        with self._lock:
            self._token, self._expires_at, self._key = None, 0.0, None

    def get(self, settings: Settings) -> str:
        key = (settings.ms_graph_tenant_id or "", settings.ms_graph_client_id or "")
        now = now_utc().timestamp()
        with self._lock:
            if self._token and self._key == key and now < self._expires_at:
                return self._token
        token, expires_in = _request_graph_token(settings)
        with self._lock:
            self._token = token
            self._key = key
            # A token whose life is shorter than the margin is simply not cached.
            self._expires_at = now + expires_in - TOKEN_SAFETY_MARGIN_SECONDS
        return token


_token_cache = _TokenCache()


def reset_token_cache() -> None:
    """Drop the cached Graph token (used by tests and after a config change)."""
    _token_cache.clear()


def _request_graph_token(settings: Settings) -> tuple[str, int]:
    url = GRAPH_TOKEN_URL.format(tenant=settings.ms_graph_tenant_id)
    data = {
        "client_id": settings.ms_graph_client_id or "",
        "client_secret": settings.ms_graph_client_secret or "",
        "scope": GRAPH_SCOPE,
        "grant_type": "client_credentials",
    }
    with httpx.Client(timeout=settings.email_timeout_seconds) as client:
        response = client.post(url, data=data)
    if response.status_code != 200:
        # The body can echo back request details; log only the status and the
        # error code Microsoft returns, never the secret.
        raise GraphError(f"token request failed with HTTP {response.status_code} ({_error_code(response)})")
    payload = response.json()
    token = payload.get("access_token")
    if not token:
        raise GraphError("token response contained no access_token")
    return token, int(payload.get("expires_in") or 3600)


def _error_code(response: httpx.Response) -> str:
    try:
        body = response.json()
    except Exception:
        return "unparseable body"
    if isinstance(body, dict):
        err = body.get("error")
        if isinstance(err, dict):
            return str(err.get("code") or "unknown")
        if isinstance(err, str):
            return err
    return "unknown"


class GraphError(RuntimeError):
    """A Microsoft Graph call failed; the message never contains secrets."""


def build_mime(*, sender: str, sender_name: str, to: str, subject: str, text_body: str, html_body: str) -> EmailMessage:
    """A multipart/alternative message carrying both the text and the HTML body."""
    msg = EmailMessage()
    msg["From"] = f"{sender_name} <{sender}>" if sender_name else sender
    msg["To"] = to
    msg["Subject"] = subject
    msg.set_content(text_body)
    msg.add_alternative(html_body, subtype="html")
    return msg


def _send_via_graph(settings: Settings, *, to: str, subject: str, text_body: str, html_body: str) -> bool:
    sender = settings.email_sender_address or ""
    try:
        # Graph's JSON message carries a single body; posting a MIME document to the
        # same endpoint is how both a plain-text and an HTML alternative get sent.
        mime = build_mime(sender=sender, sender_name=settings.email_sender_name, to=to, subject=subject, text_body=text_body, html_body=html_body)
        content = base64.b64encode(mime.as_bytes()).decode("ascii")
        url = GRAPH_SENDMAIL_URL.format(sender=quote(sender, safe="@"))

        def _post(bearer: str) -> httpx.Response:
            with httpx.Client(timeout=settings.email_timeout_seconds) as client:
                return client.post(url, content=content, headers={"Authorization": f"Bearer {bearer}", "Content-Type": "text/plain"})

        token = _token_cache.get(settings)
        response = _post(token)
        if response.status_code == 401:
            # Token revoked or rotated: drop the cache and try once more.
            reset_token_cache()
            response = _post(_token_cache.get(settings))
        if response.status_code in (200, 202):
            log.info("email sent via Graph to %s (%s)", to, subject)
            return True
        retry_after = response.headers.get("retry-after")
        log.error(
            "Graph sendMail to %s failed: HTTP %s (%s)%s",
            to, response.status_code, _error_code(response),
            f", retry after {retry_after}s" if retry_after else "",
        )
        return False
    except GraphError as exc:
        log.error("Graph email to %s not sent: %s", to, exc)
        return False
    except httpx.HTTPError as exc:
        log.error("Graph email to %s not sent: %s", to, type(exc).__name__)
        return False


# --- SMTP ---------------------------------------------------------------------
def _send_via_smtp(settings: Settings, *, to: str, subject: str, text_body: str, html_body: str) -> bool:
    sender = settings.email_sender_address or settings.smtp_from
    msg = build_mime(sender=sender, sender_name=settings.email_sender_name, to=to, subject=subject, text_body=text_body, html_body=html_body)
    try:
        with smtplib.SMTP(settings.smtp_host, settings.smtp_port, timeout=int(settings.email_timeout_seconds)) as server:
            if settings.smtp_use_tls:
                server.starttls()
            if settings.smtp_username:
                server.login(settings.smtp_username, settings.smtp_password or "")
            server.send_message(msg)
        log.info("email sent via SMTP to %s (%s)", to, subject)
        return True
    except Exception:  # pragma: no cover - depends on external server
        log.exception("failed to send email to %s via SMTP", to)
        return False


# --- public API ---------------------------------------------------------------
def send_email(*, to: str, subject: str, text_body: str, html_body: str | None = None) -> bool:
    """Send one message. Returns True when a provider accepted it."""
    settings = get_settings()
    provider = active_provider(settings)
    if provider == "none":
        log.info("email not configured; message to %s with subject %r not sent", to, subject)
        return False
    html = html_body or _html_from_text(text_body)
    if provider == "graph":
        return _send_via_graph(settings, to=to, subject=subject, text_body=text_body, html_body=html)
    return _send_via_smtp(settings, to=to, subject=subject, text_body=text_body, html_body=html)


def _escape(value: str) -> str:
    return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")


def _html_from_text(text_body: str) -> str:
    paragraphs = "".join(f"<p>{_escape(block).replace(chr(10), '<br>')}</p>" for block in text_body.split("\n\n") if block.strip())
    return _wrap_html(paragraphs)


_HTML_SHELL = """<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1"></head>
<body style="margin:0;padding:24px;background:#f4f5f7;font-family:-apple-system,Segoe UI,Roboto,Helvetica,Arial,sans-serif;color:#1f2933;">
<table role="presentation" width="100%" cellpadding="0" cellspacing="0"><tr><td align="center">
<table role="presentation" width="100%" style="max-width:560px;background:#ffffff;border-radius:8px;padding:28px;" cellpadding="0" cellspacing="0"><tr><td style="font-size:15px;line-height:1.55;">
{content}
<p style="margin-top:28px;font-size:12px;color:#7b8794;">Meridian Attendance · automated message, please do not reply.</p>
</td></tr></table>
</td></tr></table>
</body></html>"""


def _wrap_html(content: str) -> str:
    return _HTML_SHELL.replace("{content}", content)


def _button(url: str, label: str) -> str:
    return (
        f'<p style="margin:26px 0;"><a href="{_escape(url)}" '
        'style="background:#1f6feb;color:#ffffff;text-decoration:none;padding:12px 22px;border-radius:6px;display:inline-block;font-weight:600;">'
        f"{_escape(label)}</a></p>"
        f'<p style="font-size:13px;color:#52606d;">If the button does not work, copy this link into your browser:<br>'
        f'<a href="{_escape(url)}" style="color:#1f6feb;word-break:break-all;">{_escape(url)}</a></p>'
    )


# --- message templates --------------------------------------------------------
INVITATION_SUBJECT = "Set up your Meridian Attendance account"
RESET_SUBJECT = "Reset your Meridian Attendance password"


def invitation_message(*, first_name: str, link: str, expiry_days: int) -> tuple[str, str, str]:
    """Return (subject, text_body, html_body) for an invitation."""
    days = "1 day" if expiry_days == 1 else f"{expiry_days} days"
    text = (
        f"Hello {first_name},\n\n"
        "An account has been created for you on Meridian Attendance, the app that records your "
        "arrival and departure at work.\n\n"
        "Open the link below to choose your password and confirm your mobile number. "
        f"The link works once and expires in {days}.\n\n"
        f"{link}\n\n"
        "If you were not expecting this, please tell your manager.\n"
    )
    html = _wrap_html(
        f"<p>Hello {_escape(first_name)},</p>"
        "<p>An account has been created for you on <strong>Meridian Attendance</strong>, "
        "the app that records your arrival and departure at work.</p>"
        "<p>Open the link below to choose your password and confirm your mobile number. "
        f"The link works once and expires in {days}.</p>"
        + _button(link, "Set up my account")
        + "<p>If you were not expecting this, please tell your manager.</p>"
    )
    return INVITATION_SUBJECT, text, html


def password_reset_message(*, first_name: str, link: str, expiry_minutes: int) -> tuple[str, str, str]:
    """Return (subject, text_body, html_body) for a password reset."""
    text = (
        f"Hello {first_name},\n\n"
        "A password reset was requested for your Meridian Attendance account.\n\n"
        f"Open the link below to choose a new password. The link works once and expires in {expiry_minutes} minutes.\n\n"
        f"{link}\n\n"
        "If you did not ask for this, you can ignore this email — your password has not changed. "
        "If you were not expecting it at all, please tell your manager.\n"
    )
    html = _wrap_html(
        f"<p>Hello {_escape(first_name)},</p>"
        "<p>A password reset was requested for your <strong>Meridian Attendance</strong> account.</p>"
        f"<p>Open the link below to choose a new password. The link works once and expires in {expiry_minutes} minutes.</p>"
        + _button(link, "Choose a new password")
        + "<p>If you did not ask for this, you can ignore this email — your password has not changed. "
        "If you were not expecting it at all, please tell your manager.</p>"
    )
    return RESET_SUBJECT, text, html


def test_message(*, first_name: str) -> tuple[str, str, str]:
    subject = "Meridian Attendance — email test"
    text = (
        f"Hello {first_name},\n\n"
        "This is a test message from the Meridian Attendance server. If you are reading it, "
        "invitations and password-reset emails will reach your employees.\n\n"
        "No action is needed.\n"
    )
    html = _wrap_html(
        f"<p>Hello {_escape(first_name)},</p>"
        "<p>This is a test message from the Meridian Attendance server. If you are reading it, "
        "invitations and password-reset emails will reach your employees.</p>"
        "<p>No action is needed.</p>"
    )
    return subject, text, html

"""Outbound email (password reset). Uses SMTP when configured; otherwise the
message is logged (without the secret link) so the administrator temporary
password path remains the fallback."""
from __future__ import annotations

import logging
import smtplib
from email.message import EmailMessage

from app.core.config import get_settings

log = logging.getLogger("meridian.email")


def smtp_configured() -> bool:
    return bool(get_settings().smtp_host)


def send_email(*, to: str, subject: str, body: str) -> bool:
    """Send a plain-text email. Returns True when handed to an SMTP server."""
    settings = get_settings()
    if not settings.smtp_host:
        log.info("SMTP not configured; email to %s with subject %r not sent", to, subject)
        return False
    msg = EmailMessage()
    msg["From"] = settings.smtp_from
    msg["To"] = to
    msg["Subject"] = subject
    msg.set_content(body)
    try:
        with smtplib.SMTP(settings.smtp_host, settings.smtp_port, timeout=15) as server:
            if settings.smtp_use_tls:
                server.starttls()
            if settings.smtp_username:
                server.login(settings.smtp_username, settings.smtp_password or "")
            server.send_message(msg)
        log.info("email sent to %s (%s)", to, subject)
        return True
    except Exception:  # pragma: no cover - depends on external server
        log.exception("failed to send email to %s", to)
        return False

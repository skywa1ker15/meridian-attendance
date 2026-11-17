"""Firebase Cloud Messaging (HTTP v1) data messages.

Only active when ``FCM_ENABLED=true`` and a service-account file is configured.
Everything here is best-effort: a push failure never fails the request that
created the notification (the in-app inbox is the source of truth).
"""
from __future__ import annotations

import logging
import os
import threading
from typing import Any

from app.core.config import get_settings

log = logging.getLogger("meridian.fcm")

_SCOPE = "https://www.googleapis.com/auth/firebase.messaging"
_lock = threading.Lock()
_credentials: Any = None


def configured() -> bool:
    s = get_settings()
    return bool(s.fcm_enabled and s.fcm_project_id and s.fcm_service_account_file and os.path.exists(s.fcm_service_account_file))


def _access_token() -> str:
    global _credentials
    from google.auth.transport.requests import (
        Request as GoogleRequest,  # imported lazily
    )
    from google.oauth2 import service_account

    with _lock:
        if _credentials is None:
            _credentials = service_account.Credentials.from_service_account_file(get_settings().fcm_service_account_file, scopes=[_SCOPE])
        if not _credentials.valid:
            _credentials.refresh(GoogleRequest())
        return _credentials.token


def send_data_message(token: str, data: dict[str, str]) -> str:
    """Send one FCM data message. Returns ``sent``, ``skipped`` or ``failed``."""
    if not configured():
        return "skipped"
    import requests  # lazily imported; optional dependency path

    settings = get_settings()
    url = f"https://fcm.googleapis.com/v1/projects/{settings.fcm_project_id}/messages:send"
    payload = {
        "message": {
            "token": token,
            "data": {k: str(v) for k, v in data.items() if v is not None},
            "android": {"priority": "high"},
        }
    }
    try:
        resp = requests.post(url, json=payload, headers={"Authorization": f"Bearer {_access_token()}"}, timeout=10)
        if resp.status_code == 200:
            return "sent"
        log.warning("FCM send failed: %s %s", resp.status_code, resp.text[:300])
        return "failed"
    except Exception:
        log.exception("FCM send raised")
        return "failed"

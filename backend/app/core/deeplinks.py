"""The two public pages the emailed links point at.

Both try to hand the token straight to the Android app (an Android App Link opens
the app directly once ``/.well-known/assetlinks.json`` is published; the
``intent://`` fallback covers an app installed before verification). If the app is
not there, the page offers the browser route instead.
"""
from __future__ import annotations

import html
import json
from urllib.parse import quote

from app.core.config import Settings

_STYLE = """
:root{color-scheme:light dark}
*{box-sizing:border-box}
body{margin:0;min-height:100vh;display:flex;align-items:center;justify-content:center;padding:24px;
 font-family:-apple-system,Segoe UI,Roboto,Helvetica,Arial,sans-serif;background:#f4f5f7;color:#1f2933}
.card{background:#fff;max-width:420px;width:100%;border-radius:12px;padding:28px;box-shadow:0 1px 3px rgba(0,0,0,.12)}
h1{font-size:20px;margin:0 0 8px}
p{font-size:15px;line-height:1.55;color:#3e4c59;margin:0 0 14px}
.btn{display:block;text-align:center;background:#1f6feb;color:#fff;text-decoration:none;padding:13px 18px;
 border-radius:8px;font-weight:600;margin:18px 0 10px}
.alt{display:block;text-align:center;color:#1f6feb;text-decoration:none;font-size:14px;padding:10px}
.muted{font-size:13px;color:#7b8794;margin-top:18px}
@media (prefers-color-scheme:dark){body{background:#12161c;color:#e4e7eb}.card{background:#1c2129}p{color:#b6bec9}}
"""

_PAGE = """<!doctype html>
<html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="robots" content="noindex">
<title>{title}</title>
<style>{style}</style></head>
<body><div class="card">
<h1>{title}</h1>
<p>{intro}</p>
<a class="btn" id="open-app" href="{app_url}">Open in the Meridian Attendance app</a>
{fallback}
<p class="muted">{muted}</p>
</div>
<script>
(function () {{
  var url = {app_url_js};
  if (!url) return;
  // Try the app straight away; if it is not installed nothing happens and the
  // buttons above remain.
  window.setTimeout(function () {{ window.location.href = url; }}, 150);
}})();
</script>
</body></html>"""


def _app_url(settings: Settings, path: str, token: str) -> str:
    if not token:
        return ""
    return f"intent://{path}?token={quote(token, safe='')}#Intent;scheme=meridianattendance;package={settings.android_package_name};end"


def _render(*, title: str, intro: str, app_url: str, fallback: str, muted: str) -> str:
    return _PAGE.format(
        title=html.escape(title),
        style=_STYLE,
        intro=intro,
        app_url=html.escape(app_url, quote=True),
        app_url_js=json.dumps(app_url),
        fallback=fallback,
        muted=muted,
    )


def _missing_token(what: str) -> str:
    return _render(
        title="Link incomplete",
        intro=f"This {what} link is missing its token. Open the link straight from the email, or ask your manager to send a new one.",
        app_url="",
        fallback="",
        muted="Meridian Attendance",
    )


def invite_page(token: str, settings: Settings) -> str:
    if not token:
        return _missing_token("invitation")
    apk = (settings.apk_download_url or "").strip()
    install = (
        f'<a class="alt" href="{html.escape(apk, quote=True)}">Continue in your browser — download the app</a>'
        if apk
        else '<a class="alt" href="#" onclick="return false;">The app is not available for download here</a>'
    )
    fallback = install + (
        '<p class="muted">The link opens the app, where you choose your password and confirm your mobile number. '
        "If the app is not installed yet, install it first and open this link again.</p>"
    )
    return _render(
        title="Set up your Meridian Attendance account",
        intro="Tap the button to finish setting up your account in the Meridian Attendance app.",
        app_url=_app_url(settings, "invite", token),
        fallback=fallback,
        muted="The invitation can only be used once. If you were not expecting it, tell your manager.",
    )


def reset_page(token: str, settings: Settings) -> str:
    if not token:
        return _missing_token("password reset")
    browser = f"{settings.public_base_url.rstrip('/')}/admin/#/reset-password?token={quote(token, safe='')}"
    fallback = f'<a class="alt" href="{html.escape(browser, quote=True)}">Continue in your browser</a>'
    return _render(
        title="Reset your Meridian Attendance password",
        intro="Tap the button to choose a new password in the Meridian Attendance app, or continue in your browser.",
        app_url=_app_url(settings, "reset", token),
        fallback=fallback,
        muted="The link can only be used once and expires shortly. If you did not ask for it, tell your manager.",
    )

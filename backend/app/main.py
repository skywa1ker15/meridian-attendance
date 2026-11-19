"""Application factory for the Meridian Attendance API."""
from __future__ import annotations

import hmac
import json
import logging
from contextlib import asynccontextmanager
from pathlib import Path

from fastapi import FastAPI, Request, Response
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import FileResponse, HTMLResponse, JSONResponse, RedirectResponse
from sqlalchemy import text

from app.api.v1.router import api_router
from app.core import deeplinks
from app.core.config import Settings, get_settings
from app.core.errors import ApiError, install_error_handlers
from app.core.logging import configure_logging
from app.core.middleware import (
    IdempotencyMiddleware,
    MetricsMiddleware,
    RateLimitMiddleware,
    RequestContextMiddleware,
    ready_gauge,
)
from app.db.session import database_ok, get_engine
from app.services.idempotency import IdempotencyStore
from app.services.scheduler import Scheduler

log = logging.getLogger("meridian.app")

STATIC_ADMIN_DIR = Path(__file__).resolve().parent / "static" / "admin"
ALEMBIC_DIR = Path(__file__).resolve().parent.parent


def migration_head() -> str | None:
    """Head revision of the bundled Alembic scripts (None when unavailable)."""
    try:
        from alembic.config import Config
        from alembic.script import ScriptDirectory

        cfg = Config(str(ALEMBIC_DIR / "alembic.ini"))
        cfg.set_main_option("script_location", str(ALEMBIC_DIR / "alembic"))
        return ScriptDirectory.from_config(cfg).get_current_head()
    except Exception:  # pragma: no cover - alembic scripts missing in a broken image
        log.exception("could not read alembic head")
        return None


def database_revision() -> str | None:
    try:
        with get_engine().connect() as conn:
            row = conn.execute(text("SELECT version_num FROM alembic_version")).first()
            return row[0] if row else None
    except Exception:
        return None


def create_app(settings: Settings | None = None) -> FastAPI:
    settings = settings or get_settings()
    configure_logging(settings.log_level, settings.log_json)
    problems = settings.validate_for_environment()
    if problems:
        for p in problems:
            log.critical("configuration error: %s", p)
        raise RuntimeError("Refusing to start with insecure configuration: " + "; ".join(problems))

    scheduler = Scheduler(settings.scheduler_interval_seconds)

    @asynccontextmanager
    async def lifespan(app: FastAPI):
        if settings.scheduler_enabled:
            scheduler.start()
        yield
        scheduler.stop()

    docs_enabled = settings.app_env not in ("production", "staging")
    app = FastAPI(
        title=settings.app_name,
        version=settings.app_version,
        docs_url="/api/docs" if docs_enabled else None,
        redoc_url="/api/redoc" if docs_enabled else None,
        openapi_url="/api/openapi.json" if docs_enabled else None,
        lifespan=lifespan,
    )
    app.state.settings = settings
    app.state.scheduler = scheduler

    # Middleware (last added = outermost): RequestContext → CORS → RateLimit → Metrics → Idempotency
    app.add_middleware(IdempotencyMiddleware, settings=settings, store=IdempotencyStore())
    app.add_middleware(MetricsMiddleware)
    app.add_middleware(RateLimitMiddleware, settings=settings)
    if settings.cors_origins:
        app.add_middleware(
            CORSMiddleware,
            allow_origins=settings.cors_origins,
            allow_credentials=True,
            allow_methods=["GET", "POST", "PATCH", "PUT", "DELETE", "OPTIONS"],
            allow_headers=["Authorization", "Content-Type", "Idempotency-Key", "X-Request-Id"],
            expose_headers=["X-Request-Id", "Retry-After", "Content-Disposition"],
            max_age=600,
        )
    app.add_middleware(RequestContextMiddleware, settings=settings)

    install_error_handlers(app)
    app.include_router(api_router)

    # --- health & operations ---------------------------------------------------------
    @app.get("/healthz", include_in_schema=False)
    def healthz():
        return {"status": "ok", "version": settings.app_version}

    def readiness() -> dict:
        db_ok = database_ok()
        head = migration_head()
        current = database_revision() if db_ok else None
        migrations = "head" if head and current == head else ("unknown" if head is None else "behind")
        ready = db_ok and migrations == "head"
        ready_gauge().set(1 if ready else 0)
        return {"status": "ready" if ready else "not_ready", "database": "ok" if db_ok else "unavailable", "migrations": migrations, "scheduler": "running" if settings.scheduler_enabled else "disabled"}

    @app.get("/readyz", include_in_schema=False)
    def readyz():
        payload = readiness()
        return JSONResponse(payload, status_code=200 if payload["status"] == "ready" else 503)

    @app.get("/metrics", include_in_schema=False)
    def metrics(request: Request):
        from prometheus_client import CONTENT_TYPE_LATEST, generate_latest

        readiness()

        if settings.metrics_token:
            supplied = request.headers.get("authorization", "")
            if not hmac.compare_digest(supplied.encode(), f"Bearer {settings.metrics_token}".encode()):
                raise ApiError(401, "token_invalid", "Metrics token required.")
        return Response(generate_latest(), media_type=CONTENT_TYPE_LATEST)

    # --- admin dashboard (static SPA, hash routed) --------------------------------------
    if settings.serve_admin_dashboard and STATIC_ADMIN_DIR.is_dir():

        @app.get("/admin", include_in_schema=False)
        def admin_root():
            return RedirectResponse("/admin/", status_code=307)

        @app.get("/admin/{path:path}", include_in_schema=False)
        def admin_spa(path: str):
            base = STATIC_ADMIN_DIR.resolve()
            candidate = (base / path).resolve() if path else base / "index.html"
            if path and candidate.is_relative_to(base) and candidate.is_file():
                return FileResponse(candidate, headers={"Cache-Control": "no-cache"})
            return FileResponse(base / "index.html", headers={"Cache-Control": "no-cache"})

    # --- robots.txt (prevent search engines indexing the API / dashboard) -----------------
    _ROBOTS_TXT = "User-agent: *\nDisallow: /\n"

    @app.get("/robots.txt", include_in_schema=False)
    def robots_txt():
        return Response(_ROBOTS_TXT, media_type="text/plain")

    # --- deep links (invitation / password reset emails) ---------------------------------
    _DEEPLINK_CSP = "default-src 'none'; style-src 'unsafe-inline'; script-src 'unsafe-inline'"

    @app.get("/app/invite", include_in_schema=False)
    def app_invite(token: str = ""):
        return HTMLResponse(deeplinks.invite_page(token, settings), headers={"Cache-Control": "no-store", "Content-Security-Policy": _DEEPLINK_CSP})

    @app.get("/app/reset", include_in_schema=False)
    def app_reset(token: str = ""):
        return HTMLResponse(deeplinks.reset_page(token, settings), headers={"Cache-Control": "no-store", "Content-Security-Policy": _DEEPLINK_CSP})

    @app.get("/.well-known/assetlinks.json", include_in_schema=False)
    def asset_links():
        raw = (settings.android_asset_links_json or "").strip()
        if not raw:
            raise ApiError(404, "not_found", "Android asset links are not configured on this server.")
        try:
            payload = json.loads(raw)
        except json.JSONDecodeError:
            log.error("ANDROID_ASSET_LINKS_JSON is not valid JSON; serving 404")
            raise ApiError(404, "not_found", "Android asset links are not configured on this server.") from None
        return JSONResponse(payload, headers={"Cache-Control": "public, max-age=3600"})

    @app.get("/", include_in_schema=False)
    def root():
        return RedirectResponse("/admin/" if settings.serve_admin_dashboard else "/api/docs", status_code=307)

    return app


def get_app() -> FastAPI:
    """Factory entry point (``uvicorn app.main:get_app --factory``)."""
    return create_app()


# Module-level ASGI app for gunicorn/uvicorn (``app.main:app``). Building it validates
# settings but does not connect to the database (that happens per request / in /readyz).
app = create_app()

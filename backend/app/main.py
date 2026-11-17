"""Application factory for the Meridian Attendance API."""
from __future__ import annotations

import hmac
import logging
from contextlib import asynccontextmanager
from pathlib import Path

from fastapi import FastAPI, Request, Response
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import FileResponse, JSONResponse, RedirectResponse
from sqlalchemy import text

from app.api.v1.router import api_router
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

    docs_enabled = True
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

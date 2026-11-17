"""HTTP middleware: request ids & access logs, security headers, rate limiting, idempotency."""
from __future__ import annotations

import hashlib
import json
import logging
import threading
import time
import uuid
from collections import deque
from typing import Deque

from starlette.middleware.base import BaseHTTPMiddleware, RequestResponseEndpoint
from starlette.requests import Request
from starlette.responses import JSONResponse, Response

from app.core.config import Settings
from app.core.logging import Timer, request_id_var

log = logging.getLogger("meridian.http")


def client_ip(request: Request, settings: Settings) -> str:
    if settings.trust_proxy_headers:
        forwarded = request.headers.get("x-forwarded-for")
        if forwarded:
            return forwarded.split(",")[0].strip()
        real = request.headers.get("x-real-ip")
        if real:
            return real.strip()
    return request.client.host if request.client else "unknown"


class RequestContextMiddleware(BaseHTTPMiddleware):
    """Assigns a request id, logs an access line and adds security headers."""

    def __init__(self, app, settings: Settings):
        super().__init__(app)
        self.settings = settings

    async def dispatch(self, request: Request, call_next: RequestResponseEndpoint) -> Response:
        rid = request.headers.get("x-request-id") or uuid.uuid4().hex
        request.state.request_id = rid
        token = request_id_var.set(rid)
        timer = Timer()
        try:
            response = await call_next(request)
        finally:
            request_id_var.reset(token)
        response.headers["X-Request-Id"] = rid
        response.headers.setdefault("X-Content-Type-Options", "nosniff")
        response.headers.setdefault("X-Frame-Options", "DENY")
        response.headers.setdefault("Referrer-Policy", "strict-origin-when-cross-origin")
        response.headers.setdefault("Permissions-Policy", "geolocation=(), camera=(), microphone=()")
        if request.url.path.startswith("/api/"):
            response.headers.setdefault("Cache-Control", "no-store")
        elif request.url.path.startswith("/admin"):
            response.headers.setdefault(
                "Content-Security-Policy",
                "default-src 'self'; img-src 'self' data: blob:; style-src 'self' 'unsafe-inline'; script-src 'self'; connect-src 'self'; font-src 'self'; frame-ancestors 'none'; base-uri 'self'; form-action 'self'",
            )
        if self.settings.public_base_url.startswith("https://"):
            response.headers.setdefault("Strict-Transport-Security", "max-age=31536000; includeSubDomains")
        if not request.url.path.startswith(("/healthz", "/readyz", "/metrics")):
            log.info(
                "%s %s -> %s (%.1f ms)", request.method, request.url.path, response.status_code, timer.ms(),
                extra={"method": request.method, "path": request.url.path, "status": response.status_code, "duration_ms": timer.ms(), "client": client_ip(request, self.settings)},
            )
        return response


class _SlidingWindow:
    def __init__(self):
        self.events: dict[str, Deque[float]] = {}
        self.lock = threading.Lock()
        self.last_sweep = time.monotonic()

    def hit(self, key: str, limit: int, window_s: float = 60.0) -> tuple[bool, int]:
        now = time.monotonic()
        with self.lock:
            if now - self.last_sweep > 120:
                self._sweep(now, window_s)
            q = self.events.setdefault(key, deque())
            while q and q[0] <= now - window_s:
                q.popleft()
            if len(q) >= limit:
                retry = int(q[0] + window_s - now) + 1
                return False, retry
            q.append(now)
            return True, 0

    def _sweep(self, now: float, window_s: float) -> None:
        for key in list(self.events):
            q = self.events[key]
            while q and q[0] <= now - window_s:
                q.popleft()
            if not q:
                del self.events[key]
        self.last_sweep = now


class RateLimitMiddleware(BaseHTTPMiddleware):
    """Per-client-IP sliding-window limits. In-memory (per process) — put Caddy/nginx
    limits in front for multi-instance deployments (see deploy/README.md)."""

    def __init__(self, app, settings: Settings):
        super().__init__(app)
        self.settings = settings
        self.window = _SlidingWindow()

    def _limit_for(self, path: str) -> tuple[str, int]:
        s = self.settings
        if path.endswith("/auth/login"):
            return "login", s.rate_limit_login_per_minute
        if path.endswith("/auth/password/forgot") or path.endswith("/auth/password/reset"):
            return "forgot", s.rate_limit_forgot_per_minute
        if path.endswith("/auth/refresh"):
            return "refresh", s.rate_limit_refresh_per_minute
        if "/attendance/events" in path or path.endswith("/attendance/sync"):
            return "events", s.rate_limit_events_per_minute
        return "default", s.rate_limit_default_per_minute

    async def dispatch(self, request: Request, call_next: RequestResponseEndpoint) -> Response:
        if not self.settings.rate_limit_enabled or not request.url.path.startswith("/api/"):
            return await call_next(request)
        bucket, limit = self._limit_for(request.url.path)
        key = f"{bucket}:{client_ip(request, self.settings)}"
        allowed, retry_after = self.window.hit(key, limit)
        if not allowed:
            return JSONResponse(
                status_code=429,
                content={"error": {"code": "rate_limited", "message": "Too many requests. Please slow down.", "details": {"retry_after": retry_after}}},
                headers={"Retry-After": str(retry_after)},
            )
        return await call_next(request)


class IdempotencyMiddleware(BaseHTTPMiddleware):
    """Generic replay protection for mutating requests carrying ``Idempotency-Key``.

    The stored response is keyed by (authorization hash, key, method, path) and kept
    for ``idempotency_ttl_hours``. Attendance endpoints also carry explicit client ids
    in the body, so this is defence in depth rather than the only mechanism.
    """

    def __init__(self, app, settings: Settings, store):
        super().__init__(app)
        self.settings = settings
        self.store = store  # app.services.idempotency.IdempotencyStore

    async def dispatch(self, request: Request, call_next: RequestResponseEndpoint) -> Response:
        key = request.headers.get("idempotency-key")
        if not key or request.method not in ("POST", "PUT", "PATCH", "DELETE") or not request.url.path.startswith("/api/"):
            return await call_next(request)
        if len(key) > 128:
            return JSONResponse(status_code=400, content={"error": {"code": "validation_error", "message": "Idempotency-Key too long.", "details": {}}})
        auth = request.headers.get("authorization", "")
        scope = hashlib.sha256(auth.encode()).hexdigest()[:32]
        fingerprint = f"{scope}:{request.method}:{request.url.path}"
        cached = self.store.get(key, fingerprint)
        if cached is not None:
            status, body, content_type = cached
            return Response(content=body, status_code=status, media_type=content_type, headers={"Idempotent-Replayed": "true"})
        response = await call_next(request)
        content_type = response.headers.get("content-type", "")
        if "application/json" in content_type and response.status_code < 500:
            body = b""
            async for chunk in response.body_iterator:  # type: ignore[attr-defined]
                body += chunk
            self.store.put(key, fingerprint, response.status_code, body, content_type)
            return Response(content=body, status_code=response.status_code, media_type=content_type, headers=dict(response.headers))
        return response


def body_preview(data: bytes, limit: int = 2048) -> str:
    try:
        return json.dumps(json.loads(data))[:limit]
    except Exception:
        return data[:limit].decode("utf-8", "replace")


_METRICS = None


def ready_gauge():
    return _metrics()[2]


def _metrics():
    """Process-wide Prometheus collectors (created once; apps may be built many times)."""
    global _METRICS
    if _METRICS is None:
        from prometheus_client import Counter, Gauge, Histogram

        _METRICS = (
            Counter("meridian_http_requests_total", "HTTP requests", ["method", "route", "status"]),
            Histogram("meridian_http_request_duration_seconds", "HTTP request duration in seconds", ["method", "route"], buckets=(0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1, 2.5, 5)),
            Gauge("meridian_ready", "1 when the database is reachable and migrations are at head, else 0"),
        )
    return _METRICS


class MetricsMiddleware(BaseHTTPMiddleware):
    """Prometheus request counters/histograms (exposed at /metrics)."""

    def __init__(self, app):
        super().__init__(app)
        self.requests, self.latency, _ = _metrics()

    async def dispatch(self, request: Request, call_next: RequestResponseEndpoint) -> Response:
        start = time.perf_counter()
        response = await call_next(request)
        route = request.scope.get("route")
        template = getattr(route, "path", None) or ("/static" if request.url.path.startswith("/admin") else "unmatched")
        self.requests.labels(request.method, template, str(response.status_code)).inc()
        self.latency.labels(request.method, template).observe(time.perf_counter() - start)
        return response

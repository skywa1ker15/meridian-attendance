"""Application settings.

Every value can be supplied through environment variables (or a ``.env`` file in
development). Nothing secret is hard-coded; production refuses to start with
insecure defaults (see ``validate_for_environment``).
"""
from __future__ import annotations

import os
from functools import lru_cache
from typing import Annotated, Literal

from pydantic import Field, field_validator
from pydantic_settings import BaseSettings, NoDecode, SettingsConfigDict

INSECURE_SECRET_MARKERS = ("change-me", "changeme", "secret", "password", "dev-only")


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=os.environ.get("ENV_FILE", ".env"), env_file_encoding="utf-8", extra="ignore")

    # --- application ---------------------------------------------------------
    app_name: str = "Meridian Attendance API"
    app_version: str = "1.0.0"
    app_env: Literal["development", "test", "staging", "production"] = "development"
    log_level: str = "INFO"
    log_json: bool = True
    timezone: str = "Africa/Johannesburg"
    public_base_url: str = "http://localhost:8000"
    cors_origins: Annotated[list[str], NoDecode] = Field(default_factory=list)  # comma-separated in env
    trust_proxy_headers: bool = False  # set True only behind Caddy/nginx that sets X-Forwarded-For
    serve_admin_dashboard: bool = True

    # --- database ------------------------------------------------------------
    database_url: str = "postgresql+psycopg://meridian:meridian_dev@127.0.0.1:5432/meridian_attendance"
    db_pool_size: int = 10
    db_max_overflow: int = 20
    db_echo: bool = False

    # --- security ------------------------------------------------------------
    secret_key: str = "dev-only-change-me-0000000000000000000000000000"
    jwt_algorithm: Literal["HS256", "RS256"] = "HS256"
    jwt_private_key_path: str | None = None  # RS256 only
    jwt_public_key_path: str | None = None
    access_token_minutes: int = 15
    refresh_token_days: int = 30
    password_reset_minutes: int = 30
    password_min_length: int = 10
    login_max_failed_attempts: int = 5
    login_lockout_minutes: int = 15
    temporary_password_length: int = 12
    metrics_token: str | None = None
    # One-time code that lets the Android app create the FIRST administrator on an empty system
    # (POST /api/v1/setup/first-admin). Required in staging/production; once any admin exists the
    # endpoint is permanently closed regardless of this value. Remove it after setup.
    first_admin_setup_code: str | None = None
    idempotency_ttl_hours: int = 24

    # --- rate limiting (requests per minute per client IP) --------------------
    rate_limit_enabled: bool = True
    rate_limit_default_per_minute: int = 300
    rate_limit_login_per_minute: int = 10
    rate_limit_forgot_per_minute: int = 5
    rate_limit_refresh_per_minute: int = 30
    rate_limit_events_per_minute: int = 60
    rate_limit_email_test_per_minute: int = 3

    # --- scheduler -----------------------------------------------------------
    scheduler_enabled: bool = True
    scheduler_interval_seconds: int = 60
    confirmation_reminder_minutes: int = 10

    # --- push notifications (Firebase Cloud Messaging HTTP v1) ----------------
    fcm_enabled: bool = False
    fcm_project_id: str | None = None
    fcm_service_account_file: str | None = None  # path to service account JSON (never committed)

    # --- Play Integrity (optional) -------------------------------------------
    play_integrity_enabled: bool = False
    play_integrity_package_name: str = "za.co.meridiangroup.attendance"
    play_integrity_service_account_file: str | None = None

    # --- email (invitations, password reset) ---------------------------------
    # Microsoft disables SMTP basic authentication by default for existing tenants at the
    # end of December 2026 and newer tenants cannot enable it at all, so Microsoft Graph
    # (application permission Mail.Send) is the primary path for Meridian's Microsoft 365.
    # "auto" = Graph when it is configured, otherwise SMTP when it is, otherwise nothing.
    email_provider: Literal["auto", "graph", "smtp", "none"] = "auto"
    ms_graph_tenant_id: str | None = None
    ms_graph_client_id: str | None = None
    ms_graph_client_secret: str | None = None
    email_sender_address: str | None = None  # the mailbox Graph sends as
    email_sender_name: str = "Meridian Attendance"
    email_timeout_seconds: float = 15.0
    smtp_host: str | None = None
    smtp_port: int = 587
    smtp_username: str | None = None
    smtp_password: str | None = None
    smtp_use_tls: bool = True
    smtp_from: str = "attendance@meridiangroup.co.za"
    password_reset_url_template: str = "{public_base_url}/app/reset?token={token}"
    invitation_url_template: str = "{public_base_url}/app/invite?token={token}"
    invitation_expiry_days: int = 7

    # --- Android deep links ---------------------------------------------------
    # Raw JSON served at /.well-known/assetlinks.json so Android can verify App Links.
    android_asset_links_json: str = ""
    apk_download_url: str | None = None
    android_package_name: str = "za.co.meridiangroup.attendance"

    # --- observability -------------------------------------------------------
    sentry_dsn: str | None = None

    @field_validator("database_url", mode="before")
    @classmethod
    def _normalise_database_url(cls, value):
        """Accept the plain URLs managed hosts hand out and bind them to psycopg 3.

        Render, Heroku, Neon, Supabase and friends supply ``postgres://…`` or
        ``postgresql://…``. Without an explicit driver SQLAlchemy reaches for psycopg2,
        which this image does not ship, so the app would die on first connection.
        """
        if isinstance(value, str):
            for prefix in ("postgres://", "postgresql://"):
                if value.startswith(prefix):
                    return "postgresql+psycopg://" + value[len(prefix):]
        return value

    @field_validator("public_base_url", mode="before")
    @classmethod
    def _normalise_public_base_url(cls, value):
        """Accept a bare host and assume https, with no trailing slash.

        Platform-generated values are often just ``my-api.onrender.com``. Treating that
        as https is both what the platform serves and what the environment check demands,
        so a first deploy comes up instead of failing on a missing scheme.
        """
        if isinstance(value, str):
            v = value.strip().rstrip("/")
            if v and "://" not in v:
                v = "https://" + v
            return v
        return value

    @field_validator("cors_origins", mode="before")
    @classmethod
    def _split_origins(cls, value):  # noqa: D401
        if value is None or value == "":
            return []
        if isinstance(value, str):
            return [v.strip() for v in value.split(",") if v.strip()]
        return value

    @property
    def is_production(self) -> bool:
        return self.app_env == "production"

    def validate_for_environment(self) -> list[str]:
        """Return a list of fatal configuration problems for the current environment."""
        problems: list[str] = []
        if self.app_env in ("staging", "production"):
            lowered = self.secret_key.lower()
            if len(self.secret_key) < 32 or any(m in lowered for m in INSECURE_SECRET_MARKERS):
                problems.append("SECRET_KEY must be a random value of at least 32 characters in staging/production")
            if "meridian_dev" in self.database_url or "localhost" in self.database_url or "127.0.0.1" in self.database_url:
                problems.append("DATABASE_URL points at a development database")
            if not self.public_base_url.startswith("https://"):
                problems.append("PUBLIC_BASE_URL must use https:// in staging/production")
            if self.jwt_algorithm == "RS256" and not (self.jwt_private_key_path and self.jwt_public_key_path):
                problems.append("RS256 requires JWT_PRIVATE_KEY_PATH and JWT_PUBLIC_KEY_PATH")
        return problems


@lru_cache
def get_settings() -> Settings:
    return Settings()

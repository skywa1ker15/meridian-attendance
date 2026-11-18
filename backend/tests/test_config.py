"""Settings parsing that production deployments depend on.

These build Settings objects directly: mutating DATABASE_URL in the environment would
also redirect the shared test engine, so the rest of the suite would lose its database.
"""
import pytest

from app.core.config import Settings


def settings(**overrides) -> Settings:
    # _env_file=None keeps a developer's local .env out of the assertions.
    return Settings(_env_file=None, **overrides)


# --- managed hosts hand out driver-less URLs (Render, Heroku, Neon, Supabase) -----------
@pytest.mark.parametrize(
    ("given", "expected"),
    [
        ("postgres://u:p@h:5432/db", "postgresql+psycopg://u:p@h:5432/db"),
        ("postgresql://u:p@h:5432/db", "postgresql+psycopg://u:p@h:5432/db"),
        ("postgresql+psycopg://u:p@h:5432/db", "postgresql+psycopg://u:p@h:5432/db"),
    ],
)
def test_database_url_is_bound_to_psycopg(given, expected):
    assert settings(database_url=given).database_url == expected


def test_empty_cors_origins_parses_as_empty_list():
    assert settings(cors_origins="").cors_origins == []


def test_cors_origins_split_on_commas():
    assert settings(cors_origins="https://a.co.za, https://b.co.za").cors_origins == ["https://a.co.za", "https://b.co.za"]


def test_production_rejects_insecure_configuration():
    problems = " ".join(
        settings(
            app_env="production",
            secret_key="change-me",
            database_url="postgresql://meridian:meridian_dev@127.0.0.1:5432/meridian_attendance",
            public_base_url="http://attendance.example.co.za",
        ).validate_for_environment()
    )
    assert "SECRET_KEY" in problems
    assert "DATABASE_URL" in problems
    assert "PUBLIC_BASE_URL" in problems


@pytest.mark.parametrize(
    ("given", "expected"),
    [
        ("meridian-api.onrender.com", "https://meridian-api.onrender.com"),
        ("https://attendance.meridiangroup.co.za/", "https://attendance.meridiangroup.co.za"),
        ("http://192.168.1.50:8000", "http://192.168.1.50:8000"),
    ],
)
def test_bare_host_public_base_url_assumes_https(given, expected):
    assert settings(public_base_url=given).public_base_url == expected


def test_platform_supplied_bare_host_passes_production_checks():
    """A Render-style blueprint supplies the bare host; the first deploy must still boot."""
    problems = settings(
        app_env="production",
        secret_key="b8f1c2d3e4a5968778695a4b3c2d1e0fb8f1c2d3",
        database_url="postgresql://meridian:s3cret@db.internal:5432/meridian",
        public_base_url="meridian-api.onrender.com",
    ).validate_for_environment()
    assert problems == []


def test_production_accepts_a_sound_configuration():
    assert (
        settings(
            app_env="production",
            secret_key="b8f1c2d3e4a5968778695a4b3c2d1e0fb8f1c2d3",
            database_url="postgresql://meridian:s3cret@db.internal:5432/meridian",
            public_base_url="https://attendance.meridiangroup.co.za",
        ).validate_for_environment()
        == []
    )

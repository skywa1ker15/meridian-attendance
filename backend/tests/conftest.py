"""Test fixtures. Runs against the real PostgreSQL test database: the schema is
created with ``alembic upgrade head`` once per session and every table is
truncated between tests."""
from __future__ import annotations

import os

# TEST_DATABASE_URL wins; CI provides DATABASE_URL together with APP_ENV=test.
TEST_DATABASE_URL = os.environ.get("TEST_DATABASE_URL") or (
    os.environ["DATABASE_URL"] if os.environ.get("APP_ENV") == "test" and os.environ.get("DATABASE_URL") else "postgresql+psycopg://meridian:meridian_dev@127.0.0.1:5432/meridian_attendance_test"
)
# Every test TRUNCATEs all tables: refuse to run against anything not named like a test DB.
if "test" not in TEST_DATABASE_URL.rsplit("/", 1)[-1]:
    raise RuntimeError(f"Refusing to run tests against a non-test database: {TEST_DATABASE_URL.rsplit('@', 1)[-1]}")
os.environ.update(
    {
        "DATABASE_URL": TEST_DATABASE_URL,
        "APP_ENV": "test",
        "SCHEDULER_ENABLED": "false",
        "LOG_LEVEL": "WARNING",
        "LOG_JSON": "false",
        "SECRET_KEY": "test-only-key-not-used-anywhere-else-0123456789",
        "SMTP_HOST": "",
        "PUBLIC_BASE_URL": "http://localhost:8000",
        "METRICS_TOKEN": "",
        "CORS_ORIGINS": "",
        "FCM_ENABLED": "false",
        "ENV_FILE": "/nonexistent/.env",
        "RATE_LIMIT_ENABLED": "true",
        "RATE_LIMIT_LOGIN_PER_MINUTE": "1000",
        "RATE_LIMIT_DEFAULT_PER_MINUTE": "10000",
        "RATE_LIMIT_EVENTS_PER_MINUTE": "10000",
        "RATE_LIMIT_FORGOT_PER_MINUTE": "1000",
        "RATE_LIMIT_REFRESH_PER_MINUTE": "1000",
    }
)

import uuid  # noqa: E402
from datetime import datetime, timedelta  # noqa: E402
from pathlib import Path  # noqa: E402

import pytest  # noqa: E402
from alembic.config import Config  # noqa: E402
from fastapi.testclient import TestClient  # noqa: E402
from sqlalchemy import text  # noqa: E402

from alembic import command  # noqa: E402
from app.core import timeutil  # noqa: E402
from app.core.config import get_settings  # noqa: E402
from app.core.security import hash_password  # noqa: E402
from app.db.session import get_engine, get_sessionmaker, reset_engine  # noqa: E402
from app.models import Base, Department, User, Workplace  # noqa: E402

BACKEND = Path(__file__).resolve().parent.parent
PASSWORD = "Correct-Horse-Battery-9"
OFFICE_LAT, OFFICE_LON = -29.7300, 31.0800  # synthetic test coordinates (not Meridian's office)


@pytest.fixture(scope="session", autouse=True)
def _schema():
    get_settings.cache_clear()
    reset_engine()
    cfg = Config(str(BACKEND / "alembic.ini"))
    cfg.set_main_option("script_location", str(BACKEND / "alembic"))
    cfg.attributes["url"] = TEST_DATABASE_URL
    cfg.attributes["configure_logger"] = False
    command.downgrade(cfg, "base")
    command.upgrade(cfg, "head")
    yield
    reset_engine()


@pytest.fixture(autouse=True)
def _clean():
    tables = ", ".join(t.name for t in reversed(Base.metadata.sorted_tables))
    with get_engine().begin() as conn:
        conn.execute(text(f"TRUNCATE {tables} RESTART IDENTITY CASCADE"))
    yield


@pytest.fixture
def app():
    from app.main import create_app

    return create_app(get_settings())


@pytest.fixture
def client(app):
    with TestClient(app, raise_server_exceptions=False) as c:
        yield c


@pytest.fixture
def db():
    s = get_sessionmaker()()
    try:
        yield s
    finally:
        s.close()


class Clock:
    """Freezes ``timeutil.now_utc`` (used by every service) at a chosen instant."""

    def __init__(self, monkeypatch):
        self.mp = monkeypatch
        self.current: datetime | None = None

    def set(self, value: datetime) -> datetime:
        self.current = timeutil.ensure_utc(value)
        self.mp.setattr(timeutil, "now_utc", lambda: self.current)
        return self.current

    def local(self, day, hhmm: str) -> datetime:
        return self.set(timeutil.local_datetime(day, hhmm).astimezone(timeutil.UTC))

    def advance(self, **kw) -> datetime:
        return self.set((self.current or timeutil.now_utc()) + timedelta(**kw))


@pytest.fixture
def clock(monkeypatch):
    return Clock(monkeypatch)


# --- factories ------------------------------------------------------------------------
def make_user(db, *, role="employee", number=None, email=None, first="Test", last="User", department=None, password=PASSWORD, status="active", must_change=False) -> User:
    n = number or f"T-{uuid.uuid4().hex[:6].upper()}"
    u = User(
        id=uuid.uuid4(), employee_number=n, email=email or f"{n.lower()}@example.test", first_name=first, last_name=last, role=role, status=status,
        department_id=department.id if department else None, password_hash=hash_password(password), must_change_password=must_change,
    )
    db.add(u)
    db.commit()
    return u


def make_department(db, name="Finance", code="FIN") -> Department:
    d = Department(id=uuid.uuid4(), name=name, code=code)
    db.add(d)
    db.commit()
    return d


def make_workplace(db, *, confirmed=True, wifi_required=False, wifi=False) -> Workplace:
    w = Workplace(
        id=uuid.uuid4(), name="Test Office", latitude=OFFICE_LAT, longitude=OFFICE_LON, coordinates_confirmed=confirmed, radius_m=150, accuracy_threshold_m=100,
        wifi_verification_enabled=wifi or wifi_required, wifi_required=wifi_required, wifi_ssid="Office-Staff" if (wifi or wifi_required) else None, wifi_bssids=["aa:bb:cc:dd:ee:ff"] if (wifi or wifi_required) else [],
    )
    db.add(w)
    db.commit()
    return w


def device_body(device_id: str | None = None) -> dict:
    return {"device_id": device_id or f"install-{uuid.uuid4().hex}", "model": "SM-A546B", "manufacturer": "samsung", "os_version": "14", "app_version": "1.0.0 (1)"}


def login(client, identifier: str, password: str = PASSWORD, device: dict | None = None) -> dict:
    body = {"identifier": identifier, "password": password}
    if device is not None:
        body["device"] = device
    r = client.post("/api/v1/auth/login", json=body)
    assert r.status_code == 200, r.text
    return r.json()


def auth(tokens: dict) -> dict:
    return {"Authorization": f"Bearer {tokens['access_token']}"}


@pytest.fixture
def employee(db):
    return make_user(db, role="employee", number="MG-0042", first="Thandi", last="Naidoo")


@pytest.fixture
def workplace(db):
    return make_workplace(db)


@pytest.fixture
def emp_tokens(client, employee):
    return login(client, employee.employee_number, device=device_body())


@pytest.fixture
def admin_user(db):
    return make_user(db, role="admin", number="MG-ADM", first="Ada", last="Admin")


@pytest.fixture
def admin_tokens(client, admin_user):
    return login(client, admin_user.employee_number)


@pytest.fixture
def manager_tokens(client, db):
    u = make_user(db, role="manager", number="MG-MGR", first="Mo", last="Manager")
    return login(client, u.employee_number)


@pytest.fixture
def superadmin_tokens(client, db):
    u = make_user(db, role="superadmin", number="MG-SUP", first="Sue", last="Super")
    return login(client, u.employee_number)


def event_body(workplace, event_type="arrival", *, detected_at: datetime | None = None, lat=OFFICE_LAT, lon=OFFICE_LON, accuracy=15.0, offline=False, wifi=None, client_event_id=None) -> dict:
    return {
        "client_event_id": client_event_id or str(uuid.uuid4()),
        "workplace_id": str(workplace.id),
        "event_type": event_type,
        "source": "geofence",
        "detected_at": timeutil.utc_iso(detected_at or timeutil.now_utc()),
        "device_timezone": "Africa/Johannesburg",
        "geofence_transition": "dwell" if event_type == "arrival" else "exit",
        "location": {"latitude": lat, "longitude": lon, "accuracy_m": accuracy} if lat is not None else None,
        "wifi": wifi,
        "offline_queued": offline,
        "battery_level": 70,
        "note": None,
    }


def confirm_body(at: datetime | None = None) -> dict:
    return {"client_confirmation_id": str(uuid.uuid4()), "confirmed_at": timeutil.utc_iso(at or timeutil.now_utc())}

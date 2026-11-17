"""Time helpers. Storage is UTC; company-facing values use Africa/Johannesburg."""
from __future__ import annotations

from datetime import date, datetime, time, timedelta, timezone
from zoneinfo import ZoneInfo

from app.core.config import get_settings

UTC = timezone.utc


def company_tz() -> ZoneInfo:
    return ZoneInfo(get_settings().timezone)


def now_utc() -> datetime:
    """Single source of 'now' so tests can monkeypatch it."""
    return datetime.now(UTC)


def ensure_utc(value: datetime) -> datetime:
    if value.tzinfo is None:
        return value.replace(tzinfo=UTC)
    return value.astimezone(UTC)


def to_local(value: datetime) -> datetime:
    return ensure_utc(value).astimezone(company_tz())


def local_date(value: datetime) -> date:
    return to_local(value).date()


def today_local(now: datetime | None = None) -> date:
    return local_date(now or now_utc())


def local_time_str(value: datetime | None) -> str | None:
    return to_local(value).strftime("%H:%M") if value else None


def local_iso(value: datetime | None) -> str | None:
    return to_local(value).isoformat(timespec="seconds") if value else None


def utc_iso(value: datetime | None) -> str | None:
    if value is None:
        return None
    return ensure_utc(value).replace(microsecond=0).isoformat().replace("+00:00", "Z")


def parse_hhmm(value: str) -> time:
    hours, minutes = value.split(":")
    return time(int(hours), int(minutes))


def local_datetime(day: date, hhmm: str) -> datetime:
    """Combine a local calendar date and 'HH:MM' into an aware local datetime."""
    return datetime.combine(day, parse_hhmm(hhmm), tzinfo=company_tz())


def local_day_bounds_utc(day: date) -> tuple[datetime, datetime]:
    start = datetime.combine(day, time(0, 0), tzinfo=company_tz())
    end = start + timedelta(days=1)
    return start.astimezone(UTC), end.astimezone(UTC)


def week_start(day: date) -> date:
    return day - timedelta(days=day.weekday())


def month_start(day: date) -> date:
    return day.replace(day=1)


def minutes_between(start: datetime, end: datetime) -> int:
    return max(0, int((ensure_utc(end) - ensure_utc(start)).total_seconds() // 60))


def daterange(start: date, end: date):
    cur = start
    while cur <= end:
        yield cur
        cur += timedelta(days=1)

"""Password hashing, token generation and JWT handling."""
from __future__ import annotations

import hashlib
import hmac
import re
import secrets
import string
import uuid
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any

import jwt
from argon2 import PasswordHasher
from argon2.exceptions import InvalidHashError, VerifyMismatchError

from app.core.config import Settings, get_settings
from app.core.errors import ApiError

# argon2id with OWASP-recommended parameters (m=64MiB, t=3, p=4).
_hasher = PasswordHasher(time_cost=3, memory_cost=65536, parallelism=4, hash_len=32, salt_len=16)

_COMMON_PASSWORDS_FILE = Path(__file__).with_name("common_passwords.txt")
_common_passwords: set[str] | None = None
_PREDICTABLE_WORDS = {
    "password", "passw0rd", "welcome", "letmein", "qwerty", "qwertyuiop", "admin", "administrator",
    "meridian", "meridiangroup", "umhlanga", "durban", "attendance", "employee", "changeme", "summer",
    "winter", "spring", "autumn", "january", "february", "monday", "friday", "secret", "abcdef",
}


def _load_common_passwords() -> set[str]:
    global _common_passwords
    if _common_passwords is None:
        try:
            _common_passwords = {line.strip().lower() for line in _COMMON_PASSWORDS_FILE.read_text(encoding="utf-8").splitlines() if line.strip()}
        except FileNotFoundError:
            _common_passwords = set()
    return _common_passwords


# --- passwords --------------------------------------------------------------
def hash_password(password: str) -> str:
    return _hasher.hash(password)


def verify_password(password: str, password_hash: str | None) -> bool:
    if not password_hash:
        # Run a dummy hash to keep timing roughly constant for unknown users.
        _hasher.verify(_DUMMY_HASH, "dummy-password-compare")
        return False
    try:
        return _hasher.verify(password_hash, password)
    except (VerifyMismatchError, InvalidHashError):
        return False


_DUMMY_HASH = hash_password("dummy-password-compare")


def needs_rehash(password_hash: str) -> bool:
    try:
        return _hasher.check_needs_rehash(password_hash)
    except Exception:  # pragma: no cover - defensive
        return False


def validate_password_policy(password: str, identifiers: list[str] | None = None, settings: Settings | None = None) -> None:
    settings = settings or get_settings()
    if len(password) < settings.password_min_length:
        raise ApiError(422, "validation_error", f"Password must be at least {settings.password_min_length} characters.", {"fields": {"new_password": "too_short"}})
    lowered = password.lower()
    for ident in identifiers or []:
        if ident and ident.lower() in lowered:
            raise ApiError(422, "validation_error", "Password must not contain your email or employee number.", {"fields": {"new_password": "contains_identifier"}})
    common = _load_common_passwords()
    # Also catch "Password123!" / "Meridian2026" style variants: a common or
    # company-predictable word padded with digits and symbols.
    core = re.sub(r"^[^a-z]+|[^a-z]+$", "", lowered)
    if lowered in common or core in common or core in _PREDICTABLE_WORDS or len(set(lowered)) <= 3:
        raise ApiError(422, "validation_error", "That password is too common. Choose a stronger one.", {"fields": {"new_password": "too_common"}})


def generate_temporary_password(length: int | None = None) -> str:
    """Readable, unambiguous temporary password (no 0/O/1/l)."""
    length = length or get_settings().temporary_password_length
    alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789"
    while True:
        pw = "".join(secrets.choice(alphabet) for _ in range(length))
        if any(c.isdigit() for c in pw) and any(c.isupper() for c in pw) and any(c.islower() for c in pw):
            return pw


# --- opaque tokens ----------------------------------------------------------
def generate_opaque_token(nbytes: int = 48) -> str:
    return secrets.token_urlsafe(nbytes)


def hash_token(token: str) -> str:
    """SHA-256 of an opaque token (refresh / reset tokens are stored hashed)."""
    return hashlib.sha256(token.encode("utf-8")).hexdigest()


def constant_time_equals(a: str, b: str) -> bool:
    return hmac.compare_digest(a.encode("utf-8"), b.encode("utf-8"))


# --- JWT access tokens ------------------------------------------------------
def _signing_key(settings: Settings) -> str:
    if settings.jwt_algorithm == "RS256":
        return Path(settings.jwt_private_key_path).read_text(encoding="utf-8")  # type: ignore[arg-type]
    return settings.secret_key


def _verifying_key(settings: Settings) -> str:
    if settings.jwt_algorithm == "RS256":
        return Path(settings.jwt_public_key_path).read_text(encoding="utf-8")  # type: ignore[arg-type]
    return settings.secret_key


def create_access_token(*, user_id: uuid.UUID, role: str, token_version: int, device_id: uuid.UUID | None, settings: Settings | None = None, now: datetime | None = None) -> tuple[str, int]:
    settings = settings or get_settings()
    now = now or datetime.now(timezone.utc)
    expires = now + timedelta(minutes=settings.access_token_minutes)
    payload: dict[str, Any] = {
        "sub": str(user_id),
        "role": role,
        "ver": token_version,
        "did": str(device_id) if device_id else None,
        "jti": uuid.uuid4().hex,
        "iat": int(now.timestamp()),
        "nbf": int(now.timestamp()) - 5,
        "exp": int(expires.timestamp()),
        "typ": "access",
        "iss": "meridian-attendance",
    }
    token = jwt.encode(payload, _signing_key(settings), algorithm=settings.jwt_algorithm)
    return token, int(settings.access_token_minutes * 60)


def decode_access_token(token: str, settings: Settings | None = None) -> dict[str, Any]:
    settings = settings or get_settings()
    try:
        payload = jwt.decode(token, _verifying_key(settings), algorithms=[settings.jwt_algorithm], issuer="meridian-attendance", options={"require": ["exp", "sub", "iat", "typ"]}, leeway=10)
    except jwt.ExpiredSignatureError as exc:
        raise ApiError(401, "token_expired", "Access token has expired.") from exc
    except jwt.InvalidTokenError as exc:
        raise ApiError(401, "token_invalid", "Access token is invalid.") from exc
    if payload.get("typ") != "access":
        raise ApiError(401, "token_invalid", "Access token is invalid.")
    return payload


def random_alnum(n: int = 8) -> str:
    return "".join(secrets.choice(string.ascii_uppercase + string.digits) for _ in range(n))

import pytest

from app.core.errors import ApiError
from app.core.security import validate_password_policy


@pytest.mark.parametrize("pw", ["Password123!", "password2026", "Meridian2026!", "!!Welcome1234", "aaaaaaaaaaaa", "12121212121212"])
def test_predictable_passwords_rejected(pw):
    with pytest.raises(ApiError) as exc:
        validate_password_policy(pw, ["MG-0042"])
    assert exc.value.details["fields"]["new_password"] in {"too_common", "too_short"}


@pytest.mark.parametrize("pw", ["Tidal-Lamp-Orbit-94", "Qx7-3f9a1c2e-blue", "correct horse battery staple"])
def test_strong_passwords_accepted(pw):
    validate_password_policy(pw, ["MG-0042", "t.naidoo@example.co.za"])


def test_identifier_rejected():
    with pytest.raises(ApiError):
        validate_password_policy("xx-mg-0042-strong", ["MG-0042"])

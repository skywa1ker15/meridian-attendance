"""Input normalisation shared by the API layer.

Currently South African telephone numbers: employees type them in half a dozen
shapes and every one of them must end up stored identically, so that a number
entered on the phone during invitation acceptance matches the one an
administrator typed into the dashboard.
"""
from __future__ import annotations

import re

from app.core.errors import ApiError

# Everything a human might use as a separator, plus the unicode dashes copied out
# of Outlook signatures.
_SEPARATORS = re.compile(r"[\s\-()./‐-― ]")

# A South African national significant number is 9 digits. The leading digit is
# the area/network code's first digit: 1-5 and 8 are geographic, 6-8 are mobile
# ranges (060-089). 0 and 9 never start a subscriber number.
_NSN = re.compile(r"^[1-8]\d{8}$")

PHONE_HELP = "Enter a South African number, for example 082 123 4567 or +27 82 123 4567."


def normalise_sa_phone(value: str | None, *, field: str = "phone") -> str | None:
    """Return ``value`` as an E.164 South African number (``+27821234567``).

    ``None`` and the empty string pass through as ``None`` (the field is optional
    nearly everywhere). Anything that is not a plausible SA number raises a
    ``422 validation_error`` naming ``field``.
    """
    if value is None:
        return None
    cleaned = _SEPARATORS.sub("", str(value)).strip()
    if not cleaned:
        return None

    if cleaned.startswith("+"):
        digits = cleaned[1:]
        if not digits.isdigit():
            raise _invalid(field)
        if digits.startswith("27"):
            nsn = digits[2:]
        else:
            raise _invalid(field)
    elif cleaned.isdigit():
        if cleaned.startswith("0027"):
            nsn = cleaned[4:]
        elif cleaned.startswith("27") and len(cleaned) == 11:
            nsn = cleaned[2:]
        elif cleaned.startswith("0"):
            nsn = cleaned[1:]
        else:
            raise _invalid(field)
    else:
        raise _invalid(field)

    if not _NSN.match(nsn):
        raise _invalid(field)
    return "+27" + nsn


def _invalid(field: str) -> ApiError:
    return ApiError(422, "validation_error", PHONE_HELP, {"fields": {field: "invalid_phone"}})

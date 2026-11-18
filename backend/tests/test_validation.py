"""South African telephone-number normalisation."""
from __future__ import annotations

import pytest

from app.core.errors import ApiError
from app.core.validation import normalise_sa_phone

ACCEPTED = [
    ("0821234567", "+27821234567"),
    ("082 123 4567", "+27821234567"),
    ("082-123-4567", "+27821234567"),
    ("(082) 123 4567", "+27821234567"),
    ("+27821234567", "+27821234567"),
    ("+27 82 123 4567", "+27821234567"),
    ("0027821234567", "+27821234567"),
    ("0027 82 123 4567", "+27821234567"),
    ("27821234567", "+27821234567"),
    ("031 000 0000", "+27310000000"),  # landline: accepted too
    ("+27 11 555 1234", "+27115551234"),
    ("0600000000", "+27600000000"),
    (" 082–123–4567 ", "+27821234567"),  # nbsp and unicode dashes
]

REJECTED = [
    "",  # handled as "no number"
    "   ",
    "not a number",
    "082123456",  # one digit short
    "08212345678",  # one digit long
    "+27821234",  # too short after the country code
    "+2782123456789",  # too long after the country code
    "+44 20 7946 0958",  # not South African
    "0012125550123",  # not South African
    "0021234567",  # national number starting with 0
    "0921234567",  # no SA subscriber range starts with 9
    "082 123 456A",
    "+27-82-123-456?",
    "0000000000",
]


@pytest.mark.parametrize("raw,expected", ACCEPTED)
def test_accepted(raw, expected):
    assert normalise_sa_phone(raw) == expected


def test_none_and_blank_pass_through():
    assert normalise_sa_phone(None) is None
    assert normalise_sa_phone("") is None
    assert normalise_sa_phone("   ") is None


@pytest.mark.parametrize("raw", [r for r in REJECTED if r.strip()])
def test_rejected(raw):
    with pytest.raises(ApiError) as exc:
        normalise_sa_phone(raw)
    assert exc.value.status_code == 422
    assert exc.value.details["fields"]["phone"] == "invalid_phone"


def test_field_name_is_reported():
    with pytest.raises(ApiError) as exc:
        normalise_sa_phone("rubbish", field="mobile")
    assert exc.value.details["fields"] == {"mobile": "invalid_phone"}


def test_normalisation_is_idempotent():
    once = normalise_sa_phone("082 123 4567")
    assert normalise_sa_phone(once) == once

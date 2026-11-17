"""Shared schema helpers."""
from __future__ import annotations

from typing import Any, Generic, TypeVar

from pydantic import BaseModel, ConfigDict, Field

T = TypeVar("T")


class ApiModel(BaseModel):
    model_config = ConfigDict(extra="forbid", str_strip_whitespace=True)


class Page(BaseModel, Generic[T]):
    items: list[T]
    total: int
    page: int
    page_size: int


class PageParams(BaseModel):
    page: int = Field(1, ge=1)
    page_size: int = Field(50, ge=1, le=200)

    @property
    def offset(self) -> int:
        return (self.page - 1) * self.page_size


def page_dict(items: list[Any], total: int, params: PageParams) -> dict[str, Any]:
    return {"items": items, "total": total, "page": params.page, "page_size": params.page_size}


class LocationIn(ApiModel):
    latitude: float = Field(..., ge=-90, le=90)
    longitude: float = Field(..., ge=-180, le=180)
    accuracy_m: float | None = Field(None, ge=0, le=100000)


class WifiIn(ApiModel):
    ssid: str | None = Field(None, max_length=64)
    bssid: str | None = Field(None, max_length=32)


class NoteIn(ApiModel):
    note: str | None = Field(None, max_length=1000)


class ReasonIn(ApiModel):
    reason: str = Field(..., min_length=2, max_length=1000)

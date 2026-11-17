"""Aggregates all v1 routers."""
from __future__ import annotations

from fastapi import APIRouter

from app.api.v1 import admin, attendance, auth, config, devices, notifications, setup

api_router = APIRouter(prefix="/api/v1")
for r in (setup.router, auth.router, devices.router, config.router, attendance.router, notifications.router, admin.router):
    api_router.include_router(r)

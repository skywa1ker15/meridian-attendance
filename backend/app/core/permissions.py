"""Role → permission matrix. The server is the only authority on this.

Roles are stored on the user record; nothing supplied by a client is trusted.
"""
from __future__ import annotations

ROLE_EMPLOYEE = "employee"
ROLE_MANAGER = "manager"
ROLE_ADMIN = "admin"
ROLE_SUPERADMIN = "superadmin"
ROLES = (ROLE_EMPLOYEE, ROLE_MANAGER, ROLE_ADMIN, ROLE_SUPERADMIN)

P_ATTENDANCE_SELF = "attendance:self"
P_ADMIN_READ = "admin:read"
P_ATTENDANCE_REVIEW = "attendance:review"
P_REPORTS_EXPORT = "reports:export"
P_EMPLOYEES_WRITE = "employees:write"
P_DEVICES_WRITE = "devices:write"
P_CONFIG_WRITE = "config:write"
P_ROLES_WRITE = "roles:write"
P_ROLES_SUPERADMIN = "roles:superadmin"
P_NOTIFICATIONS_BROADCAST = "notifications:broadcast"

_MANAGER = {P_ATTENDANCE_SELF, P_ADMIN_READ, P_ATTENDANCE_REVIEW, P_REPORTS_EXPORT}
_ADMIN = _MANAGER | {P_EMPLOYEES_WRITE, P_DEVICES_WRITE, P_CONFIG_WRITE, P_ROLES_WRITE, P_NOTIFICATIONS_BROADCAST}
_SUPERADMIN = _ADMIN | {P_ROLES_SUPERADMIN}

ROLE_PERMISSIONS: dict[str, frozenset[str]] = {
    ROLE_EMPLOYEE: frozenset({P_ATTENDANCE_SELF}),
    ROLE_MANAGER: frozenset(_MANAGER),
    ROLE_ADMIN: frozenset(_ADMIN),
    ROLE_SUPERADMIN: frozenset(_SUPERADMIN),
}

ROLE_DESCRIPTIONS = {
    ROLE_EMPLOYEE: "Uses the mobile app to confirm own attendance and submit manual requests.",
    ROLE_MANAGER: "Monitors attendance, reviews pending confirmations and manual requests, exports reports.",
    ROLE_ADMIN: "Everything a manager can do plus employee, device, workplace and policy management and role assignment up to admin.",
    ROLE_SUPERADMIN: "Full control including superadmin assignment, system settings and personal-data erasure.",
}

RANK = {ROLE_EMPLOYEE: 0, ROLE_MANAGER: 1, ROLE_ADMIN: 2, ROLE_SUPERADMIN: 3}


def permissions_for(role: str) -> list[str]:
    return sorted(ROLE_PERMISSIONS.get(role, frozenset()))


def has_permission(role: str, permission: str) -> bool:
    return permission in ROLE_PERMISSIONS.get(role, frozenset())


def can_assign_role(actor_role: str, target_role: str) -> bool:
    """Admins may assign employee/manager/admin; only superadmins may touch superadmin."""
    if target_role not in ROLES:
        return False
    if target_role == ROLE_SUPERADMIN:
        return actor_role == ROLE_SUPERADMIN
    return RANK.get(actor_role, -1) >= RANK[ROLE_ADMIN]

package za.co.meridiangroup.attendance.core

/** Server permission strings (docs/API.md §7). The server enforces them; the app only hides UI. */
object ServerPermissions {
    const val ATTENDANCE_SELF = "attendance:self"
    const val ADMIN_READ = "admin:read"
    const val ATTENDANCE_REVIEW = "attendance:review"
    const val REPORTS_EXPORT = "reports:export"
    const val EMPLOYEES_WRITE = "employees:write"
    const val DEVICES_WRITE = "devices:write"
    const val CONFIG_WRITE = "config:write"
    const val ROLES_WRITE = "roles:write"
    const val ROLES_SUPERADMIN = "roles:superadmin"
    const val NOTIFICATIONS_BROADCAST = "notifications:broadcast"

    /** Every permission that unlocks something in the "Manage" section. */
    val MANAGE_PERMISSIONS: Set<String> = setOf(
        ADMIN_READ, ATTENDANCE_REVIEW, REPORTS_EXPORT, EMPLOYEES_WRITE, DEVICES_WRITE, CONFIG_WRITE,
        ROLES_WRITE, ROLES_SUPERADMIN, NOTIFICATIONS_BROADCAST,
    )

    private val ROLE_ORDER = listOf("employee", "manager", "admin", "superadmin")

    fun canReview(permissions: Collection<String>): Boolean = ATTENDANCE_REVIEW in permissions
    fun canViewLive(permissions: Collection<String>): Boolean = ADMIN_READ in permissions
    fun canRead(permissions: Collection<String>): Boolean = ADMIN_READ in permissions
    fun canExport(permissions: Collection<String>): Boolean = REPORTS_EXPORT in permissions
    fun canWriteEmployees(permissions: Collection<String>): Boolean = EMPLOYEES_WRITE in permissions
    fun canWriteDevices(permissions: Collection<String>): Boolean = DEVICES_WRITE in permissions
    fun canWriteConfig(permissions: Collection<String>): Boolean = CONFIG_WRITE in permissions
    fun canWriteRoles(permissions: Collection<String>): Boolean = ROLES_WRITE in permissions
    fun isSuperadmin(permissions: Collection<String>): Boolean = ROLES_SUPERADMIN in permissions
    fun canBroadcast(permissions: Collection<String>): Boolean = NOTIFICATIONS_BROADCAST in permissions

    /** True when the signed-in user gets the "Manage" tab (manager, admin, superadmin). */
    fun hasAdminSection(permissions: Collection<String>): Boolean = permissions.any { it in MANAGE_PERMISSIONS }

    /**
     * Roles this user may assign (mirrors backend `can_assign_role`): admins may assign employee/manager/admin,
     * only superadmins may grant superadmin. Without `roles:write` only "employee" is offered.
     */
    fun assignableRoles(permissions: Collection<String>): List<String> = when {
        isSuperadmin(permissions) -> ROLE_ORDER
        canWriteRoles(permissions) -> ROLE_ORDER.take(3)
        else -> listOf("employee")
    }
}

/** Names used in the local permission onboarding, independent of Android constants. */
enum class AppPermissionStep(val key: String, val required: Boolean) {
    NOTIFICATIONS("notifications", required = false),
    PRECISE_LOCATION("precise_location", required = true),
    BACKGROUND_LOCATION("background_location", required = true),
    BATTERY_OPTIMISATION("battery_optimisation", required = false),
    WIFI("wifi", required = false),
}

data class PermissionSnapshot(
    val notificationsGranted: Boolean,
    val preciseLocationGranted: Boolean,
    val backgroundLocationGranted: Boolean,
    val batteryOptimisationIgnored: Boolean,
    val wifiGranted: Boolean,
    val locationServicesOn: Boolean,
) {
    /** Detection can run only with precise + background location and location services on. */
    val detectionPossible: Boolean get() = preciseLocationGranted && backgroundLocationGranted && locationServicesOn

    /** Steps that still need the employee's action, in onboarding order. */
    fun outstanding(): List<AppPermissionStep> = buildList {
        if (!notificationsGranted) add(AppPermissionStep.NOTIFICATIONS)
        if (!preciseLocationGranted) add(AppPermissionStep.PRECISE_LOCATION)
        if (!backgroundLocationGranted) add(AppPermissionStep.BACKGROUND_LOCATION)
        if (!batteryOptimisationIgnored) add(AppPermissionStep.BATTERY_OPTIMISATION)
        if (!wifiGranted) add(AppPermissionStep.WIFI)
    }

    val requiredOutstanding: Boolean get() = outstanding().any { it.required }
}

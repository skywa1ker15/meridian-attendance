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

    fun canReview(permissions: Collection<String>): Boolean = ATTENDANCE_REVIEW in permissions
    fun canViewLive(permissions: Collection<String>): Boolean = ADMIN_READ in permissions
    fun hasAdminSection(permissions: Collection<String>): Boolean = canReview(permissions) || canViewLive(permissions)
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

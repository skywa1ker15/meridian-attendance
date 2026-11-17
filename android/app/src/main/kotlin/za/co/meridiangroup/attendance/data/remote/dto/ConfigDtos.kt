package za.co.meridiangroup.attendance.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class WorkplaceDto(
    @SerialName("id") val id: String,
    @SerialName("name") val name: String = "",
    @SerialName("address") val address: String? = null,
    @SerialName("latitude") val latitude: Double = 0.0,
    @SerialName("longitude") val longitude: Double = 0.0,
    @SerialName("radius_m") val radiusM: Double = 150.0,
    @SerialName("accuracy_threshold_m") val accuracyThresholdM: Double = 100.0,
    @SerialName("entry_detection_enabled") val entryDetectionEnabled: Boolean = true,
    @SerialName("exit_detection_enabled") val exitDetectionEnabled: Boolean = true,
    @SerialName("dwell_seconds") val dwellSeconds: Int = 120,
    @SerialName("exit_debounce_seconds") val exitDebounceSeconds: Int = 300,
    @SerialName("wifi_verification_enabled") val wifiVerificationEnabled: Boolean = false,
    @SerialName("wifi_ssid") val wifiSsid: String? = null,
    @SerialName("wifi_bssids") val wifiBssids: List<String> = emptyList(),
    @SerialName("wifi_required") val wifiRequired: Boolean = false,
    @SerialName("timezone") val timezone: String = "Africa/Johannesburg",
    @SerialName("active") val active: Boolean = true,
    /**
     * False while management has not yet supplied real coordinates (API.md marks them as PLACEHOLDERS).
     * Geofences are never registered unless this is true. When a backend omits the field entirely, the
     * app falls back to "confirmed only if the coordinates are valid and non-zero" (see [isConfigured]).
     */
    @SerialName("coordinates_confirmed") val coordinatesConfirmed: Boolean? = null,
) {
    val isConfigured: Boolean
        get() = coordinatesConfirmed ?: (latitude != 0.0 && longitude != 0.0 &&
            za.co.meridiangroup.attendance.core.Geo.isValidLatitude(latitude) &&
            za.co.meridiangroup.attendance.core.Geo.isValidLongitude(longitude))
}

@Serializable
data class WorkplacesResponseDto(
    @SerialName("items") val items: List<WorkplaceDto> = emptyList(),
    @SerialName("config_version") val configVersion: Int = 0,
)

@Serializable
data class PolicyDto(
    @SerialName("workday_start_local") val workdayStartLocal: String = "08:00",
    @SerialName("workday_end_local") val workdayEndLocal: String = "17:00",
    @SerialName("late_after_local") val lateAfterLocal: String = "08:15",
    @SerialName("grace_minutes") val graceMinutes: Int = 15,
    @SerialName("standard_day_minutes") val standardDayMinutes: Int = 480,
    @SerialName("break_minutes") val breakMinutes: Int = 60,
    @SerialName("overtime_after_minutes") val overtimeAfterMinutes: Int = 480,
    @SerialName("working_days") val workingDays: List<Int> = listOf(1, 2, 3, 4, 5),
    @SerialName("pending_expiry_hours") val pendingExpiryHours: Int = 12,
    @SerialName("missing_clock_out_flag_local") val missingClockOutFlagLocal: String = "23:30",
    @SerialName("max_future_skew_seconds") val maxFutureSkewSeconds: Int = 120,
    @SerialName("max_offline_age_hours") val maxOfflineAgeHours: Int = 72,
    @SerialName("online_skew_tolerance_seconds") val onlineSkewToleranceSeconds: Int = 300,
    @SerialName("duplicate_window_seconds") val duplicateWindowSeconds: Int = 600,
    @SerialName("official_time_source") val officialTimeSource: String = "server_received",
    @SerialName("min_verification_score_for_auto_confirm") val minVerificationScoreForAutoConfirm: Double = 0.6,
    @SerialName("max_active_devices_per_user") val maxActiveDevicesPerUser: Int = 1,
    @SerialName("device_replacement_requires_approval") val deviceReplacementRequiresApproval: Boolean = false,
    @SerialName("require_integrity_verdict") val requireIntegrityVerdict: Boolean = false,
    @SerialName("retention_days_attendance") val retentionDaysAttendance: Int = 1825,
    @SerialName("retention_days_location") val retentionDaysLocation: Int = 365,
    @SerialName("retention_days_audit") val retentionDaysAudit: Int = 2555,
    @SerialName("notification_quiet_hours") val notificationQuietHours: String? = null,
    /**
     * Minutes after which a still-pending confirmation is re-notified locally. Not (yet) part of the
     * docs/API.md §8 policy object: when the backend omits it the app uses 15 minutes.
     */
    @SerialName("confirmation_reminder_minutes") val confirmationReminderMinutes: Int = 15,
    @SerialName("updated_at") val updatedAt: String? = null,
    @SerialName("updated_by") val updatedBy: String? = null,
)

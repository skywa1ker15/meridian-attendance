package za.co.meridiangroup.attendance.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class DeviceInfoDto(
    @SerialName("device_id") val deviceId: String,
    @SerialName("model") val model: String,
    @SerialName("manufacturer") val manufacturer: String,
    @SerialName("os_version") val osVersion: String,
    @SerialName("app_version") val appVersion: String,
    @SerialName("fcm_token") val fcmToken: String? = null,
)

@Serializable
data class LoginRequestDto(
    @SerialName("identifier") val identifier: String,
    @SerialName("password") val password: String,
    @SerialName("device") val device: DeviceInfoDto? = null,
)

@Serializable
data class RefreshRequestDto(@SerialName("refresh_token") val refreshToken: String)

@Serializable
data class LogoutRequestDto(@SerialName("refresh_token") val refreshToken: String)

@Serializable
data class AuthResponseDto(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String,
    @SerialName("token_type") val tokenType: String = "bearer",
    @SerialName("expires_in") val expiresIn: Int = 900,
    @SerialName("must_change_password") val mustChangePassword: Boolean = false,
    @SerialName("user") val user: UserDto,
    @SerialName("device") val device: DeviceDto? = null,
)

@Serializable
data class UserDto(
    @SerialName("id") val id: String,
    @SerialName("employee_number") val employeeNumber: String = "",
    @SerialName("first_name") val firstName: String = "",
    @SerialName("last_name") val lastName: String = "",
    @SerialName("email") val email: String = "",
    @SerialName("phone") val phone: String? = null,
    @SerialName("role") val role: String = "employee",
    @SerialName("status") val status: String = "active",
    @SerialName("department") val department: DepartmentDto? = null,
    @SerialName("must_change_password") val mustChangePassword: Boolean = false,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("last_login_at") val lastLoginAt: String? = null,
    @SerialName("permissions") val permissions: List<String> = emptyList(),
    @SerialName("notification_preferences") val notificationPreferences: NotificationPreferencesDto? = null,
    @SerialName("last_attendance_status") val lastAttendanceStatus: String? = null,
    /** Present only in GET /admin/employees items. */
    @SerialName("active_device") val activeDevice: DeviceDto? = null,
) {
    val fullName: String get() = listOf(firstName, lastName).filter { it.isNotBlank() }.joinToString(" ")
}

@Serializable
data class NotificationPreferencesDto(@SerialName("push") val push: Boolean = true)

@Serializable
data class DeviceDto(
    @SerialName("id") val id: String,
    @SerialName("device_id") val deviceId: String = "",
    @SerialName("model") val model: String? = null,
    @SerialName("manufacturer") val manufacturer: String? = null,
    @SerialName("os_version") val osVersion: String? = null,
    @SerialName("app_version") val appVersion: String? = null,
    @SerialName("status") val status: String = "active",
    @SerialName("registered_at") val registeredAt: String? = null,
    @SerialName("last_seen_at") val lastSeenAt: String? = null,
    @SerialName("integrity_verdict") val integrityVerdict: String? = null,
    /** Present only on admin device endpoints (GET /admin/devices, approve, revoke). */
    @SerialName("employee") val employee: EmployeeSummaryDto? = null,
)

@Serializable
data class MeResponseDto(
    @SerialName("user") val user: UserDto,
    @SerialName("device") val device: DeviceDto? = null,
)

@Serializable
data class PatchMeRequestDto(
    @SerialName("phone") val phone: String? = null,
    @SerialName("notification_preferences") val notificationPreferences: NotificationPreferencesDto? = null,
)

@Serializable
data class ChangePasswordRequestDto(
    @SerialName("current_password") val currentPassword: String,
    @SerialName("new_password") val newPassword: String,
)

@Serializable
data class ForgotPasswordRequestDto(@SerialName("identifier") val identifier: String)

@Serializable
data class ResetPasswordRequestDto(
    @SerialName("token") val token: String,
    @SerialName("new_password") val newPassword: String,
)

// ---- §1 Invitations (HR invites; the employee sets their own password) ----

/** `GET /auth/invitation?token=…` — who the invitation is for. No authentication. */
@Serializable
data class InvitationDto(
    @SerialName("employee_number") val employeeNumber: String = "",
    @SerialName("first_name") val firstName: String = "",
    @SerialName("last_name") val lastName: String = "",
    @SerialName("email") val email: String = "",
    @SerialName("phone") val phone: String? = null,
    @SerialName("department") val department: DepartmentDto? = null,
    @SerialName("expires_at") val expiresAt: String? = null,
) {
    val fullName: String get() = listOf(firstName, lastName).filter { it.isNotBlank() }.joinToString(" ")
}

/** `POST /auth/invitation/accept` → the same body as `POST /auth/login`. */
@Serializable
data class AcceptInvitationRequestDto(
    @SerialName("token") val token: String,
    @SerialName("phone") val phone: String,
    @SerialName("password") val password: String,
    @SerialName("device") val device: DeviceInfoDto? = null,
)

/**
 * The invitation an administrator triggers (`POST /admin/employees` without a temporary password,
 * `POST /admin/employees/{id}/invite`). Every field is optional: the app only reports what it is given.
 */
@Serializable
data class InvitationSummaryDto(
    @SerialName("email") val email: String? = null,
    @SerialName("expires_at") val expiresAt: String? = null,
    @SerialName("sent") val sent: Boolean? = null,
)

@Serializable
data class FcmTokenRequestDto(@SerialName("fcm_token") val fcmToken: String)

@Serializable
data class IntegrityRequestDto(@SerialName("integrity_token") val integrityToken: String)

@Serializable
data class IntegrityResponseDto(@SerialName("verdict") val verdict: String = "unknown")

// ---- §0 First-run setup ----

@Serializable
data class SetupStatusDto(
    @SerialName("needs_first_admin") val needsFirstAdmin: Boolean = false,
    @SerialName("setup_code_required") val setupCodeRequired: Boolean = false,
)

@Serializable
data class FirstAdminRequestDto(
    @SerialName("setup_code") val setupCode: String? = null,
    @SerialName("employee_number") val employeeNumber: String,
    @SerialName("first_name") val firstName: String,
    @SerialName("last_name") val lastName: String,
    @SerialName("email") val email: String,
    @SerialName("password") val password: String,
    @SerialName("device") val device: DeviceInfoDto? = null,
)

// ---- §9 Health (served at the server root, not under /api/v1) ----

@Serializable
data class HealthDto(
    @SerialName("status") val status: String = "",
    @SerialName("version") val version: String? = null,
)

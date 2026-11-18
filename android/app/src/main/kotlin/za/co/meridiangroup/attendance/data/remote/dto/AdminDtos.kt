package za.co.meridiangroup.attendance.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import za.co.meridiangroup.attendance.data.remote.ApiJson

// docs/API.md §7 Administration — request/response DTOs used by the "Manage" section.
// No Android imports: compiled and tested by tools/core-check.

// ---- Overview ----

@Serializable
data class OverviewStatsDto(
    @SerialName("total_employees") val totalEmployees: Int = 0,
    @SerialName("clocked_in") val clockedIn: Int = 0,
    @SerialName("clocked_out") val clockedOut: Int = 0,
    @SerialName("absent") val absent: Int = 0,
    @SerialName("late_arrivals") val lateArrivals: Int = 0,
    @SerialName("pending_confirmations") val pendingConfirmations: Int = 0,
    @SerialName("under_review") val underReview: Int = 0,
    @SerialName("exceptions") val exceptions: Int = 0,
    @SerialName("pending_requests") val pendingRequests: Int = 0,
    @SerialName("pending_devices") val pendingDevices: Int = 0,
)

@Serializable
data class TrendPointDto(
    @SerialName("work_date") val workDate: String = "",
    @SerialName("present") val present: Int = 0,
    @SerialName("late") val late: Int = 0,
    @SerialName("absent") val absent: Int = 0,
)

@Serializable
data class DepartmentBreakdownDto(
    @SerialName("department") val department: String = "",
    @SerialName("present") val present: Int = 0,
    @SerialName("total") val total: Int = 0,
)

@Serializable
data class ActivityDto(
    @SerialName("at") val at: String? = null,
    @SerialName("kind") val kind: String = "",
    @SerialName("text") val text: String = "",
    @SerialName("employee_id") val employeeId: String? = null,
    @SerialName("event_id") val eventId: String? = null,
)

@Serializable
data class OverviewDto(
    @SerialName("work_date") val workDate: String = "",
    @SerialName("generated_at") val generatedAt: String? = null,
    @SerialName("stats") val stats: OverviewStatsDto = OverviewStatsDto(),
    @SerialName("attendance_trend") val attendanceTrend: List<TrendPointDto> = emptyList(),
    @SerialName("department_breakdown") val departmentBreakdown: List<DepartmentBreakdownDto> = emptyList(),
    @SerialName("recent_activity") val recentActivity: List<ActivityDto> = emptyList(),
)

// ---- Employees ----

@Serializable
data class EmployeeCreateRequestDto(
    @SerialName("employee_number") val employeeNumber: String,
    @SerialName("first_name") val firstName: String,
    @SerialName("last_name") val lastName: String,
    @SerialName("email") val email: String,
    @SerialName("phone") val phone: String? = null,
    @SerialName("department_id") val departmentId: String? = null,
    @SerialName("role") val role: String = "employee",
    @SerialName("temporary_password") val temporaryPassword: String? = null,
    /** null lets the server decide (it invites whenever email is configured). */
    @SerialName("send_invitation") val sendInvitation: Boolean? = null,
)

/**
 * `POST /admin/employees` → either a temporary password (when the administrator supplied one) or an
 * invitation that was emailed to the employee. Exactly one of the two is present.
 */
@Serializable
data class EmployeeCreatedDto(
    @SerialName("user") val user: UserDto,
    @SerialName("temporary_password") val temporaryPassword: String? = null,
    @SerialName("invitation") val invitation: InvitationSummaryDto? = null,
)

/** PATCH /admin/employees/{id}: null fields are omitted (ApiJson.explicitNulls = false). */
@Serializable
data class EmployeeUpdateRequestDto(
    @SerialName("employee_number") val employeeNumber: String? = null,
    @SerialName("first_name") val firstName: String? = null,
    @SerialName("last_name") val lastName: String? = null,
    @SerialName("email") val email: String? = null,
    @SerialName("phone") val phone: String? = null,
    @SerialName("department_id") val departmentId: String? = null,
    /** Server extension: true removes the department (department_id cannot be sent as null). */
    @SerialName("clear_department") val clearDepartment: Boolean? = null,
)

@Serializable
data class Stats30dDto(
    @SerialName("present_days") val presentDays: Int = 0,
    @SerialName("late_days") val lateDays: Int = 0,
    @SerialName("absent_days") val absentDays: Int = 0,
    @SerialName("worked_minutes") val workedMinutes: Int = 0,
    @SerialName("overtime_minutes") val overtimeMinutes: Int = 0,
    @SerialName("manual_requests") val manualRequests: Int = 0,
)

@Serializable
data class EmployeeDetailDto(
    @SerialName("user") val user: UserDto,
    @SerialName("devices") val devices: List<DeviceDto> = emptyList(),
    @SerialName("today") val today: TodaySummaryDto? = null,
    @SerialName("stats_30d") val stats30d: Stats30dDto = Stats30dDto(),
)

@Serializable
data class ReasonRequestDto(@SerialName("reason") val reason: String)

@Serializable
data class RoleRequestDto(@SerialName("role") val role: String)

@Serializable
data class TemporaryPasswordDto(@SerialName("temporary_password") val temporaryPassword: String = "")

@Serializable
data class EraseRequestDto(
    @SerialName("confirm_employee_number") val confirmEmployeeNumber: String,
    @SerialName("reason") val reason: String,
)

@Serializable
data class EraseResponseDto(
    @SerialName("status") val status: String = "",
    @SerialName("user_id") val userId: String? = null,
    @SerialName("erased_at") val erasedAt: String? = null,
)

// ---- Departments ----

@Serializable
data class AdminDepartmentDto(
    @SerialName("id") val id: String,
    @SerialName("name") val name: String = "",
    @SerialName("code") val code: String? = null,
    @SerialName("active") val active: Boolean = true,
    @SerialName("employee_count") val employeeCount: Int = 0,
    @SerialName("created_at") val createdAt: String? = null,
)

@Serializable
data class DepartmentCreateRequestDto(
    @SerialName("name") val name: String,
    @SerialName("code") val code: String,
)

@Serializable
data class DepartmentUpdateRequestDto(
    @SerialName("name") val name: String? = null,
    @SerialName("code") val code: String? = null,
    @SerialName("active") val active: Boolean? = null,
)

// ---- Attendance records ----

@Serializable
data class SessionCorrectionRequestDto(
    @SerialName("clock_in_at") val clockInAt: String? = null,
    @SerialName("clock_out_at") val clockOutAt: String? = null,
    @SerialName("note") val note: String,
)

@Serializable
data class SessionCreateRequestDto(
    @SerialName("employee_id") val employeeId: String,
    @SerialName("work_date") val workDate: String,
    @SerialName("clock_in_at") val clockInAt: String,
    @SerialName("clock_out_at") val clockOutAt: String? = null,
    @SerialName("note") val note: String,
)

/** Approve body: the note is optional (`ReviewIn`); reject uses [ReviewNoteRequestDto] (note required). */
@Serializable
data class OptionalNoteRequestDto(@SerialName("note") val note: String? = null)

@Serializable
data class AttendanceExceptionDto(
    @SerialName("kind") val kind: String = "",
    @SerialName("work_date") val workDate: String? = null,
    @SerialName("employee") val employee: EmployeeSummaryDto? = null,
    @SerialName("session_id") val sessionId: String? = null,
    @SerialName("event_id") val eventId: String? = null,
    @SerialName("detail") val detail: String = "",
    @SerialName("at") val at: String? = null,
)

@Serializable
data class RequestApproveRequestDto(
    @SerialName("note") val note: String? = null,
    @SerialName("clock_in_at") val clockInAt: String? = null,
    @SerialName("clock_out_at") val clockOutAt: String? = null,
)

// ---- Reports ----

@Serializable
data class ReportColumnDto(
    @SerialName("key") val key: String,
    @SerialName("label") val label: String = "",
)

@Serializable
data class ReportDto(
    @SerialName("report") val report: String = "",
    @SerialName("from") val from: String? = null,
    @SerialName("to") val to: String? = null,
    @SerialName("generated_at") val generatedAt: String? = null,
    @SerialName("columns") val columns: List<ReportColumnDto> = emptyList(),
    @SerialName("rows") val rows: List<JsonObject> = emptyList(),
    @SerialName("summary") val summary: JsonObject? = null,
) {
    /** Display text of one cell (numbers/strings/booleans as-is, null → "—", nested JSON compact). */
    fun cell(row: JsonObject, key: String): String = jsonText(row[key])
}

/** Readable text for an arbitrary JSON value. */
fun jsonText(value: JsonElement?): String = when (value) {
    null, JsonNull -> "—"
    is JsonPrimitive -> value.contentOrNull ?: "—"
    else -> value.toString()
}

// ---- Roles / audit / broadcast ----

@Serializable
data class RoleInfoDto(
    @SerialName("role") val role: String,
    @SerialName("permissions") val permissions: List<String> = emptyList(),
    @SerialName("description") val description: String = "",
    @SerialName("user_count") val userCount: Int = 0,
)

@Serializable
data class RolesResponseDto(@SerialName("roles") val roles: List<RoleInfoDto> = emptyList())

@Serializable
data class AuditActorDto(
    @SerialName("id") val id: String? = null,
    @SerialName("name") val name: String? = null,
    @SerialName("employee_number") val employeeNumber: String? = null,
    @SerialName("role") val role: String? = null,
)

@Serializable
data class AuditLogDto(
    @SerialName("id") val id: String,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("actor") val actor: AuditActorDto? = null,
    @SerialName("actor_type") val actorType: String = "user",
    @SerialName("action") val action: String = "",
    @SerialName("entity_type") val entityType: String? = null,
    @SerialName("entity_id") val entityId: String? = null,
    @SerialName("before") val before: JsonElement? = null,
    @SerialName("after") val after: JsonElement? = null,
    @SerialName("ip") val ip: String? = null,
    @SerialName("user_agent") val userAgent: String? = null,
    @SerialName("request_id") val requestId: String? = null,
)

@Serializable
data class BroadcastRequestDto(
    @SerialName("title") val title: String,
    @SerialName("body") val body: String,
    @SerialName("department_id") val departmentId: String? = null,
)

@Serializable
data class BroadcastResponseDto(@SerialName("sent") val sent: Int = 0)

// ---- Workplace / policy payloads (JSON objects so that explicit nulls and diffs are possible) ----

/** Builds POST/PATCH bodies for /admin/workplaces exactly like the web dashboard (full body on create, diff on edit). */
object WorkplacePayload {
    /** Every writable field of `WorkplaceIn`, with explicit nulls for cleared optional strings. */
    fun full(w: WorkplaceDto): JsonObject = buildJsonObject {
        put("name", w.name.trim())
        put("address", w.address?.trim()?.ifEmpty { null })
        put("latitude", w.latitude)
        put("longitude", w.longitude)
        put("coordinates_confirmed", w.coordinatesConfirmed ?: false)
        put("radius_m", w.radiusM.toInt())
        put("accuracy_threshold_m", w.accuracyThresholdM.toInt())
        put("entry_detection_enabled", w.entryDetectionEnabled)
        put("exit_detection_enabled", w.exitDetectionEnabled)
        put("dwell_seconds", w.dwellSeconds)
        put("exit_debounce_seconds", w.exitDebounceSeconds)
        put("wifi_verification_enabled", w.wifiVerificationEnabled)
        put("wifi_ssid", w.wifiSsid?.trim()?.ifEmpty { null })
        put("wifi_bssids", JsonArray(w.wifiBssids.map { JsonPrimitive(it.trim().lowercase()) }))
        put("wifi_required", w.wifiRequired)
        put("timezone", w.timezone.trim().ifEmpty { "Africa/Johannesburg" })
        put("active", w.active)
    }

    /** Only the fields whose value differs from [original] (empty → nothing to save). */
    fun diff(original: WorkplaceDto, edited: WorkplaceDto): JsonObject = diffObjects(full(original), full(edited))
}

/** PATCH /admin/policy body: only `PolicyUpdateIn` keys (the server forbids extras such as updated_at). */
object PolicyPayload {
    val WRITABLE_KEYS: List<String> = listOf(
        "workday_start_local", "workday_end_local", "late_after_local", "grace_minutes", "standard_day_minutes",
        "break_minutes", "overtime_after_minutes", "working_days", "pending_expiry_hours", "missing_clock_out_flag_local",
        "max_future_skew_seconds", "max_offline_age_hours", "online_skew_tolerance_seconds", "duplicate_window_seconds",
        "official_time_source", "min_verification_score_for_auto_confirm", "max_active_devices_per_user",
        "device_replacement_requires_approval", "require_integrity_verdict", "retention_days_attendance",
        "retention_days_location", "retention_days_audit", "notification_quiet_hours",
    )

    fun full(p: PolicyDto): JsonObject {
        val all = ApiJson.json.encodeToJsonElement(PolicyDto.serializer(), p).jsonObject
        return JsonObject(WRITABLE_KEYS.associateWith { all[it] ?: JsonNull })
    }

    fun diff(original: PolicyDto, edited: PolicyDto): JsonObject = diffObjects(full(original), full(edited))
}

fun diffObjects(original: JsonObject, edited: JsonObject): JsonObject =
    JsonObject(edited.filter { (k, v) -> (original[k] ?: JsonNull) != v })

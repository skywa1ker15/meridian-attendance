package za.co.meridiangroup.attendance.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** Body of POST /attendance/events (also used as the `payload` of a `kind=event` sync item). */
@Serializable
data class SubmitEventRequestDto(
    @SerialName("client_event_id") val clientEventId: String,
    @SerialName("workplace_id") val workplaceId: String,
    @SerialName("event_type") val eventType: String,
    @SerialName("source") val source: String,
    @SerialName("detected_at") val detectedAt: String,
    @SerialName("device_timezone") val deviceTimezone: String,
    @SerialName("geofence_transition") val geofenceTransition: String,
    @SerialName("location") val location: LocationDto? = null,
    @SerialName("wifi") val wifi: WifiDto? = null,
    @SerialName("offline_queued") val offlineQueued: Boolean = false,
    @SerialName("battery_level") val batteryLevel: Int? = null,
    @SerialName("note") val note: String? = null,
)

@Serializable
data class VerificationDto(
    @SerialName("inside_geofence") val insideGeofence: Boolean? = null,
    @SerialName("accuracy_ok") val accuracyOk: Boolean? = null,
    @SerialName("wifi_matched") val wifiMatched: Boolean? = null,
    @SerialName("timestamp_plausible") val timestampPlausible: Boolean? = null,
    @SerialName("device_active") val deviceActive: Boolean? = null,
    @SerialName("notes") val notes: List<String> = emptyList(),
)

@Serializable
data class EventDto(
    @SerialName("id") val id: String,
    @SerialName("client_event_id") val clientEventId: String? = null,
    @SerialName("event_type") val eventType: String,
    @SerialName("source") val source: String = "geofence",
    @SerialName("status") val status: String = "pending",
    @SerialName("workplace_id") val workplaceId: String? = null,
    @SerialName("workplace_name") val workplaceName: String? = null,
    @SerialName("detected_at") val detectedAt: String? = null,
    @SerialName("received_at") val receivedAt: String? = null,
    @SerialName("official_at") val officialAt: String? = null,
    @SerialName("confirmed_at") val confirmedAt: String? = null,
    @SerialName("expires_at") val expiresAt: String? = null,
    @SerialName("location") val location: LocationDto? = null,
    @SerialName("distance_from_workplace_m") val distanceFromWorkplaceM: Double? = null,
    @SerialName("wifi_verified") val wifiVerified: Boolean = false,
    @SerialName("verification_score") val verificationScore: Double? = null,
    @SerialName("verification") val verification: VerificationDto? = null,
    @SerialName("review_reason") val reviewReason: String? = null,
    @SerialName("reviewed_by") val reviewedBy: String? = null,
    @SerialName("reviewed_at") val reviewedAt: String? = null,
    @SerialName("review_note") val reviewNote: String? = null,
    @SerialName("session_id") val sessionId: String? = null,
    @SerialName("offline_queued") val offlineQueued: Boolean = false,
    @SerialName("created_at") val createdAt: String? = null,
    /** Present only on admin endpoints. */
    @SerialName("employee") val employee: EmployeeSummaryDto? = null,
)

@Serializable
data class SubmitEventResponseDto(
    @SerialName("event") val event: EventDto,
    @SerialName("today") val today: TodaySummaryDto? = null,
)

@Serializable
data class ConfirmEventRequestDto(
    @SerialName("client_confirmation_id") val clientConfirmationId: String,
    @SerialName("confirmed_at") val confirmedAt: String,
    @SerialName("location") val location: LocationDto? = null,
    @SerialName("wifi") val wifi: WifiDto? = null,
    @SerialName("note") val note: String? = null,
)

@Serializable
data class ConfirmEventResponseDto(
    @SerialName("event") val event: EventDto,
    @SerialName("session") val session: SessionDto? = null,
    @SerialName("today") val today: TodaySummaryDto? = null,
)

@Serializable
data class DismissEventRequestDto(
    @SerialName("reason") val reason: String,
    @SerialName("note") val note: String? = null,
)

@Serializable
data class SessionDto(
    @SerialName("id") val id: String,
    @SerialName("work_date") val workDate: String = "",
    @SerialName("status") val status: String = "open",
    @SerialName("workplace_id") val workplaceId: String? = null,
    @SerialName("workplace_name") val workplaceName: String? = null,
    @SerialName("clock_in_at") val clockInAt: String? = null,
    @SerialName("clock_out_at") val clockOutAt: String? = null,
    @SerialName("clock_in_local") val clockInLocal: String? = null,
    @SerialName("clock_out_local") val clockOutLocal: String? = null,
    @SerialName("clock_in_event_id") val clockInEventId: String? = null,
    @SerialName("clock_out_event_id") val clockOutEventId: String? = null,
    @SerialName("worked_minutes") val workedMinutes: Int = 0,
    @SerialName("break_minutes") val breakMinutes: Int = 0,
    @SerialName("overtime_minutes") val overtimeMinutes: Int = 0,
    @SerialName("is_late") val isLate: Boolean = false,
    @SerialName("late_minutes") val lateMinutes: Int = 0,
    @SerialName("is_manual") val isManual: Boolean = false,
    @SerialName("corrected") val corrected: Boolean = false,
    @SerialName("correction_note") val correctionNote: String? = null,
    @SerialName("notes") val notes: String? = null,
    @SerialName("employee") val employee: EmployeeSummaryDto? = null,
)

@Serializable
data class SessionDetailDto(
    @SerialName("session") val session: SessionDto,
    @SerialName("events") val events: List<EventDto> = emptyList(),
    @SerialName("employee") val employee: EmployeeSummaryDto? = null,
)

@Serializable
data class TodaySummaryDto(
    @SerialName("work_date") val workDate: String = "",
    @SerialName("status") val status: String = "not_started",
    @SerialName("session") val session: SessionDto? = null,
    @SerialName("pending_events") val pendingEvents: List<EventDto> = emptyList(),
    @SerialName("clock_in_local") val clockInLocal: String? = null,
    @SerialName("clock_out_local") val clockOutLocal: String? = null,
    @SerialName("worked_minutes_today") val workedMinutesToday: Int = 0,
    @SerialName("week_minutes") val weekMinutes: Int = 0,
    @SerialName("month_minutes") val monthMinutes: Int = 0,
    @SerialName("late_today") val lateToday: Boolean = false,
    @SerialName("unread_notifications") val unreadNotifications: Int = 0,
    @SerialName("pending_requests") val pendingRequests: Int = 0,
    @SerialName("server_time") val serverTime: String? = null,
)

// ---- POST /attendance/sync ----

@Serializable
data class SyncItemDto(
    @SerialName("kind") val kind: String,
    @SerialName("event_id") val eventId: String? = null,
    /** Raw payload JSON object (event body / confirm body / dismiss body) as stored in the outbox. */
    @SerialName("payload") val payload: JsonObject,
)

@Serializable
data class SyncRequestDto(@SerialName("items") val items: List<SyncItemDto>)

@Serializable
data class SyncResultDto(
    @SerialName("index") val index: Int,
    @SerialName("kind") val kind: String? = null,
    @SerialName("client_id") val clientId: String? = null,
    @SerialName("ok") val ok: Boolean,
    @SerialName("status") val status: Int = 0,
    @SerialName("event") val event: EventDto? = null,
    @SerialName("session") val session: SessionDto? = null,
    @SerialName("error") val error: ErrorBodyDto? = null,
)

@Serializable
data class SyncResponseDto(
    @SerialName("results") val results: List<SyncResultDto> = emptyList(),
    @SerialName("today") val today: TodaySummaryDto? = null,
)

// ---- Manual requests ----

@Serializable
data class ManualRequestCreateDto(
    @SerialName("client_request_id") val clientRequestId: String,
    @SerialName("type") val type: String,
    @SerialName("work_date") val workDate: String? = null,
    @SerialName("requested_clock_in") val requestedClockIn: String? = null,
    @SerialName("requested_clock_out") val requestedClockOut: String? = null,
    @SerialName("reason") val reason: String,
    @SerialName("session_id") val sessionId: String? = null,
    @SerialName("field") val field: String? = null,
    @SerialName("requested_value") val requestedValue: String? = null,
)

@Serializable
data class ManualRequestDto(
    @SerialName("id") val id: String,
    @SerialName("type") val type: String,
    @SerialName("status") val status: String = "pending",
    @SerialName("work_date") val workDate: String? = null,
    @SerialName("requested_clock_in") val requestedClockIn: String? = null,
    @SerialName("requested_clock_out") val requestedClockOut: String? = null,
    @SerialName("reason") val reason: String = "",
    @SerialName("field") val field: String? = null,
    @SerialName("requested_value") val requestedValue: String? = null,
    @SerialName("employee") val employee: EmployeeSummaryDto? = null,
    @SerialName("reviewed_by") val reviewedBy: String? = null,
    @SerialName("reviewed_at") val reviewedAt: String? = null,
    @SerialName("review_note") val reviewNote: String? = null,
    @SerialName("resulting_session_id") val resultingSessionId: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
)

// ---- Notifications ----

@Serializable
data class NotificationDto(
    @SerialName("id") val id: String,
    @SerialName("type") val type: String = "system",
    @SerialName("title") val title: String = "",
    @SerialName("body") val body: String = "",
    @SerialName("data") val data: Map<String, String> = emptyMap(),
    @SerialName("read_at") val readAt: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
)

// ---- Admin ----

@Serializable
data class LiveAttendanceItemDto(
    @SerialName("employee") val employee: EmployeeSummaryDto,
    @SerialName("status") val status: String = "not_started",
    @SerialName("clock_in_local") val clockInLocal: String? = null,
    @SerialName("clock_out_local") val clockOutLocal: String? = null,
    @SerialName("worked_minutes") val workedMinutes: Int = 0,
    @SerialName("is_late") val isLate: Boolean = false,
    @SerialName("pending_event_id") val pendingEventId: String? = null,
    @SerialName("last_event_at") val lastEventAt: String? = null,
)

@Serializable
data class LiveAttendanceResponseDto(
    @SerialName("work_date") val workDate: String = "",
    @SerialName("items") val items: List<LiveAttendanceItemDto> = emptyList(),
)

@Serializable
data class ReviewNoteRequestDto(@SerialName("note") val note: String)

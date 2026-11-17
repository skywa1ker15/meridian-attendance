package za.co.meridiangroup.attendance.data.local.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Cached copy of GET /config/workplaces. Geofences are (re)built from these rows. */
@Entity(tableName = "workplaces")
data class WorkplaceEntity(
    @PrimaryKey val id: String,
    val name: String,
    val address: String?,
    val latitude: Double,
    val longitude: Double,
    @ColumnInfo(name = "radius_m") val radiusM: Double,
    @ColumnInfo(name = "accuracy_threshold_m") val accuracyThresholdM: Double,
    @ColumnInfo(name = "entry_detection_enabled") val entryDetectionEnabled: Boolean,
    @ColumnInfo(name = "exit_detection_enabled") val exitDetectionEnabled: Boolean,
    @ColumnInfo(name = "dwell_seconds") val dwellSeconds: Int,
    @ColumnInfo(name = "exit_debounce_seconds") val exitDebounceSeconds: Int,
    @ColumnInfo(name = "wifi_verification_enabled") val wifiVerificationEnabled: Boolean,
    @ColumnInfo(name = "wifi_ssid") val wifiSsid: String?,
    /** Comma-separated lower-case BSSIDs. */
    @ColumnInfo(name = "wifi_bssids") val wifiBssids: String,
    @ColumnInfo(name = "wifi_required") val wifiRequired: Boolean,
    val timezone: String,
    val active: Boolean,
    @ColumnInfo(name = "coordinates_confirmed") val coordinatesConfirmed: Boolean,
    @ColumnInfo(name = "config_version") val configVersion: Int,
) {
    val bssidList: List<String> get() = wifiBssids.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    val geofenceEligible: Boolean get() = active && coordinatesConfirmed && (entryDetectionEnabled || exitDetectionEnabled)
}

/** Single-row cache of GET /attendance/today (stored as JSON to follow the contract exactly). */
@Entity(tableName = "today_summary")
data class TodaySummaryEntity(
    @PrimaryKey val id: Int = 0,
    @ColumnInfo(name = "work_date") val workDate: String,
    val json: String,
    @ColumnInfo(name = "fetched_at") val fetchedAtMillis: Long,
)

@Entity(tableName = "sessions", indices = [Index("work_date")])
data class SessionEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "work_date") val workDate: String,
    val status: String,
    val json: String,
    @ColumnInfo(name = "fetched_at") val fetchedAtMillis: Long,
)

/**
 * Attendance events known to the phone. A locally detected event exists here *before* the server has
 * it (`server_id` null, `local_status = queued`). Nothing is shown as confirmed until the server says so.
 */
@Entity(
    tableName = "events",
    indices = [Index(value = ["client_event_id"], unique = true), Index("server_id"), Index("status")],
)
data class EventEntity(
    @PrimaryKey @ColumnInfo(name = "client_event_id") val clientEventId: String,
    @ColumnInfo(name = "server_id") val serverId: String?,
    @ColumnInfo(name = "event_type") val eventType: String,
    val source: String,
    /** Server status (`pending`, `confirmed`, …) — `pending` locally until the server answers. */
    val status: String,
    /** Local lifecycle: `queued` (not yet on server), `submitted`, `confirming`, `dismissing`, `failed`. */
    @ColumnInfo(name = "local_status") val localStatus: String,
    @ColumnInfo(name = "workplace_id") val workplaceId: String?,
    @ColumnInfo(name = "workplace_name") val workplaceName: String?,
    @ColumnInfo(name = "detected_at") val detectedAt: String,
    @ColumnInfo(name = "expires_at") val expiresAt: String?,
    val latitude: Double?,
    val longitude: Double?,
    @ColumnInfo(name = "accuracy_m") val accuracyM: Double?,
    @ColumnInfo(name = "distance_m") val distanceM: Double?,
    @ColumnInfo(name = "wifi_ssid") val wifiSsid: String?,
    @ColumnInfo(name = "wifi_matched") val wifiMatched: Boolean?,
    @ColumnInfo(name = "likely_review") val likelyReview: Boolean,
    @ColumnInfo(name = "server_json") val serverJson: String?,
    @ColumnInfo(name = "created_at") val createdAtMillis: Long,
    @ColumnInfo(name = "updated_at") val updatedAtMillis: Long,
    @ColumnInfo(name = "last_notified_at") val lastNotifiedAtMillis: Long? = null,
) {
    /** Reference for confirm/dismiss calls: the server id when known, otherwise the client id (API.md §4 sync). */
    val ref: String get() = serverId ?: clientEventId
}

@Entity(tableName = "notifications", indices = [Index("created_at")])
data class NotificationEntity(
    @PrimaryKey val id: String,
    val type: String,
    val title: String,
    val body: String,
    @ColumnInfo(name = "data_json") val dataJson: String,
    @ColumnInfo(name = "read_at") val readAt: String?,
    @ColumnInfo(name = "created_at") val createdAt: String,
)

/** Durable queue of every mutating action, replayed by SyncWorker. */
@Entity(
    tableName = "pending_outbox",
    indices = [Index(value = ["kind", "client_id"], unique = true), Index("created_at")],
)
data class OutboxEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** event | confirmation | dismissal | manual_request | fcm_token */
    val kind: String,
    @ColumnInfo(name = "client_id") val clientId: String,
    /** server event id or client_event_id, for confirmation/dismissal. */
    @ColumnInfo(name = "event_ref") val eventRef: String?,
    @ColumnInfo(name = "payload_json") val payloadJson: String,
    val attempts: Int = 0,
    @ColumnInfo(name = "last_error") val lastError: String? = null,
    /** True once the planner decided the row will never succeed (kept for display only). */
    val dead: Boolean = false,
    @ColumnInfo(name = "next_attempt_at") val nextAttemptAtMillis: Long = 0,
    @ColumnInfo(name = "created_at") val createdAtMillis: Long,
)

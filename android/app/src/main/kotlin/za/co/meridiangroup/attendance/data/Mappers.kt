package za.co.meridiangroup.attendance.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import za.co.meridiangroup.attendance.core.WorkplaceGeometry
import za.co.meridiangroup.attendance.data.local.db.EventEntity
import za.co.meridiangroup.attendance.data.local.db.NotificationEntity
import za.co.meridiangroup.attendance.data.local.db.SessionEntity
import za.co.meridiangroup.attendance.data.local.db.TodaySummaryEntity
import za.co.meridiangroup.attendance.data.local.db.WorkplaceEntity
import za.co.meridiangroup.attendance.data.remote.ApiJson
import za.co.meridiangroup.attendance.data.remote.dto.EventDto
import za.co.meridiangroup.attendance.data.remote.dto.NotificationDto
import za.co.meridiangroup.attendance.data.remote.dto.SessionDto
import za.co.meridiangroup.attendance.data.remote.dto.TodaySummaryDto
import za.co.meridiangroup.attendance.data.remote.dto.WorkplaceDto

private val mapperJson: Json get() = ApiJson.json

fun WorkplaceDto.toEntity(configVersion: Int) = WorkplaceEntity(
    id = id, name = name, address = address, latitude = latitude, longitude = longitude,
    radiusM = radiusM, accuracyThresholdM = accuracyThresholdM,
    entryDetectionEnabled = entryDetectionEnabled, exitDetectionEnabled = exitDetectionEnabled,
    dwellSeconds = dwellSeconds, exitDebounceSeconds = exitDebounceSeconds,
    wifiVerificationEnabled = wifiVerificationEnabled, wifiSsid = wifiSsid,
    wifiBssids = wifiBssids.joinToString(",") { it.trim().lowercase() }, wifiRequired = wifiRequired,
    timezone = timezone, active = active, coordinatesConfirmed = isConfigured, configVersion = configVersion,
)

fun WorkplaceEntity.toGeometry() = WorkplaceGeometry(
    latitude = latitude, longitude = longitude, radiusM = radiusM, accuracyThresholdM = accuracyThresholdM,
    wifiVerificationEnabled = wifiVerificationEnabled, wifiSsid = wifiSsid, wifiBssids = bssidList, wifiRequired = wifiRequired,
)

fun TodaySummaryDto.toEntity(now: Long) = TodaySummaryEntity(
    workDate = workDate, json = mapperJson.encodeToString(TodaySummaryDto.serializer(), this), fetchedAtMillis = now,
)

fun TodaySummaryEntity.toDto(): TodaySummaryDto? =
    runCatching { mapperJson.decodeFromString(TodaySummaryDto.serializer(), this.json) }.getOrNull()

fun SessionDto.toEntity(now: Long) = SessionEntity(
    id = id, workDate = workDate, status = status, json = mapperJson.encodeToString(SessionDto.serializer(), this), fetchedAtMillis = now,
)

fun SessionEntity.toDto(): SessionDto? = runCatching { mapperJson.decodeFromString(SessionDto.serializer(), this.json) }.getOrNull()

fun EventEntity.serverEvent(): EventDto? = serverJson?.let { runCatching { mapperJson.decodeFromString(EventDto.serializer(), it) }.getOrNull() }

/** Merges a server Event into the local row, keeping local-only signals (Wi-Fi SSID, local verdict). */
fun EventDto.mergeInto(existing: EventEntity?, now: Long): EventEntity {
    val clientId = clientEventId ?: existing?.clientEventId ?: id
    return EventEntity(
        clientEventId = clientId,
        serverId = id,
        eventType = eventType,
        source = source,
        status = status,
        localStatus = when {
            existing == null -> "submitted"
            existing.localStatus == "confirming" && status == "pending" -> "confirming"
            existing.localStatus == "dismissing" && status == "pending" -> "dismissing"
            else -> "submitted"
        },
        workplaceId = workplaceId ?: existing?.workplaceId,
        workplaceName = workplaceName ?: existing?.workplaceName,
        detectedAt = detectedAt ?: existing?.detectedAt ?: "",
        expiresAt = expiresAt ?: existing?.expiresAt,
        latitude = location?.latitude ?: existing?.latitude,
        longitude = location?.longitude ?: existing?.longitude,
        accuracyM = location?.accuracyM ?: existing?.accuracyM,
        distanceM = distanceFromWorkplaceM ?: existing?.distanceM,
        wifiSsid = existing?.wifiSsid,
        wifiMatched = verification?.wifiMatched ?: if (wifiVerified) true else existing?.wifiMatched,
        likelyReview = existing?.likelyReview ?: false,
        serverJson = mapperJson.encodeToString(EventDto.serializer(), this),
        createdAtMillis = existing?.createdAtMillis ?: now,
        updatedAtMillis = now,
        lastNotifiedAtMillis = existing?.lastNotifiedAtMillis,
    )
}

fun NotificationDto.toEntity() = NotificationEntity(
    id = id, type = type, title = title, body = body,
    dataJson = mapperJson.encodeToString(JsonObject.serializer(), JsonObject(data.mapValues { JsonPrimitive(it.value) })),
    readAt = readAt, createdAt = createdAt ?: "",
)

fun NotificationEntity.data(): Map<String, String> = runCatching {
    mapperJson.decodeFromString(JsonObject.serializer(), dataJson).mapValues { (it.value as? JsonPrimitive)?.content ?: it.value.toString() }
}.getOrDefault(emptyMap())

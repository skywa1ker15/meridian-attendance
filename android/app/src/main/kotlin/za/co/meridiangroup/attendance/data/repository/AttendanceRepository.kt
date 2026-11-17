package za.co.meridiangroup.attendance.data.repository

import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import za.co.meridiangroup.attendance.core.ApiResult
import za.co.meridiangroup.attendance.core.DetectionSignals
import za.co.meridiangroup.attendance.core.DismissReason
import za.co.meridiangroup.attendance.core.EventType
import za.co.meridiangroup.attendance.core.GeofenceTransition
import za.co.meridiangroup.attendance.core.JohannesburgTime
import za.co.meridiangroup.attendance.core.LocalVerification
import za.co.meridiangroup.attendance.core.OutboxKind
import za.co.meridiangroup.attendance.core.OutboxResolution
import za.co.meridiangroup.attendance.core.OutboxRules
import za.co.meridiangroup.attendance.data.local.db.AppDatabase
import za.co.meridiangroup.attendance.data.local.db.EventEntity
import za.co.meridiangroup.attendance.data.local.db.WorkplaceEntity
import za.co.meridiangroup.attendance.data.mergeInto
import za.co.meridiangroup.attendance.data.remote.ApiCaller
import za.co.meridiangroup.attendance.data.remote.AttendanceApi
import za.co.meridiangroup.attendance.data.remote.dto.ConfirmEventRequestDto
import za.co.meridiangroup.attendance.data.remote.dto.ConfirmEventResponseDto
import za.co.meridiangroup.attendance.data.remote.dto.DismissEventRequestDto
import za.co.meridiangroup.attendance.data.remote.dto.EventDto
import za.co.meridiangroup.attendance.data.remote.dto.LocationDto
import za.co.meridiangroup.attendance.data.remote.dto.PageDto
import za.co.meridiangroup.attendance.data.remote.dto.SessionDetailDto
import za.co.meridiangroup.attendance.data.remote.dto.SessionDto
import za.co.meridiangroup.attendance.data.remote.dto.SubmitEventRequestDto
import za.co.meridiangroup.attendance.data.remote.dto.TodaySummaryDto
import za.co.meridiangroup.attendance.data.remote.dto.WifiDto
import za.co.meridiangroup.attendance.data.toDto
import za.co.meridiangroup.attendance.data.toEntity

/** Outcome of confirming / dismissing, as shown to the employee. */
sealed class ActionOutcome {
    /** The server answered; `status` is the new event status (confirmed / under_review / dismissed). */
    data class Done(val event: EventDto, val status: String) : ActionOutcome()

    /** Saved on the phone and queued; will be sent automatically. Nothing is confirmed yet. */
    object Queued : ActionOutcome()

    data class Failed(val message: String) : ActionOutcome()
}

class AttendanceRepository(
    private val api: AttendanceApi,
    private val caller: ApiCaller,
    private val db: AppDatabase,
    private val outbox: OutboxWriter,
    private val now: () -> Long,
) {
    private val eventDao = db.eventDao()
    private val todayDao = db.todayDao()

    val today: Flow<TodaySummaryDto?> = todayDao.observe().map { it?.toDto() }
    val awaitingConfirmation: Flow<List<EventEntity>> = eventDao.observeAwaitingConfirmation()
    fun observeEvent(ref: String): Flow<EventEntity?> = eventDao.observeByRef(ref)
    suspend fun eventByRef(ref: String): EventEntity? = eventDao.byRef(ref)

    /** Today's status from the cache, or null when the cache is missing or from another day. */
    suspend fun cachedTodayStatus(): za.co.meridiangroup.attendance.core.TodayStatus? {
        val cached = todayDao.get()?.toDto() ?: return null
        if (cached.workDate != JohannesburgTime.workDateOf(now())) return null
        return za.co.meridiangroup.attendance.core.TodayStatus.fromWire(cached.status)
    }

    suspend fun storeToday(summary: TodaySummaryDto) {
        todayDao.upsert(summary.toEntity(now()))
        summary.session?.let { db.sessionDao().upsertAll(listOf(it.toEntity(now()))) }
        summary.pendingEvents.forEach { mergeServerEvent(it) }
    }

    suspend fun mergeServerEvent(event: EventDto) {
        val existing = event.clientEventId?.let { eventDao.byRef(it) } ?: eventDao.byRef(event.id)
        val merged = event.mergeInto(existing, now())
        eventDao.upsert(merged)
        if (existing != null && existing.serverId == null) db.outboxDao().rewriteEventRef(existing.clientEventId, event.id)
    }

    suspend fun refreshToday(): ApiResult<TodaySummaryDto> {
        val r = caller.call { api.today() }
        if (r is ApiResult.Success) storeToday(r.value)
        return r
    }

    /** Pulls server-side pending events so confirmations are surfaced in-app even without push. */
    suspend fun refreshPending(): ApiResult<List<EventDto>> {
        val r = caller.call { api.pendingEvents() }.map { it.items }
        if (r is ApiResult.Success) {
            val serverIds = r.value.map { it.id }.toSet()
            r.value.forEach { mergeServerEvent(it) }
            // Local rows the server no longer lists as pending (expired, reviewed or confirmed elsewhere):
            // fetch their real status from the recent events list rather than guessing.
            val stale = eventDao.awaitingConfirmation().filter { it.serverId != null && it.serverId !in serverIds }
            if (stale.isNotEmpty()) {
                val from = JohannesburgTime.daysAgo(JohannesburgTime.workDateOf(now()), 3)
                val recent = caller.call { api.events(from = from, page = 1, pageSize = 100) }.getOrNull()?.items.orEmpty()
                val byId = recent.associateBy { it.id }
                stale.forEach { row -> byId[row.serverId]?.let { mergeServerEvent(it) } }
            }
        }
        return r
    }

    /**
     * Records a *detection* (never attendance). Writes the local pending event and the outbox row first
     * (durable even if the process dies), then tries POST /attendance/events directly.
     */
    suspend fun recordDetection(
        workplace: WorkplaceEntity,
        type: EventType,
        transition: GeofenceTransition,
        signals: DetectionSignals,
        verdict: LocalVerification,
        detectedAtMillis: Long,
    ): EventEntity {
        // Collapse repeated detections of the same type within 10 minutes (server applies duplicate_window_seconds too).
        eventDao.recentPendingOfType(type.wire, detectedAtMillis - DUPLICATE_WINDOW_MILLIS)?.let { return it }

        val clientEventId = UUID.randomUUID().toString()
        val body = SubmitEventRequestDto(
            clientEventId = clientEventId,
            workplaceId = workplace.id,
            eventType = type.wire,
            source = if (transition == GeofenceTransition.NONE) "wifi" else "geofence",
            detectedAt = JohannesburgTime.toIsoUtc(detectedAtMillis),
            deviceTimezone = java.util.TimeZone.getDefault().id,
            geofenceTransition = transition.wire,
            location = signals.location?.let { LocationDto(it.latitude, it.longitude, it.accuracyM) },
            wifi = if (signals.wifiSsid != null || signals.wifiBssid != null) WifiDto(signals.wifiSsid, signals.wifiBssid) else null,
            offlineQueued = false,
            batteryLevel = signals.batteryLevel,
        )
        val entity = EventEntity(
            clientEventId = clientEventId, serverId = null, eventType = type.wire, source = body.source,
            status = "pending", localStatus = "queued", workplaceId = workplace.id, workplaceName = workplace.name,
            detectedAt = body.detectedAt, expiresAt = null,
            latitude = signals.location?.latitude, longitude = signals.location?.longitude, accuracyM = signals.location?.accuracyM,
            distanceM = verdict.distanceM, wifiSsid = signals.wifiSsid, wifiMatched = verdict.wifiMatched,
            likelyReview = verdict.likelyUnderReview, serverJson = null,
            createdAtMillis = now(), updatedAtMillis = now(),
        )
        eventDao.insert(entity)
        val rowId = outbox.enqueue(OutboxKind.EVENT, clientEventId, null, SubmitEventRequestDto.serializer(), body)

        when (val r = caller.call { api.submitEvent(clientEventId, body) }) {
            is ApiResult.Success -> {
                outbox.remove(rowId)
                mergeServerEvent(r.value.event)
                r.value.today?.let { storeToday(it) }
            }
            is ApiResult.Failure -> {
                val res = OutboxRules.resolve(OutboxKind.EVENT, r.failure.httpStatus, r.failure.code, 0)
                if (res is OutboxResolution.Dead) {
                    outbox.remove(rowId)
                    eventDao.upsert(entity.copy(status = "rejected", localStatus = "failed", updatedAtMillis = now()))
                }
                // Retry/Done-equivalents stay in the outbox for SyncWorker.
            }
            is ApiResult.Offline -> Unit // stays queued
        }
        return eventDao.byRef(clientEventId) ?: entity
    }

    suspend fun confirm(ref: String, signals: DetectionSignals?, note: String?): ActionOutcome {
        val event = eventDao.byRef(ref) ?: return ActionOutcome.Failed("This detection is no longer available.")
        val clientConfirmationId = UUID.randomUUID().toString()
        val body = ConfirmEventRequestDto(
            clientConfirmationId = clientConfirmationId,
            confirmedAt = JohannesburgTime.toIsoUtc(now()),
            location = signals?.location?.let { LocationDto(it.latitude, it.longitude, it.accuracyM) },
            wifi = signals?.let { if (it.wifiSsid != null || it.wifiBssid != null) WifiDto(it.wifiSsid, it.wifiBssid) else null },
            note = note?.trim()?.ifBlank { null },
        )
        eventDao.setLocalStatus(event.clientEventId, "confirming", now())
        val rowId = outbox.enqueue(OutboxKind.CONFIRMATION, clientConfirmationId, event.ref, ConfirmEventRequestDto.serializer(), body)
        // Without a server id the event itself is still queued; the sync batch sends both in order.
        val serverId = event.serverId ?: return ActionOutcome.Queued
        return when (val r = caller.call { api.confirmEvent(serverId, clientConfirmationId, body) }) {
            is ApiResult.Success -> {
                outbox.remove(rowId)
                applyConfirmResponse(r.value)
                ActionOutcome.Done(r.value.event, r.value.event.status)
            }
            is ApiResult.Offline -> ActionOutcome.Queued
            is ApiResult.Failure -> {
                val res = OutboxRules.resolve(OutboxKind.CONFIRMATION, r.failure.httpStatus, r.failure.code, 0)
                if (res is OutboxResolution.Dead) {
                    outbox.remove(rowId)
                    eventDao.setLocalStatus(event.clientEventId, "submitted", now())
                    refreshPending()
                    ActionOutcome.Failed(r.userMessage() ?: "Could not confirm.")
                } else {
                    ActionOutcome.Queued
                }
            }
        }
    }

    suspend fun dismiss(ref: String, reason: DismissReason, note: String?): ActionOutcome {
        val event = eventDao.byRef(ref) ?: return ActionOutcome.Failed("This detection is no longer available.")
        val body = DismissEventRequestDto(reason.wire, note?.trim()?.ifBlank { null })
        val dismissalId = "dismiss-${event.clientEventId}"
        eventDao.setLocalStatus(event.clientEventId, "dismissing", now())
        val rowId = outbox.enqueue(OutboxKind.DISMISSAL, dismissalId, event.ref, DismissEventRequestDto.serializer(), body)
        val serverId = event.serverId ?: return ActionOutcome.Queued
        return when (val r = caller.call { api.dismissEvent(serverId, body) }) {
            is ApiResult.Success -> {
                outbox.remove(rowId)
                mergeServerEvent(r.value)
                refreshToday()
                ActionOutcome.Done(r.value, r.value.status)
            }
            is ApiResult.Offline -> ActionOutcome.Queued
            is ApiResult.Failure -> {
                val res = OutboxRules.resolve(OutboxKind.DISMISSAL, r.failure.httpStatus, r.failure.code, 0)
                if (res is OutboxResolution.Dead) {
                    outbox.remove(rowId)
                    eventDao.setLocalStatus(event.clientEventId, "submitted", now())
                    ActionOutcome.Failed(r.userMessage() ?: "Could not dismiss.")
                } else {
                    ActionOutcome.Queued
                }
            }
        }
    }

    suspend fun applyConfirmResponse(resp: ConfirmEventResponseDto) {
        mergeServerEvent(resp.event)
        resp.session?.let { db.sessionDao().upsertAll(listOf(it.toEntity(now()))) }
        resp.today?.let { storeToday(it) }
    }

    suspend fun sessions(from: String?, to: String?, page: Int, pageSize: Int = 30): ApiResult<PageDto<SessionDto>> {
        val r = caller.call { api.sessions(from, to, page, pageSize) }
        if (r is ApiResult.Success) db.sessionDao().upsertAll(r.value.items.map { it.toEntity(now()) })
        return r
    }

    suspend fun sessionDetail(id: String): ApiResult<SessionDetailDto> = caller.call { api.session(id) }

    suspend fun cachedSession(id: String): SessionDto? = db.sessionDao().byId(id)?.toDto()

    private companion object {
        const val DUPLICATE_WINDOW_MILLIS = 10 * 60 * 1000L
    }
}

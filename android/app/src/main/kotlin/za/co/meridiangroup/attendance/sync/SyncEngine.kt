package za.co.meridiangroup.attendance.sync

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import za.co.meridiangroup.attendance.core.ApiResult
import za.co.meridiangroup.attendance.core.OutboxItem
import za.co.meridiangroup.attendance.core.OutboxKind
import za.co.meridiangroup.attendance.core.OutboxResolution
import za.co.meridiangroup.attendance.core.OutboxRules
import za.co.meridiangroup.attendance.data.local.AppPreferences
import za.co.meridiangroup.attendance.data.local.db.AppDatabase
import za.co.meridiangroup.attendance.data.local.db.OutboxEntity
import za.co.meridiangroup.attendance.data.remote.ApiCaller
import za.co.meridiangroup.attendance.data.remote.ApiJson
import za.co.meridiangroup.attendance.data.remote.AttendanceApi
import za.co.meridiangroup.attendance.data.remote.dto.FcmTokenRequestDto
import za.co.meridiangroup.attendance.data.remote.dto.ManualRequestCreateDto
import za.co.meridiangroup.attendance.data.remote.dto.SyncItemDto
import za.co.meridiangroup.attendance.data.remote.dto.SyncRequestDto
import za.co.meridiangroup.attendance.data.repository.AttendanceRepository
import za.co.meridiangroup.attendance.data.repository.NotificationRepository

/** Called when an outbox item can never succeed, so the employee is told (attendance_submission_failed). */
fun interface SyncFailureListener {
    fun onPermanentFailure(kind: OutboxKind, clientId: String, reason: String)
}

data class SyncRunResult(val sent: Int, val remaining: Int, val needsRetry: Boolean, val offline: Boolean)

/**
 * Replays the pending outbox in order via POST /attendance/sync (event/confirmation/dismissal) and individual
 * calls (manual_request, fcm_token), then reconciles today's summary, pending events and the inbox.
 * Every item carries its own client id, so replays are idempotent server-side.
 */
class SyncEngine(
    private val api: AttendanceApi,
    private val caller: ApiCaller,
    private val db: AppDatabase,
    private val attendance: AttendanceRepository,
    private val notifications: NotificationRepository,
    private val prefs: AppPreferences,
    private val failureListener: SyncFailureListener,
    private val now: () -> Long,
) {
    private val mutex = Mutex()
    private val _syncing = MutableStateFlow(false)
    val syncing: StateFlow<Boolean> = _syncing.asStateFlow()

    suspend fun run(reconcile: Boolean = true): SyncRunResult = mutex.withLock {
        _syncing.value = true
        try {
            runLocked(reconcile)
        } finally {
            _syncing.value = false
        }
    }

    private suspend fun runLocked(reconcile: Boolean): SyncRunResult {
        val dao = db.outboxDao()
        val rows = dao.due(now())
        val plan = OutboxRules.plan(rows.mapNotNull { it.toItem() })
        if (plan.duplicates.isNotEmpty()) dao.delete(plan.duplicates.map { it.id })

        var sent = 0
        var offline = false

        // 1) Attendance batch, in chunks, strictly in order.
        for (chunk in plan.syncBatch.chunked(BATCH_SIZE)) {
            val request = SyncRequestDto(chunk.map { item -> item.toSyncItem() })
            when (val r = caller.call { api.sync(request) }) {
                is ApiResult.Success -> {
                    val results = r.value.results.associateBy { it.index }
                    chunk.forEachIndexed { index, item ->
                        val res = results[index]
                        val status = res?.status?.takeIf { it > 0 } ?: if (res?.ok == true) 200 else null
                        val resolution = OutboxRules.resolve(item.kind, status, res?.error?.code, item.attempts, ok = res?.ok == true)
                        res?.event?.let { attendance.mergeServerEvent(it) }
                        if (resolution == OutboxResolution.Done) sent++
                        apply(item, resolution)
                    }
                    r.value.today?.let { attendance.storeToday(it) }
                }
                is ApiResult.Offline -> {
                    offline = true
                    chunk.forEach { apply(it, OutboxRules.resolve(it.kind, null, null, it.attempts)) }
                }
                is ApiResult.Failure -> chunk.forEach {
                    apply(it, OutboxRules.resolve(it.kind, r.failure.httpStatus, r.failure.code, it.attempts))
                }
            }
            if (offline) break
        }

        // 2) Individually-sent rows.
        if (!offline) {
            for (item in plan.individual) {
                val result: ApiResult<*> = when (item.kind) {
                    OutboxKind.MANUAL_REQUEST -> {
                        val body = ApiJson.json.decodeFromString(ManualRequestCreateDto.serializer(), item.payloadJson)
                        caller.call { api.createManualRequest(item.clientId, body) }
                    }
                    OutboxKind.FCM_TOKEN -> {
                        val body = ApiJson.json.decodeFromString(FcmTokenRequestDto.serializer(), item.payloadJson)
                        caller.call { api.uploadFcmToken(body) }.also { if (it is ApiResult.Success) prefs.setFcmTokenUploaded(body.fcmToken) }
                    }
                    else -> continue
                }
                val resolution = when (result) {
                    is ApiResult.Success<*> -> OutboxResolution.Done
                    is ApiResult.Offline -> { offline = true; OutboxRules.resolve(item.kind, null, null, item.attempts) }
                    is ApiResult.Failure -> OutboxRules.resolve(item.kind, result.failure.httpStatus, result.failure.code, item.attempts)
                }
                if (resolution == OutboxResolution.Done) sent++
                apply(item, resolution)
                if (offline) break
            }
        }

        // 3) Reconcile with the server's view.
        if (reconcile && !offline) {
            attendance.refreshToday()
            attendance.refreshPending()
            notifications.refresh()
        }
        if (!offline) prefs.setLastSync(now())

        val remaining = dao.queuedCount()
        return SyncRunResult(sent = sent, remaining = remaining, needsRetry = remaining > 0, offline = offline)
    }

    private suspend fun apply(item: OutboxItem, resolution: OutboxResolution) {
        val dao = db.outboxDao()
        when (resolution) {
            OutboxResolution.Done -> dao.delete(listOf(item.id))
            is OutboxResolution.Retry -> dao.markRetry(item.id, resolution.reason, now() + resolution.backoffMillis)
            is OutboxResolution.Dead -> {
                dao.markDead(item.id, resolution.reason)
                // Release the local "confirming"/"dismissing" lock so the employee can act again or see the failure.
                val ref = if (item.kind == OutboxKind.EVENT) item.clientId else item.eventRef
                ref?.let { attendance.eventByRef(it) }?.let { ev ->
                    db.eventDao().setLocalStatus(ev.clientEventId, if (item.kind == OutboxKind.EVENT) "failed" else "submitted", now())
                }
                failureListener.onPermanentFailure(item.kind, item.clientId, resolution.reason)
            }
        }
    }

    private fun OutboxEntity.toItem(): OutboxItem? {
        val k = OutboxKind.fromWire(kind) ?: return null
        return OutboxItem(id, k, clientId, eventRef, payloadJson, attempts, createdAtMillis)
    }

    private fun OutboxItem.toSyncItem(): SyncItemDto {
        var payload = ApiJson.json.decodeFromString(JsonObject.serializer(), payloadJson)
        if (kind == OutboxKind.EVENT) {
            // Anything replayed from the queue is, by definition, offline-queued (API.md §4 skew rules).
            payload = JsonObject(payload + ("offline_queued" to JsonPrimitive(true)))
        }
        return SyncItemDto(kind = kind.wire, eventId = if (kind == OutboxKind.EVENT) null else eventRef, payload = payload)
    }

    private companion object {
        const val BATCH_SIZE = 50
    }
}

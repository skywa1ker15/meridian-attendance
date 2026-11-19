package za.co.meridiangroup.attendance.data.repository

import kotlinx.serialization.KSerializer
import za.co.meridiangroup.attendance.core.OutboxKind
import za.co.meridiangroup.attendance.core.OutboxRules
import za.co.meridiangroup.attendance.data.local.db.OutboxDao
import za.co.meridiangroup.attendance.data.local.db.OutboxEntity
import za.co.meridiangroup.attendance.data.remote.ApiJson

/** Writes durable outbox rows. Inserting the same (kind, client_id) twice is a no-op (idempotent). */
class OutboxWriter(private val dao: OutboxDao, private val now: () -> Long) {
    suspend fun <T> enqueue(kind: OutboxKind, clientId: String, eventRef: String?, serializer: KSerializer<T>, payload: T): Long {
        val rowId = dao.insert(
            OutboxEntity(
                kind = kind.wire,
                clientId = clientId,
                eventRef = eventRef,
                payloadJson = ApiJson.json.encodeToString(serializer, payload),
                createdAtMillis = now(),
            ),
        )
        // Trim the outbox when it grows past the safety cap (oldest dead rows first, then oldest live).
        val total = dao.totalCount()
        if (total > OutboxRules.MAX_OUTBOX_SIZE) {
            dao.trimOldest(total - OutboxRules.MAX_OUTBOX_SIZE)
        }
        return rowId
    }

    suspend fun remove(rowId: Long) {
        if (rowId > 0) dao.delete(listOf(rowId))
    }
}

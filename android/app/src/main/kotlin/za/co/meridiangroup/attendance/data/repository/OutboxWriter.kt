package za.co.meridiangroup.attendance.data.repository

import kotlinx.serialization.KSerializer
import za.co.meridiangroup.attendance.core.OutboxKind
import za.co.meridiangroup.attendance.data.local.db.OutboxDao
import za.co.meridiangroup.attendance.data.local.db.OutboxEntity
import za.co.meridiangroup.attendance.data.remote.ApiJson

/** Writes durable outbox rows. Inserting the same (kind, client_id) twice is a no-op (idempotent). */
class OutboxWriter(private val dao: OutboxDao, private val now: () -> Long) {
    suspend fun <T> enqueue(kind: OutboxKind, clientId: String, eventRef: String?, serializer: KSerializer<T>, payload: T): Long =
        dao.insert(
            OutboxEntity(
                kind = kind.wire,
                clientId = clientId,
                eventRef = eventRef,
                payloadJson = ApiJson.json.encodeToString(serializer, payload),
                createdAtMillis = now(),
            ),
        )

    suspend fun remove(rowId: Long) {
        if (rowId > 0) dao.delete(listOf(rowId))
    }
}

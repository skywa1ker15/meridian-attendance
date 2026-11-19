package za.co.meridiangroup.attendance.core

/** A row of the local `pending_outbox` table as seen by the planner (no Room types here). */
data class OutboxItem(
    val id: Long,
    val kind: OutboxKind,
    /** client_event_id / client_confirmation_id / client_request_id / token hash — the idempotency key. */
    val clientId: String,
    /** For confirmations/dismissals: the server event id, or the client_event_id when the server id is unknown. */
    val eventRef: String?,
    val payloadJson: String,
    val attempts: Int,
    val createdAtMillis: Long,
)

/** What the sync worker should send: one ordered batch for /attendance/sync and the rest individually. */
data class OutboxPlan(
    val syncBatch: List<OutboxItem>,
    val individual: List<OutboxItem>,
    /** Rows that are duplicates (same kind + client id as an earlier row) and can be deleted. */
    val duplicates: List<OutboxItem>,
)

/** Outcome of one replayed item. */
sealed class OutboxResolution {
    /** Server accepted (or already had) the item: delete the row. */
    object Done : OutboxResolution()

    /** Transient failure: keep the row and retry after `backoffMillis`. */
    data class Retry(val backoffMillis: Long, val reason: String) : OutboxResolution()

    /** Permanent failure: the row will never succeed; keep it only for display (`last_error`) and stop retrying. */
    data class Dead(val reason: String) : OutboxResolution()
}

object OutboxRules {
    const val MAX_ATTEMPTS = 25
    const val BASE_BACKOFF_MILLIS = 30_000L
    const val MAX_BACKOFF_MILLIS = 60 * 60 * 1000L
    const val PENDING_APPROVAL_BACKOFF_MILLIS = 15 * 60 * 1000L
    /** Safety cap on outbox rows. When exceeded, the oldest dead rows are pruned first; if still
     *  over the limit the oldest live rows are marked dead with "outbox_overflow". */
    const val MAX_OUTBOX_SIZE = 500

    /**
     * Orders rows by creation time then id (so a confirmation always follows the event it refers to),
     * drops duplicate idempotency keys, and splits rows between the batch endpoint and single calls.
     */
    fun plan(items: List<OutboxItem>): OutboxPlan {
        val sorted = items.sortedWith(compareBy<OutboxItem> { it.createdAtMillis }.thenBy { it.id })
        val seen = HashSet<String>()
        val batch = ArrayList<OutboxItem>()
        val single = ArrayList<OutboxItem>()
        val dupes = ArrayList<OutboxItem>()
        for (item in sorted) {
            val key = "${item.kind.wire}:${item.clientId}"
            if (!seen.add(key)) { dupes += item; continue }
            if (item.kind.goesThroughSyncEndpoint) batch += item else single += item
        }
        // A confirmation/dismissal whose event is still unsent must come after that event: the sort by
        // created_at guarantees this because the event row is always written first. Defensive re-order:
        val eventIndex = HashMap<String, Int>()
        batch.forEachIndexed { i, it -> if (it.kind == OutboxKind.EVENT) eventIndex[it.clientId] = i }
        val reordered = batch.sortedWith(
            compareBy<OutboxItem> { item ->
                val dep = item.eventRef?.let { eventIndex[it] }
                if (item.kind != OutboxKind.EVENT && dep != null) dep + 0.5 else batch.indexOf(item).toDouble()
            },
        )
        return OutboxPlan(reordered, single, dupes)
    }

    fun backoffMillis(attempts: Int): Long {
        val shift = attempts.coerceIn(0, 12)
        val value = BASE_BACKOFF_MILLIS shl shift
        return value.coerceAtMost(MAX_BACKOFF_MILLIS)
    }

    /**
     * Decides what to do with an item after one attempt.
     * @param httpStatus null when the request never reached the server (network failure / timeout).
     */
    fun resolve(kind: OutboxKind, httpStatus: Int?, errorCode: String?, attempts: Int, ok: Boolean = httpStatus in 200..299): OutboxResolution {
        val nextAttempts = attempts + 1
        fun retry(reason: String, backoff: Long = backoffMillis(nextAttempts)): OutboxResolution =
            if (nextAttempts >= MAX_ATTEMPTS) OutboxResolution.Dead("max_attempts:$reason") else OutboxResolution.Retry(backoff, reason)

        if (ok) return OutboxResolution.Done
        if (httpStatus == null) return retry("network")
        return when (httpStatus) {
            401 -> retry("unauthenticated")
            403 -> when (errorCode) {
                "device_pending_approval" -> retry("device_pending_approval", PENDING_APPROVAL_BACKOFF_MILLIS)
                "password_change_required" -> retry("password_change_required", PENDING_APPROVAL_BACKOFF_MILLIS)
                else -> OutboxResolution.Dead(errorCode ?: "forbidden")
            }
            408, 425, 429, 502, 503, 504 -> retry("server_busy")
            in 500..599 -> retry("server_error")
            409 -> when (kind) {
                // duplicate / already-exists: the server has it, which is what we wanted.
                OutboxKind.EVENT, OutboxKind.MANUAL_REQUEST -> if (errorCode == "duplicate" || errorCode == "conflict") OutboxResolution.Done else OutboxResolution.Dead(errorCode ?: "conflict")
                else -> OutboxResolution.Dead(errorCode ?: "conflict")
            }
            else -> OutboxResolution.Dead(errorCode ?: "http_$httpStatus")
        }
    }
}

package za.co.meridiangroup.attendance.core

/** What the sync status chip shows, derived from the outbox and connectivity. */
data class SyncStatus(
    val online: Boolean,
    val syncing: Boolean,
    val queued: Int,
    val dead: Int,
    val lastSyncMillis: Long?,
) {
    enum class Kind { SYNCED, SYNCING, QUEUED, OFFLINE, FAILED }

    val kind: Kind
        get() = when {
            syncing -> Kind.SYNCING
            dead > 0 -> Kind.FAILED
            !online && queued > 0 -> Kind.OFFLINE
            !online -> Kind.OFFLINE
            queued > 0 -> Kind.QUEUED
            else -> Kind.SYNCED
        }

    fun label(nowMillis: Long): String = when (kind) {
        Kind.SYNCING -> "Syncing…"
        Kind.FAILED -> if (dead == 1) "1 item needs attention" else "$dead items need attention"
        Kind.OFFLINE -> if (queued > 0) "Offline · $queued queued" else "Offline"
        Kind.QUEUED -> if (queued == 1) "1 item queued" else "$queued items queued"
        Kind.SYNCED -> lastSyncMillis?.let { "Synced ${JohannesburgTime.relative(java.time.Instant.ofEpochMilli(it), nowMillis)}" } ?: "Synced"
    }

    val tone: StatusTone
        get() = when (kind) {
            Kind.SYNCED -> StatusTone.SUCCESS
            Kind.SYNCING, Kind.QUEUED -> StatusTone.WARNING
            Kind.OFFLINE -> StatusTone.NEUTRAL
            Kind.FAILED -> StatusTone.ERROR
        }
}

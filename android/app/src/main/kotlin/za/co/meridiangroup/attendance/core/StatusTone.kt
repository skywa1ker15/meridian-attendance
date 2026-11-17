package za.co.meridiangroup.attendance.core

/**
 * Semantic colour tone for a status value. Mapping is fixed by docs/DESIGN.md:
 * confirmed/closed/clocked_in → success; pending/under_review/late/missing_clock_out → warning;
 * rejected/expired/revoked/inactive → error; dismissed/not_started/clocked_out → neutral.
 */
enum class StatusTone { SUCCESS, WARNING, ERROR, NEUTRAL }

object StatusTones {
    private val success = setOf("confirmed", "closed", "clocked_in", "approved", "active", "open", "ok", "synced")
    private val warning = setOf(
        "pending", "under_review", "late", "missing_clock_out", "pending_arrival", "pending_departure",
        "pending_approval", "syncing", "queued",
    )
    private val error = setOf("rejected", "expired", "revoked", "inactive", "failed", "error", "offline")

    fun forStatus(status: String?): StatusTone = when (status?.lowercase()) {
        null -> StatusTone.NEUTRAL
        in success -> StatusTone.SUCCESS
        in warning -> StatusTone.WARNING
        in error -> StatusTone.ERROR
        else -> StatusTone.NEUTRAL
    }

    fun forToday(status: TodayStatus): StatusTone = forStatus(status.wire)
    fun forEvent(status: EventStatus): StatusTone = forStatus(status.wire)
    fun forSession(status: SessionStatus): StatusTone = forStatus(status.wire)
    fun forRequest(status: RequestStatus): StatusTone = forStatus(status.wire)

    /** Human label for a wire value: `under_review` → `Under review`. */
    fun label(wire: String?): String {
        if (wire.isNullOrBlank()) return "Unknown"
        val words = wire.split('_').filter { it.isNotBlank() }
        return words.joinToString(" ") { it.lowercase() }.replaceFirstChar { it.uppercase() }
    }
}

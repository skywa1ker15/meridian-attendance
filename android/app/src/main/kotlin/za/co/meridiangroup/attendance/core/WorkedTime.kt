package za.co.meridiangroup.attendance.core

import java.time.Instant

/** Formatting and arithmetic for worked minutes shown on the dashboard, history and detail screens. */
object WorkedTime {
    /** `312` → `5h 12m`, `60` → `1h 00m`, `0` → `0m`, negative/null → `—`. */
    fun format(minutes: Int?): String {
        if (minutes == null || minutes < 0) return "—"
        val h = minutes / 60
        val m = minutes % 60
        return when {
            h == 0 -> "${m}m"
            else -> "${h}h ${m.toString().padStart(2, '0')}m"
        }
    }

    /** `312` → `5.2 h` (one decimal). */
    fun formatDecimalHours(minutes: Int?): String {
        if (minutes == null || minutes < 0) return "—"
        val hours = minutes / 60.0
        return String.format(java.util.Locale.UK, "%.1f h", hours)
    }

    /** Progress of the hours ring in [0,1]; a standard day of 0 minutes yields 0 to avoid division by zero. */
    fun ringProgress(workedMinutes: Int?, standardDayMinutes: Int): Float {
        if (workedMinutes == null || workedMinutes <= 0 || standardDayMinutes <= 0) return 0f
        return (workedMinutes.toFloat() / standardDayMinutes.toFloat()).coerceIn(0f, 1f)
    }

    /**
     * Minutes worked so far for an open session: now − clock-in − break. Used only to *display* a
     * live counter; the server's `worked_minutes` is authoritative once a session is closed.
     */
    fun liveMinutes(clockInAt: Instant?, nowMillis: Long, breakMinutes: Int = 0, serverWorkedMinutes: Int? = null, isOpen: Boolean = true): Int {
        if (!isOpen) return serverWorkedMinutes ?: 0
        if (clockInAt == null) return serverWorkedMinutes ?: 0
        val elapsed = ((nowMillis - clockInAt.toEpochMilli()) / 60_000L).toInt() - breakMinutes
        val live = elapsed.coerceAtLeast(0)
        // Never show less than what the server already counted.
        return maxOf(live, serverWorkedMinutes ?: 0)
    }

    /** Minutes remaining until a pending event expires; null when unknown, 0 when already expired. */
    fun minutesUntil(expiresAt: Instant?, nowMillis: Long): Long? {
        if (expiresAt == null) return null
        return ((expiresAt.toEpochMilli() - nowMillis) / 60_000L).coerceAtLeast(0)
    }
}

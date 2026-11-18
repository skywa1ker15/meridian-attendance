package za.co.meridiangroup.attendance.core

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * All company-facing times are shown in Africa/Johannesburg (SAST, UTC+2, no DST).
 * Wire timestamps are ISO-8601 UTC with a `Z` suffix (docs/API.md).
 */
object JohannesburgTime {
    val zone: ZoneId = ZoneId.of("Africa/Johannesburg")
    private val locale: Locale = Locale.forLanguageTag("en-ZA")

    private val timeFmt = DateTimeFormatter.ofPattern("HH:mm", locale)
    private val dateFmt = DateTimeFormatter.ofPattern("EEE, d MMM yyyy", locale)
    private val shortDateFmt = DateTimeFormatter.ofPattern("EEE d MMM", locale)
    private val dateTimeFmt = DateTimeFormatter.ofPattern("EEE d MMM, HH:mm", locale)
    private val isoUtc = DateTimeFormatter.ISO_INSTANT

    /** Parses `2026-11-16T06:02:11Z`, `2026-11-16T08:02:00+02:00` or a fractional-second variant. */
    fun parse(value: String?): Instant? {
        if (value.isNullOrBlank()) return null
        return runCatching { Instant.parse(value) }
            .recoverCatching { OffsetDateTime.parse(value).toInstant() }
            .recoverCatching { LocalDateTime.parse(value).atOffset(ZoneOffset.UTC).toInstant() }
            .getOrNull()
    }

    /** Formats as UTC ISO-8601 with `Z` and whole-second precision, e.g. `2026-11-16T06:02:11Z`. */
    fun toIsoUtc(instant: Instant): String = isoUtc.format(instant.truncatedTo(ChronoUnit.SECONDS))
    fun toIsoUtc(epochMillis: Long): String = toIsoUtc(Instant.ofEpochMilli(epochMillis))

    fun time(instant: Instant?): String = instant?.atZone(zone)?.format(timeFmt) ?: "—"
    fun time(iso: String?): String = time(parse(iso))
    fun date(instant: Instant?): String = instant?.atZone(zone)?.format(dateFmt) ?: "—"
    fun dateTime(instant: Instant?): String = instant?.atZone(zone)?.format(dateTimeFmt) ?: "—"
    fun dateTime(iso: String?): String = dateTime(parse(iso))

    /** `2026-11-16` → `Mon 16 Nov`; invalid input is echoed back unchanged. */
    fun workDate(workDate: String?): String {
        if (workDate.isNullOrBlank()) return "—"
        return runCatching { LocalDate.parse(workDate).format(shortDateFmt) }.getOrDefault(workDate)
    }

    fun workDateLong(workDate: String?): String {
        if (workDate.isNullOrBlank()) return "—"
        return runCatching { LocalDate.parse(workDate).format(dateFmt) }.getOrDefault(workDate)
    }

    /** Today's `work_date` (calendar date in Johannesburg) for a given instant. */
    fun workDateOf(instant: Instant): String = instant.atZone(zone).toLocalDate().toString()
    fun workDateOf(epochMillis: Long): String = workDateOf(Instant.ofEpochMilli(epochMillis))

    fun isWeekend(workDate: String): Boolean = runCatching {
        val d = LocalDate.parse(workDate).dayOfWeek
        d == DayOfWeek.SATURDAY || d == DayOfWeek.SUNDAY
    }.getOrDefault(false)

    /** Combines a local work date and `HH:mm` local time into a UTC instant. */
    fun localToInstant(workDate: String, localTime: String): Instant? = runCatching {
        LocalDateTime.of(LocalDate.parse(workDate), LocalTime.parse(localTime)).atZone(zone).toInstant()
    }.getOrNull()

    /** Local (SAST) `YYYY-MM-DD` and `HH:mm` of a UTC timestamp, for pre-filling date/time inputs. */
    fun localParts(iso: String?): Pair<String, String>? = parse(iso)?.atZone(zone)?.let { z ->
        z.toLocalDate().toString() to z.toLocalTime().format(timeFmt)
    }

    /** Local date + `HH:mm` (SAST) → ISO-8601 UTC wire string, or null when either part is invalid. */
    fun localToIsoUtc(workDate: String?, localTime: String?): String? {
        if (workDate.isNullOrBlank() || localTime.isNullOrBlank()) return null
        return localToInstant(workDate.trim(), localTime.trim())?.let { toIsoUtc(it) }
    }

    /** `just now`, `3 min ago`, `2 h ago`, `yesterday`, or a date for anything older. */
    fun relative(instant: Instant?, nowMillis: Long): String {
        if (instant == null) return "—"
        val diff = nowMillis - instant.toEpochMilli()
        if (diff < 0) return time(instant)
        val minutes = diff / 60_000
        return when {
            minutes < 1 -> "just now"
            minutes < 60 -> "$minutes min ago"
            minutes < 24 * 60 -> "${minutes / 60} h ago"
            minutes < 48 * 60 -> "yesterday"
            else -> workDate(workDateOf(instant))
        }
    }

    /** Start (Monday) and end of the week containing `workDate`, as a pair of `YYYY-MM-DD` strings. */
    fun weekBounds(workDate: String): Pair<String, String> {
        val d = LocalDate.parse(workDate)
        val monday = d.minusDays((d.dayOfWeek.value - 1).toLong())
        return monday.toString() to monday.plusDays(6).toString()
    }

    fun monthBounds(workDate: String): Pair<String, String> {
        val d = LocalDate.parse(workDate)
        val first = d.withDayOfMonth(1)
        return first.toString() to first.plusMonths(1).minusDays(1).toString()
    }

    fun daysAgo(workDate: String, days: Long): String = LocalDate.parse(workDate).minusDays(days).toString()
}

package za.co.meridiangroup.attendance.core

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TimeAndWorkedTimeTest {
    @Test
    fun parsesAllApiTimestampShapes() {
        val a = JohannesburgTime.parse("2026-11-16T06:02:11Z")!!
        val b = JohannesburgTime.parse("2026-11-16T08:02:11+02:00")!!
        val c = JohannesburgTime.parse("2026-11-16T06:02:11.123456Z")!!
        assertEquals(a, b)
        assertEquals(a.epochSecond, c.epochSecond)
        assertNull(JohannesburgTime.parse(null))
        assertNull(JohannesburgTime.parse("not a date"))
    }

    @Test
    fun formatsInJohannesburg() {
        val i = Instant.parse("2026-11-16T06:02:11Z")
        assertEquals("08:02", JohannesburgTime.time(i))
        assertEquals("Mon, 16 Nov 2026", JohannesburgTime.date(i))
        assertEquals("Mon 16 Nov, 08:02", JohannesburgTime.dateTime(i))
        assertEquals("2026-11-16", JohannesburgTime.workDateOf(i))
        // 23:30 UTC is already the next day in SAST
        assertEquals("2026-11-17", JohannesburgTime.workDateOf(Instant.parse("2026-11-16T23:30:00Z")))
    }

    @Test
    fun isoUtcOutputHasZAndSecondPrecision() {
        assertEquals("2026-11-16T06:02:11Z", JohannesburgTime.toIsoUtc(Instant.parse("2026-11-16T06:02:11.987Z")))
    }

    @Test
    fun workDateLabels() {
        assertEquals("Mon 16 Nov", JohannesburgTime.workDate("2026-11-16"))
        assertEquals("garbage", JohannesburgTime.workDate("garbage"))
        assertEquals("—", JohannesburgTime.workDate(null))
        assertTrue(JohannesburgTime.isWeekend("2026-11-15"))
        assertTrue(!JohannesburgTime.isWeekend("2026-11-16"))
    }

    @Test
    fun localToInstantUsesSast() {
        assertEquals(Instant.parse("2026-11-13T06:00:00Z"), JohannesburgTime.localToInstant("2026-11-13", "08:00"))
    }

    @Test
    fun relativeLabels() {
        val now = Instant.parse("2026-11-16T10:00:00Z").toEpochMilli()
        assertEquals("just now", JohannesburgTime.relative(Instant.parse("2026-11-16T09:59:40Z"), now))
        assertEquals("3 min ago", JohannesburgTime.relative(Instant.parse("2026-11-16T09:57:00Z"), now))
        assertEquals("2 h ago", JohannesburgTime.relative(Instant.parse("2026-11-16T08:00:00Z"), now))
        assertEquals("yesterday", JohannesburgTime.relative(Instant.parse("2026-11-15T08:00:00Z"), now))
        assertEquals("Tue 10 Nov", JohannesburgTime.relative(Instant.parse("2026-11-10T08:00:00Z"), now))
    }

    @Test
    fun weekAndMonthBounds() {
        assertEquals("2026-11-16" to "2026-11-22", JohannesburgTime.weekBounds("2026-11-18"))
        assertEquals("2026-11-01" to "2026-11-30", JohannesburgTime.monthBounds("2026-11-18"))
        assertEquals("2026-02-01" to "2026-02-28", JohannesburgTime.monthBounds("2026-02-10"))
    }

    @Test
    fun workedMinutesFormatting() {
        assertEquals("5h 12m", WorkedTime.format(312))
        assertEquals("1h 00m", WorkedTime.format(60))
        assertEquals("45m", WorkedTime.format(45))
        assertEquals("0m", WorkedTime.format(0))
        assertEquals("—", WorkedTime.format(null))
        assertEquals("—", WorkedTime.format(-3))
        assertEquals("5.2 h", WorkedTime.formatDecimalHours(312))
    }

    @Test
    fun ringProgressIsClamped() {
        assertEquals(0.5f, WorkedTime.ringProgress(240, 480))
        assertEquals(1f, WorkedTime.ringProgress(900, 480))
        assertEquals(0f, WorkedTime.ringProgress(100, 0))
        assertEquals(0f, WorkedTime.ringProgress(null, 480))
    }

    @Test
    fun liveMinutesNeverBelowServerValue() {
        val clockIn = Instant.parse("2026-11-16T06:00:00Z")
        val now = Instant.parse("2026-11-16T08:30:00Z").toEpochMilli()
        assertEquals(150, WorkedTime.liveMinutes(clockIn, now))
        assertEquals(120, WorkedTime.liveMinutes(clockIn, now, breakMinutes = 30))
        assertEquals(200, WorkedTime.liveMinutes(clockIn, now, serverWorkedMinutes = 200))
        assertEquals(312, WorkedTime.liveMinutes(clockIn, now, serverWorkedMinutes = 312, isOpen = false))
        assertEquals(0, WorkedTime.liveMinutes(null, now))
    }

    @Test
    fun minutesUntilExpiry() {
        val now = Instant.parse("2026-11-16T08:00:00Z").toEpochMilli()
        assertEquals(90L, WorkedTime.minutesUntil(Instant.parse("2026-11-16T09:30:00Z"), now))
        assertEquals(0L, WorkedTime.minutesUntil(Instant.parse("2026-11-16T07:30:00Z"), now))
        assertNull(WorkedTime.minutesUntil(null, now))
    }
}

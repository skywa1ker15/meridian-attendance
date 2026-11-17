package za.co.meridiangroup.attendance.core

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ApiErrorsValidationEnumsTest {
    @Test
    fun logoutCodes() {
        assertTrue(ApiErrorMapper.requiresLogout(401, "token_reused"))
        assertTrue(ApiErrorMapper.requiresLogout(401, "token_invalid"))
        assertTrue(ApiErrorMapper.requiresLogout(403, "device_revoked"))
        assertTrue(ApiErrorMapper.requiresLogout(403, "account_inactive"))
        assertFalse(ApiErrorMapper.requiresLogout(401, "token_expired"))
        assertFalse(ApiErrorMapper.requiresLogout(401, "invalid_credentials"))
        assertFalse(ApiErrorMapper.requiresLogout(403, "forbidden"))
    }

    @Test
    fun userMessages() {
        assertEquals("Your employee number/email or password is incorrect.", ApiErrorMapper.userMessage(ApiFailure(401, "invalid_credentials", "x")))
        assertEquals("Too many requests. Please try again in 30 seconds.", ApiErrorMapper.userMessage(ApiFailure(429, "rate_limited", "", retryAfterSeconds = 30)))
        assertEquals("Reason: too short", ApiErrorMapper.userMessage(ApiFailure(422, "validation_error", "", mapOf("reason" to "too short"))))
        assertEquals("Server said no", ApiErrorMapper.userMessage(ApiFailure(400, "invalid_state", "Server said no")))
        assertEquals("The server is having trouble. Please try again later.", ApiErrorMapper.userMessage(ApiFailure(502, "weird", "")))
        assertEquals(ApiErrorMapper.OFFLINE_MESSAGE, ApiResult.Offline("timeout").userMessage())
        assertNull(ApiResult.Success(1).userMessage())
    }

    @Test
    fun fallbackCodes() {
        assertEquals("token_invalid", ApiErrorMapper.fallback(401).code)
        assertEquals("server_error", ApiErrorMapper.fallback(503).code)
        assertEquals("http_418", ApiErrorMapper.fallback(418).code)
        assertTrue(ApiFailure(503, "server_error", "").isRetryable)
        assertFalse(ApiFailure(400, "validation_error", "").isRetryable)
    }

    @Test
    fun apiResultMap() {
        assertEquals(ApiResult.Success(4), ApiResult.Success(2).map { it * 2 })
        val f: ApiResult<Int> = ApiResult.Failure(ApiFailure(500, "server_error", ""))
        assertEquals(f, f.map { it * 2 })
    }

    @Test
    fun identifierValidation() {
        assertNull(Validation.identifier("MG-0042"))
        assertNull(Validation.identifier("t.naidoo@example.co.za"))
        assertEquals("Enter your employee number or email address.", Validation.identifier("  "))
        assertTrue(Validation.identifier("bad@") != null)
        assertTrue(Validation.identifier("!") != null)
    }

    @Test
    fun passwordPolicy() {
        assertTrue(Validation.newPassword("short", "MG-0042").isNotEmpty())
        assertTrue(Validation.newPassword("MG-0042", "MG-0042").any { it.contains("same") })
        assertTrue(Validation.newPassword("correct horse battery", "MG-0042", "different").any { it.contains("match") })
        assertTrue(Validation.newPassword("correct horse battery", "MG-0042", "correct horse battery").isEmpty())
    }

    @Test
    fun manualRequestValidation() {
        val now = Instant.parse("2026-11-16T10:00:00Z").toEpochMilli()
        val ok = Validation.manualRequest(
            Validation.ManualRequestInput(RequestType.MISSED_CLOCK_OUT, "2026-11-13", null, Instant.parse("2026-11-13T15:05:00Z"), "Phone battery died", now),
        )
        assertTrue(ok.isEmpty(), ok.toString())

        val bad = Validation.manualRequest(
            Validation.ManualRequestInput(RequestType.FULL_DAY, "2026-11-20", Instant.parse("2026-11-13T15:05:00Z"), Instant.parse("2026-11-13T06:00:00Z"), "x", now),
        )
        assertEquals("The date can't be in the future.", bad["work_date"])
        assertEquals("Clock-out must be after clock-in.", bad["requested_clock_out"])
        assertTrue(bad.containsKey("reason"))

        val missing = Validation.manualRequest(Validation.ManualRequestInput(RequestType.MISSED_CLOCK_IN, null, null, null, "valid reason", now))
        assertEquals("Choose the date.", missing["work_date"])
        assertEquals("Enter the clock-in time.", missing["requested_clock_in"])
        assertFalse(missing.containsKey("requested_clock_out"))
    }

    @Test
    fun profileCorrectionValidation() {
        assertNull(Validation.profileCorrection("email", "a@b.co"))
        assertTrue(Validation.profileCorrection("email", "nope") != null)
        assertNull(Validation.profileCorrection("phone", "+27 82 123 4567"))
        assertTrue(Validation.profileCorrection("phone", "") != null)
        assertTrue(Validation.profileCorrection("colour", "blue") != null)
    }

    @Test
    fun enumsFallBackToUnknown() {
        assertEquals(EventStatus.UNDER_REVIEW, EventStatus.fromWire("under_review"))
        assertEquals(EventStatus.UNKNOWN, EventStatus.fromWire("brand_new"))
        assertEquals(TodayStatus.MISSING_CLOCK_OUT, TodayStatus.fromWire("missing_clock_out"))
        assertEquals(NotificationType.CLOCK_IN_CONFIRMATION_REQUIRED, NotificationType.fromWire("clock_in_confirmation_required"))
        assertTrue(NotificationType.CLOCK_OUT_CONFIRMATION_REQUIRED.isConfirmation)
        assertFalse(NotificationType.SYSTEM.isConfirmation)
        assertEquals(OutboxKind.FCM_TOKEN, OutboxKind.fromWire("fcm_token"))
        assertNull(OutboxKind.fromWire("bogus"))
        assertTrue(RequestType.FULL_DAY.needsClockIn && RequestType.FULL_DAY.needsClockOut)
        assertFalse(RequestType.MISSED_CLOCK_OUT.needsClockIn)
    }

    @Test
    fun statusTonesFollowDesignDoc() {
        assertEquals(StatusTone.SUCCESS, StatusTones.forStatus("confirmed"))
        assertEquals(StatusTone.SUCCESS, StatusTones.forToday(TodayStatus.CLOCKED_IN))
        assertEquals(StatusTone.WARNING, StatusTones.forStatus("under_review"))
        assertEquals(StatusTone.WARNING, StatusTones.forSession(SessionStatus.MISSING_CLOCK_OUT))
        assertEquals(StatusTone.ERROR, StatusTones.forStatus("revoked"))
        assertEquals(StatusTone.NEUTRAL, StatusTones.forStatus("dismissed"))
        assertEquals(StatusTone.NEUTRAL, StatusTones.forToday(TodayStatus.CLOCKED_OUT))
        assertEquals("Under review", StatusTones.label("under_review"))
    }

    @Test
    fun permissionsGate() {
        assertTrue(ServerPermissions.hasAdminSection(listOf("attendance:self", "admin:read")))
        assertFalse(ServerPermissions.hasAdminSection(listOf("attendance:self")))
        val snap = PermissionSnapshot(true, true, false, false, true, true)
        assertFalse(snap.detectionPossible)
        assertEquals(listOf(AppPermissionStep.BACKGROUND_LOCATION, AppPermissionStep.BATTERY_OPTIMISATION), snap.outstanding())
        assertTrue(snap.requiredOutstanding)
    }

    @Test
    fun syncStatusChip() {
        val now = 1_000_000L
        assertEquals(SyncStatus.Kind.SYNCED, SyncStatus(true, false, 0, 0, null).kind)
        assertEquals("Synced", SyncStatus(true, false, 0, 0, null).label(now))
        assertEquals("2 items queued", SyncStatus(true, false, 2, 0, null).label(now))
        assertEquals("Offline · 1 queued", SyncStatus(false, false, 1, 0, null).label(now))
        assertEquals("Syncing…", SyncStatus(true, true, 1, 0, null).label(now))
        assertEquals(SyncStatus.Kind.FAILED, SyncStatus(true, false, 0, 1, null).kind)
        assertEquals(StatusTone.ERROR, SyncStatus(true, false, 0, 1, null).tone)
    }

    @Test
    fun distanceFormatting() {
        assertEquals("42 m", Geo.formatDistance(42.6))
        assertEquals("1.3 km", Geo.formatDistance(1_280.0))
        assertEquals("—", Geo.formatDistance(null))
    }
}

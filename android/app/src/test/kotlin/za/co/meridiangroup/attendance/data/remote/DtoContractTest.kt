package za.co.meridiangroup.attendance.data.remote

import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import za.co.meridiangroup.attendance.data.remote.dto.AuthResponseDto
import za.co.meridiangroup.attendance.data.remote.dto.ConfirmEventRequestDto
import za.co.meridiangroup.attendance.data.remote.dto.EventDto
import za.co.meridiangroup.attendance.data.remote.dto.LocationDto
import za.co.meridiangroup.attendance.data.remote.dto.LoginRequestDto
import za.co.meridiangroup.attendance.data.remote.dto.DeviceInfoDto
import za.co.meridiangroup.attendance.data.remote.dto.ManualRequestCreateDto
import za.co.meridiangroup.attendance.data.remote.dto.NotificationDto
import za.co.meridiangroup.attendance.data.remote.dto.PageDto
import za.co.meridiangroup.attendance.data.remote.dto.PolicyDto
import za.co.meridiangroup.attendance.data.remote.dto.SessionDto
import za.co.meridiangroup.attendance.data.remote.dto.SubmitEventRequestDto
import za.co.meridiangroup.attendance.data.remote.dto.SyncItemDto
import za.co.meridiangroup.attendance.data.remote.dto.SyncRequestDto
import za.co.meridiangroup.attendance.data.remote.dto.SyncResponseDto
import za.co.meridiangroup.attendance.data.remote.dto.TodaySummaryDto
import za.co.meridiangroup.attendance.data.remote.dto.WifiDto
import za.co.meridiangroup.attendance.data.remote.dto.WorkplacesResponseDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Verifies the DTOs against the JSON examples of docs/API.md (snake_case field names, nullability,
 * tolerance of unknown fields). Runs in Gradle unit tests and in tools/core-check.
 */
class DtoContractTest {
    private val json = ApiJson.json

    @Test
    fun loginRequestUsesContractFieldNames() {
        val body = LoginRequestDto("MG-0042", "secret", DeviceInfoDto("dev-1", "SM-A546B", "samsung", "14", "1.0.0 (1)", null))
        val obj = json.parseToJsonElement(json.encodeToString(LoginRequestDto.serializer(), body)).jsonObject
        assertEquals(setOf("identifier", "password", "device"), obj.keys)
        assertEquals(setOf("device_id", "model", "manufacturer", "os_version", "app_version"), obj["device"]!!.jsonObject.keys)
    }

    @Test
    fun parsesLoginResponseFromApiDoc() {
        val raw = """
            {"access_token":"jwt","refresh_token":"opaque","token_type":"bearer","expires_in":900,"must_change_password":false,
             "user":{"id":"u1","employee_number":"MG-0042","first_name":"Thandi","last_name":"Naidoo","email":"t.naidoo@example.co.za",
                     "phone":"+27 82","role":"employee","status":"active","department":{"id":"d1","name":"Finance","code":"FIN"},
                     "must_change_password":false,"created_at":"2026-11-16T06:02:11Z","last_login_at":null,
                     "permissions":["attendance:self"],"some_future_field":123},
             "device":null}
        """.trimIndent()
        val r = json.decodeFromString(AuthResponseDto.serializer(), raw)
        assertEquals("Thandi Naidoo", r.user.fullName)
        assertEquals("FIN", r.user.department?.code)
        assertEquals(listOf("attendance:self"), r.user.permissions)
        assertNull(r.device)
        assertEquals(900, r.expiresIn)
    }

    @Test
    fun submitEventBodyMatchesContract() {
        val body = SubmitEventRequestDto(
            clientEventId = "c1", workplaceId = "w1", eventType = "arrival", source = "geofence",
            detectedAt = "2026-11-16T06:02:11Z", deviceTimezone = "Africa/Johannesburg", geofenceTransition = "dwell",
            location = LocationDto(-29.7, 31.08, 18.5), wifi = WifiDto("Meridian-Staff", "aa:bb:cc:dd:ee:ff"),
            offlineQueued = false, batteryLevel = 73,
        )
        val obj = json.parseToJsonElement(json.encodeToString(SubmitEventRequestDto.serializer(), body)).jsonObject
        assertEquals(
            setOf("client_event_id", "workplace_id", "event_type", "source", "detected_at", "device_timezone",
                "geofence_transition", "location", "wifi", "offline_queued", "battery_level"),
            obj.keys, // `note` is null and explicitNulls=false omits it
        )
        assertEquals(setOf("latitude", "longitude", "accuracy_m"), obj["location"]!!.jsonObject.keys)
        assertEquals("dwell", obj["geofence_transition"]!!.jsonPrimitive.content)
    }

    @Test
    fun parsesEventAndTodaySummary() {
        val raw = """
            {"work_date":"2026-11-16","status":"clocked_in",
             "session":{"id":"s1","work_date":"2026-11-16","status":"open","workplace_id":"w1","workplace_name":"Umhlanga",
                        "clock_in_at":"2026-11-16T06:02:00Z","clock_out_at":null,"clock_in_local":"2026-11-16T08:02:00+02:00",
                        "clock_out_local":null,"clock_in_event_id":"e1","clock_out_event_id":null,"worked_minutes":312,
                        "break_minutes":0,"overtime_minutes":0,"is_late":false,"late_minutes":0,"is_manual":false,
                        "corrected":false,"correction_note":null,"notes":null},
             "pending_events":[{"id":"e2","client_event_id":"c2","event_type":"departure","source":"geofence","status":"pending",
                        "workplace_id":"w1","workplace_name":"Umhlanga","detected_at":"2026-11-16T15:00:00Z","received_at":"2026-11-16T15:00:02Z",
                        "official_at":null,"confirmed_at":null,"expires_at":"2026-11-17T03:00:00Z",
                        "location":{"latitude":-29.7,"longitude":31.08,"accuracy_m":12.0},"distance_from_workplace_m":420.5,
                        "wifi_verified":false,"verification_score":0.82,
                        "verification":{"inside_geofence":false,"accuracy_ok":true,"wifi_matched":false,"timestamp_plausible":true,"device_active":true,"notes":["outside"]},
                        "review_reason":null,"reviewed_by":null,"reviewed_at":null,"review_note":null,"session_id":null,
                        "offline_queued":false,"created_at":"2026-11-16T15:00:02Z"}],
             "clock_in_local":"08:02","clock_out_local":null,"worked_minutes_today":312,"week_minutes":1540,"month_minutes":6230,
             "late_today":false,"unread_notifications":2,"pending_requests":1,"server_time":"2026-11-16T15:01:00Z"}
        """.trimIndent()
        val t = json.decodeFromString(TodaySummaryDto.serializer(), raw)
        assertEquals("clocked_in", t.status)
        assertEquals(312, t.session?.workedMinutes)
        val e: EventDto = t.pendingEvents.single()
        assertEquals("c2", e.clientEventId)
        assertEquals(420.5, e.distanceFromWorkplaceM)
        assertEquals(false, e.verification?.insideGeofence)
        assertEquals("08:02", t.clockInLocal)
    }

    @Test
    fun syncRequestAndResponse() {
        val payload = json.parseToJsonElement("""{"client_confirmation_id":"k1","confirmed_at":"2026-11-16T06:05:00Z"}""").jsonObject
        val req = SyncRequestDto(listOf(SyncItemDto(kind = "confirmation", eventId = "c1", payload = payload)))
        val obj = json.parseToJsonElement(json.encodeToString(SyncRequestDto.serializer(), req)).jsonObject
        val item = obj["items"].toString()
        assertTrue(item.contains("\"kind\":\"confirmation\"") && item.contains("\"event_id\":\"c1\""), item)

        val resp = json.decodeFromString(
            SyncResponseDto.serializer(),
            """{"results":[{"index":0,"kind":"event","client_id":"c1","ok":true,"status":201,"event":{"id":"e1","event_type":"arrival"}},
                          {"index":1,"ok":false,"status":400,"error":{"code":"invalid_state","message":"expired","details":{}}}],
               "today":{"work_date":"2026-11-16","status":"pending_arrival"}}""",
        )
        assertTrue(resp.results[0].ok)
        assertEquals("invalid_state", resp.results[1].error?.code)
        assertEquals("pending_arrival", resp.today?.status)
    }

    @Test
    fun confirmAndManualRequestBodies() {
        val c = json.parseToJsonElement(json.encodeToString(ConfirmEventRequestDto.serializer(), ConfirmEventRequestDto("k1", "2026-11-16T06:05:00Z"))).jsonObject
        assertEquals(setOf("client_confirmation_id", "confirmed_at"), c.keys)
        val m = json.parseToJsonElement(
            json.encodeToString(
                ManualRequestCreateDto.serializer(),
                ManualRequestCreateDto("r1", "missed_clock_out", "2026-11-13", null, "2026-11-13T15:05:00Z", "Phone battery died"),
            ),
        ).jsonObject
        assertEquals(setOf("client_request_id", "type", "work_date", "requested_clock_out", "reason"), m.keys)
    }

    @Test
    fun workplacesAndCoordinatesConfirmed() {
        val r = json.decodeFromString(
            WorkplacesResponseDto.serializer(),
            """{"items":[{"id":"w1","name":"Meridian Group — Umhlanga","address":"…","latitude":-29.7,"longitude":31.08,
                 "radius_m":150,"accuracy_threshold_m":100,"entry_detection_enabled":true,"exit_detection_enabled":true,
                 "dwell_seconds":120,"exit_debounce_seconds":300,"wifi_verification_enabled":false,"wifi_ssid":null,"wifi_bssids":[],
                 "wifi_required":false,"timezone":"Africa/Johannesburg","active":true,"coordinates_confirmed":false},
                {"id":"w2","latitude":-29.72,"longitude":31.09}],"config_version":7}""",
        )
        assertEquals(7, r.configVersion)
        assertFalse(r.items[0].isConfigured, "explicit coordinates_confirmed=false must win")
        assertTrue(r.items[1].isConfigured, "missing flag falls back to coordinate validity")
        assertEquals(150.0, r.items[0].radiusM)
    }

    @Test
    fun policyDefaultsAndReminderFallback() {
        val p = json.decodeFromString(PolicyDto.serializer(), """{"standard_day_minutes":450,"working_days":[1,2,3,4,5]}""")
        assertEquals(450, p.standardDayMinutes)
        assertEquals(15, p.confirmationReminderMinutes)
    }

    @Test
    fun paginationAndNotifications() {
        val page = json.decodeFromString(
            PageDto.serializer(NotificationDto.serializer()),
            """{"items":[{"id":"n1","type":"clock_in_confirmation_required","title":"t","body":"b","data":{"event_id":"e1"},"read_at":null,"created_at":"2026-11-16T06:02:11Z"}],
               "total":61,"page":2,"page_size":30}""",
        )
        assertTrue(page.hasMore)
        assertEquals("e1", page.items.single().data["event_id"])
        val last = json.decodeFromString(PageDto.serializer(SessionDto.serializer()), """{"items":[],"total":60,"page":2,"page_size":30}""")
        assertFalse(last.hasMore)
    }

    @Test
    fun errorEnvelopeParsing() {
        val f = ApiErrorParser.parse(422, """{"error":{"code":"validation_error","message":"Invalid","details":{"fields":{"reason":"too short"}}}}""")
        assertEquals("validation_error", f.code)
        assertEquals(mapOf("reason" to "too short"), f.fieldErrors)
        val rl = ApiErrorParser.parse(429, """{"error":{"code":"rate_limited","message":"slow down","details":{}}}""", "30")
        assertEquals(30, rl.retryAfterSeconds)
        assertEquals("server_error", ApiErrorParser.parse(502, "<html>bad gateway</html>").code)
        assertEquals("token_invalid", ApiErrorParser.parse(401, "").code)
        assertTrue(ApiErrorParser.parse(401, """{"error":{"code":"token_reused","message":""}}""").requiresLogout)
    }
}

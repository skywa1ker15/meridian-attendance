package za.co.meridiangroup.attendance.data.remote

import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import za.co.meridiangroup.attendance.data.remote.dto.AdminDepartmentDto
import za.co.meridiangroup.attendance.data.remote.dto.AttendanceExceptionDto
import za.co.meridiangroup.attendance.data.remote.dto.AuditLogDto
import za.co.meridiangroup.attendance.data.remote.dto.DeviceDto
import za.co.meridiangroup.attendance.data.remote.dto.EmployeeCreateRequestDto
import za.co.meridiangroup.attendance.data.remote.dto.EmployeeDetailDto
import za.co.meridiangroup.attendance.data.remote.dto.EmployeeUpdateRequestDto
import za.co.meridiangroup.attendance.data.remote.dto.FirstAdminRequestDto
import za.co.meridiangroup.attendance.data.remote.dto.ItemsDto
import za.co.meridiangroup.attendance.data.remote.dto.OverviewDto
import za.co.meridiangroup.attendance.data.remote.dto.PageDto
import za.co.meridiangroup.attendance.data.remote.dto.PolicyDto
import za.co.meridiangroup.attendance.data.remote.dto.PolicyPayload
import za.co.meridiangroup.attendance.data.remote.dto.ReportDto
import za.co.meridiangroup.attendance.data.remote.dto.RolesResponseDto
import za.co.meridiangroup.attendance.data.remote.dto.SessionCorrectionRequestDto
import za.co.meridiangroup.attendance.data.remote.dto.SetupStatusDto
import za.co.meridiangroup.attendance.data.remote.dto.UserDto
import za.co.meridiangroup.attendance.data.remote.dto.WorkplaceDto
import za.co.meridiangroup.attendance.data.remote.dto.WorkplacePayload
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** docs/API.md §0 and §7 shapes as used by the app's "Manage" section. */
class AdminDtoContractTest {
    private val json = ApiJson.json

    @Test
    fun setupBodies() {
        val st = json.decodeFromString(SetupStatusDto.serializer(), """{"needs_first_admin":true,"setup_code_required":false}""")
        assertTrue(st.needsFirstAdmin)
        val body = FirstAdminRequestDto(null, "MG-0001", "Nomsa", "Dlamini", "n@x.co.za", "pw")
        val obj = json.parseToJsonElement(json.encodeToString(FirstAdminRequestDto.serializer(), body)).jsonObject
        assertEquals(setOf("employee_number", "first_name", "last_name", "email", "password"), obj.keys)
    }

    @Test
    fun overviewAndEmployees() {
        val o = json.decodeFromString(OverviewDto.serializer(), """{"work_date":"2026-11-16","stats":{"total_employees":48,"clocked_in":31,"clocked_out":9,"absent":6,
            "late_arrivals":4,"pending_confirmations":2,"under_review":1,"exceptions":5,"pending_requests":3,"pending_devices":0},
            "attendance_trend":[{"work_date":"2026-11-15","present":40,"late":3,"absent":5}],
            "department_breakdown":[{"department":"Finance","present":8,"total":10}],
            "recent_activity":[{"at":"2026-11-16T06:02:00Z","kind":"event_confirmed","text":"x","employee_id":"u"}]}""")
        assertEquals(48, o.stats.totalEmployees); assertEquals(4, o.stats.lateArrivals); assertEquals(1, o.attendanceTrend.size)

        val page = json.decodeFromString(PageDto.serializer(UserDto.serializer()), """{"items":[{"id":"u1","employee_number":"MG-1","first_name":"A","last_name":"B",
            "email":"a@b.co","role":"employee","status":"active","department":null,"permissions":["attendance:self"],"last_attendance_status":"clocked_in",
            "active_device":{"id":"d1","device_id":"i","status":"active"}}],"total":1,"page":1,"page_size":50}""")
        assertEquals("d1", page.items[0].activeDevice?.id)

        val detail = json.decodeFromString(EmployeeDetailDto.serializer(), """{"user":{"id":"u1"},"devices":[{"id":"d1","status":"pending_approval"}],
            "today":{"work_date":"2026-11-16","status":"not_started"},"stats_30d":{"present_days":20,"late_days":1,"absent_days":2,"worked_minutes":9000,"manual_requests":0}}""")
        assertEquals(20, detail.stats30d.presentDays)

        val create = json.parseToJsonElement(json.encodeToString(EmployeeCreateRequestDto.serializer(),
            EmployeeCreateRequestDto("MG-2", "A", "B", "a@b.co"))).jsonObject
        assertEquals(setOf("employee_number", "first_name", "last_name", "email", "role"), create.keys)

        val patch = json.parseToJsonElement(json.encodeToString(EmployeeUpdateRequestDto.serializer(), EmployeeUpdateRequestDto(firstName = "C"))).jsonObject
        assertEquals(setOf("first_name"), patch.keys)
    }

    @Test
    fun recordsReportsAudit() {
        val corr = json.parseToJsonElement(json.encodeToString(SessionCorrectionRequestDto.serializer(),
            SessionCorrectionRequestDto(clockOutAt = "2026-11-16T15:00:00Z", note = "forgot"))).jsonObject
        assertEquals(setOf("clock_out_at", "note"), corr.keys)

        val exc = json.decodeFromString(PageDto.serializer(AttendanceExceptionDto.serializer()), """{"items":[{"kind":"late_arrival","work_date":"2026-11-16",
            "employee":{"id":"u","first_name":"T","last_name":"N","full_name":"T N","role":"employee","status":"active"},"session_id":"s","event_id":null,"detail":"late","at":"2026-11-16T06:30:00Z"}],"total":1,"page":1,"page_size":50}""")
        assertEquals("T N", exc.items[0].employee?.fullName)

        val rep = json.decodeFromString(ReportDto.serializer(), """{"report":"daily","from":"2026-11-16","to":"2026-11-16","generated_at":"x",
            "columns":[{"key":"name","label":"Name"},{"key":"worked","label":"Worked"}],"rows":[{"name":"T","worked":480,"x":null}],"summary":{"rows":1}}""")
        assertEquals("480", rep.cell(rep.rows[0], "worked")); assertEquals("—", rep.cell(rep.rows[0], "x"))

        val audit = json.decodeFromString(AuditLogDto.serializer(), """{"id":"a","created_at":"x","actor":null,"actor_type":"system","action":"employee.create",
            "entity_type":"user","entity_id":"u","before":null,"after":{"role":"admin"},"ip":null,"user_agent":null,"request_id":"r"}""")
        assertNull(audit.actor); assertEquals("employee.create", audit.action)

        val roles = json.decodeFromString(RolesResponseDto.serializer(), """{"roles":[{"role":"manager","permissions":["admin:read"],"description":"d","user_count":3}]}""")
        assertEquals(3, roles.roles[0].userCount)

        val deps = json.decodeFromString(ItemsDto.serializer(AdminDepartmentDto.serializer()), """{"items":[{"id":"d","name":"Finance","code":"FIN","active":true,"employee_count":10,"created_at":"x"}]}""")
        assertEquals(10, deps.items[0].employeeCount)

        val dev = json.decodeFromString(DeviceDto.serializer(), """{"id":"d1","device_id":"i","status":"pending_approval","employee":{"id":"u","first_name":"A","last_name":"B"}}""")
        assertEquals("A B", dev.employee?.fullName)
    }

    @Test
    fun workplaceAndPolicyPayloads() {
        val w = WorkplaceDto(id = "w", name = "HQ", latitude = -29.72, longitude = 31.08, coordinatesConfirmed = false, wifiSsid = "Meridian")
        val full = WorkplacePayload.full(w)
        assertEquals(17, full.size)
        assertTrue("id" !in full)
        assertEquals(JsonPrimitive(150), full["radius_m"])
        val edited = w.copy(coordinatesConfirmed = true, wifiSsid = " ", wifiBssids = listOf("AA:BB:CC:DD:EE:FF"))
        val diff = WorkplacePayload.diff(w, edited)
        assertEquals(setOf("coordinates_confirmed", "wifi_ssid", "wifi_bssids"), diff.keys)
        assertEquals(JsonNull, diff["wifi_ssid"])
        assertEquals("aa:bb:cc:dd:ee:ff", json.decodeFromJsonElement(kotlinx.serialization.builtins.ListSerializer(String.serializer()), diff["wifi_bssids"]!!)[0])

        val p = json.decodeFromString(PolicyDto.serializer(), """{"grace_minutes":15,"notification_quiet_hours":{"start":"20:00","end":"07:00"},"note":"x","updated_at":"y"}""")
        val pFull = PolicyPayload.full(p)
        assertEquals(PolicyPayload.WRITABLE_KEYS.toSet(), pFull.keys)
        val pDiff = PolicyPayload.diff(p, p.copy(graceMinutes = 10, notificationQuietHours = JsonNull))
        assertEquals(setOf("grace_minutes", "notification_quiet_hours"), pDiff.keys)
        assertEquals(0, PolicyPayload.diff(p, p).size)
    }
}

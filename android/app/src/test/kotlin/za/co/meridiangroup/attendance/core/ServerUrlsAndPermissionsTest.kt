package za.co.meridiangroup.attendance.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ServerUrlsAndPermissionsTest {
    private fun root(input: String, cleartext: Boolean = false): String =
        (ServerUrls.normalise(input, cleartext) as ServerUrlResult.Valid).root

    @Test
    fun normalisesAddresses() {
        assertEquals("https://attendance.example.co.za/", root("attendance.example.co.za"))
        assertEquals("https://attendance.example.co.za/", root(" https://Attendance.Example.co.za/api/v1/ "))
        assertEquals("https://example.co.za/meridian/", root("https://example.co.za/meridian/api/v1"))
        assertEquals("http://192.168.1.50:8000/", root("http://192.168.1.50:8000", cleartext = true))
    }

    @Test
    fun rejectsInsecureOrBrokenAddresses() {
        assertIs<ServerUrlResult.Invalid>(ServerUrls.normalise("http://192.168.1.50:8000", allowCleartext = false))
        assertIs<ServerUrlResult.Invalid>(ServerUrls.normalise("", allowCleartext = true))
        assertIs<ServerUrlResult.Invalid>(ServerUrls.normalise("ftp://x.co.za", allowCleartext = true))
        assertIs<ServerUrlResult.Invalid>(ServerUrls.normalise("https://x.co.za/?a=b", allowCleartext = true))
    }

    @Test
    fun derivedUrls() {
        assertEquals("http://10.0.2.2:8000/api/v1/", ServerUrls.apiBase("http://10.0.2.2:8000/"))
        assertEquals("https://h.co.za/p/healthz", ServerUrls.healthUrl("https://h.co.za/p/"))
        assertEquals("https://h.co.za/", ServerUrls.rootOf("https://h.co.za/api/v1/"))
        assertEquals("h.co.za:8443", ServerUrls.display("https://h.co.za:8443/"))
    }

    @Test
    fun permissionHelpers() {
        val employee = listOf("attendance:self")
        val manager = listOf("attendance:self", "admin:read", "attendance:review", "reports:export")
        val admin = manager + listOf("employees:write", "devices:write", "config:write", "roles:write", "notifications:broadcast")
        assertFalse(ServerPermissions.hasAdminSection(employee))
        assertTrue(ServerPermissions.hasAdminSection(manager))
        assertEquals(listOf("employee"), ServerPermissions.assignableRoles(manager))
        assertEquals(listOf("employee", "manager", "admin"), ServerPermissions.assignableRoles(admin))
        assertEquals(4, ServerPermissions.assignableRoles(admin + "roles:superadmin").size)
    }

    @Test
    fun localTimeConversions() {
        assertEquals("2026-11-16T06:02:00Z", JohannesburgTime.localToIsoUtc("2026-11-16", "08:02"))
        assertEquals("2026-11-16" to "08:02", JohannesburgTime.localParts("2026-11-16T06:02:11Z"))
        assertEquals(null, JohannesburgTime.localToIsoUtc("2026-11-16", ""))
    }
}

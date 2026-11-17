package za.co.meridiangroup.attendance.ui.navigation

import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DeepLinksTest {
    @Test
    fun confirmationTypesOpenTheConfirmationScreen() {
        assertEquals("meridianattendance://confirm/e1", DeepLinks.forNotification("clock_in_confirmation_required", mapOf("event_id" to "e1")).toString())
        assertEquals("meridianattendance://confirm/e2", DeepLinks.forNotification("departure_detected", mapOf("event_id" to "e2")).toString())
        assertEquals(DeepLinks.DASHBOARD, DeepLinks.forNotification("arrival_detected", emptyMap()).toString())
    }

    @Test
    fun otherTypes() {
        assertEquals(DeepLinks.REQUESTS, DeepLinks.forNotification("manual_request_reviewed", emptyMap()).toString())
        assertEquals(DeepLinks.REQUESTS, DeepLinks.forNotification("correction_rejected", emptyMap()).toString())
        assertEquals("meridianattendance://session/s1", DeepLinks.forNotification("attendance_recorded", mapOf("session_id" to "s1")).toString())
        assertEquals(DeepLinks.NOTIFICATIONS, DeepLinks.forNotification("system", emptyMap()).toString())
    }

    @Test
    fun refsAreEncoded() {
        assertEquals("meridianattendance://confirm/a%2Fb", DeepLinks.confirm("a/b").toString())
    }
}

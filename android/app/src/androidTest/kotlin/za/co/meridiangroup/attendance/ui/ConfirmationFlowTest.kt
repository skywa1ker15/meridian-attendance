package za.co.meridiangroup.attendance.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import za.co.meridiangroup.attendance.core.DismissReason
import za.co.meridiangroup.attendance.data.local.db.AppDatabase
import za.co.meridiangroup.attendance.data.local.db.EventEntity
import za.co.meridiangroup.attendance.data.remote.ApiCaller
import za.co.meridiangroup.attendance.data.repository.ActionOutcome
import za.co.meridiangroup.attendance.data.repository.AttendanceRepository
import za.co.meridiangroup.attendance.data.repository.OutboxWriter
import za.co.meridiangroup.attendance.fakes.FakeAttendanceApi
import za.co.meridiangroup.attendance.ui.confirm.ConfirmationActions
import za.co.meridiangroup.attendance.ui.confirm.ConfirmationScreen
import za.co.meridiangroup.attendance.ui.confirm.ConfirmationViewModel
import za.co.meridiangroup.attendance.ui.theme.MeridianTheme
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Confirmation screen + real repository + in-memory Room + fake API (online, offline, dismiss). */
@RunWith(AndroidJUnit4::class)
class ConfirmationFlowTest {
    @get:Rule val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var db: AppDatabase
    private lateinit var api: FakeAttendanceApi
    private lateinit var repo: AttendanceRepository
    private var afterActions = 0

    private val pending = EventEntity(
        clientEventId = "c1", serverId = "e1", eventType = "arrival", source = "geofence", status = "pending", localStatus = "submitted",
        workplaceId = "w1", workplaceName = "Umhlanga", detectedAt = "2026-11-16T06:02:11Z", expiresAt = null,
        latitude = -29.72, longitude = 31.08, accuracyM = 15.0, distanceM = 42.0, wifiSsid = null, wifiMatched = null,
        likelyReview = false, serverJson = null, createdAtMillis = 1, updatedAtMillis = 1,
    )

    @Before
    fun setUp() = runBlocking {
        db = AppDatabase.inMemory(context)
        api = FakeAttendanceApi()
        repo = AttendanceRepository(api, ApiCaller { }, db, OutboxWriter(db.outboxDao()) { System.currentTimeMillis() }) { System.currentTimeMillis() }
        db.eventDao().insert(pending)
        val actions = object : ConfirmationActions {
            override suspend fun confirm(ref: String, note: String?): ActionOutcome = repo.confirm(ref, null, note)
            override suspend fun dismiss(ref: String, reason: DismissReason, note: String?): ActionOutcome = repo.dismiss(ref, reason, note)
            override suspend fun afterAction(event: EventEntity) { afterActions++ }
        }
        compose.setContent { MeridianTheme(darkTheme = false) { ConfirmationScreen(ConfirmationViewModel("e1", repo, actions)) {} } }
    }

    @After fun tearDown() = db.close()

    @Test
    fun confirmOnlineShowsClockedIn() {
        compose.onNodeWithTag("confirm_note").performTextInput("Traffic on the M4")
        compose.onNodeWithTag("confirm_button").performClick()
        compose.waitUntil(5_000) { api.calls.contains("confirmEvent") }
        compose.onNodeWithTag("confirm_result_confirmed").assertIsDisplayed()
        runBlocking { assertEquals(0, db.outboxDao().queuedCount(), "nothing left in the outbox") }
        assertEquals(1, afterActions)
    }

    @Test
    fun confirmUnderReviewShowsReviewResult() {
        api.confirmStatus = "under_review"
        compose.onNodeWithTag("confirm_button").performClick()
        compose.waitUntil(5_000) { api.calls.contains("confirmEvent") }
        compose.onNodeWithTag("confirm_result_under_review").assertIsDisplayed()
    }

    @Test
    fun confirmOfflineIsQueuedNotRecorded() {
        api.offline = true
        compose.onNodeWithTag("confirm_button").performClick()
        compose.waitUntil(5_000) { api.calls.contains("confirmEvent") }
        compose.onNodeWithTag("confirm_result_queued_confirm").assertIsDisplayed()
        runBlocking {
            val rows = db.outboxDao().due(Long.MAX_VALUE)
            assertEquals(listOf("confirmation"), rows.map { it.kind })
            assertEquals("e1", rows.single().eventRef)
            assertEquals("pending", db.eventDao().byRef("e1")?.status, "never shown as confirmed before the server says so")
        }
    }

    @Test
    fun notMeDismissesWithReason() {
        compose.onNodeWithTag("dismiss_button").performClick()
        compose.onNodeWithTag("reason_wrong_detection").performClick()
        compose.onNodeWithTag("dismiss_submit").performClick()
        compose.waitUntil(5_000) { api.calls.contains("dismissEvent") }
        compose.onNodeWithTag("confirm_result_dismissed").assertIsDisplayed()
        assertTrue(api.calls.none { it == "confirmEvent" })
    }
}

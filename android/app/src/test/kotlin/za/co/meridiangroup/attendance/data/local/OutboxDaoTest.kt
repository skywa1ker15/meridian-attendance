package za.co.meridiangroup.attendance.data.local

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import za.co.meridiangroup.attendance.data.local.db.AppDatabase
import za.co.meridiangroup.attendance.data.local.db.EventEntity
import za.co.meridiangroup.attendance.data.local.db.OutboxEntity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Room behaviour the sync design relies on: idempotent inserts, ordering, retry scheduling, ref rewriting. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OutboxDaoTest {
    private lateinit var db: AppDatabase

    @Before fun setUp() { db = AppDatabase.inMemory(ApplicationProvider.getApplicationContext()) }
    @After fun tearDown() { db.close() }

    private fun row(kind: String, clientId: String, created: Long, ref: String? = null) =
        OutboxEntity(kind = kind, clientId = clientId, eventRef = ref, payloadJson = "{}", createdAtMillis = created)

    @Test
    fun duplicateClientIdIsIgnored() = runTest {
        val dao = db.outboxDao()
        assertTrue(dao.insert(row("event", "c1", 1)) > 0)
        assertEquals(-1L, dao.insert(row("event", "c1", 2)))
        assertTrue(dao.insert(row("confirmation", "c1", 3)) > 0, "same id, different kind is a different row")
        assertEquals(2, dao.queuedCount())
    }

    @Test
    fun dueRowsAreOrderedAndBackoffRespected() = runTest {
        val dao = db.outboxDao()
        dao.insert(row("confirmation", "k1", 20, ref = "c1"))
        dao.insert(row("event", "c1", 10))
        val id3 = dao.insert(row("event", "c2", 30))
        dao.markRetry(id3, "network", nextAt = 1_000)
        assertEquals(listOf("c1", "k1"), dao.due(now = 500).map { it.clientId })
        assertEquals(listOf("c1", "k1", "c2"), dao.due(now = 1_000).map { it.clientId })
    }

    @Test
    fun deadRowsAreExcludedAndCounted() = runTest {
        val dao = db.outboxDao()
        val id = dao.insert(row("event", "c1", 1))
        dao.markDead(id, "invalid_state")
        assertTrue(dao.due(Long.MAX_VALUE).isEmpty())
        assertEquals(1, dao.observeDeadCount().first())
        dao.clearDead()
        assertEquals(0, dao.observeDeadCount().first())
    }

    @Test
    fun eventRefIsRewrittenToServerId() = runTest {
        val dao = db.outboxDao()
        dao.insert(row("confirmation", "k1", 1, ref = "client-1"))
        dao.rewriteEventRef("client-1", "server-9")
        assertEquals("server-9", dao.due(Long.MAX_VALUE).single().eventRef)
    }

    @Test
    fun awaitingConfirmationExcludesInFlightRows() = runTest {
        val events = db.eventDao()
        fun ev(id: String, local: String) = EventEntity(
            clientEventId = id, serverId = null, eventType = "arrival", source = "geofence", status = "pending", localStatus = local,
            workplaceId = "w", workplaceName = "W", detectedAt = "2026-11-16T06:00:00Z", expiresAt = null, latitude = null, longitude = null,
            accuracyM = null, distanceM = null, wifiSsid = null, wifiMatched = null, likelyReview = false, serverJson = null,
            createdAtMillis = 1, updatedAtMillis = 1,
        )
        events.insert(ev("a", "queued"))
        events.insert(ev("b", "confirming"))
        assertEquals(listOf("a"), events.awaitingConfirmation().map { it.clientEventId })
        assertNotNull(events.byRef("b"))
    }
}

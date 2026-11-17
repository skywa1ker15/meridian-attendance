package za.co.meridiangroup.attendance.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class OutboxRulesTest {
    private fun item(id: Long, kind: OutboxKind, clientId: String, eventRef: String? = null, createdAt: Long = id * 1000, attempts: Int = 0) =
        OutboxItem(id, kind, clientId, eventRef, "{}", attempts, createdAt)

    @Test
    fun ordersByCreationThenIdAndSplitsKinds() {
        val plan = OutboxRules.plan(
            listOf(
                item(3, OutboxKind.CONFIRMATION, "c1", eventRef = "e1", createdAt = 3000),
                item(1, OutboxKind.EVENT, "e1", createdAt = 1000),
                item(2, OutboxKind.FCM_TOKEN, "tok", createdAt = 2000),
                item(4, OutboxKind.MANUAL_REQUEST, "r1", createdAt = 500),
            ),
        )
        assertEquals(listOf("e1", "c1"), plan.syncBatch.map { it.clientId })
        assertEquals(listOf("r1", "tok"), plan.individual.map { it.clientId })
        assertTrue(plan.duplicates.isEmpty())
    }

    @Test
    fun duplicateIdempotencyKeysAreDropped() {
        val plan = OutboxRules.plan(listOf(item(1, OutboxKind.EVENT, "e1"), item(2, OutboxKind.EVENT, "e1"), item(3, OutboxKind.CONFIRMATION, "e1")))
        assertEquals(listOf(1L, 3L), plan.syncBatch.map { it.id })
        assertEquals(listOf(2L), plan.duplicates.map { it.id })
    }

    @Test
    fun confirmationIsNeverSentBeforeItsEventEvenWithClockSkew() {
        // Clock went backwards: confirmation row has an older created_at than its event.
        val plan = OutboxRules.plan(
            listOf(
                item(10, OutboxKind.EVENT, "e9", createdAt = 9_000),
                item(11, OutboxKind.CONFIRMATION, "c9", eventRef = "e9", createdAt = 1_000),
            ),
        )
        assertEquals(listOf("e9", "c9"), plan.syncBatch.map { it.clientId })
    }

    @Test
    fun successIsDone() {
        assertEquals(OutboxResolution.Done, OutboxRules.resolve(OutboxKind.EVENT, 201, null, 0))
        assertEquals(OutboxResolution.Done, OutboxRules.resolve(OutboxKind.EVENT, 200, null, 3))
        assertEquals(OutboxResolution.Done, OutboxRules.resolve(OutboxKind.FCM_TOKEN, 204, null, 0))
    }

    @Test
    fun networkAndServerErrorsRetryWithBackoff() {
        val r1 = assertIs<OutboxResolution.Retry>(OutboxRules.resolve(OutboxKind.EVENT, null, null, 0))
        assertEquals(60_000L, r1.backoffMillis)
        val r2 = assertIs<OutboxResolution.Retry>(OutboxRules.resolve(OutboxKind.EVENT, 503, null, 1))
        assertEquals(120_000L, r2.backoffMillis)
        val r3 = assertIs<OutboxResolution.Retry>(OutboxRules.resolve(OutboxKind.EVENT, 500, "server_error", 20))
        assertEquals(OutboxRules.MAX_BACKOFF_MILLIS, r3.backoffMillis)
        assertIs<OutboxResolution.Retry>(OutboxRules.resolve(OutboxKind.EVENT, 429, "rate_limited", 0))
        assertIs<OutboxResolution.Retry>(OutboxRules.resolve(OutboxKind.EVENT, 401, "token_expired", 0))
    }

    @Test
    fun permanentClientErrorsAreDead() {
        assertEquals(OutboxResolution.Dead("invalid_state"), OutboxRules.resolve(OutboxKind.CONFIRMATION, 400, "invalid_state", 0))
        assertEquals(OutboxResolution.Dead("not_found"), OutboxRules.resolve(OutboxKind.DISMISSAL, 404, "not_found", 0))
        assertEquals(OutboxResolution.Dead("validation_error"), OutboxRules.resolve(OutboxKind.EVENT, 422, "validation_error", 0))
        assertEquals(OutboxResolution.Dead("forbidden"), OutboxRules.resolve(OutboxKind.EVENT, 403, "forbidden", 0))
        assertEquals(OutboxResolution.Dead("invalid_state"), OutboxRules.resolve(OutboxKind.EVENT, 409, "invalid_state", 0))
    }

    @Test
    fun duplicateConflictCountsAsDoneForEvents() {
        assertEquals(OutboxResolution.Done, OutboxRules.resolve(OutboxKind.EVENT, 409, "duplicate", 0))
        assertEquals(OutboxResolution.Done, OutboxRules.resolve(OutboxKind.MANUAL_REQUEST, 409, "conflict", 0))
        assertIs<OutboxResolution.Dead>(OutboxRules.resolve(OutboxKind.CONFIRMATION, 409, "conflict", 0))
    }

    @Test
    fun devicePendingApprovalWaitsLonger() {
        val r = assertIs<OutboxResolution.Retry>(OutboxRules.resolve(OutboxKind.EVENT, 403, "device_pending_approval", 0))
        assertEquals(OutboxRules.PENDING_APPROVAL_BACKOFF_MILLIS, r.backoffMillis)
    }

    @Test
    fun exhaustedRetriesBecomeDead() {
        val r = OutboxRules.resolve(OutboxKind.EVENT, null, null, OutboxRules.MAX_ATTEMPTS - 1)
        assertEquals(OutboxResolution.Dead("max_attempts:network"), r)
    }
}

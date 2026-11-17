package za.co.meridiangroup.attendance.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ExitDebounceTest {
    private val wp = WorkplaceGeometry(-29.7261, 31.0856, radiusM = 150.0, accuracyThresholdM = 100.0)
    private val exitAt = 1_000_000L
    private val debounce = 300

    private fun offsetLat(meters: Double) = -29.7261 + meters / 111_320.0

    @Test
    fun beforeDebounceElapsesNothingHappens() {
        val d = ExitDebounce.decide(wp, exitAt, exitAt + 10_000, debounce, false, LocationSample(offsetLat(2000.0), 31.0856, 10.0, exitAt + 10_000))
        assertEquals(DepartureDecision.Undetermined(ExitDebounce.REASON_DEBOUNCE_NOT_ELAPSED), d)
    }

    @Test
    fun dwellAfterExitCancelsDeparture() {
        val now = exitAt + 301_000
        val d = ExitDebounce.decide(wp, exitAt, now, debounce, true, LocationSample(offsetLat(2000.0), 31.0856, 10.0, now))
        assertEquals(DepartureDecision.Undetermined(ExitDebounce.REASON_DWELL_AFTER_EXIT), d)
    }

    @Test
    fun noLocationNeverConcludesDeparture() {
        val d = ExitDebounce.decide(wp, exitAt, exitAt + 400_000, debounce, false, null)
        assertEquals(DepartureDecision.Undetermined(ExitDebounce.REASON_NO_LOCATION), d)
    }

    @Test
    fun staleFixIsRejected() {
        val now = exitAt + 400_000
        val d = ExitDebounce.decide(wp, exitAt, now, debounce, false, LocationSample(offsetLat(2000.0), 31.0856, 10.0, now - 10 * 60_000))
        assertEquals(DepartureDecision.Undetermined(ExitDebounce.REASON_STALE_LOCATION), d)
    }

    @Test
    fun poorAccuracyIsRejected() {
        val now = exitAt + 400_000
        val d = ExitDebounce.decide(wp, exitAt, now, debounce, false, LocationSample(offsetLat(2000.0), 31.0856, 900.0, now))
        assertEquals(DepartureDecision.Undetermined(ExitDebounce.REASON_ACCURACY_TOO_POOR), d)
    }

    @Test
    fun stillInsideWhenDriftBroughtUsBack() {
        val now = exitAt + 400_000
        val d = ExitDebounce.decide(wp, exitAt, now, debounce, false, LocationSample(offsetLat(120.0), 31.0856, 20.0, now))
        assertIs<DepartureDecision.StillInside>(d)
    }

    @Test
    fun insideThanksToAccuracyMarginIsStillInside() {
        // 200 m away but accuracy 80 m: 200 <= 150 + 80
        val now = exitAt + 400_000
        val d = ExitDebounce.decide(wp, exitAt, now, debounce, false, LocationSample(offsetLat(200.0), 31.0856, 80.0, now))
        assertIs<DepartureDecision.StillInside>(d)
    }

    @Test
    fun clearlyOutsideAfterDebounceIsDeparture() {
        val now = exitAt + 400_000
        val d = ExitDebounce.decide(wp, exitAt, now, debounce, false, LocationSample(offsetLat(800.0), 31.0856, 25.0, now))
        val departed = assertIs<DepartureDecision.Departed>(d)
        assertTrue(departed.distanceM > 700 && departed.distanceM < 900)
    }

    @Test
    fun recheckDelayHasFloor() {
        assertEquals(5_000L, ExitDebounce.recheckDelayMillis(0))
        assertEquals(300_000L, ExitDebounce.recheckDelayMillis(300))
    }
}

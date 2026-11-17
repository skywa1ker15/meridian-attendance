package za.co.meridiangroup.attendance.core

/**
 * Decision taken after a geofence EXIT has been debounced for `exit_debounce_seconds`
 * (docs/ARCHITECTURE.md §5 step 3). A departure is only *proposed* to the employee — it is never
 * recorded without their confirmation — but even the proposal must not fire on GPS drift.
 */
sealed class DepartureDecision {
    /** The phone is still outside radius + accuracy: propose a departure for confirmation. */
    data class Departed(val distanceM: Double, val accuracyM: Double) : DepartureDecision()

    /** The phone is (again) inside the workplace: drop the exit silently. */
    data class StillInside(val distanceM: Double) : DepartureDecision()

    /** Not enough evidence: do nothing now (a later EXIT will start a new debounce). */
    data class Undetermined(val reason: String) : DepartureDecision()
}

object ExitDebounce {
    const val REASON_DEBOUNCE_NOT_ELAPSED = "debounce_not_elapsed"
    const val REASON_DWELL_AFTER_EXIT = "dwell_after_exit"
    const val REASON_NO_LOCATION = "no_location"
    const val REASON_STALE_LOCATION = "stale_location"
    const val REASON_ACCURACY_TOO_POOR = "accuracy_too_poor"

    /** A re-check fix older than this is not trusted. */
    const val DEFAULT_MAX_FIX_AGE_MILLIS: Long = 2 * 60 * 1000L

    /** Above this accuracy radius the fix says nothing useful about being outside a ~150 m geofence. */
    const val DEFAULT_MAX_ACCURACY_M: Double = 500.0

    fun decide(
        workplace: WorkplaceGeometry,
        exitObservedAtMillis: Long,
        nowMillis: Long,
        debounceSeconds: Int,
        dwellSeenAfterExit: Boolean,
        recheck: LocationSample?,
        maxFixAgeMillis: Long = DEFAULT_MAX_FIX_AGE_MILLIS,
        maxAccuracyM: Double = DEFAULT_MAX_ACCURACY_M,
    ): DepartureDecision {
        if (dwellSeenAfterExit) return DepartureDecision.Undetermined(REASON_DWELL_AFTER_EXIT)
        val debounceMillis = debounceSeconds.coerceAtLeast(0) * 1000L
        if (nowMillis < exitObservedAtMillis + debounceMillis) {
            return DepartureDecision.Undetermined(REASON_DEBOUNCE_NOT_ELAPSED)
        }
        if (recheck == null) return DepartureDecision.Undetermined(REASON_NO_LOCATION)
        if (nowMillis - recheck.observedAtMillis > maxFixAgeMillis) {
            return DepartureDecision.Undetermined(REASON_STALE_LOCATION)
        }
        if (!recheck.accuracyM.isFinite() || recheck.accuracyM > maxAccuracyM) {
            return DepartureDecision.Undetermined(REASON_ACCURACY_TOO_POOR)
        }
        val distance = Geo.distanceMeters(recheck.latitude, recheck.longitude, workplace.latitude, workplace.longitude)
        return if (distance > workplace.radiusM + recheck.accuracyM) {
            DepartureDecision.Departed(distance, recheck.accuracyM)
        } else {
            DepartureDecision.StillInside(distance)
        }
    }

    /** Delay before the re-check worker runs, never negative, never below a 5 s safety floor. */
    fun recheckDelayMillis(exitDebounceSeconds: Int): Long = (exitDebounceSeconds.coerceAtLeast(5)) * 1000L
}

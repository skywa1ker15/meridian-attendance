package za.co.meridiangroup.attendance.detection

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit
import za.co.meridiangroup.attendance.MeridianApplication
import za.co.meridiangroup.attendance.core.DepartureDecision
import za.co.meridiangroup.attendance.core.EventType
import za.co.meridiangroup.attendance.core.ExitDebounce
import za.co.meridiangroup.attendance.core.GeofenceTransition
import za.co.meridiangroup.attendance.core.LocationSample
import za.co.meridiangroup.attendance.core.TodayStatus
import za.co.meridiangroup.attendance.core.Verification
import za.co.meridiangroup.attendance.data.toGeometry
import za.co.meridiangroup.attendance.notifications.NotificationChannels
import za.co.meridiangroup.attendance.sync.SyncScheduler

/**
 * Turns a geofence transition into a *proposed* attendance event:
 *  * DWELL → arrival candidate immediately (DWELL already proves the employee stayed `dwell_seconds`).
 *  * EXIT  → re-enqueues itself with initialDelay = exit_debounce_seconds (stage RECHECK); then re-reads the
 *            current location and proposes a departure only if still outside radius + accuracy.
 * In both cases: collect signals → local pending event (Room + outbox) → POST /attendance/events → confirmation
 * notification. Attendance is NEVER recorded here: the employee must confirm, and departures are never automatic.
 */
class GeofenceTransitionWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val graph = (applicationContext as MeridianApplication).graph
        if (!graph.tokenStore.isLoggedIn) return Result.success()
        val workplaceId = inputData.getString(KEY_WORKPLACE) ?: return Result.success()
        val transition = inputData.getString(KEY_TRANSITION) ?: return Result.success()
        val observedAt = inputData.getLong(KEY_OBSERVED_AT, System.currentTimeMillis())
        val workplace = graph.configRepository.workplace(workplaceId)
        // Unknown or no-longer-eligible workplace (config changed): ignore silently.
        if (workplace == null || !workplace.geofenceEligible) return Result.success()

        val triggerSample = if (inputData.hasKeyWithValueOfType(KEY_LAT, Double::class.java)) {
            LocationSample(inputData.getDouble(KEY_LAT, 0.0), inputData.getDouble(KEY_LNG, 0.0), inputData.getDouble(KEY_ACC, 9_999.0), observedAt)
        } else {
            null
        }
        val today = graph.attendanceRepository.cachedTodayStatus()

        when (transition) {
            TRANSITION_DWELL -> {
                if (!workplace.entryDetectionEnabled) return Result.success()
                // Already clocked in / already asked: the server would refuse a second arrival (409 invalid_state).
                if (today == TodayStatus.CLOCKED_IN || today == TodayStatus.PENDING_ARRIVAL) return Result.success()
                propose(workplaceId, EventType.ARRIVAL, GeofenceTransition.DWELL, triggerSample, observedAt)
            }
            TRANSITION_EXIT -> {
                if (!workplace.exitDetectionEnabled) return Result.success()
                // Only an employee who is (or may be) clocked in can depart. Unknown state (no cache) → still ask.
                if (today == TodayStatus.NOT_STARTED || today == TodayStatus.CLOCKED_OUT || today == TodayStatus.PENDING_DEPARTURE) {
                    return Result.success()
                }
                enqueueRecheck(applicationContext, workplaceId, observedAt, workplace.exitDebounceSeconds)
            }
            STAGE_RECHECK -> {
                val recheck = graph.signalCollector.currentLocation()
                val decision = ExitDebounce.decide(
                    workplace = workplace.toGeometry(),
                    exitObservedAtMillis = observedAt,
                    nowMillis = System.currentTimeMillis(),
                    debounceSeconds = workplace.exitDebounceSeconds,
                    dwellSeenAfterExit = DetectionState(applicationContext).lastDwell(workplaceId) > observedAt,
                    recheck = recheck,
                )
                if (decision is DepartureDecision.Departed) {
                    // Detected time = the original EXIT time (when the employee actually left), not the re-check time.
                    propose(workplaceId, EventType.DEPARTURE, GeofenceTransition.EXIT, recheck, observedAt)
                }
                // StillInside / Undetermined: nothing. A missing clock-out is flagged by the server at
                // missing_clock_out_flag_local; the phone never invents a departure (e.g. phone left at work).
            }
        }
        return Result.success()
    }

    private suspend fun propose(workplaceId: String, type: EventType, transition: GeofenceTransition, fallback: LocationSample?, detectedAt: Long) {
        val graph = (applicationContext as MeridianApplication).graph
        val workplace = graph.configRepository.workplace(workplaceId) ?: return
        val signals = graph.signalCollector.collect(fallback)
        val verdict = Verification.evaluate(workplace.toGeometry(), signals, isDeparture = type == EventType.DEPARTURE)
        val event = graph.attendanceRepository.recordDetection(workplace, type, transition, signals, verdict, detectedAt)
        if (event.status == "pending") {
            graph.notifier.showConfirmationRequired(event)
            graph.reminderScheduler.schedule(event)
        }
        SyncScheduler.syncNow(applicationContext)
    }

    /** Needed for expedited work on API < 31, where WorkManager runs it as a short foreground service. */
    override suspend fun getForegroundInfo(): ForegroundInfo {
        val graph = (applicationContext as MeridianApplication).graph
        val notification = graph.notifier.buildWorkingNotification()
        // No foreground-service type: this path is only taken on API < 31, where types are not enforced.
        return ForegroundInfo(NotificationChannels.WORKING_NOTIFICATION_ID, notification)
    }

    companion object {
        const val TRANSITION_DWELL = "dwell"
        const val TRANSITION_EXIT = "exit"
        const val STAGE_RECHECK = "exit_recheck"
        private const val KEY_WORKPLACE = "workplace_id"
        private const val KEY_TRANSITION = "transition"
        private const val KEY_OBSERVED_AT = "observed_at"
        private const val KEY_LAT = "lat"
        private const val KEY_LNG = "lng"
        private const val KEY_ACC = "acc"

        fun enqueue(context: Context, workplaceId: String, transition: String, observedAtMillis: Long, latitude: Double?, longitude: Double?, accuracyM: Double?) {
            val pairs = mutableListOf<Pair<String, Any?>>(KEY_WORKPLACE to workplaceId, KEY_TRANSITION to transition, KEY_OBSERVED_AT to observedAtMillis)
            if (latitude != null && longitude != null) {
                pairs += KEY_LAT to latitude; pairs += KEY_LNG to longitude; pairs += KEY_ACC to (accuracyM ?: 9_999.0)
            }
            val request = OneTimeWorkRequestBuilder<GeofenceTransitionWorker>()
                .setInputData(workDataOf(*pairs.toTypedArray()))
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .addTag(TAG)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork("geofence-$transition-$workplaceId", ExistingWorkPolicy.REPLACE, request)
        }

        /** Debounced re-check. A new EXIT for the same workplace replaces the pending one (REPLACE). */
        fun enqueueRecheck(context: Context, workplaceId: String, exitObservedAt: Long, debounceSeconds: Int) {
            val request = OneTimeWorkRequestBuilder<GeofenceTransitionWorker>()
                .setInputData(workDataOf(KEY_WORKPLACE to workplaceId, KEY_TRANSITION to STAGE_RECHECK, KEY_OBSERVED_AT to exitObservedAt))
                .setInitialDelay(ExitDebounce.recheckDelayMillis(debounceSeconds), TimeUnit.MILLISECONDS)
                .addTag(TAG)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork("geofence-recheck-$workplaceId", ExistingWorkPolicy.REPLACE, request)
        }

        const val TAG = "geofence-transition"
    }
}

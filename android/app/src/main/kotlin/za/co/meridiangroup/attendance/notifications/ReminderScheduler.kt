package za.co.meridiangroup.attendance.notifications

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit
import za.co.meridiangroup.attendance.MeridianApplication
import za.co.meridiangroup.attendance.core.JohannesburgTime
import za.co.meridiangroup.attendance.data.local.AppPreferences
import za.co.meridiangroup.attendance.data.local.db.EventDao
import za.co.meridiangroup.attendance.data.local.db.EventEntity

/**
 * Re-notifies a still-pending confirmation after `confirmation_reminder_minutes` (default 15), repeating
 * until the event is confirmed, dismissed or expires. Purely local; independent of push delivery.
 */
class ReminderScheduler(private val context: Context, private val prefs: AppPreferences, private val events: EventDao) {

    suspend fun schedule(event: EventEntity, chainAfterCurrent: Boolean = false) {
        val minutes = (prefs.policy()?.confirmationReminderMinutes ?: DEFAULT_MINUTES).coerceIn(5, 240).toLong()
        val request = OneTimeWorkRequestBuilder<ReminderWorker>()
            .setInitialDelay(minutes, TimeUnit.MINUTES)
            .setInputData(workDataOf(KEY_REF to event.clientEventId))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            "reminder-${event.clientEventId}",
            if (chainAfterCurrent) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.KEEP,
            request,
        )
    }

    fun cancel(clientEventId: String) = WorkManager.getInstance(context).cancelUniqueWork("reminder-$clientEventId")

    /** Ensures every pending event (e.g. ones that arrived from the server) has a reminder scheduled. */
    suspend fun rescheduleFromPending() {
        events.awaitingConfirmation().forEach { schedule(it) }
    }

    companion object {
        const val DEFAULT_MINUTES = 15
        const val KEY_REF = "client_event_id"
    }
}

class ReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val graph = (applicationContext as MeridianApplication).graph
        val ref = inputData.getString(ReminderScheduler.KEY_REF) ?: return Result.success()
        val event = graph.db.eventDao().byRef(ref) ?: return Result.success()
        if (event.status != "pending" || event.localStatus == "confirming" || event.localStatus == "dismissing") return Result.success()
        val expires = JohannesburgTime.parse(event.expiresAt)
        if (expires != null && expires.toEpochMilli() <= System.currentTimeMillis()) return Result.success()
        graph.notifier.showConfirmationRequired(event, reminder = true)
        graph.db.eventDao().markNotified(event.clientEventId, System.currentTimeMillis())
        // Chain the next reminder after this run (KEEP would be a no-op while this worker is still RUNNING).
        graph.reminderScheduler.schedule(event, chainAfterCurrent = true)
        return Result.success()
    }
}

package za.co.meridiangroup.attendance.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit
import za.co.meridiangroup.attendance.MeridianApplication

/** Replays the outbox and refreshes server state. Runs periodically, on connectivity and on app open. */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val graph = (applicationContext as MeridianApplication).graph
        if (!graph.tokenStore.isLoggedIn) return Result.success()
        val result = graph.syncEngine.run(reconcile = true)
        // Config is cheap and drives geofences; refresh at most every 6 h from the background.
        if (!result.offline && System.currentTimeMillis() - graph.prefs.lastConfigFetchMillis() > CONFIG_MAX_AGE_MILLIS) {
            graph.configRepository.refresh()
        }
        graph.reminderScheduler.rescheduleFromPending()
        return when {
            result.offline -> Result.retry()
            result.needsRetry -> {
                // Remaining rows have their own next_attempt_at; a one-off delayed run picks them up.
                SyncScheduler.enqueueDelayed(applicationContext, RETRY_DELAY_MINUTES)
                Result.success()
            }
            else -> Result.success()
        }
    }

    private companion object {
        const val CONFIG_MAX_AGE_MILLIS = 6 * 60 * 60 * 1000L
        const val RETRY_DELAY_MINUTES = 2L
    }
}

object SyncScheduler {
    private const val PERIODIC = "sync-periodic"
    private const val NOW = "sync-now"
    private const val DELAYED = "sync-delayed"

    private val connected = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /** 15-minute periodic sync (the WorkManager minimum), only when a network is available. */
    fun schedulePeriodic(context: Context) {
        val request = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(connected)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    /**
     * Expedited one-off sync: on app open, after a detection/confirmation, and when connectivity returns
     * (the CONNECTED constraint makes WorkManager hold it until the network is back).
     */
    fun syncNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(connected)
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(NOW, ExistingWorkPolicy.REPLACE, request)
    }

    fun enqueueDelayed(context: Context, minutes: Long) {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(connected)
            .setInitialDelay(minutes, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(DELAYED, ExistingWorkPolicy.KEEP, request)
    }

    fun cancelAll(context: Context) {
        val wm = WorkManager.getInstance(context)
        wm.cancelUniqueWork(PERIODIC); wm.cancelUniqueWork(NOW); wm.cancelUniqueWork(DELAYED)
    }
}

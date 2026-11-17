package za.co.meridiangroup.attendance.detection

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit
import za.co.meridiangroup.attendance.MeridianApplication

/** Re-registers geofences from the cached config (and refreshes config when online). Used after boot / location toggles. */
class GeofenceRefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val graph = (applicationContext as MeridianApplication).graph
        if (!graph.tokenStore.isLoggedIn) return Result.success()
        graph.configRepository.invalidateGeofences()
        graph.configRepository.refresh(forceGeofences = true)
        return Result.success()
    }

    companion object {
        fun enqueue(context: Context, delayMinutes: Long) {
            val request = OneTimeWorkRequestBuilder<GeofenceRefreshWorker>()
                .setInitialDelay(delayMinutes, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork("geofence-refresh", ExistingWorkPolicy.REPLACE, request)
        }
    }
}

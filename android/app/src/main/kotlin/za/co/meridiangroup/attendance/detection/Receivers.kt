package za.co.meridiangroup.attendance.detection

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent
import za.co.meridiangroup.attendance.MeridianApplication

/**
 * Receives geofence transitions from Play services (also when the app process was killed) and hands them
 * to [GeofenceTransitionWorker] as expedited work. No logic runs on the receiver thread.
 */
class GeofenceBroadcastReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val event = GeofencingEvent.fromIntent(intent) ?: return
        if (event.hasError()) {
            // GEOFENCE_NOT_AVAILABLE (location turned off) removes all geofences: re-register when it is back.
            GeofenceRefreshWorker.enqueue(context, delayMinutes = 5)
            return
        }
        val transition = when (event.geofenceTransition) {
            Geofence.GEOFENCE_TRANSITION_DWELL -> GeofenceTransitionWorker.TRANSITION_DWELL
            Geofence.GEOFENCE_TRANSITION_EXIT -> GeofenceTransitionWorker.TRANSITION_EXIT
            Geofence.GEOFENCE_TRANSITION_ENTER -> return // ENTER is not used: people driving past must not trigger anything.
            else -> return
        }
        val now = System.currentTimeMillis()
        val loc = event.triggeringLocation
        event.triggeringGeofences.orEmpty().forEach { geofence ->
            if (transition == GeofenceTransitionWorker.TRANSITION_DWELL) DetectionState(context).markDwell(geofence.requestId, now)
            GeofenceTransitionWorker.enqueue(
                context = context,
                workplaceId = geofence.requestId,
                transition = transition,
                observedAtMillis = now,
                latitude = loc?.latitude,
                longitude = loc?.longitude,
                accuracyM = loc?.takeIf { it.hasAccuracy() }?.accuracy?.toDouble(),
            )
        }
    }

    companion object {
        const val ACTION = "za.co.meridiangroup.attendance.action.GEOFENCE_TRANSITION"
    }
}

/** Geofences are cleared by a reboot: re-register them (and resume periodic sync) after boot / app update. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_LOCKED_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> {
                val app = context.applicationContext as? MeridianApplication ?: return
                if (!app.graph.tokenStore.isLoggedIn) return
                GeofenceRefreshWorker.enqueue(context, delayMinutes = 0)
                za.co.meridiangroup.attendance.sync.SyncScheduler.schedulePeriodic(context)
            }
        }
    }
}

/**
 * Location services toggled. When switched off geofences stop (and are dropped by Play services); when switched
 * back on they are re-registered. The dashboard banner reflects the state via PermissionChecker on resume.
 */
class LocationProviderChangedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != LocationManager.PROVIDERS_CHANGED_ACTION) return
        val app = context.applicationContext as? MeridianApplication ?: return
        if (!app.graph.tokenStore.isLoggedIn) return
        if (app.graph.permissionChecker.locationServicesOn()) {
            GeofenceRefreshWorker.enqueue(context, delayMinutes = 0)
        } else {
            app.graph.notifier.showLocationOff()
        }
    }
}

/** Tiny persistent state used by the EXIT debounce (was there a DWELL after the EXIT?). */
class DetectionState(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("detection_state", Context.MODE_PRIVATE)

    fun markDwell(workplaceId: String, atMillis: Long) = prefs.edit().putLong("dwell_$workplaceId", atMillis).apply()
    fun lastDwell(workplaceId: String): Long = prefs.getLong("dwell_$workplaceId", 0L)
    fun clear() = prefs.edit().clear().apply()
}

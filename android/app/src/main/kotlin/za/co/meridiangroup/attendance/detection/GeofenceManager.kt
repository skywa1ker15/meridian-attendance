package za.co.meridiangroup.attendance.detection

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingClient
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import za.co.meridiangroup.attendance.data.local.db.WorkplaceEntity
import za.co.meridiangroup.attendance.data.repository.GeofenceRegistrar

/**
 * Registers OS geofences (docs/ARCHITECTURE.md §5 step 1): DWELL (loiteringDelay = dwell_seconds) for arrival,
 * EXIT for departure, initial trigger DWELL. Geofences live in Play services, so they survive the app being
 * killed; they are lost on reboot, app update/data clear and when location is switched off, which is why
 * BootReceiver, LocationProviderChangedReceiver and app start all call ConfigRepository.ensureGeofences(force=true).
 */
class GeofenceManager(private val context: Context, private val permissions: PermissionChecker) : GeofenceRegistrar {
    private val client: GeofencingClient by lazy { LocationServices.getGeofencingClient(context) }

    private val pendingIntent: PendingIntent by lazy {
        val intent = Intent(context, GeofenceBroadcastReceiver::class.java).setAction(GeofenceBroadcastReceiver.ACTION)
        // Must be MUTABLE: Play services fills in the GeofencingEvent extras.
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
        PendingIntent.getBroadcast(context, REQUEST_CODE, intent, flags)
    }

    @SuppressLint("MissingPermission")
    override suspend fun registerOnly(workplaces: List<WorkplaceEntity>): Boolean {
        if (!permissions.preciseLocationGranted() || !permissions.backgroundLocationGranted() || !permissions.locationServicesOn()) {
            return false
        }
        val geofences = workplaces.filter { it.geofenceEligible }.mapNotNull { wp ->
            var types = 0
            if (wp.entryDetectionEnabled) types = types or Geofence.GEOFENCE_TRANSITION_DWELL
            if (wp.exitDetectionEnabled) types = types or Geofence.GEOFENCE_TRANSITION_EXIT
            if (types == 0) return@mapNotNull null
            Geofence.Builder()
                .setRequestId(wp.id)
                .setCircularRegion(wp.latitude, wp.longitude, wp.radiusM.toFloat().coerceAtLeast(MIN_RADIUS_M))
                .setExpirationDuration(Geofence.NEVER_EXPIRE)
                .setTransitionTypes(types)
                .setLoiteringDelay((wp.dwellSeconds.coerceAtLeast(30)) * 1000)
                .setNotificationResponsiveness(RESPONSIVENESS_MS)
                .build()
        }
        runCatching { client.removeGeofences(pendingIntent).awaitOrNull() }
        if (geofences.isEmpty()) return true
        val request = GeofencingRequest.Builder()
            .setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_DWELL)
            .addGeofences(geofences)
            .build()
        val task = client.addGeofences(request, pendingIntent)
        val ok = runCatching { task.awaitOrNull(); task.isSuccessful }.getOrDefault(false)
        return ok
    }

    override suspend fun removeAll() {
        runCatching { client.removeGeofences(pendingIntent).awaitOrNull() }
    }

    private companion object {
        const val REQUEST_CODE = 4101
        /** Android recommends ≥ 100 m; below ~50 m geofences are unreliable. Server radius is respected above that. */
        const val MIN_RADIUS_M = 50f
        const val RESPONSIVENESS_MS = 60_000
    }
}

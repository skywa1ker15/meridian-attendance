package za.co.meridiangroup.attendance.detection

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.Location
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.SystemClock
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.android.gms.tasks.Task
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import za.co.meridiangroup.attendance.core.DetectionSignals
import za.co.meridiangroup.attendance.core.LocationSample
import za.co.meridiangroup.attendance.core.Verification

/**
 * Gathers the verification signals at detection / confirmation time: a fresh location fix with accuracy,
 * the connected Wi-Fi SSID/BSSID (only when the employee granted the permissions), and battery level.
 * Every signal is optional; missing signals lower the server's verification score, never block the flow.
 */
class SignalCollector(private val context: Context, private val permissions: PermissionChecker) {
    private val fused: FusedLocationProviderClient by lazy { LocationServices.getFusedLocationProviderClient(context) }

    suspend fun collect(fallback: LocationSample? = null): DetectionSignals {
        val location = currentLocation() ?: fallback
        val (ssid, bssid) = currentWifi()
        return DetectionSignals(location, ssid, bssid, batteryLevel())
    }

    /** Fresh high-accuracy fix (≤ 20 s), falling back to the last known fix if it is younger than 2 minutes. */
    @SuppressLint("MissingPermission")
    suspend fun currentLocation(timeoutMillis: Long = 20_000): LocationSample? {
        if (!permissions.preciseLocationGranted() && !permissions.coarseOnly()) return null
        if (!permissions.locationServicesOn()) return null
        val cts = CancellationTokenSource()
        val request = CurrentLocationRequest.Builder()
            .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
            .setMaxUpdateAgeMillis(30_000)
            .setDurationMillis(timeoutMillis)
            .build()
        val fresh = withTimeoutOrNull(timeoutMillis + 2_000) {
            runCatching { fused.getCurrentLocation(request, cts.token).awaitOrNull() }.getOrNull()
        }
        if (fresh == null) cts.cancel()
        val loc = fresh ?: runCatching { fused.lastLocation.awaitOrNull() }.getOrNull()?.takeIf { ageMillis(it) < 120_000 }
        return loc?.toSample()
    }

    private fun ageMillis(location: Location): Long =
        (SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos) / 1_000_000

    /** Converts to a sample whose timestamp is on the wall clock (`now - age`), robust to wrong GPS time. */
    private fun Location.toSample(): LocationSample =
        LocationSample(latitude, longitude, if (hasAccuracy()) accuracy.toDouble() else 9_999.0, System.currentTimeMillis() - ageMillis(this))

    /** Connected Wi-Fi network. API 31+: via NetworkCallback with FLAG_INCLUDE_LOCATION_INFO (WifiInfo is redacted otherwise). */
    suspend fun currentWifi(): Pair<String?, String?> {
        if (!permissions.wifiInfoGranted() || !permissions.wifiEnabled()) return null to null
        val info: WifiInfo? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) wifiInfoViaCallback() else legacyWifiInfo()
        return Verification.normaliseSsid(info?.ssid) to Verification.normaliseBssid(info?.bssid)
    }

    @Suppress("DEPRECATION")
    private fun legacyWifiInfo(): WifiInfo? = runCatching {
        context.applicationContext.getSystemService(WifiManager::class.java)?.connectionInfo
    }.getOrNull()

    @SuppressLint("NewApi")
    private suspend fun wifiInfoViaCallback(): WifiInfo? {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val request = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build()
        return withTimeoutOrNull(3_000) {
            suspendCancellableCoroutine<WifiInfo?> { cont ->
                val callback = object : ConnectivityManager.NetworkCallback(ConnectivityManager.NetworkCallback.FLAG_INCLUDE_LOCATION_INFO) {
                    override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                        val info = caps.transportInfo as? WifiInfo
                        if (info != null && cont.isActive) {
                            runCatching { cm.unregisterNetworkCallback(this) }
                            cont.resume(info)
                        }
                    }
                }
                runCatching { cm.registerNetworkCallback(request, callback) }.onFailure { if (cont.isActive) cont.resume(null) }
                cont.invokeOnCancellation { runCatching { cm.unregisterNetworkCallback(callback) } }
            }
        }
    }

    fun batteryLevel(): Int? {
        val bm = context.getSystemService(BatteryManager::class.java)
        val level = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: Int.MIN_VALUE
        if (level in 0..100) return level
        val intent: Intent? = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val raw = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        return if (raw >= 0 && scale > 0) raw * 100 / scale else null
    }
}

/** Minimal Task → coroutine bridge (avoids an extra kotlinx-coroutines-play-services dependency). */
suspend fun <T> Task<T>.awaitOrNull(): T? = suspendCancellableCoroutine<T?> { cont ->
    addOnSuccessListener { if (cont.isActive) cont.resume(it) }
    addOnFailureListener { if (cont.isActive) cont.resume(null) }
    addOnCanceledListener { if (cont.isActive) cont.resume(null) }
}

package za.co.meridiangroup.attendance.detection

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import za.co.meridiangroup.attendance.core.PermissionSnapshot

/** Reads the current permission / device-setting state. Re-evaluated on every resume (revocation detection). */
class PermissionChecker(private val context: Context) {

    private fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    fun notificationsGranted(): Boolean {
        val runtime = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || granted(Manifest.permission.POST_NOTIFICATIONS)
        return runtime && NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    fun preciseLocationGranted(): Boolean = granted(Manifest.permission.ACCESS_FINE_LOCATION)

    fun coarseOnly(): Boolean = !preciseLocationGranted() && granted(Manifest.permission.ACCESS_COARSE_LOCATION)

    fun backgroundLocationGranted(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION) else preciseLocationGranted()

    fun batteryOptimisationIgnored(): Boolean {
        val pm = context.getSystemService(PowerManager::class.java) ?: return true
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    /** Permission needed to read the connected SSID/BSSID. */
    fun wifiInfoGranted(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            granted(Manifest.permission.NEARBY_WIFI_DEVICES) && preciseLocationGranted()
        } else {
            preciseLocationGranted()
        }

    @Suppress("DEPRECATION")
    fun wifiEnabled(): Boolean = runCatching { context.applicationContext.getSystemService(WifiManager::class.java)?.isWifiEnabled == true }.getOrDefault(false)

    fun locationServicesOn(): Boolean {
        val lm = context.getSystemService(android.location.LocationManager::class.java) ?: return false
        return LocationManagerCompat.isLocationEnabled(lm)
    }

    fun snapshot(): PermissionSnapshot = PermissionSnapshot(
        notificationsGranted = notificationsGranted(),
        preciseLocationGranted = preciseLocationGranted(),
        backgroundLocationGranted = backgroundLocationGranted(),
        batteryOptimisationIgnored = batteryOptimisationIgnored(),
        wifiGranted = wifiInfoGranted(),
        locationServicesOn = locationServicesOn(),
    )

    // ---- Intents used by onboarding / profile ----

    fun appSettingsIntent(): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun locationSettingsIntent(): Intent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun wifiSettingsIntent(): Intent = Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /**
     * Opens the system battery-optimisation list. We deliberately do not use
     * ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS (needs a Play-restricted permission); the employee picks
     * "Meridian Attendance" → "Don't optimise" / "Unrestricted" after reading our explanation.
     */
    fun batterySettingsIntent(): Intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun notificationSettingsIntent(): Intent =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        } else {
            appSettingsIntent()
        }
}

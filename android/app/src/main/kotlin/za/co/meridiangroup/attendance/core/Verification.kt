package za.co.meridiangroup.attendance.core

/** Geometry/Wi-Fi settings of a workplace as returned by GET /config/workplaces. */
data class WorkplaceGeometry(
    val latitude: Double,
    val longitude: Double,
    val radiusM: Double,
    val accuracyThresholdM: Double,
    val wifiVerificationEnabled: Boolean = false,
    val wifiSsid: String? = null,
    val wifiBssids: List<String> = emptyList(),
    val wifiRequired: Boolean = false,
)

/** A location fix as observed on the device. */
data class LocationSample(
    val latitude: Double,
    val longitude: Double,
    val accuracyM: Double,
    val observedAtMillis: Long,
)

/** Signals collected when a detection happens. */
data class DetectionSignals(
    val location: LocationSample?,
    val wifiSsid: String?,
    val wifiBssid: String?,
    val batteryLevel: Int?,
)

/**
 * Result of the *local* pre-check. This is only used to tell the employee what the phone saw
 * (distance, Wi-Fi match) and to predict whether a manager review is likely. The server's
 * verification engine is authoritative (docs/ARCHITECTURE.md §3).
 */
data class LocalVerification(
    val distanceM: Double?,
    val insideGeofence: Boolean?,
    val accuracyOk: Boolean?,
    val wifiMatched: Boolean?,
    val score: Double,
    val likelyUnderReview: Boolean,
    val notes: List<String>,
)

object Verification {
    private const val WEIGHT_INSIDE = 0.5
    private const val WEIGHT_ACCURACY = 0.2
    private const val WEIGHT_WIFI = 0.3

    fun normaliseSsid(ssid: String?): String? {
        if (ssid.isNullOrBlank()) return null
        val trimmed = ssid.trim().removeSurrounding("\"")
        return if (trimmed.isBlank() || trimmed == "<unknown ssid>") null else trimmed
    }

    fun normaliseBssid(bssid: String?): String? {
        if (bssid.isNullOrBlank()) return null
        val v = bssid.trim().lowercase()
        return if (v == "02:00:00:00:00:00" || v == "00:00:00:00:00:00") null else v
    }

    /** True when the connected network matches the workplace's SSID or one of its BSSIDs. */
    fun wifiMatches(workplace: WorkplaceGeometry, ssid: String?, bssid: String?): Boolean? {
        if (!workplace.wifiVerificationEnabled) return null
        val nSsid = normaliseSsid(ssid)
        val nBssid = normaliseBssid(bssid)
        if (nSsid == null && nBssid == null) return false
        val bssidMatch = nBssid != null && workplace.wifiBssids.any { normaliseBssid(it) == nBssid }
        val ssidMatch = nSsid != null && workplace.wifiSsid != null &&
            normaliseSsid(workplace.wifiSsid).equals(nSsid, ignoreCase = true)
        return bssidMatch || ssidMatch
    }

    fun evaluate(workplace: WorkplaceGeometry, signals: DetectionSignals): LocalVerification {
        val notes = mutableListOf<String>()
        val loc = signals.location
        val distance = loc?.let { Geo.distanceMeters(it.latitude, it.longitude, workplace.latitude, workplace.longitude) }
        val inside = if (loc != null && distance != null) distance <= workplace.radiusM + loc.accuracyM else null
        val accuracyOk = loc?.let { it.accuracyM <= workplace.accuracyThresholdM }
        val wifi = wifiMatches(workplace, signals.wifiSsid, signals.wifiBssid)

        if (loc == null) notes += "no_location"
        if (inside == false) notes += "outside_geofence"
        if (accuracyOk == false) notes += "accuracy_above_threshold"
        if (workplace.wifiVerificationEnabled && wifi == false) notes += "wifi_not_matched"

        var score = 0.0
        var usedWeight = 0.0
        if (inside != null) { usedWeight += WEIGHT_INSIDE; if (inside) score += WEIGHT_INSIDE }
        if (accuracyOk != null) { usedWeight += WEIGHT_ACCURACY; if (accuracyOk) score += WEIGHT_ACCURACY }
        if (wifi != null) { usedWeight += WEIGHT_WIFI; if (wifi) score += WEIGHT_WIFI }
        val normalised = if (usedWeight == 0.0) 0.0 else (score / usedWeight).coerceIn(0.0, 1.0)

        val review = loc == null || inside == false || (workplace.wifiRequired && wifi != true)
        return LocalVerification(
            distanceM = distance,
            insideGeofence = inside,
            accuracyOk = accuracyOk,
            wifiMatched = wifi,
            score = (normalised * 100).toLong() / 100.0,
            likelyUnderReview = review,
            notes = notes,
        )
    }
}

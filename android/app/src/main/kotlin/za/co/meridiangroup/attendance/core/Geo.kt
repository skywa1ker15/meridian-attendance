package za.co.meridiangroup.attendance.core

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

object Geo {
    private const val EARTH_RADIUS_M = 6_371_008.8

    /** Great-circle distance in metres (haversine). */
    fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return EARTH_RADIUS_M * c
    }

    fun isValidLatitude(lat: Double): Boolean = lat.isFinite() && lat >= -90.0 && lat <= 90.0
    fun isValidLongitude(lon: Double): Boolean = lon.isFinite() && lon >= -180.0 && lon <= 180.0

    /** Human distance: `42 m`, `1.3 km`. */
    fun formatDistance(meters: Double?): String = when {
        meters == null || !meters.isFinite() || meters < 0 -> "—"
        meters < 1000 -> "${meters.toLong()} m"
        else -> String.format(java.util.Locale.UK, "%.1f km", meters / 1000.0)
    }
}

package za.co.meridiangroup.attendance.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VerificationTest {
    private val umhlanga = WorkplaceGeometry(
        latitude = -29.7261, longitude = 31.0856, radiusM = 150.0, accuracyThresholdM = 100.0,
        wifiVerificationEnabled = true, wifiSsid = "Meridian-Staff", wifiBssids = listOf("AA:BB:CC:DD:EE:FF"), wifiRequired = false,
    )

    @Test
    fun haversineIsRoughlyRight() {
        // ~111 km per degree of latitude
        val d = Geo.distanceMeters(0.0, 0.0, 1.0, 0.0)
        assertTrue(d > 110_000 && d < 112_000, "got $d")
        assertEquals(0.0, Geo.distanceMeters(-29.7, 31.0, -29.7, 31.0), 0.001)
    }

    @Test
    fun insideWithGoodAccuracyAndWifiScoresFull() {
        val signals = DetectionSignals(LocationSample(-29.7262, 31.0857, 18.0, 0L), "\"Meridian-Staff\"", "aa:bb:cc:dd:ee:ff", 73)
        val v = Verification.evaluate(umhlanga, signals)
        assertEquals(true, v.insideGeofence)
        assertEquals(true, v.accuracyOk)
        assertEquals(true, v.wifiMatched)
        assertEquals(1.0, v.score)
        assertFalse(v.likelyUnderReview)
        assertTrue(v.notes.isEmpty())
        assertTrue(v.distanceM!! < 30)
    }

    @Test
    fun outsideGeofenceIsFlaggedForReview() {
        val farAway = LocationSample(-29.74, 31.10, 10.0, 0L)
        val v = Verification.evaluate(umhlanga, DetectionSignals(farAway, null, null, null))
        assertEquals(false, v.insideGeofence)
        assertTrue(v.likelyUnderReview)
        assertTrue("outside_geofence" in v.notes)
        assertTrue("wifi_not_matched" in v.notes)
        assertEquals(0.2, v.score)
    }

    @Test
    fun accuracyExpandsTheRadius() {
        // 200 m away with 80 m accuracy: 200 <= 150 + 80 → inside, but accuracy fine (<=100)
        val lat = -29.7261 + (200.0 / 111_320.0)
        val v = Verification.evaluate(umhlanga, DetectionSignals(LocationSample(lat, 31.0856, 80.0, 0L), null, null, null))
        assertEquals(true, v.insideGeofence)
        assertEquals(true, v.accuracyOk)
    }

    @Test
    fun missingLocationMeansReview() {
        val v = Verification.evaluate(umhlanga, DetectionSignals(null, "Meridian-Staff", null, 50))
        assertNull(v.insideGeofence)
        assertNull(v.distanceM)
        assertTrue(v.likelyUnderReview)
        assertEquals(true, v.wifiMatched)
        assertEquals(1.0, v.score) // only the wifi signal was available and it matched
    }

    @Test
    fun wifiRequiredWithoutMatchIsReview() {
        val wp = umhlanga.copy(wifiRequired = true)
        val v = Verification.evaluate(wp, DetectionSignals(LocationSample(-29.7261, 31.0856, 5.0, 0L), "Guest", "11:22:33:44:55:66", 50))
        assertEquals(false, v.wifiMatched)
        assertTrue(v.likelyUnderReview)
    }

    @Test
    fun wifiDisabledYieldsNullMatch() {
        val wp = umhlanga.copy(wifiVerificationEnabled = false)
        val v = Verification.evaluate(wp, DetectionSignals(LocationSample(-29.7261, 31.0856, 5.0, 0L), "Meridian-Staff", null, 50))
        assertNull(v.wifiMatched)
        assertEquals(1.0, v.score)
    }

    @Test
    fun departureOutsideGeofenceIsNotReview() {
        val farAway = LocationSample(-29.74, 31.10, 10.0, 0L)
        val v = Verification.evaluate(umhlanga, DetectionSignals(farAway, null, null, null), isDeparture = true)
        assertEquals(false, v.insideGeofence)
        // Departure outside is EXPECTED — should NOT be flagged for review
        assertFalse(v.likelyUnderReview)
        assertTrue("departure_still_inside" !in v.notes)
    }

    @Test
    fun departureStillInsideIsFlaggedForReview() {
        val inside = LocationSample(-29.7262, 31.0857, 18.0, 0L)
        val v = Verification.evaluate(umhlanga, DetectionSignals(inside, null, null, null), isDeparture = true)
        assertEquals(true, v.insideGeofence)
        // Departure while still inside the geofence → review
        assertTrue(v.likelyUnderReview)
        assertTrue("departure_still_inside" in v.notes)
    }

    @Test
    fun departureDoesNotRequireWifi() {
        val wp = umhlanga.copy(wifiRequired = true)
        val farAway = LocationSample(-29.74, 31.10, 10.0, 0L)
        val v = Verification.evaluate(wp, DetectionSignals(farAway, null, null, null), isDeparture = true)
        // Even with wifiRequired, departure is not flagged just because wifi doesn't match
        assertFalse(v.likelyUnderReview)
    }

    @Test
    fun ssidAndBssidNormalisation() {
        assertNull(Verification.normaliseSsid("<unknown ssid>"))
        assertNull(Verification.normaliseSsid("  "))
        assertEquals("Meridian-Staff", Verification.normaliseSsid("\"Meridian-Staff\""))
        assertNull(Verification.normaliseBssid("02:00:00:00:00:00"))
        assertEquals("aa:bb:cc:dd:ee:ff", Verification.normaliseBssid("AA:BB:CC:DD:EE:FF"))
    }
}

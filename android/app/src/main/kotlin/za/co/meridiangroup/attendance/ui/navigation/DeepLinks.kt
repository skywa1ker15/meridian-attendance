package za.co.meridiangroup.attendance.ui.navigation

import android.net.Uri

/** Deep links used by notifications. Only the app itself creates them (the scheme is not exported to the web). */
object DeepLinks {
    const val SCHEME = "meridianattendance"
    const val CONFIRM_PATTERN = "$SCHEME://confirm/{ref}"
    const val SESSION_PATTERN = "$SCHEME://session/{id}"
    const val NOTIFICATIONS = "$SCHEME://notifications"
    const val REQUESTS = "$SCHEME://requests"
    const val ONBOARDING = "$SCHEME://onboarding"
    const val DASHBOARD = "$SCHEME://home"

    fun confirm(ref: String): Uri = Uri.parse("$SCHEME://confirm/${Uri.encode(ref)}")
    fun session(id: String): Uri = Uri.parse("$SCHEME://session/${Uri.encode(id)}")
    fun notifications(): Uri = Uri.parse(NOTIFICATIONS)
    fun requests(): Uri = Uri.parse(REQUESTS)
    fun onboarding(): Uri = Uri.parse(ONBOARDING)
    fun dashboard(): Uri = Uri.parse(DASHBOARD)

    /** Maps an FCM / inbox notification to the screen it should open. */
    fun forNotification(type: String, data: Map<String, String>): Uri {
        val eventId = data["event_id"]?.takeIf { it.isNotBlank() }
        return when (type) {
            "arrival_detected", "departure_detected", "clock_in_confirmation_required", "clock_out_confirmation_required" ->
                eventId?.let { confirm(it) } ?: dashboard()
            "manual_request_reviewed", "correction_approved", "correction_rejected" -> requests()
            "attendance_recorded" -> data["session_id"]?.takeIf { it.isNotBlank() }?.let { session(it) } ?: dashboard()
            "attendance_submission_failed" -> eventId?.let { confirm(it) } ?: dashboard()
            else -> notifications()
        }
    }
}

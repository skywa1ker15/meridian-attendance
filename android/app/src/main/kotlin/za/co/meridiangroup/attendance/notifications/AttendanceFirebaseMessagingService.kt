package za.co.meridiangroup.attendance.notifications

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import za.co.meridiangroup.attendance.MeridianApplication
import za.co.meridiangroup.attendance.core.JohannesburgTime
import za.co.meridiangroup.attendance.core.NotificationType
import za.co.meridiangroup.attendance.data.remote.dto.NotificationDto
import za.co.meridiangroup.attendance.ui.navigation.DeepLinks

/**
 * Handles FCM *data* messages (docs/API.md §6: all values are strings —
 * type, notification_id, event_id, request_id, title, body). Push is a convenience only: the same
 * information is always available through the inbox and GET /attendance/pending on app open / sync.
 */
class AttendanceFirebaseMessagingService : FirebaseMessagingService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNewToken(token: String) {
        val graph = (application as MeridianApplication).graph
        scope.launch { graph.fcmTokenManager.onNewToken(token) }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val graph = (application as MeridianApplication).graph
        if (!graph.tokenStore.isLoggedIn) return
        val data = message.data
        val type = NotificationType.fromWire(data["type"])
        val title = data["title"] ?: message.notification?.title.orEmpty()
        val body = data["body"] ?: message.notification?.body.orEmpty()
        val notificationId = data["notification_id"]?.takeIf { it.isNotBlank() } ?: "push-${System.currentTimeMillis()}"

        scope.launch {
            graph.notificationRepository.storePushed(
                NotificationDto(
                    id = notificationId, type = type.wire, title = title, body = body,
                    data = data.filterKeys { it in setOf("event_id", "request_id", "session_id") },
                    readAt = null, createdAt = JohannesburgTime.toIsoUtc(System.currentTimeMillis()),
                ),
            )
            when {
                type == NotificationType.DEVICE_REVOKED -> {
                    graph.notifier.showGeneral(notificationId, title, body, DeepLinks.dashboard(), highPriority = true)
                    graph.authRepository.forceLogout()
                }
                type.isConfirmation -> {
                    // Pull the event so the local pending row exists, then show the rich confirmation notification.
                    graph.attendanceRepository.refreshPending()
                    val eventId = data["event_id"]
                    val local = eventId?.let { graph.attendanceRepository.eventByRef(it) }
                    if (local != null && local.status == "pending") {
                        graph.notifier.showConfirmationRequired(local)
                        graph.reminderScheduler.schedule(local)
                    } else {
                        graph.notifier.showGeneral(notificationId, title, body, DeepLinks.forNotification(type.wire, data), highPriority = true)
                    }
                }
                else -> {
                    graph.notifier.showGeneral(notificationId, title, body, DeepLinks.forNotification(type.wire, data))
                    if (type == NotificationType.ATTENDANCE_RECORDED || type == NotificationType.CORRECTION_APPROVED) {
                        graph.attendanceRepository.refreshToday()
                    }
                }
            }
        }
    }
}

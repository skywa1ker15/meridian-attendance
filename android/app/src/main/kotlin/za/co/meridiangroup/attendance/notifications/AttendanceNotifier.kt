package za.co.meridiangroup.attendance.notifications

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import za.co.meridiangroup.attendance.MainActivity
import za.co.meridiangroup.attendance.R
import za.co.meridiangroup.attendance.core.EventType
import za.co.meridiangroup.attendance.core.Geo
import za.co.meridiangroup.attendance.core.JohannesburgTime
import za.co.meridiangroup.attendance.core.OutboxKind
import za.co.meridiangroup.attendance.data.local.db.EventEntity
import za.co.meridiangroup.attendance.sync.SyncFailureListener
import za.co.meridiangroup.attendance.ui.navigation.DeepLinks

object NotificationChannels {
    const val CONFIRMATIONS = "confirmations"
    const val GENERAL = "general"
    const val BACKGROUND = "background"
    const val WORKING_NOTIFICATION_ID = 9001
    const val LOCATION_OFF_ID = 9002
    const val SUBMISSION_FAILED_ID = 9003

    fun create(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CONFIRMATIONS, context.getString(R.string.channel_confirmations), NotificationManager.IMPORTANCE_HIGH).apply {
                description = context.getString(R.string.channel_confirmations_desc)
                enableVibration(true)
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(GENERAL, context.getString(R.string.channel_general), NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = context.getString(R.string.channel_general_desc)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(BACKGROUND, context.getString(R.string.channel_background), NotificationManager.IMPORTANCE_MIN).apply {
                description = context.getString(R.string.channel_background_desc)
                setShowBadge(false)
            },
        )
    }
}

/**
 * All local notifications. Confirmation notifications use the high-importance channel (heads-up) and deep-link to
 * the confirmation screen; they never contain coordinates (lock-screen privacy: VISIBILITY_PRIVATE + public version).
 */
class AttendanceNotifier(private val context: Context) : SyncFailureListener {
    private val nm = NotificationManagerCompat.from(context)

    private fun canPost(): Boolean =
        (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
            nm.areNotificationsEnabled()

    private fun deepLinkIntent(uri: Uri, requestCode: Int): PendingIntent {
        val intent = Intent(Intent.ACTION_VIEW, uri, context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(context, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun notifyIdFor(ref: String): Int = 1_000 + (ref.hashCode() and 0x0FFFFFFF) % 100_000

    @SuppressLint("MissingPermission")
    private fun post(id: Int, notification: Notification) {
        if (!canPost()) return
        runCatching { nm.notify(id, notification) }
    }

    fun showConfirmationRequired(event: EventEntity, reminder: Boolean = false) {
        val type = EventType.fromWire(event.eventType)
        val arrival = type == EventType.ARRIVAL
        val title = context.getString(
            if (reminder) R.string.notif_reminder_title else if (arrival) R.string.notif_arrival_title else R.string.notif_departure_title,
        )
        val time = JohannesburgTime.time(event.detectedAt)
        val where = event.workplaceName ?: context.getString(R.string.your_workplace)
        val body = context.getString(
            if (arrival) R.string.notif_arrival_body else R.string.notif_departure_body,
            where, time, Geo.formatDistance(event.distanceM),
        )
        val content = deepLinkIntent(DeepLinks.confirm(event.ref), notifyIdFor(event.clientEventId))
        val publicVersion = NotificationCompat.Builder(context, NotificationChannels.CONFIRMATIONS)
            .setSmallIcon(R.drawable.ic_stat_attendance)
            .setContentTitle(context.getString(R.string.notif_public_title))
            .build()
        val n = NotificationCompat.Builder(context, NotificationChannels.CONFIRMATIONS)
            .setSmallIcon(R.drawable.ic_stat_attendance)
            .setColor(ContextCompat.getColor(context, R.color.navy_700))
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion)
            .setAutoCancel(true)
            .setOnlyAlertOnce(!reminder)
            .setContentIntent(content)
            .addAction(0, context.getString(if (arrival) R.string.action_review_arrival else R.string.action_review_departure), content)
            .build()
        post(notifyIdFor(event.clientEventId), n)
    }

    fun cancelConfirmation(clientEventId: String) = nm.cancel(notifyIdFor(clientEventId))

    /** Generic notification from FCM / inbox (non-confirmation types). */
    fun showGeneral(id: String, title: String, body: String, uri: Uri, highPriority: Boolean = false) {
        val channel = if (highPriority) NotificationChannels.CONFIRMATIONS else NotificationChannels.GENERAL
        val n = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_stat_attendance)
            .setColor(ContextCompat.getColor(context, R.color.navy_700))
            .setContentTitle(title.ifBlank { context.getString(R.string.app_name) })
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(if (highPriority) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(deepLinkIntent(uri, id.hashCode()))
            .build()
        post(2_000_000 + (id.hashCode() and 0x00FFFFFF), n)
    }

    fun showLocationOff() {
        val n = NotificationCompat.Builder(context, NotificationChannels.GENERAL)
            .setSmallIcon(R.drawable.ic_stat_attendance)
            .setContentTitle(context.getString(R.string.notif_location_off_title))
            .setContentText(context.getString(R.string.notif_location_off_body))
            .setStyle(NotificationCompat.BigTextStyle().bigText(context.getString(R.string.notif_location_off_body)))
            .setAutoCancel(true)
            .setContentIntent(deepLinkIntent(DeepLinks.onboarding(), NotificationChannels.LOCATION_OFF_ID))
            .build()
        post(NotificationChannels.LOCATION_OFF_ID, n)
    }

    fun buildWorkingNotification(): Notification =
        NotificationCompat.Builder(context, NotificationChannels.BACKGROUND)
            .setSmallIcon(R.drawable.ic_stat_attendance)
            .setContentTitle(context.getString(R.string.notif_working))
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .build()

    override fun onPermanentFailure(kind: OutboxKind, clientId: String, reason: String) {
        val text = context.getString(R.string.notif_submission_failed_body)
        val n = NotificationCompat.Builder(context, NotificationChannels.GENERAL)
            .setSmallIcon(R.drawable.ic_stat_attendance)
            .setContentTitle(context.getString(R.string.notif_submission_failed_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(deepLinkIntent(DeepLinks.dashboard(), NotificationChannels.SUBMISSION_FAILED_ID))
            .build()
        post(NotificationChannels.SUBMISSION_FAILED_ID, n)
    }
}

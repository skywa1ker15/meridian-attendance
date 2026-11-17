package za.co.meridiangroup.attendance.notifications

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import za.co.meridiangroup.attendance.core.OutboxKind
import za.co.meridiangroup.attendance.data.local.AppPreferences
import za.co.meridiangroup.attendance.data.local.TokenStore
import za.co.meridiangroup.attendance.data.remote.dto.FcmTokenRequestDto
import za.co.meridiangroup.attendance.data.repository.OutboxWriter
import za.co.meridiangroup.attendance.detection.awaitOrNull
import za.co.meridiangroup.attendance.sync.SyncScheduler

/**
 * Keeps the server's copy of the FCM token current via the outbox (kind=fcm_token, client id = token).
 * When the build has no google-services.json Firebase is not initialised and push is simply unavailable.
 */
class FcmTokenManager(
    private val context: Context,
    private val prefs: AppPreferences,
    private val tokenStore: TokenStore,
    private val outbox: OutboxWriter,
) {
    val firebaseAvailable: Boolean get() = FirebaseApp.getApps(context).isNotEmpty()

    suspend fun onNewToken(token: String) {
        prefs.setFcmToken(token)
        if (tokenStore.isLoggedIn && prefs.fcmTokenUploaded() != token) {
            outbox.enqueue(OutboxKind.FCM_TOKEN, token.takeLast(64), null, FcmTokenRequestDto.serializer(), FcmTokenRequestDto(token))
            SyncScheduler.syncNow(context)
        }
    }

    /** Called after login and on app start. */
    suspend fun ensureUploaded() {
        if (!firebaseAvailable) return
        val token = runCatching { FirebaseMessaging.getInstance().token.awaitOrNull() }.getOrNull() ?: return
        onNewToken(token)
    }
}

package za.co.meridiangroup.attendance.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import za.co.meridiangroup.attendance.data.remote.ApiJson
import za.co.meridiangroup.attendance.data.remote.dto.PolicyDto
import za.co.meridiangroup.attendance.data.remote.dto.UserDto

private val Context.appPrefsDataStore: DataStore<Preferences> by preferencesDataStore(name = "app_prefs")

/** Non-secret app state: installation id, cached user/policy JSON, onboarding flags, sync metadata. */
class AppPreferences(context: Context) {
    private val ds = context.applicationContext.appPrefsDataStore

    private object Keys {
        val installationId = stringPreferencesKey("installation_id")
        val userJson = stringPreferencesKey("user_json")
        val policyJson = stringPreferencesKey("policy_json")
        val mustChangePassword = booleanPreferencesKey("must_change_password")
        val onboardingDone = booleanPreferencesKey("onboarding_done")
        val configVersion = intPreferencesKey("config_version")
        val lastSyncMillis = longPreferencesKey("last_sync_millis")
        val lastConfigFetchMillis = longPreferencesKey("last_config_fetch_millis")
        val fcmToken = stringPreferencesKey("fcm_token")
        val fcmTokenUploaded = stringPreferencesKey("fcm_token_uploaded")
        val geofencesRegisteredVersion = intPreferencesKey("geofences_registered_version")
        val pushEnabled = booleanPreferencesKey("push_enabled")
        val deviceStatus = stringPreferencesKey("device_status")
        /** Backend root chosen in Settings → Server (survives logout; null = BuildConfig default). */
        val serverRoot = stringPreferencesKey("server_root")

        // ---- App settings (survive logout) ----
        /** "system" (default), "light", "dark" */
        val themeMode = stringPreferencesKey("settings_theme_mode")
        /** 0 = Small, 1 = Medium (default), 2 = Large */
        val fontSize = intPreferencesKey("settings_font_size")
        val reduceAnimations = booleanPreferencesKey("settings_reduce_animations")
        val notificationSound = booleanPreferencesKey("settings_notification_sound")
        val vibration = booleanPreferencesKey("settings_vibration")
        val biometricLock = booleanPreferencesKey("settings_biometric_lock")
        /** Minutes: 0 = immediately, 1, 5, 15 */
        val autoLockTimeout = intPreferencesKey("settings_auto_lock_timeout")
        val showLocationOnEvents = booleanPreferencesKey("settings_show_location_on_events")
        val offlineMode = booleanPreferencesKey("settings_offline_mode")
    }

    /** Generated once per installation and reused for every login (API.md `device.device_id`). */
    fun installationId(): String = runBlocking {
        val existing = ds.data.first()[Keys.installationId]
        if (existing != null) return@runBlocking existing
        val fresh = UUID.randomUUID().toString()
        ds.edit { it[Keys.installationId] = fresh }
        fresh
    }

    val userFlow: Flow<UserDto?> = ds.data.map { prefs -> prefs[Keys.userJson]?.let { decodeUser(it) } }
    val mustChangePasswordFlow: Flow<Boolean> = ds.data.map { it[Keys.mustChangePassword] ?: false }
    val onboardingDoneFlow: Flow<Boolean> = ds.data.map { it[Keys.onboardingDone] ?: false }
    val lastSyncFlow: Flow<Long?> = ds.data.map { it[Keys.lastSyncMillis] }
    val policyFlow: Flow<PolicyDto?> = ds.data.map { prefs -> prefs[Keys.policyJson]?.let { decodePolicy(it) } }
    val pushEnabledFlow: Flow<Boolean> = ds.data.map { it[Keys.pushEnabled] ?: true }
    val deviceStatusFlow: Flow<String?> = ds.data.map { it[Keys.deviceStatus] }

    // ---- App settings flows (survive logout) ----
    val themeModeFlow: Flow<String> = ds.data.map { it[Keys.themeMode] ?: "system" }
    val fontSizeFlow: Flow<Int> = ds.data.map { it[Keys.fontSize] ?: 1 }
    val reduceAnimationsFlow: Flow<Boolean> = ds.data.map { it[Keys.reduceAnimations] ?: false }
    val notificationSoundFlow: Flow<Boolean> = ds.data.map { it[Keys.notificationSound] ?: true }
    val vibrationFlow: Flow<Boolean> = ds.data.map { it[Keys.vibration] ?: true }
    val biometricLockFlow: Flow<Boolean> = ds.data.map { it[Keys.biometricLock] ?: false }
    val autoLockTimeoutFlow: Flow<Int> = ds.data.map { it[Keys.autoLockTimeout] ?: 1 }
    val showLocationOnEventsFlow: Flow<Boolean> = ds.data.map { it[Keys.showLocationOnEvents] ?: true }
    val offlineModeFlow: Flow<Boolean> = ds.data.map { it[Keys.offlineMode] ?: true }

    suspend fun setThemeMode(mode: String) = ds.edit { it[Keys.themeMode] = mode }
    suspend fun setFontSize(size: Int) = ds.edit { it[Keys.fontSize] = size }
    suspend fun setReduceAnimations(enabled: Boolean) = ds.edit { it[Keys.reduceAnimations] = enabled }
    suspend fun setNotificationSound(enabled: Boolean) = ds.edit { it[Keys.notificationSound] = enabled }
    suspend fun setVibration(enabled: Boolean) = ds.edit { it[Keys.vibration] = enabled }
    suspend fun setBiometricLock(enabled: Boolean) = ds.edit { it[Keys.biometricLock] = enabled }
    suspend fun setAutoLockTimeout(minutes: Int) = ds.edit { it[Keys.autoLockTimeout] = minutes }
    suspend fun setShowLocationOnEvents(enabled: Boolean) = ds.edit { it[Keys.showLocationOnEvents] = enabled }
    suspend fun setOfflineMode(enabled: Boolean) = ds.edit { it[Keys.offlineMode] = enabled }

    val serverRootFlow: Flow<String?> = ds.data.map { it[Keys.serverRoot] }
    suspend fun serverRoot(): String? = ds.data.first()[Keys.serverRoot]
    suspend fun setServerRoot(root: String?) = ds.edit { if (root == null) it.remove(Keys.serverRoot) else it[Keys.serverRoot] = root }

    suspend fun user(): UserDto? = ds.data.first()[Keys.userJson]?.let { decodeUser(it) }
    suspend fun policy(): PolicyDto? = ds.data.first()[Keys.policyJson]?.let { decodePolicy(it) }
    suspend fun onboardingDone(): Boolean = ds.data.first()[Keys.onboardingDone] ?: false
    suspend fun configVersion(): Int = ds.data.first()[Keys.configVersion] ?: -1
    suspend fun geofencesRegisteredVersion(): Int = ds.data.first()[Keys.geofencesRegisteredVersion] ?: -1
    suspend fun lastConfigFetchMillis(): Long = ds.data.first()[Keys.lastConfigFetchMillis] ?: 0L
    suspend fun fcmToken(): String? = ds.data.first()[Keys.fcmToken]
    suspend fun fcmTokenUploaded(): String? = ds.data.first()[Keys.fcmTokenUploaded]
    suspend fun mustChangePassword(): Boolean = ds.data.first()[Keys.mustChangePassword] ?: false

    suspend fun saveUser(user: UserDto, mustChangePassword: Boolean = user.mustChangePassword) = ds.edit {
        it[Keys.userJson] = ApiJson.json.encodeToString(UserDto.serializer(), user)
        it[Keys.mustChangePassword] = mustChangePassword
    }

    suspend fun setMustChangePassword(value: Boolean) = ds.edit { it[Keys.mustChangePassword] = value }
    suspend fun savePolicy(policy: PolicyDto) = ds.edit { it[Keys.policyJson] = ApiJson.json.encodeToString(PolicyDto.serializer(), policy) }
    suspend fun setOnboardingDone(done: Boolean) = ds.edit { it[Keys.onboardingDone] = done }
    suspend fun setConfigVersion(version: Int, fetchedAtMillis: Long) = ds.edit { it[Keys.configVersion] = version; it[Keys.lastConfigFetchMillis] = fetchedAtMillis }
    suspend fun setGeofencesRegisteredVersion(version: Int) = ds.edit { it[Keys.geofencesRegisteredVersion] = version }
    suspend fun setLastSync(millis: Long) = ds.edit { it[Keys.lastSyncMillis] = millis }
    suspend fun setFcmToken(token: String) = ds.edit { it[Keys.fcmToken] = token }
    suspend fun setFcmTokenUploaded(token: String) = ds.edit { it[Keys.fcmTokenUploaded] = token }
    suspend fun setPushEnabled(enabled: Boolean) = ds.edit { it[Keys.pushEnabled] = enabled }
    suspend fun setDeviceStatus(status: String?) = ds.edit { if (status == null) it.remove(Keys.deviceStatus) else it[Keys.deviceStatus] = status }

    /** Clears everything tied to the signed-in user but keeps the installation id and onboarding flag. */
    suspend fun clearSession() = ds.edit {
        it.remove(Keys.userJson); it.remove(Keys.policyJson); it.remove(Keys.mustChangePassword)
        it.remove(Keys.configVersion); it.remove(Keys.lastSyncMillis); it.remove(Keys.fcmTokenUploaded)
        it.remove(Keys.geofencesRegisteredVersion); it.remove(Keys.deviceStatus); it.remove(Keys.lastConfigFetchMillis)
    }

    private fun decodeUser(json: String): UserDto? = runCatching { ApiJson.json.decodeFromString(UserDto.serializer(), json) }.getOrNull()
    private fun decodePolicy(json: String): PolicyDto? = runCatching { ApiJson.json.decodeFromString(PolicyDto.serializer(), json) }.getOrNull()
}

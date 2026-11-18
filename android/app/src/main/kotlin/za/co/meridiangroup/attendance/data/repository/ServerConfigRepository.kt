package za.co.meridiangroup.attendance.data.repository

import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import za.co.meridiangroup.attendance.BuildConfig
import za.co.meridiangroup.attendance.core.ServerUrlResult
import za.co.meridiangroup.attendance.core.ServerUrls
import za.co.meridiangroup.attendance.data.local.AppPreferences
import za.co.meridiangroup.attendance.data.remote.ApiJson
import za.co.meridiangroup.attendance.data.remote.BaseUrlHolder
import za.co.meridiangroup.attendance.data.remote.dto.HealthDto

/** Outcome of "Test connection" (GET <root>healthz). */
sealed class ConnectionTestResult {
    data class Ok(val version: String?) : ConnectionTestResult()
    data class Failed(val message: String) : ConnectionTestResult()
}

/**
 * The backend address used by the app (login screen "Server" setting). Persisted in DataStore; the default is
 * BuildConfig.API_BASE_URL. Release builds accept only https:// (BuildConfig.ALLOW_CLEARTEXT = false).
 */
class ServerConfigRepository(
    private val prefs: AppPreferences,
    private val holder: BaseUrlHolder,
    private val defaultRoot: String,
    private val probeClient: () -> OkHttpClient,
) {
    private val _root = MutableStateFlow(holder.root)
    val root: StateFlow<String> = _root.asStateFlow()
    val allowCleartext: Boolean get() = BuildConfig.ALLOW_CLEARTEXT
    val isDefault: Boolean get() = _root.value == defaultRoot
    val default: String get() = defaultRoot

    fun validate(input: String): ServerUrlResult = ServerUrls.normalise(input, allowCleartext)

    /** Saves and activates [root] (already validated). Callers log out first when a session exists. */
    suspend fun apply(root: String) {
        prefs.setServerRoot(if (root == defaultRoot) null else root)
        holder.set(root)
        _root.value = root
    }

    suspend fun resetToDefault() = apply(defaultRoot)

    suspend fun testConnection(root: String): ConnectionTestResult = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(ServerUrls.healthUrl(root)).header("Accept", "application/json").get().build()
        try {
            probeClient().newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) return@use ConnectionTestResult.Failed("The server answered HTTP ${response.code}. Check the address.")
                val health = runCatching { ApiJson.json.decodeFromString(HealthDto.serializer(), text) }.getOrNull()
                if (health == null || health.status != "ok") ConnectionTestResult.Failed("That address answered, but it is not a Meridian Attendance server.")
                else ConnectionTestResult.Ok(health.version)
            }
        } catch (e: javax.net.ssl.SSLException) {
            ConnectionTestResult.Failed("Secure connection failed (${e.message ?: "TLS error"}). Check the certificate or use https://.")
        } catch (e: IOException) {
            ConnectionTestResult.Failed("Could not reach the server (${e.message ?: "network error"}). Check the address and that this phone is on the right network.")
        } catch (e: IllegalArgumentException) {
            ConnectionTestResult.Failed("That address is not valid.")
        }
    }
}

package za.co.meridiangroup.attendance.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import za.co.meridiangroup.attendance.data.remote.ApiJson

@Serializable
data class TokenPair(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String,
    @SerialName("access_expires_at_millis") val accessExpiresAtMillis: Long,
) {
    fun isAccessExpired(nowMillis: Long = System.currentTimeMillis()): Boolean = nowMillis >= accessExpiresAtMillis - 10_000
}

/** Abstraction used by the HTTP layer (and faked in JVM tests). */
interface TokenHolder {
    fun current(): TokenPair?
    fun save(pair: TokenPair)
    fun clear()
}

private val Context.secureTokenDataStore: DataStore<Preferences> by preferencesDataStore(name = "secure_tokens")

/**
 * Persists the token pair as an AES-GCM blob (key in Android Keystore) inside a dedicated DataStore
 * file that is excluded from backups (res/xml/backup_rules.xml, data_extraction_rules.xml).
 * [current] is synchronous because OkHttp interceptors are not suspending; the value is cached in memory.
 */
class TokenStore(context: Context, private val crypto: KeystoreCrypto = KeystoreCrypto()) : TokenHolder {
    private val dataStore = context.applicationContext.secureTokenDataStore
    private val key = stringPreferencesKey("token_blob")
    /** Dedicated IO scope for fire-and-forget disk writes. SupervisorJob prevents one failure from
     *  cancelling subsequent writes. The in-memory cache is updated synchronously so callers never
     *  see stale data; the disk write simply persists for the next cold-start. */
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var loaded = false
    @Volatile private var cache: TokenPair? = null
    private val lock = Any()

    override fun current(): TokenPair? {
        if (!loaded) {
            synchronized(lock) {
                if (!loaded) {
                    // runBlocking is acceptable here: called from OkHttp threads (never Main) and only on
                    // the first access — thereafter the in-memory cache is used.
                    cache = runBlocking { readFromDisk() }
                    loaded = true
                }
            }
        }
        return cache
    }

    val isLoggedIn: Boolean get() = current() != null

    private suspend fun readFromDisk(): TokenPair? {
        val blob = dataStore.data.first()[key] ?: return null
        val plain = crypto.decrypt(blob) ?: return null
        return runCatching { ApiJson.json.decodeFromString(TokenPair.serializer(), plain) }.getOrNull()
    }

    override fun save(pair: TokenPair) {
        synchronized(lock) { cache = pair; loaded = true }
        // Persist to disk on IO — never blocks the calling thread (which may be Main after login).
        ioScope.launch {
            val blob = crypto.encrypt(ApiJson.json.encodeToString(TokenPair.serializer(), pair))
            dataStore.edit { it[key] = blob }
        }
    }

    override fun clear() {
        synchronized(lock) { cache = null; loaded = true }
        ioScope.launch { dataStore.edit { it.remove(key) } }
    }
}

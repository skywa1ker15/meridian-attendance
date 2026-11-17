package za.co.meridiangroup.attendance.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide online/offline state for the sync chip. When the network comes back it triggers an
 * expedited sync (in addition to WorkManager's own CONNECTED constraint on queued work).
 */
class ConnectivityMonitor(private val context: Context) {
    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private val _online = MutableStateFlow(currentlyOnline())
    val online: StateFlow<Boolean> = _online.asStateFlow()
    private var registered = false

    fun start() {
        if (registered) return
        val request = NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build()
        runCatching {
            cm.registerNetworkCallback(
                request,
                object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        val wasOffline = !_online.value
                        _online.value = true
                        if (wasOffline) SyncScheduler.syncNow(context)
                    }

                    override fun onLost(network: Network) {
                        _online.value = currentlyOnline()
                    }
                },
            )
            registered = true
        }
    }

    private fun currentlyOnline(): Boolean {
        val caps = cm?.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
}

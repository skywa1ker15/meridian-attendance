package za.co.meridiangroup.attendance

import android.app.Application
import kotlinx.coroutines.launch
import za.co.meridiangroup.attendance.notifications.NotificationChannels

/**
 * WorkManager uses its default initializer: workers obtain dependencies from [graph], so no custom
 * WorkerFactory / Configuration.Provider is needed.
 */
class MeridianApplication : Application() {
    /** Replaceable in instrumented tests before the activity starts. */
    lateinit var graph: AppGraph

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        NotificationChannels.create(this)
        graph.connectivity.start()
        graph.appScope.launch {
            graph.authRepository.restore()
            if (graph.tokenStore.isLoggedIn) graph.onSessionActive()
        }
    }
}

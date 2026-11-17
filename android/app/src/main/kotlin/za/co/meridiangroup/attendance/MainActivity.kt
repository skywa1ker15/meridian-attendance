package za.co.meridiangroup.attendance

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import za.co.meridiangroup.attendance.sync.SyncScheduler
import za.co.meridiangroup.attendance.ui.navigation.AppRoot
import za.co.meridiangroup.attendance.ui.theme.MeridianTheme

class MainActivity : ComponentActivity() {
    private var pendingDeepLink by mutableStateOf<Intent?>(null)
    private var ready by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        splash.setKeepOnScreenCondition { !ready }
        enableEdgeToEdge()
        val graph = (application as MeridianApplication).graph
        lifecycleScope.launch {
            graph.authRepository.restore()
            ready = true
        }
        if (savedInstanceState == null) pendingDeepLink = intent?.takeIf { it.data != null }
        setContent {
            MeridianTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    if (ready) AppRoot(pendingDeepLink = pendingDeepLink, onDeepLinkConsumed = { pendingDeepLink = null })
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.data != null) pendingDeepLink = intent
    }

    override fun onStart() {
        super.onStart()
        // "On app open" sync (docs/ARCHITECTURE.md §5): outbox replay + today/pending/inbox refresh.
        val graph = (application as MeridianApplication).graph
        if (graph.tokenStore.isLoggedIn) {
            SyncScheduler.syncNow(this)
            lifecycleScope.launch { graph.configRepository.ensureGeofences(force = false) }
        }
    }
}

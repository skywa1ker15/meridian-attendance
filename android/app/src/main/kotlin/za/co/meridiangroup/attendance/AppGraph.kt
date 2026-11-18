package za.co.meridiangroup.attendance

import android.content.Context
import androidx.core.app.NotificationManagerCompat
import androidx.work.WorkManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import za.co.meridiangroup.attendance.core.ApiErrorMapper
import za.co.meridiangroup.attendance.data.local.AppPreferences
import za.co.meridiangroup.attendance.data.local.TokenStore
import za.co.meridiangroup.attendance.data.local.db.AppDatabase
import za.co.meridiangroup.attendance.data.remote.ApiCaller
import za.co.meridiangroup.attendance.data.remote.ApiClientFactory
import za.co.meridiangroup.attendance.data.remote.AttendanceApi
import za.co.meridiangroup.attendance.data.remote.BaseUrlHolder
import za.co.meridiangroup.attendance.core.ServerUrls
import za.co.meridiangroup.attendance.data.files.ExportStore
import za.co.meridiangroup.attendance.data.repository.ServerConfigRepository
import za.co.meridiangroup.attendance.data.remote.ForcedLogoutListener
import za.co.meridiangroup.attendance.data.repository.AdminRepository
import za.co.meridiangroup.attendance.data.repository.AttendanceRepository
import za.co.meridiangroup.attendance.data.repository.AuthRepository
import za.co.meridiangroup.attendance.data.repository.ConfigRepository
import za.co.meridiangroup.attendance.data.repository.GeofenceRegistrar
import za.co.meridiangroup.attendance.data.repository.NotificationRepository
import za.co.meridiangroup.attendance.data.repository.OutboxWriter
import za.co.meridiangroup.attendance.data.repository.RequestRepository
import za.co.meridiangroup.attendance.detection.DetectionState
import za.co.meridiangroup.attendance.detection.GeofenceManager
import za.co.meridiangroup.attendance.detection.GeofenceTransitionWorker
import za.co.meridiangroup.attendance.detection.PermissionChecker
import za.co.meridiangroup.attendance.detection.SignalCollector
import za.co.meridiangroup.attendance.notifications.AttendanceNotifier
import za.co.meridiangroup.attendance.notifications.FcmTokenManager
import za.co.meridiangroup.attendance.notifications.ReminderScheduler
import za.co.meridiangroup.attendance.sync.ConnectivityMonitor
import za.co.meridiangroup.attendance.sync.SyncEngine
import za.co.meridiangroup.attendance.sync.SyncScheduler

/**
 * Application-scoped manual dependency graph (docs/ARCHITECTURE.md §5: no Hilt/annotation-processing DI).
 * Tests construct it with a fake [apiOverride], [baseUrlOverride] (MockWebServer), an in-memory DB or a fake registrar.
 */
class AppGraph(
    context: Context,
    apiOverride: AttendanceApi? = null,
    baseUrlOverride: String? = null,
    inMemoryDatabase: Boolean = false,
    registrarOverride: GeofenceRegistrar? = null,
    val now: () -> Long = { System.currentTimeMillis() },
) {
    val appContext: Context = context.applicationContext
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _sessionMessages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    /** One-off messages for the UI (e.g. "Your session has expired"). */
    val sessionMessages: SharedFlow<String> = _sessionMessages.asSharedFlow()

    val prefs = AppPreferences(appContext)
    val tokenStore = TokenStore(appContext)
    val db: AppDatabase = if (inMemoryDatabase) AppDatabase.inMemory(appContext) else AppDatabase.build(appContext)

    private val forcedLogout = ForcedLogoutListener { failure ->
        appScope.launch {
            if (tokenStore.isLoggedIn || failure.code == "device_revoked") {
                authRepository.forceLogout()
                _sessionMessages.tryEmit(ApiErrorMapper.userMessage(failure))
            }
        }
    }

    /** Current backend (Settings → Server); every Retrofit request is rewritten onto it. */
    private val defaultServerRoot: String = baseUrlOverride?.let { ServerUrls.rootOf(it) } ?: ApiClientFactory.defaultRoot()
    val baseUrlHolder = BaseUrlHolder(
        if (baseUrlOverride != null) defaultServerRoot else runBlocking { prefs.serverRoot() } ?: defaultServerRoot,
    )

    val api: AttendanceApi = apiOverride ?: run {
        val refreshApi = ApiClientFactory.createRefreshApi(baseUrlHolder)
        ApiClientFactory.createApi(tokenStore, refreshApi, forcedLogout, baseUrlHolder)
    }
    val apiCaller = ApiCaller(forcedLogout)
    val apiBaseUrl: String get() = baseUrlHolder.apiBaseUrl
    val serverConfigRepository = ServerConfigRepository(prefs, baseUrlHolder, defaultServerRoot) { ApiClientFactory.probeClient() }
    val exportStore = ExportStore(appContext)

    /** Set after POST /setup/first-admin so the Manage home can offer workplace setup once. */
    val offerWorkplaceSetup = MutableStateFlow(false)

    val permissionChecker = PermissionChecker(appContext)
    val signalCollector = SignalCollector(appContext, permissionChecker)
    val geofenceRegistrar: GeofenceRegistrar = registrarOverride ?: GeofenceManager(appContext, permissionChecker)
    val notifier = AttendanceNotifier(appContext)
    val connectivity = ConnectivityMonitor(appContext)

    val outboxWriter = OutboxWriter(db.outboxDao(), now)

    val authRepository: AuthRepository by lazy {
        AuthRepository(api, apiCaller, tokenStore, prefs, now, onLocalDataWipe = ::wipeLocalData)
    }
    val attendanceRepository = AttendanceRepository(api, apiCaller, db, outboxWriter, now)
    val configRepository = ConfigRepository(api, apiCaller, db.workplaceDao(), prefs, geofenceRegistrar, now)
    val notificationRepository = NotificationRepository(api, apiCaller, db.notificationDao(), now)
    val requestRepository = RequestRepository(api, apiCaller, outboxWriter)
    val adminRepository = AdminRepository(api, apiCaller)
    val reminderScheduler = ReminderScheduler(appContext, prefs, db.eventDao())
    val fcmTokenManager = FcmTokenManager(appContext, prefs, tokenStore, outboxWriter)
    val syncEngine = SyncEngine(api, apiCaller, db, attendanceRepository, notificationRepository, prefs, notifier, now)

    /** Work done after login and on every app start while logged in. */
    fun onSessionActive() {
        SyncScheduler.schedulePeriodic(appContext)
        SyncScheduler.syncNow(appContext)
        appScope.launch {
            configRepository.refresh()
            configRepository.ensureGeofences(force = true)
            fcmTokenManager.ensureUploaded()
            reminderScheduler.rescheduleFromPending()
        }
    }

    /** After a workplace is created/edited from the app: pull the new config and re-register geofences now. */
    fun onWorkplacesChanged() {
        appScope.launch { configRepository.refresh(forceGeofences = true) }
    }

    private suspend fun wipeLocalData() {
        withContext(Dispatchers.IO) {
            geofenceRegistrar.removeAll()
            SyncScheduler.cancelAll(appContext)
            WorkManager.getInstance(appContext).cancelAllWorkByTag(GeofenceTransitionWorker.TAG)
            db.clearAllTables()
            DetectionState(appContext).clear()
            NotificationManagerCompat.from(appContext).cancelAll()
        }
    }
}

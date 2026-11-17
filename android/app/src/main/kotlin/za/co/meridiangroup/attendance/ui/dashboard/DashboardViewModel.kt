package za.co.meridiangroup.attendance.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import za.co.meridiangroup.attendance.AppGraph
import za.co.meridiangroup.attendance.core.ApiResult
import za.co.meridiangroup.attendance.core.JohannesburgTime
import za.co.meridiangroup.attendance.core.PermissionSnapshot
import za.co.meridiangroup.attendance.core.ServerPermissions
import za.co.meridiangroup.attendance.core.SyncStatus
import za.co.meridiangroup.attendance.data.local.db.EventEntity
import za.co.meridiangroup.attendance.data.local.db.WorkplaceEntity
import za.co.meridiangroup.attendance.data.remote.dto.SessionDto
import za.co.meridiangroup.attendance.data.remote.dto.TodaySummaryDto
import za.co.meridiangroup.attendance.data.remote.dto.UserDto
import za.co.meridiangroup.attendance.data.toDto
import za.co.meridiangroup.attendance.sync.SyncScheduler

data class DashboardUiState(
    val user: UserDto? = null,
    val today: TodaySummaryDto? = null,
    val awaiting: List<EventEntity> = emptyList(),
    val recent: List<SessionDto> = emptyList(),
    val unread: Int = 0,
    val sync: SyncStatus = SyncStatus(online = true, syncing = false, queued = 0, dead = 0, lastSyncMillis = null),
    val workplaces: List<WorkplaceEntity> = emptyList(),
    val standardDayMinutes: Int = 480,
    val deviceStatus: String? = null,
) {
    val hasAdmin: Boolean get() = ServerPermissions.hasAdminSection(user?.permissions.orEmpty())
    /** Workplace-not-configured banner: no active workplace with confirmed coordinates. */
    val workplaceNotConfigured: Boolean get() = workplaces.isNotEmpty() && workplaces.none { it.active && it.coordinatesConfirmed }
    val noWorkplaces: Boolean get() = workplaces.isEmpty()
}

class DashboardViewModel(private val graph: AppGraph) : ViewModel() {
    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error
    private val _permissions = MutableStateFlow(graph.permissionChecker.snapshot())
    val permissions: StateFlow<PermissionSnapshot> = _permissions

    private val syncStatus = combine(
        graph.connectivity.online,
        graph.syncEngine.syncing,
        graph.db.outboxDao().observeQueuedCount(),
        graph.db.outboxDao().observeDeadCount(),
        graph.prefs.lastSyncFlow,
    ) { online, syncing, queued, dead, last -> SyncStatus(online, syncing, queued, dead, last) }

    private val base = combine(
        graph.prefs.userFlow,
        graph.attendanceRepository.today,
        graph.attendanceRepository.awaitingConfirmation,
        graph.db.sessionDao().observeRecent(5).map { list -> list.mapNotNull { it.toDto() } },
        graph.notificationRepository.unreadCount,
    ) { user, today, awaiting, recent, unread -> DashboardUiState(user, today, awaiting, recent, unread) }

    val state: StateFlow<DashboardUiState> = combine(
        base, syncStatus, graph.configRepository.workplaces, graph.prefs.policyFlow, graph.prefs.deviceStatusFlow,
    ) { s, sync, wps, policy, device ->
        s.copy(
            sync = sync, workplaces = wps, standardDayMinutes = policy?.standardDayMinutes ?: 480, deviceStatus = device,
            unread = maxOf(s.unread, s.today?.unreadNotifications ?: 0),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DashboardUiState())

    init { refresh() }

    fun recheckPermissions() { _permissions.value = graph.permissionChecker.snapshot() }

    fun refresh() {
        if (_refreshing.value) return
        _refreshing.value = true
        viewModelScope.launch {
            val today = JohannesburgTime.workDateOf(graph.now())
            val jobs = listOf(
                async { graph.attendanceRepository.refreshToday() },
                async { graph.attendanceRepository.refreshPending() },
                async { graph.notificationRepository.refresh() },
                async { graph.attendanceRepository.sessions(JohannesburgTime.daysAgo(today, 31), today, page = 1, pageSize = 10) },
                async { graph.authRepository.refreshMe() },
            )
            val results = jobs.awaitAll()
            graph.configRepository.refresh()
            val first = results.firstOrNull { it !is ApiResult.Success<*> }
            _error.update { first?.userMessage() }
            SyncScheduler.syncNow(graph.appContext)
            _refreshing.value = false
        }
    }

    /** Permanently failed items cannot succeed (e.g. expired event); the employee acknowledges them. */
    fun acknowledgeFailedItems() {
        viewModelScope.launch { graph.db.outboxDao().clearDead() }
    }

    fun syncNow() {
        viewModelScope.launch { graph.syncEngine.run() }
    }
}

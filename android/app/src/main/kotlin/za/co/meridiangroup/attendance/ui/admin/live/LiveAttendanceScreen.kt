package za.co.meridiangroup.attendance.ui.admin.live

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import za.co.meridiangroup.attendance.R
import za.co.meridiangroup.attendance.core.ApiResult
import za.co.meridiangroup.attendance.core.JohannesburgTime
import za.co.meridiangroup.attendance.core.StatusTones
import za.co.meridiangroup.attendance.core.TodayStatus
import za.co.meridiangroup.attendance.core.WorkedTime
import za.co.meridiangroup.attendance.data.remote.dto.AdminDepartmentDto
import za.co.meridiangroup.attendance.data.remote.dto.LiveAttendanceResponseDto
import za.co.meridiangroup.attendance.data.repository.AdminRepository
import za.co.meridiangroup.attendance.ui.admin.AdminDropdown
import za.co.meridiangroup.attendance.ui.admin.AdminTitle
import za.co.meridiangroup.attendance.ui.components.EmptyState
import za.co.meridiangroup.attendance.ui.components.ErrorState
import za.co.meridiangroup.attendance.ui.components.GlassCard
import za.co.meridiangroup.attendance.ui.components.LoadingState
import za.co.meridiangroup.attendance.ui.components.StatTile
import za.co.meridiangroup.attendance.ui.components.StatusChip
import za.co.meridiangroup.attendance.ui.dashboard.statusLabel
import za.co.meridiangroup.attendance.ui.theme.MeridianTheme
import za.co.meridiangroup.attendance.ui.theme.Spacing

data class LiveUiState(
    val data: LiveAttendanceResponseDto? = null,
    val departments: List<AdminDepartmentDto> = emptyList(),
    val departmentId: String = "",
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val error: String? = null,
)

class LiveAttendanceViewModel(private val repo: AdminRepository) : ViewModel() {
    private val _state = MutableStateFlow(LiveUiState())
    val state: StateFlow<LiveUiState> = _state.asStateFlow()

    init {
        load()
        viewModelScope.launch { repo.departments().getOrNull()?.let { d -> _state.update { it.copy(departments = d.items) } } }
    }

    fun onDepartment(id: String) { _state.update { it.copy(departmentId = id) }; load() }

    fun load(refreshing: Boolean = false) {
        _state.update { it.copy(loading = it.data == null, refreshing = refreshing, error = null) }
        viewModelScope.launch {
            when (val r = repo.live(_state.value.departmentId)) {
                is ApiResult.Success -> _state.update { it.copy(data = r.value, loading = false, refreshing = false, error = null) }
                else -> _state.update { it.copy(loading = false, refreshing = false, error = r.userMessage()) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveAttendanceScreen(viewModel: LiveAttendanceViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val data = state.data
    PullToRefreshBox(isRefreshing = state.refreshing, onRefresh = { viewModel.load(refreshing = true) }, modifier = Modifier.fillMaxSize()) {
        when {
            state.loading && data == null -> LoadingState(Modifier.fillMaxSize())
            data == null -> ErrorState(state.error.orEmpty(), { viewModel.load() })
            else -> {
                val items = data.items
                val counts = items.groupingBy { TodayStatus.fromWire(it.status) }.eachCount()
                LazyColumn(
                    Modifier.fillMaxSize().testTag("admin_live_list"),
                    contentPadding = PaddingValues(Spacing.l),
                    verticalArrangement = Arrangement.spacedBy(Spacing.s),
                ) {
                    item { AdminTitle(stringResource(R.string.admin_live_title), JohannesburgTime.workDateLong(data.workDate)) }
                    item {
                        AdminDropdown(
                            stringResource(R.string.profile_department),
                            listOf("" to stringResource(R.string.filter_all_departments)) + state.departments.map { it.id to it.name },
                            state.departmentId, viewModel::onDepartment, tag = "live_department",
                        )
                    }
                    item {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                            StatTile(stringResource(R.string.today_clocked_in), (counts[TodayStatus.CLOCKED_IN] ?: 0).toString(), Modifier.weight(1f), Icons.Outlined.Groups)
                            StatTile(
                                stringResource(R.string.admin_pending),
                                ((counts[TodayStatus.PENDING_ARRIVAL] ?: 0) + (counts[TodayStatus.PENDING_DEPARTURE] ?: 0)).toString(),
                                Modifier.weight(1f),
                            )
                        }
                    }
                    item {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                            StatTile(stringResource(R.string.today_not_started), (counts[TodayStatus.NOT_STARTED] ?: 0).toString(), Modifier.weight(1f))
                            StatTile(stringResource(R.string.manage_total), items.size.toString(), Modifier.weight(1f))
                        }
                    }
                    if (items.isEmpty()) item { EmptyState(stringResource(R.string.admin_live_empty_title), stringResource(R.string.admin_live_empty_body)) }
                    items(items, key = { it.employee.id }) { row ->
                        val st = TodayStatus.fromWire(row.status)
                        GlassCard(contentPadding = PaddingValues(Spacing.m)) {
                            Row(verticalAlignment = Alignment.Top) {
                                Column(Modifier.weight(1f)) {
                                    Text(row.employee.fullName, style = MaterialTheme.typography.titleSmall)
                                    Text(
                                        listOfNotNull(row.employee.employeeNumber, row.employee.department?.name).joinToString(" · "),
                                        style = MaterialTheme.typography.bodySmall, color = MeridianTheme.extended.mutedText,
                                    )
                                    Text(
                                        "${row.clockInLocal ?: "—"} – ${row.clockOutLocal ?: "—"}  ·  ${WorkedTime.format(row.workedMinutes)}",
                                        style = MaterialTheme.typography.bodySmall, color = MeridianTheme.extended.mutedText,
                                    )
                                }
                                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                                    StatusChip(st.wire, label = statusLabel(st), tone = StatusTones.forToday(st))
                                    if (row.isLate) StatusChip("late", label = stringResource(R.string.status_late))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

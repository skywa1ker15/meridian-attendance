package za.co.meridiangroup.attendance.ui.admin.exceptions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import za.co.meridiangroup.attendance.data.remote.dto.AdminDepartmentDto
import za.co.meridiangroup.attendance.data.remote.dto.AttendanceExceptionDto
import za.co.meridiangroup.attendance.data.repository.AdminRepository
import za.co.meridiangroup.attendance.ui.admin.AdminDropdown
import za.co.meridiangroup.attendance.ui.admin.AdminTitle
import za.co.meridiangroup.attendance.ui.admin.DateField
import za.co.meridiangroup.attendance.ui.admin.PagedFooter
import za.co.meridiangroup.attendance.ui.admin.PagedUiState
import za.co.meridiangroup.attendance.ui.components.GlassCard
import za.co.meridiangroup.attendance.ui.components.StatusChip
import za.co.meridiangroup.attendance.ui.theme.MeridianTheme
import za.co.meridiangroup.attendance.ui.theme.Spacing

/** docs/API.md §7 exception kinds. */
private val KINDS = listOf(
    "", "missing_clock_out", "late_arrival", "under_review", "expired_confirmation",
    "dismissed_detection", "absent", "rejected_event", "outside_geofence",
)

data class ExceptionsUiState(
    val paged: PagedUiState<AttendanceExceptionDto> = PagedUiState(),
    val from: String? = null,
    val to: String? = null,
    val kind: String = "",
    val departmentId: String = "",
    val departments: List<AdminDepartmentDto> = emptyList(),
)

class ExceptionsViewModel(private val repo: AdminRepository, now: () -> Long) : ViewModel() {
    private val today = JohannesburgTime.workDateOf(now())
    private val _state = MutableStateFlow(ExceptionsUiState(from = JohannesburgTime.daysAgo(today, 6), to = today))
    val state: StateFlow<ExceptionsUiState> = _state.asStateFlow()

    init {
        reload()
        viewModelScope.launch { repo.departments().getOrNull()?.let { d -> _state.update { it.copy(departments = d.items) } } }
    }

    fun setFrom(v: String) { _state.update { it.copy(from = v) }; reload() }
    fun setTo(v: String) { _state.update { it.copy(to = v) }; reload() }
    fun setKind(v: String) { _state.update { it.copy(kind = v) }; reload() }
    fun setDepartment(v: String) { _state.update { it.copy(departmentId = v) }; reload() }

    fun reload() { _state.update { it.copy(paged = it.paged.reset()) }; loadMore() }

    fun loadMore() {
        val s = _state.value
        if (s.paged.loading || !s.paged.hasMore) return
        _state.update { it.copy(paged = it.paged.startLoading()) }
        viewModelScope.launch {
            when (val r = repo.exceptions(s.from, s.to, s.departmentId, s.kind, s.paged.page + 1)) {
                is ApiResult.Success -> _state.update { it.copy(paged = it.paged.append(r.value.items, r.value.page, r.value.hasMore)) }
                else -> _state.update { it.copy(paged = it.paged.failed(r.userMessage())) }
            }
        }
    }
}

@Composable
fun ExceptionsScreen(viewModel: ExceptionsViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LazyColumn(
        Modifier.fillMaxSize().testTag("exceptions_list"),
        contentPadding = PaddingValues(Spacing.l),
        verticalArrangement = Arrangement.spacedBy(Spacing.s),
    ) {
        item { AdminTitle(stringResource(R.string.manage_exceptions), stringResource(R.string.manage_exceptions_body)) }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                DateField(stringResource(R.string.records_from), state.from, viewModel::setFrom, Modifier.weight(1f), tag = "exceptions_from")
                DateField(stringResource(R.string.records_to), state.to, viewModel::setTo, Modifier.weight(1f), tag = "exceptions_to")
            }
        }
        item {
            AdminDropdown(
                stringResource(R.string.exceptions_kind),
                KINDS.map { it to if (it.isBlank()) stringResource(R.string.filter_all) else StatusTones.label(it) },
                state.kind, viewModel::setKind, tag = "exceptions_kind",
            )
        }
        item {
            AdminDropdown(
                stringResource(R.string.profile_department),
                listOf("" to stringResource(R.string.filter_all_departments)) + state.departments.map { it.id to it.name },
                state.departmentId, viewModel::setDepartment, tag = "exceptions_department",
            )
        }
        items(state.paged.items, key = { "${it.kind}-${it.workDate}-${it.employee?.id}-${it.eventId ?: it.sessionId ?: ""}" }) { row ->
            GlassCard(contentPadding = PaddingValues(Spacing.m)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(row.employee?.fullName ?: "—", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "${JohannesburgTime.workDate(row.workDate)} · ${row.detail}",
                            style = MaterialTheme.typography.bodySmall, color = MeridianTheme.extended.mutedText,
                        )
                    }
                    StatusChip(row.kind)
                }
            }
        }
        item {
            PagedFooter(
                state.paged, stringResource(R.string.exceptions_empty_title), stringResource(R.string.exceptions_empty_body),
                viewModel::reload, viewModel::loadMore,
            )
        }
    }
}

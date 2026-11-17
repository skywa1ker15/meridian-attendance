package za.co.meridiangroup.attendance.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import za.co.meridiangroup.attendance.core.ApiResult
import za.co.meridiangroup.attendance.core.JohannesburgTime
import za.co.meridiangroup.attendance.data.remote.dto.SessionDetailDto
import za.co.meridiangroup.attendance.data.remote.dto.SessionDto
import za.co.meridiangroup.attendance.data.repository.AttendanceRepository
import za.co.meridiangroup.attendance.ui.common.UiState

enum class HistoryRange { THIS_WEEK, THIS_MONTH, LAST_30, LAST_90 }
enum class HistoryStatusFilter(val wire: String?) { ALL(null), CLOSED("closed"), OPEN("open"), MISSING("missing_clock_out"), LATE(null) }

data class HistoryUiState(
    val range: HistoryRange = HistoryRange.LAST_30,
    val filter: HistoryStatusFilter = HistoryStatusFilter.ALL,
    val items: List<SessionDto> = emptyList(),
    val page: Int = 0,
    val hasMore: Boolean = true,
    val loading: Boolean = false,
    val error: String? = null,
) {
    val visible: List<SessionDto>
        get() = when (filter) {
            HistoryStatusFilter.ALL -> items
            HistoryStatusFilter.LATE -> items.filter { it.isLate }
            else -> items.filter { it.status == filter.wire }
        }
    val totalMinutes: Int get() = visible.sumOf { it.workedMinutes }
}

class HistoryViewModel(private val repo: AttendanceRepository, private val now: () -> Long) : ViewModel() {
    private val _state = MutableStateFlow(HistoryUiState())
    val state: StateFlow<HistoryUiState> = _state.asStateFlow()

    init { reload() }

    fun setRange(r: HistoryRange) { _state.update { it.copy(range = r) }; reload() }
    fun setFilter(f: HistoryStatusFilter) = _state.update { it.copy(filter = f) }

    fun reload() {
        _state.update { it.copy(items = emptyList(), page = 0, hasMore = true, error = null) }
        loadMore()
    }

    fun loadMore() {
        val s = _state.value
        if (s.loading || !s.hasMore) return
        _state.update { it.copy(loading = true, error = null) }
        val today = JohannesburgTime.workDateOf(now())
        val (from, to) = when (s.range) {
            HistoryRange.THIS_WEEK -> JohannesburgTime.weekBounds(today)
            HistoryRange.THIS_MONTH -> JohannesburgTime.monthBounds(today)
            HistoryRange.LAST_30 -> JohannesburgTime.daysAgo(today, 30) to today
            HistoryRange.LAST_90 -> JohannesburgTime.daysAgo(today, 90) to today
        }
        viewModelScope.launch {
            when (val r = repo.sessions(from, to, s.page + 1)) {
                is ApiResult.Success -> _state.update {
                    it.copy(loading = false, items = it.items + r.value.items, page = r.value.page, hasMore = r.value.hasMore)
                }
                else -> _state.update { it.copy(loading = false, error = r.userMessage()) }
            }
        }
    }
}

class SessionDetailViewModel(private val id: String, private val repo: AttendanceRepository) : ViewModel() {
    private val _state = MutableStateFlow<UiState<SessionDetailDto>>(UiState.Loading)
    val state: StateFlow<UiState<SessionDetailDto>> = _state.asStateFlow()

    init { load() }

    fun load() {
        viewModelScope.launch {
            val cached = repo.cachedSession(id)
            _state.value = if (cached != null) UiState.Content(SessionDetailDto(cached), refreshing = true) else UiState.Loading
            when (val r = repo.sessionDetail(id)) {
                is ApiResult.Success -> _state.value = UiState.Content(r.value)
                else -> _state.value = if (cached != null) UiState.Content(SessionDetailDto(cached), message = r.userMessage()) else UiState.Error(r.userMessage() ?: "")
            }
        }
    }
}

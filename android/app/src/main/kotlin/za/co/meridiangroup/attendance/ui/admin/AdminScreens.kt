package za.co.meridiangroup.attendance.ui.admin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.FactCheck
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import za.co.meridiangroup.attendance.R
import za.co.meridiangroup.attendance.core.ApiResult
import za.co.meridiangroup.attendance.core.Geo
import za.co.meridiangroup.attendance.core.JohannesburgTime
import za.co.meridiangroup.attendance.core.StatusTone
import za.co.meridiangroup.attendance.core.StatusTones
import za.co.meridiangroup.attendance.core.TodayStatus
import za.co.meridiangroup.attendance.core.WorkedTime
import za.co.meridiangroup.attendance.data.remote.dto.EventDto
import za.co.meridiangroup.attendance.data.remote.dto.LiveAttendanceResponseDto
import za.co.meridiangroup.attendance.data.repository.AdminRepository
import za.co.meridiangroup.attendance.ui.common.UiState
import za.co.meridiangroup.attendance.ui.components.Banner
import za.co.meridiangroup.attendance.ui.components.EmptyState
import za.co.meridiangroup.attendance.ui.components.ErrorState
import za.co.meridiangroup.attendance.ui.components.GlassCard
import za.co.meridiangroup.attendance.ui.components.KeyValueRow
import za.co.meridiangroup.attendance.ui.components.LoadingState
import za.co.meridiangroup.attendance.ui.components.PillButton
import za.co.meridiangroup.attendance.ui.components.PillStyle
import za.co.meridiangroup.attendance.ui.components.StatTile
import za.co.meridiangroup.attendance.ui.components.StatusChip
import za.co.meridiangroup.attendance.ui.dashboard.statusLabel
import za.co.meridiangroup.attendance.ui.theme.MeridianTheme
import za.co.meridiangroup.attendance.ui.theme.Spacing

/** Admin hub: entries are shown only for the permissions the server granted. */
@Composable
fun AdminHomeScreen(canReview: Boolean, canViewLive: Boolean, onReview: () -> Unit, onLive: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(Spacing.l), verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
        Text(stringResource(R.string.admin_title), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(R.string.admin_body), style = MaterialTheme.typography.bodyMedium, color = MeridianTheme.extended.mutedText)
        if (canReview) GlassCard(onClick = onReview, modifier = Modifier.testTag("admin_review")) {
            Text(stringResource(R.string.admin_review_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.admin_review_body), style = MaterialTheme.typography.bodySmall)
        }
        if (canViewLive) GlassCard(onClick = onLive, modifier = Modifier.testTag("admin_live")) {
            Text(stringResource(R.string.admin_live_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.admin_live_body), style = MaterialTheme.typography.bodySmall)
        }
        if (!canReview && !canViewLive) EmptyState(stringResource(R.string.admin_no_access_title), stringResource(R.string.admin_no_access_body))
    }
}


data class ReviewUiState(
    val status: String = "under_review",
    val items: List<EventDto> = emptyList(),
    val page: Int = 0,
    val hasMore: Boolean = true,
    val loading: Boolean = false,
    val error: String? = null,
    val actingId: String? = null,
    val message: String? = null,
)

class AdminReviewViewModel(private val repo: AdminRepository) : ViewModel() {
    val state = MutableStateFlow(ReviewUiState())

    init { reload() }

    fun setStatus(s: String) { state.update { it.copy(status = s) }; reload() }

    fun reload() { state.update { it.copy(items = emptyList(), page = 0, hasMore = true, error = null) }; loadMore() }

    fun loadMore() {
        val s = state.value
        if (s.loading || !s.hasMore) return
        state.update { it.copy(loading = true) }
        viewModelScope.launch {
            when (val r = repo.reviewQueue(s.status, s.page + 1)) {
                is ApiResult.Success -> state.update { it.copy(loading = false, items = it.items + r.value.items, page = r.value.page, hasMore = r.value.hasMore) }
                else -> state.update { it.copy(loading = false, error = r.userMessage()) }
            }
        }
    }

    fun decide(id: String, approve: Boolean, note: String) {
        state.update { it.copy(actingId = id, message = null) }
        viewModelScope.launch {
            val r = if (approve) repo.approve(id, note) else repo.reject(id, note)
            state.update { st ->
                if (r is ApiResult.Success) st.copy(actingId = null, items = st.items.filterNot { it.id == id }, message = if (approve) "Event approved." else "Event rejected.")
                else st.copy(actingId = null, message = r.userMessage())
            }
        }
    }
}

@Composable
fun AdminReviewScreen(viewModel: AdminReviewViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var deciding by remember { mutableStateOf<Pair<EventDto, Boolean>?>(null) }
    var note by remember { mutableStateOf("") }
    LazyColumn(Modifier.fillMaxSize().testTag("admin_review_list"), contentPadding = PaddingValues(Spacing.l), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
        item { Text(stringResource(R.string.admin_review_title), style = MaterialTheme.typography.headlineSmall) }
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                items(listOf("under_review", "pending")) { st ->
                    FilterChip(selected = state.status == st, onClick = { viewModel.setStatus(st) }, label = { Text(StatusTones.label(st)) })
                }
            }
        }
        state.message?.let { item { Banner(it, "", StatusTone.NEUTRAL) } }
        items(state.items, key = { it.id }) { e ->
            GlassCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(e.employee?.fullName ?: "—", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    StatusChip(e.status)
                }
                KeyValueRow(stringResource(R.string.admin_event), "${StatusTones.label(e.eventType)} · ${StatusTones.label(e.source)}")
                KeyValueRow(stringResource(R.string.confirm_detected), JohannesburgTime.dateTime(e.detectedAt))
                KeyValueRow(stringResource(R.string.confirm_distance), Geo.formatDistance(e.distanceFromWorkplaceM))
                KeyValueRow(stringResource(R.string.admin_score), e.verificationScore?.let { "%.2f".format(it) } ?: "—")
                e.reviewReason?.let { KeyValueRow(stringResource(R.string.admin_reason), StatusTones.label(it)) }
                e.verification?.notes?.takeIf { it.isNotEmpty() }?.let { Text(it.joinToString(" · ") { n -> StatusTones.label(n) }, style = MaterialTheme.typography.bodySmall) }
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                    PillButton(stringResource(R.string.admin_approve), { note = ""; deciding = e to true }, style = PillStyle.SUCCESS, loading = state.actingId == e.id)
                    PillButton(stringResource(R.string.admin_reject), { note = ""; deciding = e to false }, style = PillStyle.DANGER, enabled = state.actingId != e.id)
                }
            }
        }
        when {
            state.error != null && state.items.isEmpty() -> item { ErrorState(state.error.orEmpty(), viewModel::reload) }
            state.loading -> item { LoadingState() }
            state.items.isEmpty() -> item { EmptyState(stringResource(R.string.admin_review_empty_title), stringResource(R.string.admin_review_empty_body), icon = Icons.AutoMirrored.Outlined.FactCheck) }
            state.hasMore -> item { PillButton(stringResource(R.string.action_load_more), viewModel::loadMore, Modifier.fillMaxWidth(), style = PillStyle.GHOST) }
        }
    }
    deciding?.let { (event, approve) ->
        AlertDialog(
            onDismissRequest = { deciding = null },
            title = { Text(stringResource(if (approve) R.string.admin_approve_title else R.string.admin_reject_title)) },
            text = {
                OutlinedTextField(note, { note = it }, label = { Text(stringResource(R.string.admin_note)) }, minLines = 2,
                    supportingText = { Text(stringResource(R.string.admin_note_hint)) })
            },
            confirmButton = {
                TextButton(onClick = { viewModel.decide(event.id, approve, note); deciding = null }, enabled = note.trim().length >= 3) {
                    Text(stringResource(if (approve) R.string.admin_approve else R.string.admin_reject))
                }
            },
            dismissButton = { TextButton(onClick = { deciding = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

class LiveAttendanceViewModel(private val repo: AdminRepository) : ViewModel() {
    val state = MutableStateFlow<UiState<LiveAttendanceResponseDto>>(UiState.Loading)
    init { load() }
    fun load() {
        viewModelScope.launch {
            state.value = when (val r = repo.live()) {
                is ApiResult.Success -> UiState.Content(r.value)
                else -> UiState.Error(r.userMessage().orEmpty())
            }
        }
    }
}

@Composable
fun LiveAttendanceScreen(viewModel: LiveAttendanceViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    when (val s = state) {
        UiState.Loading -> LoadingState(Modifier.fillMaxSize())
        is UiState.Error -> ErrorState(s.message, viewModel::load)
        is UiState.Content -> {
            val items = s.value.items
            val counts = items.groupingBy { TodayStatus.fromWire(it.status) }.eachCount()
            LazyColumn(Modifier.fillMaxSize().testTag("admin_live_list"), contentPadding = PaddingValues(Spacing.l), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                item {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.admin_live_title), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                        PillButton(stringResource(R.string.action_refresh), viewModel::load, style = PillStyle.GHOST)
                    }
                    Text(JohannesburgTime.workDateLong(s.value.workDate), color = MeridianTheme.extended.mutedText)
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                        StatTile(stringResource(R.string.today_clocked_in), (counts[TodayStatus.CLOCKED_IN] ?: 0).toString(), Modifier.weight(1f), Icons.Outlined.Groups)
                        StatTile(stringResource(R.string.admin_pending), ((counts[TodayStatus.PENDING_ARRIVAL] ?: 0) + (counts[TodayStatus.PENDING_DEPARTURE] ?: 0)).toString(), Modifier.weight(1f))
                        StatTile(stringResource(R.string.today_not_started), (counts[TodayStatus.NOT_STARTED] ?: 0).toString(), Modifier.weight(1f))
                    }
                }
                if (items.isEmpty()) item { EmptyState(stringResource(R.string.admin_live_empty_title), stringResource(R.string.admin_live_empty_body)) }
                items(items, key = { it.employee.id }) { row ->
                    val st = TodayStatus.fromWire(row.status)
                    GlassCard(contentPadding = PaddingValues(Spacing.m)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(row.employee.fullName, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    listOfNotNull(row.employee.employeeNumber, row.employee.department?.name).joinToString(" · ") +
                                        " · ${row.clockInLocal ?: "—"}–${row.clockOutLocal ?: "—"} · ${WorkedTime.format(row.workedMinutes)}",
                                    style = MaterialTheme.typography.bodySmall, color = MeridianTheme.extended.mutedText,
                                )
                            }
                            if (row.isLate) StatusChip("late", label = stringResource(R.string.status_late))
                            StatusChip(st.wire, label = statusLabel(st), tone = StatusTones.forToday(st))
                        }
                    }
                }
            }
        }
    }
}

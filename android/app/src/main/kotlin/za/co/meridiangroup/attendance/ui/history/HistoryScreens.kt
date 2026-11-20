package za.co.meridiangroup.attendance.ui.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.EventBusy
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import za.co.meridiangroup.attendance.R
import za.co.meridiangroup.attendance.core.EventType
import za.co.meridiangroup.attendance.core.Geo
import za.co.meridiangroup.attendance.core.JohannesburgTime
import za.co.meridiangroup.attendance.core.StatusTones
import za.co.meridiangroup.attendance.core.WorkedTime
import za.co.meridiangroup.attendance.data.remote.dto.EventDto
import za.co.meridiangroup.attendance.data.remote.dto.SessionDetailDto
import za.co.meridiangroup.attendance.ui.common.UiState
import za.co.meridiangroup.attendance.ui.components.Banner
import za.co.meridiangroup.attendance.ui.components.EmptyState
import za.co.meridiangroup.attendance.ui.components.ErrorState
import za.co.meridiangroup.attendance.ui.components.GlassCard
import za.co.meridiangroup.attendance.ui.components.KeyValueRow
import za.co.meridiangroup.attendance.ui.components.LoadingState
import za.co.meridiangroup.attendance.ui.components.PillButton
import za.co.meridiangroup.attendance.ui.components.PillStyle
import za.co.meridiangroup.attendance.ui.components.SectionHeader
import za.co.meridiangroup.attendance.ui.components.StatusChip
import za.co.meridiangroup.attendance.ui.dashboard.SessionRow
import za.co.meridiangroup.attendance.ui.theme.MeridianTheme
import za.co.meridiangroup.attendance.ui.theme.Spacing

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(viewModel: HistoryViewModel, onOpenSession: (String) -> Unit, onRequestCorrection: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    PullToRefreshBox(isRefreshing = state.loading, onRefresh = viewModel::reload, modifier = Modifier.fillMaxSize()) {
    LazyColumn(Modifier.fillMaxSize().testTag("history"), contentPadding = PaddingValues(Spacing.l), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
        item { Text(stringResource(R.string.history_title), style = MaterialTheme.typography.headlineSmall) }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                    items(HistoryRange.entries) { r ->
                        FilterChip(selected = state.range == r, onClick = { viewModel.setRange(r) }, label = { Text(rangeLabel(r)) })
                    }
                }
                LazyRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                    items(HistoryStatusFilter.entries) { f ->
                        FilterChip(selected = state.filter == f, onClick = { viewModel.setFilter(f) }, label = { Text(filterLabel(f)) })
                    }
                }
            }
        }
        item {
            Text(
                stringResource(R.string.history_total, state.visible.size, WorkedTime.format(state.totalMinutes)),
                style = MaterialTheme.typography.bodySmall, color = MeridianTheme.extended.mutedText,
            )
        }
        items(state.visible, key = { it.id }) { s -> SessionRow(s) { onOpenSession(s.id) } }
        when {
            state.error != null && state.items.isEmpty() -> item { ErrorState(state.error.orEmpty(), viewModel::reload) }
            state.error != null -> item { Banner(stringResource(R.string.banner_refresh_failed), state.error.orEmpty(), za.co.meridiangroup.attendance.core.StatusTone.NEUTRAL, actionLabel = stringResource(R.string.action_retry), onAction = viewModel::loadMore) }
            state.loading -> item { LoadingState() }
            state.visible.isEmpty() -> item {
                EmptyState(stringResource(R.string.history_empty_title), stringResource(R.string.history_empty_body), icon = Icons.Outlined.EventBusy) {
                    PillButton(stringResource(R.string.requests_new), onRequestCorrection, style = PillStyle.OUTLINE)
                }
            }
            state.hasMore -> item { PillButton(stringResource(R.string.action_load_more), viewModel::loadMore, Modifier.fillMaxWidth(), style = PillStyle.GHOST) }
        }
    }
    } // PullToRefreshBox
}

@Composable
private fun rangeLabel(r: HistoryRange) = stringResource(
    when (r) {
        HistoryRange.THIS_WEEK -> R.string.range_this_week
        HistoryRange.THIS_MONTH -> R.string.range_this_month
        HistoryRange.LAST_30 -> R.string.range_last_30
        HistoryRange.LAST_90 -> R.string.range_last_90
    },
)

@Composable
private fun filterLabel(f: HistoryStatusFilter) = stringResource(
    when (f) {
        HistoryStatusFilter.ALL -> R.string.filter_all
        HistoryStatusFilter.CLOSED -> R.string.filter_closed
        HistoryStatusFilter.OPEN -> R.string.filter_open
        HistoryStatusFilter.MISSING -> R.string.filter_missing
        HistoryStatusFilter.LATE -> R.string.filter_late
    },
)

@Composable
fun SessionDetailScreen(viewModel: SessionDetailViewModel, onRequestCorrection: (sessionId: String, workDate: String) -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    when (val s = state) {
        UiState.Loading -> LoadingState(Modifier.fillMaxSize())
        is UiState.Error -> ErrorState(s.message, viewModel::load)
        is UiState.Content -> DetailContent(s.value, s.message, onRequestCorrection)
    }
}

@Composable
private fun DetailContent(detail: SessionDetailDto, message: String?, onRequestCorrection: (String, String) -> Unit) {
    val session = detail.session
    LazyColumn(Modifier.fillMaxSize().testTag("session_detail"), contentPadding = PaddingValues(Spacing.l), verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
        item {
            Text(JohannesburgTime.workDateLong(session.workDate), style = MaterialTheme.typography.headlineSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                StatusChip(session.status)
                if (session.isLate) StatusChip("late", label = stringResource(R.string.status_late_minutes, session.lateMinutes))
                if (session.isManual) StatusChip("manual", label = stringResource(R.string.status_manual))
                if (session.corrected) StatusChip("corrected", label = stringResource(R.string.status_corrected))
            }
        }
        message?.let { item { Banner(stringResource(R.string.banner_refresh_failed), it, za.co.meridiangroup.attendance.core.StatusTone.NEUTRAL) } }
        item {
            GlassCard {
                KeyValueRow(stringResource(R.string.confirm_workplace), session.workplaceName ?: "—")
                KeyValueRow(stringResource(R.string.dashboard_clock_in), JohannesburgTime.time(session.clockInAt))
                KeyValueRow(stringResource(R.string.dashboard_clock_out), JohannesburgTime.time(session.clockOutAt))
                KeyValueRow(stringResource(R.string.detail_worked), WorkedTime.format(session.workedMinutes))
                KeyValueRow(stringResource(R.string.detail_break), WorkedTime.format(session.breakMinutes))
                KeyValueRow(stringResource(R.string.detail_overtime), WorkedTime.format(session.overtimeMinutes))
                session.correctionNote?.let { KeyValueRow(stringResource(R.string.detail_correction_note), it) }
                session.notes?.let { KeyValueRow(stringResource(R.string.detail_notes), it) }
            }
        }
        item { SectionHeader(stringResource(R.string.detail_timeline)) }
        if (detail.events.isEmpty()) {
            item { Text(stringResource(R.string.detail_no_events), color = MeridianTheme.extended.mutedText) }
        } else {
            items(detail.events.sortedBy { it.detectedAt ?: it.createdAt ?: "" }, key = { it.id }) { TimelineItem(it) }
        }
        item {
            PillButton(stringResource(R.string.detail_request_correction), { onRequestCorrection(session.id, session.workDate) }, Modifier.fillMaxWidth(), style = PillStyle.OUTLINE)
        }
    }
}

@Composable
private fun TimelineItem(e: EventDto) {
    val tone = StatusTones.forStatus(e.status)
    Row(verticalAlignment = Alignment.Top) {
        Box(Modifier.padding(top = 6.dp).size(12.dp).clip(CircleShape).background(MeridianTheme.extended.accent(tone)))
        Spacer(Modifier.width(Spacing.m))
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(if (EventType.fromWire(e.eventType) == EventType.ARRIVAL) R.string.event_arrival else R.string.event_departure) +
                    " · " + StatusTones.label(e.source),
                style = MaterialTheme.typography.titleSmall,
            )
            Text(stringResource(R.string.event_detected_line, JohannesburgTime.time(e.detectedAt), Geo.formatDistance(e.distanceFromWorkplaceM)), style = MaterialTheme.typography.bodySmall)
            e.officialAt?.let { Text(stringResource(R.string.event_official_line, JohannesburgTime.time(it)), style = MaterialTheme.typography.bodySmall) }
            e.reviewNote?.let { Text(stringResource(R.string.event_review_note, it), style = MaterialTheme.typography.bodySmall, color = MeridianTheme.extended.mutedText) }
        }
        StatusChip(e.status)
    }
}

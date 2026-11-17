package za.co.meridiangroup.attendance.ui.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AdminPanelSettings
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.DateRange
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.LocationOff
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Badge
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import za.co.meridiangroup.attendance.R
import za.co.meridiangroup.attendance.core.EventType
import za.co.meridiangroup.attendance.core.Geo
import za.co.meridiangroup.attendance.core.JohannesburgTime
import za.co.meridiangroup.attendance.core.SessionStatus
import za.co.meridiangroup.attendance.core.StatusTone
import za.co.meridiangroup.attendance.core.StatusTones
import za.co.meridiangroup.attendance.core.TodayStatus
import za.co.meridiangroup.attendance.core.WorkedTime
import za.co.meridiangroup.attendance.data.local.db.EventEntity
import za.co.meridiangroup.attendance.data.remote.dto.SessionDto
import za.co.meridiangroup.attendance.ui.components.Banner
import za.co.meridiangroup.attendance.ui.components.GlassCard
import za.co.meridiangroup.attendance.ui.components.HoursRing
import za.co.meridiangroup.attendance.ui.components.KeyValueRow
import za.co.meridiangroup.attendance.ui.components.PillButton
import za.co.meridiangroup.attendance.ui.components.PillStyle
import za.co.meridiangroup.attendance.ui.components.SectionHeader
import za.co.meridiangroup.attendance.ui.components.StatTile
import za.co.meridiangroup.attendance.ui.components.StatusChip
import za.co.meridiangroup.attendance.ui.theme.MeridianTheme
import za.co.meridiangroup.attendance.ui.theme.Spacing

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    viewModel: DashboardViewModel,
    onOpenConfirmation: (String) -> Unit,
    onOpenSession: (String) -> Unit,
    onOpenHistory: () -> Unit,
    onOpenNotifications: () -> Unit,
    onOpenOnboarding: () -> Unit,
    onOpenAdmin: () -> Unit,
    onOpenLocationSettings: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val refreshing by viewModel.refreshing.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val permissions by viewModel.permissions.collectAsStateWithLifecycle()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }

    LifecycleResumeEffect(Unit) {
        viewModel.recheckPermissions()
        onPauseOrDispose { }
    }
    LaunchedEffect(Unit) { while (true) { delay(30_000); now = System.currentTimeMillis() } }

    PullToRefreshBox(isRefreshing = refreshing, onRefresh = viewModel::refresh, modifier = Modifier.fillMaxSize().testTag("dashboard")) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(Spacing.l),
            verticalArrangement = Arrangement.spacedBy(Spacing.m),
        ) {
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.dashboard_greeting, state.user?.firstName?.ifBlank { null } ?: stringResource(R.string.there)),
                            style = MaterialTheme.typography.headlineSmall,
                        )
                        Text(
                            listOfNotNull(state.user?.employeeNumber, state.user?.department?.name).joinToString(" · "),
                            style = MaterialTheme.typography.bodyMedium, color = MeridianTheme.extended.mutedText,
                        )
                    }
                    if (state.hasAdmin) {
                        IconButton(onClick = onOpenAdmin) { Icon(Icons.Outlined.AdminPanelSettings, stringResource(R.string.nav_admin)) }
                    }
                    IconButton(onClick = onOpenNotifications) {
                        BadgedBox(badge = { if (state.unread > 0) Badge { Text(state.unread.coerceAtMost(99).toString()) } }) {
                            Icon(Icons.Outlined.NotificationsActive, stringResource(R.string.nav_notifications))
                        }
                    }
                }
                Spacer(Modifier.padding(top = Spacing.xs))
                AssistChip(
                    onClick = viewModel::syncNow,
                    label = { Text(state.sync.label(now)) },
                    leadingIcon = { Icon(Icons.Outlined.Sync, null, tint = MeridianTheme.extended.accent(state.sync.tone)) },
                    colors = AssistChipDefaults.assistChipColors(containerColor = MeridianTheme.extended.container(state.sync.tone)),
                    modifier = Modifier.testTag("sync_chip"),
                )
            }

            // ---- Banners (most important first) ----
            if (state.deviceStatus == "revoked" || state.deviceStatus == "pending_approval") item {
                Banner(
                    stringResource(if (state.deviceStatus == "revoked") R.string.banner_device_revoked_title else R.string.banner_device_pending_title),
                    stringResource(if (state.deviceStatus == "revoked") R.string.banner_device_revoked_body else R.string.banner_device_pending_body),
                    StatusTone.ERROR, icon = Icons.Outlined.ErrorOutline,
                )
            }
            if (!permissions.locationServicesOn) item {
                Banner(stringResource(R.string.banner_location_off_title), stringResource(R.string.banner_location_off_body), StatusTone.WARNING,
                    icon = Icons.Outlined.LocationOff, actionLabel = stringResource(R.string.ob_turn_on_location), onAction = onOpenLocationSettings)
            }
            if (permissions.requiredOutstanding || !permissions.notificationsGranted) item {
                Banner(stringResource(R.string.banner_permissions_title), stringResource(R.string.banner_permissions_body), StatusTone.WARNING,
                    icon = Icons.Outlined.WarningAmber, actionLabel = stringResource(R.string.banner_permissions_action), onAction = onOpenOnboarding,
                    modifier = Modifier.testTag("banner_permissions"))
            }
            if (state.workplaceNotConfigured || state.noWorkplaces) item {
                Banner(stringResource(R.string.banner_workplace_title), stringResource(R.string.banner_workplace_body), StatusTone.NEUTRAL,
                    icon = Icons.Outlined.LocationOff, modifier = Modifier.testTag("banner_workplace"))
            }
            if (state.sync.dead > 0) item {
                Banner(stringResource(R.string.banner_failed_title), stringResource(R.string.banner_failed_body), StatusTone.ERROR,
                    actionLabel = stringResource(R.string.banner_failed_action), onAction = viewModel::acknowledgeFailedItems)
            }
            error?.let { msg -> item { Banner(stringResource(R.string.banner_refresh_failed), msg, StatusTone.NEUTRAL, actionLabel = stringResource(R.string.action_retry), onAction = viewModel::refresh) } }

            item { StatusHero(state, now) }

            if (state.awaiting.isNotEmpty()) {
                item { SectionHeader(stringResource(R.string.dashboard_pending_title)) }
                items(state.awaiting, key = { it.clientEventId }) { event -> PendingCard(event) { onOpenConfirmation(event.ref) } }
            }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.m)) {
                    StatTile(stringResource(R.string.dashboard_week), WorkedTime.format(state.today?.weekMinutes), Modifier.weight(1f), Icons.Outlined.DateRange)
                    StatTile(stringResource(R.string.dashboard_month), WorkedTime.format(state.today?.monthMinutes), Modifier.weight(1f), Icons.Outlined.CalendarMonth)
                }
            }

            item {
                SectionHeader(stringResource(R.string.dashboard_recent)) {
                    PillButton(stringResource(R.string.action_see_all), onOpenHistory, style = PillStyle.GHOST)
                }
            }
            if (state.recent.isEmpty()) {
                item { Text(stringResource(R.string.dashboard_no_recent), color = MeridianTheme.extended.mutedText, style = MaterialTheme.typography.bodyMedium) }
            } else {
                items(state.recent, key = { it.id }) { s -> SessionRow(s) { onOpenSession(s.id) } }
            }
        }
    }
}

@Composable
private fun StatusHero(state: DashboardUiState, now: Long) {
    val today = state.today
    val status = TodayStatus.fromWire(today?.status)
    val session = today?.session
    val live = WorkedTime.liveMinutes(
        clockInAt = JohannesburgTime.parse(session?.clockInAt), nowMillis = now, breakMinutes = 0,
        serverWorkedMinutes = today?.workedMinutesToday, isOpen = SessionStatus.fromWire(session?.status) == SessionStatus.OPEN,
    )
    GlassCard(Modifier.testTag("status_hero")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                Text(JohannesburgTime.workDateLong(today?.workDate ?: JohannesburgTime.workDateOf(now)), style = MaterialTheme.typography.labelLarge, color = MeridianTheme.extended.mutedText)
                StatusChip(status.wire, label = statusLabel(status), tone = StatusTones.forToday(status))
                KeyValueRow(stringResource(R.string.dashboard_clock_in), today?.clockInLocal ?: "—")
                KeyValueRow(stringResource(R.string.dashboard_clock_out), today?.clockOutLocal ?: "—")
                if (today?.lateToday == true) StatusChip("late", label = stringResource(R.string.status_late))
            }
            Spacer(Modifier.width(Spacing.l))
            HoursRing(
                progress = WorkedTime.ringProgress(live, state.standardDayMinutes),
                centerText = WorkedTime.format(live),
                caption = stringResource(R.string.dashboard_worked_today),
            )
        }
        if (today == null) {
            Text(stringResource(R.string.dashboard_no_data), style = MaterialTheme.typography.bodySmall, color = MeridianTheme.extended.mutedText)
        }
    }
}

@Composable
fun statusLabel(status: TodayStatus): String = stringResource(
    when (status) {
        TodayStatus.NOT_STARTED -> R.string.today_not_started
        TodayStatus.PENDING_ARRIVAL -> R.string.today_pending_arrival
        TodayStatus.CLOCKED_IN -> R.string.today_clocked_in
        TodayStatus.PENDING_DEPARTURE -> R.string.today_pending_departure
        TodayStatus.CLOCKED_OUT -> R.string.today_clocked_out
        TodayStatus.MISSING_CLOCK_OUT -> R.string.today_missing_clock_out
        TodayStatus.UNKNOWN -> R.string.today_unknown
    },
)

@Composable
private fun PendingCard(event: EventEntity, onClick: () -> Unit) {
    val arrival = EventType.fromWire(event.eventType) == EventType.ARRIVAL
    GlassCard(onClick = onClick, modifier = Modifier.testTag("pending_card")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(if (arrival) R.string.pending_arrival_title else R.string.pending_departure_title), style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(R.string.pending_detected_at, JohannesburgTime.time(event.detectedAt), event.workplaceName ?: "—", Geo.formatDistance(event.distanceM)),
                    style = MaterialTheme.typography.bodySmall, color = MeridianTheme.extended.mutedText,
                )
                if (event.serverId == null) StatusChip("queued", label = stringResource(R.string.status_saved_offline))
            }
            PillButton(stringResource(R.string.action_review), onClick, style = if (arrival) PillStyle.SUCCESS else PillStyle.PRIMARY)
        }
    }
}

@Composable
fun SessionRow(s: SessionDto, onClick: () -> Unit) {
    GlassCard(onClick = onClick, contentPadding = androidx.compose.foundation.layout.PaddingValues(Spacing.m)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(JohannesburgTime.workDate(s.workDate), style = MaterialTheme.typography.titleSmall)
                Text(
                    "${JohannesburgTime.time(s.clockInAt)} – ${JohannesburgTime.time(s.clockOutAt)} · ${WorkedTime.format(s.workedMinutes)}",
                    style = MaterialTheme.typography.bodySmall, color = MeridianTheme.extended.mutedText,
                )
            }
            if (s.isLate) { StatusChip("late", label = stringResource(R.string.status_late)); Spacer(Modifier.width(Spacing.xs)) }
            StatusChip(s.status)
        }
    }
}

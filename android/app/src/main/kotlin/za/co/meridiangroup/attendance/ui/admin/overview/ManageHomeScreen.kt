package za.co.meridiangroup.attendance.ui.admin.overview

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.FactCheck
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.PersonOff
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import za.co.meridiangroup.attendance.AppGraph
import za.co.meridiangroup.attendance.R
import za.co.meridiangroup.attendance.core.ApiResult
import za.co.meridiangroup.attendance.core.JohannesburgTime
import za.co.meridiangroup.attendance.core.ServerPermissions
import za.co.meridiangroup.attendance.core.StatusTone
import za.co.meridiangroup.attendance.data.remote.dto.OverviewDto
import za.co.meridiangroup.attendance.ui.admin.AdminSectionCard
import za.co.meridiangroup.attendance.ui.admin.AdminTitle
import za.co.meridiangroup.attendance.ui.admin.DateField
import za.co.meridiangroup.attendance.ui.admin.NoPermission
import za.co.meridiangroup.attendance.ui.components.Banner
import za.co.meridiangroup.attendance.ui.components.GlassCard
import za.co.meridiangroup.attendance.ui.components.KeyValueRow
import za.co.meridiangroup.attendance.ui.components.LoadingState
import za.co.meridiangroup.attendance.ui.components.PillButton
import za.co.meridiangroup.attendance.ui.components.PillStyle
import za.co.meridiangroup.attendance.ui.components.SectionHeader
import za.co.meridiangroup.attendance.ui.components.StatTile
import za.co.meridiangroup.attendance.ui.theme.MeridianTheme
import za.co.meridiangroup.attendance.ui.theme.Spacing

/** Where the Manage home can navigate to (each entry is permission-gated by the caller). */
data class ManageActions(
    val onEmployees: () -> Unit,
    val onLive: () -> Unit,
    val onRecords: () -> Unit,
    val onReview: () -> Unit,
    val onExceptions: () -> Unit,
    val onReports: () -> Unit,
    val onWorkplaces: () -> Unit,
    val onPolicy: () -> Unit,
    val onDepartments: () -> Unit,
    val onDevices: () -> Unit,
    val onRoles: () -> Unit,
    val onAudit: () -> Unit,
    val onBroadcast: () -> Unit,
    val onNotifications: () -> Unit,
)

data class ManageHomeUiState(
    val workDate: String? = null,
    val overview: OverviewDto? = null,
    val loading: Boolean = true,
    val error: String? = null,
    val offerWorkplaceSetup: Boolean = false,
    val workplacesUnconfirmed: Boolean = false,
)

class ManageHomeViewModel(private val graph: AppGraph) : ViewModel() {
    private val _state = MutableStateFlow(ManageHomeUiState())
    val state: StateFlow<ManageHomeUiState> = _state.asStateFlow()

    init {
        load(null)
        viewModelScope.launch {
            graph.offerWorkplaceSetup.collect { offer -> _state.update { it.copy(offerWorkplaceSetup = offer) } }
        }
    }

    fun load(date: String?) {
        _state.update { it.copy(loading = true, error = null, workDate = date ?: it.workDate) }
        viewModelScope.launch {
            when (val r = graph.adminRepository.overview(date)) {
                is ApiResult.Success -> _state.update { it.copy(loading = false, overview = r.value, workDate = r.value.workDate, error = null) }
                else -> _state.update { it.copy(loading = false, error = r.userMessage()) }
            }
            // Warn while a workplace still has unconfirmed coordinates (geofencing cannot work).
            val workplaces = graph.adminRepository.workplaces().getOrNull()?.items
            if (workplaces != null) {
                _state.update { it.copy(workplacesUnconfirmed = workplaces.any { w -> w.active && w.coordinatesConfirmed != true }) }
            }
        }
    }

    fun dismissSetupOffer() {
        graph.offerWorkplaceSetup.value = false
    }
}

/** Manage home: live numbers for a chosen day plus every section the server lets this user use. */
@Composable
fun ManageHomeScreen(viewModel: ManageHomeViewModel, permissions: List<String>, unread: Int, actions: ManageActions) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val canRead = ServerPermissions.canRead(permissions)
    val stats = state.overview?.stats

    LazyColumn(
        Modifier.fillMaxSize().testTag("manage_home"),
        contentPadding = PaddingValues(Spacing.l),
        verticalArrangement = Arrangement.spacedBy(Spacing.m),
    ) {
        item {
            AdminTitle(
                stringResource(R.string.manage_title),
                JohannesburgTime.workDateLong(state.workDate),
                action = {
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        IconButton(onClick = actions.onNotifications, modifier = Modifier.testTag("manage_notifications")) {
                            BadgedBox(badge = { if (unread > 0) Badge { Text(unread.coerceAtMost(99).toString()) } }) {
                                Icon(Icons.Outlined.NotificationsActive, contentDescription = stringResource(R.string.nav_notifications))
                            }
                        }
                        PillButton(stringResource(R.string.action_refresh), { viewModel.load(state.workDate) }, style = PillStyle.GHOST)
                    }
                },
            )
        }
        if (state.offerWorkplaceSetup) {
            item {
                Banner(
                    stringResource(R.string.manage_setup_offer_title), stringResource(R.string.manage_setup_offer_body), StatusTone.WARNING,
                    modifier = Modifier.testTag("workplace_offer"),
                    actionLabel = stringResource(R.string.manage_setup_offer_action),
                    onAction = { viewModel.dismissSetupOffer(); actions.onWorkplaces() },
                )
            }
        } else if (state.workplacesUnconfirmed && ServerPermissions.canWriteConfig(permissions)) {
            item {
                Banner(
                    stringResource(R.string.manage_unconfirmed_title), stringResource(R.string.manage_unconfirmed_body), StatusTone.WARNING,
                    actionLabel = stringResource(R.string.manage_unconfirmed_action), onAction = actions.onWorkplaces,
                )
            }
        }

        if (canRead) {
            item {
                DateField(stringResource(R.string.manage_date), state.workDate, { viewModel.load(it) }, Modifier.fillMaxWidth(), tag = "overview_date")
            }
            when {
                state.loading && stats == null -> item { LoadingState() }
                state.error != null && stats == null -> item {
                    Banner(stringResource(R.string.manage_overview_failed), state.error.orEmpty(), StatusTone.ERROR,
                        actionLabel = stringResource(R.string.action_retry), onAction = { viewModel.load(state.workDate) })
                }
                stats != null -> {
                    item {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                            StatTile(stringResource(R.string.manage_total), stats.totalEmployees.toString(), Modifier.weight(1f).testTag("tile_total"), Icons.Outlined.Groups)
                            StatTile(stringResource(R.string.today_clocked_in), stats.clockedIn.toString(), Modifier.weight(1f).testTag("tile_clocked_in"))
                            StatTile(stringResource(R.string.manage_absent), stats.absent.toString(), Modifier.weight(1f).testTag("tile_absent"), Icons.Outlined.PersonOff)
                        }
                    }
                    item {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                            ClickableTile(stringResource(R.string.manage_late), stats.lateArrivals, Modifier.weight(1f), "tile_late", Icons.Outlined.Schedule, actions.onExceptions)
                            ClickableTile(stringResource(R.string.manage_pending_confirmations), stats.pendingConfirmations, Modifier.weight(1f), "tile_pending", Icons.AutoMirrored.Outlined.FactCheck, actions.onReview)
                            ClickableTile(stringResource(R.string.manage_under_review), stats.underReview, Modifier.weight(1f), "tile_review", null, actions.onReview)
                        }
                    }
                    item {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                            ClickableTile(stringResource(R.string.manage_exceptions), stats.exceptions, Modifier.weight(1f), "tile_exceptions", null, actions.onExceptions)
                            ClickableTile(stringResource(R.string.manage_pending_requests), stats.pendingRequests, Modifier.weight(1f), "tile_requests", null, actions.onReview)
                            ClickableTile(stringResource(R.string.manage_pending_devices), stats.pendingDevices, Modifier.weight(1f), "tile_devices", null, actions.onDevices)
                        }
                    }
                    val breakdown = state.overview?.departmentBreakdown.orEmpty()
                    if (breakdown.isNotEmpty()) {
                        item {
                            GlassCard {
                                Text(stringResource(R.string.manage_departments_today), style = MaterialTheme.typography.titleSmall)
                                breakdown.forEach { KeyValueRow(it.department, "${it.present}/${it.total}") }
                            }
                        }
                    }
                    val activity = state.overview?.recentActivity.orEmpty()
                    if (activity.isNotEmpty()) {
                        item {
                            GlassCard {
                                Text(stringResource(R.string.manage_recent_activity), style = MaterialTheme.typography.titleSmall)
                                activity.take(8).forEach {
                                    KeyValueRow(JohannesburgTime.time(it.at), it.text)
                                }
                            }
                        }
                    }
                }
            }
        }

        item { SectionHeader(stringResource(R.string.manage_sections)) }
        if (canRead) item { AdminSectionCard(stringResource(R.string.manage_employees), stringResource(R.string.manage_employees_body), actions.onEmployees, "section_employees") }
        if (canRead) item { AdminSectionCard(stringResource(R.string.admin_live_title), stringResource(R.string.admin_live_body), actions.onLive, "section_live") }
        if (canRead) item { AdminSectionCard(stringResource(R.string.manage_records), stringResource(R.string.manage_records_body), actions.onRecords, "section_records") }
        if (ServerPermissions.canReview(permissions)) {
            item { AdminSectionCard(stringResource(R.string.manage_review), stringResource(R.string.manage_review_body), actions.onReview, "section_review") }
        }
        if (canRead) item { AdminSectionCard(stringResource(R.string.manage_exceptions), stringResource(R.string.manage_exceptions_body), actions.onExceptions, "section_exceptions") }
        if (ServerPermissions.canExport(permissions)) {
            item { AdminSectionCard(stringResource(R.string.manage_reports), stringResource(R.string.manage_reports_body), actions.onReports, "section_reports") }
        }
        if (ServerPermissions.canWriteConfig(permissions)) {
            item { AdminSectionCard(stringResource(R.string.manage_workplaces), stringResource(R.string.manage_workplaces_body), actions.onWorkplaces, "section_workplaces") }
            item { AdminSectionCard(stringResource(R.string.manage_policy), stringResource(R.string.manage_policy_body), actions.onPolicy, "section_policy") }
            item { AdminSectionCard(stringResource(R.string.manage_departments), stringResource(R.string.manage_departments_body), actions.onDepartments, "section_departments") }
        }
        if (canRead) item { AdminSectionCard(stringResource(R.string.manage_devices), stringResource(R.string.manage_devices_body), actions.onDevices, "section_devices") }
        if (canRead) item { AdminSectionCard(stringResource(R.string.manage_roles), stringResource(R.string.manage_roles_body), actions.onRoles, "section_roles") }
        if (canRead) item { AdminSectionCard(stringResource(R.string.manage_audit), stringResource(R.string.manage_audit_body), actions.onAudit, "section_audit") }
        if (ServerPermissions.canBroadcast(permissions)) {
            item { AdminSectionCard(stringResource(R.string.manage_broadcast), stringResource(R.string.manage_broadcast_body), actions.onBroadcast, "section_broadcast") }
        }
        if (!ServerPermissions.hasAdminSection(permissions)) item { NoPermission() }
        item { Text(stringResource(R.string.manage_audit_note), style = MaterialTheme.typography.bodySmall, color = MeridianTheme.extended.mutedText) }
    }
}

@Composable
private fun ClickableTile(
    label: String,
    value: Int,
    modifier: Modifier,
    tag: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector?,
    onClick: () -> Unit,
) {
    androidx.compose.foundation.layout.Box(modifier.testTag(tag)) {
        GlassCard(onClick = onClick) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MeridianTheme.extended.mutedText)
            Text(value.toString(), style = MaterialTheme.typography.headlineSmall)
            if (icon != null) androidx.compose.material3.Icon(icon, contentDescription = null, tint = MeridianTheme.extended.mutedText)
        }
    }
}

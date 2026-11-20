package za.co.meridiangroup.attendance.ui.requests

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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.EditCalendar
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import za.co.meridiangroup.attendance.R
import za.co.meridiangroup.attendance.core.JohannesburgTime
import za.co.meridiangroup.attendance.core.RequestStatus
import za.co.meridiangroup.attendance.core.RequestType
import za.co.meridiangroup.attendance.core.StatusTone
import za.co.meridiangroup.attendance.core.StatusTones
import za.co.meridiangroup.attendance.data.remote.dto.ManualRequestDto
import za.co.meridiangroup.attendance.ui.components.Banner
import za.co.meridiangroup.attendance.ui.components.EmptyState
import za.co.meridiangroup.attendance.ui.components.ErrorState
import za.co.meridiangroup.attendance.ui.components.GlassCard
import za.co.meridiangroup.attendance.ui.components.KeyValueRow
import za.co.meridiangroup.attendance.ui.components.LoadingState
import za.co.meridiangroup.attendance.ui.components.PillButton
import za.co.meridiangroup.attendance.ui.components.PillStyle
import za.co.meridiangroup.attendance.ui.components.StatusChip
import za.co.meridiangroup.attendance.ui.theme.MeridianTheme
import za.co.meridiangroup.attendance.ui.theme.Spacing

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RequestsScreen(viewModel: RequestsViewModel, onNewRequest: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var confirmCancel by remember { mutableStateOf<ManualRequestDto?>(null) }
    LaunchedEffect(Unit) { viewModel.reload() }
    Scaffold(
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = onNewRequest, icon = { Icon(Icons.Outlined.Add, null) }, text = { Text(stringResource(R.string.requests_new)) },
                modifier = Modifier.testTag("new_request"))
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        PullToRefreshBox(isRefreshing = state.loading, onRefresh = viewModel::reload, modifier = Modifier.fillMaxSize().padding(padding)) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(Spacing.l), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
            item { Text(stringResource(R.string.requests_title), style = MaterialTheme.typography.headlineSmall) }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                    val options = listOf(null) + RequestStatus.entries.filter { it != RequestStatus.UNKNOWN }.map { it.wire }
                    items(options) { st ->
                        FilterChip(selected = state.status == st, onClick = { viewModel.setStatus(st) },
                            label = { Text(st?.let { StatusTones.label(it) } ?: stringResource(R.string.filter_all)) })
                    }
                }
            }
            state.message?.let { msg -> item { Banner(msg, "", StatusTone.NEUTRAL, actionLabel = stringResource(R.string.action_ok), onAction = viewModel::clearMessage) } }
            items(state.items, key = { it.id }) { r -> RequestCard(r, cancelling = state.cancellingId == r.id) { confirmCancel = r } }
            when {
                state.error != null && state.items.isEmpty() -> item { ErrorState(state.error.orEmpty(), viewModel::reload) }
                state.loading -> item { LoadingState() }
                state.items.isEmpty() -> item { EmptyState(stringResource(R.string.requests_empty_title), stringResource(R.string.requests_empty_body)) }
                state.hasMore -> item { PillButton(stringResource(R.string.action_load_more), viewModel::loadMore, Modifier.fillMaxWidth(), style = PillStyle.GHOST) }
            }
        }
        } // PullToRefreshBox
    }
    confirmCancel?.let { r ->
        AlertDialog(
            onDismissRequest = { confirmCancel = null },
            title = { Text(stringResource(R.string.requests_cancel_title)) },
            text = { Text(stringResource(R.string.requests_cancel_body)) },
            confirmButton = { TextButton(onClick = { viewModel.cancel(r.id); confirmCancel = null }) { Text(stringResource(R.string.requests_cancel_confirm)) } },
            dismissButton = { TextButton(onClick = { confirmCancel = null }) { Text(stringResource(R.string.action_keep)) } },
        )
    }
}

@Composable
private fun RequestCard(r: ManualRequestDto, cancelling: Boolean, onCancel: () -> Unit) {
    GlassCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(StatusTones.label(r.type), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            StatusChip(r.status)
        }
        r.workDate?.let { KeyValueRow(stringResource(R.string.request_date), JohannesburgTime.workDate(it)) }
        r.requestedClockIn?.let { KeyValueRow(stringResource(R.string.dashboard_clock_in), JohannesburgTime.time(it)) }
        r.requestedClockOut?.let { KeyValueRow(stringResource(R.string.dashboard_clock_out), JohannesburgTime.time(it)) }
        r.field?.let { KeyValueRow(StatusTones.label(it), r.requestedValue ?: "—") }
        Text(r.reason, style = MaterialTheme.typography.bodySmall, color = MeridianTheme.extended.mutedText)
        r.reviewNote?.let { Text(stringResource(R.string.event_review_note, it), style = MaterialTheme.typography.bodySmall) }
        if (RequestStatus.fromWire(r.status) == RequestStatus.PENDING) {
            PillButton(stringResource(R.string.requests_cancel), onCancel, Modifier.align(Alignment.End), style = PillStyle.GHOST, loading = cancelling)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewRequestScreen(viewModel: NewRequestViewModel, onDone: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var datePicker by remember { mutableStateOf(false) }
    var timePickerFor by remember { mutableStateOf<String?>(null) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Spacing.l).testTag("new_request_form"),
        verticalArrangement = Arrangement.spacedBy(Spacing.m),
    ) {
        Text(stringResource(if (viewModel.profileMode) R.string.profile_correction_title else R.string.requests_new_title), style = MaterialTheme.typography.headlineSmall)
        if (state.done) {
            Banner(stringResource(R.string.requests_sent_title), state.result.orEmpty(), StatusTone.SUCCESS)
            PillButton(stringResource(R.string.action_done), onDone, Modifier.fillMaxWidth())
            return@Column
        }
        if (viewModel.profileMode) {
            Text(stringResource(R.string.profile_correction_body), style = MaterialTheme.typography.bodyMedium)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                items(listOf("first_name", "last_name", "email", "phone", "department")) { f ->
                    FilterChip(selected = state.profileField == f, onClick = { viewModel.setProfileField(f) }, label = { Text(StatusTones.label(f)) })
                }
            }
            OutlinedTextField(
                value = state.profileValue, onValueChange = viewModel::setProfileValue, label = { Text(stringResource(R.string.profile_correction_value)) },
                isError = "requested_value" in state.errors, supportingText = state.errors["requested_value"]?.let { { Text(it) } },
                modifier = Modifier.fillMaxWidth(), singleLine = true,
            )
        } else {
            Text(stringResource(R.string.request_type), style = MaterialTheme.typography.labelLarge)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                items(RequestType.attendanceTypes) { t ->
                    FilterChip(selected = state.type == t, onClick = { viewModel.setType(t) }, label = { Text(StatusTones.label(t.wire)) }, modifier = Modifier.testTag("type_${t.wire}"))
                }
            }
            PickerField(stringResource(R.string.request_date), state.workDate?.let { JohannesburgTime.workDateLong(it) }, state.errors["work_date"], Icons.Outlined.EditCalendar) { datePicker = true }
            if (state.type.needsClockIn) {
                PickerField(stringResource(R.string.request_clock_in), state.clockInTime, state.errors["requested_clock_in"], Icons.Outlined.Schedule) { timePickerFor = "in" }
            }
            if (state.type.needsClockOut) {
                PickerField(stringResource(R.string.request_clock_out), state.clockOutTime, state.errors["requested_clock_out"], Icons.Outlined.Schedule) { timePickerFor = "out" }
            }
            Text(stringResource(R.string.request_times_note), style = MaterialTheme.typography.bodySmall, color = MeridianTheme.extended.mutedText)
        }
        OutlinedTextField(
            value = state.reason, onValueChange = viewModel::setReason, label = { Text(stringResource(R.string.request_reason)) },
            isError = "reason" in state.errors, supportingText = { Text(state.errors["reason"] ?: stringResource(R.string.request_reason_hint)) },
            modifier = Modifier.fillMaxWidth().testTag("request_reason"), minLines = 3,
        )
        if (state.result != null) Banner(stringResource(R.string.requests_failed), state.result.orEmpty(), StatusTone.ERROR)
        PillButton(stringResource(R.string.requests_submit), viewModel::submit, Modifier.fillMaxWidth().testTag("request_submit"), loading = state.submitting)
    }

    if (datePicker) {
        val today = LocalDate.parse(JohannesburgTime.workDateOf(System.currentTimeMillis()))
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = state.workDate?.let { LocalDate.parse(it).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli() },
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long): Boolean =
                    !Instant.ofEpochMilli(utcTimeMillis).atOffset(ZoneOffset.UTC).toLocalDate().isAfter(today)
            },
        )
        DatePickerDialog(
            onDismissRequest = { datePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let { viewModel.setDate(Instant.ofEpochMilli(it).atOffset(ZoneOffset.UTC).toLocalDate().toString()) }
                    datePicker = false
                }) { Text(stringResource(R.string.action_ok)) }
            },
            dismissButton = { TextButton(onClick = { datePicker = false }) { Text(stringResource(R.string.action_cancel)) } },
        ) { DatePicker(state = pickerState) }
    }
    timePickerFor?.let { which ->
        val initial = (if (which == "in") state.clockInTime else state.clockOutTime) ?: if (which == "in") "08:00" else "17:00"
        val tp = rememberTimePickerState(initialHour = initial.substring(0, 2).toInt(), initialMinute = initial.substring(3, 5).toInt(), is24Hour = true)
        AlertDialog(
            onDismissRequest = { timePickerFor = null },
            title = { Text(stringResource(if (which == "in") R.string.request_clock_in else R.string.request_clock_out)) },
            text = { TimePicker(state = tp) },
            confirmButton = {
                TextButton(onClick = {
                    val v = "%02d:%02d".format(tp.hour, tp.minute)
                    if (which == "in") viewModel.setClockIn(v) else viewModel.setClockOut(v)
                    timePickerFor = null
                }) { Text(stringResource(R.string.action_ok)) }
            },
            dismissButton = { TextButton(onClick = { timePickerFor = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
private fun PickerField(label: String, value: String?, error: String?, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    GlassCard(onClick = onClick, contentPadding = PaddingValues(Spacing.m)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f).padding(start = Spacing.m)) {
                Text(label, style = MaterialTheme.typography.labelMedium, color = MeridianTheme.extended.mutedText)
                Text(value ?: stringResource(R.string.request_tap_to_choose), style = MaterialTheme.typography.bodyLarge)
            }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }
}

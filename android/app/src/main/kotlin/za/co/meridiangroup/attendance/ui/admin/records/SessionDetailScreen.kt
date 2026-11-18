package za.co.meridiangroup.attendance.ui.admin.records

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
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
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import za.co.meridiangroup.attendance.R
import za.co.meridiangroup.attendance.core.ApiResult
import za.co.meridiangroup.attendance.core.Geo
import za.co.meridiangroup.attendance.core.JohannesburgTime
import za.co.meridiangroup.attendance.core.ServerPermissions
import za.co.meridiangroup.attendance.core.StatusTones
import za.co.meridiangroup.attendance.core.WorkedTime
import za.co.meridiangroup.attendance.data.remote.dto.SessionDetailDto
import za.co.meridiangroup.attendance.data.repository.AdminRepository
import za.co.meridiangroup.attendance.ui.admin.AdminMessage
import za.co.meridiangroup.attendance.ui.admin.AdminTitle
import za.co.meridiangroup.attendance.ui.components.ErrorState
import za.co.meridiangroup.attendance.ui.components.GlassCard
import za.co.meridiangroup.attendance.ui.components.KeyValueRow
import za.co.meridiangroup.attendance.ui.components.LoadingState
import za.co.meridiangroup.attendance.ui.components.PillButton
import za.co.meridiangroup.attendance.ui.components.PillStyle
import za.co.meridiangroup.attendance.ui.components.SectionHeader
import za.co.meridiangroup.attendance.ui.components.StatusChip
import za.co.meridiangroup.attendance.ui.theme.MeridianTheme
import za.co.meridiangroup.attendance.ui.theme.Spacing

data class AdminSessionUiState(
    val detail: SessionDetailDto? = null,
    val loading: Boolean = true,
    val error: String? = null,
    val message: String? = null,
    val saving: Boolean = false,
)

class AdminSessionViewModel(private val repo: AdminRepository, private val sessionId: String) : ViewModel() {
    private val _state = MutableStateFlow(AdminSessionUiState())
    val state: StateFlow<AdminSessionUiState> = _state.asStateFlow()

    init { load() }

    fun load() {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            when (val r = repo.session(sessionId)) {
                is ApiResult.Success -> _state.update { it.copy(loading = false, detail = r.value) }
                else -> _state.update { it.copy(loading = false, error = r.userMessage()) }
            }
        }
    }

    fun correct(clockInAt: String?, clockOutAt: String?, note: String, onDone: () -> Unit) {
        _state.update { it.copy(saving = true, message = null) }
        viewModelScope.launch {
            val r = repo.correctSession(sessionId, clockInAt, clockOutAt, note)
            _state.update { it.copy(saving = false, message = if (r is ApiResult.Success) "Session corrected." else r.userMessage()) }
            if (r is ApiResult.Success) { onDone(); load() }
        }
    }

    fun clearMessage() = _state.update { it.copy(message = null) }
}

@Composable
fun AdminSessionScreen(viewModel: AdminSessionViewModel, permissions: List<String>) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var correcting by remember { mutableStateOf(false) }
    val detail = state.detail

    when {
        state.loading && detail == null -> LoadingState(Modifier.fillMaxSize())
        detail == null -> ErrorState(state.error.orEmpty(), viewModel::load)
        else -> {
            val session = detail.session
            LazyColumn(
                Modifier.fillMaxSize().testTag("admin_session"),
                contentPadding = PaddingValues(Spacing.l),
                verticalArrangement = Arrangement.spacedBy(Spacing.m),
            ) {
                item {
                    AdminTitle(
                        detail.employee?.fullName ?: session.employee?.fullName ?: stringResource(R.string.manage_records),
                        JohannesburgTime.workDateLong(session.workDate),
                    ) { StatusChip(session.status) }
                }
                item { AdminMessage(state.message, viewModel::clearMessage) }
                item {
                    GlassCard {
                        KeyValueRow(stringResource(R.string.requests_clock_in), JohannesburgTime.dateTime(session.clockInAt))
                        KeyValueRow(stringResource(R.string.requests_clock_out), JohannesburgTime.dateTime(session.clockOutAt))
                        KeyValueRow(stringResource(R.string.employee_worked), WorkedTime.format(session.workedMinutes))
                        KeyValueRow(stringResource(R.string.session_overtime), WorkedTime.format(session.overtimeMinutes))
                        if (session.isLate) KeyValueRow(stringResource(R.string.status_late), stringResource(R.string.status_late_minutes, session.lateMinutes))
                        if (session.isManual) KeyValueRow(stringResource(R.string.records_manual), stringResource(R.string.action_yes))
                        session.correctionNote?.let { KeyValueRow(stringResource(R.string.records_correction_note), it) }
                        if (ServerPermissions.canReview(permissions)) {
                            PillButton(
                                stringResource(R.string.records_correct), { correcting = true },
                                Modifier.fillMaxWidth().testTag("session_correct"), style = PillStyle.OUTLINE, enabled = !state.saving,
                            )
                        }
                    }
                }
                item { SectionHeader(stringResource(R.string.session_timeline)) }
                items(detail.events, key = { it.id }) { event ->
                    GlassCard(contentPadding = PaddingValues(Spacing.m)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    "${StatusTones.label(event.eventType)} · ${StatusTones.label(event.source)}",
                                    style = MaterialTheme.typography.titleSmall,
                                )
                                Text(
                                    JohannesburgTime.dateTime(event.officialAt ?: event.detectedAt),
                                    style = MaterialTheme.typography.bodySmall, color = MeridianTheme.extended.mutedText,
                                )
                            }
                            StatusChip(event.status)
                        }
                        KeyValueRow(stringResource(R.string.confirm_detected), JohannesburgTime.dateTime(event.detectedAt))
                        KeyValueRow(stringResource(R.string.session_received), JohannesburgTime.dateTime(event.receivedAt))
                        KeyValueRow(stringResource(R.string.confirm_distance), Geo.formatDistance(event.distanceFromWorkplaceM))
                        KeyValueRow(stringResource(R.string.admin_score), event.verificationScore?.let { "%.2f".format(it) } ?: "—")
                        KeyValueRow(stringResource(R.string.review_wifi), stringResource(if (event.wifiVerified) R.string.review_wifi_matched else R.string.review_wifi_not_matched))
                        event.verification?.let { v ->
                            val signals = listOfNotNull(
                                v.insideGeofence?.let { stringResource(R.string.session_inside_geofence) + ": " + yesNo(it) },
                                v.accuracyOk?.let { stringResource(R.string.session_accuracy_ok) + ": " + yesNo(it) },
                                v.timestampPlausible?.let { stringResource(R.string.session_time_plausible) + ": " + yesNo(it) },
                                v.deviceActive?.let { stringResource(R.string.session_device_active) + ": " + yesNo(it) },
                            ) + v.notes.map { StatusTones.label(it) }
                            if (signals.isNotEmpty()) {
                                Text(signals.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MeridianTheme.extended.mutedText)
                            }
                        }
                        event.reviewNote?.let { KeyValueRow(stringResource(R.string.admin_note), it) }
                    }
                }
                if (detail.events.isEmpty()) {
                    item { Text(stringResource(R.string.session_no_events), style = MaterialTheme.typography.bodySmall, color = MeridianTheme.extended.mutedText) }
                }
            }
        }
    }

    val session = detail?.session
    if (correcting && session != null) {
        val workDate = session.workDate
        var clockIn by remember { mutableStateOf(JohannesburgTime.localParts(session.clockInAt)?.second.orEmpty()) }
        var clockOut by remember { mutableStateOf(JohannesburgTime.localParts(session.clockOutAt)?.second.orEmpty()) }
        var note by remember { mutableStateOf("") }
        var error by remember { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { correcting = false },
            title = { Text(stringResource(R.string.records_correct)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                    Text(stringResource(R.string.records_correct_body), style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(clockIn, { clockIn = it }, label = { Text(stringResource(R.string.requests_clock_in)) }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("correct_in"))
                    OutlinedTextField(clockOut, { clockOut = it }, label = { Text(stringResource(R.string.requests_clock_out)) }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("correct_out"))
                    OutlinedTextField(note, { note = it }, label = { Text(stringResource(R.string.admin_reason)) }, minLines = 2, modifier = Modifier.fillMaxWidth().testTag("correct_note"))
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val inIso = JohannesburgTime.localToIsoUtc(workDate, clockIn)
                        val outIso = JohannesburgTime.localToIsoUtc(workDate, clockOut)
                        val changedIn = inIso?.takeIf { it != session.clockInAt }
                        val changedOut = outIso?.takeIf { it != session.clockOutAt }
                        error = when {
                            clockIn.isNotBlank() && inIso == null -> "Enter a time like 08:00."
                            clockOut.isNotBlank() && outIso == null -> "Enter a time like 17:00."
                            inIso != null && outIso != null && outIso <= inIso -> "Clock out must be after clock in."
                            note.trim().length < 3 -> "A reason of at least 3 characters is required."
                            changedIn == null && changedOut == null -> "Change at least one time."
                            else -> null
                        }
                        if (error == null) viewModel.correct(changedIn, changedOut, note.trim()) { correcting = false }
                    },
                    modifier = Modifier.testTag("correct_submit"),
                ) { Text(stringResource(R.string.action_save)) }
            },
            dismissButton = { TextButton(onClick = { correcting = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
private fun yesNo(value: Boolean): String = stringResource(if (value) R.string.action_yes else R.string.action_no)

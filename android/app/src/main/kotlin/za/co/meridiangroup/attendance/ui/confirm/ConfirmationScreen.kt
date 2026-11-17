package za.co.meridiangroup.attendance.ui.confirm

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CloudQueue
import androidx.compose.material.icons.outlined.HourglassTop
import androidx.compose.material.icons.automirrored.outlined.Login
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.DoNotDisturbOn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import za.co.meridiangroup.attendance.R
import za.co.meridiangroup.attendance.core.DismissReason
import za.co.meridiangroup.attendance.core.EventStatus
import za.co.meridiangroup.attendance.core.EventType
import za.co.meridiangroup.attendance.core.Geo
import za.co.meridiangroup.attendance.core.JohannesburgTime
import za.co.meridiangroup.attendance.core.StatusTone
import za.co.meridiangroup.attendance.core.WorkedTime
import za.co.meridiangroup.attendance.data.local.db.EventEntity
import za.co.meridiangroup.attendance.ui.components.Banner
import za.co.meridiangroup.attendance.ui.components.EmptyState
import za.co.meridiangroup.attendance.ui.components.GlassCard
import za.co.meridiangroup.attendance.ui.components.KeyValueRow
import za.co.meridiangroup.attendance.ui.components.PillButton
import za.co.meridiangroup.attendance.ui.components.PillStyle
import za.co.meridiangroup.attendance.ui.components.StatusChip
import za.co.meridiangroup.attendance.ui.theme.MeridianTheme
import za.co.meridiangroup.attendance.ui.theme.Spacing

/** Arrival or departure confirmation (layout adapts to the event type). */
@Composable
fun ConfirmationScreen(viewModel: ConfirmationViewModel, onClose: () -> Unit) {
    val event by viewModel.event.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Spacing.l).testTag("confirmation"),
        verticalArrangement = Arrangement.spacedBy(Spacing.m),
    ) {
        val result = state.result
        val e = event
        when {
            result != null -> ResultCard(result, e, onClose)
            e == null -> EmptyState(stringResource(R.string.confirm_missing_title), stringResource(R.string.confirm_missing_body)) {
                PillButton(stringResource(R.string.action_back_home), onClose)
            }
            EventStatus.fromWire(e.status) != EventStatus.PENDING -> AlreadyHandled(e, onClose)
            else -> PendingContent(e, state, viewModel)
        }
    }
    if (state.dismissOpen) DismissDialog(state, viewModel)
}

@Composable
private fun PendingContent(e: EventEntity, state: ConfirmUiState, vm: ConfirmationViewModel) {
    val arrival = EventType.fromWire(e.eventType) == EventType.ARRIVAL
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(if (arrival) Icons.AutoMirrored.Outlined.Login else Icons.AutoMirrored.Outlined.Logout, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp))
        Text(
            stringResource(if (arrival) R.string.confirm_arrival_title else R.string.confirm_departure_title),
            style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(start = Spacing.m),
        )
    }
    Text(stringResource(if (arrival) R.string.confirm_arrival_body else R.string.confirm_departure_body), style = MaterialTheme.typography.bodyMedium)

    GlassCard {
        KeyValueRow(stringResource(R.string.confirm_workplace), e.workplaceName ?: "—")
        KeyValueRow(stringResource(R.string.confirm_detected), JohannesburgTime.dateTime(e.detectedAt))
        KeyValueRow(stringResource(R.string.confirm_distance), Geo.formatDistance(e.distanceM))
        KeyValueRow(stringResource(R.string.confirm_accuracy), e.accuracyM?.let { "± ${it.toLong()} m" } ?: "—")
        KeyValueRow(
            stringResource(R.string.confirm_wifi),
            when (e.wifiMatched) {
                true -> stringResource(R.string.confirm_wifi_matched)
                false -> stringResource(R.string.confirm_wifi_not_matched)
                null -> stringResource(R.string.confirm_wifi_not_checked)
            },
        )
        JohannesburgTime.parse(e.expiresAt)?.let { exp ->
            KeyValueRow(stringResource(R.string.confirm_expires), stringResource(R.string.confirm_expires_in, WorkedTime.format(WorkedTime.minutesUntil(exp, System.currentTimeMillis())?.toInt())))
        }
        if (e.serverId == null) StatusChip("queued", label = stringResource(R.string.status_saved_offline))
    }
    if (e.likelyReview) {
        Banner(stringResource(R.string.confirm_review_title), stringResource(R.string.confirm_review_body), StatusTone.WARNING)
    }
    Text(stringResource(R.string.confirm_official_time_note), style = MaterialTheme.typography.bodySmall, color = MeridianTheme.extended.mutedText)
    OutlinedTextField(
        value = state.note, onValueChange = vm::onNote, label = { Text(stringResource(R.string.confirm_note)) },
        isError = state.noteError != null, supportingText = state.noteError?.let { { Text(it) } },
        modifier = Modifier.fillMaxWidth().testTag("confirm_note"), maxLines = 3,
    )
    state.error?.let { Banner(stringResource(R.string.confirm_failed), it, StatusTone.ERROR, modifier = Modifier.testTag("confirm_error")) }
    PillButton(
        stringResource(if (arrival) R.string.confirm_arrival_cta else R.string.confirm_departure_cta), vm::confirm,
        Modifier.fillMaxWidth().testTag("confirm_button"), style = PillStyle.SUCCESS, loading = state.working, icon = Icons.Outlined.CheckCircle,
    )
    PillButton(
        stringResource(R.string.confirm_not_me), vm::openDismiss, Modifier.fillMaxWidth().testTag("dismiss_button"),
        style = PillStyle.OUTLINE, enabled = !state.working, icon = Icons.Outlined.DoNotDisturbOn,
    )
}

@Composable
private fun DismissDialog(state: ConfirmUiState, vm: ConfirmationViewModel) {
    AlertDialog(
        onDismissRequest = vm::closeDismiss,
        title = { Text(stringResource(R.string.dismiss_title)) },
        text = {
            Column {
                Text(stringResource(R.string.dismiss_body), style = MaterialTheme.typography.bodyMedium)
                DismissReason.entries.forEach { reason ->
                    Row(
                        Modifier.fillMaxWidth().selectable(selected = state.dismissReason == reason, onClick = { vm.onDismissReason(reason) }, role = Role.RadioButton)
                            .padding(vertical = 4.dp).testTag("reason_${reason.wire}"),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = state.dismissReason == reason, onClick = null)
                        Text(
                            stringResource(
                                when (reason) {
                                    DismissReason.NOT_AT_WORK -> R.string.dismiss_not_at_work
                                    DismissReason.WRONG_DETECTION -> R.string.dismiss_wrong_detection
                                    DismissReason.OTHER -> R.string.dismiss_other
                                },
                            ),
                            modifier = Modifier.padding(start = Spacing.s),
                        )
                    }
                }
                OutlinedTextField(
                    value = state.dismissNote, onValueChange = vm::onDismissNote, label = { Text(stringResource(R.string.dismiss_note)) },
                    modifier = Modifier.fillMaxWidth().testTag("dismiss_note"), maxLines = 3,
                )
            }
        },
        confirmButton = { TextButton(onClick = vm::dismiss, modifier = Modifier.testTag("dismiss_submit")) { Text(stringResource(R.string.dismiss_submit)) } },
        dismissButton = { TextButton(onClick = vm::closeDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun ResultCard(result: ConfirmResult, e: EventEntity?, onClose: () -> Unit) {
    val arrival = e?.let { EventType.fromWire(it.eventType) == EventType.ARRIVAL } ?: true
    val (icon: ImageVector, title: Int, body: Int, tone: StatusTone) = when (result) {
        ConfirmResult.CONFIRMED -> Quad(Icons.Outlined.CheckCircle, if (arrival) R.string.result_clocked_in else R.string.result_clocked_out, R.string.result_confirmed_body, StatusTone.SUCCESS)
        ConfirmResult.UNDER_REVIEW -> Quad(Icons.Outlined.HourglassTop, R.string.result_review_title, R.string.result_review_body, StatusTone.WARNING)
        ConfirmResult.DISMISSED -> Quad(Icons.Outlined.DoNotDisturbOn, R.string.result_dismissed_title, R.string.result_dismissed_body, StatusTone.NEUTRAL)
        ConfirmResult.QUEUED_CONFIRM -> Quad(Icons.Outlined.CloudQueue, R.string.result_queued_title, R.string.result_queued_confirm_body, StatusTone.WARNING)
        ConfirmResult.QUEUED_DISMISS -> Quad(Icons.Outlined.CloudQueue, R.string.result_queued_title, R.string.result_queued_dismiss_body, StatusTone.WARNING)
    }
    GlassCard(Modifier.testTag("confirm_result_${result.name.lowercase()}")) {
        Icon(icon, null, tint = MeridianTheme.extended.accent(tone), modifier = Modifier.size(48.dp))
        Text(stringResource(title), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(body), style = MaterialTheme.typography.bodyMedium)
        PillButton(stringResource(R.string.action_done), onClose, Modifier.fillMaxWidth().padding(top = Spacing.l))
    }
}

@Composable
private fun AlreadyHandled(e: EventEntity, onClose: () -> Unit) {
    GlassCard {
        Text(stringResource(R.string.confirm_already_title), style = MaterialTheme.typography.titleLarge)
        StatusChip(e.status)
        Text(stringResource(R.string.confirm_already_body), style = MaterialTheme.typography.bodyMedium)
        PillButton(stringResource(R.string.action_back_home), onClose, Modifier.fillMaxWidth().padding(top = Spacing.l))
    }
}

private data class Quad<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)

package za.co.meridiangroup.attendance.ui.admin.policy

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import za.co.meridiangroup.attendance.core.StatusTone
import za.co.meridiangroup.attendance.data.remote.dto.PolicyDto
import za.co.meridiangroup.attendance.data.remote.dto.PolicyPayload
import za.co.meridiangroup.attendance.data.repository.AdminRepository
import za.co.meridiangroup.attendance.ui.admin.AdminDropdown
import za.co.meridiangroup.attendance.ui.admin.AdminMessage
import za.co.meridiangroup.attendance.ui.admin.AdminTitle
import za.co.meridiangroup.attendance.ui.components.Banner
import za.co.meridiangroup.attendance.ui.components.ErrorState
import za.co.meridiangroup.attendance.ui.components.LoadingState
import za.co.meridiangroup.attendance.ui.components.PillButton
import za.co.meridiangroup.attendance.ui.components.SectionHeader
import za.co.meridiangroup.attendance.ui.theme.MeridianTheme
import za.co.meridiangroup.attendance.ui.theme.Spacing

data class PolicyUiState(
    val original: PolicyDto? = null,
    val edited: PolicyDto? = null,
    val loading: Boolean = true,
    val error: String? = null,
    val message: String? = null,
    val saving: Boolean = false,
)

/** docs/API.md §8 — every policy value, with the reminder that these are defaults for management to review. */
class PolicyViewModel(private val repo: AdminRepository) : ViewModel() {
    private val _state = MutableStateFlow(PolicyUiState())
    val state: StateFlow<PolicyUiState> = _state.asStateFlow()

    init { load() }

    fun load() {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            when (val r = repo.policy()) {
                is ApiResult.Success -> _state.update { it.copy(loading = false, original = r.value, edited = r.value) }
                else -> _state.update { it.copy(loading = false, error = r.userMessage()) }
            }
        }
    }

    fun update(block: (PolicyDto) -> PolicyDto) = _state.update { st -> st.copy(edited = st.edited?.let(block)) }
    fun clearMessage() = _state.update { it.copy(message = null) }

    fun save() {
        val original = _state.value.original ?: return
        val edited = _state.value.edited ?: return
        val diff = PolicyPayload.diff(original, edited)
        if (diff.isEmpty()) { _state.update { it.copy(message = "No changes to save.") }; return }
        _state.update { it.copy(saving = true, message = null) }
        viewModelScope.launch {
            when (val r = repo.updatePolicy(diff)) {
                is ApiResult.Success -> _state.update { it.copy(saving = false, original = r.value, edited = r.value, message = "Policy saved.") }
                else -> _state.update { it.copy(saving = false, message = r.userMessage()) }
            }
        }
    }
}

@Composable
fun PolicyScreen(viewModel: PolicyViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val policy = state.edited

    when {
        state.loading && policy == null -> LoadingState(Modifier.fillMaxSize())
        policy == null -> ErrorState(state.error.orEmpty(), viewModel::load)
        else -> LazyColumn(
            Modifier.fillMaxSize().testTag("policy"),
            contentPadding = PaddingValues(Spacing.l),
            verticalArrangement = Arrangement.spacedBy(Spacing.s),
        ) {
            item { AdminTitle(stringResource(R.string.manage_policy), stringResource(R.string.manage_policy_body)) }
            item {
                Banner(stringResource(R.string.policy_review_title), stringResource(R.string.policy_review_body), StatusTone.NEUTRAL, modifier = Modifier.testTag("policy_note"))
            }
            item { AdminMessage(state.message, viewModel::clearMessage) }

            item { SectionHeader(stringResource(R.string.policy_hours)) }
            item { TextRow(policy.workdayStartLocal, stringResource(R.string.policy_workday_start), "p_start") { v -> viewModel.update { it.copy(workdayStartLocal = v) } } }
            item { TextRow(policy.workdayEndLocal, stringResource(R.string.policy_workday_end), "p_end") { v -> viewModel.update { it.copy(workdayEndLocal = v) } } }
            item { TextRow(policy.lateAfterLocal, stringResource(R.string.policy_late_after), "p_late") { v -> viewModel.update { it.copy(lateAfterLocal = v) } } }
            item { NumberRow(policy.graceMinutes, stringResource(R.string.policy_grace), "p_grace") { v -> viewModel.update { it.copy(graceMinutes = v) } } }
            item { NumberRow(policy.standardDayMinutes, stringResource(R.string.policy_standard_day), "p_standard") { v -> viewModel.update { it.copy(standardDayMinutes = v) } } }
            item { NumberRow(policy.breakMinutes, stringResource(R.string.policy_break), "p_break") { v -> viewModel.update { it.copy(breakMinutes = v) } } }
            item { NumberRow(policy.overtimeAfterMinutes, stringResource(R.string.policy_overtime_after), "p_overtime") { v -> viewModel.update { it.copy(overtimeAfterMinutes = v) } } }
            item {
                TextRow(policy.workingDays.joinToString(","), stringResource(R.string.policy_working_days), "p_days") { v ->
                    val days = v.split(',').mapNotNull { it.trim().toIntOrNull() }.filter { it in 1..7 }
                    viewModel.update { it.copy(workingDays = days) }
                }
            }

            item { SectionHeader(stringResource(R.string.policy_confirmations)) }
            item { NumberRow(policy.pendingExpiryHours, stringResource(R.string.policy_pending_expiry), "p_expiry") { v -> viewModel.update { it.copy(pendingExpiryHours = v) } } }
            item { TextRow(policy.missingClockOutFlagLocal, stringResource(R.string.policy_missing_flag), "p_missing") { v -> viewModel.update { it.copy(missingClockOutFlagLocal = v) } } }
            item { NumberRow(policy.maxFutureSkewSeconds, stringResource(R.string.policy_future_skew), "p_skew") { v -> viewModel.update { it.copy(maxFutureSkewSeconds = v) } } }
            item { NumberRow(policy.maxOfflineAgeHours, stringResource(R.string.policy_offline_age), "p_offline") { v -> viewModel.update { it.copy(maxOfflineAgeHours = v) } } }
            item { NumberRow(policy.onlineSkewToleranceSeconds, stringResource(R.string.policy_online_skew), "p_online_skew") { v -> viewModel.update { it.copy(onlineSkewToleranceSeconds = v) } } }
            item { NumberRow(policy.duplicateWindowSeconds, stringResource(R.string.policy_duplicate_window), "p_duplicate") { v -> viewModel.update { it.copy(duplicateWindowSeconds = v) } } }
            item {
                AdminDropdown(
                    stringResource(R.string.policy_time_source),
                    listOf("server_received" to stringResource(R.string.policy_time_server), "device_detected" to stringResource(R.string.policy_time_device)),
                    policy.officialTimeSource, { v -> viewModel.update { it.copy(officialTimeSource = v) } }, tag = "p_time_source",
                )
            }
            item {
                TextRow("%.2f".format(policy.minVerificationScoreForAutoConfirm), stringResource(R.string.policy_min_score), "p_score") { v ->
                    v.toDoubleOrNull()?.takeIf { it in 0.0..1.0 }?.let { d -> viewModel.update { it.copy(minVerificationScoreForAutoConfirm = d) } }
                }
            }

            item { SectionHeader(stringResource(R.string.policy_devices)) }
            item { NumberRow(policy.maxActiveDevicesPerUser, stringResource(R.string.policy_max_devices), "p_devices") { v -> viewModel.update { it.copy(maxActiveDevicesPerUser = v) } } }
            item {
                SwitchRow(stringResource(R.string.policy_replacement_approval), policy.deviceReplacementRequiresApproval, "p_replacement") { v ->
                    viewModel.update { it.copy(deviceReplacementRequiresApproval = v) }
                }
            }
            item {
                SwitchRow(stringResource(R.string.policy_require_integrity), policy.requireIntegrityVerdict, "p_integrity") { v ->
                    viewModel.update { it.copy(requireIntegrityVerdict = v) }
                }
            }

            item { SectionHeader(stringResource(R.string.policy_retention)) }
            item { NumberRow(policy.retentionDaysAttendance, stringResource(R.string.policy_retention_attendance), "p_ret_att") { v -> viewModel.update { it.copy(retentionDaysAttendance = v) } } }
            item { NumberRow(policy.retentionDaysLocation, stringResource(R.string.policy_retention_location), "p_ret_loc") { v -> viewModel.update { it.copy(retentionDaysLocation = v) } } }
            item { NumberRow(policy.retentionDaysAudit, stringResource(R.string.policy_retention_audit), "p_ret_audit") { v -> viewModel.update { it.copy(retentionDaysAudit = v) } } }

            item {
                PillButton(stringResource(R.string.action_save), viewModel::save, Modifier.fillMaxWidth().testTag("policy_save"), loading = state.saving)
            }
            item {
                Text(
                    stringResource(R.string.policy_updated, state.original?.updatedAt ?: "—"),
                    style = MaterialTheme.typography.bodySmall, color = MeridianTheme.extended.mutedText,
                )
            }
        }
    }
}

@Composable
private fun TextRow(value: String, label: String, tag: String, onChange: (String) -> Unit) {
    var text by remember(tag, value) { mutableStateOf(value) }
    OutlinedTextField(
        value = text, onValueChange = { text = it; onChange(it) }, label = { Text(label) }, singleLine = true,
        modifier = Modifier.fillMaxWidth().testTag(tag),
    )
}

@Composable
private fun NumberRow(value: Int, label: String, tag: String, onChange: (Int) -> Unit) {
    var text by remember(tag, value) { mutableStateOf(value.toString()) }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it; it.toIntOrNull()?.let(onChange) },
        label = { Text(label) }, singleLine = true,
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
        modifier = Modifier.fillMaxWidth().testTag(tag),
    )
}

@Composable
private fun SwitchRow(title: String, checked: Boolean, tag: String, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text(title, style = MaterialTheme.typography.bodyMedium) }
        Switch(checked = checked, onCheckedChange = onChange, modifier = Modifier.testTag(tag))
    }
}

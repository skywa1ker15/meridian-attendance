package za.co.meridiangroup.attendance.ui.admin.employees

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import java.io.File
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
import za.co.meridiangroup.attendance.core.StatusTones
import za.co.meridiangroup.attendance.core.WorkedTime
import za.co.meridiangroup.attendance.data.files.ExportStore
import za.co.meridiangroup.attendance.data.remote.dto.AdminDepartmentDto
import za.co.meridiangroup.attendance.data.remote.dto.DeviceDto
import za.co.meridiangroup.attendance.data.remote.dto.EmployeeDetailDto
import za.co.meridiangroup.attendance.data.remote.dto.EmployeeUpdateRequestDto
import za.co.meridiangroup.attendance.data.remote.dto.InvitationSummaryDto
import za.co.meridiangroup.attendance.ui.admin.AdminDropdown
import za.co.meridiangroup.attendance.ui.admin.AdminMessage
import za.co.meridiangroup.attendance.ui.admin.AdminTitle
import za.co.meridiangroup.attendance.ui.admin.InvitationDialog
import za.co.meridiangroup.attendance.ui.admin.NoteDialog
import za.co.meridiangroup.attendance.ui.admin.SecretDialog
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

data class EmployeeProfileUiState(
    val detail: EmployeeDetailDto? = null,
    val departments: List<AdminDepartmentDto> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
    val message: String? = null,
    val busy: Boolean = false,
    val secret: String? = null,
    /** Result of "Resend invitation": shown with the link when the email could not be sent. */
    val invitation: InvitationSummaryDto? = null,
    val exportFile: File? = null,
    val erased: Boolean = false,
)

/** One employee: details, role, devices, 30-day stats, password reset, POPIA export and erasure. */
class EmployeeProfileViewModel(private val graph: AppGraph, private val employeeId: String) : ViewModel() {
    private val repo = graph.adminRepository
    private val _state = MutableStateFlow(EmployeeProfileUiState())
    val state: StateFlow<EmployeeProfileUiState> = _state.asStateFlow()

    init {
        load()
        viewModelScope.launch { repo.departments().getOrNull()?.let { d -> _state.update { it.copy(departments = d.items) } } }
    }

    fun load() {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            when (val r = repo.employee(employeeId)) {
                is ApiResult.Success -> _state.update { it.copy(loading = false, detail = r.value) }
                else -> _state.update { it.copy(loading = false, error = r.userMessage()) }
            }
        }
    }

    private fun act(block: suspend () -> ApiResult<*>, success: String) {
        _state.update { it.copy(busy = true, message = null) }
        viewModelScope.launch {
            val r = block()
            _state.update { it.copy(busy = false, message = if (r is ApiResult.Success) success else r.userMessage()) }
            if (r is ApiResult.Success) load()
        }
    }

    fun save(body: EmployeeUpdateRequestDto) = act({ repo.updateEmployee(employeeId, body) }, "Employee updated.")
    fun changeRole(role: String) = act({ repo.changeRole(employeeId, role) }, "Role changed.")
    fun deactivate(reason: String) = act({ repo.deactivateEmployee(employeeId, reason) }, "Account deactivated; devices and sessions revoked.")
    fun reactivate() = act({ repo.reactivateEmployee(employeeId) }, "Account reactivated.")
    fun approveDevice(id: String) = act({ repo.approveDevice(id) }, "Device approved.")
    fun revokeDevice(id: String, reason: String) = act({ repo.revokeDevice(id, reason) }, "Device revoked.")

    /** Emails a fresh invitation link; the employee sets their own password from it. */
    fun resendInvitation() {
        _state.update { it.copy(busy = true, message = null) }
        viewModelScope.launch {
            when (val r = repo.resendInvitation(employeeId)) {
                is ApiResult.Success -> _state.update { it.copy(busy = false, invitation = r.value) }
                else -> _state.update { it.copy(busy = false, message = r.userMessage()) }
            }
        }
    }

    fun resetPassword() {
        _state.update { it.copy(busy = true, message = null) }
        viewModelScope.launch {
            when (val r = repo.resetPassword(employeeId)) {
                is ApiResult.Success -> _state.update { it.copy(busy = false, secret = r.value.temporaryPassword) }
                else -> _state.update { it.copy(busy = false, message = r.userMessage()) }
            }
        }
    }

    fun exportData() {
        val number = _state.value.detail?.user?.employeeNumber.orEmpty()
        _state.update { it.copy(busy = true, message = null) }
        viewModelScope.launch {
            when (val r = repo.exportEmployee(employeeId, number)) {
                is ApiResult.Success -> _state.update { it.copy(busy = false, exportFile = r.value, message = "Personal-data export downloaded.") }
                else -> _state.update { it.copy(busy = false, message = r.userMessage()) }
            }
        }
    }

    fun erase(confirmNumber: String, reason: String) {
        _state.update { it.copy(busy = true, message = null) }
        viewModelScope.launch {
            when (val r = repo.eraseEmployee(employeeId, confirmNumber, reason)) {
                is ApiResult.Success -> _state.update { it.copy(busy = false, erased = true, message = "Personal data erased.") }
                else -> _state.update { it.copy(busy = false, message = r.userMessage()) }
            }
        }
    }

    fun clearMessage() = _state.update { it.copy(message = null) }
    fun clearSecret() = _state.update { it.copy(secret = null) }
    fun clearInvitation() = _state.update { it.copy(invitation = null) }
    fun clearExport() = _state.update { it.copy(exportFile = null) }
    val exports: ExportStore get() = graph.exportStore
}

@Composable
fun EmployeeProfileScreen(viewModel: EmployeeProfileViewModel, permissions: List<String>) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val canWrite = ServerPermissions.canWriteEmployees(permissions)
    val canRoles = ServerPermissions.canWriteRoles(permissions)
    val canDevices = ServerPermissions.canWriteDevices(permissions)
    val isSuperadmin = ServerPermissions.isSuperadmin(permissions)
    var editing by remember { mutableStateOf(false) }
    var deactivating by remember { mutableStateOf(false) }
    var erasing by remember { mutableStateOf(false) }
    var revokingDevice by remember { mutableStateOf<DeviceDto?>(null) }

    state.exportFile?.let { file ->
        androidx.compose.runtime.LaunchedEffect(file) {
            context.startActivity(viewModel.exports.shareIntent(file, ExportStore.mimeFor("json")))
            viewModel.clearExport()
        }
    }

    val detail = state.detail
    when {
        state.loading && detail == null -> LoadingState(Modifier.fillMaxSize())
        detail == null -> ErrorState(state.error.orEmpty(), viewModel::load)
        else -> {
            val user = detail.user
            LazyColumn(
                Modifier.fillMaxSize().testTag("employee_profile"),
                contentPadding = PaddingValues(Spacing.l),
                verticalArrangement = Arrangement.spacedBy(Spacing.m),
            ) {
                item {
                    AdminTitle(user.fullName, listOfNotNull(user.employeeNumber, user.department?.name).joinToString(" · ")) {
                        StatusChip(user.status)
                    }
                }
                item { AdminMessage(state.message, viewModel::clearMessage) }
                item {
                    GlassCard {
                        KeyValueRow(stringResource(R.string.profile_email), user.email)
                        KeyValueRow(stringResource(R.string.profile_phone), user.phone ?: "—")
                        KeyValueRow(stringResource(R.string.profile_role), StatusTones.label(user.role))
                        KeyValueRow(stringResource(R.string.employee_last_login), JohannesburgTime.dateTime(user.lastLoginAt))
                        detail.today?.let { KeyValueRow(stringResource(R.string.employee_today), StatusTones.label(it.status)) }
                    }
                }
                item {
                    GlassCard {
                        Text(stringResource(R.string.employee_stats_30d), style = MaterialTheme.typography.titleSmall)
                        KeyValueRow(stringResource(R.string.employee_present_days), detail.stats30d.presentDays.toString())
                        KeyValueRow(stringResource(R.string.employee_late_days), detail.stats30d.lateDays.toString())
                        KeyValueRow(stringResource(R.string.employee_absent_days), detail.stats30d.absentDays.toString())
                        KeyValueRow(stringResource(R.string.employee_worked), WorkedTime.format(detail.stats30d.workedMinutes))
                        KeyValueRow(stringResource(R.string.employee_manual_requests), detail.stats30d.manualRequests.toString())
                    }
                }
                if (canWrite || canRoles || isSuperadmin) {
                    item { SectionHeader(stringResource(R.string.employee_actions)) }
                    item {
                        GlassCard {
                            Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                                if (canWrite) PillButton(stringResource(R.string.employee_edit), { editing = true }, Modifier.fillMaxWidth(), style = PillStyle.OUTLINE, enabled = !state.busy)
                                if (canWrite && user.lastLoginAt == null) {
                                    PillButton(
                                        stringResource(R.string.employee_resend_invitation), viewModel::resendInvitation,
                                        Modifier.fillMaxWidth().testTag("employee_resend_invite"), style = PillStyle.PRIMARY, enabled = !state.busy,
                                    )
                                }
                                if (canWrite && user.lastLoginAt != null) {
                                    PillButton(stringResource(R.string.employee_reset_password), viewModel::resetPassword, Modifier.fillMaxWidth(), style = PillStyle.OUTLINE, enabled = !state.busy)
                                }
                                if (canRoles) {
                                    AdminDropdown(
                                        stringResource(R.string.profile_role),
                                        ServerPermissions.assignableRoles(permissions).map { it to StatusTones.label(it) },
                                        user.role, viewModel::changeRole, tag = "role_select",
                                    )
                                }
                                if (canWrite) {
                                    if (user.status == "active") {
                                        PillButton(stringResource(R.string.employee_deactivate), { deactivating = true }, Modifier.fillMaxWidth(), style = PillStyle.DANGER, enabled = !state.busy)
                                    } else {
                                        PillButton(stringResource(R.string.employee_reactivate), viewModel::reactivate, Modifier.fillMaxWidth(), style = PillStyle.SUCCESS, enabled = !state.busy)
                                    }
                                }
                            }
                        }
                    }
                    // POPIA section — less prominent
                    if (canWrite || isSuperadmin) {
                        item {
                            GlassCard {
                                Text(stringResource(R.string.employee_popia_section, "POPIA"), style = MaterialTheme.typography.titleSmall)
                                Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                                    if (canWrite) PillButton(stringResource(R.string.employee_export), viewModel::exportData, Modifier.fillMaxWidth(), style = PillStyle.GHOST, enabled = !state.busy)
                                    if (isSuperadmin) PillButton(stringResource(R.string.employee_erase), { erasing = true }, Modifier.fillMaxWidth(), style = PillStyle.DANGER, enabled = !state.busy)
                                }
                            }
                        }
                    }
                }
                item { SectionHeader(stringResource(R.string.employee_devices)) }
                if (detail.devices.isEmpty()) {
                    item { Text(stringResource(R.string.employee_no_devices), style = MaterialTheme.typography.bodySmall, color = MeridianTheme.extended.mutedText) }
                }
                items(detail.devices) { device ->
                    GlassCard(contentPadding = PaddingValues(Spacing.m)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("${device.manufacturer.orEmpty()} ${device.model.orEmpty()}".trim().ifBlank { device.deviceId }, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    stringResource(R.string.employee_device_seen, JohannesburgTime.dateTime(device.lastSeenAt)),
                                    style = MaterialTheme.typography.bodySmall, color = MeridianTheme.extended.mutedText,
                                )
                            }
                            StatusChip(device.status)
                        }
                        if (canDevices) {
                            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                                if (device.status == "pending_approval") {
                                    PillButton(stringResource(R.string.devices_approve), { viewModel.approveDevice(device.id) }, style = PillStyle.SUCCESS, enabled = !state.busy)
                                }
                                if (device.status != "revoked") {
                                    PillButton(stringResource(R.string.devices_revoke), { revokingDevice = device }, style = PillStyle.DANGER, enabled = !state.busy)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    val detailUser = state.detail?.user
    if (editing && detailUser != null) {
        EditEmployeeDialog(
            user = detailUser,
            departments = state.departments,
            onDismiss = { editing = false },
            onSave = { body -> viewModel.save(body); editing = false },
        )
    }
    if (deactivating) {
        NoteDialog(
            stringResource(R.string.employee_deactivate), stringResource(R.string.admin_reason),
            stringResource(R.string.employee_deactivate), body = stringResource(R.string.employee_deactivate_body),
            onDismiss = { deactivating = false },
        ) { reason -> deactivating = false; viewModel.deactivate(reason) }
    }
    revokingDevice?.let { device ->
        NoteDialog(
            stringResource(R.string.devices_revoke), stringResource(R.string.admin_reason), stringResource(R.string.devices_revoke),
            onDismiss = { revokingDevice = null },
        ) { reason -> revokingDevice = null; viewModel.revokeDevice(device.id, reason) }
    }
    if (erasing && detailUser != null) {
        EraseDialog(
            employeeNumber = detailUser.employeeNumber,
            onDismiss = { erasing = false },
            onErase = { number, reason -> erasing = false; viewModel.erase(number, reason) },
        )
    }
    state.invitation?.let { invitation ->
        val employee = state.detail?.user
        InvitationDialog(
            name = employee?.fullName.orEmpty(),
            email = invitation.email?.ifBlank { null } ?: employee?.email.orEmpty(),
            expiresAt = invitation.expiresAt,
            link = invitation.takeIf { it.sent != true }?.link?.ifBlank { null },
            onDismiss = viewModel::clearInvitation,
        )
    }
    state.secret?.let {
        SecretDialog(stringResource(R.string.employee_temp_password_title), stringResource(R.string.employee_temp_password_body), it, viewModel::clearSecret)
    }
}

@Composable
private fun EditEmployeeDialog(
    user: za.co.meridiangroup.attendance.data.remote.dto.UserDto,
    departments: List<AdminDepartmentDto>,
    onDismiss: () -> Unit,
    onSave: (EmployeeUpdateRequestDto) -> Unit,
) {
    var number by remember { mutableStateOf(user.employeeNumber) }
    var first by remember { mutableStateOf(user.firstName) }
    var last by remember { mutableStateOf(user.lastName) }
    var email by remember { mutableStateOf(user.email) }
    var phone by remember { mutableStateOf(user.phone.orEmpty()) }
    var departmentId by remember { mutableStateOf(user.department?.id.orEmpty()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.employee_edit)) },
        text = {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Spacing.s),
            ) {
                OutlinedTextField(number, { number = it }, label = { Text(stringResource(R.string.setup_employee_number)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(first, { first = it }, label = { Text(stringResource(R.string.setup_first_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(last, { last = it }, label = { Text(stringResource(R.string.setup_last_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(email, { email = it }, label = { Text(stringResource(R.string.profile_email)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(phone, { phone = it }, label = { Text(stringResource(R.string.profile_phone)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                AdminDropdown(
                    stringResource(R.string.profile_department),
                    listOf("" to stringResource(R.string.employees_no_department)) + departments.map { it.id to it.name },
                    departmentId, { departmentId = it }, tag = "edit_department",
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val original = user
                    onSave(
                        EmployeeUpdateRequestDto(
                            employeeNumber = number.trim().takeIf { it != original.employeeNumber },
                            firstName = first.trim().takeIf { it != original.firstName },
                            lastName = last.trim().takeIf { it != original.lastName },
                            email = email.trim().takeIf { it != original.email },
                            phone = phone.trim().takeIf { it != original.phone.orEmpty() },
                            departmentId = departmentId.takeIf { it.isNotBlank() && it != original.department?.id },
                            clearDepartment = if (departmentId.isBlank() && original.department != null) true else null,
                        ),
                    )
                },
                modifier = Modifier.testTag("edit_submit"),
            ) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** Superadmin-only POPIA erasure: the employee number must be typed to confirm. */
@Composable
private fun EraseDialog(employeeNumber: String, onDismiss: () -> Unit, onErase: (String, String) -> Unit) {
    var typed by remember { mutableStateOf("") }
    var reason by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.employee_erase)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                Text(stringResource(R.string.employee_erase_body, employeeNumber), style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(typed, { typed = it }, label = { Text(stringResource(R.string.setup_employee_number)) }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("erase_number"))
                OutlinedTextField(reason, { reason = it }, label = { Text(stringResource(R.string.admin_reason)) }, minLines = 2, modifier = Modifier.fillMaxWidth().testTag("erase_reason"))
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onErase(typed.trim(), reason.trim()) },
                enabled = typed.trim().equals(employeeNumber, ignoreCase = true) && reason.trim().length >= 3,
                modifier = Modifier.testTag("erase_submit"),
            ) { Text(stringResource(R.string.employee_erase)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

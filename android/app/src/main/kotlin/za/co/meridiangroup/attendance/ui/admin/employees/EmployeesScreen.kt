package za.co.meridiangroup.attendance.ui.admin.employees

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PersonAdd
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
import za.co.meridiangroup.attendance.core.PhoneNumbers
import za.co.meridiangroup.attendance.core.ServerPermissions
import za.co.meridiangroup.attendance.core.StatusTones
import za.co.meridiangroup.attendance.core.Validation
import za.co.meridiangroup.attendance.data.remote.dto.AdminDepartmentDto
import za.co.meridiangroup.attendance.data.remote.dto.EmployeeCreateRequestDto
import za.co.meridiangroup.attendance.data.remote.dto.UserDto
import za.co.meridiangroup.attendance.data.repository.AdminRepository
import za.co.meridiangroup.attendance.ui.admin.AdminDropdown
import za.co.meridiangroup.attendance.ui.admin.AdminMessage
import za.co.meridiangroup.attendance.ui.admin.AdminTitle
import za.co.meridiangroup.attendance.ui.admin.ChipFilters
import za.co.meridiangroup.attendance.ui.admin.PagedUiState
import za.co.meridiangroup.attendance.ui.admin.SearchField
import za.co.meridiangroup.attendance.ui.admin.InvitationDialog
import za.co.meridiangroup.attendance.ui.admin.SecretDialog
import za.co.meridiangroup.attendance.ui.admin.PagedFooter
import za.co.meridiangroup.attendance.ui.components.GlassCard
import za.co.meridiangroup.attendance.ui.components.PillButton
import za.co.meridiangroup.attendance.ui.components.StatusChip
import za.co.meridiangroup.attendance.ui.theme.MeridianTheme
import za.co.meridiangroup.attendance.ui.theme.Spacing

data class EmployeesUiState(
    val paged: PagedUiState<UserDto> = PagedUiState(),
    val search: String = "",
    val departmentId: String = "",
    val status: String = "",
    val role: String = "",
    val departments: List<AdminDepartmentDto> = emptyList(),
    val creating: Boolean = false,
    val createError: String? = null,
    val created: CreateOutcome? = null,
)

/**
 * What the server answered when an employee was created: HR normally invites (the employee sets their own
 * password from the emailed link), but an administrator can still hand out a temporary password instead.
 */
sealed class CreateOutcome {
    data class Invited(val name: String, val email: String, val expiresAt: String?, val link: String?) : CreateOutcome()
    data class TemporaryPassword(val name: String, val password: String) : CreateOutcome()
}

class EmployeesViewModel(private val repo: AdminRepository) : ViewModel() {
    private val _state = MutableStateFlow(EmployeesUiState())
    val state: StateFlow<EmployeesUiState> = _state.asStateFlow()

    init {
        reload()
        viewModelScope.launch {
            repo.departments().getOrNull()?.let { items -> _state.update { it.copy(departments = items.items) } }
        }
    }

    fun onSearch(v: String) { _state.update { it.copy(search = v) }; reload() }
    fun onDepartment(v: String) { _state.update { it.copy(departmentId = v) }; reload() }
    fun onStatus(v: String) { _state.update { it.copy(status = v) }; reload() }
    fun onRole(v: String) { _state.update { it.copy(role = v) }; reload() }

    fun reload() { _state.update { it.copy(paged = it.paged.reset()) }; loadMore() }

    fun loadMore() {
        val s = _state.value
        if (s.paged.loading || !s.paged.hasMore) return
        _state.update { it.copy(paged = it.paged.startLoading()) }
        viewModelScope.launch {
            when (val r = repo.employees(s.search, s.departmentId, s.status, s.role, s.paged.page + 1)) {
                is ApiResult.Success -> _state.update { it.copy(paged = it.paged.append(r.value.items, r.value.page, r.value.hasMore)) }
                else -> _state.update { it.copy(paged = it.paged.failed(r.userMessage())) }
            }
        }
    }

    fun create(body: EmployeeCreateRequestDto, onCreated: () -> Unit) {
        _state.update { it.copy(creating = true, createError = null) }
        viewModelScope.launch {
            when (val r = repo.createEmployee(body)) {
                is ApiResult.Success -> {
                    val value = r.value
                    val outcome = value.temporaryPassword?.takeIf { p -> p.isNotBlank() }
                        ?.let { p -> CreateOutcome.TemporaryPassword(value.user.fullName, p) }
                        ?: CreateOutcome.Invited(
                            value.user.fullName,
                            value.invitation?.email?.ifBlank { null } ?: value.user.email,
                            value.invitation?.expiresAt,
                            // Present only when the email could not be sent.
                            value.invitation?.takeIf { i -> i.sent != true }?.link?.ifBlank { null },
                        )
                    _state.update { it.copy(creating = false, created = outcome) }
                    onCreated()
                    reload()
                }
                else -> _state.update { it.copy(creating = false, createError = r.userMessage()) }
            }
        }
    }

    fun clearSecret() = _state.update { it.copy(created = null) }
    fun clearMessage() = _state.update { it.copy(paged = it.paged.copy(message = null), createError = null) }
}

@Composable
fun EmployeesScreen(viewModel: EmployeesViewModel, permissions: List<String>, onOpenEmployee: (String) -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showCreate by remember { mutableStateOf(false) }
    val canWrite = ServerPermissions.canWriteEmployees(permissions)
    val departmentOptions = listOf("" to stringResource(R.string.filter_all_departments)) + state.departments.map { it.id to it.name }

    LazyColumn(
        Modifier.fillMaxSize().testTag("employees_list"),
        contentPadding = PaddingValues(Spacing.l),
        verticalArrangement = Arrangement.spacedBy(Spacing.s),
    ) {
        item {
            AdminTitle(
                stringResource(R.string.manage_employees),
                stringResource(R.string.manage_employees_count, state.paged.items.size),
                action = {
                    if (canWrite) {
                        PillButton(stringResource(R.string.employees_new), { showCreate = true }, icon = Icons.Outlined.PersonAdd, modifier = Modifier.testTag("employee_new"))
                    }
                },
            )
        }
        item { SearchField(state.search, viewModel::onSearch, stringResource(R.string.employees_search), tag = "employee_search") }
        item { AdminDropdown(stringResource(R.string.profile_department), departmentOptions, state.departmentId, viewModel::onDepartment, tag = "employee_department") }
        item {
            ChipFilters(
                listOf("" to stringResource(R.string.filter_all), "active" to stringResource(R.string.status_active), "inactive" to stringResource(R.string.status_inactive)),
                state.status, viewModel::onStatus,
            )
        }
        item {
            ChipFilters(
                listOf("" to stringResource(R.string.filter_all_roles), "employee" to StatusTones.label("employee"), "manager" to StatusTones.label("manager"),
                    "admin" to StatusTones.label("admin"), "superadmin" to StatusTones.label("superadmin")),
                state.role, viewModel::onRole,
            )
        }
        state.createError?.let { item { AdminMessage(it, viewModel::clearMessage) } }
        items(state.paged.items, key = { it.id }) { user ->
            GlassCard(onClick = { onOpenEmployee(user.id) }, contentPadding = PaddingValues(Spacing.m)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(user.fullName, style = MaterialTheme.typography.titleSmall)
                        Text(
                            listOfNotNull(user.employeeNumber, user.department?.name, StatusTones.label(user.role)).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall, color = MeridianTheme.extended.mutedText,
                        )
                    }
                    if (user.status != "active") StatusChip(user.status)
                    user.lastAttendanceStatus?.let { StatusChip(it) }
                }
            }
        }
        item {
            PagedFooter(
                state.paged, stringResource(R.string.employees_empty_title), stringResource(R.string.employees_empty_body),
                viewModel::reload, viewModel::loadMore,
            )
        }
    }

    if (showCreate) {
        CreateEmployeeDialog(
            departments = state.departments,
            roles = ServerPermissions.assignableRoles(permissions),
            saving = state.creating,
            error = state.createError,
            onDismiss = { showCreate = false },
            onCreate = { body -> viewModel.create(body) { showCreate = false } },
        )
    }
    when (val outcome = state.created) {
        is CreateOutcome.TemporaryPassword -> SecretDialog(
            stringResource(R.string.employees_created_title),
            stringResource(R.string.employees_created_body, outcome.name),
            outcome.password,
            viewModel::clearSecret,
        )
        is CreateOutcome.Invited -> InvitationDialog(
            name = outcome.name, email = outcome.email, expiresAt = outcome.expiresAt, link = outcome.link,
            onDismiss = viewModel::clearSecret,
        )
        null -> Unit
    }
}

@Composable
private fun CreateEmployeeDialog(
    departments: List<AdminDepartmentDto>,
    roles: List<String>,
    saving: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onCreate: (EmployeeCreateRequestDto) -> Unit,
) {
    var number by remember { mutableStateOf("") }
    var first by remember { mutableStateOf("") }
    var last by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var departmentId by remember { mutableStateOf("") }
    var role by remember { mutableStateOf("employee") }
    var localError by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.employees_new)) },
        text = {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Spacing.s),
            ) {
                OutlinedTextField(number, { number = it }, label = { Text(stringResource(R.string.setup_employee_number)) }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("new_number"))
                OutlinedTextField(first, { first = it }, label = { Text(stringResource(R.string.setup_first_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("new_first"))
                OutlinedTextField(last, { last = it }, label = { Text(stringResource(R.string.setup_last_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("new_last"))
                OutlinedTextField(email, { email = it }, label = { Text(stringResource(R.string.profile_email)) }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("new_email"))
                OutlinedTextField(
                    phone, { phone = it }, label = { Text(stringResource(R.string.profile_phone)) }, singleLine = true,
                    supportingText = { Text(stringResource(R.string.employees_phone_optional)) },
                    modifier = Modifier.fillMaxWidth().testTag("new_phone"),
                )
                AdminDropdown(
                    stringResource(R.string.profile_department),
                    listOf("" to stringResource(R.string.employees_no_department)) + departments.map { it.id to it.name },
                    departmentId, { departmentId = it }, tag = "new_department",
                )
                AdminDropdown(stringResource(R.string.profile_role), roles.map { it to StatusTones.label(it) }, role, { role = it }, tag = "new_role")
                (localError ?: error)?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                Text(stringResource(R.string.employees_invite_note), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val issue = when {
                        Validation.identifier(number) != null -> Validation.identifier(number)
                        first.isBlank() || last.isBlank() -> "Enter the first and last name."
                        !email.contains('@') -> "Enter a valid email address."
                        PhoneNumbers.error(phone, required = false) != null -> PhoneNumbers.HELP
                        else -> null
                    }
                    if (issue != null) { localError = issue; return@TextButton }
                    localError = null
                    onCreate(
                        EmployeeCreateRequestDto(
                            employeeNumber = number.trim(), firstName = first.trim(), lastName = last.trim(), email = email.trim(),
                            phone = PhoneNumbers.normalise(phone), departmentId = departmentId.ifBlank { null }, role = role,
                        ),
                    )
                },
                enabled = !saving,
                modifier = Modifier.testTag("new_submit"),
            ) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

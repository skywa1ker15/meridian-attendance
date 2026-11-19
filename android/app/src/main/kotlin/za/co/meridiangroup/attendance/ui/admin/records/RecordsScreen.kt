package za.co.meridiangroup.attendance.ui.admin.records

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PostAdd
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
import za.co.meridiangroup.attendance.core.JohannesburgTime
import za.co.meridiangroup.attendance.core.ServerPermissions
import za.co.meridiangroup.attendance.core.StatusTones
import za.co.meridiangroup.attendance.core.WorkedTime
import za.co.meridiangroup.attendance.data.remote.dto.AdminDepartmentDto
import za.co.meridiangroup.attendance.data.remote.dto.SessionDto
import za.co.meridiangroup.attendance.data.remote.dto.UserDto
import za.co.meridiangroup.attendance.data.repository.AdminRepository
import za.co.meridiangroup.attendance.ui.admin.AdminDropdown
import za.co.meridiangroup.attendance.ui.admin.AdminMessage
import za.co.meridiangroup.attendance.ui.admin.AdminTitle
import za.co.meridiangroup.attendance.ui.admin.ChipFilters
import za.co.meridiangroup.attendance.ui.admin.DateField
import za.co.meridiangroup.attendance.ui.admin.PagedFooter
import za.co.meridiangroup.attendance.ui.admin.PagedUiState
import za.co.meridiangroup.attendance.ui.admin.employees.EmployeePickerDialog
import za.co.meridiangroup.attendance.ui.components.GlassCard
import za.co.meridiangroup.attendance.ui.components.PillButton
import za.co.meridiangroup.attendance.ui.components.PillStyle
import za.co.meridiangroup.attendance.ui.components.StatusChip
import za.co.meridiangroup.attendance.ui.theme.MeridianTheme
import za.co.meridiangroup.attendance.ui.theme.Spacing

data class RecordsUiState(
    val paged: PagedUiState<SessionDto> = PagedUiState(),
    val from: String? = null,
    val to: String? = null,
    val employee: UserDto? = null,
    val departmentId: String = "",
    val status: String = "",
    val departments: List<AdminDepartmentDto> = emptyList(),
    val message: String? = null,
    val creating: Boolean = false,
)

class RecordsViewModel(private val repo: AdminRepository, now: () -> Long) : ViewModel() {
    private val today = JohannesburgTime.workDateOf(now())
    private val _state = MutableStateFlow(RecordsUiState(from = JohannesburgTime.daysAgo(today, 6), to = today))
    val state: StateFlow<RecordsUiState> = _state.asStateFlow()

    init {
        reload()
        viewModelScope.launch { repo.departments().getOrNull()?.let { d -> _state.update { it.copy(departments = d.items) } } }
    }

    fun setFrom(v: String) { _state.update { it.copy(from = v) }; reload() }
    fun setTo(v: String) { _state.update { it.copy(to = v) }; reload() }
    fun setEmployee(u: UserDto?) { _state.update { it.copy(employee = u) }; reload() }
    fun setDepartment(v: String) { _state.update { it.copy(departmentId = v) }; reload() }
    fun setStatus(v: String) { _state.update { it.copy(status = v) }; reload() }

    fun reload() { _state.update { it.copy(paged = it.paged.reset()) }; loadMore() }

    fun loadMore() {
        val s = _state.value
        if (s.paged.loading || !s.paged.hasMore) return
        _state.update { it.copy(paged = it.paged.startLoading()) }
        viewModelScope.launch {
            val late = if (s.status == "late") true else null
            val manual = if (s.status == "manual") true else null
            val status = s.status.takeIf { it !in setOf("", "late", "manual") }
            when (val r = repo.sessions(s.from, s.to, s.employee?.id, s.departmentId, status, late, manual, s.paged.page + 1)) {
                is ApiResult.Success -> _state.update { it.copy(paged = it.paged.append(r.value.items, r.value.page, r.value.hasMore)) }
                else -> _state.update { it.copy(paged = it.paged.failed(r.userMessage())) }
            }
        }
    }

    fun createSession(employeeId: String, workDate: String, clockInAt: String, clockOutAt: String?, note: String, onDone: () -> Unit) {
        _state.update { it.copy(creating = true, message = null) }
        viewModelScope.launch {
            val r = repo.createSession(employeeId, workDate, clockInAt, clockOutAt, note)
            _state.update { it.copy(creating = false, message = if (r is ApiResult.Success) "Manual session created." else r.userMessage()) }
            if (r is ApiResult.Success) { onDone(); reload() }
        }
    }

    fun clearMessage() = _state.update { it.copy(message = null) }
}

@Composable
fun RecordsScreen(viewModel: RecordsViewModel, repo: AdminRepository, permissions: List<String>, onOpenSession: (String) -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var pickingEmployee by remember { mutableStateOf(false) }
    var addingSession by remember { mutableStateOf(false) }
    val canReview = ServerPermissions.canReview(permissions)

    LazyColumn(
        Modifier.fillMaxSize().testTag("records_list"),
        contentPadding = PaddingValues(Spacing.l),
        verticalArrangement = Arrangement.spacedBy(Spacing.s),
    ) {
        item {
            AdminTitle(stringResource(R.string.manage_records), stringResource(R.string.manage_records_body)) {
                if (canReview) {
                    PillButton(stringResource(R.string.records_add), { addingSession = true }, icon = Icons.Outlined.PostAdd, modifier = Modifier.testTag("records_add"))
                }
            }
        }
        item { AdminMessage(state.message, viewModel::clearMessage) }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                DateField(stringResource(R.string.records_from), state.from, viewModel::setFrom, Modifier.weight(1f), tag = "records_from")
                DateField(stringResource(R.string.records_to), state.to, viewModel::setTo, Modifier.weight(1f), tag = "records_to")
            }
        }
        item {
            PillButton(
                state.employee?.fullName ?: stringResource(R.string.records_any_employee),
                { pickingEmployee = true }, Modifier.fillMaxWidth().testTag("records_employee"), style = PillStyle.OUTLINE,
            )
        }
        item {
            AdminDropdown(
                stringResource(R.string.profile_department),
                listOf("" to stringResource(R.string.filter_all_departments)) + state.departments.map { it.id to it.name },
                state.departmentId, viewModel::setDepartment, tag = "records_department",
            )
        }
        item {
            ChipFilters(
                listOf(
                    "" to stringResource(R.string.filter_all),
                    "open" to StatusTones.label("open"),
                    "closed" to StatusTones.label("closed"),
                    "missing_clock_out" to StatusTones.label("missing_clock_out"),
                    "late" to stringResource(R.string.status_late),
                    "manual" to stringResource(R.string.records_manual),
                ),
                state.status, viewModel::setStatus,
            )
        }
        items(state.paged.items, key = { it.id }) { session ->
            GlassCard(onClick = { onOpenSession(session.id) }, contentPadding = PaddingValues(Spacing.m)) {
                Row(verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f)) {
                        Text(session.employee?.fullName ?: JohannesburgTime.workDate(session.workDate), style = MaterialTheme.typography.titleSmall)
                        Text(
                            JohannesburgTime.workDate(session.workDate),
                            style = MaterialTheme.typography.bodySmall, color = MeridianTheme.extended.mutedText,
                        )
                        Text(
                            "${session.clockInLocal ?: "—"} – ${session.clockOutLocal ?: "—"}  ·  ${WorkedTime.format(session.workedMinutes)}",
                            style = MaterialTheme.typography.bodySmall, color = MeridianTheme.extended.mutedText,
                        )
                    }
                    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                        StatusChip(session.status)
                        if (session.isLate) StatusChip("late", label = stringResource(R.string.status_late))
                        if (session.corrected) StatusChip("corrected", label = stringResource(R.string.records_corrected))
                    }
                }
            }
        }
        item {
            PagedFooter(
                state.paged, stringResource(R.string.records_empty_title), stringResource(R.string.records_empty_body),
                viewModel::reload, viewModel::loadMore,
            )
        }
    }

    if (pickingEmployee) {
        EmployeePickerDialog(repo, onDismiss = { pickingEmployee = false }) { user ->
            pickingEmployee = false
            viewModel.setEmployee(user)
        }
    }
    if (addingSession) {
        AddSessionDialog(
            repo = repo,
            saving = state.creating,
            onDismiss = { addingSession = false },
        ) { employeeId, workDate, inIso, outIso, note ->
            viewModel.createSession(employeeId, workDate, inIso, outIso, note) { addingSession = false }
        }
    }
}

/** Creates a session with source `admin` (docs/API.md §7 POST /admin/attendance/sessions). */
@Composable
private fun AddSessionDialog(
    repo: AdminRepository,
    saving: Boolean,
    onDismiss: () -> Unit,
    onCreate: (String, String, String, String?, String) -> Unit,
) {
    var employee by remember { mutableStateOf<UserDto?>(null) }
    var picking by remember { mutableStateOf(false) }
    var workDate by remember { mutableStateOf(JohannesburgTime.workDateOf(System.currentTimeMillis())) }
    var clockIn by remember { mutableStateOf("08:00") }
    var clockOut by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.records_add)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                Text(stringResource(R.string.records_add_body), style = MaterialTheme.typography.bodySmall)
                PillButton(
                    employee?.fullName ?: stringResource(R.string.picker_employee), { picking = true },
                    Modifier.fillMaxWidth().testTag("add_employee"), style = PillStyle.OUTLINE,
                )
                DateField(stringResource(R.string.requests_work_date), workDate, { workDate = it }, Modifier.fillMaxWidth(), tag = "add_date")
                OutlinedTextField(clockIn, { clockIn = it }, label = { Text(stringResource(R.string.requests_clock_in)) }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("add_in"))
                OutlinedTextField(clockOut, { clockOut = it }, label = { Text(stringResource(R.string.requests_clock_out)) }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("add_out"))
                OutlinedTextField(note, { note = it }, label = { Text(stringResource(R.string.admin_reason)) }, minLines = 2, modifier = Modifier.fillMaxWidth().testTag("add_note"))
                Text(stringResource(R.string.records_times_sast), style = MaterialTheme.typography.bodySmall, color = MeridianTheme.extended.mutedText)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val user = employee
                    val inIso = JohannesburgTime.localToIsoUtc(workDate, clockIn)
                    val outIso = JohannesburgTime.localToIsoUtc(workDate, clockOut)
                    error = when {
                        user == null -> "Choose an employee."
                        inIso == null -> "Enter a clock-in time like 08:00."
                        clockOut.isNotBlank() && outIso == null -> "Enter a clock-out time like 17:00."
                        outIso != null && outIso <= inIso -> "Clock out must be after clock in."
                        note.trim().length < 3 -> "A reason of at least 3 characters is required."
                        else -> null
                    }
                    if (error == null && user != null && inIso != null) onCreate(user.id, workDate, inIso, outIso, note.trim())
                },
                enabled = !saving,
                modifier = Modifier.testTag("add_submit"),
            ) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )

    if (picking) {
        EmployeePickerDialog(repo, onDismiss = { picking = false }, allowClear = false) { user ->
            picking = false
            if (user != null) employee = user
        }
    }
}

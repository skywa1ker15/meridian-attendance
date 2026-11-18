package za.co.meridiangroup.attendance.ui.admin.policy

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
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
import za.co.meridiangroup.attendance.data.remote.dto.AdminDepartmentDto
import za.co.meridiangroup.attendance.data.repository.AdminRepository
import za.co.meridiangroup.attendance.ui.admin.AdminMessage
import za.co.meridiangroup.attendance.ui.admin.AdminTitle
import za.co.meridiangroup.attendance.ui.components.EmptyState
import za.co.meridiangroup.attendance.ui.components.ErrorState
import za.co.meridiangroup.attendance.ui.components.GlassCard
import za.co.meridiangroup.attendance.ui.components.LoadingState
import za.co.meridiangroup.attendance.ui.components.PillButton
import za.co.meridiangroup.attendance.ui.components.PillStyle
import za.co.meridiangroup.attendance.ui.components.StatusChip
import za.co.meridiangroup.attendance.ui.theme.MeridianTheme
import za.co.meridiangroup.attendance.ui.theme.Spacing

data class DepartmentsUiState(
    val items: List<AdminDepartmentDto> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
    val message: String? = null,
    val saving: Boolean = false,
)

class DepartmentsViewModel(private val repo: AdminRepository) : ViewModel() {
    private val _state = MutableStateFlow(DepartmentsUiState())
    val state: StateFlow<DepartmentsUiState> = _state.asStateFlow()

    init { load() }

    fun load() {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            when (val r = repo.departments()) {
                is ApiResult.Success -> _state.update { it.copy(loading = false, items = r.value.items) }
                else -> _state.update { it.copy(loading = false, error = r.userMessage()) }
            }
        }
    }

    fun create(name: String, code: String, onDone: () -> Unit) = act(onDone) { repo.createDepartment(name, code) }

    fun update(id: String, name: String?, code: String?, active: Boolean?, onDone: () -> Unit) = act(onDone) { repo.updateDepartment(id, name, code, active) }

    private fun act(onDone: () -> Unit, block: suspend () -> ApiResult<*>) {
        _state.update { it.copy(saving = true, message = null) }
        viewModelScope.launch {
            val r = block()
            _state.update { it.copy(saving = false, message = if (r is ApiResult.Success) "Department saved." else r.userMessage()) }
            if (r is ApiResult.Success) { onDone(); load() }
        }
    }

    fun clearMessage() = _state.update { it.copy(message = null) }
}

@Composable
fun DepartmentsScreen(viewModel: DepartmentsViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<AdminDepartmentDto?>(null) }
    var creating by remember { mutableStateOf(false) }

    when {
        state.loading && state.items.isEmpty() -> LoadingState(Modifier.fillMaxSize())
        state.error != null && state.items.isEmpty() -> ErrorState(state.error.orEmpty(), viewModel::load)
        else -> LazyColumn(
            Modifier.fillMaxSize().testTag("departments"),
            contentPadding = PaddingValues(Spacing.l),
            verticalArrangement = Arrangement.spacedBy(Spacing.s),
        ) {
            item {
                AdminTitle(stringResource(R.string.manage_departments), stringResource(R.string.manage_departments_body)) {
                    PillButton(stringResource(R.string.departments_new), { creating = true }, icon = Icons.Outlined.Add, modifier = Modifier.testTag("department_new"))
                }
            }
            item { AdminMessage(state.message, viewModel::clearMessage) }
            if (state.items.isEmpty()) item { EmptyState(stringResource(R.string.departments_empty_title), stringResource(R.string.departments_empty_body)) }
            items(state.items, key = { it.id }) { department ->
                GlassCard(onClick = { editing = department }, contentPadding = PaddingValues(Spacing.m)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(department.name, style = MaterialTheme.typography.titleSmall)
                            Text(
                                listOfNotNull(department.code, stringResource(R.string.departments_employees, department.employeeCount)).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall, color = MeridianTheme.extended.mutedText,
                            )
                        }
                        if (!department.active) StatusChip("inactive")
                        PillButton(
                            stringResource(if (department.active) R.string.departments_deactivate else R.string.departments_activate),
                            { viewModel.update(department.id, null, null, !department.active) {} },
                            style = PillStyle.GHOST, enabled = !state.saving,
                        )
                    }
                }
            }
        }
    }

    if (creating) {
        DepartmentDialog(null, { creating = false }) { name, code -> viewModel.create(name, code) { creating = false } }
    }
    editing?.let { department ->
        DepartmentDialog(department, { editing = null }) { name, code ->
            viewModel.update(department.id, name.takeIf { it != department.name }, code.takeIf { it != department.code }, null) { editing = null }
        }
    }
}

@Composable
private fun DepartmentDialog(department: AdminDepartmentDto?, onDismiss: () -> Unit, onSave: (String, String) -> Unit) {
    var name by remember { mutableStateOf(department?.name.orEmpty()) }
    var code by remember { mutableStateOf(department?.code.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (department == null) R.string.departments_new else R.string.departments_edit)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.departments_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("dept_name"))
                OutlinedTextField(code, { code = it }, label = { Text(stringResource(R.string.departments_code)) }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("dept_code"))
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(name.trim(), code.trim()) },
                enabled = name.isNotBlank() && code.isNotBlank(),
                modifier = Modifier.testTag("dept_submit"),
            ) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

package za.co.meridiangroup.attendance.ui.admin.roles

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
import za.co.meridiangroup.attendance.core.ServerPermissions
import za.co.meridiangroup.attendance.core.StatusTones
import za.co.meridiangroup.attendance.data.remote.dto.RoleInfoDto
import za.co.meridiangroup.attendance.data.remote.dto.UserDto
import za.co.meridiangroup.attendance.data.repository.AdminRepository
import za.co.meridiangroup.attendance.ui.admin.AdminDropdown
import za.co.meridiangroup.attendance.ui.admin.AdminMessage
import za.co.meridiangroup.attendance.ui.admin.AdminTitle
import za.co.meridiangroup.attendance.ui.admin.employees.EmployeePickerDialog
import za.co.meridiangroup.attendance.ui.components.ErrorState
import za.co.meridiangroup.attendance.ui.components.GlassCard
import za.co.meridiangroup.attendance.ui.components.LoadingState
import za.co.meridiangroup.attendance.ui.components.PillButton
import za.co.meridiangroup.attendance.ui.components.PillStyle
import za.co.meridiangroup.attendance.ui.theme.MeridianTheme
import za.co.meridiangroup.attendance.ui.theme.Spacing

data class RolesUiState(
    val roles: List<RoleInfoDto> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
    val message: String? = null,
    val saving: Boolean = false,
)

class RolesViewModel(private val repo: AdminRepository) : ViewModel() {
    private val _state = MutableStateFlow(RolesUiState())
    val state: StateFlow<RolesUiState> = _state.asStateFlow()

    init { load() }

    fun load() {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            when (val r = repo.roles()) {
                is ApiResult.Success -> _state.update { it.copy(loading = false, roles = r.value.roles) }
                else -> _state.update { it.copy(loading = false, error = r.userMessage()) }
            }
        }
    }

    fun changeRole(userId: String, role: String, onDone: () -> Unit) {
        _state.update { it.copy(saving = true, message = null) }
        viewModelScope.launch {
            val r = repo.changeRole(userId, role)
            _state.update { it.copy(saving = false, message = if (r is ApiResult.Success) "Role changed." else r.userMessage()) }
            if (r is ApiResult.Success) { onDone(); load() }
        }
    }

    fun clearMessage() = _state.update { it.copy(message = null) }
}

@Composable
fun RolesScreen(viewModel: RolesViewModel, permissions: List<String>) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val repo = za.co.meridiangroup.attendance.ui.common.rememberGraph().adminRepository
    var picking by remember { mutableStateOf(false) }
    var target by remember { mutableStateOf<UserDto?>(null) }
    val canWrite = ServerPermissions.canWriteRoles(permissions)

    when {
        state.loading && state.roles.isEmpty() -> LoadingState(Modifier.fillMaxSize())
        state.error != null && state.roles.isEmpty() -> ErrorState(state.error.orEmpty(), viewModel::load)
        else -> LazyColumn(
            Modifier.fillMaxSize().testTag("roles"),
            contentPadding = PaddingValues(Spacing.l),
            verticalArrangement = Arrangement.spacedBy(Spacing.s),
        ) {
            item {
                AdminTitle(stringResource(R.string.manage_roles), stringResource(R.string.manage_roles_body)) {
                    if (canWrite) PillButton(stringResource(R.string.roles_change), { picking = true }, modifier = Modifier.testTag("roles_change"))
                }
            }
            item { AdminMessage(state.message, viewModel::clearMessage) }
            items(state.roles, key = { it.role }) { role ->
                GlassCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(StatusTones.label(role.role), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        Text(stringResource(R.string.roles_user_count, role.userCount), style = MaterialTheme.typography.bodySmall, color = MeridianTheme.extended.mutedText)
                    }
                    Text(role.description, style = MaterialTheme.typography.bodySmall)
                    role.permissions.forEach { permission ->
                        Text("• ${StatusTones.label(permission.replace(':', '_'))}", style = MaterialTheme.typography.bodySmall, color = MeridianTheme.extended.mutedText)
                    }
                }
            }
            item {
                Text(stringResource(R.string.roles_note), style = MaterialTheme.typography.bodySmall, color = MeridianTheme.extended.mutedText)
            }
        }
    }

    if (picking) {
        EmployeePickerDialog(repo, onDismiss = { picking = false }, allowClear = false) { user ->
            picking = false
            target = user
        }
    }
    target?.let { user ->
        var role by remember(user.id) { mutableStateOf(user.role) }
        AlertDialog(
            onDismissRequest = { target = null },
            title = { Text(stringResource(R.string.roles_change)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                    Text(user.fullName, style = MaterialTheme.typography.titleSmall)
                    AdminDropdown(
                        stringResource(R.string.profile_role),
                        ServerPermissions.assignableRoles(permissions).map { it to StatusTones.label(it) },
                        role, { role = it }, tag = "roles_select",
                    )
                    Text(stringResource(R.string.roles_change_body), style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { viewModel.changeRole(user.id, role) { target = null } },
                    enabled = !state.saving,
                    modifier = Modifier.testTag("roles_submit"),
                ) { Text(stringResource(R.string.action_save)) }
            },
            dismissButton = { TextButton(onClick = { target = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

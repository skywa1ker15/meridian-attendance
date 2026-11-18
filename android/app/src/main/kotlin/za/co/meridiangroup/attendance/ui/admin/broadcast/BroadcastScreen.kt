package za.co.meridiangroup.attendance.ui.admin.broadcast

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Campaign
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import za.co.meridiangroup.attendance.R
import za.co.meridiangroup.attendance.core.ApiResult
import za.co.meridiangroup.attendance.data.remote.dto.AdminDepartmentDto
import za.co.meridiangroup.attendance.data.repository.AdminRepository
import za.co.meridiangroup.attendance.ui.admin.AdminDropdown
import za.co.meridiangroup.attendance.ui.admin.AdminMessage
import za.co.meridiangroup.attendance.ui.admin.AdminTitle
import za.co.meridiangroup.attendance.ui.components.PillButton
import za.co.meridiangroup.attendance.ui.theme.MeridianTheme
import za.co.meridiangroup.attendance.ui.theme.Spacing

data class BroadcastUiState(
    val title: String = "",
    val body: String = "",
    val departmentId: String = "",
    val departments: List<AdminDepartmentDto> = emptyList(),
    val sending: Boolean = false,
    val message: String? = null,
)

class BroadcastViewModel(private val repo: AdminRepository) : ViewModel() {
    private val _state = MutableStateFlow(BroadcastUiState())
    val state: StateFlow<BroadcastUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch { repo.departments().getOrNull()?.let { d -> _state.update { it.copy(departments = d.items) } } }
    }

    fun onTitle(v: String) = _state.update { it.copy(title = v) }
    fun onBody(v: String) = _state.update { it.copy(body = v) }
    fun onDepartment(v: String) = _state.update { it.copy(departmentId = v) }
    fun clearMessage() = _state.update { it.copy(message = null) }

    fun send() {
        val s = _state.value
        if (s.title.isBlank() || s.body.isBlank()) {
            _state.update { it.copy(message = "Enter a title and a message.") }
            return
        }
        _state.update { it.copy(sending = true, message = null) }
        viewModelScope.launch {
            when (val r = repo.broadcast(s.title, s.body, s.departmentId)) {
                is ApiResult.Success -> _state.update { it.copy(sending = false, title = "", body = "", message = "Sent to ${r.value.sent} employees.") }
                else -> _state.update { it.copy(sending = false, message = r.userMessage()) }
            }
        }
    }
}

@Composable
fun BroadcastScreen(viewModel: BroadcastViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Spacing.l).testTag("broadcast"),
        verticalArrangement = Arrangement.spacedBy(Spacing.m),
    ) {
        AdminTitle(stringResource(R.string.manage_broadcast), stringResource(R.string.manage_broadcast_body))
        AdminMessage(state.message, viewModel::clearMessage)
        OutlinedTextField(
            state.title, viewModel::onTitle, label = { Text(stringResource(R.string.broadcast_title_label)) }, singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("broadcast_title"),
        )
        OutlinedTextField(
            state.body, viewModel::onBody, label = { Text(stringResource(R.string.broadcast_body_label)) }, minLines = 4,
            modifier = Modifier.fillMaxWidth().testTag("broadcast_body"),
        )
        AdminDropdown(
            stringResource(R.string.profile_department),
            listOf("" to stringResource(R.string.broadcast_everyone)) + state.departments.map { it.id to it.name },
            state.departmentId, viewModel::onDepartment, tag = "broadcast_department",
        )
        PillButton(
            stringResource(R.string.broadcast_send), viewModel::send, Modifier.fillMaxWidth().testTag("broadcast_send"),
            icon = Icons.Outlined.Campaign, loading = state.sending,
        )
        Text(stringResource(R.string.broadcast_note), style = MaterialTheme.typography.bodySmall, color = MeridianTheme.extended.mutedText)
    }
}

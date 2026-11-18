package za.co.meridiangroup.attendance.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import za.co.meridiangroup.attendance.AppGraph
import za.co.meridiangroup.attendance.R
import za.co.meridiangroup.attendance.core.ServerUrlResult
import za.co.meridiangroup.attendance.core.ServerUrls
import za.co.meridiangroup.attendance.data.repository.ConnectionTestResult
import za.co.meridiangroup.attendance.ui.components.PillButton
import za.co.meridiangroup.attendance.ui.components.PillStyle
import za.co.meridiangroup.attendance.ui.theme.MeridianTheme
import za.co.meridiangroup.attendance.ui.theme.Spacing

data class ServerUiState(
    val activeRoot: String = "",
    val open: Boolean = false,
    val input: String = "",
    val error: String? = null,
    val testing: Boolean = false,
    val testMessage: String? = null,
    val testOk: Boolean = false,
    val saving: Boolean = false,
    /** True while a session exists: changing the server signs the user out. */
    val signedIn: Boolean = false,
    val isDefault: Boolean = true,
)

/** Settings → Server: view/change the backend address. Used by the login screen and by Profile. */
class ServerSettingsViewModel(private val graph: AppGraph) : ViewModel() {
    private val repo = graph.serverConfigRepository
    private val _state = MutableStateFlow(ServerUiState(activeRoot = repo.root.value, isDefault = repo.isDefault))
    val state: StateFlow<ServerUiState> = _state.asStateFlow()

    val allowCleartext: Boolean get() = repo.allowCleartext

    fun open() = _state.update {
        it.copy(open = true, input = repo.root.value, error = null, testMessage = null, testOk = false, signedIn = graph.tokenStore.isLoggedIn)
    }

    fun close() = _state.update { it.copy(open = false, saving = false, testing = false) }
    fun onInput(v: String) = _state.update { it.copy(input = v, error = null, testMessage = null, testOk = false) }

    private fun validated(): String? = when (val r = repo.validate(_state.value.input)) {
        is ServerUrlResult.Valid -> r.root
        is ServerUrlResult.Invalid -> { _state.update { it.copy(error = r.message) }; null }
    }

    fun test() {
        val root = validated() ?: return
        _state.update { it.copy(testing = true, testMessage = null, testOk = false) }
        viewModelScope.launch {
            val result = repo.testConnection(root)
            _state.update {
                when (result) {
                    is ConnectionTestResult.Ok ->
                        it.copy(testing = false, testOk = true, testMessage = "Connected to ${ServerUrls.display(root)}" + (result.version?.let { v -> " (version $v)" } ?: ""))
                    is ConnectionTestResult.Failed -> it.copy(testing = false, testOk = false, testMessage = result.message)
                }
            }
        }
    }

    /** Saves the address. Any active session is signed out first, because tokens belong to one server. */
    fun save(onSaved: (String) -> Unit) {
        val root = validated() ?: return
        _state.update { it.copy(saving = true) }
        viewModelScope.launch {
            if (graph.tokenStore.isLoggedIn) graph.authRepository.logout()
            repo.apply(root)
            _state.update { it.copy(saving = false, open = false, activeRoot = root, isDefault = repo.isDefault) }
            onSaved(root)
        }
    }

    fun useDefault() = _state.update { it.copy(input = repo.default, error = null, testMessage = null, testOk = false) }
}

/** Small "Server: host" row with a gear, shown on the login screen and in Profile. */
@Composable
fun ServerRow(
    viewModel: ServerSettingsViewModel,
    modifier: Modifier = Modifier,
    contentColor: androidx.compose.ui.graphics.Color? = null,
    onSaved: (String) -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        Icon(
            Icons.Outlined.Dns, contentDescription = null,
            modifier = Modifier.size(16.dp), tint = contentColor ?: MeridianTheme.extended.mutedText,
        )
        TextButton(onClick = viewModel::open, modifier = Modifier.testTag("server_row")) {
            Text(
                stringResource(R.string.server_row, ServerUrls.display(state.activeRoot)),
                style = MaterialTheme.typography.bodySmall,
                color = contentColor ?: MaterialTheme.colorScheme.primary,
            )
        }
    }
    ServerDialog(viewModel, onSaved)
}

@Composable
fun ServerDialog(viewModel: ServerSettingsViewModel, onSaved: (String) -> Unit = {}) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    if (!state.open) return
    AlertDialog(
        onDismissRequest = viewModel::close,
        title = { Text(stringResource(R.string.server_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                Text(stringResource(if (viewModel.allowCleartext) R.string.server_body_debug else R.string.server_body), style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = state.input,
                    onValueChange = viewModel::onInput,
                    label = { Text(stringResource(R.string.server_address)) },
                    singleLine = true,
                    isError = state.error != null,
                    supportingText = { Text(state.error ?: stringResource(R.string.server_hint)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth().testTag("server_input"),
                )
                state.testMessage?.let {
                    Text(
                        it, style = MaterialTheme.typography.bodySmall,
                        color = if (state.testOk) MeridianTheme.extended.success else MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag("server_test_result"),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                    PillButton(stringResource(R.string.server_test), viewModel::test, style = PillStyle.OUTLINE, loading = state.testing)
                    PillButton(stringResource(R.string.server_default), viewModel::useDefault, style = PillStyle.GHOST)
                }
                if (state.signedIn) Text(stringResource(R.string.server_logout_warning), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            TextButton(onClick = { viewModel.save(onSaved) }, enabled = !state.saving, modifier = Modifier.testTag("server_save")) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = { TextButton(onClick = viewModel::close) { Text(stringResource(R.string.action_cancel)) } },
    )
}

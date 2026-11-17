package za.co.meridiangroup.attendance.ui.profile

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import za.co.meridiangroup.attendance.AppGraph
import za.co.meridiangroup.attendance.BuildConfig
import za.co.meridiangroup.attendance.R
import za.co.meridiangroup.attendance.core.ApiResult
import za.co.meridiangroup.attendance.core.StatusTone
import za.co.meridiangroup.attendance.core.StatusTones
import za.co.meridiangroup.attendance.core.Validation
import za.co.meridiangroup.attendance.data.remote.dto.DeviceDto
import za.co.meridiangroup.attendance.data.remote.dto.UserDto
import za.co.meridiangroup.attendance.ui.components.Banner
import za.co.meridiangroup.attendance.ui.components.GlassCard
import za.co.meridiangroup.attendance.ui.components.KeyValueRow
import za.co.meridiangroup.attendance.ui.components.PillButton
import za.co.meridiangroup.attendance.ui.components.PillStyle
import za.co.meridiangroup.attendance.ui.components.SectionHeader
import za.co.meridiangroup.attendance.ui.components.StatusChip
import za.co.meridiangroup.attendance.ui.onboarding.PermissionSummary
import za.co.meridiangroup.attendance.ui.theme.Spacing

class ProfileViewModel(private val graph: AppGraph) : ViewModel() {
    val user: StateFlow<UserDto?> = graph.prefs.userFlow.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val pushEnabled: StateFlow<Boolean> = graph.prefs.pushEnabledFlow.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)
    val device = MutableStateFlow<DeviceDto?>(null)
    val permissions = MutableStateFlow(graph.permissionChecker.snapshot())
    val message = MutableStateFlow<String?>(null)
    val savingPhone = MutableStateFlow(false)
    val installationId: String = graph.prefs.installationId()
    val apiHost: String = graph.apiBaseUrl
    val pushAvailable: Boolean get() = graph.fcmTokenManager.firebaseAvailable

    init {
        viewModelScope.launch {
            graph.authRepository.refreshMe()
            device.value = graph.authRepository.myDevice().getOrNull()
        }
    }

    fun recheckPermissions() { permissions.value = graph.permissionChecker.snapshot() }

    fun setPush(enabled: Boolean) {
        viewModelScope.launch {
            val r = graph.authRepository.setPushEnabled(enabled)
            message.value = if (r is ApiResult.Success) null else r.userMessage()
        }
    }

    fun updatePhone(phone: String, onDone: () -> Unit) {
        Validation.phone(phone)?.let { message.value = it; return }
        viewModelScope.launch {
            savingPhone.value = true
            val r = graph.authRepository.updatePhone(phone)
            savingPhone.value = false
            message.value = if (r is ApiResult.Success) "Phone number updated." else r.userMessage()
            if (r is ApiResult.Success) onDone()
        }
    }

    fun logout(onLoggedOut: () -> Unit) {
        viewModelScope.launch { graph.authRepository.logout(); onLoggedOut() }
    }
}

@Composable
fun ProfileScreen(
    viewModel: ProfileViewModel,
    onChangePassword: () -> Unit,
    onRequestCorrection: () -> Unit,
    onRerunOnboarding: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onLoggedOut: () -> Unit,
) {
    val user by viewModel.user.collectAsStateWithLifecycle()
    val push by viewModel.pushEnabled.collectAsStateWithLifecycle()
    val device by viewModel.device.collectAsStateWithLifecycle()
    val permissions by viewModel.permissions.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val savingPhone by viewModel.savingPhone.collectAsStateWithLifecycle()
    var editPhone by remember { mutableStateOf(false) }
    var phoneInput by remember { mutableStateOf("") }
    var showPrivacy by remember { mutableStateOf(false) }
    var confirmLogout by remember { mutableStateOf(false) }

    LifecycleResumeEffect(Unit) {
        viewModel.recheckPermissions()
        onPauseOrDispose { }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Spacing.l).testTag("profile"),
        verticalArrangement = Arrangement.spacedBy(Spacing.m),
    ) {
        Text(stringResource(R.string.profile_title), style = MaterialTheme.typography.headlineSmall)
        message?.let { Banner(it, "", StatusTone.NEUTRAL, actionLabel = stringResource(R.string.action_ok), onAction = { viewModel.message.value = null }) }

        GlassCard {
            Text(user?.fullName ?: "—", style = MaterialTheme.typography.titleLarge)
            KeyValueRow(stringResource(R.string.profile_employee_number), user?.employeeNumber ?: "—")
            KeyValueRow(stringResource(R.string.profile_email), user?.email ?: "—")
            KeyValueRow(stringResource(R.string.profile_phone), user?.phone ?: "—")
            KeyValueRow(stringResource(R.string.profile_department), user?.department?.name ?: "—")
            KeyValueRow(stringResource(R.string.profile_role), StatusTones.label(user?.role))
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                PillButton(stringResource(R.string.profile_edit_phone), { phoneInput = user?.phone.orEmpty(); editPhone = true }, style = PillStyle.OUTLINE)
                PillButton(stringResource(R.string.profile_request_correction), onRequestCorrection, style = PillStyle.GHOST)
            }
        }

        SectionHeader(stringResource(R.string.profile_settings))
        GlassCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.profile_push), style = MaterialTheme.typography.titleSmall)
                    Text(
                        stringResource(if (viewModel.pushAvailable) R.string.profile_push_body else R.string.profile_push_unavailable),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(checked = push, onCheckedChange = viewModel::setPush, modifier = Modifier.testTag("push_switch"))
            }
            PillButton(stringResource(R.string.profile_change_password), onChangePassword, Modifier.fillMaxWidth(), style = PillStyle.OUTLINE)
        }

        SectionHeader(stringResource(R.string.profile_permissions))
        GlassCard {
            PermissionSummary(permissions)
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s), modifier = Modifier.padding(top = Spacing.s)) {
                PillButton(stringResource(R.string.profile_rerun_onboarding), onRerunOnboarding, style = PillStyle.OUTLINE)
                PillButton(stringResource(R.string.ob_open_settings), onOpenAppSettings, style = PillStyle.GHOST)
            }
        }

        SectionHeader(stringResource(R.string.profile_device))
        GlassCard {
            KeyValueRow(stringResource(R.string.profile_device_model), "${Build.MANUFACTURER} ${Build.MODEL}")
            KeyValueRow(stringResource(R.string.profile_android), "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            KeyValueRow(stringResource(R.string.profile_installation_id), viewModel.installationId.take(8) + "…")
            device?.let { d ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.profile_device_status), style = MaterialTheme.typography.bodyMedium)
                    StatusChip(d.status)
                }
            }
            KeyValueRow(stringResource(R.string.profile_app_version), "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            KeyValueRow(stringResource(R.string.profile_api_host), android.net.Uri.parse(viewModel.apiHost).host ?: viewModel.apiHost)
        }

        PillButton(stringResource(R.string.profile_privacy), { showPrivacy = true }, Modifier.fillMaxWidth(), style = PillStyle.GHOST)
        PillButton(stringResource(R.string.profile_logout), { confirmLogout = true }, Modifier.fillMaxWidth().testTag("logout"), style = PillStyle.DANGER, icon = Icons.AutoMirrored.Outlined.Logout)
    }

    if (editPhone) {
        AlertDialog(
            onDismissRequest = { editPhone = false },
            title = { Text(stringResource(R.string.profile_edit_phone)) },
            text = { OutlinedTextField(phoneInput, { phoneInput = it }, label = { Text(stringResource(R.string.profile_phone)) }, singleLine = true) },
            confirmButton = { TextButton(onClick = { viewModel.updatePhone(phoneInput) { editPhone = false } }, enabled = !savingPhone) { Text(stringResource(R.string.action_save)) } },
            dismissButton = { TextButton(onClick = { editPhone = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
    if (showPrivacy) {
        AlertDialog(
            onDismissRequest = { showPrivacy = false },
            title = { Text(stringResource(R.string.privacy_title)) },
            text = { Column(Modifier.verticalScroll(rememberScrollState())) { Text(stringResource(R.string.privacy_body)) } },
            confirmButton = { TextButton(onClick = { showPrivacy = false }) { Text(stringResource(R.string.action_ok)) } },
        )
    }
    if (confirmLogout) {
        AlertDialog(
            onDismissRequest = { confirmLogout = false },
            title = { Text(stringResource(R.string.logout_title)) },
            text = { Text(stringResource(R.string.logout_body)) },
            confirmButton = { TextButton(onClick = { confirmLogout = false; viewModel.logout(onLoggedOut) }, modifier = Modifier.testTag("logout_confirm")) { Text(stringResource(R.string.profile_logout)) } },
            dismissButton = { TextButton(onClick = { confirmLogout = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

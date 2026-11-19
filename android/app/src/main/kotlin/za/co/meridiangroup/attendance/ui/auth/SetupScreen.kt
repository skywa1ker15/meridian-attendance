package za.co.meridiangroup.attendance.ui.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AdminPanelSettings
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
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
import za.co.meridiangroup.attendance.core.ApiResult
import za.co.meridiangroup.attendance.core.PasswordPolicy
import za.co.meridiangroup.attendance.core.StatusTone
import za.co.meridiangroup.attendance.core.Validation
import za.co.meridiangroup.attendance.data.remote.dto.FirstAdminRequestDto
import za.co.meridiangroup.attendance.ui.components.Banner
import za.co.meridiangroup.attendance.ui.components.GlassCard
import za.co.meridiangroup.attendance.ui.components.PillButton
import za.co.meridiangroup.attendance.ui.theme.MeridianColors
import za.co.meridiangroup.attendance.ui.theme.Spacing

data class SetupUiState(
    val setupCodeRequired: Boolean = true,
    val setupCode: String = "",
    val employeeNumber: String = "",
    val firstName: String = "",
    val lastName: String = "",
    val email: String = "",
    val password: String = "",
    val confirm: String = "",
    val fieldErrors: Map<String, String> = emptyMap(),
    val issues: List<String> = emptyList(),
    val formError: String? = null,
    val saving: Boolean = false,
    /** Set on 409 setup_complete: the caller returns to the login screen with this message. */
    val closed: String? = null,
    val done: Boolean = false,
) {
    val checklist: List<PasswordPolicy.Check>
        get() = PasswordPolicy.check(
            password,
            listOf(employeeNumber, email, firstName, lastName).filter { it.isNotBlank() },
            confirm,
        )
}

/**
 * docs/API.md §0 — creates the first administrator on a brand-new installation and signs them in.
 * On success the app lands on the Manage home and offers to set the workplace up.
 */
class SetupViewModel(private val graph: AppGraph, setupCodeRequired: Boolean) : ViewModel() {
    private val _state = MutableStateFlow(SetupUiState(setupCodeRequired = setupCodeRequired))
    val state: StateFlow<SetupUiState> = _state.asStateFlow()

    fun onSetupCode(v: String) = update { it.copy(setupCode = v) }
    fun onEmployeeNumber(v: String) = update { it.copy(employeeNumber = v) }
    fun onFirstName(v: String) = update { it.copy(firstName = v) }
    fun onLastName(v: String) = update { it.copy(lastName = v) }
    fun onEmail(v: String) = update { it.copy(email = v) }
    fun onPassword(v: String) = update { it.copy(password = v, issues = emptyList()) }
    fun onConfirm(v: String) = update { it.copy(confirm = v, issues = emptyList()) }

    private fun update(block: (SetupUiState) -> SetupUiState) =
        _state.update { block(it).copy(fieldErrors = emptyMap(), formError = null) }

    fun submit() {
        val s = _state.value
        val errors = linkedMapOf<String, String>()
        if (s.setupCodeRequired && s.setupCode.isBlank()) errors["setup_code"] = "Enter the setup code from your IT team."
        Validation.identifier(s.employeeNumber)?.let { errors["employee_number"] = it }
        if (s.firstName.isBlank()) errors["first_name"] = "Enter your first name."
        if (s.lastName.isBlank()) errors["last_name"] = "Enter your last name."
        if (Validation.identifier(s.email) != null || !s.email.contains('@')) errors["email"] = "Enter a valid email address."
        val passwordOk = s.checklist.all { it.satisfied }
        if (errors.isNotEmpty() || !passwordOk) {
            _state.update { it.copy(fieldErrors = errors) }
            return
        }
        _state.update { it.copy(saving = true, formError = null) }
        viewModelScope.launch {
            val body = FirstAdminRequestDto(
                setupCode = s.setupCode.trim().ifBlank { null },
                employeeNumber = s.employeeNumber.trim(),
                firstName = s.firstName.trim(),
                lastName = s.lastName.trim(),
                email = s.email.trim(),
                password = s.password,
            )
            when (val r = graph.authRepository.createFirstAdmin(body)) {
                is ApiResult.Success -> {
                    graph.offerWorkplaceSetup.value = true
                    graph.onSessionActive()
                    _state.update { it.copy(saving = false, done = true, password = "", confirm = "") }
                }
                is ApiResult.Failure -> {
                    val f = r.failure
                    _state.update {
                        when {
                            f.httpStatus == 409 && f.code == "setup_complete" ->
                                it.copy(saving = false, closed = "This system already has an administrator. Please sign in instead.")
                            f.code == "invalid_setup_code" -> it.copy(saving = false, fieldErrors = mapOf("setup_code" to "That setup code is not correct."))
                            f.code == "setup_disabled" ->
                                it.copy(saving = false, formError = "First-time setup is not enabled on this server. Ask your IT team to set FIRST_ADMIN_SETUP_CODE.")
                            f.httpStatus == 422 || f.httpStatus == 409 ->
                                it.copy(saving = false, fieldErrors = f.fieldErrors, formError = r.userMessage())
                            else -> it.copy(saving = false, formError = r.userMessage())
                        }
                    }
                }
                else -> _state.update { it.copy(saving = false, formError = r.userMessage()) }
            }
        }
    }
}

@Composable
fun SetupScreen(viewModel: SetupViewModel, onBackToLogin: (String?) -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showPassword by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(state.closed) { state.closed?.let(onBackToLogin) }

    Box(
        Modifier.fillMaxSize()
            .background(Brush.verticalGradient(listOf(MeridianColors.Navy700, MeridianColors.Navy900)))
            .systemBarsPadding()
            .imePadding(),
    ) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Spacing.xl).testTag("setup"),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(stringResource(R.string.setup_title), style = MaterialTheme.typography.headlineSmall, color = MeridianColors.White)
            Text(
                stringResource(R.string.setup_body), style = MaterialTheme.typography.bodyMedium,
                color = MeridianColors.TextMutedDark, textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(Spacing.l))
            GlassCard(Modifier.fillMaxWidth()) {
                if (state.setupCodeRequired) {
                    SetupField(state.setupCode, viewModel::onSetupCode, stringResource(R.string.setup_code), "setup_code",
                        error = state.fieldErrors["setup_code"], hint = stringResource(R.string.setup_code_hint))
                }
                SetupField(state.employeeNumber, viewModel::onEmployeeNumber, stringResource(R.string.setup_employee_number), "setup_number",
                    error = state.fieldErrors["employee_number"], hint = stringResource(R.string.login_identifier_hint))
                SetupField(state.firstName, viewModel::onFirstName, stringResource(R.string.setup_first_name), "setup_first", error = state.fieldErrors["first_name"])
                SetupField(state.lastName, viewModel::onLastName, stringResource(R.string.setup_last_name), "setup_last", error = state.fieldErrors["last_name"])
                SetupField(state.email, viewModel::onEmail, stringResource(R.string.profile_email), "setup_email",
                    error = state.fieldErrors["email"], keyboardType = KeyboardType.Email)
                PasswordInput(
                    value = state.password, onChange = viewModel::onPassword,
                    label = stringResource(R.string.change_password_new), tag = "setup_password",
                    show = showPassword, onToggle = { showPassword = !showPassword },
                )
                PasswordInput(
                    value = state.confirm, onChange = viewModel::onConfirm,
                    label = stringResource(R.string.change_password_confirm), tag = "setup_confirm",
                    show = showPassword, onToggle = { showPassword = !showPassword }, imeAction = ImeAction.Done,
                )
                Spacer(Modifier.height(Spacing.s))
                PasswordChecklist(state.checklist)
                state.formError?.let {
                    Spacer(Modifier.height(Spacing.s))
                    Banner(stringResource(R.string.setup_failed), it, StatusTone.ERROR, modifier = Modifier.testTag("setup_error"))
                }
                Spacer(Modifier.height(Spacing.m))
                PillButton(
                    stringResource(R.string.setup_submit), viewModel::submit, Modifier.fillMaxWidth().testTag("setup_submit"),
                    icon = Icons.Outlined.AdminPanelSettings, loading = state.saving,
                )
                TextButton(onClick = { onBackToLogin(null) }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                    Text(stringResource(R.string.setup_back_to_login))
                }
            }
            Spacer(Modifier.height(Spacing.l))
            Text(
                stringResource(R.string.setup_note), style = MaterialTheme.typography.bodySmall,
                color = MeridianColors.TextMutedDark, textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun SetupField(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    tag: String,
    error: String? = null,
    hint: String? = null,
    password: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Next,
) {
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) }, singleLine = true,
        isError = error != null,
        supportingText = (error ?: hint)?.let { { Text(it) } },
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = if (password) KeyboardType.Password else keyboardType, imeAction = imeAction),
        modifier = Modifier.fillMaxWidth().testTag(tag),
    )
}

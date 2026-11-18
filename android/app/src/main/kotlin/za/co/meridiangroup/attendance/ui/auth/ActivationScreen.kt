package za.co.meridiangroup.attendance.ui.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.unit.dp
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
import za.co.meridiangroup.attendance.core.JohannesburgTime
import za.co.meridiangroup.attendance.core.PasswordPolicy
import za.co.meridiangroup.attendance.core.PhoneNumbers
import za.co.meridiangroup.attendance.core.ServerPermissions
import za.co.meridiangroup.attendance.core.StatusTone
import za.co.meridiangroup.attendance.data.remote.dto.InvitationDto
import za.co.meridiangroup.attendance.ui.components.Banner
import za.co.meridiangroup.attendance.ui.components.GlassCard
import za.co.meridiangroup.attendance.ui.components.KeyValueRow
import za.co.meridiangroup.attendance.ui.components.PillButton
import za.co.meridiangroup.attendance.ui.theme.MeridianColors
import za.co.meridiangroup.attendance.ui.theme.MeridianTheme
import za.co.meridiangroup.attendance.ui.theme.Spacing

data class ActivationUiState(
    val loading: Boolean = true,
    val invitation: InvitationDto? = null,
    /** The link is invalid, already used or expired: nothing can be done but ask for a new one. */
    val invalidLink: Boolean = false,
    val loadError: String? = null,
    val phone: String = "",
    val password: String = "",
    val confirm: String = "",
    val phoneError: String? = null,
    val passwordError: String? = null,
    val formError: String? = null,
    val submitting: Boolean = false,
    val done: Boolean = false,
) {
    val checklist: List<PasswordPolicy.Check>
        get() = PasswordPolicy.check(
            password,
            // The server checks all four (backend/app/services/invitations.py#accept).
            listOfNotNull(invitation?.employeeNumber, invitation?.email, invitation?.firstName, invitation?.lastName),
            confirm,
        )
}

/**
 * Invitation acceptance (docs/API.md §1): HR invites an employee, the employee opens the emailed link
 * and sets their own password and phone number. On success the app is signed in exactly as after login.
 */
class ActivationViewModel(private val graph: AppGraph, private val token: String) : ViewModel() {
    private val auth = graph.authRepository
    private val _state = MutableStateFlow(ActivationUiState())
    val state: StateFlow<ActivationUiState> = _state.asStateFlow()

    init { load() }

    fun load() {
        _state.update { it.copy(loading = true, loadError = null, invalidLink = false) }
        viewModelScope.launch {
            when (val r = auth.invitation(token)) {
                is ApiResult.Success -> _state.update {
                    it.copy(loading = false, invitation = r.value, phone = it.phone.ifBlank { PhoneNumbers.display(r.value.phone).orEmpty() })
                }
                is ApiResult.Failure ->
                    if (r.failure.code == "token_invalid" || r.failure.httpStatus == 404) {
                        _state.update { it.copy(loading = false, invalidLink = true) }
                    } else {
                        _state.update { it.copy(loading = false, loadError = r.userMessage()) }
                    }
                else -> _state.update { it.copy(loading = false, loadError = r.userMessage()) }
            }
        }
    }

    fun onPhone(v: String) = _state.update { it.copy(phone = v, phoneError = null, formError = null) }
    fun onPassword(v: String) = _state.update { it.copy(password = v, passwordError = null, formError = null) }
    fun onConfirm(v: String) = _state.update { it.copy(confirm = v, passwordError = null, formError = null) }

    fun submit() {
        val s = _state.value
        if (s.submitting) return
        val phoneError = PhoneNumbers.error(s.phone, required = true)
        val passwordOk = s.checklist.all { it.satisfied }
        if (phoneError != null || !passwordOk) {
            _state.update { it.copy(phoneError = phoneError, passwordError = if (passwordOk) null else "") }
            return
        }
        val phone = PhoneNumbers.normalise(s.phone) ?: return
        _state.update { it.copy(submitting = true, formError = null) }
        viewModelScope.launch {
            when (val r = auth.acceptInvitation(token, phone, s.password)) {
                is ApiResult.Success -> {
                    // Administrators land on Manage home; employees go through the permissions onboarding.
                    if (ServerPermissions.hasAdminSection(r.value.user.permissions)) graph.prefs.setOnboardingDone(true)
                    graph.onSessionActive()
                    _state.update { it.copy(submitting = false, done = true, password = "", confirm = "") }
                }
                is ApiResult.Failure -> {
                    val f = r.failure
                    _state.update {
                        when {
                            f.code == "token_invalid" -> it.copy(submitting = false, invalidLink = true)
                            f.httpStatus == 422 -> it.copy(
                                submitting = false,
                                phoneError = f.fieldErrors["phone"]?.let { _ -> PhoneNumbers.HELP },
                                passwordError = (f.fieldErrors["password"] ?: f.fieldErrors["new_password"])
                                    ?.let(PasswordPolicy::messageForCode),
                                formError = if (f.fieldErrors.isEmpty()) r.userMessage() else null,
                            )
                            else -> it.copy(submitting = false, formError = r.userMessage())
                        }
                    }
                }
                else -> _state.update { it.copy(submitting = false, formError = r.userMessage()) }
            }
        }
    }
}

@Composable
fun ActivationScreen(viewModel: ActivationViewModel, onBackToLogin: () -> Unit, onActivated: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showPassword by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(state.done) { if (state.done) onActivated() }

    AuthBackdrop(testTag = "activation") {
        when {
            state.loading -> {
                CircularProgressIndicator(color = MeridianColors.White, modifier = Modifier.testTag("activation_loading"))
                Spacer(Modifier.height(Spacing.m))
                Text(stringResource(R.string.activation_loading), color = MeridianColors.TextMutedDark)
            }
            state.invalidLink || state.loadError != null -> {
                GlassCard(Modifier.fillMaxWidth().testTag("activation_invalid")) {
                    Text(
                        stringResource(if (state.invalidLink) R.string.activation_invalid_title else R.string.activation_failed),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        state.loadError ?: stringResource(R.string.activation_invalid_body),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(Spacing.m))
                    if (!state.invalidLink) {
                        PillButton(stringResource(R.string.action_retry), viewModel::load, Modifier.fillMaxWidth().testTag("activation_retry"))
                    }
                    TextButton(onClick = onBackToLogin, modifier = Modifier.align(Alignment.CenterHorizontally).testTag("activation_back")) {
                        Text(stringResource(R.string.activation_back_to_login))
                    }
                }
            }
            else -> {
                val invitation = state.invitation ?: return@AuthBackdrop
                Text(
                    stringResource(R.string.activation_title, invitation.firstName.ifBlank { invitation.fullName }),
                    style = MaterialTheme.typography.headlineSmall, color = MeridianColors.White, textAlign = TextAlign.Center,
                )
                Text(
                    stringResource(R.string.activation_subtitle), style = MaterialTheme.typography.bodyMedium,
                    color = MeridianColors.TextMutedDark, textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(Spacing.l))
                GlassCard(Modifier.fillMaxWidth().testTag("activation_details")) {
                    KeyValueRow(stringResource(R.string.activation_name), invitation.fullName)
                    KeyValueRow(stringResource(R.string.profile_employee_number), invitation.employeeNumber)
                    KeyValueRow(stringResource(R.string.profile_email), invitation.email)
                    KeyValueRow(stringResource(R.string.profile_department), invitation.department?.name ?: "—")
                    invitation.expiresAt?.let {
                        Text(
                            stringResource(R.string.activation_expires, JohannesburgTime.dateTime(it)),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                Spacer(Modifier.height(Spacing.m))
                GlassCard(Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = state.phone,
                        onValueChange = viewModel::onPhone,
                        label = { Text(stringResource(R.string.profile_phone)) },
                        singleLine = true,
                        isError = state.phoneError != null,
                        supportingText = { Text(state.phoneError ?: stringResource(R.string.activation_phone_hint)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone, imeAction = ImeAction.Next),
                        modifier = Modifier.fillMaxWidth().testTag("activation_phone"),
                    )
                    PasswordInput(
                        value = state.password, onChange = viewModel::onPassword,
                        label = stringResource(R.string.change_password_new), tag = "activation_password",
                        show = showPassword, onToggle = { showPassword = !showPassword }, imeAction = ImeAction.Next,
                        error = state.passwordError?.ifBlank { null },
                    )
                    PasswordInput(
                        value = state.confirm, onChange = viewModel::onConfirm,
                        label = stringResource(R.string.change_password_confirm), tag = "activation_confirm",
                        show = showPassword, onToggle = { showPassword = !showPassword }, imeAction = ImeAction.Done,
                    )
                    Spacer(Modifier.height(Spacing.s))
                    PasswordChecklist(state.checklist)
                    state.formError?.let {
                        Spacer(Modifier.height(Spacing.s))
                        Banner(stringResource(R.string.activation_failed), it, StatusTone.ERROR, modifier = Modifier.testTag("activation_error"))
                    }
                    Spacer(Modifier.height(Spacing.m))
                    PillButton(
                        stringResource(R.string.activation_submit), viewModel::submit,
                        Modifier.fillMaxWidth().testTag("activation_submit"), loading = state.submitting,
                    )
                    TextButton(onClick = onBackToLogin, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                        Text(stringResource(R.string.activation_back_to_login))
                    }
                }
            }
        }
    }
}

/** The navy gradient the login and first-run setup screens use, with a centred scrolling column. */
@Composable
fun AuthBackdrop(testTag: String, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Box(
        Modifier.fillMaxSize()
            .background(Brush.verticalGradient(listOf(MeridianColors.Navy700, MeridianColors.Navy900)))
            .systemBarsPadding()
            .imePadding(),
    ) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Spacing.xl).testTag(testTag),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
            content = content,
        )
    }
}

/** The live password-policy checklist shared by activation and password reset. */
@Composable
fun PasswordChecklist(checks: List<PasswordPolicy.Check>) {
    Text(stringResource(R.string.policy_title), style = MaterialTheme.typography.bodySmall)
    checks.forEach { check ->
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.testTag("policy_${check.rule.name.lowercase()}")) {
            Icon(
                if (check.satisfied) Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = if (check.satisfied) MeridianTheme.extended.success else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.size(Spacing.s))
            Text(
                stringResource(
                    when (check.rule) {
                        PasswordPolicy.Rule.LENGTH -> R.string.policy_rule_length
                        PasswordPolicy.Rule.NOT_IDENTIFIER -> R.string.policy_rule_identifier
                        PasswordPolicy.Rule.NOT_COMMON -> R.string.policy_rule_common
                        PasswordPolicy.Rule.MATCH -> R.string.policy_rule_match
                    },
                    PasswordPolicy.MIN_LENGTH,
                ),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/** Password field with a show/hide toggle. */
@Composable
fun PasswordInput(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    tag: String,
    show: Boolean,
    onToggle: () -> Unit,
    imeAction: ImeAction = ImeAction.Next,
    error: String? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        isError = error != null,
        supportingText = error?.let { { Text(it) } },
        visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            IconButton(onClick = onToggle) {
                Icon(
                    if (show) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                    contentDescription = stringResource(if (show) R.string.login_hide_password else R.string.login_show_password),
                )
            }
        },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = imeAction),
        modifier = Modifier.fillMaxWidth().testTag(tag),
    )
}

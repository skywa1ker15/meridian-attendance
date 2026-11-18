package za.co.meridiangroup.attendance.ui.auth

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
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
import za.co.meridiangroup.attendance.ui.components.Banner
import za.co.meridiangroup.attendance.ui.components.GlassCard
import za.co.meridiangroup.attendance.ui.components.PillButton
import za.co.meridiangroup.attendance.ui.theme.MeridianColors
import za.co.meridiangroup.attendance.ui.theme.Spacing

data class ResetPasswordUiState(
    val password: String = "",
    val confirm: String = "",
    val passwordError: String? = null,
    val formError: String? = null,
    /** The reset link has expired or was already used. */
    val invalidLink: Boolean = false,
    val submitting: Boolean = false,
    val done: Boolean = false,
) {
    val checklist: List<PasswordPolicy.Check> get() = PasswordPolicy.check(password, emptyList(), confirm)
}

/**
 * Password reset from an emailed link (docs/API.md §1 `POST /auth/password/reset`). The server revokes
 * every session, so the user signs in again afterwards — [AuthRepository.resetPassword] drops any local one.
 */
class ResetPasswordViewModel(graph: AppGraph, private val token: String) : ViewModel() {
    private val auth = graph.authRepository
    private val _state = MutableStateFlow(ResetPasswordUiState())
    val state: StateFlow<ResetPasswordUiState> = _state.asStateFlow()

    fun onPassword(v: String) = _state.update { it.copy(password = v, passwordError = null, formError = null) }
    fun onConfirm(v: String) = _state.update { it.copy(confirm = v, passwordError = null, formError = null) }

    fun submit() {
        val s = _state.value
        if (s.submitting) return
        if (!s.checklist.all { it.satisfied }) { _state.update { it.copy(passwordError = "") }; return }
        _state.update { it.copy(submitting = true, formError = null) }
        viewModelScope.launch {
            when (val r = auth.resetPassword(token, s.password)) {
                is ApiResult.Success -> _state.update { it.copy(submitting = false, done = true, password = "", confirm = "") }
                is ApiResult.Failure -> {
                    val f = r.failure
                    _state.update {
                        when {
                            f.code == "token_invalid" -> it.copy(submitting = false, invalidLink = true)
                            f.httpStatus == 422 -> it.copy(
                                submitting = false,
                                passwordError = f.fieldErrors["new_password"]?.let(PasswordPolicy::messageForCode)
                                    ?: f.fieldErrors["password"]?.let(PasswordPolicy::messageForCode),
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

/**
 * [onRequestNewLink] returns to the login screen with the forgot-password dialog open;
 * [onBackToLogin] just closes the link.
 */
@Composable
fun ResetPasswordScreen(
    viewModel: ResetPasswordViewModel,
    onBackToLogin: () -> Unit,
    onRequestNewLink: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showPassword by rememberSaveable { mutableStateOf(false) }

    AuthBackdrop(testTag = "reset") {
        when {
            state.invalidLink -> GlassCard(Modifier.fillMaxWidth().testTag("reset_invalid")) {
                Text(stringResource(R.string.reset_invalid_title), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.reset_invalid_body), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(Spacing.m))
                PillButton(
                    stringResource(R.string.reset_request_new), onRequestNewLink,
                    Modifier.fillMaxWidth().testTag("reset_request_new"),
                )
                TextButton(onClick = onBackToLogin, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                    Text(stringResource(R.string.activation_back_to_login))
                }
            }
            state.done -> GlassCard(Modifier.fillMaxWidth().testTag("reset_done")) {
                Text(stringResource(R.string.reset_done_title), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.reset_done_body), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(Spacing.m))
                PillButton(
                    stringResource(R.string.reset_done_action), onBackToLogin,
                    Modifier.fillMaxWidth().testTag("reset_done_action"),
                )
            }
            else -> {
                Text(stringResource(R.string.reset_title), style = MaterialTheme.typography.headlineSmall, color = MeridianColors.White)
                Text(
                    stringResource(R.string.reset_body), style = MaterialTheme.typography.bodyMedium,
                    color = MeridianColors.TextMutedDark, textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(Spacing.l))
                GlassCard(Modifier.fillMaxWidth()) {
                    PasswordInput(
                        value = state.password, onChange = viewModel::onPassword,
                        label = stringResource(R.string.change_password_new), tag = "reset_password",
                        show = showPassword, onToggle = { showPassword = !showPassword },
                        error = state.passwordError?.ifBlank { null },
                    )
                    PasswordInput(
                        value = state.confirm, onChange = viewModel::onConfirm,
                        label = stringResource(R.string.change_password_confirm), tag = "reset_confirm",
                        show = showPassword, onToggle = { showPassword = !showPassword }, imeAction = ImeAction.Done,
                    )
                    Spacer(Modifier.height(Spacing.s))
                    PasswordChecklist(state.checklist)
                    state.formError?.let {
                        Spacer(Modifier.height(Spacing.s))
                        Banner(stringResource(R.string.reset_failed), it, StatusTone.ERROR, modifier = Modifier.testTag("reset_error"))
                    }
                    Spacer(Modifier.height(Spacing.m))
                    PillButton(
                        stringResource(R.string.reset_submit), viewModel::submit,
                        Modifier.fillMaxWidth().testTag("reset_submit"), loading = state.submitting,
                    )
                    TextButton(onClick = onBackToLogin, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                        Text(stringResource(R.string.activation_back_to_login))
                    }
                }
            }
        }
    }
}

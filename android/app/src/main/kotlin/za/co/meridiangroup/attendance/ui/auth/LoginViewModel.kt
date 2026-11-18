package za.co.meridiangroup.attendance.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import za.co.meridiangroup.attendance.core.ApiResult
import za.co.meridiangroup.attendance.core.Validation
import za.co.meridiangroup.attendance.data.repository.AuthRepository

data class LoginUiState(
    val identifier: String = "",
    val password: String = "",
    val identifierError: String? = null,
    val passwordError: String? = null,
    val formError: String? = null,
    val loading: Boolean = false,
    val forgotOpen: Boolean = false,
    val forgotIdentifier: String = "",
    val forgotError: String? = null,
    val forgotSending: Boolean = false,
    val forgotSent: Boolean = false,
    /** Set when login succeeded; the nav host moves on. */
    val loggedIn: Boolean = false,
    /** docs/API.md §0: this server has no administrator yet — offer first-run setup. */
    val needsFirstAdmin: Boolean = false,
    val setupCodeRequired: Boolean = true,
    /** One-off notice, e.g. after a 409 setup_complete or a server change. */
    val notice: String? = null,
)

class LoginViewModel(private val auth: AuthRepository, private val onLoggedIn: () -> Unit) : ViewModel() {
    private val _state = MutableStateFlow(LoginUiState())
    val state: StateFlow<LoginUiState> = _state.asStateFlow()

    init { checkSetupStatus() }

    /** GET /setup/status — silently ignored when the server cannot be reached (login still works offline-ish). */
    fun checkSetupStatus() {
        viewModelScope.launch {
            val status = auth.setupStatus().getOrNull() ?: return@launch
            _state.update { it.copy(needsFirstAdmin = status.needsFirstAdmin, setupCodeRequired = status.setupCodeRequired) }
        }
    }

    fun showNotice(message: String?) = _state.update { it.copy(notice = message) }
    fun dismissNotice() = _state.update { it.copy(notice = null) }

    /** After the server address changed: re-run the first-run probe against the new backend. */
    fun onServerChanged() {
        _state.update { it.copy(needsFirstAdmin = false, formError = null, notice = null) }
        checkSetupStatus()
    }

    fun onIdentifier(v: String) = _state.update { it.copy(identifier = v, identifierError = null, formError = null) }
    fun onPassword(v: String) = _state.update { it.copy(password = v, passwordError = null, formError = null) }

    fun submit() {
        val s = _state.value
        if (s.loading) return
        val idErr = Validation.identifier(s.identifier)
        val pwErr = Validation.loginPassword(s.password)
        if (idErr != null || pwErr != null) {
            _state.update { it.copy(identifierError = idErr, passwordError = pwErr) }
            return
        }
        _state.update { it.copy(loading = true, formError = null) }
        viewModelScope.launch {
            when (val r = auth.login(s.identifier, s.password)) {
                is ApiResult.Success -> {
                    _state.update { it.copy(loading = false, password = "", loggedIn = true) }
                    onLoggedIn()
                }
                else -> _state.update { it.copy(loading = false, formError = r.userMessage()) }
            }
        }
    }

    fun openForgot() = _state.update { it.copy(forgotOpen = true, forgotIdentifier = it.identifier, forgotError = null, forgotSent = false) }
    fun closeForgot() = _state.update { it.copy(forgotOpen = false) }
    fun onForgotIdentifier(v: String) = _state.update { it.copy(forgotIdentifier = v, forgotError = null) }

    fun sendForgot() {
        val id = _state.value.forgotIdentifier
        Validation.identifier(id)?.let { err -> _state.update { it.copy(forgotError = err) }; return }
        _state.update { it.copy(forgotSending = true) }
        viewModelScope.launch {
            val r = auth.forgotPassword(id)
            // 202 always (anti-enumeration): show the same neutral confirmation whatever the account state.
            _state.update {
                if (r is ApiResult.Success) it.copy(forgotSending = false, forgotSent = true)
                else it.copy(forgotSending = false, forgotError = r.userMessage())
            }
        }
    }
}

data class ChangePasswordUiState(
    val current: String = "",
    val new: String = "",
    val confirm: String = "",
    val issues: List<String> = emptyList(),
    val error: String? = null,
    val saving: Boolean = false,
    val done: Boolean = false,
)

class ChangePasswordViewModel(private val auth: AuthRepository, private val identifier: () -> String?) : ViewModel() {
    private val _state = MutableStateFlow(ChangePasswordUiState())
    val state: StateFlow<ChangePasswordUiState> = _state.asStateFlow()

    fun onCurrent(v: String) = _state.update { it.copy(current = v, error = null) }
    fun onNew(v: String) = _state.update { it.copy(new = v, issues = emptyList(), error = null) }
    fun onConfirm(v: String) = _state.update { it.copy(confirm = v, issues = emptyList(), error = null) }

    fun submit() {
        val s = _state.value
        if (s.current.isEmpty()) { _state.update { it.copy(error = "Enter your current (temporary) password.") }; return }
        val issues = Validation.newPassword(s.new, identifier(), s.confirm)
        if (issues.isNotEmpty()) { _state.update { it.copy(issues = issues) }; return }
        _state.update { it.copy(saving = true) }
        viewModelScope.launch {
            val r = auth.changePassword(s.current, s.new)
            _state.update {
                if (r is ApiResult.Success) it.copy(saving = false, done = true, current = "", new = "", confirm = "")
                else it.copy(saving = false, error = r.userMessage())
            }
        }
    }
}

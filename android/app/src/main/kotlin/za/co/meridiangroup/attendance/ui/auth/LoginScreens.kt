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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import za.co.meridiangroup.attendance.R
import za.co.meridiangroup.attendance.core.StatusTone
import za.co.meridiangroup.attendance.ui.components.Banner
import za.co.meridiangroup.attendance.ui.components.GlassCard
import za.co.meridiangroup.attendance.ui.components.PillButton
import za.co.meridiangroup.attendance.ui.theme.MeridianColors
import za.co.meridiangroup.attendance.ui.theme.Spacing

@Composable
fun LoginScreen(viewModel: LoginViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showPassword by rememberSaveable { mutableStateOf(false) }

    Box(
        Modifier.fillMaxSize()
            .background(Brush.verticalGradient(listOf(MeridianColors.Navy700, MeridianColors.Navy900)))
            .systemBarsPadding()
            .imePadding(),
    ) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Spacing.xl),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(Modifier.size(72.dp).clip(RoundedCornerShape(20.dp)).background(MeridianColors.Navy600), contentAlignment = Alignment.Center) {
                Text("M", style = MaterialTheme.typography.headlineLarge, color = MeridianColors.White)
            }
            Spacer(Modifier.height(Spacing.l))
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineSmall, color = MeridianColors.White)
            Text(stringResource(R.string.login_subtitle), style = MaterialTheme.typography.bodyMedium, color = MeridianColors.TextMutedDark, textAlign = TextAlign.Center)
            Spacer(Modifier.height(Spacing.xl))

            GlassCard(Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = state.identifier,
                    onValueChange = viewModel::onIdentifier,
                    label = { Text(stringResource(R.string.login_identifier)) },
                    supportingText = { Text(state.identifierError ?: stringResource(R.string.login_identifier_hint)) },
                    isError = state.identifierError != null,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth().testTag("login_identifier"),
                )
                OutlinedTextField(
                    value = state.password,
                    onValueChange = viewModel::onPassword,
                    label = { Text(stringResource(R.string.login_password)) },
                    supportingText = state.passwordError?.let { { Text(it) } },
                    isError = state.passwordError != null,
                    singleLine = true,
                    visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showPassword = !showPassword }) {
                            Icon(
                                if (showPassword) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                contentDescription = stringResource(if (showPassword) R.string.login_hide_password else R.string.login_show_password),
                            )
                        }
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { viewModel.submit() }),
                    modifier = Modifier.fillMaxWidth().testTag("login_password"),
                )
                if (state.formError != null) {
                    Spacer(Modifier.height(Spacing.s))
                    Banner(
                        title = stringResource(R.string.login_failed), message = state.formError.orEmpty(), tone = StatusTone.ERROR,
                        modifier = Modifier.testTag("login_error"),
                    )
                }
                Spacer(Modifier.height(Spacing.l))
                PillButton(
                    text = stringResource(R.string.login_submit), onClick = viewModel::submit, loading = state.loading,
                    icon = Icons.Outlined.Lock, modifier = Modifier.fillMaxWidth().testTag("login_submit"),
                )
                TextButton(onClick = viewModel::openForgot, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                    Text(stringResource(R.string.login_forgot))
                }
            }
            Spacer(Modifier.height(Spacing.l))
            Text(stringResource(R.string.login_popia_note), style = MaterialTheme.typography.bodySmall, color = MeridianColors.TextMutedDark, textAlign = TextAlign.Center)
        }
    }

    if (state.forgotOpen) ForgotPasswordDialog(state, viewModel)
}

@Composable
private fun ForgotPasswordDialog(state: LoginUiState, viewModel: LoginViewModel) {
    AlertDialog(
        onDismissRequest = viewModel::closeForgot,
        title = { Text(stringResource(R.string.forgot_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                if (state.forgotSent) {
                    Text(stringResource(R.string.forgot_sent))
                } else {
                    Text(stringResource(R.string.forgot_body))
                    OutlinedTextField(
                        value = state.forgotIdentifier, onValueChange = viewModel::onForgotIdentifier,
                        label = { Text(stringResource(R.string.login_identifier)) }, singleLine = true,
                        isError = state.forgotError != null, supportingText = state.forgotError?.let { { Text(it) } },
                        modifier = Modifier.fillMaxWidth().testTag("forgot_identifier"),
                    )
                }
            }
        },
        confirmButton = {
            if (state.forgotSent) {
                TextButton(onClick = viewModel::closeForgot) { Text(stringResource(R.string.action_done)) }
            } else {
                TextButton(onClick = viewModel::sendForgot, enabled = !state.forgotSending, modifier = Modifier.testTag("forgot_send")) {
                    Text(stringResource(R.string.forgot_send))
                }
            }
        },
        dismissButton = { if (!state.forgotSent) TextButton(onClick = viewModel::closeForgot) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** Forced (must_change_password) or voluntary password change. */
@Composable
fun ChangePasswordScreen(viewModel: ChangePasswordViewModel, forced: Boolean, onDone: () -> Unit, onLogout: (() -> Unit)?) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    if (state.done) {
        androidx.compose.runtime.LaunchedEffect(Unit) { onDone() }
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Spacing.xl).imePadding(),
        verticalArrangement = Arrangement.spacedBy(Spacing.m),
    ) {
        Text(stringResource(R.string.change_password_title), style = MaterialTheme.typography.headlineSmall)
        Text(
            stringResource(if (forced) R.string.change_password_forced else R.string.change_password_body),
            style = MaterialTheme.typography.bodyMedium,
        )
        PasswordField(state.current, viewModel::onCurrent, stringResource(R.string.change_password_current), "pw_current")
        PasswordField(state.new, viewModel::onNew, stringResource(R.string.change_password_new), "pw_new")
        PasswordField(state.confirm, viewModel::onConfirm, stringResource(R.string.change_password_confirm), "pw_confirm")
        Text(stringResource(R.string.change_password_policy), style = MaterialTheme.typography.bodySmall)
        state.issues.forEach { Text("• $it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        state.error?.let { Banner(stringResource(R.string.change_password_failed), it, StatusTone.ERROR) }
        PillButton(stringResource(R.string.change_password_submit), viewModel::submit, Modifier.fillMaxWidth().testTag("pw_submit"), loading = state.saving)
        if (onLogout != null) {
            TextButton(onClick = onLogout, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text(stringResource(R.string.profile_logout)) }
        }
    }
}

@Composable
private fun PasswordField(value: String, onChange: (String) -> Unit, label: String, tag: String) {
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) }, singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth().testTag(tag),
    )
}

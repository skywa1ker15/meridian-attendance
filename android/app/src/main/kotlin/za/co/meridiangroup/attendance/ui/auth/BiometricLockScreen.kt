package za.co.meridiangroup.attendance.ui.auth

import android.widget.Toast
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import za.co.meridiangroup.attendance.R
import za.co.meridiangroup.attendance.ui.components.PillButton
import za.co.meridiangroup.attendance.ui.theme.MeridianColors
import za.co.meridiangroup.attendance.ui.theme.Spacing

/**
 * Full-screen biometric lock shown when the app is resumed and biometric lock is enabled.
 * Automatically triggers the biometric prompt on first display.
 */
@Composable
fun BiometricLockScreen(onUnlocked: () -> Unit) {
    val context = LocalContext.current

    fun showPrompt() {
        val activity = context as? FragmentActivity ?: run {
            // Fallback: if not a FragmentActivity, just unlock
            onUnlocked()
            return
        }
        val executor = ContextCompat.getMainExecutor(context)
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                onUnlocked()
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                // User cancelled or too many attempts — stay locked, they can tap to retry
                if (errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON ||
                    errorCode == BiometricPrompt.ERROR_USER_CANCELED
                ) return
                Toast.makeText(context, errString, Toast.LENGTH_SHORT).show()
            }
        }
        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(context.getString(R.string.biometric_title))
            .setSubtitle(context.getString(R.string.biometric_subtitle))
            .setNegativeButtonText(context.getString(R.string.action_cancel))
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.BIOMETRIC_WEAK)
            .build()
        BiometricPrompt(activity, executor, callback).authenticate(promptInfo)
    }

    // Trigger the prompt automatically on composition
    LaunchedEffect(Unit) { showPrompt() }

    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(MeridianColors.Navy700, MeridianColors.Navy900))),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                Icons.Outlined.Fingerprint, contentDescription = null,
                modifier = Modifier.size(80.dp), tint = MeridianColors.White,
            )
            Spacer(Modifier.height(Spacing.l))
            Text(
                stringResource(R.string.biometric_title),
                style = MaterialTheme.typography.headlineSmall,
                color = MeridianColors.White,
            )
            Spacer(Modifier.height(Spacing.s))
            Text(
                stringResource(R.string.biometric_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MeridianColors.TextMutedDark,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(Spacing.xl))
            PillButton(
                text = stringResource(R.string.biometric_tap_unlock),
                onClick = { showPrompt() },
            )
        }
    }
}

/** Check if biometric authentication is available on this device. */
fun canUseBiometrics(context: android.content.Context): Boolean {
    val bm = BiometricManager.from(context)
    return bm.canAuthenticate(
        BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.BIOMETRIC_WEAK
    ) == BiometricManager.BIOMETRIC_SUCCESS
}

package za.co.meridiangroup.attendance.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import za.co.meridiangroup.attendance.R
import za.co.meridiangroup.attendance.ui.theme.MeridianTheme
import za.co.meridiangroup.attendance.ui.theme.Spacing

@Composable
fun LoadingState(modifier: Modifier = Modifier, message: String? = null) {
    Box(modifier.fillMaxWidth().padding(Spacing.xxl).testTag("loading"), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            if (message != null) {
                Spacer(Modifier.height(Spacing.m))
                Text(message, style = MaterialTheme.typography.bodyMedium, color = MeridianTheme.extended.mutedText)
            }
        }
    }
}

@Composable
fun EmptyState(title: String, message: String, modifier: Modifier = Modifier, icon: ImageVector = Icons.Outlined.Inbox, action: (@Composable () -> Unit)? = null) {
    Column(
        modifier.fillMaxWidth().padding(Spacing.xxl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.s),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(48.dp), tint = MeridianTheme.extended.mutedText)
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Text(message, style = MaterialTheme.typography.bodyMedium, color = MeridianTheme.extended.mutedText, textAlign = TextAlign.Center)
        action?.invoke()
    }
}

@Composable
fun ErrorState(message: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().padding(Spacing.xxl).testTag("error"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.m),
    ) {
        Icon(Icons.Outlined.CloudOff, contentDescription = null, modifier = Modifier.size(48.dp), tint = MeridianTheme.extended.danger)
        Text(message, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
        PillButton(text = stringResource(R.string.action_retry), onClick = onRetry, style = PillStyle.OUTLINE)
    }
}

package za.co.meridiangroup.attendance.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import za.co.meridiangroup.attendance.core.StatusTone
import za.co.meridiangroup.attendance.core.StatusTones
import za.co.meridiangroup.attendance.ui.theme.MeridianTheme
import za.co.meridiangroup.attendance.ui.theme.Spacing

/** Translucent rounded card with a faint top highlight (DESIGN.md "glass"). */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(Spacing.l),
    content: @Composable ColumnScope.() -> Unit,
) {
    val ext = MeridianTheme.extended
    val shape = RoundedCornerShape(20.dp)
    val highlight = Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.10f), Color.Transparent))
    val inner: @Composable () -> Unit = {
        Box(Modifier.background(highlight)) {
            Column(Modifier.padding(contentPadding), content = content)
        }
    }
    if (onClick != null) {
        Surface(onClick = onClick, modifier = modifier.fillMaxWidth(), shape = shape, color = ext.glassFill,
            border = BorderStroke(1.dp, ext.glassBorder), shadowElevation = 1.dp, content = inner)
    } else {
        Surface(modifier = modifier.fillMaxWidth(), shape = shape, color = ext.glassFill,
            border = BorderStroke(1.dp, ext.glassBorder), shadowElevation = 1.dp, content = inner)
    }
}

/** Status pill: colour + text (+ optional dot), never colour alone (DESIGN.md accessibility). */
@Composable
fun StatusChip(status: String, modifier: Modifier = Modifier, label: String = StatusTones.label(status), tone: StatusTone = StatusTones.forStatus(status)) {
    val ext = MeridianTheme.extended
    Row(
        modifier = modifier
            .clip(CircleShape)
            .background(ext.container(tone))
            .padding(horizontal = 10.dp, vertical = 4.dp)
            .semantics { contentDescription = "Status: $label" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(ext.accent(tone)))
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = ext.onContainer(tone), maxLines = 1)
    }
}

enum class PillStyle { PRIMARY, SUCCESS, DANGER, GHOST, OUTLINE }

/** Pill-shaped button, min 48dp touch target. */
@Composable
fun PillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: PillStyle = PillStyle.PRIMARY,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    loading: Boolean = false,
) {
    val ext = MeridianTheme.extended
    val shape = CircleShape
    val content: @Composable () -> Unit = {
        if (loading) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = pillContentColor(style))
            Spacer(Modifier.width(Spacing.s))
        } else if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(Spacing.s))
        }
        Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    val m = modifier.defaultMinSize(minHeight = 48.dp)
    when (style) {
        PillStyle.GHOST -> TextButton(onClick, m, enabled = enabled && !loading, shape = shape) { content() }
        PillStyle.OUTLINE -> OutlinedButton(onClick, m, enabled = enabled && !loading, shape = shape) { content() }
        else -> {
            val container = when (style) {
                PillStyle.SUCCESS -> ext.success
                PillStyle.DANGER -> ext.danger
                else -> MaterialTheme.colorScheme.primary
            }
            Button(
                onClick, m, enabled = enabled && !loading, shape = shape,
                colors = ButtonDefaults.buttonColors(containerColor = container, contentColor = Color.White),
            ) { content() }
        }
    }
}

@Composable
private fun pillContentColor(style: PillStyle): Color =
    if (style == PillStyle.GHOST || style == PillStyle.OUTLINE) MaterialTheme.colorScheme.primary else Color.White

/** Icon chip + label + big number + sub-label. */
@Composable
fun StatTile(label: String, value: String, modifier: Modifier = Modifier, icon: ImageVector? = null, sub: String? = null) {
    GlassCard(modifier = modifier) {
        if (icon != null) {
            Box(
                Modifier.size(36.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) { Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp)) }
            Spacer(Modifier.padding(top = Spacing.s))
        }
        Text(label, style = MaterialTheme.typography.labelMedium, color = MeridianTheme.extended.mutedText)
        Text(value, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface)
        if (sub != null) Text(sub, style = MaterialTheme.typography.bodySmall, color = MeridianTheme.extended.mutedText)
    }
}

@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(top = Spacing.s), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        action?.invoke()
    }
}

/** Coloured information banner (permission revoked, workplace not configured, offline…). */
@Composable
fun Banner(
    title: String,
    message: String,
    tone: StatusTone,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val ext = MeridianTheme.extended
    Surface(modifier = modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), color = ext.container(tone)) {
        Row(Modifier.padding(Spacing.m), verticalAlignment = Alignment.Top) {
            if (icon != null) {
                Icon(icon, contentDescription = null, tint = ext.onContainer(tone), modifier = Modifier.padding(top = 2.dp).size(20.dp))
                Spacer(Modifier.width(Spacing.m))
            }
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = ext.onContainer(tone))
                Text(message, style = MaterialTheme.typography.bodySmall, color = ext.onContainer(tone))
                if (actionLabel != null && onAction != null) {
                    TextButton(onClick = onAction, contentPadding = PaddingValues(0.dp)) {
                        Text(actionLabel, color = ext.onContainer(tone))
                    }
                }
            }
        }
    }
}

@Composable
fun KeyValueRow(key: String, value: String, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(key, style = MaterialTheme.typography.bodyMedium, color = MeridianTheme.extended.mutedText)
        Spacer(Modifier.width(Spacing.m))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

package za.co.meridiangroup.attendance.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import za.co.meridiangroup.attendance.core.StatusTone

/** Colour tokens — docs/DESIGN.md. */
object MeridianColors {
    val Navy950 = Color(0xFF070F1F)
    val Navy900 = Color(0xFF0B1A33)
    val Navy800 = Color(0xFF10264D)
    val Navy700 = Color(0xFF163A6E)
    val Navy600 = Color(0xFF1F4C8F)
    val Blue500 = Color(0xFF2F6FED)
    val Blue300 = Color(0xFF7FA6F5)
    val Blue100 = Color(0xFFDCE7FB)
    val White = Color(0xFFFFFFFF)
    val Grey100 = Color(0xFFF3F6FB)
    val Grey300 = Color(0xFFCBD5E3)
    val TextDark = Color(0xFF0E1A2B)
    val TextLight = Color(0xFFE8EEF9)
    val TextMutedDark = Color(0xFF9FB0CC)
    val TextMutedLight = Color(0xFF5B6B82)
    val Green500 = Color(0xFF1FA463)
    val Green100 = Color(0xFFDFF5E8)
    val Orange500 = Color(0xFFF28C28)
    val Orange100 = Color(0xFFFDEBD8)
    val Red500 = Color(0xFFE5484D)
    val Red100 = Color(0xFFFBE1E2)
    val Neutral100 = Color(0xFFE6EBF3)
    val Neutral600 = Color(0xFF4A5A73)
    // Darker shades used for *text* on light status containers (≥ 4.5:1 contrast).
    val Green700 = Color(0xFF12683F)
    val Orange700 = Color(0xFF8A4A0B)
    val Red700 = Color(0xFFA61E23)
}

/** Extra semantic colours not covered by Material's scheme. */
@Immutable
data class ExtendedColors(
    val success: Color,
    val successContainer: Color,
    val onSuccessContainer: Color,
    val warning: Color,
    val warningContainer: Color,
    val onWarningContainer: Color,
    val danger: Color,
    val dangerContainer: Color,
    val onDangerContainer: Color,
    val neutralContainer: Color,
    val onNeutralContainer: Color,
    val glassFill: Color,
    val glassBorder: Color,
    val mutedText: Color,
) {
    fun container(tone: StatusTone): Color = when (tone) {
        StatusTone.SUCCESS -> successContainer
        StatusTone.WARNING -> warningContainer
        StatusTone.ERROR -> dangerContainer
        StatusTone.NEUTRAL -> neutralContainer
    }

    fun onContainer(tone: StatusTone): Color = when (tone) {
        StatusTone.SUCCESS -> onSuccessContainer
        StatusTone.WARNING -> onWarningContainer
        StatusTone.ERROR -> onDangerContainer
        StatusTone.NEUTRAL -> onNeutralContainer
    }

    fun accent(tone: StatusTone): Color = when (tone) {
        StatusTone.SUCCESS -> success
        StatusTone.WARNING -> warning
        StatusTone.ERROR -> danger
        StatusTone.NEUTRAL -> mutedText
    }
}

private val LightExtended = ExtendedColors(
    success = MeridianColors.Green500, successContainer = MeridianColors.Green100, onSuccessContainer = MeridianColors.Green700,
    warning = MeridianColors.Orange500, warningContainer = MeridianColors.Orange100, onWarningContainer = MeridianColors.Orange700,
    danger = MeridianColors.Red500, dangerContainer = MeridianColors.Red100, onDangerContainer = MeridianColors.Red700,
    neutralContainer = MeridianColors.Neutral100, onNeutralContainer = MeridianColors.Neutral600,
    glassFill = Color(0xB8FFFFFF), glassBorder = Color(0x140E1A2B), mutedText = MeridianColors.TextMutedLight,
)

private val DarkExtended = ExtendedColors(
    success = MeridianColors.Green500, successContainer = Color(0x331FA463), onSuccessContainer = Color(0xFF8EE0B5),
    warning = MeridianColors.Orange500, warningContainer = Color(0x33F28C28), onWarningContainer = Color(0xFFFFC58A),
    danger = MeridianColors.Red500, dangerContainer = Color(0x33E5484D), onDangerContainer = Color(0xFFFFA9AB),
    neutralContainer = Color(0x1FFFFFFF), onNeutralContainer = MeridianColors.TextMutedDark,
    glassFill = Color(0x0FFFFFFF), glassBorder = Color(0x1FFFFFFF), mutedText = MeridianColors.TextMutedDark,
)

val LocalExtendedColors = staticCompositionLocalOf { LightExtended }

private val LightScheme: ColorScheme = lightColorScheme(
    primary = MeridianColors.Navy700,
    onPrimary = MeridianColors.White,
    primaryContainer = MeridianColors.Blue100,
    onPrimaryContainer = MeridianColors.Navy800,
    secondary = MeridianColors.Blue500,
    onSecondary = MeridianColors.White,
    background = MeridianColors.Grey100,
    onBackground = MeridianColors.TextDark,
    surface = MeridianColors.White,
    onSurface = MeridianColors.TextDark,
    surfaceVariant = MeridianColors.Grey100,
    onSurfaceVariant = MeridianColors.TextMutedLight,
    outline = MeridianColors.Grey300,
    error = MeridianColors.Red500,
    onError = MeridianColors.White,
)

private val DarkScheme: ColorScheme = darkColorScheme(
    primary = MeridianColors.Blue500,
    onPrimary = MeridianColors.White,
    primaryContainer = MeridianColors.Navy800,
    onPrimaryContainer = MeridianColors.TextLight,
    secondary = MeridianColors.Blue300,
    background = MeridianColors.Navy900,
    onBackground = MeridianColors.TextLight,
    surface = MeridianColors.Navy800,
    onSurface = MeridianColors.TextLight,
    surfaceVariant = MeridianColors.Navy800,
    onSurfaceVariant = MeridianColors.TextMutedDark,
    outline = Color(0x33FFFFFF),
    error = MeridianColors.Red500,
)

private val MeridianShapes = Shapes(
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

private val MeridianTypography = Typography().let { base ->
    base.copy(
        headlineLarge = base.headlineLarge.copy(fontWeight = FontWeight.SemiBold),
        headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.SemiBold),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = base.labelLarge.copy(fontWeight = FontWeight.SemiBold),
    )
}

/** Font size scale: 0 = Small (0.85), 1 = Medium (1.0), 2 = Large (1.2). */
fun scaledTypography(fontSizeLevel: Int): Typography {
    val scale = when (fontSizeLevel) { 0 -> 0.85f; 2 -> 1.2f; else -> 1.0f }
    if (scale == 1.0f) return MeridianTypography
    return MeridianTypography.let { base ->
        base.copy(
            displayLarge = base.displayLarge.copy(fontSize = base.displayLarge.fontSize * scale, lineHeight = base.displayLarge.lineHeight * scale),
            displayMedium = base.displayMedium.copy(fontSize = base.displayMedium.fontSize * scale, lineHeight = base.displayMedium.lineHeight * scale),
            displaySmall = base.displaySmall.copy(fontSize = base.displaySmall.fontSize * scale, lineHeight = base.displaySmall.lineHeight * scale),
            headlineLarge = base.headlineLarge.copy(fontSize = base.headlineLarge.fontSize * scale, lineHeight = base.headlineLarge.lineHeight * scale),
            headlineMedium = base.headlineMedium.copy(fontSize = base.headlineMedium.fontSize * scale, lineHeight = base.headlineMedium.lineHeight * scale),
            headlineSmall = base.headlineSmall.copy(fontSize = base.headlineSmall.fontSize * scale, lineHeight = base.headlineSmall.lineHeight * scale),
            titleLarge = base.titleLarge.copy(fontSize = base.titleLarge.fontSize * scale, lineHeight = base.titleLarge.lineHeight * scale),
            titleMedium = base.titleMedium.copy(fontSize = base.titleMedium.fontSize * scale, lineHeight = base.titleMedium.lineHeight * scale),
            titleSmall = base.titleSmall.copy(fontSize = base.titleSmall.fontSize * scale, lineHeight = base.titleSmall.lineHeight * scale),
            bodyLarge = base.bodyLarge.copy(fontSize = base.bodyLarge.fontSize * scale, lineHeight = base.bodyLarge.lineHeight * scale),
            bodyMedium = base.bodyMedium.copy(fontSize = base.bodyMedium.fontSize * scale, lineHeight = base.bodyMedium.lineHeight * scale),
            bodySmall = base.bodySmall.copy(fontSize = base.bodySmall.fontSize * scale, lineHeight = base.bodySmall.lineHeight * scale),
            labelLarge = base.labelLarge.copy(fontSize = base.labelLarge.fontSize * scale, lineHeight = base.labelLarge.lineHeight * scale),
            labelMedium = base.labelMedium.copy(fontSize = base.labelMedium.fontSize * scale, lineHeight = base.labelMedium.lineHeight * scale),
            labelSmall = base.labelSmall.copy(fontSize = base.labelSmall.fontSize * scale, lineHeight = base.labelSmall.lineHeight * scale),
        )
    }
}

object Spacing {
    val xs = 4.dp
    val s = 8.dp
    val m = 12.dp
    val l = 16.dp
    val xl = 24.dp
    val xxl = 32.dp
}

/**
 * @param themeMode "system" (default), "light", or "dark"
 * @param fontSizeLevel 0 = Small, 1 = Medium (default), 2 = Large
 */
@Composable
fun MeridianTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    themeMode: String = "system",
    fontSizeLevel: Int = 1,
    content: @Composable () -> Unit,
) {
    val isDark = when (themeMode) {
        "light" -> false
        "dark" -> true
        else -> darkTheme
    }
    val extended = if (isDark) DarkExtended else LightExtended
    val typography = scaledTypography(fontSizeLevel)
    androidx.compose.runtime.CompositionLocalProvider(LocalExtendedColors provides extended) {
        MaterialTheme(
            colorScheme = if (isDark) DarkScheme else LightScheme,
            typography = typography,
            shapes = MeridianShapes,
            content = content,
        )
    }
}

object MeridianTheme {
    val extended: ExtendedColors
        @Composable get() = LocalExtendedColors.current
}

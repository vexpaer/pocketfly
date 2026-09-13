package io.github.pocketfly.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight

private val DarkColors = darkColorScheme(
    primary = Amber,
    onPrimary = OnAmber,
    primaryContainer = Color(0xFF3A2F14),
    onPrimaryContainer = Color(0xFFF3D28A),
    secondary = Teal,
    onSecondary = Color(0xFF0E221D),
    secondaryContainer = Color(0xFF1B2F2A),
    onSecondaryContainer = Color(0xFFA9D4C8),
    tertiary = CoolBlue,
    onTertiary = Color(0xFF131C2B),
    background = InkBackground,
    onBackground = TextPrimary,
    surface = InkSurface,
    onSurface = TextPrimary,
    surfaceVariant = InkSurfaceHigh,
    onSurfaceVariant = TextSecondary,
    outline = InkOutline,
    outlineVariant = InkOutline,
    error = SignalRed,
    onError = Color(0xFF2B0F0A),
)

private val LightColors = lightColorScheme(
    primary = AmberDeep,
    onPrimary = Color(0xFFFFFFFF),
    secondary = Color(0xFF3E6A5F),
    onSecondary = Color(0xFFFFFFFF),
    tertiary = Color(0xFF44577A),
    background = PaperBackground,
    onBackground = PaperText,
    surface = PaperSurface,
    onSurface = PaperText,
    surfaceVariant = PaperSurfaceHigh,
    onSurfaceVariant = PaperTextSecondary,
    outline = PaperOutline,
    outlineVariant = PaperOutline,
    error = Color(0xFFB34A38),
)

private val AppTypography = Typography().let { t ->
    t.copy(
        titleLarge = t.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = t.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = t.labelLarge.copy(fontWeight = FontWeight.Medium),
        labelMedium = t.labelMedium.copy(
            fontWeight = FontWeight.Medium,
            fontFamily = FontFamily.Monospace,
        ),
        labelSmall = t.labelSmall.copy(
            fontWeight = FontWeight.Medium,
            fontFamily = FontFamily.Monospace,
        ),
    )
}

@Composable
fun PocketFlyTheme(
    dark: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        typography = AppTypography,
        content = content,
    )
}

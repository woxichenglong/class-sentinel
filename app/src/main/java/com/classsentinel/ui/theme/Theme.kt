package com.classsentinel.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import java.util.Locale

private val LightColors = lightColorScheme(
    primary = AuroraBlue,
    onPrimary = AuroraInk,
    primaryContainer = AuroraMist,
    onPrimaryContainer = AuroraInk,
    secondary = AuroraBlueDeep,
    onSecondary = SnowWhite,
    secondaryContainer = AuroraIce,
    onSecondaryContainer = AuroraInk,
    tertiary = AuroraInk,
    onTertiary = SnowWhite,
    tertiaryContainer = SnowTint,
    onTertiaryContainer = AuroraInk,
    background = SnowWhite,
    onBackground = AuroraInk,
    surface = SnowWhite,
    onSurface = AuroraInk,
    surfaceVariant = SnowTint,
    onSurfaceVariant = MutedInk,
    outline = ColorTokens.lightOutline,
    outlineVariant = SnowLine,
    error = SoftError,
    onError = SnowWhite,
    errorContainer = SoftErrorContainer,
    onErrorContainer = SoftError,
)

private val DarkColors = darkColorScheme(
    primary = AuroraBlue,
    onPrimary = AuroraInk,
    primaryContainer = AuroraBlueDeep,
    onPrimaryContainer = NightSnow,
    secondary = AuroraIce,
    onSecondary = AuroraInk,
    secondaryContainer = NightSurfaceVariant,
    onSecondaryContainer = NightSnow,
    tertiary = AuroraBlue,
    onTertiary = AuroraInk,
    tertiaryContainer = NightSurfaceVariant,
    onTertiaryContainer = NightSnow,
    background = NightIce,
    onBackground = NightSnow,
    surface = NightSurface,
    onSurface = NightSnow,
    surfaceVariant = NightSurfaceVariant,
    onSurfaceVariant = Color(0xFFB7CCCD),
    outline = NightLine,
    outlineVariant = Color(0xFF355258),
    error = Color(0xFFFFB6C0),
    onError = Color(0xFF5F1F2B),
    errorContainer = Color(0xFF762F3B),
    onErrorContainer = Color(0xFFFFD9DE),
)

private object ColorTokens {
    val lightOutline = Color(0xFFA9BFC0)
}

private val ClassSentinelShapes = Shapes(
    extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
    small = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
    medium = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
    large = androidx.compose.foundation.shape.RoundedCornerShape(22.dp),
    extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(28.dp),
)

@Composable
fun ClassSentinelTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    // Dynamic Color is intentionally opt-in at the call site; product defaults stay brand-stable.
    val colorScheme = if (darkTheme) DarkColors else LightColors
    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        shapes = ClassSentinelShapes,
        content = content,
    )
}

/** 设置页深色模式的纯决策：未知值安全回退为跟随系统。 */
internal fun darkThemeForPreference(mode: String, systemIsDark: Boolean): Boolean = when (mode.trim().lowercase(Locale.ROOT)) {
    "on" -> true
    "off" -> false
    else -> systemIsDark
}

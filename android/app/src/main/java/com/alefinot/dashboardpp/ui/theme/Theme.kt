package com.alefinot.dashboardpp.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val HudeDarkColorScheme = androidx.compose.material3.darkColorScheme(
    primary = HudColors.Cyan,
    onPrimary = HudColors.Bg,
    secondary = HudColors.CyanDim,
    onSecondary = HudColors.TextPrimary,
    error = HudColors.Red,
    onError = HudColors.Bg,
    background = HudColors.Bg,
    onBackground = HudColors.TextPrimary,
    surface = Color(0xFF101622),
    onSurface = HudColors.TextPrimary,
    onSurfaceVariant = HudColors.TextMuted,
    outline = HudColors.TextMuted,
    outlineVariant = HudColors.Hairline,
)

@Composable
fun HudTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = HudeDarkColorScheme,
        typography = HudeTypography(),
        content = content,
    )
}

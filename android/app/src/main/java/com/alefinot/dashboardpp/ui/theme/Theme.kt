package com.alefinot.dashboardpp.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import com.alefinot.dashboardpp.ui.theme.HudColors

private val HudeDarkColorScheme = darkColorScheme(
    primary = HudColors.Cyan,
    onPrimary = HudColors.Bg,
    secondary = HudColors.CyanDim,
    onSecondary = HudColors.TextPrimary,
    error = HudColors.Red,
    onError = HudColors.Bg,
    background = HudColors.Bg,
    onBackground = HudColors.TextPrimary,
    surface = HudColors.Panel,
    onSurface = HudColors.TextPrimary,
    onSurfaceVariant = HudColors.TextDim,
    outline = HudColors.CyanDim,
)

@Composable
fun HudTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = HudeDarkColorScheme,
        typography = HudeTypography(),
        content = content,
    )
}

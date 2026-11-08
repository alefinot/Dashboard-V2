package com.alefinot.dashboardpp.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

val HudeDisplayFamily = FontFamily.SansSerif

// `body` and `p` ask for 'Inter', then system-ui; on Android that resolves to Roboto.
val HudeBodyFamily = FontFamily.SansSerif

// Data, tables and the clock ask for 'JetBrains Mono', then ui-monospace: the system mono.
val HudeMonoFamily = FontFamily.Monospace

val HudeSplash = TextStyle(
    fontFamily = HudeDisplayFamily,
    fontWeight = FontWeight.W800,
    fontSize = 34.sp,
    letterSpacing = (-0.75).sp,
    lineHeight = 34.sp,
)

fun HudeSectionTitle(spacingEm: Float = 0.04f) = TextStyle(
    fontFamily = HudeDisplayFamily,
    fontSize = 13.sp,
    fontWeight = FontWeight.W700,
    letterSpacing = spacingEm.em,
)

/** `h2`: 26px, 800, -0.5px — `LIVE`, `TRIPS`, filled by `HudBrushes.title`. */
fun HudeHeading(spacingEm: Float = -0.5f / 26f) = TextStyle(
    fontFamily = HudeDisplayFamily,
    fontSize = 26.sp,
    fontWeight = FontWeight.W800,
    letterSpacing = spacingEm.em,
    lineHeight = 30.sp,
)

/** `label`: 12px, 500, --text-muted. */
fun HudeFormLabel(spacingEm: Float = 0.04f) = TextStyle(
    fontFamily = HudeBodyFamily,
    fontSize = 12.sp,
    fontWeight = FontWeight.W500,
    letterSpacing = spacingEm.em,
    lineHeight = 17.sp,
)

/** `.param-name`: 13px, 700, 1.45 line-height. */
fun HudeParamName(spacingEm: Float = 0f) = TextStyle(
    fontFamily = HudeBodyFamily,
    fontSize = 13.sp,
    fontWeight = FontWeight.W700,
    letterSpacing = spacingEm.em,
    lineHeight = 19.sp,
)

/** `.param-value`: 12px, 0.3px tracking, 1.4 line-height. */
fun HudeParamValue(spacingEm: Float = 0.3f / 12f) = TextStyle(
    fontFamily = HudeBodyFamily,
    fontSize = 12.sp,
    fontWeight = FontWeight.W500,
    letterSpacing = spacingEm.em,
    lineHeight = 17.sp,
)

/** `p`: 13px, 500, 1.55 line-height — the page's paragraph. */
fun HudeBody(spacingEm: Float = 0f) = TextStyle(
    fontFamily = HudeBodyFamily,
    fontSize = 13.sp,
    fontWeight = FontWeight.W500,
    letterSpacing = spacingEm.em,
    lineHeight = 20.sp,
)

/** `table`: 12px monospace, 0.5px tracking. */
fun HudeTable(spacingEm: Float = 0.5f / 12f) = TextStyle(
    fontFamily = HudeMonoFamily,
    fontSize = 12.sp,
    fontWeight = FontWeight.W400,
    letterSpacing = spacingEm.em,
    lineHeight = 17.sp,
)

/** `#live-display .speed-value`: 44px monospace, 700, 0.05em tracking. */
fun HudeMetric(spacingEm: Float = 0.05f) = TextStyle(
    fontFamily = HudeMonoFamily,
    fontSize = 44.sp,
    fontWeight = FontWeight.W700,
    letterSpacing = spacingEm.em,
    lineHeight = 48.sp,
)

/** The same figure's unit label: 11px, 600, 0.15em, --text-dim. */
fun HudeMetricUnit(spacingEm: Float = 0.15f) = TextStyle(
    fontFamily = HudeDisplayFamily,
    fontSize = 11.sp,
    fontWeight = FontWeight.W600,
    letterSpacing = spacingEm.em,
    lineHeight = 14.sp,
)

fun HudeSectionCaps(spacingEm: Float = 0.8f / 11f) = TextStyle(
    fontFamily = HudeDisplayFamily,
    fontSize = 11.sp,
    fontWeight = FontWeight.W700,
    letterSpacing = spacingEm.em,
    lineHeight = 17.sp,
)

/** `.perf-label`: 12px, `--text-muted`, and no weight of its own - the body's 400. */
fun HudePerfLabel(spacingEm: Float = 0f) = TextStyle(
    fontFamily = HudeBodyFamily,
    fontSize = 12.sp,
    fontWeight = FontWeight.W400,
    letterSpacing = spacingEm.em,
    lineHeight = 17.sp,
)

/** `.cfg-note`: 11px, `--text-dim`, 1.5 line-height - the line under a setting. */
fun HudeCfgNote(spacingEm: Float = 0f) = TextStyle(
    fontFamily = HudeBodyFamily,
    fontSize = 11.sp,
    fontWeight = FontWeight.W400,
    letterSpacing = spacingEm.em,
    lineHeight = 17.sp,
)

fun HudeCfgHelp(spacingEm: Float = 0f) = TextStyle(
    fontFamily = HudeBodyFamily,
    fontSize = 11.sp,
    fontWeight = FontWeight.W400,
    letterSpacing = spacingEm.em,
    lineHeight = 17.sp,
)

/** `.cfg-live`: 12px `--text-muted`, the value line inside a card. */
fun HudeCfgLive(spacingEm: Float = 0f) = TextStyle(
    fontFamily = HudeBodyFamily,
    fontSize = 12.sp,
    fontWeight = FontWeight.W400,
    letterSpacing = spacingEm.em,
    lineHeight = 17.sp,
)

/** `.cfg-live strong`: 12px monospace, 700, `#fff`. */
fun HudeCfgLiveStrong(spacingEm: Float = 0f) = TextStyle(
    fontFamily = HudeMonoFamily,
    fontSize = 12.sp,
    fontWeight = FontWeight.W700,
    letterSpacing = spacingEm.em,
    lineHeight = 17.sp,
)

fun HudePerfValue(spacingEm: Float = 0.5f / 13f) = TextStyle(
    fontFamily = HudeMonoFamily,
    fontSize = 13.sp,
    fontWeight = FontWeight.W600,
    letterSpacing = spacingEm.em,
    lineHeight = 18.sp,
)

fun HudePerfRowValue(spacingEm: Float = 0.5f / 12f) = TextStyle(
    fontFamily = HudeMonoFamily,
    fontSize = 12.sp,
    fontWeight = FontWeight.W600,
    letterSpacing = spacingEm.em,
    lineHeight = 17.sp,
)

/** `button`: 13px, 600 — the words on an action, in the body family. */
fun HudeButtonLabel(spacingEm: Float = 0f) = TextStyle(
    fontFamily = HudeBodyFamily,
    fontSize = 13.sp,
    fontWeight = FontWeight.W600,
    letterSpacing = spacingEm.em,
)

fun HudeTypography(): Typography = Typography(
    displayLarge = HudeSplash,
    displayMedium = HudeHeading(),
    displaySmall = HudeHeading(),
    headlineLarge = HudeHeading(),
    headlineMedium = HudeHeading(),
    headlineSmall = HudeSectionTitle(),
    titleLarge = HudeHeading(),
    titleMedium = HudeSectionTitle(),
    titleSmall = HudeSectionTitle(),
    bodyLarge = HudeBody(),
    bodyMedium = HudeBody(),
    bodySmall = HudeCfgNote(),
    labelLarge = HudeButtonLabel(),
    labelMedium = HudeFormLabel(),
    labelSmall = HudeSectionCaps(),
)

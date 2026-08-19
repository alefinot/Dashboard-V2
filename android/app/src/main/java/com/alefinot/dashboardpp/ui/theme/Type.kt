package com.alefinot.dashboardpp.ui.theme

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Typography
import com.alefinot.dashboardpp.R

val HudeDisplayFamily = FontFamily(
    Font(R.font.rajdhani_light, FontWeight.W300),
    Font(R.font.rajdhani_regular, FontWeight.W400),
    Font(R.font.rajdhani_semibold, FontWeight.W600),
    Font(R.font.rajdhani_bold, FontWeight.W700),
)

val HudeMonoFamily = FontFamily(
    Font(R.font.share_tech_mono),
)

val HudMonoText = TextStyle(
    fontFamily = HudeMonoFamily,
    fontSize = 13.sp,
)

val HudeDisplayLarge = TextStyle(
    fontFamily = HudeDisplayFamily,
    fontSize = 40.sp,
    fontWeight = FontWeight.W600,
    letterSpacing = 2.sp,
)

val HudeDisplaySmall = TextStyle(
    fontFamily = HudeDisplayFamily,
    fontSize = 20.sp,
    fontWeight = FontWeight.W600,
    letterSpacing = 1.sp,
)

val HudeTitle = TextStyle(
    fontFamily = HudeDisplayFamily,
    fontSize = 18.sp,
    fontWeight = FontWeight.W600,
)

val HudeLabel = TextStyle(
    fontFamily = HudeDisplayFamily,
    fontSize = 13.sp,
    fontWeight = FontWeight.W600,
    letterSpacing = 1.5.sp,
)

val HudeBody = TextStyle(
    fontFamily = HudeDisplayFamily,
    fontSize = 14.sp,
    fontWeight = FontWeight.W400,
)

val HudeSmall = TextStyle(
    fontFamily = HudeDisplayFamily,
    fontSize = 11.sp,
    fontWeight = FontWeight.W600,
    letterSpacing = 1.sp,
)

fun HudeTypography(): Typography {
    return Typography(
        displayLarge = TextStyle(
            fontFamily = HudeDisplayFamily,
            fontSize = 40.sp,
            fontWeight = FontWeight.W600,
            letterSpacing = 2.sp,
        ),
        displayMedium = TextStyle(
            fontFamily = HudeDisplayFamily,
            fontSize = 28.sp,
            fontWeight = FontWeight.W600,
            letterSpacing = 1.sp,
        ),
        displaySmall = TextStyle(
            fontFamily = HudeDisplayFamily,
            fontSize = 20.sp,
            fontWeight = FontWeight.W600,
            letterSpacing = 1.sp,
        ),
        titleLarge = TextStyle(
            fontFamily = HudeDisplayFamily,
            fontSize = 18.sp,
            fontWeight = FontWeight.W600,
        ),
        titleMedium = TextStyle(
            fontFamily = HudeDisplayFamily,
            fontSize = 16.sp,
            fontWeight = FontWeight.W600,
        ),
        bodyLarge = TextStyle(
            fontFamily = HudeDisplayFamily,
            fontSize = 15.sp,
            fontWeight = FontWeight.W400,
        ),
        bodyMedium = TextStyle(
            fontFamily = HudeDisplayFamily,
            fontSize = 14.sp,
            fontWeight = FontWeight.W400,
        ),
        bodySmall = TextStyle(
            fontFamily = HudeDisplayFamily,
            fontSize = 12.sp,
            fontWeight = FontWeight.W400,
        ),
        labelLarge = TextStyle(
            fontFamily = HudeDisplayFamily,
            fontSize = 13.sp,
            fontWeight = FontWeight.W600,
            letterSpacing = 1.5.sp,
        ),
        labelMedium = TextStyle(
            fontFamily = HudeDisplayFamily,
            fontSize = 12.sp,
            fontWeight = FontWeight.W600,
            letterSpacing = 1.sp,
        ),
        labelSmall = TextStyle(
            fontFamily = HudeDisplayFamily,
            fontSize = 11.sp,
            fontWeight = FontWeight.W600,
            letterSpacing = 1.sp,
        ),
    )
}

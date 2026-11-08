package com.alefinot.dashboardpp.ui.screens

import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.alefinot.dashboardpp.ui.components.HudIcons

enum class AppTab { LIVE, TRIPS, VEHICLES, WEB, SETTINGS }

object BarLayout {

    /** Left to right. Dragging a finger left walks right along the bar and the two agree. */
    val pages: List<AppTab> =
        listOf(AppTab.LIVE, AppTab.TRIPS, AppTab.VEHICLES, AppTab.WEB, AppTab.SETTINGS)

    /** The middle slot — the one drawn larger, and the one the glass sheet parks on. */
    val centre: AppTab = AppTab.VEHICLES

    /** The page the app opens on. It is the centre, but that is a choice, not a law. */
    val home: AppTab = AppTab.VEHICLES

    /** [home]'s index in [pages], which is the pager's `initialPage`. */
    val homePage: Int = pages.indexOf(home)

    /** The pager index for a bar slot. Every slot has one; there are no dead slots. */
    fun pageOf(tab: AppTab): Int = pages.indexOf(tab)

    data class Glyph(
        val desc: String,
        val size: Dp,
        val box: Dp,
        val weight: Float,
        val colored: Boolean,
    )

    fun glyph(tab: AppTab): Glyph = when (tab) {
        AppTab.VEHICLES -> Glyph("Vehicle", 30.dp, 52.dp, 1.4f, true)
        AppTab.LIVE -> Glyph("Live", 22.dp, 48.dp, 1f, false)
        AppTab.TRIPS -> Glyph("Trips", 22.dp, 48.dp, 1f, false)
        AppTab.WEB -> Glyph("Dashboard settings", 22.dp, 48.dp, 1f, false)
        AppTab.SETTINGS -> Glyph("App settings", 22.dp, 48.dp, 1f, false)
    }

    fun icon(tab: AppTab): ImageVector = when (tab) {
        AppTab.LIVE -> HudIcons.Gauge
        AppTab.TRIPS -> HudIcons.Pin
        AppTab.VEHICLES -> HudIcons.Car
        AppTab.WEB -> HudIcons.Chip
        AppTab.SETTINGS -> HudIcons.Cog
    }
}

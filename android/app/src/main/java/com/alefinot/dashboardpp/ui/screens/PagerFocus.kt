package com.alefinot.dashboardpp.ui.screens

object PagerFocus {

    fun inViewport(current: Int, offset: Float, index: Int): Boolean =
        kotlin.math.abs(current + offset - index) < 0.999f
}

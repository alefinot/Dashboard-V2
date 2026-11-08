package com.alefinot.dashboardpp.vehicles

data class CropRect(val x: Int, val y: Int, val size: Int)

object PhotoCrop {

    fun rectFor(
        imgW: Int,
        imgH: Int,
        boxPx: Int,
        zoom: Float,
        offsetX: Float,
        offsetY: Float
    ): CropRect? {
        if (imgW <= 0 || imgH <= 0 || boxPx <= 0) return null
        if (!zoom.isFinite() || !offsetX.isFinite() || !offsetY.isFinite()) return null

        val z = if (zoom < 1f) 1f else zoom
        val dImgW = imgW.toDouble()
        val dImgH = imgH.toDouble()
        // Cover fit, then the user's zoom: how many source pixels fit in one box pixel.
        val scale = (boxPx.toDouble() / minOf(dImgW, dImgH)) * z
        // The window, in source pixels, seen through the box.
        val sideD = boxPx / scale
        val cx = dImgW / 2.0 - offsetX / scale
        val cy = dImgH / 2.0 - offsetY / scale

        val size = sideD.toInt().coerceAtLeast(1).coerceAtMost(minOf(imgW, imgH))
        val x = (cx - sideD / 2.0).toInt().coerceIn(0, imgW - size)
        val y = (cy - sideD / 2.0).toInt().coerceIn(0, imgH - size)
        return CropRect(x, y, size)
    }

    fun maxOffsetPx(imgW: Int, imgH: Int, boxPx: Int, zoom: Float): Pair<Float, Float> {
        if (imgW <= 0 || imgH <= 0 || boxPx <= 0) return 0f to 0f
        val z = if (!zoom.isFinite() || zoom < 1f) 1f else zoom
        val scale = (boxPx.toDouble() / minOf(imgW.toDouble(), imgH.toDouble())) * z
        val slackX = (imgW * scale - boxPx) / 2.0
        val slackY = (imgH * scale - boxPx) / 2.0
        return slackX.coerceAtLeast(0.0).toFloat() to slackY.coerceAtLeast(0.0).toFloat()
    }
    fun scaleRectInto(rect: CropRect, fullW: Int, fullH: Int, smallW: Int, smallH: Int): CropRect? {
        if (rect.size <= 0 || fullW <= 0 || fullH <= 0 || smallW <= 0 || smallH <= 0) return null
        val sx = smallW.toDouble() / fullW
        val sy = smallH.toDouble() / fullH
        val size = (rect.size * minOf(sx, sy)).toInt()
            .coerceAtLeast(1)
            .coerceAtMost(minOf(smallW, smallH))
        val x = (rect.x * sx).toInt().coerceIn(0, smallW - size)
        val y = (rect.y * sy).toInt().coerceIn(0, smallH - size)
        return CropRect(x, y, size)
    }
}

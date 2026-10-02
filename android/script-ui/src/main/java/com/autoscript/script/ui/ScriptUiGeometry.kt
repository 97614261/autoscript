package com.autoscript.script.ui

import kotlin.math.min
import kotlin.math.roundToInt

enum class UiDisplayMode { FIT_PAGE, WIDTH_SCROLL }

data class UiViewport(val scale: Float, val width: Float, val height: Float, val left: Float, val top: Float)
data class UiWindowSize(val width: Int, val height: Int)
data class UiScreenBounds(val left: Int, val top: Int, val width: Int, val height: Int) {
    fun clamp(x: Int, y: Int, size: UiWindowSize): Pair<Int, Int> =
        x.coerceIn(left, left + (width - size.width).coerceAtLeast(0)) to
            y.coerceIn(top, top + (height - size.height).coerceAtLeast(0))

    fun centered(size: UiWindowSize) = clamp(left + (width - size.width) / 2, top + (height - size.height) / 2, size)
}

/** Units are consistent within one calculation: physical pixels for Views, dp for designer thumbnails. */
object ScriptUiGeometry {
    /** Compact radio groups stay usable as one row instead of clipping vertically stacked options. */
    fun radioHorizontal(height: Int, fontPx: Int, optionCount: Int) = optionCount > 1 && height < maxOf(20f, fontPx * 1.8f) * optionCount

    fun viewport(width: Int, height: Int, availableWidth: Float, availableHeight: Float, mode: UiDisplayMode = UiDisplayMode.FIT_PAGE): UiViewport {
        require(width > 0 && height > 0 && availableWidth.isFinite() && availableHeight.isFinite())
        val w = availableWidth.coerceAtLeast(1f); val h = availableHeight.coerceAtLeast(1f)
        val scale = if (mode == UiDisplayMode.WIDTH_SCROLL) w / width else min(w / width, h / height)
        val actualW = width * scale; val actualH = height * scale
        return UiViewport(scale, actualW, actualH, ((w - actualW) / 2).coerceAtLeast(0f), ((h - actualH) / 2).coerceAtLeast(0f))
    }

    fun window(page: UiPage, bounds: UiScreenBounds, density: Float, chromeHeight: Int, legacyBodyHeight: Int? = null): UiWindowSize {
        require(bounds.width > 0 && bounds.height > 0 && density.isFinite() && density > 0 && page.width > 0 && page.height > 0)
        val maxWidth = min(bounds.width * .94f, 560 * density).roundToInt().coerceIn(1, bounds.width)
        val maxHeight = (bounds.height * .9f).roundToInt().coerceIn(1, bounds.height)
        val minWidth = min(maxWidth, (240 * density).roundToInt().coerceAtLeast(1))
        val width = (page.width * .5f * density).roundToInt().coerceIn(minWidth, maxWidth)
        val body = legacyBodyHeight?.toFloat() ?: (page.height.toFloat() * width / page.width)
        return UiWindowSize(width, (chromeHeight.coerceAtLeast(0) + body).roundToInt().coerceIn(1, maxHeight))
    }
}

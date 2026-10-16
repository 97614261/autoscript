package com.autoscript.script.ui

import kotlin.math.roundToInt

/** Design pixels, shared by the designer and native renderer; never Android theme dp. */
object ScriptUiControlMetrics {
    const val DEFAULT_WIDTH = 208
    const val DEFAULT_HEIGHT = 40
    const val DEFAULT_FONT = 20
    const val INSET = 4
    const val INDICATOR = 18
    const val SWITCH_WIDTH = 28
    const val SWITCH_HEIGHT = 16
    const val SWITCH_THUMB = 12
    const val TRACK_HEIGHT = 8
    const val SLIDER_THUMB = 16
    const val CHOICE_RESERVED = 30
    const val SWITCH_RESERVED = SWITCH_WIDTH + INSET * 3

    fun pixels(designPx: Int, scale: Float): Int {
        require(designPx > 0 && scale.isFinite() && scale > 0)
        return (designPx * scale).roundToInt().coerceAtLeast(1)
    }

    fun switchWidth(scale: Float) = maxOf(pixels(SWITCH_WIDTH, scale), 2 * pixels(SWITCH_THUMB, scale))
}

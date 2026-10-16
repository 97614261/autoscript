package com.autoscript.studio

import com.autoscript.project.store.PopupStyle
import java.util.Locale

internal val POPUP_NUMBER_RANGES = mapOf(
    "width" to 1..2160, "height" to 1..1200, "x" to -1..10000, "y" to -1..10000,
    "font" to 10..160, "corner" to 0..200, "duration" to 500..30000,
)

internal data class PopupStyleDraft(val inputs: Map<String, String>, val textAlign: String) {
    fun edit(key: String, value: String): PopupStyleDraft = copy(inputs = inputs + (key to value))

    /** Explicit shortcut changes only alpha; incomplete color text is preserved, not silently repaired. */
    fun withOpacity(key: String, percent: Int): PopupStyleDraft {
        require(key in setOf("background", "text") && percent in 0..100)
        if (!valid(key)) return this
        val alpha = (percent * 255 + 50) / 100
        return edit(key, "#%02X".format(Locale.ROOT, alpha) + inputs.getValue(key).substring(3))
    }

    fun valid(key: String): Boolean = if (key in POPUP_NUMBER_RANGES)
        inputs[key]?.toIntOrNull()?.let { it in POPUP_NUMBER_RANGES.getValue(key) } == true
    else key in setOf("background", "text") && inputs[key]?.matches(Regex("#[0-9A-Fa-f]{8}")) == true

    fun parse(): PopupStyle? = if ((POPUP_NUMBER_RANGES.keys + listOf("background", "text")).all(::valid) &&
        textAlign in setOf("left", "center", "right")) preview(PopupStyle()) else null

    // Invalid text stays in the draft; only validated fields are used for drawing.
    fun preview(fallback: PopupStyle): PopupStyle {
        fun number(key: String, value: Int) = if (valid(key)) inputs.getValue(key).toInt() else value
        fun color(key: String, value: String) = if (valid(key)) inputs.getValue(key).uppercase(Locale.ROOT) else value
        return fallback.copy(widthPx = number("width", fallback.widthPx), heightPx = number("height", fallback.heightPx),
            xPx = number("x", fallback.xPx), yPx = number("y", fallback.yPx), fontPx = number("font", fallback.fontPx),
            cornerPx = number("corner", fallback.cornerPx), durationMs = number("duration", fallback.durationMs),
            backgroundColor = color("background", fallback.backgroundColor), textColor = color("text", fallback.textColor),
            textAlign = textAlign.takeIf { it in setOf("left", "center", "right") } ?: fallback.textAlign)
    }

    companion object {
        fun from(style: PopupStyle) = PopupStyleDraft(mapOf(
            "width" to style.widthPx.toString(), "height" to style.heightPx.toString(), "x" to style.xPx.toString(),
            "y" to style.yPx.toString(), "font" to style.fontPx.toString(), "corner" to style.cornerPx.toString(),
            "duration" to style.durationMs.toString(), "background" to style.backgroundColor, "text" to style.textColor,
        ), style.textAlign)
    }
}

internal fun popupPreviewScale(widthPx: Int, heightPx: Int, availableWidthPx: Float, availableHeightPx: Float): Float {
    val width = availableWidthPx.takeIf { it.isFinite() && it > 0f } ?: 1f
    val height = availableHeightPx.takeIf { it.isFinite() && it > 0f } ?: 1f
    return minOf(1f, width / widthPx.coerceAtLeast(1), height / heightPx.coerceAtLeast(1))
}

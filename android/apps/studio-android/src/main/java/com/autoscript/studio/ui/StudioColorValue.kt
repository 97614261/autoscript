package com.autoscript.studio

import com.autoscript.studio.generated.BlockPropertyEditor
import kotlin.math.roundToInt

internal enum class StudioColorFormat(val alpha: Boolean) {
    RGB_HEX(false), RGB_BARE(false), RGB_DECIMAL(false), ARGB_HEX(true), RGB_OR_ARGB(true),
}

internal fun studioBlockColorFormat(path: String, editor: BlockPropertyEditor): StudioColorFormat? = when {
    editor == BlockPropertyEditor.COLOR -> StudioColorFormat.RGB_HEX
    editor == BlockPropertyEditor.INTEGER && path in setOf("rgb", "foregroundRgb", "anchorRgb") -> StudioColorFormat.RGB_DECIMAL
    editor == BlockPropertyEditor.STRING && path in setOf("textColor", "background", "borderColor", "backgroundColor") -> StudioColorFormat.RGB_OR_ARGB
    else -> null
}

/** Formats are explicit: a six-digit numeric-looking RGB must not become a decimal color. */
internal fun studioParseColor(text: String, format: StudioColorFormat): Long? {
    val value = text.trim()
    val hex = value.removePrefix("#")
    val number = when (format) {
        StudioColorFormat.ARGB_HEX -> if (value.startsWith("#") && hex.matches(Regex("[0-9a-fA-F]{8}"))) hex.toLong(16) else return null
        StudioColorFormat.RGB_OR_ARGB -> if (value.startsWith("#") && hex.matches(Regex("[0-9a-fA-F]{6}([0-9a-fA-F]{2})?")))
            hex.toLong(16) or if (hex.length == 6) 0xFF000000L else 0L else return null
        StudioColorFormat.RGB_BARE -> if (hex.matches(Regex("[0-9a-fA-F]{6}"))) hex.toLong(16) or 0xFF000000L else return null
        StudioColorFormat.RGB_HEX, StudioColorFormat.RGB_DECIMAL -> {
            val rgb = if (value.startsWith("#") && hex.matches(Regex("[0-9a-fA-F]{6}"))) hex.toLong(16)
                else if (value.startsWith("0x", true)) value.drop(2).toLongOrNull(16)?.takeIf { it in 0..0xFFFFFFL } ?: return null
                else value.toLongOrNull()?.takeIf { it in 0..0xFFFFFFL } ?: return null
            rgb or 0xFF000000L
        }
    }
    return number
}

internal fun studioFormatColor(argb: Long, format: StudioColorFormat): String = when (format) {
    StudioColorFormat.ARGB_HEX -> "#%08X".format(argb and 0xFFFFFFFFL)
    StudioColorFormat.RGB_OR_ARGB -> if (argb ushr 24 and 255L == 255L) "#%06X".format(argb and 0xFFFFFFL) else "#%08X".format(argb and 0xFFFFFFFFL)
    StudioColorFormat.RGB_HEX -> "#%06X".format(argb and 0xFFFFFFL)
    StudioColorFormat.RGB_BARE -> "%06X".format(argb and 0xFFFFFFL)
    StudioColorFormat.RGB_DECIMAL -> (argb and 0xFFFFFFL).toString()
}

internal data class StudioColorSelection(val hue: Float, val saturation: Float, val brightness: Float, val alpha: Int) {
    fun argb(): Long {
        val h = ((hue % 360f + 360f) % 360f) / 60f
        val c = brightness.coerceIn(0f, 1f) * saturation.coerceIn(0f, 1f)
        val x = c * (1f - kotlin.math.abs(h % 2f - 1f))
        val m = brightness.coerceIn(0f, 1f) - c
        val (r, g, b) = when (h.toInt()) {
            0 -> Triple(c, x, 0f); 1 -> Triple(x, c, 0f); 2 -> Triple(0f, c, x)
            3 -> Triple(0f, x, c); 4 -> Triple(x, 0f, c); else -> Triple(c, 0f, x)
        }
        fun byte(v: Float) = ((v + m) * 255).roundToInt().coerceIn(0, 255).toLong()
        return (alpha.coerceIn(0, 255).toLong() shl 24) or (byte(r) shl 16) or (byte(g) shl 8) or byte(b)
    }

    companion object {
        fun from(argb: Long): StudioColorSelection {
            val r = (argb shr 16 and 255).toFloat() / 255
            val g = (argb shr 8 and 255).toFloat() / 255
            val b = (argb and 255).toFloat() / 255
            val max = maxOf(r, g, b); val min = minOf(r, g, b); val delta = max - min
            val hue = if (delta == 0f) 0f else when (max) {
                r -> 60f * (((g - b) / delta + 6f) % 6f)
                g -> 60f * ((b - r) / delta + 2f)
                else -> 60f * ((r - g) / delta + 4f)
            }
            return StudioColorSelection(hue, if (max == 0f) 0f else delta / max, max, (argb shr 24 and 255).toInt())
        }
    }
}

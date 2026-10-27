package com.autoscript.studio

import com.autoscript.project.store.PopupStyle
import com.autoscript.project.store.ProjectDebugSettings
import java.util.Locale

internal val POPUP_NUMBER_RANGES = mapOf(
    "width" to 1..2160, "height" to 1..1200, "x" to -1..10000, "y" to -1..10000,
    "font" to 10..160, "corner" to 0..200, "duration" to 500..30000,
)

internal enum class PopupPositionPreset(val title: String, val column: Int, val row: Int) {
    TOP_LEFT("左上", 0, 0), TOP_CENTER("上中", 1, 0), TOP_RIGHT("右上", 2, 0),
    CENTER_LEFT("左中", 0, 1), CENTER("居中", 1, 1), CENTER_RIGHT("右中", 2, 1),
    BOTTOM_LEFT("左下", 0, 2), BOTTOM_CENTER("下中", 1, 2), BOTTOM_RIGHT("右下", 2, 2),
}

internal data class PopupStyleDraft(val inputs: Map<String, String>, val textAlign: String) {
    fun edit(key: String, value: String): PopupStyleDraft = copy(inputs = inputs + (key to value))

    fun withPosition(preset: PopupPositionPreset, screenWidth: Int, screenHeight: Int): PopupStyleDraft? {
        if (!valid("width") || !valid("height") || screenWidth <= 0 || screenHeight <= 0) return null
        fun coordinate(index: Int, screen: Int, size: Int): Int = when (index) {
            0 -> 0
            1 -> -1
            else -> screen - minOf(size, screen)
        }
        val x = coordinate(preset.column, screenWidth, inputs.getValue("width").toInt())
        val y = coordinate(preset.row, screenHeight, inputs.getValue("height").toInt())
        if (x !in POPUP_NUMBER_RANGES.getValue("x") || y !in POPUP_NUMBER_RANGES.getValue("y")) return null
        return edit("x", x.toString()).edit("y", y.toString())
    }

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

internal class PopupStyleSaveRequest(val draft: PopupStyleDraft, val settings: ProjectDebugSettings)

/** Only the matching disk-save acknowledgement advances this form's saved baseline. */
internal data class PopupStyleSaveState(
    val draft: PopupStyleDraft,
    val saved: PopupStyleDraft = draft,
    val pending: PopupStyleSaveRequest? = null,
    val message: String? = null,
    val error: String? = null,
) {
    val dirty: Boolean get() = draft != saved

    fun edit(value: PopupStyleDraft): PopupStyleSaveState = copy(draft = value, message = null, error = null)

    fun observe(style: PopupStyle): PopupStyleSaveState = if (pending == null && !dirty) {
        val refreshed = PopupStyleDraft.from(style)
        copy(draft = refreshed, saved = refreshed)
    } else this

    fun submit(settings: ProjectDebugSettings): PopupStyleSaveState {
        if (pending != null) return this
        val parsed = draft.parse() ?: return copy(message = null, error = "请完成红框内的尺寸、坐标或颜色输入后再保存")
        return copy(pending = PopupStyleSaveRequest(draft, settings.copy(popupStyle = parsed)),
            message = "正在保存配置…", error = null)
    }

    fun complete(request: PopupStyleSaveRequest, result: Result<ProjectDebugSettings>): PopupStyleSaveState {
        if (pending !== request) return this
        val persisted = result.getOrNull()?.popupStyle
        if (persisted != request.settings.popupStyle) return copy(pending = null, message = null,
            error = if (result.isFailure) "配置未保存，输入已保留，请重试" else "保存结果与提交不一致，输入已保留")
        val unchanged = draft == request.draft
        val baseline = if (unchanged) PopupStyleDraft.from(persisted) else request.draft
        return copy(draft = if (unchanged) baseline else draft, saved = baseline, pending = null, error = null,
            message = if (unchanged) "配置已保存" else "上次配置已保存，当前修改尚未保存")
    }
}

internal fun popupPreviewScale(widthPx: Int, heightPx: Int, availableWidthPx: Float, availableHeightPx: Float): Float {
    val width = availableWidthPx.takeIf { it.isFinite() && it > 0f } ?: 1f
    val height = availableHeightPx.takeIf { it.isFinite() && it > 0f } ?: 1f
    return minOf(1f, width / widthPx.coerceAtLeast(1), height / heightPx.coerceAtLeast(1))
}

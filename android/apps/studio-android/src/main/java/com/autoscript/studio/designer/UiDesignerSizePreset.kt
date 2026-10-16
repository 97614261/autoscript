package com.autoscript.studio

import com.autoscript.script.ui.ScriptUiControlMetrics
import com.autoscript.script.ui.UiPresentation

internal enum class DesignerSizePreset(val label: String, val width: Int, val height: Int, val fontPx: Int) {
    SMALL("小", 160, 32, 16),
    MEDIUM("中", ScriptUiControlMetrics.DEFAULT_WIDTH, ScriptUiControlMetrics.DEFAULT_HEIGHT, ScriptUiControlMetrics.DEFAULT_FONT),
    LARGE("大", 256, 48, 24);

    fun matches(ui: UiPresentation) = ui.width == width && ui.height == height && ui.fontPx == fontPx
}

/** Explicit manual sizing: one undoable change, without automatic content fitting or neighbor moves. */
internal fun RunnerUiDesignerDraft.withSizePreset(ids: Set<String>, preset: DesignerSizePreset): RunnerUiDesignerDraft {
    require(ids.isNotEmpty() && ids.all { id -> fields.any { it.id == id } }) { "请先选择控件" }
    val next = copy(fields = fields.map { field ->
        if (field.id in ids) field.copy(ui = field.ui.copy(width = preset.width, height = preset.height, fontPx = preset.fontPx)) else field
    })
    return checkedGeometryChange(next, ids, "${preset.label}尺寸")
}

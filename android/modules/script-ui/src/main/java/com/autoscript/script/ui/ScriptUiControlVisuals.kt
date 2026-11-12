package com.autoscript.script.ui

import kotlin.math.roundToInt

data class ScriptUiPreviewText(val text: String, val placeholder: Boolean = false)

/** Shared display policy; values, event routing and saved properties are unaffected. */
object ScriptUiControlVisuals {
    const val ACCENT = 0xFF386AFF.toInt()
    const val LINE = 0xFFE2E7F0.toInt()
    const val DISABLED_ALPHA = .45f
    const val HINT_ALPHA = .55f
    const val PRESSED = 0x1F000000

    fun text(control: UiControl, value: String, label: String): ScriptUiPreviewText = when (control) {
        UiControl.SELECT -> ScriptUiPreviewText(value.ifEmpty { "未选择" }, value.isEmpty())
        UiControl.INPUT, UiControl.TEXTAREA, UiControl.NUMBER, UiControl.DECIMAL, UiControl.PASSWORD ->
            ScriptUiPreviewText(if (control == UiControl.PASSWORD && value.isNotEmpty()) "\u2022".repeat(value.length) else value.ifEmpty { label }, value.isEmpty())
        else -> ScriptUiPreviewText(value.ifEmpty { label })
    }

    fun hintColor(color: Int): Int = (color and 0x00FFFFFF) or (((color ushr 24) * HINT_ALPHA).roundToInt() shl 24)
    fun cornerRadius(corner: Float, width: Float, height: Float) = corner.coerceIn(0f, minOf(width, height).coerceAtLeast(0f) / 2f)
}

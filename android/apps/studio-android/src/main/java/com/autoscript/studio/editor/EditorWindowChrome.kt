package com.autoscript.studio

internal enum class EditorPanelMode { NORMAL, MAXIMIZED }
internal const val EDITOR_HEADER_HEIGHT_DP = 36
internal const val EDITOR_HEADER_BUTTON_WIDTH_DP = 28
internal const val EDITOR_HEADER_INSET_DP = 4
internal const val EDITOR_HEADER_RIGHT_ACTION_COUNT = 3

internal data class EditorPanelPlacement(val x: Float, val y: Float, val width: Float, val height: Float)

internal fun editorWorkArea(width: Int, height: Int, left: Int, top: Int, right: Int, bottom: Int): androidx.compose.ui.geometry.Rect {
    val w = width.coerceAtLeast(1); val h = height.coerceAtLeast(1)
    val x = left.coerceIn(0, w - 1); val y = top.coerceIn(0, h - 1)
    return androidx.compose.ui.geometry.Rect(x.toFloat(), y.toFloat(),
        (w - right.coerceAtLeast(0)).coerceIn(x + 1, w).toFloat(),
        (h - bottom.coerceAtLeast(0)).coerceIn(y + 1, h).toFloat())
}

internal fun editorTogglePanelMode(current: EditorPanelMode, target: EditorPanelMode): EditorPanelMode =
    if (current == target) EditorPanelMode.NORMAL else target

internal fun editorPanelDisplayBounds(normal: EditorPanelPlacement, mode: EditorPanelMode,
    viewportWidth: Float, viewportHeight: Float, workArea: androidx.compose.ui.geometry.Rect? = null): EditorPanelPlacement {
    if (mode == EditorPanelMode.NORMAL) return normal
    val width = viewportWidth.takeIf { it.isFinite() && it > 0f } ?: 1f
    val height = viewportHeight.takeIf { it.isFinite() && it > 0f } ?: 1f
    return workArea?.let { EditorPanelPlacement(it.left, it.top, it.width, it.height) }
        ?: EditorPanelPlacement(0f, 0f, width, height)
}

internal fun editorHeaderIsButtonArea(x: Float, width: Float, buttonWidth: Float, inset: Float): Boolean {
    val end = width - inset
    return (x >= inset && x < inset + buttonWidth) ||
        (x >= maxOf(0f, end - EDITOR_HEADER_RIGHT_ACTION_COUNT * buttonWidth) && x < end)
}

internal fun editorRestoredDragOrigin(normal: EditorPanelPlacement, displayed: EditorPanelPlacement,
    downX: Float, downY: Float, minX: Float, maxX: Float, maxY: Float): Pair<Float, Float> {
    val fraction = (downX / displayed.width.coerceAtLeast(1f)).coerceIn(0f, 1f)
    return (displayed.x + downX - normal.width * fraction).coerceIn(minX, maxX) to
        (displayed.y + downY - downY.coerceAtLeast(0f)).coerceIn(0f, maxY)
}

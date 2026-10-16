package com.autoscript.studio

import kotlin.math.roundToInt

internal data class EditorMorePlacement(val x: Int, val y: Int)

/** More belongs to the existing panel window; opening it must not enlarge the native host. */
internal fun editorMoreViewport(panel: EditorPanelPlacement, viewportWidth: Float, viewportHeight: Float): EditorPanelPlacement {
    val screenWidth = viewportWidth.coerceAtLeast(1f)
    val screenHeight = viewportHeight.coerceAtLeast(1f)
    val left = panel.x.coerceIn(0f, screenWidth - 1f)
    val top = panel.y.coerceIn(0f, screenHeight - 1f)
    val right = (panel.x + panel.width).coerceIn(left + 1f, screenWidth)
    val bottom = (panel.y + panel.height).coerceIn(top + 1f, screenHeight)
    return EditorPanelPlacement(left, top, right - left, bottom - top)
}

/** Place the measured menu against its trigger, not a reserved oversized rectangle. */
internal fun editorMorePlacement(
    viewportWidth: Float, viewportHeight: Float, menuWidth: Float, menuHeight: Float,
    anchorRight: Float, anchorTop: Float, anchorBottom: Float,
): EditorMorePlacement {
    val width = menuWidth.coerceIn(0f, viewportWidth.coerceAtLeast(0f))
    val height = menuHeight.coerceIn(0f, viewportHeight.coerceAtLeast(0f))
    val y = if (anchorTop >= height || anchorTop >= viewportHeight - anchorBottom) anchorTop - height else anchorBottom
    return EditorMorePlacement(
        (anchorRight - width).coerceIn(0f, (viewportWidth - width).coerceAtLeast(0f)).roundToInt(),
        y.coerceIn(0f, (viewportHeight - height).coerceAtLeast(0f)).roundToInt(),
    )
}

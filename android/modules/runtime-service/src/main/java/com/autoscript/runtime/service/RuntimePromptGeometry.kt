package com.autoscript.runtime.service

internal data class RuntimePromptGeometry(val width: Int, val height: Int, val x: Int, val y: Int)

/** A transient tip takes visual priority only when rectangles overlap; neither origin is moved. */
internal fun runtimePromptsOverlap(first: RuntimePromptGeometry, second: RuntimePromptGeometry): Boolean =
    first.x < second.x + second.width && second.x < first.x + first.width &&
        first.y < second.y + second.height && second.y < first.y + first.height

/** Physical screen pixels, not dp, project design coordinates or status-bar-relative positions. */
internal fun runtimePromptGeometry(
    screenWidth: Int, screenHeight: Int, widthPx: Int, heightPx: Int, xPx: Int, yPx: Int,
): RuntimePromptGeometry {
    val width = widthPx.coerceIn(1, screenWidth.coerceAtLeast(1))
    val height = heightPx.coerceIn(1, screenHeight.coerceAtLeast(1))
    val maximumX = (screenWidth - width).coerceAtLeast(0)
    val maximumY = (screenHeight - height).coerceAtLeast(0)
    return RuntimePromptGeometry(width, height,
        (if (xPx == -1) maximumX / 2 else xPx).coerceIn(0, maximumX),
        (if (yPx == -1) maximumY / 2 else yPx).coerceIn(0, maximumY))
}

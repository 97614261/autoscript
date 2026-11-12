package com.autoscript.studio

internal data class ImageWorkspaceLayout(val side: Boolean, val panelWidthDp: Float, val panelHeightDp: Float)

/** Keep window orientation stable when adjustResize has already reduced the height. */
internal fun imageWorkspaceLayout(
    width: Float, height: Float, usableHeight: Float, keyboard: Boolean,
    landscape: Boolean = width > height, compact: Boolean = false,
): ImageWorkspaceLayout {
    require(listOf(width, height, usableHeight).all { it.isFinite() && it >= 0f })
    val side = landscape && width >= 480f
    val panelWidth = (width * .38f).coerceIn(220f, 340f).coerceAtMost(width * .48f)
    val available = usableHeight.coerceAtMost(height)
    val panelHeight = (available * if (keyboard) .72f else .52f)
        .coerceAtMost(if (compact && !keyboard) 190f else 360f)
        .coerceAtMost((available - 40f).coerceAtLeast(0f))
    return ImageWorkspaceLayout(side, panelWidth, panelHeight)
}

package com.autoscript.studio

internal data class ImagePanelPosition(val x: Float, val y: Float)

/** Fractions describe the available travel, so keyboard/layout changes never overwrite placement. */
internal fun imagePanelOffset(position: ImagePanelPosition, width: Float, height: Float,
    panelWidth: Float, panelHeight: Float): Pair<Float, Float> {
    require(listOf(width, height, panelWidth, panelHeight).all { it.isFinite() && it >= 0f })
    require(position.x.isFinite() && position.y.isFinite())
    return position.x.coerceIn(0f, 1f) * (width - panelWidth).coerceAtLeast(0f) to
        position.y.coerceIn(0f, 1f) * (height - panelHeight).coerceAtLeast(0f)
}

internal fun imagePanelDrag(position: ImagePanelPosition, dx: Float, dy: Float, width: Float,
    height: Float, panelWidth: Float, panelHeight: Float): ImagePanelPosition {
    val (x, y) = imagePanelOffset(position, width, height, panelWidth, panelHeight)
    require(dx.isFinite() && dy.isFinite())
    val travelX = (width - panelWidth).coerceAtLeast(0f)
    val travelY = (height - panelHeight).coerceAtLeast(0f)
    return ImagePanelPosition(
        if (travelX > 0f) ((x + dx) / travelX).coerceIn(0f, 1f) else position.x.coerceIn(0f, 1f),
        if (travelY > 0f) ((y + dy) / travelY).coerceIn(0f, 1f) else position.y.coerceIn(0f, 1f))
}

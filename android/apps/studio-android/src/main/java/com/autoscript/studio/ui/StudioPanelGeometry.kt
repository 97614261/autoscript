package com.autoscript.studio

import kotlin.math.ceil

/** Available constraints already exclude the keyboard; never impose a minimum taller than them. */
internal fun studioPanelMaxHeight(availableDp: Float, screenDp: Int, marginDp: Int = 24): Float {
    val screen = (screenDp - marginDp).coerceAtLeast(1).toFloat()
    return if (availableDp.isFinite()) minOf(screen, availableDp.coerceAtLeast(1f)) else screen
}

internal fun studioCompactControlHeight(baseDp: Int, fontScale: Float): Float =
    ceil(baseDp * if (fontScale.isFinite() && fontScale > 1f) fontScale else 1f)

/** Use measured height: a resized overlay or keyboard can be short even in portrait. */
internal fun studioShortViewport(heightDp: Float): Boolean = heightDp.isFinite() && heightDp <= 400f

/** Four full labels on wide forms; never squeeze them into clipped fixed-width cells. */
internal fun debugOutputChoiceColumns(widthDp: Float, fontScale: Float): Int {
    val scale = if (fontScale.isFinite()) fontScale.coerceAtLeast(1f) else 1f
    val cell = 82f * scale
    return when {
        widthDp >= cell * 4 -> 4
        widthDp >= cell * 2 -> 2
        else -> 1
    }
}

internal fun studioPopupPreviewHeight(heightDp: Float): Float =
    if (heightDp.isFinite()) minOf(72f, heightDp.coerceAtLeast(0f) * .24f) else 72f

internal fun imageWorkbenchSideWidth(widthDp: Float, heightDp: Float, fontScale: Float): Float {
    if (!widthDp.isFinite() || widthDp < 560f || !studioShortViewport(heightDp)) return 0f
    val scale = if (fontScale.isFinite()) fontScale.coerceIn(1f, 1.6f) else 1f
    return (224f * scale).coerceAtMost(widthDp * .42f)
}

/** Expanded editors use the actual available viewport, not the former compact content cap. */
internal fun studioPanelViewportHeight(availableDp: Float, compactDp: Int, fullScreen: Boolean): Float =
    if (fullScreen && availableDp.isFinite()) availableDp.coerceAtLeast(1f)
    else studioPanelMaxHeight(availableDp, compactDp, marginDp = 0)

/** Keep header actions on one side; symmetric title padding uses their measured bounded width. */
internal fun studioHeaderActionsMaxWidth(widthDp: Float, pinnedWidthDp: Float = 44f): Float =
    if (widthDp.isFinite() && pinnedWidthDp.isFinite())
        (widthDp.coerceAtLeast(0f) * .35f - pinnedWidthDp.coerceAtLeast(0f)).coerceAtLeast(0f) else 0f

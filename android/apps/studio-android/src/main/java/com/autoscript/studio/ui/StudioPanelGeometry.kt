package com.autoscript.studio

import kotlin.math.ceil

/** Available constraints already exclude the keyboard; never impose a minimum taller than them. */
internal fun studioPanelMaxHeight(availableDp: Float, screenDp: Int, marginDp: Int = 24): Float {
    val screen = (screenDp - marginDp).coerceAtLeast(1).toFloat()
    return if (availableDp.isFinite()) minOf(screen, availableDp.coerceAtLeast(1f)) else screen
}

internal fun studioCompactControlHeight(baseDp: Int, fontScale: Float): Float =
    ceil(baseDp * if (fontScale.isFinite() && fontScale > 1f) fontScale else 1f)

/** Expanded editors use the actual available viewport, not the former compact content cap. */
internal fun studioPanelViewportHeight(availableDp: Float, compactDp: Int, fullScreen: Boolean): Float =
    if (fullScreen && availableDp.isFinite()) availableDp.coerceAtLeast(1f)
    else studioPanelMaxHeight(availableDp, compactDp, marginDp = 0)

/** Keep header actions on one side; symmetric title padding uses their measured bounded width. */
internal fun studioHeaderActionsMaxWidth(widthDp: Float, pinnedWidthDp: Float = 44f): Float =
    if (widthDp.isFinite() && pinnedWidthDp.isFinite())
        (widthDp.coerceAtLeast(0f) * .35f - pinnedWidthDp.coerceAtLeast(0f)).coerceAtLeast(0f) else 0f

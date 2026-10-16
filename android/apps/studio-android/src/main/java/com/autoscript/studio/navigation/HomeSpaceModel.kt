package com.autoscript.studio

import kotlin.math.abs
import kotlin.math.max

internal enum class HomeSpaceTheme(val label: String) { AURORA("极光"), ORBITS("星轨"), PARTICLES("粒子") }

internal fun nextHomeTheme(current: HomeSpaceTheme, direction: Int = 1): HomeSpaceTheme {
    val count = HomeSpaceTheme.entries.size
    return HomeSpaceTheme.entries[((current.ordinal + direction % count) + count) % count]
}

/** A horizontal background swipe changes only a local visual theme. */
internal fun homeThemeAfterSwipe(current: HomeSpaceTheme, dx: Float, dy: Float, width: Float): HomeSpaceTheme {
    if (!dx.isFinite() || !dy.isFinite() || !width.isFinite() || width <= 0f ||
        abs(dx) < max(48f, width * .14f) || abs(dx) < abs(dy) * 1.35f) return current
    return nextHomeTheme(current, if (dx < 0f) 1 else -1)
}

internal fun homeDragFraction(distance: Float, radius: Float): Float =
    if (!distance.isFinite() || !radius.isFinite() || radius <= 0f) 0f else (distance / radius).coerceIn(-1f, 1f)

internal data class HomeDemoFrame(val active: Int = -1, val completed: Boolean = false, val progress: Float = 0f,
    val activeLine: Int = -1, val lineProgress: Float = 0f)

/** A decorative, single-shot timeline. It has no script/runtime callbacks. */
internal fun homeDemoFrame(elapsedMs: Long?): HomeDemoFrame = when {
    elapsedMs == null || elapsedMs < 0 || elapsedMs >= 4_400 -> HomeDemoFrame()
    elapsedMs < 3_250 -> {
        val line = (elapsedMs / 650).toInt()
        HomeDemoFrame(active = when (line) { 0, 1 -> 0; 2 -> 1; else -> 2 }, progress = elapsedMs / 3_250f,
            activeLine = line, lineProgress = (elapsedMs % 650) / 650f)
    }
    else -> HomeDemoFrame(completed = true, progress = ((elapsedMs - 3_250) / 1_150f).coerceIn(0f, 1f))
}

internal fun homeDemoFirstVisibleLine(activeLine: Int, compact: Boolean): Int =
    if (!compact) 0 else (activeLine.coerceAtLeast(1) - 1).coerceIn(0, 2)

internal fun homeAmbientMotionAllowed(animatorsEnabled: Boolean, powerSaver: Boolean): Boolean = animatorsEnabled && !powerSaver

internal fun homeBlockRowHeight(fontScale: Float, compact: Boolean): Float {
    val scale = if (fontScale.isFinite()) fontScale.coerceIn(1f, 3f) else 1f
    return max(if (compact) 20f else 22f, 14f * scale + if (compact) 4f else 6f)
}

internal data class HomeSpaceLayout(val landscape: Boolean, val compactBlocks: Boolean, val contentHeightDp: Float,
    val diameterDp: Float, val centerXDp: Float, val centerYDp: Float, val headerReserveDp: Float,
    val footerReserveDp: Float, val playbackHeightDp: Float)

/** Budget text first, then give the original-aspect logo the remaining visual space. */
internal fun homeSpaceLayout(width: Float, height: Float, fontScale: Float): HomeSpaceLayout {
    val w = if (width.isFinite()) width.coerceAtLeast(1f) else 360f
    val h = if (height.isFinite()) height.coerceAtLeast(1f) else 580f
    val fs = if (fontScale.isFinite()) fontScale.coerceIn(1f, 3f) else 1f
    val wide = w > h
    val compact = wide || h < 520f || fs > 1.3f
    val header = max(if (wide) 68f else 70f, (if (wide) 20f else 44f) + 36f * fs + 6f)
    val footer = if (wide) 8f + max(28f, 16f * fs + 14f) + 12f
        else max(94f, 16f + max(28f, 16f * fs + 14f) + 12f * fs + 19f)
    val caption = if (wide) 11f * fs + 6f else (if (fs > 1.2f) 24f * fs + 3f else 12f * fs) + 10f
    val playback = homeBlockRowHeight(fs, compact) * (if (compact) 3 else 5) + (if (compact) 10f else 14f) + caption
    val content = max(h, header + footer + playback + if (wide) 8f else 112f)
    val visualTop = header + 6f
    val visualBottom = if (wide) content - footer else content - footer - playback - 16f
    val diameter = minOf(w * if (wide) .44f else .84f, content * if (wide) .75f else .48f, 320f,
        ((visualBottom - visualTop) / .7f).coerceAtLeast(0f))
    return HomeSpaceLayout(wide, compact, content, diameter, w * if (wide) .28f else .5f,
        (visualTop + visualBottom) / 2f, header, footer, playback)
}

package com.autoscript.studio

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import kotlin.math.ceil
import kotlin.math.floor
import com.autoscript.core.model.RuntimeConnectionPhase
import com.autoscript.core.model.RuntimeConnectionState
import com.autoscript.core.model.RuntimeEngineState

internal data class EditorWindowBounds(val left: Int, val top: Int, val width: Int, val height: Int)
internal data class EditorWindowVisibility(val panel: Boolean, val ball: Boolean)

internal fun editorPanelSize(workArea: Rect, density: Float, resized: Size? = null, initialViewport: Size? = null): Size {
    val width = workArea.width.takeIf { it.isFinite() && it > 0f } ?: 1f
    val height = workArea.height.takeIf { it.isFinite() && it > 0f } ?: 1f
    val scale = density.takeIf { it.isFinite() && it > 0f } ?: 1f
    // Dragging may use the full display; the default window must not grow to fill a short viewport.
    val initialWidth = initialViewport?.width?.takeIf { it.isFinite() && it > 0f } ?: width
    val initialHeight = initialViewport?.height?.takeIf { it.isFinite() && it > 0f } ?: height
    val initial = Size(minOf(EDITOR_DEFAULT_WIDTH_DP * scale, initialWidth * .9f, width),
        minOf(EDITOR_DEFAULT_HEIGHT_DP * scale, initialHeight * .78f, height))
    if (resized == null || !resized.width.isFinite() || !resized.height.isFinite()) return initial
    return Size(resized.width.coerceIn(minOf(210f * scale, initial.width), width),
        resized.height.coerceIn(minOf(260f * scale, initial.height), height))
}

/**
 * Seed the first native overlay layout before Compose has produced bounds.
 * A 1x1 bootstrap window cannot provide a useful composition constraint, so
 * waiting for onGloballyPositioned would otherwise leave the editor invisible.
 */
internal fun defaultEditorDockBounds(screenWidth: Int, screenHeight: Int, density: Float, workArea: Rect? = null,
    initialViewport: Size? = null): Rect {
    val safeWidth = screenWidth.coerceAtLeast(1).toFloat()
    val safeHeight = screenHeight.coerceAtLeast(1).toFloat()
    val safeDensity = density.takeIf { it.isFinite() && it > 0f } ?: 1f
    val screen = Rect(0f, 0f, safeWidth, safeHeight)
    val area = workArea?.takeIf { listOf(it.left, it.top, it.right, it.bottom).all(Float::isFinite) }
        ?.intersect(screen)?.takeIf { it.width > 0f && it.height > 0f } ?: screen
    val size = editorPanelSize(area, safeDensity, initialViewport = initialViewport)
    val width = size.width
    val height = size.height
    val x = (4f * safeDensity).coerceIn(area.left, (area.right - width).coerceAtLeast(area.left))
    val y = (area.bottom - height - 76f * safeDensity).coerceIn(area.top, (area.bottom - height).coerceAtLeast(area.top))
    return Rect(x, y, x + width, y + height)
}

internal fun editorWindowVisibility(hidden: Boolean, expanded: Boolean, canvas: Boolean, modal: Boolean) =
    EditorWindowVisibility(
        panel = !hidden && (expanded || canvas || modal),
        ball = !hidden && !expanded && !canvas && !modal,
    )

/** Crop the native window, not the editor's virtual canvas, so outside touches reach the target App. */
internal fun editorWindowBounds(left: Float, top: Float, right: Float, bottom: Float,
    screenWidth: Int, screenHeight: Int, fullScreen: Boolean = false): EditorWindowBounds {
    val w = screenWidth.coerceAtLeast(1)
    val h = screenHeight.coerceAtLeast(1)
    if (fullScreen) return EditorWindowBounds(0, 0, w, h)
    if (!listOf(left, top, right, bottom).all(Float::isFinite) || right <= left || bottom <= top)
        return EditorWindowBounds(0, 0, 1, 1)
    val x = floor(left).toInt().coerceIn(0, w - 1)
    val y = floor(top).toInt().coerceIn(0, h - 1)
    val r = ceil(right).toInt().coerceIn(x + 1, w)
    val b = ceil(bottom).toInt().coerceIn(y + 1, h)
    return EditorWindowBounds(x, y, r - x, b - y)
}

internal fun validEditorProjectId(value: String?): Boolean =
    value != null && Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}").matches(value)

/** A single host must never silently replace an unsaved project. */
internal enum class EditorSessionDecision { OPEN, RESTORE, REJECT }
internal fun editorSessionDecision(current: String?, requested: String): EditorSessionDecision = when {
    current == null -> EditorSessionDecision.OPEN
    current == requested -> EditorSessionDecision.RESTORE
    else -> EditorSessionDecision.REJECT
}

/** Restore is not a project mutation: keep the same project's Edit entry available after minimize. */
internal fun editorEntryEnabled(available: Boolean, editorProjectId: String?, launchProjectId: String?, requested: String): Boolean =
    available && launchProjectId == null && (editorProjectId == null || editorProjectId == requested)

internal fun editorRunAcknowledged(before: Long, state: RuntimeConnectionState): Boolean =
    state.phase != RuntimeConnectionPhase.CONNECTED || (state.stateRevision > before &&
        state.engineState in setOf(RuntimeEngineState.RUNNING, RuntimeEngineState.PAUSED,
            RuntimeEngineState.STOPPED, RuntimeEngineState.FAILED))

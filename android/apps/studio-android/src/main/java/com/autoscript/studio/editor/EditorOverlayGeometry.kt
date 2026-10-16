package com.autoscript.studio

import androidx.compose.ui.geometry.Rect
import kotlin.math.ceil
import kotlin.math.floor
import com.autoscript.core.model.RuntimeConnectionPhase
import com.autoscript.core.model.RuntimeConnectionState
import com.autoscript.core.model.RuntimeEngineState

internal data class EditorWindowBounds(val left: Int, val top: Int, val width: Int, val height: Int)
internal data class EditorWindowVisibility(val panel: Boolean, val ball: Boolean)

/**
 * Seed the first native overlay layout before Compose has produced bounds.
 * A 1x1 bootstrap window cannot provide a useful composition constraint, so
 * waiting for onGloballyPositioned would otherwise leave the editor invisible.
 */
internal fun defaultEditorDockBounds(screenWidth: Int, screenHeight: Int, density: Float): Rect {
    val safeWidth = screenWidth.coerceAtLeast(1).toFloat()
    val safeHeight = screenHeight.coerceAtLeast(1).toFloat()
    val safeDensity = density.takeIf { it.isFinite() && it > 0f } ?: 1f
    val width = (292f * safeDensity).coerceAtMost(safeWidth)
    val height = (378f * safeDensity).coerceAtMost(safeHeight)
    val x = (4f * safeDensity).coerceIn(0f, (safeWidth - width).coerceAtLeast(0f))
    val y = (safeHeight - height - 76f * safeDensity).coerceIn(0f, (safeHeight - height).coerceAtLeast(0f))
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

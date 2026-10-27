package com.autoscript.studio

import com.autoscript.core.model.RuntimeEngineState

internal const val EDITOR_TREE_LEADING_DP = 2
internal const val EDITOR_TREE_INDENT_STEP_DP = 8
internal const val EDITOR_TREE_EXPAND_WIDTH_DP = 16
internal const val EDITOR_TREE_CHECK_WIDTH_DP = 20

internal fun editorTreeRowIndent(depth: Int): Int = EDITOR_TREE_LEADING_DP + depth.coerceIn(0, 32) * EDITOR_TREE_INDENT_STEP_DP
internal fun editorTreeGuideOffset(level: Int): Int = EDITOR_TREE_LEADING_DP + EDITOR_TREE_EXPAND_WIDTH_DP / 2 +
    level.coerceIn(0, 31) * EDITOR_TREE_INDENT_STEP_DP

/** Reserve readable label space for the deepest row without a fixed 960dp empty canvas. */
internal fun editorTreeContentWidth(viewportDp: Float, deepestDepth: Int): Float =
    maxOf(viewportDp.takeIf { it.isFinite() && it >= 0f } ?: 0f,
        220f + deepestDepth.coerceIn(0, 32) * EDITOR_TREE_INDENT_STEP_DP)

internal fun editorCanMutate(busy: Boolean, readOnly: Boolean, state: RuntimeEngineState): Boolean =
    !busy && !readOnly && state !in setOf(
        RuntimeEngineState.RUNNING, RuntimeEngineState.PAUSED, RuntimeEngineState.STOPPING,
    )

internal fun editorCanStep(busy: Boolean, connected: Boolean, state: RuntimeEngineState, hasSelection: Boolean): Boolean =
    !busy && connected && (state == RuntimeEngineState.PAUSED ||
        (state !in setOf(RuntimeEngineState.RUNNING, RuntimeEngineState.STOPPING) && hasSelection))

/** Stop is independent of ordinary pause/resume/step requests. */
internal fun editorCanStop(connected: Boolean, state: RuntimeEngineState, stopPending: Boolean): Boolean =
    connected && !stopPending && state in setOf(RuntimeEngineState.RUNNING, RuntimeEngineState.PAUSED)

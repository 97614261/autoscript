package com.autoscript.studio

import com.autoscript.core.model.RuntimeEngineState

internal fun editorCanMutate(busy: Boolean, readOnly: Boolean, state: RuntimeEngineState): Boolean =
    !busy && !readOnly && state !in setOf(
        RuntimeEngineState.RUNNING, RuntimeEngineState.PAUSED, RuntimeEngineState.STOPPING,
    )

internal fun editorCanStep(busy: Boolean, connected: Boolean, state: RuntimeEngineState, hasSelection: Boolean): Boolean =
    !busy && connected && (state == RuntimeEngineState.PAUSED ||
        (state !in setOf(RuntimeEngineState.RUNNING, RuntimeEngineState.STOPPING) && hasSelection))

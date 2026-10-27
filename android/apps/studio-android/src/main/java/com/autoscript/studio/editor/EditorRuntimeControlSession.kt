package com.autoscript.studio

import com.autoscript.core.model.RuntimeConnectionPhase
import com.autoscript.core.model.RuntimeConnectionState
import com.autoscript.core.model.RuntimeEngineState

/** Main-thread ownership of the short Binder surface command, never script work.
 * Serial calls avoid a late asynchronous hide racing close/reopen. Reconnects must
 * suppress the new Runner session too; signed, nonzero generations remain valid.
 */
internal class EditorRuntimeControlSession {
    private var opened = false
    private var hiddenGeneration: Long? = null
    private var applying = false

    fun open(state: RuntimeConnectionState, setEnabled: (Boolean) -> Boolean) {
        opened = true
        observe(state, setEnabled)
    }

    fun observe(state: RuntimeConnectionState, setEnabled: (Boolean) -> Boolean) {
        if (!opened || applying) return
        if (state.phase != RuntimeConnectionPhase.CONNECTED) {
            hiddenGeneration = null
            return
        }
        val generation = state.sessionGeneration?.takeUnless { it == 0L } ?: return
        if (hiddenGeneration == generation) return
        applying = true
        try {
            if (setEnabled(false)) hiddenGeneration = generation
        } finally {
            applying = false
        }
    }

    fun close(state: RuntimeConnectionState, setEnabled: (Boolean) -> Boolean) {
        val hidden = hiddenGeneration
        opened = false
        hiddenGeneration = null
        // A stale session must never enable a different run's control surface.
        if (hidden != null && state.phase == RuntimeConnectionPhase.CONNECTED &&
            state.sessionGeneration == hidden &&
            state.engineState in setOf(RuntimeEngineState.RUNNING, RuntimeEngineState.PAUSED)) {
            setEnabled(true)
        }
    }
}

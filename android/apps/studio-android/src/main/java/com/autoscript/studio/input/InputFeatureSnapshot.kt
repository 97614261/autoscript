package com.autoscript.studio

import com.autoscript.core.model.RuntimeConnectionPhase
import com.autoscript.core.model.RuntimeConnectionState
import com.autoscript.core.model.RuntimeRootState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf

/** An old authenticated connection must not enable controls in a replacement session. */
internal data class InputFeatureSnapshot(val generation: Long?, val refresh: Int, val features: Int) {
    fun available(state: RuntimeConnectionState, revision: Int): Int? =
        if (state.phase == RuntimeConnectionPhase.CONNECTED && state.rootState == RuntimeRootState.READY &&
            generation != null && generation == state.sessionGeneration && refresh == revision) features else null
}

/** Temporary hiding belongs to a request, not to the user's minimize preference. */
internal class InputPickerLaunchState {
    private var revision = 0L
    private var returnToEditor: (() -> Boolean)? = null
    var preparing by mutableStateOf(false)
        private set
    fun begin(): Long { returnToEditor = null; preparing = true; return ++revision }
    fun current(ticket: Long): Boolean = ticket == revision
    fun finish(ticket: Long) { if (current(ticket)) preparing = false }
    fun background(ticket: Long, moveBehind: () -> Boolean, restore: () -> Boolean) {
        if (current(ticket) && moveBehind()) returnToEditor = restore
    }
    fun returned(ticket: Long): Boolean {
        if (!current(ticket)) return false
        preparing = false
        val restore = returnToEditor
        returnToEditor = null
        return runCatching { restore?.invoke() ?: true }.getOrDefault(false)
    }
    fun invalidate() { ++revision; preparing = false; returnToEditor = null }
}

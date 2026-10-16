package com.autoscript.studio

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.autoscript.runtime.api.RuntimeDebugReply
import com.autoscript.runtime.api.RuntimeDebugValue

internal data class RuntimeVariableKey(val scope: String, val flowId: String, val name: String)
internal fun RuntimeDebugValue.watchKey(flowId: String) = RuntimeVariableKey(scope, if (scope == "local") flowId else "", name)

/** Only observed, bounded values are retained. A sample is not an assignment trace. */
internal class RuntimeVariableWatchState {
    var reply by mutableStateOf(RuntimeDebugReply.unavailable())
        private set
    var status by mutableIntStateOf(RuntimeDebugReply.UNAVAILABLE)
        private set
    var changed by mutableStateOf<Set<RuntimeVariableKey>>(emptySet())
        private set
    var latestChanges by mutableStateOf<Set<RuntimeVariableKey>>(emptySet())
        private set

    fun clear() {
        reply = RuntimeDebugReply.unavailable(); status = RuntimeDebugReply.UNAVAILABLE
        changed = emptySet(); latestChanges = emptySet()
    }
    fun sample(next: RuntimeDebugReply): Boolean {
        if (next.status == RuntimeDebugReply.BUSY) { status = next.status; return false }
        if (next.status != RuntimeDebugReply.SUCCESS) { clear(); status = next.status; return false }
        if (next.sessionGeneration == 0L || next.runRevision <= 0L ||
            next.variables.map { it.watchKey(next.flowId) }.distinct().size != next.variables.size) {
            clear(); return false
        }
        if (reply.status == RuntimeDebugReply.SUCCESS && reply.sessionGeneration == next.sessionGeneration && next.runRevision < reply.runRevision) return false
        val sameRun = reply.status == RuntimeDebugReply.SUCCESS && reply.sessionGeneration == next.sessionGeneration && reply.runRevision == next.runRevision
        val previous = if (sameRun) reply.variables.associateBy { it.watchKey(reply.flowId) } else emptyMap()
        val keys = next.variables.map { it.watchKey(next.flowId) }.toSet()
        latestChanges = next.variables.filter { value ->
            val old = previous[value.watchKey(next.flowId)]
            old != null && (old.type != value.type || old.value != value.value || old.truncated != value.truncated)
        }.map { it.watchKey(next.flowId) }.toSet()
        changed = ((if (sameRun) changed else emptySet()) + latestChanges).intersect(keys)
        reply = next; status = next.status
        return true
    }
    fun values(filter: String, query: String, lockedFlow: String?): List<RuntimeDebugValue> = reply.variables.filter {
        (it.scope != "local" || lockedFlow == null || reply.flowId == lockedFlow) &&
            (filter == "changed" && it.watchKey(reply.flowId) in changed || filter == it.scope) && it.name.contains(query, ignoreCase = true)
    }
}

internal data class RuntimeVariableWindowRect(val x: Int, val y: Int, val width: Int, val height: Int)
internal fun runtimeVariableWindowRect(screenWidth: Int, screenHeight: Int, normalWidth: Int, normalHeight: Int,
    x: Int, y: Int, maximized: Boolean, collapsed: Boolean, headerHeight: Int): RuntimeVariableWindowRect {
    val width = (if (maximized && !collapsed) screenWidth else normalWidth).coerceIn(1, screenWidth.coerceAtLeast(1))
    val height = (if (collapsed) headerHeight else if (maximized) screenHeight else normalHeight).coerceIn(1, screenHeight.coerceAtLeast(1))
    return RuntimeVariableWindowRect(if (maximized && !collapsed) 0 else x.coerceIn(0, (screenWidth - width).coerceAtLeast(0)),
        if (maximized && !collapsed) 0 else y.coerceIn(0, (screenHeight - height).coerceAtLeast(0)), width, height)
}

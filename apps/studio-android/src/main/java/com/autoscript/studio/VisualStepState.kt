package com.autoscript.studio

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.autoscript.core.model.RuntimeConnectionPhase
import com.autoscript.core.model.RuntimeConnectionState
import com.autoscript.core.model.RuntimeEngineState
import com.autoscript.runtime.api.RuntimeDebugReply
import com.autoscript.runtime.client.RuntimeClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class VisualDebugPosition(val flowId: String, val nodeId: String)

internal fun debugPosition(reply: RuntimeDebugReply): VisualDebugPosition? =
    if (reply.status == RuntimeDebugReply.SUCCESS && reply.flowId.isNotBlank() && reply.nodeId.isNotBlank())
        VisualDebugPosition(reply.flowId, reply.nodeId) else null

internal class VisualStepState {
    var position by mutableStateOf<VisualDebugPosition?>(null)
        private set
    var pending by mutableStateOf(false)
        private set
    var loading by mutableStateOf(false)
        private set
    private var continuing = false
    val pendingMessage: String get() = if (continuing) "正在继续运行…可随时停止" else "单步执行中…可随时停止"

    fun begin(continueRun: Boolean = false): Boolean {
        if (pending || loading || position == null) return false
        pending = true
        continuing = continueRun
        return true
    }
    fun rejected() { pending = false }
    fun fetching() { loading = true; position = null }
    fun paused(position: VisualDebugPosition?) { this.position = position; loading = false; pending = false }
    fun running() { position = null; loading = false; if (continuing) pending = false }
    fun reset() { position = null; loading = false; pending = false }
}

/** Event-driven one-shot snapshot, never a periodic poll; the Runner validates project/session identity. */
@Composable
internal fun rememberVisualStepState(projectId: String?, runtime: RuntimeConnectionState, client: RuntimeClient): VisualStepState {
    val state = remember(projectId) { VisualStepState() }
    LaunchedEffect(projectId, runtime.phase, runtime.sessionGeneration, runtime.stateRevision, runtime.engineState) {
        if (projectId == null || runtime.phase != RuntimeConnectionPhase.CONNECTED) state.reset()
        else when (runtime.engineState) {
            RuntimeEngineState.PAUSED -> {
                state.fetching()
                val reply = withContext(Dispatchers.IO) { client.debugSnapshot(projectId, runtime.sessionGeneration) }
                state.paused(debugPosition(reply))
            }
            RuntimeEngineState.RUNNING -> state.running()
            else -> state.reset()
        }
    }
    return state
}

package com.autoscript.studio

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.autoscript.core.model.RuntimeConnectionPhase
import com.autoscript.core.model.RuntimeConnectionState
import com.autoscript.core.model.RuntimeEngineState
import com.autoscript.runtime.api.RuntimeDebugReply
import com.autoscript.runtime.api.RuntimeProtocol
import com.autoscript.runtime.client.RuntimeClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext

internal data class VisualDebugPosition(val flowId: String, val nodeId: String, val recent: Boolean = false)

@Composable
internal fun rememberVisualExecutionPosition(projectId: String?, runtime: RuntimeConnectionState, client: RuntimeClient): VisualDebugPosition? {
    val position by client.executionPosition.collectAsState()
    val trail = remember(projectId, runtime.sessionGeneration) { EditorExecutionTrail() }
    val eligible = runtime.phase == RuntimeConnectionPhase.CONNECTED && runtime.engineState == RuntimeEngineState.RUNNING
    LaunchedEffect(trail, eligible) { if (eligible) trail.follow() else trail.clear() }
    LaunchedEffect(trail, eligible, position) {
        position?.takeIf { eligible && it.projectId == projectId && it.sessionGeneration == runtime.sessionGeneration }
            ?.let(trail::offer) ?: trail.clear()
    }
    val displayed by trail.position.collectAsState()
    return displayed.takeIf { eligible }
}

internal suspend fun requestVisualStep(state: VisualStepState, onFailure: (String) -> Unit, request: suspend () -> Boolean) {
    var accepted = false
    val ticket = state.requestTicket
    try {
        accepted = request()
        if (!accepted && state.requestTicket == ticket) onFailure("运行控制被拒绝：当前项目的会话或暂停状态已变化")
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        if (state.requestTicket == ticket) onFailure(failure.message ?: "运行控制请求失败")
    } finally {
        if (!accepted) state.rejected(ticket)
    }
}

internal fun debugPosition(reply: RuntimeDebugReply): VisualDebugPosition? =
    if (reply.status == RuntimeDebugReply.SUCCESS && reply.runtimeState == RuntimeProtocol.STATE_PAUSED && reply.flowId.isNotBlank() && reply.nodeId.isNotBlank())
        VisualDebugPosition(reply.flowId, reply.nodeId) else null

internal class VisualStepState {
    var snapshot by mutableStateOf<RuntimeDebugReply?>(null)
        private set
    var position by mutableStateOf<VisualDebugPosition?>(null)
        private set
    var pending by mutableStateOf(false)
        private set
    var loading by mutableStateOf(false)
        private set
    private var continuing = false
    private var readRevision = 0L
    var requestTicket = 0L
        private set
    val pendingMessage: String get() = if (continuing) "正在继续运行…可随时停止" else "单步执行中…可随时停止"

    fun begin(continueRun: Boolean = false): Boolean {
        if (pending || loading || position == null) return false
        ++requestTicket
        pending = true
        continuing = continueRun
        return true
    }
    fun rejected(ticket: Long = requestTicket) { if (ticket == requestTicket) pending = false }
    fun fetching(): Long { loading = true; position = null; snapshot = null; return ++readRevision }
    fun fetched(ticket: Long, reply: RuntimeDebugReply): Boolean {
        if (!loading || ticket != readRevision) return false
        paused(reply)
        return true
    }
    fun fetchFinished(ticket: Long) {
        if (loading && readRevision == ticket) paused(null as VisualDebugPosition?)
    }
    fun paused(position: VisualDebugPosition?) { ++requestTicket; this.position = position; snapshot = null; loading = false; pending = false }
    fun paused(reply: RuntimeDebugReply) {
        paused(debugPosition(reply))
        if (position != null) snapshot = reply
    }
    fun running() { ++readRevision; position = null; snapshot = null; loading = false; if (continuing) pending = false }
    fun reset() { ++readRevision; ++requestTicket; position = null; snapshot = null; loading = false; pending = false; continuing = false }
}

/** Event-driven one-shot snapshot, never a periodic poll; the Runner validates project/session identity. */
@Composable
internal fun rememberVisualStepState(projectId: String?, runtime: RuntimeConnectionState, client: RuntimeClient): VisualStepState {
    val state = remember(projectId) { VisualStepState() }
    LaunchedEffect(projectId, runtime.phase, runtime.sessionGeneration, runtime.stateRevision, runtime.engineState) {
        if (projectId == null || runtime.phase != RuntimeConnectionPhase.CONNECTED) state.reset()
        else when (runtime.engineState) {
            RuntimeEngineState.PAUSED -> {
                val ticket = state.fetching()
                editorOperation({}, { state.fetchFinished(ticket) }) {
                    val reply = withContext(Dispatchers.IO) { client.debugSnapshot(projectId, runtime.sessionGeneration) }
                    state.fetched(ticket, reply)
                }
            }
            RuntimeEngineState.RUNNING -> state.running()
            else -> state.reset()
        }
    }
    return state
}

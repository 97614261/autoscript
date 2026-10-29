package com.autoscript.runtime.client

import com.autoscript.core.model.RuntimeConnectionPhase
import com.autoscript.core.model.RuntimeConnectionState
import com.autoscript.core.model.RuntimeEngineState
import com.autoscript.core.model.RuntimeRootState
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

internal enum class RuntimeStartReadiness { WAITING, READY, REJECTED, TIMEOUT }

internal fun captureUnavailableMessage(state: RuntimeConnectionState): String = when {
    state.phase != RuntimeConnectionPhase.CONNECTED -> "Runner 连接已断开，请到运行环境重新连接"
    state.engineState in setOf(RuntimeEngineState.RUNNING, RuntimeEngineState.PAUSED, RuntimeEngineState.STOPPING) ->
        "脚本正在运行或暂停，请先停止脚本再截图"
    state.rootState == RuntimeRootState.FAILED -> "Root 后端启动失败；请确认 Root 授权，运行环境 / 调试详情可查看具体阶段"
    state.rootState != RuntimeRootState.READY -> "Root 后端尚未就绪，请在运行环境查看启动状态"
    else -> "Root 已认证，但截图采集失败 [CAPTURE_BACKEND_FAILED]；请查看运行环境 / 调试详情"
}

internal fun runtimeStartReadiness(state: RuntimeConnectionState, generation: Long): RuntimeStartReadiness = when {
    state.phase != RuntimeConnectionPhase.CONNECTED || state.sessionGeneration != generation -> RuntimeStartReadiness.REJECTED
    state.engineState in setOf(RuntimeEngineState.RUNNING, RuntimeEngineState.PAUSED, RuntimeEngineState.STOPPING) -> RuntimeStartReadiness.REJECTED
    state.rootState in setOf(RuntimeRootState.FAILED, RuntimeRootState.STOPPED) -> RuntimeStartReadiness.REJECTED
    state.engineState == RuntimeEngineState.UNKNOWN -> RuntimeStartReadiness.WAITING
    state.rootState == RuntimeRootState.READY -> RuntimeStartReadiness.READY
    else -> RuntimeStartReadiness.WAITING
}

/** One authorization attempt has 60 s, followed by bounded native attach and state delivery. */
internal suspend fun awaitRuntimeReady(
    states: StateFlow<RuntimeConnectionState>,
    generation: Long,
    timeoutMillis: Long = 70_000L,
): RuntimeStartReadiness = withTimeoutOrNull(timeoutMillis) {
    runtimeStartReadiness(states.first { runtimeStartReadiness(it, generation) != RuntimeStartReadiness.WAITING }, generation)
} ?: RuntimeStartReadiness.TIMEOUT

/** Passive state observation never requests Root; callers invoke this only for user demand. */
internal suspend fun requestRuntimeReady(
    states: StateFlow<RuntimeConnectionState>,
    generation: Long,
    request: suspend () -> Boolean,
    timeoutMillis: Long = 70_000L,
): RuntimeStartReadiness {
    val current = states.value
    if (current.phase != RuntimeConnectionPhase.CONNECTED || current.sessionGeneration != generation ||
        current.engineState in setOf(RuntimeEngineState.RUNNING, RuntimeEngineState.PAUSED, RuntimeEngineState.STOPPING))
        return RuntimeStartReadiness.REJECTED
    if (!request()) return RuntimeStartReadiness.REJECTED
    return awaitRuntimeReady(states, generation, timeoutMillis)
}

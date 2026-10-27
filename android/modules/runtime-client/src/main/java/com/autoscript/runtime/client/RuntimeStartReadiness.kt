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
    state.rootState == RuntimeRootState.FAILED -> RuntimeStartReadiness.REJECTED
    state.engineState == RuntimeEngineState.UNKNOWN -> RuntimeStartReadiness.WAITING
    state.rootState == RuntimeRootState.READY -> RuntimeStartReadiness.READY
    else -> RuntimeStartReadiness.WAITING
}

/** Root startup has a 15 s service-side deadline; allow callback delivery, but never wait forever. */
internal suspend fun awaitRuntimeReady(
    states: StateFlow<RuntimeConnectionState>,
    generation: Long,
    timeoutMillis: Long = 20_000L,
): RuntimeStartReadiness = withTimeoutOrNull(timeoutMillis) {
    runtimeStartReadiness(states.first { runtimeStartReadiness(it, generation) != RuntimeStartReadiness.WAITING }, generation)
} ?: RuntimeStartReadiness.TIMEOUT

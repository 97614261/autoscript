package com.autoscript.runtime.client

import com.autoscript.core.model.RuntimeConnectionPhase
import com.autoscript.core.model.RuntimeConnectionState
import com.autoscript.core.model.RuntimeEngineState
import com.autoscript.core.model.RuntimeRootState
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

internal enum class RuntimeStartReadiness { WAITING, READY, REJECTED, TIMEOUT }

internal fun runtimeStartReadiness(state: RuntimeConnectionState, generation: Long): RuntimeStartReadiness = when {
    state.phase != RuntimeConnectionPhase.CONNECTED || state.sessionGeneration != generation -> RuntimeStartReadiness.REJECTED
    state.engineState in setOf(RuntimeEngineState.RUNNING, RuntimeEngineState.PAUSED, RuntimeEngineState.STOPPING) -> RuntimeStartReadiness.REJECTED
    state.rootState == RuntimeRootState.FAILED -> RuntimeStartReadiness.REJECTED
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

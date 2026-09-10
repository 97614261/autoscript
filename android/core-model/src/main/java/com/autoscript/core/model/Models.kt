package com.autoscript.core.model

data class LocalProfile(
    val id: String,
    val displayName: String,
    val isRemoteAccount: Boolean,
)

enum class RuntimeConnectionPhase {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    ERROR,
}

enum class RuntimeEngineState {
    UNKNOWN,
    IDLE,
    RUNNING,
    STOPPING,
    STOPPED,
    FAILED,
}

data class RuntimeConnectionState(
    val phase: RuntimeConnectionPhase = RuntimeConnectionPhase.DISCONNECTED,
    val protocolVersion: Int? = null,
    val sessionGeneration: Long? = null,
    val engineState: RuntimeEngineState = RuntimeEngineState.UNKNOWN,
    val message: String? = null,
)

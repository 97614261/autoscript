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
    PAUSED,
    STOPPING,
    STOPPED,
    FAILED,
}

enum class RuntimeRootState {
    UNKNOWN,
    STOPPED,
    STARTING,
    READY,
    FAILED,
}

data class RuntimeConnectionState(
    val phase: RuntimeConnectionPhase = RuntimeConnectionPhase.DISCONNECTED,
    val protocolVersion: Int? = null,
    val sessionGeneration: Long? = null,
    val engineState: RuntimeEngineState = RuntimeEngineState.UNKNOWN,
    val rootState: RuntimeRootState = RuntimeRootState.UNKNOWN,
    val message: String? = null,
    /** Local event identity: a rapid RUNNING→PAUSED cycle must still invalidate Studio state. */
    val stateRevision: Long = 0,
)

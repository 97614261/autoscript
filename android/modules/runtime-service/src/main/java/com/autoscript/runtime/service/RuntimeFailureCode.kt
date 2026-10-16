package com.autoscript.runtime.service

// Only the engine's leading structural code is persisted, never Lua text or nested messages.
internal fun runtimeFailureCode(diagnostic: String?): String {
    val code = diagnostic?.substringBefore(':')
    return when (code) {
        "ROOT_STOP_CLEANUP_FAILED", "LUA_RUNTIME_ERROR", "LUA_MEMORY_LIMIT",
        "LUA_INSTRUCTION_LIMIT", "ENGINE_PANIC", "HOST_REQUEST_FAILED",
        "ROOT_DISCONNECTED", "SCREEN_CAPTURE_FAILED", "FRAME_OPERATION_FAILED" -> code
        else -> "ENGINE_FAILURE"
    }
}

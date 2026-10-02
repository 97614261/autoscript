package com.autoscript.runtime.service

import com.autoscript.runtime.api.RuntimeProtocol

internal data class RuntimeFloatingControls(
    val expanded: Boolean,
    val showCamera: Boolean,
    val pauseLabel: String,
    val canPauseResume: Boolean,
    val canStop: Boolean,
)

internal fun runtimeFloatingControls(state: Int, captureOnly: Boolean, expanded: Boolean) = RuntimeFloatingControls(
    expanded = expanded && !captureOnly,
    showCamera = captureOnly,
    pauseLabel = if (state == RuntimeProtocol.STATE_PAUSED) "继续" else "暂停",
    canPauseResume = !captureOnly && state in setOf(RuntimeProtocol.STATE_RUNNING, RuntimeProtocol.STATE_PAUSED),
    canStop = !captureOnly && state in setOf(RuntimeProtocol.STATE_RUNNING, RuntimeProtocol.STATE_PAUSED),
)

package com.autoscript.runtime.service

import com.autoscript.runtime.api.RuntimeProtocol
import com.autoscript.brand.ui.BrandState

/** STOPPED includes manual cancellation; it must never implicitly display success. */
internal fun runtimeBrandState(state: Int, normalCompletion: Boolean = false) = when (state) {
    RuntimeProtocol.STATE_STOPPED -> if (normalCompletion) BrandState.SUCCESS else BrandState.IDLE
    RuntimeProtocol.STATE_RUNNING -> BrandState.RUNNING
    RuntimeProtocol.STATE_PAUSED -> BrandState.PAUSED
    RuntimeProtocol.STATE_STOPPING -> BrandState.EXECUTING
    RuntimeProtocol.STATE_FAILED -> BrandState.FAILED
    else -> BrandState.IDLE
}

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

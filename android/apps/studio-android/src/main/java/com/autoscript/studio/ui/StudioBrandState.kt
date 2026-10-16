package com.autoscript.studio

import com.autoscript.brand.ui.BrandState
import com.autoscript.core.model.RuntimeConnectionState
import com.autoscript.core.model.RuntimeEngineState
import com.autoscript.core.model.RuntimeRootState

internal fun studioBrandState(state: RuntimeConnectionState): BrandState = when (state.engineState) {
    RuntimeEngineState.RUNNING -> BrandState.RUNNING
    RuntimeEngineState.PAUSED -> BrandState.PAUSED
    RuntimeEngineState.FAILED -> BrandState.FAILED
    RuntimeEngineState.STOPPING -> BrandState.EXECUTING
    else -> if (state.rootState == RuntimeRootState.STARTING) BrandState.EXECUTING else BrandState.IDLE
}

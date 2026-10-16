package com.autoscript.studio

import com.autoscript.core.model.RuntimeConnectionPhase
import com.autoscript.core.model.RuntimeConnectionState
import com.autoscript.core.model.RuntimeEngineState
import com.autoscript.core.model.RuntimeRootState

internal fun runtimeConnectionLabel(phase: RuntimeConnectionPhase): String = when (phase) {
    RuntimeConnectionPhase.CONNECTED -> "已连接"
    RuntimeConnectionPhase.CONNECTING -> "连接中"
    RuntimeConnectionPhase.DISCONNECTED -> "未连接"
    RuntimeConnectionPhase.ERROR -> "连接失败"
}

internal fun runtimeRootLabel(state: RuntimeRootState): String = when (state) {
    RuntimeRootState.READY -> "已就绪"
    RuntimeRootState.STARTING -> "启动中"
    RuntimeRootState.STOPPED -> "未启动"
    RuntimeRootState.FAILED -> "启动失败"
    RuntimeRootState.UNKNOWN -> "待检查"
}

internal fun runtimeExecutionLabel(state: RuntimeEngineState): String = when (state) {
    RuntimeEngineState.RUNNING -> "运行中"
    RuntimeEngineState.PAUSED -> "已暂停"
    RuntimeEngineState.STOPPING -> "停止清理中"
    RuntimeEngineState.FAILED -> "运行失败"
    RuntimeEngineState.IDLE -> "空闲"
    RuntimeEngineState.STOPPED -> "已停止"
    RuntimeEngineState.UNKNOWN -> "待检查"
}

/** Observed state is not permission: a disconnected old READY snapshot must not look healthy. */
internal fun runtimeReadinessProblem(state: RuntimeConnectionState): String? = when {
    state.phase != RuntimeConnectionPhase.CONNECTED -> "Runner ${runtimeConnectionLabel(state.phase)}，请检查运行环境"
    state.engineState == RuntimeEngineState.STOPPING -> "停止清理中，等待触点清理确认"
    state.rootState != RuntimeRootState.READY -> "Root ${runtimeRootLabel(state.rootState)}，请检查软件权限"
    state.engineState == RuntimeEngineState.UNKNOWN -> "等待引擎状态确认"
    else -> null
}

internal fun editorSaveSummary(dirtyPlugins: Int, saving: Boolean): String = when {
    saving -> "正在保存 · 草稿保留"
    dirtyPlugins > 0 -> "$dirtyPlugins 个插件未保存"
    else -> "草稿已保存"
}

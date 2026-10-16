package com.autoscript.studio

import com.autoscript.core.model.RuntimeConnectionPhase
import com.autoscript.core.model.RuntimeConnectionState
import com.autoscript.core.model.RuntimeEngineState

/** Only observes a request accepted from this editor; save success is not execution success. */
internal class EditorRunFeedback(before: RuntimeConnectionState, val singleStep: Boolean) {
    private val generation = before.sessionGeneration
    private var revision = before.stateRevision
    private var active = false
    var finished = false
        private set
    var stopRequested = false
    private val label get() = if (singleStep) "单步" else "编辑运行"
    val submitted get() = "$label 已提交，等待执行确认"

    fun observe(state: RuntimeConnectionState): String? {
        if (finished || state.stateRevision <= revision) return null
        revision = state.stateRevision
        if (state.phase != RuntimeConnectionPhase.CONNECTED || state.sessionGeneration != generation) {
            finished = true
            return "$label 执行状态中断，请查看日志"
        }
        return when (state.engineState) {
            RuntimeEngineState.RUNNING -> { active = true; "$label 执行中 · ▶ 为当前行" }
            RuntimeEngineState.PAUSED -> { active = true; "$label 已暂停 · 单步执行下一步，继续恢复运行" }
            RuntimeEngineState.STOPPING -> "$label 停止清理中"
            RuntimeEngineState.FAILED -> { finished = true; "$label 执行失败，请查看日志" }
            RuntimeEngineState.STOPPED -> { finished = true; if (stopRequested) "$label 已停止" else "$label 已结束，请查看输出" }
            RuntimeEngineState.IDLE -> if (active) { finished = true; "$label 已结束，请查看输出" } else null
            else -> null
        }
    }
}

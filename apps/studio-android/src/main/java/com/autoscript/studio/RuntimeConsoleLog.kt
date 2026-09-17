package com.autoscript.studio

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import com.autoscript.core.model.RuntimeConnectionPhase
import com.autoscript.core.model.RuntimeConnectionState
import com.autoscript.core.model.RuntimeEngineState
import com.autoscript.core.model.RuntimeRootState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 控制台最多保留的行数；超过后丢最旧的，避免长时间运行把 UI 撑爆。 */
internal const val MAX_CONSOLE_LINES = 200

/**
 * 悬浮面板“控制台”的数据源：Runner 连接、引擎状态、Root 状态和失败诊断的**真实**变化记录。
 *
 * Studio 现在只收到 [RuntimeConnectionState] 快照（含失败终态的有界诊断），
 * 脚本内 `Log(...)` 的输出流尚未经 AIDL 送到 Studio；空态文案要如实说明这一点，
 * 不能写成“运行后 Log 会显示在这里”。
 */
internal class RuntimeConsoleLog(
    private val now: () -> String = {
        SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
    },
) {
    val lines: SnapshotStateList<String> = mutableStateListOf()
    private var previous: RuntimeConnectionState? = null

    fun record(state: RuntimeConnectionState) {
        val delta = runtimeConsoleDelta(previous, state)
        previous = state
        if (delta.isEmpty()) return
        val stamp = now()
        delta.forEach { lines.add("[$stamp] $it") }
        while (lines.size > MAX_CONSOLE_LINES) lines.removeAt(0)
    }

    fun clear() {
        lines.clear()
    }
}

/**
 * 只把**变化了的**字段写成日志行，同一状态重复推送不产生重复行。
 * 顺序固定为 连接 → Root → 引擎 → 诊断，便于阅读。
 */
internal fun runtimeConsoleDelta(previous: RuntimeConnectionState?, next: RuntimeConnectionState): List<String> {
    val out = mutableListOf<String>()
    if (previous?.phase != next.phase) {
        out += when (next.phase) {
            RuntimeConnectionPhase.DISCONNECTED -> "Runner 已断开"
            RuntimeConnectionPhase.CONNECTING -> "正在连接 Runner…"
            RuntimeConnectionPhase.CONNECTED -> "Runner 已连接" + (next.protocolVersion?.let { " · AIDL v$it" } ?: "")
            RuntimeConnectionPhase.ERROR -> "Runner 连接失败"
        }
    }
    if (previous?.rootState != next.rootState && next.rootState != RuntimeRootState.UNKNOWN) {
        out += "Root " + when (next.rootState) {
            RuntimeRootState.STOPPED -> "已停止"
            RuntimeRootState.STARTING -> "启动中"
            RuntimeRootState.READY -> "已就绪"
            RuntimeRootState.FAILED -> "启动失败"
            RuntimeRootState.UNKNOWN -> "未知"
        }
    }
    if (previous?.engineState != next.engineState && next.engineState != RuntimeEngineState.UNKNOWN) {
        out += "引擎 " + when (next.engineState) {
            RuntimeEngineState.IDLE -> "空闲"
            RuntimeEngineState.RUNNING -> "运行中"
            RuntimeEngineState.PAUSED -> "已暂停"
            RuntimeEngineState.STOPPING -> "停止中"
            RuntimeEngineState.STOPPED -> "已停止"
            RuntimeEngineState.FAILED -> "失败"
            RuntimeEngineState.UNKNOWN -> "未知"
        }
    }
    val message = next.message?.trim().orEmpty()
    if (message.isNotEmpty() && message != previous?.message?.trim()) {
        out += "诊断：$message"
    }
    return out
}

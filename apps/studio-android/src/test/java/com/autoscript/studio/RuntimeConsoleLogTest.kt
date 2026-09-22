package com.autoscript.studio

import com.autoscript.core.model.RuntimeConnectionPhase
import com.autoscript.core.model.RuntimeConnectionState
import com.autoscript.core.model.RuntimeEngineState
import com.autoscript.core.model.RuntimeRootState
import org.junit.Assert.assertEquals
import org.junit.Test

class RuntimeConsoleLogTest {
    @Test
    fun `first snapshot logs only known fields`() {
        val delta = runtimeConsoleDelta(
            previous = null,
            next = RuntimeConnectionState(phase = RuntimeConnectionPhase.CONNECTED, protocolVersion = 14),
        )
        assertEquals(listOf("Runner 已连接 · AIDL v14"), delta)
    }

    @Test
    fun `only changed fields produce lines and repeats are silent`() {
        val connected = RuntimeConnectionState(
            phase = RuntimeConnectionPhase.CONNECTED,
            protocolVersion = 14,
            rootState = RuntimeRootState.READY,
            engineState = RuntimeEngineState.IDLE,
        )
        val running = connected.copy(engineState = RuntimeEngineState.RUNNING)
        assertEquals(listOf("引擎 运行中"), runtimeConsoleDelta(connected, running))
        assertEquals(emptyList<String>(), runtimeConsoleDelta(running, running))
    }

    @Test
    fun `failure diagnostic is logged once in fixed order`() {
        val running = RuntimeConnectionState(
            phase = RuntimeConnectionPhase.CONNECTED,
            protocolVersion = 14,
            rootState = RuntimeRootState.READY,
            engineState = RuntimeEngineState.RUNNING,
        )
        val failed = running.copy(
            engineState = RuntimeEngineState.FAILED,
            rootState = RuntimeRootState.FAILED,
            message = " CAPABILITY_DENIED: screen.capture ",
        )
        assertEquals(
            listOf("Root 启动失败", "引擎 失败", "诊断：CAPABILITY_DENIED: screen.capture"),
            runtimeConsoleDelta(running, failed),
        )
        assertEquals(emptyList<String>(), runtimeConsoleDelta(failed, failed))
    }

    @Test
    fun `log is bounded, stamped and drops the oldest lines`() {
        var tick = 0
        val log = RuntimeConsoleLog(now = { "t${tick++}" })
        // 205 次记录：第 0 次产生“已连接 + 引擎运行中”两行，之后每次状态翻转各一行，共 206 行。
        // 裁到 200 时丢掉最旧的 6 行（[t0]×2、[t1]…[t4]），剩下的第一行是 index 5（奇数→已停止）。
        repeat(MAX_CONSOLE_LINES + 5) { index ->
            log.record(
                RuntimeConnectionState(
                    phase = RuntimeConnectionPhase.CONNECTED,
                    engineState = if (index % 2 == 0) RuntimeEngineState.RUNNING else RuntimeEngineState.STOPPED,
                ),
            )
        }
        assertEquals(MAX_CONSOLE_LINES, log.lines.size)
        assertEquals("[t204] 引擎 运行中", log.lines.last())
        assertEquals("[t5] 引擎 已停止", log.lines.first())
        log.clear()
        assertEquals(0, log.lines.size)
    }

    @Test
    fun `remote snapshots append only their new suffix and preserve repeated messages`() {
        val log = RuntimeConsoleLog(now = { "unused" })
        log.recordRemote(listOf("1ms · 脚本/INFO: ready", "2ms · 脚本/INFO: ready"))
        log.recordRemote(listOf("1ms · 脚本/INFO: ready", "2ms · 脚本/INFO: ready"))
        log.recordRemote(listOf("2ms · 脚本/INFO: ready", "3ms · 脚本/WARN: retry"))
        assertEquals(
            listOf(
                "1ms · 脚本/INFO: ready",
                "2ms · 脚本/INFO: ready",
                "3ms · 脚本/WARN: retry",
            ),
            log.lines,
        )
    }
}

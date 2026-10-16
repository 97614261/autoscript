package com.autoscript.studio

import com.autoscript.core.model.*
import org.junit.Assert.*
import org.junit.Test

class RuntimeStatusPresentationTest {
    @Test fun stoppingIsNotIdleOrSuccessfulCleanup() {
        val state = RuntimeConnectionState(phase = RuntimeConnectionPhase.CONNECTED,
            rootState = RuntimeRootState.READY, engineState = RuntimeEngineState.STOPPING)
        assertEquals("停止清理中", studioRuntimeLabels(state).execution)
        assertTrue(runtimeReadinessProblem(state)!!.contains("清理确认"))
    }
    @Test fun staleReadyDisconnectedSnapshotIsNotPermission() {
        val state = RuntimeConnectionState(rootState = RuntimeRootState.READY, engineState = RuntimeEngineState.RUNNING)
        assertEquals("待重新确认", studioRuntimeLabels(state).root)
        assertEquals("状态未确认", studioRuntimeLabels(state).execution)
        assertNotNull(runtimeReadinessProblem(state))
    }
    @Test fun connectedReadyDoesNotImplyConfirmedEngine() {
        val state = RuntimeConnectionState(phase = RuntimeConnectionPhase.CONNECTED, rootState = RuntimeRootState.READY)
        assertEquals("等待引擎状态确认", runtimeReadinessProblem(state))
        assertNull(runtimeReadinessProblem(state.copy(engineState = RuntimeEngineState.STOPPED)))
    }
    @Test fun unsavedStateIsExplicitAndDoesNotClaimCrashPersistence() {
        assertEquals("3 个插件未保存", editorSaveSummary(3, false))
        assertEquals("正在保存 · 草稿保留", editorSaveSummary(3, true))
        assertEquals("草稿已保存", editorSaveSummary(0, false))
    }
}

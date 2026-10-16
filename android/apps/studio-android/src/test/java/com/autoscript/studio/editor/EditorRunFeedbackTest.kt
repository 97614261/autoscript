package com.autoscript.studio

import com.autoscript.core.model.RuntimeConnectionPhase
import com.autoscript.core.model.RuntimeConnectionState
import com.autoscript.core.model.RuntimeEngineState
import org.junit.Assert.*
import org.junit.Test

class EditorRunFeedbackTest {
    private fun state(revision: Long, engine: RuntimeEngineState, generation: Long = 3) =
        RuntimeConnectionState(phase = RuntimeConnectionPhase.CONNECTED, sessionGeneration = generation,
            engineState = engine, stateRevision = revision)

    @Test fun submissionIsNotCompletionAndPreparationIdleIsIgnored() {
        val feedback = EditorRunFeedback(state(10, RuntimeEngineState.STOPPED), true)
        assertTrue(feedback.submitted.contains("等待执行确认"))
        assertNull(feedback.observe(state(10, RuntimeEngineState.STOPPED)))
        assertNull(feedback.observe(state(11, RuntimeEngineState.IDLE)))
        assertTrue(feedback.observe(state(12, RuntimeEngineState.RUNNING))!!.contains("单步 执行中"))
        assertTrue(feedback.observe(state(13, RuntimeEngineState.STOPPED))!!.contains("已结束"))
        assertTrue(feedback.finished)
        assertNull(feedback.observe(state(14, RuntimeEngineState.RUNNING)))
    }

    @Test fun rapidTerminalFailureAndChangedGenerationAreNotSaveSuccess() {
        for (engine in listOf(RuntimeEngineState.STOPPED, RuntimeEngineState.FAILED)) {
            val feedback = EditorRunFeedback(state(0, RuntimeEngineState.IDLE), true)
            assertNotNull(feedback.observe(state(1, engine)))
            assertTrue(feedback.finished)
        }
        val feedback = EditorRunFeedback(state(0, RuntimeEngineState.IDLE), false)
        assertTrue(feedback.observe(state(1, RuntimeEngineState.RUNNING, 4))!!.contains("中断"))
        assertTrue(feedback.finished)
    }

    @Test fun pauseStepResumeAndPriorityStopKeepDistinctFeedback() {
        val feedback = EditorRunFeedback(state(0, RuntimeEngineState.IDLE), false)
        feedback.observe(state(1, RuntimeEngineState.RUNNING))
        assertTrue(feedback.observe(state(2, RuntimeEngineState.PAUSED))!!.contains("继续恢复"))
        assertTrue(feedback.observe(state(3, RuntimeEngineState.RUNNING))!!.contains("执行中"))
        feedback.stopRequested = true
        assertTrue(feedback.observe(state(4, RuntimeEngineState.STOPPING))!!.contains("清理中"))
        assertEquals("编辑运行 已停止", feedback.observe(state(5, RuntimeEngineState.STOPPED)))
    }
}

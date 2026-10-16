package com.autoscript.studio

import com.autoscript.runtime.api.RuntimeExecutionPosition
import com.autoscript.runtime.api.RuntimeNodePosition
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class EditorExecutionTrailTest {
    private fun sample(sequence: Long = 1, run: Long = 1, vararg ids: String) =
        RuntimeExecutionPosition(-7, run, "project-1", "main", ids.last(), sequence,
            ids.map { RuntimeNodePosition("main", it) })

    @Test fun fastDynamicPromptIsShownAndMarkedRecentNotCurrentlyExecuting() {
        val trail = EditorExecutionTrail()
        trail.offer(sample(ids = arrayOf("image-before", "dynamic-prompt", "image-after")))
        assertEquals(VisualDebugPosition("main", "image-before", true), trail.next())
        assertEquals(VisualDebugPosition("main", "dynamic-prompt", true), trail.next())
        assertEquals(VisualDebugPosition("main", "image-after", false), trail.next())
        assertEquals(VisualDebugPosition("main", "image-after", false), trail.next())
        assertEquals(0, trail.pendingCount)
    }

    @Test fun duplicateHotRowsDoNotStarvePromptAndPendingMemoryIsBounded() {
        val trail = EditorExecutionTrail()
        repeat(100) { index -> trail.offer(sample(index.toLong() + 1, ids = arrayOf("image", "prompt", "image"))) }
        assertEquals(2, trail.pendingCount)
        assertEquals("image", trail.next()?.nodeId)
        assertEquals("prompt", trail.next()?.nodeId)
        repeat(100) { index -> trail.offer(sample(index.toLong() + 101, ids = arrayOf("node-$index"))) }
        assertEquals(32, trail.pendingCount)
        assertEquals("node-68", trail.next()?.nodeId)
    }

    @Test fun newRunAndClearRemoveOldQueueAndSampleSequenceRejectsOldBatch() {
        val trail = EditorExecutionTrail()
        trail.offer(sample(2, ids = arrayOf("old-a", "old-b")))
        trail.offer(sample(1, ids = arrayOf("late")))
        assertEquals("old-a", trail.next()?.nodeId)
        trail.offer(sample(1, run = 2, ids = arrayOf("new")))
        assertEquals(VisualDebugPosition("main", "new"), trail.next())
        trail.clear()
        assertNull(trail.next())
        assertNull(trail.position.value)
        assertEquals(0, trail.pendingCount)
    }

    @Test fun cancellingDisplayWorkerClearsPendingRowsAndNoLatePaintOccurs() = runBlocking {
        val trail = EditorExecutionTrail()
        val worker = launch { trail.follow() }
        trail.offer(sample(ids = arrayOf("image", "prompt")))
        yield()
        assertNotNull(trail.position.value)
        worker.cancel(); worker.join()
        assertNull(trail.position.value)
        assertEquals(0, trail.pendingCount)
    }
}

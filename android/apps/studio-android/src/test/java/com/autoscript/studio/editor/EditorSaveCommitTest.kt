package com.autoscript.studio

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class EditorSaveCommitTest {
    @Test fun cancellationDuringCommitKeepsAcknowledgmentButDoesNotContinueToRun() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var acknowledged: String? = null
        var started = false
        val task = launch {
            editorSaveCommit(write = { entered.complete(Unit); release.await(); "saved" }) { acknowledged = it }
            started = true
        }
        entered.await(); task.cancel(); release.complete(Unit); task.join()
        assertEquals("saved", acknowledged)
        assertFalse(started)
        assertTrue(task.isCancelled)
    }

    @Test fun cancellationBeforeCommitDoesNotWriteAndFailureDoesNotAcknowledge() = runBlocking {
        var writes = 0
        var acknowledgments = 0
        val cancelled = launch {
            yield()
            editorSaveCommit(write = { writes++; "saved" }) { acknowledgments++ }
        }
        cancelled.cancel(); cancelled.join()
        assertEquals(0, writes); assertEquals(0, acknowledgments)
        val failure = runCatching {
            editorSaveCommit<String>(write = { throw IllegalStateException("failure") }) { acknowledgments++ }
        }
        assertTrue(failure.isFailure); assertEquals(0, acknowledgments)
    }
}

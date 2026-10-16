package com.autoscript.studio

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class RuntimeLogLoadStateTest {
    @Test fun successfulRetryClearsOnlyItsOwnFailure() {
        val failure = Result.failure<Unit>(IOException("synthetic"))
        val errors = RuntimeLogLoadErrors()
            .completed(RuntimeLogOperation.HISTORY, failure)
            .completed(RuntimeLogOperation.SNAPSHOT, failure)
            .completed(RuntimeLogOperation.EXPORT, failure)
        val loaded = errors.completed(RuntimeLogOperation.SNAPSHOT, Result.success(Unit))
        assertNull(loaded.snapshot)
        assertEquals(errors.history, loaded.history)
        assertEquals(errors.export, loaded.export)
        assertEquals(RuntimeLogLoadErrors(), loaded
            .completed(RuntimeLogOperation.HISTORY, Result.success(Unit))
            .completed(RuntimeLogOperation.EXPORT, Result.success(Unit)))
    }

    @Test fun historyFailuresDoNotAppearInRealtimeOutput() {
        val errors = RuntimeLogLoadErrors(history = "history", snapshot = "snapshot")
        assertNull(errors.visible(historyVisible = false))
        assertEquals("history", errors.visible(historyVisible = true))
        assertEquals("snapshot", errors.copy(history = null).visible(historyVisible = true))
        assertEquals("export", errors.copy(export = "export").visible(historyVisible = false))
    }

    @Test fun snapshotFailureDoesNotClaimAnUnfinishedRun() {
        val errors = RuntimeLogLoadErrors().completed(RuntimeLogOperation.SNAPSHOT, Result.failure<Unit>(IOException()))
        assertEquals("读取此运行日志失败，请刷新重试", errors.snapshot)
    }

    @Test fun successfulAttemptReturnsValue() = runBlocking {
        assertEquals(42, runtimeLogAttempt { 42 }.getOrThrow())
    }

    @Test fun ordinaryIoFailureRemainsVisible() = runBlocking {
        val failure = IOException("synthetic")
        assertSame(failure, runtimeLogAttempt<Unit> { throw failure }.exceptionOrNull())
    }

    @Test fun explicitCancellationIsRethrown() = runBlocking {
        val cancellation = CancellationException("switched run")
        try {
            runtimeLogAttempt<Unit> { throw cancellation }
            fail("Cancellation must not become a read failure")
        } catch (actual: CancellationException) {
            assertSame(cancellation, actual)
        }
    }

    @Test fun cancelledSuspendedReadCannotPublishAResult() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val pending = CompletableDeferred<Unit>()
        var published = false
        val job = launch {
            runtimeLogAttempt { started.complete(Unit); pending.await() }
            published = true
        }
        started.await()
        job.cancel()
        job.join()
        assertFalse(published)
    }

    @Test fun cancellationWinsEvenWhenTheOperationReturnsOrThrowsIoFailure() = runBlocking {
        listOf(false, true).forEach { throws ->
            var published = false
            val job = launch {
                runtimeLogAttempt {
                    cancel()
                    if (throws) throw IOException("operation finished after cancellation")
                    Unit
                }
                published = true
            }
            job.join()
            assertFalse(published)
            assertTrue(job.isCancelled)
        }
    }
}

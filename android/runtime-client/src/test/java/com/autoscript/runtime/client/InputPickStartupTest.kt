package com.autoscript.runtime.client

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class InputPickStartupTest {
    @Test fun blockingStartIsOffCallerThread() = runBlocking {
        val caller = Thread.currentThread()
        val outcome = startInputPickOffUi(
            start = { assertNotSame(caller, Thread.currentThread()); true },
            isCurrent = { true }, clearPending = { fail("Should retain accepted request") },
            cancel = { fail("Should not cancel accepted request") },
        )
        assertEquals(InputPickStartOutcome.ACCEPTED, outcome)
    }

    @Test fun changedSessionCancelsAcceptedOverlayInsteadOfReturningSuccess() = runBlocking {
        var cleared = false
        var cancelled = false
        val outcome = startInputPickOffUi({ true }, { false }, { cleared = true }, { cancelled = true })
        assertEquals(InputPickStartOutcome.STALE, outcome)
        assertTrue(cleared)
        assertTrue(cancelled)
    }

    @Test fun cancellationDuringBinderWaitReleasesLateOverlay() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        var cleared = false
        var cancelled = false
        val job = launch {
            startInputPickOffUi(start = {
                entered.complete(Unit)
                check(release.await(5, TimeUnit.SECONDS))
                true
            }, isCurrent = { true }, clearPending = { cleared = true }, cancel = { cancelled = true })
        }
        try {
            withTimeout(5_000) { entered.await() }
            job.cancel()
        } finally { release.countDown() }
        withTimeout(5_000) { job.join() }
        assertTrue(cleared)
        assertTrue(cancelled)
    }

    @Test fun rejectionAndExceptionAreNotAccepted() = runBlocking {
        assertEquals(InputPickStartOutcome.REJECTED, startInputPickOffUi({ false }, { true }, {}, {}))
        assertEquals(InputPickStartOutcome.REJECTED, startInputPickOffUi({ error("Binder died") }, { true }, {}, {}))
    }
}

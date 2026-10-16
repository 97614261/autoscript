package com.autoscript.runtime.client

import com.autoscript.runtime.api.RecordingReply
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference

class RecordingCompletionTest {
    @Test fun consumesOnceAndNeverClearsReplacementLease() {
        val lease = Any()
        val reply = RecordingReply(17, -99, RecordingReply.SUCCESS, 720, 1280)
        val leases = AtomicReference<Any?>(lease)
        assertSame(reply, takeRecordingCompletion(leases, lease, reply, 17, -99, true))
        assertNull(takeRecordingCompletion(leases, lease, reply, 17, -99, true))
        val replacement = Any()
        leases.set(replacement)
        assertNull(takeRecordingCompletion(leases, lease, reply, 17, -99, true))
        assertSame(replacement, leases.get())
    }

    @Test fun invalidReplyOrSessionDoesNotConsumeActiveLease() {
        val lease = Any()
        val leases = AtomicReference<Any?>(lease)
        val reply = RecordingReply(17, 99, RecordingReply.CANCELLED)
        assertNull(takeRecordingCompletion(leases, lease, reply, 18, 99, true))
        assertNull(takeRecordingCompletion(leases, lease, reply, 17, 99, false))
        assertSame(lease, leases.get())
    }

    @Test fun acceptsOnlyCurrentTerminalIdentity() {
        val reply = RecordingReply(17, -99, RecordingReply.SUCCESS, 720, 1280)
        assertTrue(acceptsRecordingCompletion(reply, 17, -99, true))
        assertFalse(acceptsRecordingCompletion(reply, 18, -99, true))
        assertFalse(acceptsRecordingCompletion(reply, 17, 99, true))
        assertFalse(acceptsRecordingCompletion(reply, 17, -99, false))
    }

    @Test fun rejectsBusyZeroGenerationAndInvalidPayload() {
        val reply = RecordingReply(17, 99, RecordingReply.CANCELLED)
        assertTrue(acceptsRecordingCompletion(reply, 17, 99, true))
        assertFalse(acceptsRecordingCompletion(reply.copy(status = RecordingReply.BUSY), 17, 99, true))
        assertFalse(acceptsRecordingCompletion(reply.copy(generation = 0), 17, 0, true))
        assertFalse(acceptsRecordingCompletion(reply.copy(actions = ByteArray(32)), 17, 99, true))
    }
}

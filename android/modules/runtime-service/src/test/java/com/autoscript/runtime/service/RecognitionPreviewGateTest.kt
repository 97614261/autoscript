package com.autoscript.runtime.service

import com.autoscript.runtime.api.RecognitionPreviewReply as Reply
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.*
import org.junit.Test

class RecognitionPreviewGateTest {
    @Test fun oneOutstandingRequestRejectsBusyAndHonorsCancellationIdentity() {
        val gate = RecognitionPreviewGate(AtomicBoolean()) { 1L }
        val active = requireNotNull(gate.begin(10, 20))
        assertNull(gate.begin(11, 20))
        gate.cancel(10, 21)
        assertNull(gate.status(active, 20))
        gate.cancel(10, 20)
        assertEquals(Reply.CANCELLED, gate.status(active, 20))
        gate.finish(active)
        assertNotNull(gate.begin(11, 20))
    }

    @Test fun earlyCancellationDeadlineAndStaleSessionNeverDeliverSuccess() {
        var now = 0L
        val gate = RecognitionPreviewGate(AtomicBoolean()) { now }
        gate.cancel(1, 2)
        val cancelled = requireNotNull(gate.begin(1, 2))
        assertEquals(Reply.CANCELLED, gate.status(cancelled, 2))
        gate.finish(cancelled)
        val timed = requireNotNull(gate.begin(3, 2))
        now = 5_000_000_000L
        assertEquals(Reply.TIMEOUT, gate.status(timed, 2))
        assertEquals(Reply.SESSION_MISMATCH, gate.status(timed, 9))
        gate.finish(timed)
        val closing = requireNotNull(gate.begin(4, 2))
        gate.close()
        assertEquals(Reply.CANCELLED, gate.status(closing, 2))
        gate.finish(closing)
        assertNull(gate.begin(5, 2))
    }

    @Test fun optionsRejectUnknownOperationWrongShapeAndOverflowedArea() {
        val valid = intArrayOf(0, 0, 320, 48, 500, 0xFFFFFF, 16, 2)
        assertTrue(Reply.validOptions(Reply.ALPHANUMERIC, valid))
        assertFalse(Reply.validOptions(999, valid))
        assertFalse(Reply.validOptions(Reply.ALPHANUMERIC, valid.drop(1).toIntArray()))
        assertFalse(Reply.validOptions(Reply.GLYPH, valid.copyOf().apply { this[2] = Int.MAX_VALUE; this[3] = Int.MAX_VALUE }))
        assertFalse(Reply.validOptions(Reply.GLYPH, valid.copyOf().apply { this[4] = 1001 }))
    }
    @Test fun pixelDirectionHasItsOwnBoundAndDoesNotChangeGlyphSpaceGap() {
        val options = intArrayOf(7, 11, 100, 40, 900, 0xFFFFFF, 0, 0)
        for (direction in 0..4) assertTrue(Reply.validOptions(Reply.TEMPLATE, options.copyOf().apply { this[7] = direction }))
        for (direction in listOf(-1, 5, 65535)) assertFalse(Reply.validOptions(Reply.TEMPLATE, options.copyOf().apply { this[7] = direction }))
        assertTrue(Reply.validOptions(Reply.GLYPH, options.copyOf().apply { this[7] = 65535 }))
        assertFalse(Reply.validOptions(Reply.GLYPH, options.copyOf().apply { this[7] = 65536 }))
    }
}

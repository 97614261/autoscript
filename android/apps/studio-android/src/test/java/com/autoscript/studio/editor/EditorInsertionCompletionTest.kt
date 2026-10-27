package com.autoscript.studio

import org.junit.Assert.*
import org.junit.Test

class EditorInsertionCompletionTest {
    @Test fun closesOnlyAfterActualCommitAndOnlyOnce() {
        var closed = 0
        val ticket = EditorInsertionCompletion { closed++ }
        assertEquals(0, closed) // opening/cancelling a location picker does not commit
        ticket.committed(); ticket.committed()
        assertEquals(1, closed)
    }
    @Test fun disposingFormRejectsLateCompletion() {
        var closed = false
        val ticket = EditorInsertionCompletion { closed = true }
        ticket.cancel(); ticket.committed()
        assertFalse(closed)
    }
    @Test fun oldRequestCannotCloseNewForm() {
        var first = 0; var second = 0
        val old = EditorInsertionCompletion { first++ }
        val fresh = EditorInsertionCompletion { second++ }
        old.cancel(); old.committed()
        assertEquals(0, first); assertEquals(0, second)
        fresh.committed()
        assertEquals(1, second)
    }
}

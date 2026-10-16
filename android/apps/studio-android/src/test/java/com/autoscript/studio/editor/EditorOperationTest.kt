package com.autoscript.studio

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class EditorOperationTest {
    @Test fun `success releases busy exactly once`() = runBlocking {
        var finished = 0
        var applied = false
        editorOperation({ fail("unexpected failure") }, { finished++ }) { applied = true }
        assertTrue(applied)
        assertEquals(1, finished)
    }

    @Test fun `failure is reported and allows retry`() = runBlocking {
        var busy = true
        var message: String? = null
        editorOperation({ message = it.message }, { busy = false }) { error("save conflict") }
        assertFalse(busy)
        assertEquals("save conflict", message)
        editorOperation({ fail("retry failed") }, {}) { message = "retried" }
        assertEquals("retried", message)
    }

    @Test fun `cancellation releases busy without reporting false failure`() = runBlocking {
        var finished = 0
        var reported = false
        val cancellation = CancellationException("closed")
        try {
            editorOperation({ reported = true }, { finished++ }) { throw cancellation }
            fail("cancellation swallowed")
        } catch (actual: CancellationException) { assertSame(cancellation, actual) }
        assertFalse(reported)
        assertEquals(1, finished)
    }
}

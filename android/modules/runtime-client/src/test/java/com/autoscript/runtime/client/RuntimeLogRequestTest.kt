package com.autoscript.runtime.client

import org.junit.Assert.*
import org.junit.Test

class RuntimeLogRequestTest {
    @Test fun editorHistoryCapacityRequestsAValidSnapshotInsteadOfEmptyLogs() {
        assertEquals(100, runtimeLogRequestSize(200))
        assertEquals(100, runtimeLogRequestSize(Int.MAX_VALUE))
        assertEquals(100, runtimeLogRequestSize(100))
        assertEquals(50, runtimeLogRequestSize(50))
        assertEquals(1, runtimeLogRequestSize(1))
        assertNull(runtimeLogRequestSize(0))
        assertNull(runtimeLogRequestSize(-1))
    }
}

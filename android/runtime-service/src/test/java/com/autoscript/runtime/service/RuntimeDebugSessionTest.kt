package com.autoscript.runtime.service

import org.junit.Assert.*
import org.junit.Test

class RuntimeDebugSessionTest {
    @Test fun readRequiresTheRunningProjectHandleAndConnection() {
        val session = RuntimeDebugSession()
        session.started(7, 42, "project")
        assertNull(session.ticket(7, 42, "other"))
        assertNull(session.ticket(8, 42, "project"))
        assertNull(session.ticket(7, 43, "project"))
        assertTrue(session.accepts(requireNotNull(session.ticket(7, 42, "project")), 7, 42))
    }

    @Test fun resetOrSecondRunInTheSameGenerationInvalidatesAnInflightRead() {
        val session = RuntimeDebugSession()
        session.started(7, 42, "project")
        val first = requireNotNull(session.ticket(7, 42, "project"))
        session.clear()
        assertFalse(session.accepts(first, 7, 42))
        session.started(7, 42, "project")
        val next = requireNotNull(session.ticket(7, 42, "project"))
        assertTrue(next.revision > first.revision)
        assertFalse(session.accepts(first, 7, 42))
        assertFalse(session.accepts(next, 8, 42))
        assertFalse(session.accepts(next, 7, 43))
        assertTrue(session.accepts(next, 7, 42))
    }
}

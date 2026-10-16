package com.autoscript.runtime.service

import org.junit.Assert.*
import org.junit.Test
import com.autoscript.runtime.api.RuntimeProtocol

class RuntimePromptSessionTest {
    @Test fun failureAndExplicitStopClearBothChannelsButNormalEndKeepsExpiry() {
        assertTrue(terminalClearsAllRuntimePrompts(RuntimeProtocol.STATE_FAILED, false))
        assertTrue(terminalClearsAllRuntimePrompts(RuntimeProtocol.STATE_FAILED, true))
        assertTrue(terminalClearsAllRuntimePrompts(RuntimeProtocol.STATE_STOPPED, true))
        assertFalse(terminalClearsAllRuntimePrompts(RuntimeProtocol.STATE_STOPPED, false))
        assertFalse(terminalClearsAllRuntimePrompts(RuntimeProtocol.STATE_RUNNING, false))
        assertFalse(terminalClearsAllRuntimePrompts(RuntimeProtocol.STATE_PAUSED, true))
        val session = RuntimePromptSession()
        val run = session.begin()
        val toast = requireNotNull(session.beginToast(run))
        if (terminalClearsAllRuntimePrompts(RuntimeProtocol.STATE_FAILED, false)) session.end()
        assertFalse(session.acceptsWindow(run))
        assertFalse(session.acceptsToast(run, toast))
        assertFalse(session.endToast(run, toast))
    }
    @Test fun rapidRestartsRejectEveryOldShowCleanupAndExpiry() {
        val session = RuntimePromptSession()
        val previous = mutableListOf<Pair<Long, Long>>()
        val cleanups = mutableListOf<Long>()
        repeat(100) {
            val run = session.begin()
            previous += run to requireNotNull(session.beginToast(run))
            cleanups += session.end()
        }
        val current = session.begin()
        val toast = requireNotNull(session.beginToast(current))
        previous.forEach { (run, expired) ->
            assertFalse(session.acceptsWindow(run))
            assertFalse(session.acceptsToast(run, expired))
            assertFalse(session.endToast(run, expired))
            session.closeWindow(run)
        }
        cleanups.forEach { assertFalse(session.isCurrent(it)) }
        assertTrue(session.acceptsWindow(current))
        assertTrue(session.acceptsToast(current, toast))
    }
    @Test fun stopInvalidatesQueuedWindowsToastsAndOldExpiry() {
        val session = RuntimePromptSession()
        val run = session.begin()
        assertTrue(session.acceptsWindow(run))
        val previousToast = requireNotNull(session.beginToast(run))
        val cleanup = session.end()
        assertTrue(session.isCurrent(cleanup))
        assertFalse(session.acceptsWindow(run))
        assertFalse(session.acceptsToast(run))
        assertFalse(session.hasToast())
        val nextRun = session.begin()
        assertFalse(session.isCurrent(cleanup))
        assertNotNull(session.beginToast(nextRun))
        assertFalse(session.endToast(run, previousToast))
        assertTrue(session.hasToast())
    }

    @Test fun completionClosesPersistentPromptButLetsExistingShortTipExpire() {
        val session = RuntimePromptSession()
        val run = session.begin()
        val toast = requireNotNull(session.beginToast(run))
        session.closeWindow()
        assertFalse(session.acceptsWindow(run))
        assertTrue(session.hasToast())
        assertTrue(session.endToast(run, toast))
        assertFalse(session.hasToast())
        assertTrue(session.acceptsWindow(session.begin()))
    }

    @Test fun oldExpiryCannotClearReplacementThatIsStillQueuedForDisplay() {
        val session = RuntimePromptSession()
        val run = session.begin()
        val first = requireNotNull(session.beginToast(run))
        val replacement = requireNotNull(session.beginToast(run))
        assertFalse(session.acceptsToast(run, first))
        assertFalse(session.endToast(run, first))
        assertTrue(session.hasToast())
        assertTrue(session.acceptsToast(run, replacement))
        assertTrue(session.endToast(run, replacement))
    }

    @Test fun oldWindowDialogCannotCloseNewRun() {
        val session = RuntimePromptSession()
        val old = session.begin()
        val current = session.begin()
        session.closeWindow(old)
        assertTrue(session.acceptsWindow(current))
    }
}

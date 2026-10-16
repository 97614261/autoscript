package com.autoscript.studio

import androidx.compose.ui.geometry.Rect
import androidx.compose.runtime.snapshots.Snapshot
import org.junit.Assert.*
import org.junit.Test

class EditorOverlaySessionTest {
    @Test fun observerRegistrationAndLateUnregistrationAreIdempotent() {
        val host = EditorOverlaySession({})
        val observer = Observer()
        assertTrue(host.registerObserver(observer))
        assertTrue(host.registerObserver(observer))
        assertEquals(0, observer.closes)
        host.closeDialogs()
        repeat(3) { host.unregisterObserver(observer); host.closeDialogs(); host.restore() }
        assertEquals(1, observer.closes)
        assertFalse(observer.visible)
    }
    @Test fun visibilityFailureRejectsRegistrationAndReportsOncePerSession() {
        var reports = 0
        val host = EditorOverlaySession({}, { reports++ })
        class Broken : EditorObserverWindow {
            var closes = 0
            override fun visibility(visible: Boolean) { throw SecurityException("synthetic QA") }
            override fun close() { closes++ }
        }
        val first = Broken()
        assertFalse(host.registerObserver(first))
        assertEquals(1, first.closes)
        val second = Broken()
        assertFalse(host.registerChild(second))
        assertEquals(1, second.closes)
        host.windowFailed(); host.closeDialogs(); host.unregisterObserver(first); host.unregisterChild(second)
        assertEquals(1, reports)
        assertEquals(1, first.closes)
        assertEquals(1, second.closes)
    }
    @Test fun failureCallbackCanCloseSessionWithoutDoubleClosingTheFailedObserver() {
        lateinit var host: EditorOverlaySession
        host = EditorOverlaySession({}, { host.closeDialogs() })
        var closes = 0
        val observer = object : EditorObserverWindow {
            override fun visibility(visible: Boolean) { throw IllegalStateException("synthetic QA") }
            override fun close() { closes++; host.unregisterObserver(this) }
        }
        assertFalse(host.registerObserver(observer))
        assertTrue(host.isClosed)
        assertEquals(1, closes)
        host.unregisterObserver(observer)
        assertEquals(1, closes)
    }
    @Test fun childOwnershipUsesObjectIdentityNotCustomEquality() {
        class EqualWindow : EditorSessionWindow {
            var closes = 0
            override fun visibility(visible: Boolean) = Unit
            override fun close() { closes++ }
            override fun equals(other: Any?) = other is EqualWindow
            override fun hashCode() = 0
        }
        val host = EditorOverlaySession({})
        val first = EqualWindow(); val second = EqualWindow()
        assertTrue(host.registerChild(first)); assertTrue(host.registerChild(second))
        host.unregisterChild(first)
        assertEquals(1, first.closes); assertEquals(0, second.closes)
        host.closeDialogs()
        assertEquals(1, first.closes); assertEquals(1, second.closes)
    }
    @Test fun moreUsesPanelCoordinatesWithoutNativeFullscreenTransition() {
        val host = EditorOverlaySession({})
        val bounds = Rect(80f, 350f, 372f, 728f)
        host.bounds(bounds)
        repeat(10) {
            // Opening/closing More no longer switches the dock's native modal viewport.
            host.dockLayout(false)
            assertFalse(host.dockModal)
            assertEquals(bounds, host.dockBounds)
            assertTrue(host.panelExpanded)
        }
    }
    private class Observer : EditorObserverWindow {
        var visible = false
        var closes = 0
        override fun visibility(visible: Boolean) { this.visible = visible }
        override fun close() { closes++; visible = false }
    }
    @Test fun nativePreviewSharesSuppressionAndSessionCloseButNotTheVariableObserverSlot() {
        val host = EditorOverlaySession({})
        val preview = Observer()
        val variables = Observer()
        assertTrue(host.registerChild(preview))
        assertTrue(host.registerObserver(variables))
        assertTrue(host.hasOpenDialogs)
        assertTrue(preview.visible)
        host.suppress("capture")
        assertFalse(preview.visible)
        assertFalse(variables.visible)
        host.suppress(null)
        assertTrue(preview.visible)
        host.systemRequest(true)
        assertFalse(preview.visible)
        host.systemRequest(false)
        assertTrue(preview.visible)
        host.closeDialogs()
        host.restore()
        assertEquals(1, preview.closes)
        assertEquals(1, variables.closes)
        host.unregisterChild(preview)
        assertEquals(1, preview.closes)
        val late = Observer()
        assertFalse(host.registerChild(late))
        assertEquals(1, late.closes)
        assertFalse(late.visible)
    }
    @Test fun nativeChildWindowsShareTheBoundAndUnregisteringReleasesIt() {
        var failures = 0
        val host = EditorOverlaySession({}, { failures++ })
        val windows = List(16) { Observer() }
        windows.forEach { assertTrue(host.registerChild(it)) }
        assertTrue(host.registerChild(windows.first()))
        val overflow = Observer()
        assertFalse(host.registerChild(overflow))
        assertEquals(1, failures)
        assertEquals(1, overflow.closes)
        host.unregisterChild(windows.first())
        assertTrue(host.registerChild(Observer()))
        windows.drop(1).forEach(host::unregisterChild)
        host.closeDialogs()
        assertFalse(host.hasOpenDialogs)
    }
    @Test fun observerIsNonModalAndSurvivesMinimizationButNotCaptureOrSessionClose() {
        val host = EditorOverlaySession({})
        val observer = Observer()
        assertTrue(host.registerObserver(observer))
        assertFalse(host.hasOpenDialogs)
        assertTrue(observer.visible)
        host.panel(false)
        assertTrue(observer.visible)
        host.suppress("capture")
        assertFalse(observer.visible)
        host.suppress(null)
        assertTrue(observer.visible)
        host.systemRequest(true)
        assertFalse(observer.visible)
        host.restore()
        assertFalse(observer.visible)
        host.systemRequest(false)
        assertTrue(observer.visible)
        host.closeDialogs()
        assertEquals(1, observer.closes)
        host.restore()
        assertFalse(observer.visible)
    }
    @Test fun onlyOneObserverIsOwnedAndLateRegistrationCannotReopenClosedSession() {
        val host = EditorOverlaySession({})
        val first = Observer()
        val second = Observer()
        assertTrue(host.registerObserver(first))
        assertFalse(host.registerObserver(second))
        assertEquals(1, second.closes)
        host.unregisterObserver(first)
        assertTrue(host.registerObserver(second))
        host.closeDialogs()
        val late = Observer()
        assertFalse(host.registerObserver(late))
        assertEquals(1, late.closes)
    }
    @Test fun closeImmediatelyHidesBothWindowsAndCannotRestore() {
        var updates = 0
        val host = EditorOverlaySession({ updates++ })
        host.panel(false)
        val before = updates
        host.closeDialogs()
        assertEquals(before + 1, updates)
        assertEquals(EditorWindowVisibility(false, false), editorWindowVisibility(host.hidden, host.panelExpanded, host.canvasVisible, host.dockModal))
        host.restore()
        assertTrue(host.isClosed)
        assertTrue(host.hidden)
    }

    @Test fun editorTestsDoNotSuppressOrForceExpansion() {
        val host = EditorOverlaySession({})
        val feedback = EditorRunFeedback(com.autoscript.core.model.RuntimeConnectionState(
            phase = com.autoscript.core.model.RuntimeConnectionPhase.CONNECTED, sessionGeneration = 1), false)
        feedback.observe(com.autoscript.core.model.RuntimeConnectionState(
            phase = com.autoscript.core.model.RuntimeConnectionPhase.CONNECTED, sessionGeneration = 1,
            engineState = com.autoscript.core.model.RuntimeEngineState.RUNNING, stateRevision = 1))
        assertFalse(host.hidden)
        assertEquals(EditorWindowVisibility(true, false), editorWindowVisibility(host.hidden, host.panelExpanded, false, false))
        host.panel(false)
        feedback.observe(com.autoscript.core.model.RuntimeConnectionState(
            phase = com.autoscript.core.model.RuntimeConnectionPhase.CONNECTED, sessionGeneration = 1,
            engineState = com.autoscript.core.model.RuntimeEngineState.STOPPED, stateRevision = 2))
        assertEquals(EditorWindowVisibility(false, true), editorWindowVisibility(host.hidden, host.panelExpanded, false, false))
        assertEquals(0, host.restoreRequest)
    }

    @Test fun closedSessionIgnoresLateRestorePickerAndRunCallbacks() {
        var updates = 0
        val host = EditorOverlaySession(updateWindow = { updates++ })
        host.systemRequest(true)
        host.closeDialogs()
        val before = updates
        repeat(100) {
            host.restore(); host.systemRequest(false); host.suppress(null); host.panel(false)
            host.canvas(true); host.dockLayout(true); host.bounds(Rect(1f, 2f, 10f, 20f))
        }
        assertEquals(before, updates)
        assertEquals(0, host.restoreRequest)
        assertTrue(host.hidden)
        assertFalse(host.canvasVisible)
    }
    @Test fun `native restoration is compose observable and updates window before a drawing frame`() {
        var updates = 0
        val host = EditorOverlaySession(updateWindow = { updates++ })
        val reads = mutableSetOf<Any>()
        Snapshot.observe({ reads.add(it) }, null) { assertTrue(host.panelExpanded) }
        assertTrue("Compose must observe the service-owned expansion state", reads.isNotEmpty())
        host.panel(false)
        val beforeRestore = updates
        host.restore()
        assertTrue(host.panelExpanded)
        assertEquals(beforeRestore + 1, updates)
        assertEquals(EditorWindowVisibility(true, false), editorWindowVisibility(host.hidden, host.panelExpanded, false, false))
    }
    @Test fun `repeated project edit restores the minimized session without changing retained bounds`() {
        val host = EditorOverlaySession(updateWindow = {})
        val bounds = Rect(8f, 372f, 592f, 1128f)
        host.bounds(bounds)
        repeat(3) {
            host.panel(false)
            assertEquals(EditorWindowVisibility(false, true), editorWindowVisibility(host.hidden, host.panelExpanded, false, false))
            host.restore()
            assertTrue(host.panelExpanded)
            assertEquals(EditorWindowVisibility(true, false), editorWindowVisibility(host.hidden, host.panelExpanded, false, false))
            assertEquals(bounds, host.dockBounds)
        }
        assertEquals(3, host.restoreRequest)
    }
    @Test fun `only explicit restore expands minimized editor`() {
        val host = EditorOverlaySession(updateWindow = {})
        host.panel(false)
        host.suppress(null)
        assertFalse(host.panelExpanded)
        host.restore()
        assertFalse(host.hidden)
        assertTrue(host.panelExpanded)
        assertEquals(1, host.restoreRequest)
    }
    @Test fun `capture suppresses without losing bounds and explicit restoration is one shot per operation`() {
        val host = EditorOverlaySession(updateWindow = {})
        host.bounds(Rect(10f, 20f, 300f, 500f))
        host.suppress("capture:1")
        assertTrue(host.hidden)
        host.restore()
        assertFalse(host.hidden)
        host.suppress("capture:1")
        assertFalse(host.hidden)
        host.suppress("capture:2")
        assertTrue(host.hidden)
        host.suppress(null)
        assertFalse(host.hidden)
        assertEquals(Rect(10f, 20f, 300f, 500f), host.dockBounds)
    }
    @Test fun `system picker always suppresses even when notification restores editor`() {
        val host = EditorOverlaySession(updateWindow = {})
        host.systemRequest(true)
        host.restore()
        assertTrue(host.hidden)
        host.systemRequest(false)
        assertFalse(host.hidden)
    }
    @Test fun `dock modal and canvas viewport cannot overwrite each other`() {
        val host = EditorOverlaySession(updateWindow = {})
        host.canvas(true)
        host.dockLayout(false)
        assertTrue(host.canvasVisible)
        host.dockLayout(true)
        host.canvas(false)
        assertTrue(host.dockModal)
        assertFalse(host.canvasVisible)
    }
    @Test fun `minimizing preserves panel rectangle and restoring asks for expansion`() {
        val host = EditorOverlaySession(updateWindow = {})
        val bounds = Rect(80f, 100f, 400f, 500f)
        host.bounds(bounds)
        host.panel(false)
        assertFalse(host.panelExpanded)
        assertFalse(host.hidden)
        assertEquals(bounds, host.dockBounds)
        host.restore()
        assertEquals(1, host.restoreRequest)
        assertTrue(host.panelExpanded)
        assertEquals(bounds, host.dockBounds)
    }
    @Test fun `unowned runtime notifications do not expand minimized editor`() {
        val host = EditorOverlaySession(updateWindow = {})
        host.panel(false)
        host.suppress(null)
        assertEquals(0, host.restoreRequest)
        assertFalse(host.panelExpanded)
    }
}

package com.autoscript.studio

import org.junit.Assert.*
import org.junit.Test
import com.autoscript.core.model.RuntimeConnectionPhase
import com.autoscript.core.model.RuntimeConnectionState
import com.autoscript.core.model.RuntimeEngineState
import com.autoscript.core.model.RuntimeRootState

class EditorOverlayGeometryTest {
    @Test fun firstOverlayLayoutIsVisibleBeforeComposeReportsBounds() {
        val bounds = defaultEditorDockBounds(720, 1280, 1f)
        assertEquals(4f, bounds.left, 0.01f)
        assertEquals(292f, bounds.width, 0.01f)
        assertEquals(378f, bounds.height, 0.01f)
        assertTrue(bounds.top >= 0f)
        assertTrue(bounds.bottom <= 1280f)
    }

    @Test fun initialOverlayBoundsRespectSmallLandscapeScreens() {
        val bounds = defaultEditorDockBounds(320, 240, 3f)
        assertTrue(bounds.left >= 0f)
        assertTrue(bounds.top >= 0f)
        assertTrue(bounds.right <= 320f)
        assertTrue(bounds.bottom <= 240f)
        assertTrue(bounds.width > 1f)
        assertTrue(bounds.height > 1f)
    }

    @Test fun `same project edit stays enabled while an editor exists`() {
        assertTrue(editorEntryEnabled(true, null, null, "a"))
        assertTrue(editorEntryEnabled(true, "a", null, "a"))
    }
    @Test fun `restore entry cannot switch projects or bypass a configuration session`() {
        assertFalse(editorEntryEnabled(true, "a", null, "b"))
        assertFalse(editorEntryEnabled(true, null, "a", "a"))
        assertFalse(editorEntryEnabled(true, "a", "b", "a"))
    }
    @Test fun `loading or busy page does not become editable merely because its session exists`() {
        assertFalse(editorEntryEnabled(false, "a", null, "a"))
        assertFalse(editorEntryEnabled(false, null, null, "a"))
    }
    @Test fun `editor ball appears only when the panel is minimized`() {
        assertEquals(EditorWindowVisibility(true, false), editorWindowVisibility(false, true, false, false))
        assertEquals(EditorWindowVisibility(false, true), editorWindowVisibility(false, false, false, false))
    }
    @Test fun `running suppresses both windows and image canvas does not carry an editor ball`() {
        assertEquals(EditorWindowVisibility(false, false), editorWindowVisibility(true, true, false, true))
        assertEquals(EditorWindowVisibility(true, false), editorWindowVisibility(false, true, true, false))
    }
    @Test fun `temporary content and suppression take priority over a minimized ball`() {
        assertEquals(EditorWindowVisibility(true, false), editorWindowVisibility(false, false, true, false))
        assertEquals(EditorWindowVisibility(true, false), editorWindowVisibility(false, false, false, true))
        assertEquals(EditorWindowVisibility(false, false), editorWindowVisibility(true, false, false, false))
    }
    @Test fun `panel native window clips to visible panel rather than intercepting whole screen`() {
        assertEquals(EditorWindowBounds(12, 100, 300, 400), editorWindowBounds(12f, 100f, 312f, 500f, 720, 1280))
    }
    @Test fun `edge docked ball clips negative peek and remains reachable`() {
        assertEquals(EditorWindowBounds(0, 200, 50, 60), editorWindowBounds(-10f, 200f, 50f, 260f, 720, 1280))
    }
    @Test fun `fractional edges round outward and cannot leave the display`() {
        assertEquals(EditorWindowBounds(700, 1200, 20, 80), editorWindowBounds(700.4f, 1200.1f, 900f, 1400f, 720, 1280))
    }
    @Test fun `image canvas and temporary menu may explicitly own full viewport`() {
        assertEquals(EditorWindowBounds(0, 0, 1280, 720), editorWindowBounds(10f, 10f, 100f, 100f, 1280, 720, fullScreen = true))
    }
    @Test fun `invalid and empty geometry stays bounded without crashing`() {
        assertEquals(EditorWindowBounds(0, 0, 1, 1), editorWindowBounds(Float.NaN, 0f, 100f, 100f, 0, 0))
        assertEquals(EditorWindowBounds(0, 0, 1, 1), editorWindowBounds(20f, 20f, 10f, 10f, 720, 1280))
    }
    @Test fun `editor intent accepts bounded opaque identity not paths or source`() {
        assertTrue(validEditorProjectId("project-abcd-123"))
        listOf(null, "", "../project", "C:\\private", "a/b", "<script>", "a".repeat(65)).forEach { assertFalse(validEditorProjectId(it)) }
    }
    @Test fun `existing same session is restored and different project cannot replace drafts`() {
        assertEquals(EditorSessionDecision.OPEN, editorSessionDecision(null, "a"))
        assertEquals(EditorSessionDecision.RESTORE, editorSessionDecision("a", "a"))
        assertEquals(EditorSessionDecision.REJECT, editorSessionDecision("a", "b"))
    }
    @Test fun `root and transport updates do not acknowledge a submitted script`() {
        assertFalse(editorRunAcknowledged(10, RuntimeConnectionState(phase = RuntimeConnectionPhase.CONNECTED,
            engineState = RuntimeEngineState.IDLE, rootState = RuntimeRootState.READY, stateRevision = 11)))
    }
    @Test fun `execution or termination acknowledgment closes the pending interval`() {
        assertTrue(editorRunAcknowledged(10, RuntimeConnectionState(phase = RuntimeConnectionPhase.CONNECTED,
            engineState = RuntimeEngineState.STOPPED, stateRevision = 12)))
        assertFalse(editorRunAcknowledged(10, RuntimeConnectionState(phase = RuntimeConnectionPhase.CONNECTED,
            engineState = RuntimeEngineState.RUNNING, stateRevision = 10)))
        assertTrue(editorRunAcknowledged(10, RuntimeConnectionState(phase = RuntimeConnectionPhase.DISCONNECTED)))
    }
}

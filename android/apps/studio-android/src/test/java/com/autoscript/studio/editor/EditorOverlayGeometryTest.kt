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
        assertEquals(284f, bounds.width, 0.01f)
        assertEquals(366f, bounds.height, 0.01f)
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

    @Test fun initialOverlayOriginMatchesAnExplicitWorkArea() {
        val area = androidx.compose.ui.geometry.Rect(0f, 48f, 720f, 1184f)
        val bounds = defaultEditorDockBounds(720, 1280, 2f, area)
        assertEquals(8f, bounds.left, 0f)
        assertEquals(300f, bounds.top, 0f)
        assertEquals(568f, bounds.width, 0f)
        assertEquals(732f, bounds.height, 0f)
    }

    @Test fun initialOverlayRetainsSpaceAroundNarrowLandscapeWindow() {
        val area = androidx.compose.ui.geometry.Rect(24f, 48f, 296f, 216f)
        val bounds = defaultEditorDockBounds(320, 240, 3f, area)
        assertEquals(24f, bounds.left, .01f)
        assertEquals(48f, bounds.top, .01f)
        assertEquals(244.8f, bounds.width, .01f)
        assertEquals(131.04f, bounds.height, .01f)
        assertTrue(bounds.right < area.right)
        assertTrue(bounds.bottom < area.bottom)
    }

    @Test fun denseLandscapeDefaultDoesNotExpandToTheNewFullScreenDragArea() {
        val bounds = defaultEditorDockBounds(1280, 720, 2f)
        assertEquals(568f, bounds.width, .01f)
        assertEquals(561.6f, bounds.height, .01f)
        assertTrue(bounds.height < 720f)
        assertEquals(bounds.size, editorPanelSize(editorWorkArea(1280, 720), 2f))
    }

    @Test fun unresizedDefaultAdaptsToRotationInsteadOfReusingTallPortraitHeight() {
        val portrait = editorPanelSize(editorWorkArea(720, 1280), 2f)
        val landscape = editorPanelSize(editorWorkArea(1280, 720), 2f)
        assertEquals(732f, portrait.height, .01f)
        assertEquals(561.6f, landscape.height, .01f)
        assertEquals(portrait, editorPanelSize(editorWorkArea(720, 1280), 2f))
    }

    @Test fun minimumResizeHeightDoesNotOverrideTheShortScreenDefault() {
        val area = editorWorkArea(1280, 480)
        val initial = editorPanelSize(area, 2f)
        val small = editorPanelSize(area, 2f, androidx.compose.ui.geometry.Size(1f, 1f))
        assertEquals(374.4f, initial.height, .01f)
        assertEquals(initial.height, small.height, .01f)
        assertTrue(small.height < area.height)
    }

    @Test fun manualResizeCanExceedDefaultButNeverThePhysicalScreen() {
        val area = editorWorkArea(1280, 720)
        val requested = androidx.compose.ui.geometry.Size(850f, 640f)
        assertEquals(requested, editorPanelSize(area, 2f, requested))
        assertEquals(area.size, editorPanelSize(area, 2f, androidx.compose.ui.geometry.Size(5000f, 5000f)))
        val range = editorPanelDragArea(area, requested.width, requested.height)
        assertEquals(430f, range.right, .01f)
        assertEquals(80f, range.bottom, .01f)
    }

    @Test fun invalidResizeAndDensityFallBackToTheSameBoundedInitialSize() {
        val area = editorWorkArea(720, 1280)
        val initial = editorPanelSize(area, 1f)
        assertEquals(initial, editorPanelSize(area, Float.NaN))
        assertEquals(initial, editorPanelSize(area, 1f, androidx.compose.ui.geometry.Size(Float.NaN, 900f)))
    }

    @Test fun visibleStatusBarDoesNotApplyTheDefaultHeightRatioASecondTime() {
        val viewport = androidx.compose.ui.geometry.Size(1280f, 720f)
        val full = editorWorkArea(1280, 720)
        val belowBar = editorWorkArea(1280, 720, 48)
        val initial = editorPanelSize(full, 2f, initialViewport = viewport)
        assertEquals(561.6f, initial.height, .01f)
        assertEquals(initial, editorPanelSize(belowBar, 2f, initialViewport = viewport))
        val native = defaultEditorDockBounds(1280, 720, 2f, belowBar, viewport)
        assertEquals(initial, native.size)
        assertTrue(native.top >= 48f)
        assertTrue(native.bottom <= 720f)
    }

    @Test fun shortScreensOnlyShrinkTheInitialWindowWhenItCannotFitBelowTheBar() {
        val viewport = androidx.compose.ui.geometry.Size(1280f, 480f)
        val belowBar = editorWorkArea(1280, 480, 120)
        val initial = editorPanelSize(belowBar, 2f, initialViewport = viewport)
        assertEquals(360f, initial.height, .01f)
        val minimum = editorPanelSize(belowBar, 2f, androidx.compose.ui.geometry.Size(1f, 1f), viewport)
        assertEquals(420f, minimum.width, .01f)
        assertEquals(initial.height, minimum.height, .01f)
    }

    @Test fun showingAndHidingTheStatusBarDoesNotReplaceManualSizeRequests() {
        val viewport = androidx.compose.ui.geometry.Size(1280f, 720f)
        val manual = androidx.compose.ui.geometry.Size(850f, 640f)
        assertEquals(manual, editorPanelSize(editorWorkArea(1280, 720, 48), 2f, manual, viewport))
        val tall = androidx.compose.ui.geometry.Size(850f, 710f)
        assertEquals(672f, editorPanelSize(editorWorkArea(1280, 720, 48), 2f, tall, viewport).height, .01f)
        assertEquals(tall, editorPanelSize(editorWorkArea(1280, 720), 2f, tall, viewport))
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

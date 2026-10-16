package com.autoscript.studio

import org.junit.Assert.assertEquals
import org.junit.Test

class EditorMoreLayoutTest {
    @Test fun moreFitsInsideMovedPanelWithoutChangingItsNativeWindow() {
        val panel = EditorPanelPlacement(80f, 350f, 292f, 378f)
        val menu = editorMoreViewport(panel, 720f, 1280f)
        assertEquals(panel, menu)
        val before = editorWindowBounds(panel.x, panel.y, panel.x + panel.width, panel.y + panel.height, 720, 1280)
        val placement = editorMorePlacement(menu.width, menu.height, 164f, 168f, 290f, 334f, 378f)
        assertEquals(EditorMorePlacement(126, 166), placement)
        assertEquals(before, editorWindowBounds(panel.x, panel.y, panel.x + panel.width, panel.y + panel.height, 720, 1280))
    }
    @Test fun partlyOffscreenPanelClipsMenuInsteadOfExpandingHost() {
        val menu = editorMoreViewport(EditorPanelPlacement(80f, 1150f, 292f, 378f), 720f, 1280f)
        assertEquals(EditorPanelPlacement(80f, 1150f, 292f, 130f), menu)
        assertEquals(EditorMorePlacement(128, 0), editorMorePlacement(menu.width, menu.height, 164f, 240f, 292f, 100f, 130f))
    }
    @Test fun maximumAndNarrowViewportKeepMenuWithinExistingPanel() {
        assertEquals(EditorPanelPlacement(0f, 0f, 1280f, 720f), editorMoreViewport(EditorPanelPlacement(0f, 0f, 1280f, 720f), 1280f, 720f))
        assertEquals(EditorPanelPlacement(0f, 20f, 100f, 160f), editorMoreViewport(EditorPanelPlacement(0f, 20f, 100f, 160f), 100f, 200f))
    }
    @Test fun measuredMenuTouchesBottomTrigger() {
        assertEquals(EditorMorePlacement(336, 342), editorMorePlacement(500f, 600f, 164f, 168f, 500f, 510f, 550f))
    }
    @Test fun topTriggerOpensBelowAndBothEdgesAreBounded() {
        assertEquals(EditorMorePlacement(0, 40), editorMorePlacement(200f, 600f, 164f, 168f, 40f, 0f, 40f))
        assertEquals(EditorMorePlacement(0, 0), editorMorePlacement(100f, 100f, 164f, 168f, 140f, 80f, 120f))
    }
    @Test fun largeFontMeasuredHeightDoesNotUseOldFixedReservation() {
        assertEquals(EditorMorePlacement(136, 30), editorMorePlacement(300f, 300f, 164f, 240f, 300f, 270f, 300f))
    }
}

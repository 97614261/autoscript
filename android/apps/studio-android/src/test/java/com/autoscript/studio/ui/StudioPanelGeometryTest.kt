package com.autoscript.studio

import org.junit.Assert.*
import org.junit.Test

class StudioPanelGeometryTest {
    @Test fun keyboardAndTinyWindowAlwaysBoundPanel() {
        assertEquals(180f, studioPanelMaxHeight(180f, 640))
        assertEquals(65f, studioPanelMaxHeight(65f, 640))
        assertEquals(1f, studioPanelMaxHeight(0f, 640))
        assertEquals(336f, studioPanelMaxHeight(Float.POSITIVE_INFINITY, 360))
    }
    @Test fun fontScaleDoesNotChangeDefaultButAllowsLargeText() {
        assertEquals(30f, studioCompactControlHeight(30, 1f))
        assertEquals(60f, studioCompactControlHeight(30, 2f))
        assertEquals(30f, studioCompactControlHeight(30, Float.NaN))
    }
    @Test fun narrowRotatedHeaderCannotConsumeTheEntireTitle() {
        for (width in listOf(0f, 120f, 240f, 360f, 540f, 1080f)) {
            val actions = studioHeaderActionsMaxWidth(width)
            assertTrue(actions >= 0f)
            assertTrue(actions <= width * .35f)
        }
        assertEquals(0f, studioHeaderActionsMaxWidth(Float.NaN))
        assertEquals(0f, studioHeaderActionsMaxWidth(Float.POSITIVE_INFINITY))
    }
    @Test fun fullscreenAndCloseStayOutsideTheScrollableActions() {
        for (width in listOf(120f, 240f, 330f, 540f, 1080f)) {
            val pinned = 64f
            val actions = studioHeaderActionsMaxWidth(width, pinned)
            assertTrue(actions >= 0f)
            assertTrue(actions + pinned <= maxOf(pinned, width * .35f) + .001f)
        }
        assertEquals(0f, studioHeaderActionsMaxWidth(120f, 64f))
        assertEquals(51.5f, studioHeaderActionsMaxWidth(330f, 64f), .001f)
    }
    @Test fun expandedSourceViewportRemovesCompactCapButStillFitsKeyboardSpace() {
        assertEquals(260f, studioPanelViewportHeight(600f, 260, false))
        assertEquals(600f, studioPanelViewportHeight(600f, 260, true))
        assertEquals(100f, studioPanelViewportHeight(100f, 260, true))
        assertEquals(100f, studioPanelViewportHeight(100f, 260, false))
        assertEquals(1f, studioPanelViewportHeight(0f, 260, true))
        assertEquals(260f, studioPanelViewportHeight(Float.POSITIVE_INFINITY, 260, true))
    }
}

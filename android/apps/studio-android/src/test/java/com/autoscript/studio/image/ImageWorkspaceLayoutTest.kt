package com.autoscript.studio

import org.junit.Assert.*
import org.junit.Test

class ImageWorkspaceLayoutTest {
    @Test fun portraitDoesNotSwitchToSidePanelWhenKeyboardReducesHeight() {
        assertFalse(imageWorkspaceLayout(390f, 800f, 250f, true).side)
        assertTrue(imageWorkspaceLayout(800f, 390f, 160f, true).side)
        assertFalse(imageWorkspaceLayout(600f, 280f, 280f, true, landscape = false).side)
    }

    @Test fun typingGivesParametersMoreSpaceWithoutCoveringTheWholeImage() {
        val normal = imageWorkspaceLayout(390f, 800f, 250f, false)
        val typing = imageWorkspaceLayout(390f, 800f, 250f, true)
        assertTrue(typing.panelHeightDp > normal.panelHeightDp)
        assertTrue(250f - typing.panelHeightDp >= 40f)
    }

    @Test fun tallPortraitKeepsMoreThanHalfTheDisplayForTheScreenshot() {
        val layout = imageWorkspaceLayout(440f, 1000f, 1000f, false)
        assertFalse(layout.side)
        assertTrue(layout.panelHeightDp <= 360f)
        assertTrue(1000f - layout.panelHeightDp > 500f)
    }

    @Test fun selectionAndTestLaunchersLeaveTheCanvasMoreRoomThanParameterForms() {
        val compact = imageWorkspaceLayout(390f, 800f, 800f, false, compact = true)
        val form = imageWorkspaceLayout(390f, 800f, 800f, false)
        assertTrue(compact.panelHeightDp < form.panelHeightDp)
        assertTrue(800f - compact.panelHeightDp > 600f)
        val typing = imageWorkspaceLayout(390f, 800f, 250f, true, compact = true)
        assertTrue(250f - typing.panelHeightDp >= 40f)
    }

    @Test fun narrowLandscapeLeavesAtLeastHalfForTheCanvas() {
        for (width in listOf(480f, 600f, 800f, 1280f)) {
            val layout = imageWorkspaceLayout(width, 300f, 180f, true)
            assertTrue(layout.side)
            assertTrue(layout.panelWidthDp <= width * .48f)
            assertTrue(layout.panelWidthDp <= 340f)
        }
    }

    @Test fun veryShortOrTransitioningSurfaceNeverProducesNegativePanelBounds() {
        for (height in listOf(0f, 20f, 40f, 80f)) {
            val layout = imageWorkspaceLayout(390f, 800f, height, true)
            assertTrue(layout.panelHeightDp in 0f..height)
        }
    }

    @Test fun invalidGeometryIsRejectedBeforeConstructingLayoutBounds() {
        assertThrows(IllegalArgumentException::class.java) { imageWorkspaceLayout(Float.NaN, 400f, 200f, false) }
        assertThrows(IllegalArgumentException::class.java) { imageWorkspaceLayout(600f, 400f, -1f, false) }
    }
}

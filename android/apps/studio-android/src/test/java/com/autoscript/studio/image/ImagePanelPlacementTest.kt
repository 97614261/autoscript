package com.autoscript.studio

import org.junit.Assert.*
import org.junit.Test

class ImagePanelPlacementTest {
    @Test fun rightPanelCanMoveAcrossTheCanvasWithoutEscapingEitherEdge() {
        val start = ImagePanelPosition(1f, 0f)
        val left = imagePanelDrag(start, -900f, 50f, 800f, 400f, 300f, 300f)
        assertEquals(0f to 50f, imagePanelOffset(left, 800f, 400f, 300f, 300f))
        val bottomRight = imagePanelDrag(left, 2000f, 2000f, 800f, 400f, 300f, 300f)
        assertEquals(500f to 100f, imagePanelOffset(bottomRight, 800f, 400f, 300f, 300f))
    }

    @Test fun keyboardClampingDoesNotLoseThePositionUsedWhenItCloses() {
        val position = ImagePanelPosition(.25f, .8f)
        val before = imagePanelOffset(position, 800f, 400f, 300f, 300f)
        assertEquals(125f to 0f, imagePanelOffset(position, 800f, 180f, 300f, 180f))
        assertEquals(before, imagePanelOffset(position, 800f, 400f, 300f, 300f))
        val horizontalDrag = imagePanelDrag(position, 100f, -100f, 800f, 180f, 300f, 180f)
        assertEquals(.8f, horizontalDrag.y, 0f)
        assertEquals(225f to 80f, imagePanelOffset(horizontalDrag, 800f, 400f, 300f, 300f))
    }

    @Test fun rotationAndTinyBoundsKeepTheEntireFittingPanelInsideItsHost() {
        val position = ImagePanelPosition(.8f, .6f)
        for ((width, height) in listOf(800f to 400f, 390f to 800f, 40f to 20f, 0f to 0f)) {
            val panelWidth = minOf(300f, width)
            val panelHeight = minOf(280f, height)
            val (x, y) = imagePanelOffset(position, width, height, panelWidth, panelHeight)
            assertTrue(x >= 0f && y >= 0f && x + panelWidth <= width && y + panelHeight <= height)
        }
    }

    @Test fun oversizeOrFormerOutOfBoundsPositionCannotCreateNegativeOffsets() {
        assertEquals(0f to 0f, imagePanelOffset(ImagePanelPosition(4f, -3f), 100f, 100f, 200f, 200f))
        assertEquals(80f to 0f, imagePanelOffset(ImagePanelPosition(4f, -3f), 100f, 100f, 20f, 20f))
    }

    @Test fun invalidBoundsAndDeltasAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            imagePanelOffset(ImagePanelPosition(0f, 0f), Float.NaN, 100f, 20f, 20f)
        }
        assertThrows(IllegalArgumentException::class.java) {
            imagePanelDrag(ImagePanelPosition(0f, 0f), Float.POSITIVE_INFINITY, 0f, 100f, 100f, 20f, 20f)
        }
    }
}

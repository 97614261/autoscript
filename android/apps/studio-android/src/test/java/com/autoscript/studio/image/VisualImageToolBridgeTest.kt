package com.autoscript.studio

import com.autoscript.studio.ImageToolCodeGen.PickedPoint
import com.autoscript.studio.ImageToolCodeGen.Roi
import com.autoscript.studio.ImageToolCodeGen.VisualSelection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VisualImageToolBridgeTest {
    @Test
    fun colorSelectionUsesRawScreenshotPixelsAndColor() {
        val draft = requireNotNull(visualImageToolDraft(VisualSelection(
            ImageToolMode.COLOR, Roi(10, 20, 110, 220), listOf(PickedPoint(30, 40, 0x123456)),
            8, 1080, 1920,
        )))
        assertEquals("vision.findcolor", draft.first)
        assertEquals(0x123456, draft.second.get("rgb").asInt)
        assertEquals(110, draft.second.getAsJsonObject("region").get("right").asInt)
    }

    @Test
    fun multiColorSamplesAreOffsetsFromAnchor() {
        val draft = requireNotNull(visualImageToolDraft(VisualSelection(
            ImageToolMode.MULTI_COLOR, null,
            listOf(PickedPoint(100, 200, 0xFF0000), PickedPoint(95, 207, 0x00FF00)),
            12, 1080, 1920,
        )))
        assertEquals("vision.findmulticolor", draft.first)
        val sample = draft.second.getAsJsonArray("samples")[0].asJsonObject
        assertEquals(-5, sample.get("x").asInt)
        assertEquals(7, sample.get("y").asInt)
        assertEquals(1920, draft.second.getAsJsonObject("region").get("bottom").asInt)
    }

    @Test
    fun multiColorRequiresAnAdditionalSample() {
        assertNull(visualImageToolDraft(VisualSelection(
            ImageToolMode.MULTI_COLOR, null, listOf(PickedPoint(1, 2, 0)), 8, 720, 1280,
        )))
    }
}

package com.autoscript.studio

import org.junit.Assert.*
import org.junit.Test

class ImageWorkbenchRulersTest {
    @Test fun pixelGridOnlyAppearsAtHighZoomAndUsesImageBoundaries() {
        assertTrue(imageWorkbenchMarks(100, 0f, 7f, 80f, true).isEmpty())
        assertEquals(listOf(2 to 0f, 3 to 8f, 4 to 16f), imageWorkbenchMarks(100, -16f, 8f, 16f, true))
    }

    @Test fun marksAndActualSizeAreBoundedAndDistinctFromFit() {
        assertTrue(imageWorkbenchMarks(4096, 0f, 8f, 100_000f, true).size <= 512)
        assertTrue(imageWorkbenchMarks(4096, 0f, .01f, 200f, false).size <= 18)
        val fitted = ImageToolViewport.fit(720, 1280, 360f, 640f)
        assertEquals(.5f, fitted.scale, .001f)
        assertEquals(1f, fitted.actualSize().scale, .001f)
        assertEquals(360 to 640, fitted.actualSize().toImage(180f, 320f))
    }
}

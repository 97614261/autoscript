package com.autoscript.studio

import org.junit.Assert.*
import org.junit.Test

class TouchToolRegressionTest {
    @Test fun slideAndDragHaveTwoValidInitialMarkersInBothOrientations() {
        for ((width, height) in listOf(1920 to 1080, 720 to 1280, 2 to 1, 1 to 1)) {
            val (start, end) = imageToolSlideDefaults(width, height)
            for (point in listOf(start, end)) {
                assertTrue(point.first in 0 until width)
                assertTrue(point.second in 0 until height)
            }
            if (width > 1) assertNotEquals(start, end)
        }
        assertEquals((480 to 540) to (1440 to 540), imageToolSlideDefaults(1920, 1080))
    }

    @Test fun touchModeMenuKeepsAllInputActionsWithoutImageProcessingModes() {
        assertEquals(setOf(ImageToolMode.TAP, ImageToolMode.LONG_PRESS, ImageToolMode.SWIPE,
            ImageToolMode.DRAG, ImageToolMode.POINTER_DOWN, ImageToolMode.POINTER_MOVE,
            ImageToolMode.POINTER_UP, ImageToolMode.RECORD), touchToolModes.toSet())
        assertEquals(touchToolModes.size, touchToolModes.distinct().size)
        assertFalse(touchToolModes.any { it.usesDragBox || it.usesPressPick })
    }

    @Test fun capturedPolygonTemplateNeverKeepsItsCropAsASearchPolygon() {
        val original = mapOf("region" to "10,20,50,60", "polygon" to "[[10,20],[50,20],[10,60]]",
            "leftVariable" to "oldLeft", "bottomVariable" to "oldBottom",
            "similarityPermille" to "900", "tolerance" to "0", "foundVariable" to "found",
            "imagePath" to "assets/images/old.png", "imagePaths" to "", "imageDirectory" to "")
        val selected = recognitionCapturedTemplate(original, "vision.findimage", "assets/images/new.png")
        assertEquals("assets/images/new.png", selected["imagePath"])
        assertEquals("", selected["polygon"])
        assertEquals("", selected["leftVariable"])
        assertEquals("", selected["bottomVariable"])
        assertEquals("900", selected["similarityPermille"])
        assertEquals("0", selected["tolerance"])
        val origin = ScreenshotTemplateRegion(1920, 1080, ImageToolCodeGen.Roi(1010, 120, 1130, 200))
        val defaults = recognitionTemplateRegionInputs(selected, origin, true)
        assertEquals("1010,120,1130,200", defaults["region"])
        assertEquals("", defaults["polygon"])
        assertEquals("oldLeft", original["leftVariable"])
    }
}

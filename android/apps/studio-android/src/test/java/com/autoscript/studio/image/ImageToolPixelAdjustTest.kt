package com.autoscript.studio

import org.junit.Assert.*
import org.junit.Test

class ImageToolPixelAdjustTest {
    @Test fun acceptsOriginalPixelEdgesWithoutDesignMapping() {
        assertEquals(listOf(0 to 0, 1279 to 719), imageToolPixelPoints(listOf("0" to "0", "1279" to "719"), 1280, 720))
    }

    @Test fun rejectsOneInvalidPointWithoutPartiallyApplyingOthers() {
        assertNull(imageToolPixelPoints(listOf("10" to "20", "1280" to "0"), 1280, 720))
        assertNull(imageToolPixelPoints(listOf("0" to "720"), 1280, 720))
        assertNull(imageToolPixelPoints(listOf("-1" to "20"), 1280, 720))
    }

    @Test fun rejectsIncompleteDecimalAndOverflowDrafts() {
        for (text in listOf("", "-", "1.5", "2147483648")) assertNull(imageToolPixelPoints(listOf(text to "0"), 1280, 720))
    }

    @Test fun preservesPointOrderAndEnforcesExistingSampleBudget() {
        val maximum = ImageToolCodeGen.MAX_MULTI_COLOR_SAMPLES + 1
        assertEquals(listOf(9 to 8, 1 to 2), imageToolPixelPoints(listOf("9" to "8", "1" to "2"), 20, 20))
        assertNotNull(imageToolPixelPoints(List(maximum) { "0" to "0" }, 20, 20))
        assertNull(imageToolPixelPoints(List(maximum + 1) { "0" to "0" }, 20, 20))
    }

    @Test fun refusesMissingPointsAndInvalidImageDimensions() {
        assertNull(imageToolPixelPoints(emptyList(), 1280, 720))
        assertNull(imageToolPixelPoints(listOf("0" to "0"), 0, 720))
    }

    @Test fun rectangleDraftRejectsOverflowAndKeepsHalfOpenBounds() {
        assertEquals(ImageToolCodeGen.Roi(1279, 719, 1280, 720), glyphMakerRegion("1279", "719", "1", "1", 1280, 720))
        assertNull(glyphMakerRegion("1279", "719", "2", "1", 1280, 720))
        assertNull(glyphMakerRegion("1", "0", "2147483647", "1", 1280, 720))
    }
}

package com.autoscript.studio

import com.autoscript.runtime.api.RecognitionPreviewReply as Reply
import org.junit.Assert.*
import org.junit.Test

class RecognitionPreviewConfigurationTest {
    @Test fun previewAndBackfillShareThresholdDirectionAndHalfOpenRoi() {
        val config = RecognitionPreviewConfiguration(Reply.TEMPLATE, "assets/images/a.png", 12, 925, 720, 1280)
        assertArrayEquals(intArrayOf(0, 0, 720, 1280, 925, 0xFFFFFF, 12, 0), config.options())
        val args = config.arguments()
        assertEquals(config.similarityPermille, args["similarityPermille"].asInt)
        assertEquals(config.tolerance, args["tolerance"].asInt)
        assertEquals(config.width, args.getAsJsonObject("region")["right"].asInt)
        assertEquals(config.height, args.getAsJsonObject("region")["bottom"].asInt)
        assertEquals(config.options().last(), args["direction"].asInt)
    }
    @Test fun grayCannotSilentlyBorrowColorToleranceOrPixelAlgorithm() {
        val config = RecognitionPreviewConfiguration(Reply.GRAY_TEMPLATE, "assets/images/a.png", 0, 950, 100, 40)
        assertFalse(config.arguments().has("tolerance"))
        assertFalse(config.arguments().has("direction"))
        assertEquals("grayScore", config.arguments()["scoreVariable"].asString)
        assertThrows(IllegalArgumentException::class.java) { config.copy(tolerance = 8) }
        assertThrows(IllegalArgumentException::class.java) { config.copy(direction = 1) }
    }
    @Test fun everyPixelDirectionAndSelectedRegionAreIdenticalInPreviewAndBackfill() {
        val roi = ImageToolCodeGen.Roi(7, 11, 90, 39)
        for (direction in 0..4) {
            val config = RecognitionPreviewConfiguration(Reply.TEMPLATE, "assets/images/a.png", 3, 900, 100, 40, roi, direction)
            assertArrayEquals(intArrayOf(7, 11, 90, 39, 900, 0xFFFFFF, 3, direction), config.options())
            val args = config.arguments()
            assertEquals(direction, args["direction"].asInt)
            assertEquals(listOf(7, 11, 90, 39), listOf("left", "top", "right", "bottom").map { args["region"].asJsonObject[it].asInt })
        }
        val gray = RecognitionPreviewConfiguration(Reply.GRAY_TEMPLATE, "assets/images/a.png", 0, 950, 100, 40, roi)
        assertEquals(7, gray.options()[0])
        assertEquals(39, gray.arguments()["region"].asJsonObject["bottom"].asInt)
    }
    @Test fun invalidOrPartiallyEnteredBoundsNeverCoerceToAnotherRegion() {
        assertEquals(ImageToolCodeGen.Roi(0, 0, 100, 40), recognitionPreviewRegion(listOf("0", "0", "100", "40"), 100, 40))
        assertEquals(ImageToolCodeGen.Roi(99, 39, 100, 40), recognitionPreviewRegion(listOf("99", "39", "1", "1"), 100, 40))
        for (values in listOf(listOf("-", "0", "1", "1"), listOf("", "0", "1", "1"), listOf("0", "0", "0", "1"),
            listOf("99", "0", "2", "1"), listOf("-1", "0", "1", "1"), listOf("100", "0", "1", "1"),
            listOf("0", "0", Long.MAX_VALUE.toString(), "1"), listOf("0", "0", "9223372036854775808", "1"),
            listOf("0", "0", "1.0", "1"), emptyList())) {
            assertNull(values.toString(), recognitionPreviewRegion(values, 100, 40))
        }
        assertNull(recognitionPreviewRegion(listOf("0", "0", "1", "1"), 0, 40))
        val config = RecognitionPreviewConfiguration(Reply.TEMPLATE, "assets/images/a.png", 0, 900, 100, 40)
        for (roi in listOf(ImageToolCodeGen.Roi(-1, 0, 2, 2), ImageToolCodeGen.Roi(0, 0, 101, 40), ImageToolCodeGen.Roi(1, 0, 1, 1))) {
            assertThrows(IllegalArgumentException::class.java) { config.copy(region = roi) }
        }
        for (direction in listOf(-1, 5)) assertThrows(IllegalArgumentException::class.java) { config.copy(direction = direction) }
    }
    @Test fun budgetAndInvalidResourceCannotBecomeBackfill() {
        val config = RecognitionPreviewConfiguration(Reply.TEMPLATE, "assets/images/a.png", 0, 900, 100, 40)
        assertThrows(IllegalArgumentException::class.java) { config.copy(template = "assets/images/../x.png") }
        assertThrows(IllegalArgumentException::class.java) { config.copy(width = 4096, height = 4096) }
        assertThrows(IllegalArgumentException::class.java) { config.copy(similarityPermille = 1001) }
        val options = config.options(); options[4] = 0
        assertEquals(900, config.options()[4])
        val args = config.arguments(); args.addProperty("similarityPermille", 0)
        assertEquals(900, config.arguments()["similarityPermille"].asInt)
    }
}

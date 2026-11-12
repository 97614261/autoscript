package com.autoscript.studio

import com.autoscript.project.store.ProjectMaskPoint
import org.junit.Assert.*
import org.junit.Test

class RecognitionCompactFormTest {
    @Test fun landscapeColumnsRespectUsableWidthAndFontScale() {
        assertTrue(recognitionFormSideBySide(500f, true))
        assertTrue(recognitionFormSideBySide(460f, true))
        assertFalse(recognitionFormSideBySide(459f, true))
        assertFalse(recognitionFormSideBySide(500f, false))
        assertFalse(recognitionFormSideBySide(500f, true, 1.3f))
        for (width in listOf(Float.NaN, Float.POSITIVE_INFINITY, -10f)) {
            assertFalse(recognitionFormSideBySide(width, true))
        }
    }

    @Test fun polygonCropRetainsLandscapeScreenLocationBeyondPortraitDesignWidth() {
        val points = listOf(ProjectMaskPoint(1010, 120), ProjectMaskPoint(1130, 120), ProjectMaskPoint(1010, 200))
        val pipeline = ImageProcessingPipeline(1920, 1080, polygonSelectionOperations(1920, 1080, points, crop = true, keep = true))
        assertEquals(120 to 80, pipeline.dimensions)
        assertEquals(ScreenshotTemplateRegion(1920, 1080, ImageToolCodeGen.Roi(1010, 120, 1130, 200)),
            pipeline.screenshotTemplateRegion(ImageToolCodeGen.Roi(0, 0, 120, 80)))
        assertEquals(ImageToolCodeGen.Roi(1020, 130, 1120, 190),
            pipeline.screenshotTemplateRegion(ImageToolCodeGen.Roi(10, 10, 110, 70))?.roi)
    }

    @Test fun repeatedCropsAddEachOriginExactlyOnceAndRejectInvalidBounds() {
        val pipeline = ImageProcessingPipeline(1920, 1080, listOf(
            ImagePixelOperation("crop", roi = ImageToolCodeGen.Roi(1000, 100, 1400, 400)),
            ImagePixelOperation("crop", roi = ImageToolCodeGen.Roi(10, 20, 130, 100))))
        assertEquals(ImageToolCodeGen.Roi(1010, 120, 1130, 200),
            pipeline.screenshotTemplateRegion(ImageToolCodeGen.Roi(0, 0, 120, 80))?.roi)
        for (roi in listOf(ImageToolCodeGen.Roi(-1, 0, 120, 80), ImageToolCodeGen.Roi(0, 0, 121, 80),
            ImageToolCodeGen.Roi(0, 0, 120, 81), ImageToolCodeGen.Roi(1, 1, 1, 4))) {
            assertNull(pipeline.screenshotTemplateRegion(roi))
        }
    }

    @Test fun changedPixelScaleOrOrientationNeverClaimUnchangedScreenCoordinates() {
        for (operation in listOf(ImagePixelOperation("resize", 120, 80), ImagePixelOperation("rotate90"),
            ImagePixelOperation("rotate180"), ImagePixelOperation("rotate270"), ImagePixelOperation("flipX"), ImagePixelOperation("flipY"))) {
            val pipeline = ImageProcessingPipeline(240, 160, listOf(operation))
            assertNull(operation.mode, pipeline.screenshotTemplateRegion(ImageToolCodeGen.Roi(0, 0, 10, 10)))
        }
    }

    @Test fun polygonTemplateKeepsSourcePixelsInsideAndTransparentCornersOutside() {
        val original = IntArray(8 * 6) { 0xff000000.toInt() or (it * 193) }
        val points = listOf(ProjectMaskPoint(2, 1), ProjectMaskPoint(6, 1), ProjectMaskPoint(2, 5))
        val operations = polygonSelectionOperations(8, 6, points, crop = true, keep = true)
        var image = ProcessedImagePixels(8, 6, original)
        operations.forEach { operation -> image = processImagePixels(image.argb, image.width, image.height, operation) {} }
        assertEquals(4 to 4, image.width to image.height)
        assertEquals(original[1 * 8 + 2], image.argb[0])
        assertEquals(original[2 * 8 + 2], image.argb[4])
        assertEquals(0, image.argb[15])
        assertTrue(image.argb.count { it == 0 } in 1..14)
        assertArrayEquals(IntArray(8 * 6) { 0xff000000.toInt() or (it * 193) }, original)
    }
}

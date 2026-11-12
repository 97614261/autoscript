package com.autoscript.studio

import com.autoscript.project.store.ProjectMaskPoint
import org.junit.Assert.*
import org.junit.Test

class ImageWorkspaceMappingTest {
    @Test fun fullImageEdgesSurviveEveryRotationAndMirror() {
        for (mode in listOf("rotate90", "rotate180", "rotate270", "flipX", "flipY")) {
            val pipeline = ImageProcessingPipeline(8, 6, listOf(ImagePixelOperation(mode)))
            val (w, h) = pipeline.dimensions
            val polygon = listOf(ProjectMaskPoint(0, 0), ProjectMaskPoint(w, 0), ProjectMaskPoint(w, h), ProjectMaskPoint(0, h))
            val mapped = pipeline.selectionToOriginal(ImageToolSelection(ImageToolCodeGen.Roi(0, 0, w, h), polygon = polygon))
            assertEquals(mode, ImageToolCodeGen.Roi(0, 0, 8, 6), mapped.box)
            assertEquals(mode, setOf(ProjectMaskPoint(0, 0), ProjectMaskPoint(8, 0), ProjectMaskPoint(8, 6), ProjectMaskPoint(0, 6)), mapped.polygon.toSet())
        }
    }

    @Test fun resizedRegionUsesBoundariesWhileColorPointsUsePixelSamples() {
        val pipeline = ImageProcessingPipeline(8, 6, listOf(ImagePixelOperation("resize", 4, 3), ImagePixelOperation("flipX")))
        val mapped = pipeline.selectionToOriginal(ImageToolSelection(ImageToolCodeGen.Roi(0, 0, 4, 3),
            listOf(ImageToolCodeGen.PickedPoint(0, 0, 0x123456))))
        assertEquals(ImageToolCodeGen.Roi(0, 0, 8, 6), mapped.box)
        assertEquals(ImageToolCodeGen.PickedPoint(6, 0, 0x123456), mapped.points.single())
        assertEquals(8.0 to 0.0, pipeline.mapBoundaryToOriginal(0.0, 0.0))
    }

    @Test fun cropRotationMirrorAndResizeRestoreScreenRegionWithoutDoubleMapping() {
        val pipeline = ImageProcessingPipeline(8, 6, listOf(
            ImagePixelOperation("crop", roi = ImageToolCodeGen.Roi(2, 1, 8, 5)),
            ImagePixelOperation("rotate90"), ImagePixelOperation("flipX"), ImagePixelOperation("resize", 2, 3)))
        assertEquals(ImageToolCodeGen.Roi(2, 1, 8, 5), pipeline.selectionToOriginal(
            ImageToolSelection(ImageToolCodeGen.Roi(0, 0, 2, 3))).box)
        assertEquals(ImageToolCodeGen.Roi(2, 1, 6, 3), pipeline.selectionToOriginal(
            ImageToolSelection(ImageToolCodeGen.Roi(0, 0, 1, 2))).box)
        assertEquals(4 to 3, pipeline.mapToOriginal(1, 1))
    }

    @Test fun fractionalResizeEdgesRoundOutwardAndContainSelectedSourceSamples() {
        val pipeline = ImageProcessingPipeline(7, 5, listOf(ImagePixelOperation("resize", 3, 2)))
        val region = requireNotNull(pipeline.selectionToOriginal(ImageToolSelection(ImageToolCodeGen.Roi(1, 0, 2, 1))).box)
        assertEquals(ImageToolCodeGen.Roi(2, 0, 5, 3), region)
        val pixel = pipeline.mapToOriginal(1, 0)
        assertTrue(pixel.first in region.left until region.right && pixel.second in region.top until region.bottom)
    }

    @Test fun colorOnlyProcessingPreservesRegionPolygonAndPointColors() {
        val pipeline = ImageProcessingPipeline(8, 6, listOf(ImagePixelOperation("gray"), ImagePixelOperation("threshold", threshold = 128)))
        val selection = ImageToolSelection(ImageToolCodeGen.Roi(1, 1, 6, 5),
            listOf(ImageToolCodeGen.PickedPoint(3, 2, 0xAB1234)),
            listOf(ProjectMaskPoint(1, 1), ProjectMaskPoint(6, 1), ProjectMaskPoint(3, 5)))
        assertEquals(selection, pipeline.selectionToOriginal(selection))
    }

    @Test fun invalidOrDegenerateRegionsNeverBecomeClampedValidSelections() {
        val pipeline = ImageProcessingPipeline(8, 6)
        for (region in listOf(ImageToolCodeGen.Roi(-1, 0, 2, 2), ImageToolCodeGen.Roi(0, 0, 9, 6),
            ImageToolCodeGen.Roi(2, 1, 2, 3), ImageToolCodeGen.Roi(2, 3, 1, 1))) {
            assertThrows(IllegalArgumentException::class.java) { pipeline.selectionToOriginal(ImageToolSelection(region)) }
        }
        assertThrows(IllegalArgumentException::class.java) { pipeline.mapBoundaryToOriginal(Double.NaN, 0.0) }
    }
}

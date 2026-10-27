package com.autoscript.studio

import com.autoscript.project.store.ProjectVariableType
import com.google.gson.JsonObject
import org.junit.Assert.*
import org.junit.Test

class ImageProcessingPipelineTest {
    @Test fun filterPipelineCarriesExactRulesAndReleasesScratchWithoutChangingCoordinates() {
        val filter=com.autoscript.project.store.ProjectImageFilter(output="keep")
        val pipeline=ImageProcessingPipeline(2,3).append(ImagePixelOperation("filter",filter=filter))
        val blocks=imageProcessingBlocks(pipeline,null,"scratch","result",variables,emptyList())
        assertEquals(listOf("screen.capture","screen.transform","screen.release"),blocks.map { it.first.kind })
        assertEquals(filter.encode(),blocks[1].second["filter"].asString)
        assertEquals("filter",blocks[1].second["mode"].asString)
        assertEquals(2 to 3,pipeline.dimensions)
    }

    private val variables = listOf(CalculationVariable("scratch", ProjectVariableType.IMAGE, "局部"),
        CalculationVariable("result", ProjectVariableType.IMAGE, "局部"), CalculationVariable("text", ProjectVariableType.STRING, "全局"))
    private val image = JsonObject().apply { addProperty("kind", "image"); addProperty("path", "assets/images/source.png") }

    @Test fun frameParityReleasesEveryIntermediateAndKeepsExplicitFinalOutput() {
        for (count in 0..6) {
            val pipeline = ImageProcessingPipeline(2, 3, List(count) { ImagePixelOperation("flipX") })
            val blocks = imageProcessingBlocks(pipeline, null, "scratch", "result", variables, emptyList())
            assertEquals(1 + count * 2, blocks.size)
            assertEquals("screen.capture", blocks.first().first.kind)
            var previous = blocks.first().second["resultVariable"].asString
            for (i in 0 until count) {
                val transform = blocks[1 + i * 2]; val release = blocks[2 + i * 2]
                assertEquals(previous, transform.second["frameVariable"].asString)
                assertNotEquals(previous, transform.second["resultVariable"].asString)
                assertEquals(previous, release.second["frameVariable"].asString)
                assertEquals("screen.release", release.first.kind)
                previous = transform.second["resultVariable"].asString
            }
            assertEquals("result", previous)
        }
    }

    @Test fun resourceLoadCropAndTransformHaveExactExistingContracts() {
        val pipeline = ImageProcessingPipeline(2, 3).append(ImagePixelOperation("rotate90"))
            .append(ImagePixelOperation("crop", roi = ImageToolCodeGen.Roi(1, 0, 3, 2)))
            .append(ImagePixelOperation("resize", 4, 2))
        assertEquals(4 to 2, pipeline.dimensions)
        val blocks = imageProcessingBlocks(pipeline, image["path"].asString, "scratch", "result", variables, listOf(image))
        assertEquals("screen.loadimage", blocks[0].first.kind)
        assertEquals(image["path"].asString, blocks[0].second["imagePath"].asString)
        assertEquals("screen.crop", blocks[3].first.kind)
        assertEquals(1, blocks[3].second["left"].asInt)
        assertEquals(3, blocks[3].second["right"].asInt)
        assertEquals(4, blocks[5].second["width"].asInt)
        assertTrue(blocks.none { it.first.kind.startsWith("input.") || it.second.has("autoCapture") })
    }

    @Test fun bitmapEvictionUndoAndRedoKeepReproducibleOperationLists() {
        class Image
        val root = ImageProcessingVersion(Image(), ImageProcessingPipeline(2, 3))
        val history = ImageProcessingHistory(root, { 1L }, {}, maxBytes = 100)
        repeat(10) {
            val next = ImageProcessingVersion(Image(), history.current.pipeline.append(ImagePixelOperation("flipX")))
            history.publish(next, history.revision)
        }
        assertEquals(8, history.editCount)
        assertEquals(10, history.current.pipeline.operations.size)
        history.undo(); assertEquals(9, history.current.pipeline.operations.size)
        history.redo(); assertEquals(10, history.current.pipeline.operations.size)
        val tooLong = ImageProcessingPipeline(1, 1, List(64) { ImagePixelOperation("gray") })
        assertThrows(IllegalArgumentException::class.java) { tooLong.append(ImagePixelOperation("gray")) }
    }

    @Test fun thresholdIsSerializedWithoutChangingOtherTransformContracts() {
        val pipeline = ImageProcessingPipeline(2, 3).append(ImagePixelOperation("threshold", threshold = 173))
            .append(ImagePixelOperation("rotate90"))
        val blocks = imageProcessingBlocks(pipeline, null, "scratch", "result", variables, emptyList())
        val threshold = blocks[1]
        assertEquals("screen.transform", threshold.first.kind)
        assertEquals("threshold", threshold.second["mode"].asString)
        assertEquals(173, threshold.second["threshold"].asInt)
        assertEquals(0, threshold.second["width"].asInt)
        assertEquals(0, threshold.second["height"].asInt)
        assertFalse(blocks[3].second.has("threshold"))
        assertEquals(3 to 2, pipeline.dimensions)
        assertEquals(listOf("screen.capture", "screen.transform", "screen.release", "screen.transform", "screen.release"), blocks.map { it.first.kind })
    }

    @Test fun undeclaredWrongTypeSameVariablesAndUnknownAssetAreRejected() {
        val pipeline = ImageProcessingPipeline(2, 3)
        for ((scratch, result) in listOf("scratch" to "scratch", "missing" to "result", "text" to "result")) {
            assertThrows(IllegalArgumentException::class.java) { imageProcessingBlocks(pipeline, null, scratch, result, variables, emptyList()) }
        }
        assertThrows(IllegalArgumentException::class.java) { imageProcessingBlocks(pipeline, "assets/images/missing.png", "scratch", "result", variables, listOf(image)) }
    }
}

package com.autoscript.studio

import com.autoscript.project.store.ProjectVariableType as Type
import com.autoscript.runtime.api.RecognitionPreviewReply as Reply
import com.google.gson.JsonObject
import org.junit.Assert.*
import org.junit.Test

class ImagePipelineRecognitionTailTest {
    private val pipeline = ImageProcessingPipeline(8, 6).append(ImagePixelOperation("rotate90"))
    private val roi = ImageToolCodeGen.Roi(1, 2, 5, 7)
    private fun resource(kind: String, path: String) = JsonObject().apply { addProperty("kind", kind); addProperty("path", path) }
    private val resources = listOf(resource("image", "assets/images/source.png"), resource("image", "assets/images/template.png"),
        resource("glyphDictionary", "dictionaries/main.asglyph"))
    private fun variables(tail: ImagePipelineRecognitionTail) = listOf(
        CalculationVariable("scratch", Type.IMAGE, "局部"), CalculationVariable("result", Type.IMAGE, "局部")) +
        tail.outputs.map { CalculationVariable(it.suggestion, it.type, "局部") }
    private fun names(tail: ImagePipelineRecognitionTail) = tail.outputs.associate { it.field to it.suggestion }
    private fun match(operation: Int = Reply.TEMPLATE) = ImagePipelineRecognitionTail.Match(
        RecognitionPreviewConfiguration(operation, "assets/images/template.png", if (operation == Reply.TEMPLATE) 12 else 0, 940, 6, 8, roi,
            if (operation == Reply.TEMPLATE) 3 else 0), roi)

    @Test fun pixelsAndGrayKeepTestedRegionParametersAndNeverRecaptureOrTouch() {
        for (operation in listOf(Reply.TEMPLATE, Reply.GRAY_TEMPLATE)) {
            val tail = match(operation)
            val blocks = imageProcessingRecognitionBlocks(pipeline, "assets/images/source.png", "scratch", "result", tail,
                names(tail), variables(tail), resources)
            assertEquals(listOf("screen.loadimage", "screen.transform", "screen.release",
                if (operation == Reply.TEMPLATE) "vision.findimage" else "vision.findgray", "screen.release"), blocks.map { it.first.kind })
            val args = blocks[3].second
            assertEquals("result", args["frameVariable"].asString)
            assertFalse(args["autoCapture"].asBoolean)
            assertEquals(940, args["similarityPermille"].asInt)
            assertEquals(1, args["region"].asJsonObject["left"].asInt)
            assertEquals(7, args["region"].asJsonObject["bottom"].asInt)
            assertEquals("matchScore", args["scoreVariable"].asString)
            if (operation == Reply.TEMPLATE) {
                assertEquals("none", args["successAction"].asString); assertEquals(12, args["tolerance"].asInt)
                assertEquals(3, args["direction"].asInt)
            }
            else assertFalse(args.has("successAction"))
            assertTrue(blocks.none { it.first.kind.startsWith("input.") || it.first.kind == "screen.capture" })
            assertEquals("result", blocks.last().second["frameVariable"].asString)
        }
    }

    @Test fun glyphAndAlphanumericUseProcessedFrameAndExplicitTypedOutputs() {
        for (operation in listOf(Reply.GLYPH, Reply.ALPHANUMERIC, Reply.LATIN_TEXT)) {
            val tail = ImagePipelineRecognitionTail.Ocr(ImageToolOcrConfig(operation, "dictionaries/main.asglyph", 0x123456, 12, 900, 3), roi)
            val blocks = imageProcessingRecognitionBlocks(pipeline, null, "scratch", "result", tail, names(tail), variables(tail), resources, retainFinal = true)
            assertEquals("screen.capture", blocks.first().first.kind)
            val args = blocks.last().second
            assertEquals("result", args["frameVariable"].asString)
            assertEquals("ocrText", args["textVariable"].asString)
            if (operation == Reply.GLYPH) {
                assertEquals("ocr.glyph", blocks.last().first.kind)
                assertTrue(args["foregroundRgb"].asJsonPrimitive.isNumber)
                assertEquals(0x123456, args["foregroundRgb"].asInt)
                assertEquals("ocrCoverage", args["coverageVariable"].asString)
            } else {
                assertEquals("ocr.alphanumeric", blocks.last().first.kind); assertFalse(args["autoCapture"].asBoolean)
                assertEquals(operation == Reply.LATIN_TEXT, args["includeSymbols"].asBoolean)
            }
        }
    }

    @Test fun unknownWrongTypeRepeatedOrFrameOutputsAreRejected() {
        val tail = match()
        val validNames = names(tail)
        for (invalid in listOf(validNames + ("xVariable" to "missing"), validNames + ("xVariable" to "found"),
            validNames + ("xVariable" to "result"), validNames - "xVariable", validNames + ("unexpected" to "foundX"))) {
            assertThrows(IllegalArgumentException::class.java) {
                imageProcessingRecognitionBlocks(pipeline, null, "scratch", "result", tail, invalid, variables(tail), resources)
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            tail.block("result", 6, 8, validNames, variables(tail).map { if (it.name == "foundX") it.copy(type = Type.STRING) else it }, resources)
        }
    }

    @Test fun changedDimensionsUndeclaredTemplatesDictionariesAndInvalidRegionsAreRejected() {
        val tail = match()
        assertThrows(IllegalArgumentException::class.java) {
            ImagePipelineRecognitionTail.Match(tail.settings, ImageToolCodeGen.Roi(0, 0, 6, 8))
        }
        assertThrows(IllegalArgumentException::class.java) { tail.block("result", 6, 9, names(tail), variables(tail), resources) }
        assertThrows(IllegalArgumentException::class.java) { tail.block("result", 6, 8, names(tail), variables(tail), emptyList()) }
        val glyph = ImagePipelineRecognitionTail.Ocr(ImageToolOcrConfig(Reply.GLYPH, "dictionaries/main.asglyph"), roi)
        assertThrows(IllegalArgumentException::class.java) { glyph.block("result", 6, 8, names(glyph), variables(glyph), emptyList()) }
        val alpha = ImagePipelineRecognitionTail.Ocr(ImageToolOcrConfig(), ImageToolCodeGen.Roi(0, 0, 21, 1))
        assertThrows(IllegalArgumentException::class.java) { alpha.block("result", 21, 1, names(alpha), variables(alpha), resources) }
        assertThrows(IllegalArgumentException::class.java) { glyph.block("result", 2, 2, names(glyph), variables(glyph), resources) }
    }

    @Test fun canonicalAndLegacyDictionaryKindsAreReadWithoutChangingManifest() {
        assertTrue(isGlyphDictionaryResource(resources.last()))
        assertTrue(isGlyphDictionaryResource(resource("glyph_dictionary", "dictionaries/main.asglyph")))
        assertFalse(isGlyphDictionaryResource(resources.first()))
        assertEquals("glyphDictionary", resources.last()["kind"].asString)
    }
}

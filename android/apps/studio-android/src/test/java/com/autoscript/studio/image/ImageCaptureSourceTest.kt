package com.autoscript.studio

import com.google.gson.JsonObject
import org.junit.Assert.*
import org.junit.Test

class ImageCaptureSourceTest {
    @Test fun unknownPreferenceDefaultsToFreshCapture() {
        for (stored in listOf(null, "", "unknown")) {
            assertEquals(ImageCaptureSource.EACH_CALL, ImageCaptureSource.fromPreference(stored))
        }
        for (source in ImageCaptureSource.entries) {
            assertEquals(source, ImageCaptureSource.fromPreference(source.name))
            assertEquals(source, ImageCaptureSource.fromCapture(source.capture))
        }
    }

    @Test fun newImageEntriesUseTheSelectedDefault() {
        for (default in ImageCaptureSource.entries) for (autoCapture in listOf(false, true)) {
            assertEquals(default, initialImageCaptureSource(true, autoCapture, default))
        }
    }

    @Test fun editingKeepsSavedSourceRegardlessOfNewDefault() {
        for (default in ImageCaptureSource.entries) for (autoCapture in listOf(false, true)) {
            assertEquals(ImageCaptureSource.fromCapture(autoCapture), initialImageCaptureSource(false, autoCapture, default))
        }
    }

    @Test fun bothSourcesKeepExactOutputsAndPairOnlyTheirOwnCaptureWithRelease() {
        val kinds = listOf("vision.findimage", "vision.findgray", "vision.findallimages", "ocr.alphanumeric",
            "vision.getcolor", "vision.findcolor", "vision.comparecolor", "vision.countcolor", "vision.findallcolor",
            "vision.findmulticolor", "ocr.glyph", "vision.findallmulticolor", "vision.components", "ocr.glyphdetails",
            "vision.findscaled", "vision.findfeatures", "ocr.alphanumericv5")
        for (kind in kinds) for (source in ImageCaptureSource.entries) {
            val contract = requireNotNull(BlockCatalog.find(kind))
            val arguments = JsonObject().apply {
                addProperty("frameVariable", "共用图像")
                addProperty("resultVariable", "识别结果")
                if (recognitionUsesOwnCapture(kind)) addProperty("autoCapture", !source.capture)
            }
            val original = arguments.deepCopy()
            val blocks = recognitionImageEntryBlocks(contract, arguments, source.capture)
            val operation = blocks.single { it.first.kind == kind }.second
            assertEquals(kind, "识别结果", operation["resultVariable"].asString)
            assertEquals(kind, "共用图像", operation["frameVariable"].asString)
            if (recognitionUsesOwnCapture(kind)) {
                assertEquals(listOf(kind), blocks.map { it.first.kind })
                assertEquals(source.capture, operation["autoCapture"].asBoolean)
            } else if (source.capture) {
                assertEquals(listOf("screen.capture", kind, "screen.release"), blocks.map { it.first.kind })
                assertEquals("共用图像", blocks.first().second["resultVariable"].asString)
                assertEquals("共用图像", blocks.last().second["frameVariable"].asString)
            } else {
                assertEquals(listOf(kind), blocks.map { it.first.kind })
                assertFalse(operation.has("autoCapture"))
            }
            assertEquals("Source selection must not mutate the pending draft", original, arguments)
        }
    }

    @Test fun extendedPixelRecognitionIncludesFeaturesAndCandidateOcrButNotResultReadersOrTransforms() {
        for (kind in listOf("vision.findfeatures", "ocr.alphanumericv5", "vision.findscaled", "vision.findallmulticolor", "vision.components", "ocr.glyphdetails")) {
            assertTrue(kind, recognitionCanWrapCapture(kind))
            assertTrue(requireNotNull(BlockCatalog.find(kind)).properties.any { it.path == "frameVariable" })
        }
        for (kind in listOf("vision.result", "vision.readresult", "vision.resultaction", "ocr.findtext",
            "screen.transform", "screen.crop", "screen.savepng", "screen.mappoint", "screen.compareframes")) {
            assertFalse(kind, recognitionCanWrapCapture(kind))
            for (source in ImageCaptureSource.entries) {
                val blocks = recognitionImageEntryBlocks(requireNotNull(BlockCatalog.find(kind)), JsonObject(), source.capture)
                assertEquals(listOf(kind), blocks.map { it.first.kind })
            }
        }
    }
}

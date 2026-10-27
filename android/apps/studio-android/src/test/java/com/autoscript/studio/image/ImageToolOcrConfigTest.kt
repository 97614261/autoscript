package com.autoscript.studio

import com.autoscript.runtime.api.RecognitionPreviewReply as Reply
import org.junit.Assert.*
import org.junit.Test

class ImageToolOcrConfigTest {
    private val roi = ImageToolCodeGen.Roi(11, 22, 211, 62)
    @Test fun screenshotPixelsAndTestedSettingsSurviveLuaAndVisualBackfill() {
        val config = ImageToolOcrConfig(Reply.GLYPH, "dictionaries/main.asglyph", 0x123456, 12, 950, 3)
        val snippet = config.snippet(roi, 1080, 1920)
        assertNull(snippet.rejection)
        assertTrue(snippet.code.contains("0x123456, 12, 950, 11, 22, 211, 62, 3"))
        val draft = requireNotNull(visualImageToolDraft(requireNotNull(snippet.imageSelection)))
        assertEquals("ocr.glyph", draft.first)
        assertEquals(config.arguments(roi), draft.second)
        assertEquals(listOf("screen.capture", "ocr.glyph", "screen.release"), ocrRecognitionSequence(draft.first, draft.second).map { it.first.kind })
    }

    @Test fun alphanumericUsesRealApiAndImplicitBalancedCaptureContract() {
        val config = ImageToolOcrConfig(operation = Reply.ALPHANUMERIC, threshold = 600)
        val snippet = config.snippet(roi, 720, 1280)
        assertNull(snippet.rejection)
        assertTrue(snippet.code.contains("Ocr.alphanumeric(frame, 11, 22, 211, 62, 600)"))
        assertTrue(config.arguments(roi).get("autoCapture").asBoolean)
        assertEquals(listOf("ocr.alphanumeric"), ocrRecognitionSequence(config.kind, config.arguments(roi)).map { it.first.kind })
        assertThrows(IllegalArgumentException::class.java) { config.copy(operation = 123).arguments(roi) }
        assertThrows(IllegalArgumentException::class.java) { config.copy(operation = Reply.GLYPH, dictionaryPath = "dictionaries/../bad.asglyph").arguments(roi) }
    }

    @Test fun freshV4ConfigurationsKeepSymbolsAndOldModeIsExplicit() {
        val config = ImageToolOcrConfig(threshold = 600)
        assertEquals(Reply.LATIN_TEXT, config.operation)
        assertTrue(config.arguments(roi)["includeSymbols"].asBoolean)
        assertTrue(config.snippet(roi, 720, 1280).code.contains("Ocr.latinText(frame, 11, 22, 211, 62, 600)"))
        assertNull(config.snippet(roi,720,1280).rejection)
        assertFalse(config.copy(operation=Reply.ALPHANUMERIC).arguments(roi)["includeSymbols"].asBoolean)
        val entry = imageEntryGroups.flatMap { it.second }.first { it.kind == TEXT_EXTRACTION_ENTRY }
        assertEquals("文本处理", entry.title)
        assertTrue(textExtractionKinds.containsAll(setOf("text.regexall", "text.regexextract", "text.split", "text.numberat")))
    }

    @Test fun discardedResultsAreExplicitAndNeverShownAsText() {
        for (status in listOf(Reply.CANCELLED, Reply.TIMEOUT, Reply.SESSION_MISMATCH, Reply.BUSY)) {
            val reply = Reply(1, 2, status, Reply.GLYPH, "stale result")
            assertFalse(recognitionPreviewMessage(reply).contains("stale result"))
        }
    }

    @Test fun savedGlyphHandoffPreservesTargetGeometryOutputsAndOriginalArguments() {
        val config = ImageToolOcrConfig(Reply.GLYPH, "dictionaries/new.asglyph", 0x123456, 9, 930, 0)
        val target = ImageToolOcrConfig(Reply.GLYPH, "dictionaries/old.asglyph").arguments(roi).apply {
            addProperty("textVariable", "myText")
            addProperty("frameVariable", "myFrame")
        }
        val baseline = target.deepCopy()
        val result = config.applyGlyphSettings(target)
        assertEquals(baseline, target)
        assertEquals(target.get("region"), result.get("region"))
        assertEquals("myText", result.get("textVariable").asString)
        assertEquals("myFrame", result.get("frameVariable").asString)
        assertEquals("dictionaries/new.asglyph", result.get("dictionaryPath").asString)
        assertEquals(0x123456, result.get("foregroundRgb").asInt)
        assertEquals(9, result.get("tolerance").asInt)
        assertEquals(930, result.get("similarityPermille").asInt)
        assertEquals(0, result.get("spaceGapColumns").asInt)
    }

    @Test fun savedGlyphHandoffRejectsInvalidSettingsRatherThanSwitchingAlgorithm() {
        val target = com.google.gson.JsonObject()
        val valid = ImageToolOcrConfig(Reply.GLYPH, "dictionaries/new.asglyph", threshold = 900)
        for (invalid in listOf(valid.copy(operation = Reply.ALPHANUMERIC), valid.copy(tolerance = 256),
            valid.copy(threshold = 1001), valid.copy(spaceGapColumns = -1), valid.copy(dictionaryPath = "bad"))) {
            assertThrows(IllegalArgumentException::class.java) { invalid.applyGlyphSettings(target) }
        }
        assertEquals(0, target.size())
    }
}

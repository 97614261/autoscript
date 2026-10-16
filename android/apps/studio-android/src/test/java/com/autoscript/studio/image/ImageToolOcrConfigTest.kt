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
        val config = ImageToolOcrConfig(threshold = 600)
        val snippet = config.snippet(roi, 720, 1280)
        assertNull(snippet.rejection)
        assertTrue(snippet.code.contains("Ocr.alphanumeric(frame, 11, 22, 211, 62, 600)"))
        assertTrue(config.arguments(roi).get("autoCapture").asBoolean)
        assertEquals(listOf("ocr.alphanumeric"), ocrRecognitionSequence(config.kind, config.arguments(roi)).map { it.first.kind })
        assertThrows(IllegalArgumentException::class.java) { config.copy(operation = 123).arguments(roi) }
        assertThrows(IllegalArgumentException::class.java) { config.copy(operation = Reply.GLYPH, dictionaryPath = "dictionaries/../bad.asglyph").arguments(roi) }
    }

    @Test fun discardedResultsAreExplicitAndNeverShownAsText() {
        for (status in listOf(Reply.CANCELLED, Reply.TIMEOUT, Reply.SESSION_MISMATCH, Reply.BUSY)) {
            val reply = Reply(1, 2, status, Reply.GLYPH, "stale result")
            assertFalse(recognitionPreviewMessage(reply).contains("stale result"))
        }
    }
}

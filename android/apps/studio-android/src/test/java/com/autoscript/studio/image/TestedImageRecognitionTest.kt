package com.autoscript.studio

import com.autoscript.runtime.api.RecognitionPreviewReply as Reply
import org.junit.Assert.*
import org.junit.Test

class TestedImageRecognitionTest {
    @Test fun proofRequiresExactIdentityOptionsResourceOperationAndCurrentRuntime() {
        val source = Any()
        val options = intArrayOf(0, 0, 10, 20, 900, 0xFFFFFF, 12, 0)
        val proof = TestedImageRecognition(source, Reply.TEMPLATE, options, "template", Reply(1, 2, Reply.SUCCESS, Reply.TEMPLATE))
        assertTrue(proof.matches(source, Reply.TEMPLATE, options, "template") { it.generation == 2L })
        assertFalse(proof.matches(Any(), Reply.TEMPLATE, options, "template") { true })
        assertFalse(proof.matches(source, Reply.GRAY_TEMPLATE, options, "template") { true })
        assertFalse(proof.matches(source, Reply.TEMPLATE, options.copyOf().apply { this[4] = 800 }, "template") { true })
        assertFalse(proof.matches(source, Reply.TEMPLATE, options.copyOf().apply { this[7] = 4 }, "template") { true })
        assertFalse(proof.matches(source, Reply.TEMPLATE, options.copyOf().apply { this[0] = 1 }, "template") { true })
        assertFalse(proof.matches(source, Reply.TEMPLATE, options, "other") { true })
        assertFalse(proof.matches(source, Reply.TEMPLATE, options, "template") { false })
        val originalOptions = options.copyOf()
        options[0] = 3
        assertTrue(proof.matches(source, Reply.TEMPLATE, originalOptions, "template") { true })
    }

    @Test fun cancelledFailedMismatchedRepliesNeverProveConfiguration() {
        val source = Any(); val options = IntArray(8)
        for (status in Reply.INVALID_INPUT..Reply.ENGINE_ERROR) {
            val proof = TestedImageRecognition(source, Reply.GLYPH, options, "dictionary", Reply(1, 2, status, Reply.GLYPH))
            assertFalse(proof.matches(source, Reply.GLYPH, options, "dictionary") { true })
        }
        val notFound = TestedImageRecognition(source, Reply.GLYPH, options, "dictionary", Reply(1, 2, Reply.NOT_FOUND, Reply.GLYPH))
        assertTrue(notFound.matches(source, Reply.GLYPH, options, "dictionary") { true })
        val mismatch = TestedImageRecognition(source, Reply.GLYPH, options, "dictionary", Reply(1, 2, Reply.SUCCESS, Reply.ALPHANUMERIC))
        assertFalse(mismatch.matches(source, Reply.GLYPH, options, "dictionary") { true })
    }
}

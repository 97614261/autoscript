package com.autoscript.runtime.client

import com.autoscript.runtime.api.RecognitionPreviewReply as Reply
import org.junit.Assert.*
import org.junit.Test

class RecognitionPreviewInputsTest {
    @Test fun fixedOcrPreviewsDoNotRequireAnUnrelatedTemplateOrDictionary() {
        for(operation in listOf(Reply.ALPHANUMERIC,Reply.ALPHANUMERIC_V5)) {
            assertFalse(Reply.requiresTarget(operation))
            assertTrue(Reply.validOptions(operation,intArrayOf(0,0,240,56,500,0xffffff,0,0)))
        }
    }
    @Test fun matchingAndGlyphPreviewsStillRequireTheirExactTarget() {
        for(operation in listOf(Reply.TEMPLATE,Reply.GRAY_TEMPLATE,Reply.SCALED_TEMPLATE,Reply.FEATURE_TEMPLATE,Reply.GLYPH,Reply.GLYPH_DETAILS))
            assertTrue(Reply.requiresTarget(operation))
    }
}

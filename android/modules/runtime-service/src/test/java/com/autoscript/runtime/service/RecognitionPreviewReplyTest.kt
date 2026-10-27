package com.autoscript.runtime.service

import com.autoscript.runtime.api.RecognitionPreviewReply as Reply
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class RecognitionPreviewReplyTest {
    @Test fun latinPreviewRetainsSymbolsButRejectsControlAndNonAsciiPayloads() {
        assertTrue(Reply.validOptions(Reply.LATIN_TEXT, intArrayOf(0,0,320,48,500,0,0,0)))
        assertFalse(Reply.requiresTarget(Reply.LATIN_TEXT))
        val text = "100(+100) 180"
        val prefix = byteArrayOf(0) + ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(900).array()
        val result = decodeNativePreviewReply(1,2,Reply.LATIN_TEXT,prefix + text.toByteArray())
        assertEquals(text, result.text)
        assertEquals(Reply.SUCCESS, result.status)
        for (suffix in listOf(byteArrayOf(10), byteArrayOf(127), byteArrayOf(0xff.toByte())))
            assertThrows(IllegalArgumentException::class.java) { decodeNativePreviewReply(1,2,Reply.LATIN_TEXT,prefix + suffix) }
        assertThrows(IllegalArgumentException::class.java) { decodeNativePreviewReply(1,2,Reply.ALPHANUMERIC,prefix + text.toByteArray()) }
    }
    @Test fun glyphPalettesOnlyExtendGlyphPreviewsAndRejectDuplicatePartialAndBudgetOverflow() {
        val base=intArrayOf(0,0,20,20,1000,0xffffff,0,0)
        val multi=base+intArrayOf(0xff0000,8,0x00ff00,16)
        assertTrue(Reply.validOptions(Reply.GLYPH,multi));assertTrue(Reply.validOptions(Reply.GLYPH_DETAILS,multi))
        assertFalse(Reply.validOptions(Reply.ALPHANUMERIC,multi))
        assertFalse(Reply.validOptions(Reply.GLYPH,base+intArrayOf(0xff0000)))
        assertFalse(Reply.validOptions(Reply.GLYPH,base+intArrayOf(0xffffff,8)))
        assertFalse(Reply.validOptions(Reply.GLYPH,base+intArrayOf(0xff0000,256)))
        val full=intArrayOf(0,0,2048,2048,1000,0xffffff,0,0)+(0..14).flatMap {listOf(it,0)}.toIntArray()
        assertFalse(Reply.validOptions(Reply.GLYPH,full))
    }
    @Test fun featureAndV5PreviewsAreExplicitBoundedOperationsWithDistinctResults() {
        assertTrue(Reply.validOptions(Reply.FEATURE_TEMPLATE,intArrayOf(0,0,128,128,600,0,0,0)))
        assertFalse(Reply.validOptions(Reply.FEATURE_TEMPLATE,intArrayOf(0,0,2048,2048,600,0,0,0)))
        assertTrue(Reply.validOptions(Reply.ALPHANUMERIC_V5,intArrayOf(0,0,128,64,500,0,0,0)))
        val payload=ByteBuffer.allocate(19).order(ByteOrder.LITTLE_ENDIAN).put(0).putInt(10).putInt(20).putInt(40).putInt(50).putShort(800).array()
        val reply=decodeNativePreviewReply(1,2,Reply.FEATURE_TEMPLATE,payload)
        assertEquals(Reply.SUCCESS,reply.status);assertEquals(10,reply.x)
        assertEquals(40,com.google.gson.JsonParser.parseString(reply.text).asJsonArray[0].asJsonObject["width"].asInt)
    }
    @Test fun scaledPreviewRequiresElevenBoundedOptionsWithoutReinterpretingOldOperations() {
        val valid=intArrayOf(0,0,20,20,900,0xffffff,0,0,750,1250,125)
        assertTrue(Reply.validOptions(Reply.SCALED_TEMPLATE,valid))
        assertFalse(Reply.validOptions(Reply.TEMPLATE,valid))
        assertFalse(Reply.validOptions(Reply.SCALED_TEMPLATE,valid.copyOf().apply {this[10]=1}))
        assertFalse(Reply.validOptions(Reply.SCALED_TEMPLATE,valid.copyOf().apply {this[7]=5}))
        assertFalse(Reply.validOptions(Reply.SCALED_TEMPLATE,valid.copyOf().apply {this[8]=0}))
    }
    @Test fun detailedGlyphOperationReusesBoundedPreviewOptionsWithoutChangingParcelFields() {
        assertTrue(Reply.validOptions(Reply.GLYPH_DETAILS,intArrayOf(0,0,20,20,900,0xFFFFFF,16,2)))
        assertFalse(Reply.validOptions(Reply.GLYPH_DETAILS,IntArray(7)))
        assertFalse(Reply.validOptions(6,intArrayOf(0,0,20,20,900,0,0,0)))
    }
    @Test fun failurePayloadOnlyCarriesFixedCodesNotSdkMessagesOrPaths() {
        fun reply(text: String) = decodeNativePreviewReply(10, 20, Reply.GRAY_TEMPLATE, byteArrayOf(1) + text.toByteArray())
        assertEquals("VISION_COMPONENT_LOAD_FAILED", reply("VISION_COMPONENT_LOAD_FAILED").text)
        assertEquals(Reply.ENGINE_ERROR, reply("VISION_COMPONENT_MISSING").status)
        assertEquals("", reply("/private/project/secret.png").text)
        assertEquals("", reply("VISION_COMPONENT_LOAD_FAILED extra").text)
        assertEquals("", reply("x".repeat(1000)).text)
    }
    @Test fun nativeOcrOutputKeepsRequestIdentityAndConfidence() {
        val response = byteArrayOf(0) + ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(900).array() + "Ab12".toByteArray()
        val reply = decodeNativePreviewReply(10, 20, Reply.ALPHANUMERIC, response)
        assertEquals(Reply(10, 20, Reply.SUCCESS, Reply.ALPHANUMERIC, "Ab12", scorePermille = 900), reply)
        assertEquals(Reply.ENGINE_ERROR, decodeNativePreviewReply(10, 20, Reply.ALPHANUMERIC, byteArrayOf(1)).status)
        assertThrows(IllegalArgumentException::class.java) { decodeNativePreviewReply(10, 20, Reply.ALPHANUMERIC, response + '.'.code.toByte()) }
        assertThrows(IllegalArgumentException::class.java) { decodeNativePreviewReply(10, 20, Reply.ALPHANUMERIC, byteArrayOf(0, 1)) }
    }
}

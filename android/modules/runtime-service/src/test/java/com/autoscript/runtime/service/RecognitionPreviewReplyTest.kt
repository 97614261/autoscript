package com.autoscript.runtime.service

import com.autoscript.runtime.api.RecognitionPreviewReply as Reply
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class RecognitionPreviewReplyTest {
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

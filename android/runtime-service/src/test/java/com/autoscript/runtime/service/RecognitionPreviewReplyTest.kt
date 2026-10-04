package com.autoscript.runtime.service

import com.autoscript.runtime.api.RecognitionPreviewReply as Reply
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class RecognitionPreviewReplyTest {
    @Test fun nativeOcrOutputKeepsRequestIdentityAndConfidence() {
        val response = byteArrayOf(0) + ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(900).array() + "Ab12".toByteArray()
        val reply = decodeNativePreviewReply(10, 20, Reply.ALPHANUMERIC, response)
        assertEquals(Reply(10, 20, Reply.SUCCESS, Reply.ALPHANUMERIC, "Ab12", scorePermille = 900), reply)
        assertEquals(Reply.ENGINE_ERROR, decodeNativePreviewReply(10, 20, Reply.ALPHANUMERIC, byteArrayOf(1)).status)
        assertThrows(IllegalArgumentException::class.java) { decodeNativePreviewReply(10, 20, Reply.ALPHANUMERIC, response + '.'.code.toByte()) }
        assertThrows(IllegalArgumentException::class.java) { decodeNativePreviewReply(10, 20, Reply.ALPHANUMERIC, byteArrayOf(0, 1)) }
    }
}

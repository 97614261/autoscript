package com.autoscript.runtime.service

import com.autoscript.runtime.api.RecordingReply
import com.autoscript.runtime.api.RuntimeBinarySignature
import java.nio.ByteBuffer
import java.nio.ByteOrder
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class RuntimeToolsContractTest {
    @Test fun runtimeToolTimeoutAndLateDecoderCallbacksCompleteOnlyOnce() {
        val replies = mutableListOf<Result<String>>()
        val completion = RuntimeToolCompletion { replies.add(it) }
        completion.finish(Result.failure(IllegalStateException("timeout")))
        completion.finish(Result.success("late"))
        completion.finish(Result.failure(IllegalStateException("decoder")))
        assertEquals(1, replies.size)
        assertEquals("timeout", replies.single().exceptionOrNull()?.message)
    }

    @Test fun runtimeToolConcurrentRepliesHaveExactlyOneWinner() {
        val count = java.util.concurrent.atomic.AtomicInteger()
        val completion = RuntimeToolCompletion { count.incrementAndGet() }
        val start = java.util.concurrent.CountDownLatch(1)
        val workers = (1..8).map {
            Thread { start.await(); completion.finish(Result.success("done")) }.apply { start() }
        }
        start.countDown()
        workers.forEach { it.join(2000); assertFalse(it.isAlive) }
        assertEquals(1, count.get())
    }
    @Test fun recordingJsonContainsExactOriginalPixelsAndNoPrivateContext() {
        val action = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN)
        listOf(1, 0, 100, 20, 30, 20, 30, 0).forEach(action::putInt)
        val reply = RecordingReply(7, 3, RecordingReply.SUCCESS, 720, 1280, 0, 100, action.array())
        val json = JsonParser.parseString(RuntimeToolsController.encodeRecording(reply)).asJsonObject
        assertEquals(6, json.size())
        assertEquals(720, json["width"].asInt)
        assertEquals(20, json["actions"].asJsonArray[0].asJsonArray[3].asInt)
        assertFalse(json.has("projectId")); assertFalse(json.has("generation"))
    }
    @Test fun binarySignatureIsContentBasedNotAnExtension() {
        assertTrue(RuntimeBinarySignature.matches("RIFF1234WAVE".toByteArray()))
        assertTrue(RuntimeBinarySignature.matches(byteArrayOf(-119,80,78,71,13,10,26,10)))
        assertFalse(RuntimeBinarySignature.matches("ordinary text.wav".toByteArray()))
        assertFalse(RuntimeBinarySignature.matches(byteArrayOf(1,2,3)))
    }
}

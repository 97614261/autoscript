package com.autoscript.runtime.service

import com.autoscript.runtime.api.RecordingReply
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class RecordingReplyTest {
    private fun action(kind: Int = 1, start: Int = 10, duration: Int = 20, x: Int = 10, y: Int = 20, key: Int = 0): ByteArray =
        ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN).apply {
            listOf(kind,start,duration,x,y,x,y,key).forEach(::putInt)
        }.array()
    private fun native(actions: ByteArray, status: Int = 0, skipped: Int = 0): ByteArray =
        ByteBuffer.allocate(24+actions.size).order(ByteOrder.LITTLE_ENDIAN).apply {
            putLong(17); putInt(actions.size/32); putInt(100); putInt(status); putInt(skipped); put(actions)
        }.array()
    @Test fun nativeResultPreservesPixelsWarningsAndRejectsIdentityLengthOrBudget() {
        val bytes = native(action(),skipped=2)
        val reply = RecordingReply.fromNative(bytes,17,99,1080,1920,1)
        assertTrue(reply.valid()); assertEquals(1,reply.rotation); assertEquals(2,reply.skippedGestures)
        assertArrayEquals(action(),reply.actions)
        assertThrows(IllegalArgumentException::class.java) { RecordingReply.fromNative(bytes,18,99,1080,1920,1) }
        assertThrows(IllegalArgumentException::class.java) { RecordingReply.fromNative(bytes.copyOf(bytes.size-1),17,99,1080,1920,1) }
        assertThrows(IllegalArgumentException::class.java) { RecordingReply.fromNative(ByteArray(24+RecordingReply.MAX_ACTION_BYTES+1),17,99,1080,1920,1) }
        assertThrows(IllegalArgumentException::class.java) { RecordingReply.fromNative(native(action(),status=4),17,99,1080,1920,1) }
    }
    @Test fun rejectedStatusCannotContainActionsAndInvalidTimingCoordinatesOrKeysAreRejected() {
        assertFalse(RecordingReply(17,99,4,actions=action()).valid())
        assertFalse(RecordingReply(17,99,0,100,100,actions=action(x=100),elapsedMs=100).valid())
        assertFalse(RecordingReply(17,99,0,100,100,actions=action(start=100),elapsedMs=100).valid())
        assertFalse(RecordingReply(17,99,0,100,100,actions=action()+action(start=15),elapsedMs=100).valid())
        assertFalse(RecordingReply(17,99,0,100,100,actions=action(kind=4,duration=0,x=0,y=0,key=67),elapsedMs=100).valid())
        assertTrue(RecordingReply(17,99,0,100,100,actions=action(kind=4,duration=0,x=0,y=0,key=4),elapsedMs=100).valid())
    }
    @Test fun controlWindowTouchesAreExcludedButGlobalActionsAndKeysRemain() {
        val target=action(x=200,y=300)
        val stop=action(start=50,x=20,y=20)
        val key=action(kind=4,start=80,duration=0,x=0,y=0,key=4)
        assertArrayEquals(target+key,RuntimeInputRecordingController.excludeControls(target+stop+key,10,10,100,100))
    }
}

package com.autoscript.runtime.service

import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test

class VisionProviderProcessLifetimeTest {
    @Test fun sameArtifactReusesCodeButReplacementRequiresProcessRestart() {
        val pins = VisionProviderProcessPins<Any>()
        val first = pins.pin("opencv", "first:arm64-v8a") { Any() }
        assertSame(first, pins.pin("opencv", "first:arm64-v8a") { error("must reuse") })
        assertSame(first, pins.existing("opencv", "first:arm64-v8a"))
        assertNull(pins.existing("onnx", "not-loaded:arm64-v8a"))
        assertThrows(IllegalStateException::class.java) { pins.existing("opencv", "different:arm64-v8a") }
        assertThrows(IllegalStateException::class.java) { pins.pin("opencv", "second:arm64-v8a") { Any() } }
        assertThrows(IllegalStateException::class.java) { pins.pin("opencv", "first:x86_64") { Any() } }
        assertNotSame(first, pins.pin("onnx", "first:arm64-v8a") { Any() })
        assertThrows(IllegalArgumentException::class.java) { pins.pin("arbitrary", "first") { Any() } }
    }

    @Test fun sdkInitializationFailureDoesNotDiscardProcessPin() {
        val pins = VisionProviderProcessPins<Any>()
        val code = pins.pin("onnx", "signed:arm64-v8a") { Any() }
        assertThrows(IllegalStateException::class.java) { error("SDK initialization failed after pin") }
        assertSame(code, pins.pin("onnx", "signed:arm64-v8a") { error("must retain") })
    }

    @Test fun backendCloseIsIdempotentAndRejectsFurtherExecutionEvenWhenCloseFailed() {
        var executes = 0
        var closes = 0
        val backend = object : NativeVisionBackend {
            override fun execute(work: NativeVisionWork, pixels: ByteBuffer, metadata: IntArray,
                templatePixels: ByteBuffer?, templateMetadata: IntArray, options: IntArray): ByteArray {
                executes++
                return byteArrayOf(7)
            }
            override fun close() { closes++; error("SDK close failed") }
        }
        val loaded = LoadedVisionProvider(backend)
        val buffer = ByteBuffer.allocateDirect(1)
        assertArrayEquals(byteArrayOf(7), loaded.execute(NativeVisionWork(1), buffer, intArrayOf(), null, intArrayOf(), intArrayOf()))
        assertThrows(IllegalStateException::class.java) { loaded.close() }
        loaded.close()
        assertThrows(IllegalStateException::class.java) {
            loaded.execute(NativeVisionWork(2), buffer, intArrayOf(), null, intArrayOf(), intArrayOf())
        }
        assertEquals(1, executes)
        assertEquals(1, closes)
    }
}

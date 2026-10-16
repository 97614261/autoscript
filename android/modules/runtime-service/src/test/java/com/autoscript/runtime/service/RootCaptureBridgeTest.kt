package com.autoscript.runtime.service

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class RootCaptureBridgeTest {
    @Test fun timingTrailerIsFixedBigEndianAndTimedPixelsAreIdentical() {
        val timings = RootCaptureStageTimings()
        val output = ByteArrayOutputStream()
        var clock = 0L
        writeRootCapturePixels(720, 1280, output, RootCaptureTransferBuffer(), timings, nanoTime = { clock += 1_000; clock }) { pixels, _, rows ->
            pixels.fill(0x80445566.toInt(), 0, 720 * rows)
        }
        assertEquals(59_000L, timings.pixelReadNs)
        assertEquals(59_000L, timings.encodeNs)
        assertEquals(59_000L, timings.writeNs)
        val original = ByteArrayOutputStream()
        writeRootCapturePixels(720, 1280, original, RootCaptureTransferBuffer()) { pixels, _, rows ->
            pixels.fill(0x80445566.toInt(), 0, 720 * rows)
        }
        assertArrayEquals(original.toByteArray(), output.toByteArray())
        timings.captureNs = 123_000; timings.readbackNs = 456_000
        val trailer = ByteArrayOutputStream()
        timings.writeTrailer(java.io.DataOutputStream(trailer))
        assertEquals(40, trailer.size())
        val input = java.io.DataInputStream(java.io.ByteArrayInputStream(trailer.toByteArray()))
        for (expected in listOf(123L, 456L, 59L, 59L, 59L)) assertEquals(expected, input.readLong())
        timings.captureNs = 10_000_001_000L
        assertThrows(IllegalArgumentException::class.java) { timings.writeTrailer(java.io.DataOutputStream(trailer)) }
    }

    @Test fun batchedPixelsMatchEveryOriginalRowAndDoNotWriteStaleTail() {
        val width = 720; val height = 1280
        val buffer = RootCaptureTransferBuffer()
        val output = ByteArrayOutputStream()
        var calls = 0
        writeRootCapturePixels(width, height, output, buffer) { pixels, y, rows ->
            calls++
            for (index in 0 until width * rows) pixels[index] = ((y * width + index) * 31) xor 0x80445566.toInt()
        }
        assertEquals(59, calls)
        assertEquals(width * height * 4, output.size())
        val expected = ByteArray(width * height * 4)
        encodeRootCapturePixels(IntArray(width * height) { (it * 31) xor 0x80445566.toInt() }, expected, width * height)
        assertArrayEquals(expected, output.toByteArray())
        val pixels = buffer.pixels; val bytes = buffer.bytes
        val next = ByteArrayOutputStream()
        writeRootCapturePixels(width, height, next, buffer) { data, _, rows -> data.fill(0, 0, width * rows) }
        assertSame(pixels, buffer.pixels)
        assertSame(bytes, buffer.bytes)
        assertTrue(next.toByteArray().all { it == 0.toByte() })
    }

    @Test fun buffersRemainBoundedAndGeometryChangesUseFreshLength() {
        val buffer = RootCaptureTransferBuffer()
        for ((width, height) in listOf(1 to 100, 720 to 1280, 1280 to 720, 16_384 to 1, 3 to 2)) {
            val rows = buffer.prepare(width, height)
            assertTrue(rows in 1..32)
            assertEquals(width * rows, buffer.pixels.size)
            assertTrue(buffer.bytes.size <= 65_536)
            val output = ByteArrayOutputStream()
            writeRootCapturePixels(width, height, output, buffer) { data, _, count -> data.fill(0xFF112233.toInt(), 0, width * count) }
            assertEquals(width * height * 4, output.size())
        }
        assertThrows(IllegalArgumentException::class.java) { buffer.prepare(0, 1) }
        assertThrows(IllegalArgumentException::class.java) { encodeRootCapturePixels(IntArray(1), ByteArray(4), 2) }
        assertThrows(IllegalArgumentException::class.java) { encodeRootCapturePixels(IntArray(1), ByteArray(3), 1) }
    }
    @Test fun rowUsesBgraWithoutSwappingRedAndBlueOrLosingAlpha() {
        val output = ByteArray(8)
        encodeRootCaptureRow(intArrayOf(0xFF112233.toInt(), 0x80445566.toInt()), output)
        assertArrayEquals(byteArrayOf(0x33, 0x22, 0x11, 0xFF.toByte(), 0x66, 0x55, 0x44, 0x80.toByte()), output)
    }
    @Test fun frameLimitsRejectOverflowAndZeroBeforeAllocation() {
        assertEquals(3_686_400, checkedRootCaptureBytes(720, 1280))
        assertThrows(IllegalArgumentException::class.java) { checkedRootCaptureBytes(0, 10) }
        assertThrows(IllegalArgumentException::class.java) { checkedRootCaptureBytes(Int.MAX_VALUE, 2) }
        assertThrows(IllegalArgumentException::class.java) { checkedRootCaptureBytes(16_384, 16_384) }
        assertThrows(IllegalArgumentException::class.java) { encodeRootCaptureRow(intArrayOf(1), ByteArray(3)) }
    }
}

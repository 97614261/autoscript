package com.autoscript.runtime.service

import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test

class OnnxImagePreprocessingTest {
    @Test fun rgbaAndBgraProduceTheSameBgrPlanesAndZeroPadding() {
        fun input(bytes: ByteArray, format: Int): OnnxImageInput {
            val pixels = ByteBuffer.allocateDirect(4).put(bytes).apply { flip() }
            val result = prepareOnnxImage(pixels, intArrayOf(1, 1, 4, format), intArrayOf(0, 0, 1, 1, 0))
            assertEquals(0, pixels.position())
            return result
        }
        val rgba = input(byteArrayOf(10, 20, 30, 0), 1)
        val bgra = input(byteArrayOf(30, 20, 10, -1), 2)
        assertEquals(48, rgba.width)
        assertEquals(320, rgba.paddedWidth)
        assertArrayEquals(rgba.tensor, bgra.tensor, 0f)
        assertEquals(30 / 127.5f - 1f, rgba.tensor[0], 0f)
        assertEquals(20 / 127.5f - 1f, rgba.tensor[48 * 320], 0f)
        assertEquals(10 / 127.5f - 1f, rgba.tensor[2 * 48 * 320], 0f)
        assertEquals(0f, rgba.tensor[48], 0f)
    }

    @Test fun preservesStrideRoiAndSourceWhileCheckingCancellation() {
        val pixels = ByteBuffer.allocateDirect(3 * 20)
        for (i in 0 until pixels.capacity()) pixels.put(i, 90.toByte())
        pixels.put(20 + 4, 10).put(20 + 5, 20).put(20 + 6, 30)
        val before = ByteArray(pixels.capacity()).also { pixels.duplicate().get(it) }
        val result = prepareOnnxImage(pixels, intArrayOf(4, 3, 20, 1), intArrayOf(1, 1, 2, 2, 0))
        assertEquals(30 / 127.5f - 1f, result.tensor[0], 0f)
        val after = ByteArray(pixels.capacity()).also { pixels.duplicate().get(it) }
        assertArrayEquals(before, after)
        assertThrows(IllegalStateException::class.java) {
            prepareOnnxImage(pixels, intArrayOf(4, 3, 20, 1), intArrayOf(1, 1, 2, 2, 0)) { error("cancelled") }
        }
    }

    @Test fun rejectsOverwideAndMalformedInputBeforeAllocation() {
        val pixels = ByteBuffer.allocateDirect(100 * 4)
        assertThrows(IllegalArgumentException::class.java) {
            prepareOnnxImage(pixels, intArrayOf(100, 1, 400, 1), intArrayOf(0, 0, 100, 1, 0))
        }
        assertThrows(IllegalArgumentException::class.java) {
            prepareOnnxImage(ByteBuffer.allocate(4), intArrayOf(1, 1, 4, 1), intArrayOf(0, 0, 1, 1, 0))
        }
    }
}

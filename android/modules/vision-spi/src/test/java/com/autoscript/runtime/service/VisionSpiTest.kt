package com.autoscript.runtime.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class VisionSpiTest {
    @Test fun bufferContractRejectsHeapBuffersAndBadStride() {
        val metadata = intArrayOf(2, 2, 8, 1)
        assertThrows(IllegalArgumentException::class.java) {
            validateVisionBuffer(16, 16, false, metadata)
        }
        assertThrows(IllegalArgumentException::class.java) {
            validateVisionBuffer(15, 15, true, intArrayOf(2, 2, 7, 1))
        }
    }

    @Test fun rectangleAndDecoderRemainBoundedAndAlphanumeric() {
        assertEquals(VisionRect(0, 0, 4, 3), checkedVisionRect(4, 3, intArrayOf(0, 0, 4, 3, 0)))
        val dictionary = List(63) { "" }.toMutableList().apply { this[1] = "A"; this[2] = "-" }
        val steps = arrayOf(
            FloatArray(63).apply { this[1] = .9f; this[2] = .1f },
            FloatArray(63).apply { this[1] = .8f; this[2] = .2f },
            FloatArray(63).apply { this[0] = .9f; this[1] = .1f },
        )
        assertEquals("A", decodeAlphanumeric(steps, dictionary, 500).first)
        assertThrows(IllegalArgumentException::class.java) {
            checkedVisionRect(10_000, 10_000, intArrayOf(0, 0, 10_000, 10_000, 0))
        }
    }
}

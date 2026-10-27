package com.autoscript.runtime.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class VisionSpiTest {
    private val latinDictionary = listOf("") + (32..126).map { it.toChar().toString() } + listOf("")
    private fun latinStep(symbol: String, score: Float = 1f) = FloatArray(97).apply {
        this[latinDictionary.indexOf(symbol)] = score
    }

    @Test fun symbolsSpacesAndCtcRepeatsSurviveOnlyInExplicitLatinMode() {
        val value = "100(+100) 180[-12.50]/25% = A_b:!"
        val steps = value.flatMap { listOf(latinStep(it.toString()), latinStep(it.toString()), latinStep("")) }.toTypedArray()
        assertEquals(value to 1000, decodeLatinText(steps, latinDictionary, 500))
        assertEquals("100100180125025Ab", decodeAlphanumeric(steps, latinDictionary, 500).first)
    }

    @Test fun latinDecoderKeepsFixedDictionaryThresholdAndOutputBudgets() {
        assertEquals("" to 0, decodeLatinText(arrayOf(latinStep("+", .4f)), latinDictionary, 500))
        assertThrows(IllegalArgumentException::class.java) { decodeLatinText(emptyArray(), List(438) { "" }, 500) }
        assertThrows(IllegalArgumentException::class.java) { decodeLatinText(Array(1025) { latinStep("1") }, latinDictionary, 500) }
        assertThrows(IllegalArgumentException::class.java) { decodeLatinText(arrayOf(latinStep("1", Float.NaN)), latinDictionary, 500) }
    }
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

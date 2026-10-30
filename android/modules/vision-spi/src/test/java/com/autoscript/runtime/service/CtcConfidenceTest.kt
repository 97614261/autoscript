package com.autoscript.runtime.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CtcConfidenceTest {
    private val dictionary = listOf("") + (32..126).map { it.toChar().toString() } + listOf("")
    private fun step(symbol: String, confidence: Float) = FloatArray(dictionary.size).apply {
        this[dictionary.indexOf(symbol)] = confidence
    }

    @Test fun weakLeadingTimestepDoesNotDiscardConfidentDigitInTheSameRun() {
        val steps = arrayOf(step("2", 1f), step("3", .4f), step("3", 1f), step("4", 1f))
        assertEquals("234" to 1000, decodeAlphanumeric(steps, dictionary, 1000))
    }

    @Test fun repeatedTimestepsEmitOnceAndBlankSeparatesRepeatedDigits() {
        val steps = arrayOf(step("3", .4f), step("3", 1f), step("3", .8f), step("", 1f), step("3", 1f))
        assertEquals("33" to 1000, decodeAlphanumeric(steps, dictionary, 1000))
    }

    @Test fun lowConfidenceRunRemainsRejectedAndFinalRunIsFlushed() {
        assertEquals("4" to 1000, decodeAlphanumeric(arrayOf(step("3", .4f), step("3", .49f), step("4", 1f)), dictionary, 500))
        assertEquals("" to 0, decodeAlphanumeric(arrayOf(step("3", .999f)), dictionary, 1000))
        assertEquals("" to 0, decodeAlphanumeric(emptyArray(), dictionary, 500))
    }

    @Test fun symbolsUseTheSameRunConfidenceWithoutChangingAlphanumericFiltering() {
        val steps = arrayOf(step("+", .4f), step("+", 1f), step("3", 1f))
        assertEquals("+3" to 1000, decodeLatinText(steps, dictionary, 1000))
        assertEquals("3" to 1000, decodeAlphanumeric(steps, dictionary, 1000))
    }

    @Test fun explicitV5UsesTheSameRunRuleAndRetainsItsDictionaryBudget() {
        val candidate = listOf("") + ('0'..'9').map(Char::toString) + List(427) { "!" }
        fun candidateStep(confidence: Float) = FloatArray(438).apply { this[4] = confidence }
        assertEquals("3" to 1000, decodeEnglishV5(arrayOf(candidateStep(.4f), candidateStep(1f)), candidate, 1000))
    }

    @Test fun malformedLaterTimestepStillRejectsTheWholeOutput() {
        assertThrows(IllegalArgumentException::class.java) {
            decodeAlphanumeric(arrayOf(step("3", 1f), step("3", Float.NaN)), dictionary, 500)
        }
    }
}

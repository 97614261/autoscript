package com.autoscript.runtime.service

import org.junit.Assert.*
import org.junit.Test

class NativeVisionAdapterTest {
    private val dictionary=listOf("") + ('A'..'Z').map(Char::toString) + ('a'..'z').map(Char::toString) + ('0'..'9').map(Char::toString) + listOf(".", " ")
    private fun step(symbol:String, confidence:Float=.9f)=FloatArray(dictionary.size).apply { this[dictionary.indexOf(symbol)]=confidence }
    @Test fun ctcCollapsesRepeatsButNotAcrossBlanksAndFiltersPunctuation() {
        val (text,score)=decodeAlphanumeric(arrayOf(step("A"),step("A"),step(""),step("A"),step("a"),step("1"),step("."),step(" ")),dictionary,500)
        assertEquals("AAa1",text)
        assertEquals(899,score) // Float32 .9 is just below .9.
    }
    @Test fun thresholdAppliesToEachAcceptedCharacter() {
        assertEquals("1" to 899,decodeAlphanumeric(arrayOf(step("a",.2f),step("1")),dictionary,500))
        assertEquals("" to 0,decodeAlphanumeric(arrayOf(step("")),dictionary,1000))
    }
    @Test fun malformedOrNonFiniteModelOutputIsRejected() {
        for(value in listOf(Float.NaN,Float.POSITIVE_INFINITY,-.1f,1.1f)) {
            assertThrows(IllegalArgumentException::class.java) { decodeAlphanumeric(arrayOf(step("A",value)),dictionary,500) }
        }
        assertThrows(IllegalArgumentException::class.java) { decodeAlphanumeric(arrayOf(FloatArray(1)),dictionary,500) }
    }
    @Test fun invalidAndOverBudgetRoiAreRejectedBeforeNativeWork() {
        listOf(intArrayOf(-1,0,10,10,500),intArrayOf(0,0,21,10,500),intArrayOf(1,1,1,2,500)).forEach {
            assertThrows(IllegalArgumentException::class.java) { checkedVisionRect(20,20,it) }
        }
        assertThrows(IllegalArgumentException::class.java) { checkedVisionRect(4096,4096,intArrayOf(0,0,4096,4096,500)) }
    }
}

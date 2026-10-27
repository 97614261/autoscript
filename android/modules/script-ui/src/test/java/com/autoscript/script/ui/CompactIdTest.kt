package com.autoscript.script.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class CompactIdTest {
    @Test fun tokenIsCompactAndUrlSafe() {
        val token = CompactId.token()
        assertEquals(16, token.length)
        assertTrue(token.matches(Regex("[A-Za-z0-9_-]{16}")))
    }

    @Test fun prefixedIdsRemainWithinEntityLimits() {
        val id = CompactId.next("node")
        assertTrue(id.matches(Regex("node-[A-Za-z0-9_-]{16}")))
        assertTrue(id.length < 32)
    }

    @Test fun numericIdsAdvancePastHistoricalSuffixes() {
        assertEquals("node-12", CompactId.nextNumeric("node", listOf("node-2", "node-11", "node-old")))
    }

    @Test fun numericIdsFailInsteadOfRepeatingAtTheUpperBound() {
        assertThrows(IllegalArgumentException::class.java) {
            CompactId.nextNumeric("node", listOf("node-${Int.MAX_VALUE}"))
        }
    }
}

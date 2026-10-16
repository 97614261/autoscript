package com.autoscript.runtime.service

import org.junit.Assert.*
import org.junit.Test

class StrictBase64Test {
    @Test fun standardVectorsAndBinaryRoundTripHaveCanonicalPadding() {
        for ((plain, encoded) in listOf("" to "", "f" to "Zg==", "fo" to "Zm8=", "foo" to "Zm9v", "foobar" to "Zm9vYmFy")) {
            assertEquals(encoded, StrictBase64.encode(plain.toByteArray()))
            assertArrayEquals(plain.toByteArray(), StrictBase64.decode(encoded))
        }
        val all = ByteArray(49152) { it.toByte() }
        assertArrayEquals(all, StrictBase64.decode(StrictBase64.encode(all)))
    }
    @Test fun malformedEncodingAndBudgetsAreRejected() {
        for (value in listOf("Zg", "Zh==", "Zm9=", "=g==", "Z g==", "Zg==\n", "____", "===="))
            assertThrows(IllegalArgumentException::class.java) { StrictBase64.decode(value) }
        assertThrows(IllegalArgumentException::class.java) { StrictBase64.encode(ByteArray(49153)) }
        assertThrows(IllegalArgumentException::class.java) { StrictBase64.decode("A".repeat(65540)) }
    }
}

package com.autoscript.runtime.client

import org.junit.Assert.*
import org.junit.Test

class VisionAvailabilityRecordsTest {
    @Test fun fixedSnapshotIsBoundedAndRejectsDuplicatesAndUnknownFamilies() {
        assertEquals(2, parseVisionAvailabilityRecords(arrayOf("opencv=AVAILABLE,source=bundled,loaded=false", "onnx=INSTALLED,source=provider,loaded=false")).size)
        for (records in listOf(emptyArray(), arrayOf("opencv=AVAILABLE"), arrayOf("opencv=AVAILABLE", "opencv=MISSING"),
            arrayOf("opencv=AVAILABLE", "arbitrary=AVAILABLE"), arrayOf("opencv=", "onnx=MISSING"), arrayOf("opencv=" + "X".repeat(145), "onnx=MISSING"))) {
            assertTrue(parseVisionAvailabilityRecords(records).isEmpty())
        }
    }
}

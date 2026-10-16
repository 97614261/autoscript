package com.autoscript.studio

import org.junit.Assert.*
import org.junit.Test

class RuntimeLogHealthTest {
    private fun event(algorithm: String, status: String) = """{"version":1,"kind":"vision","algorithm":"$algorithm","elapsedUs":1000,"status":"$status"}"""

    @Test fun countsIgnoreDerivedRowsAndKeepOutcomesSeparate() {
        val capture = """{"version":1,"kind":"vision","algorithm":"capture-host-roundtrip","elapsedUs":30000,"status":"completed","captureStagesUs":{"acquisition":20000,"transferVerified":8000,"framePublish":1000},"captureTransferUs":{"sendEncode":1,"sendWrite":2,"receiveRead":3,"verify":4,"append":5,"packets":60}}"""
        val report = VisionRunStatistics.parse(listOf(capture, event("rust-rgba-first", "found"), event("rust-rgba-first", "not_found"),
            event("rust-rgba-first", "timeout"), event("rust-rgba-first", "budget_exceeded"), event("capture-host-roundtrip", "error"),
            event("capture-host-roundtrip", "cancelled")).joinToString("\n"))
        assertEquals(RuntimeLogHealth.Counts(1, 1, 1, 3, 1), RuntimeLogHealth.counts(report))
        assertTrue(RuntimeLogHealth.visionSummary(report, mapOf("droppedRecords" to "2")).contains("丢弃 2"))
    }

    @Test fun durationsNeverInferTimeFromSamplesOrWallClock() {
        assertEquals("未知", RuntimeLogHealth.duration(null))
        assertEquals("未知", RuntimeLogHealth.duration(-1))
        assertEquals("0:00", RuntimeLogHealth.duration(999))
        assertEquals("7:05", RuntimeLogHealth.duration(425000))
        assertEquals("1:00:00", RuntimeLogHealth.duration(3600000))
        val old = RuntimeLogHealth.runSummary(mapOf("startedAtMs" to "100", "endedAtMs" to "300000"))
        assertTrue(old.contains("运行 未知")); assertTrue(old.contains("Root 未知"))
    }

    @Test fun runningDurationIsAnObservedSnapshotAndRootReplacementIsExplicit() {
        val text = RuntimeLogHealth.runSummary(mapOf("status" to "running", "durationMs" to "425000", "rootSessionAgeMs" to "540000", "rootSessionChanged" to "true"))
        assertTrue(text.contains("已记录 7:05")); assertTrue(text.contains("Root 9:00（已断开）"))
    }
}

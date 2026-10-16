package com.autoscript.studio

import com.autoscript.runtime.api.RuntimeLogFiles
import com.autoscript.runtime.api.RuntimeLogWriter
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RuntimeRunReliabilityTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun capture(landscape: Boolean = false) = """{"version":1,"kind":"vision","algorithm":"capture-host-roundtrip","frame":${if (landscape) "[1280,720]" else "[720,1280]"},"captureBackend":"root-surfacecontrol","captureTransport":"authenticated-stream","elapsedUs":36000,"status":"completed"}"""

    private fun awaitCaptures(root: File, id: String, expected: Int) {
        val deadline = System.nanoTime() + 3_000_000_000L
        while (true) {
            val observed = runCatching { RuntimeLogFiles.snapshot(root, id, "events.jsonl").run.metadata["visionCaptures"]?.toIntOrNull() }.getOrNull()
            if (observed == expected) return
            check(System.nanoTime() < deadline) { "Timed out waiting for synthetic journal flush: $observed / $expected" }
            Thread.sleep(10)
        }
    }

    @Test fun syntheticLongRunRotatesAcrossOrientationChangesAndPreservesFinalState() {
        val root = temporary.newFolder()
        val clock = AtomicLong(1000)
        val writer = RuntimeLogWriter(root, elapsedMillis = clock::get, eventSegmentBytes = 16384, eventSegments = 3)
        try {
            val id = writer.begin(mapOf("rootSessionAgeAtStartMs" to "1000"))!!
            repeat(20) { batch ->
                repeat(100) { writer.append("events.jsonl", capture(batch % 2 == 1)) }
                clock.addAndGet(300_000)
                awaitCaptures(root, id, (batch + 1) * 100)
            }
            writer.rootSessionLost(6_000_000)
            writer.end("stopped")
            writer.close(); assertTrue(writer.awaitClosed(5000))
            val snapshot = RuntimeLogFiles.snapshot(root, id, "events.jsonl")
            assertEquals("2000", snapshot.run.metadata["visionCaptures"])
            assertEquals("0", snapshot.run.metadata["droppedRecords"])
            assertEquals("0", snapshot.run.metadata["truncatedRecords"])
            assertEquals("6000000", snapshot.run.metadata["durationMs"])
            assertEquals("true", snapshot.run.metadata["rootSessionChanged"])
            assertEquals("stopped", snapshot.run.metadata["status"])
            assertTrue(snapshot.run.metadata.getValue("evictedEventRecords").toInt() > 0)
            val report = VisionRunStatistics.parse(snapshot.events)
            assertEquals(0, report.malformed)
            assertTrue(report.groups.sumOf { it.count } < 2000)
            val segments = RuntimeLogFiles.directory(root, id).listFiles()!!.filter { it.name.startsWith("events.") }
            assertEquals(3, segments.size)
            assertTrue(segments.all { it.length() <= 16384 })
            assertFalse(writer.failure)
        } finally { writer.close(); writer.awaitClosed(5000) }
    }

    @Test fun terminalAndNewRunsDoNotShareCountsOrAcceptLateOutputAfterEnd() {
        val root = temporary.newFolder()
        val writer = RuntimeLogWriter(root)
        try {
            val first = writer.begin(emptyMap())!!
            writer.append("events.jsonl", capture())
            writer.end("stopped")
            writer.append("events.jsonl", capture())
            val second = writer.begin(emptyMap())!!
            writer.append("events.jsonl", capture(true))
            writer.end("ended")
            writer.close(); assertTrue(writer.awaitClosed(5000))
            for ((id, status) in listOf(first to "stopped", second to "ended")) {
                val snapshot = RuntimeLogFiles.snapshot(root, id, "events.jsonl")
                assertEquals("1", snapshot.run.metadata["visionCaptures"])
                assertEquals(status, snapshot.run.metadata["status"])
                assertEquals(1, VisionRunStatistics.parse(snapshot.events).groups.single().count)
            }
            assertNull(writer.begin(emptyMap()))
        } finally { writer.close(); writer.awaitClosed(5000) }
    }
}

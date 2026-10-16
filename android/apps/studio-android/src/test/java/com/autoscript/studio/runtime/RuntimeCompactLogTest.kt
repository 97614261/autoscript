package com.autoscript.studio

import com.autoscript.runtime.api.RuntimeLogEventCodec
import com.autoscript.runtime.api.RuntimeLogFiles
import com.autoscript.runtime.api.RuntimeLogWriter
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RuntimeCompactLogTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun capture(time: Int = 28000) = """{"version":1,"kind":"vision","opcode":5000,"algorithm":"capture-host-roundtrip","flowId":"main","nodeId":"node-12345678-1234-1234-1234-123456789abc","frame":[720,1280],"captureBackend":"root-surfacecontrol","captureTransport":"authenticated-stream","elapsedUs":$time,"status":"completed","receivedAtElapsedMs":1000,"captureStagesUs":{"acquisition":16000,"transferVerified":9500,"framePublish":140,"systemCapture":2900,"softwareReadback":9920,"pixelRead":1230,"pixelEncode":620,"bridgeWrite":1620,"bridgeReceive":3350},"captureTransferUs":{"sendEncode":7710,"sendWrite":1380,"receiveRead":460,"verify":7430,"append":1380,"packets":60}}"""

    @Test fun compactRoundtripPreservesEveryFieldAndPercentileWithLegacyMix() {
        val encoder = RuntimeLogEventCodec.Encoder()
        val first = encoder.encode(capture())
        val next = encoder.encode(capture(35000))
        assertNotNull(first.condition); assertNull(next.condition)
        val decoder = RuntimeLogEventCodec.Decoder()
        assertNull(decoder.expand(first.condition!!))
        assertEquals(JsonParser.parseString(capture()), JsonParser.parseString(decoder.expand(first.sample)))
        val mixed = first.condition + "\n" + first.sample + "\n" + next.sample + "\n" + capture(40000)
        assertEquals(VisionRunStatistics.parse(listOf(capture(), capture(35000), capture(40000)).joinToString("\n")), VisionRunStatistics.parse(mixed))
        val legacyBytes = (capture().length + 1) * 1000
        val compactBytes = first.condition!!.length + 1 + (first.sample.length + 1) * 1000
        assertTrue("$compactBytes vs $legacyBytes", compactBytes < legacyBytes / 3)
        println("compact-capture: 1000 samples legacy=$legacyBytes bytes compact=$compactBytes bytes")
        val preview = RuntimeLogEventCodec.preview(mixed)
        assertEquals(3, preview.lines().size)
        assertFalse(preview.contains("captureStagesUs"))
        assertTrue(preview.contains("截图 完成 · 28.00ms"))
    }

    @Test fun absentAndNullFieldsAndUnknownExtensionsAreNotSilentlyLost() {
        val encoder = RuntimeLogEventCodec.Encoder()
        val raw = capture().replace("\"bridgeReceive\":3350", "\"bridgeReceive\":null").replace("\"pixelEncode\":620,", "")
        val packed = encoder.encode(raw)
        val decoder = RuntimeLogEventCodec.Decoder()
        decoder.expand(packed.condition!!)
        assertEquals(JsonParser.parseString(raw), JsonParser.parseString(decoder.expand(packed.sample)))
        val future = capture().replace("\"bridgeReceive\":3350", "\"futureStage\":3350")
        assertEquals(future, encoder.encode(future).sample)
        val unknown = capture().dropLast(1) + ",\"futureFlag\":true}"
        val preserved = encoder.encode(unknown)
        decoder.expand(preserved.condition!!)
        assertEquals(JsonParser.parseString(unknown), JsonParser.parseString(decoder.expand(preserved.sample)))
        val oldExtension = capture().dropLast(1) + ",\"v\":\"legacy-extension\"}"
        assertEquals(0, VisionRunStatistics.parse(oldExtension).malformed)
    }

    @Test fun missingConflictingAndMalformedDefinitionsNeverBecomeSamples() {
        val packed = RuntimeLogEventCodec.Encoder().encode(capture())
        assertEquals(1, VisionRunStatistics.parse(packed.sample).malformed)
        val conflict = packed.condition!!.replace("\"main\"", "\"other\"")
        val bad = VisionRunStatistics.parse(packed.condition + "\n" + conflict + "\n" + packed.sample)
        assertEquals(2, bad.malformed); assertTrue(bad.groups.isEmpty())
        val mask = packed.sample.replace(",60,63]", ",60,64]")
        val invalid = VisionRunStatistics.parse(packed.condition + "\n" + mask)
        assertEquals(1, invalid.malformed); assertTrue(invalid.groups.isEmpty())
        assertEquals(1, VisionRunStatistics.parse(packed.condition + "\n" + packed.sample.replace("\"v\":2", "\"v\":2.5")).malformed)
    }

    @Test fun conditionBudgetFallsBackToFullRecordsWithoutChangingResults() {
        val encoder = RuntimeLogEventCodec.Encoder()
        val lines = mutableListOf<String>()
        repeat(257) { index ->
            val raw = capture().replace("\"main\"", "\"flow-$index\"")
            val packed = encoder.encode(raw)
            packed.condition?.let(lines::add)
            lines.add(packed.sample)
            if (index == 256) { assertNull(packed.condition); assertEquals(raw, packed.sample) }
        }
        val report = VisionRunStatistics.parse(lines.joinToString("\n"))
        assertEquals(0, report.malformed)
        assertEquals(257, report.groups.count { it.algorithm == "capture-host-roundtrip" })
    }

    @Test fun conditionByteBudgetIsBoundedAndFallbackDoesNotLoseUnknownFields() {
        val encoder = RuntimeLogEventCodec.Encoder()
        var definitions = 0
        var bytes = 0
        repeat(100) { index ->
            val raw = capture().dropLast(1) + ",\"futureGeometry\":\"$index-${"a".repeat(4000)}\"}"
            val packed = encoder.encode(raw)
            packed.condition?.let { definitions++; bytes += it.toByteArray(Charsets.UTF_8).size + 1 }
            if (packed.condition == null) assertEquals(raw, packed.sample)
        }
        assertTrue(definitions in 1..99)
        assertTrue(bytes <= RuntimeLogEventCodec.CONDITION_BYTES)
    }

    @Test fun budgetsAndTimeoutsAreSeparateConditionsAfterCompactDecoding() {
        val encoder = RuntimeLogEventCodec.Encoder()
        val lines = listOf(100, 200).flatMap { budget ->
            val raw = capture().dropLast(1) + ",\"comparisonBudget\":$budget,\"timeoutMs\":5000}"
            val packed = encoder.encode(raw)
            listOfNotNull(packed.condition, packed.sample)
        }.joinToString("\n")
        val report = VisionRunStatistics.parse(lines)
        assertEquals(0, report.malformed)
        assertEquals(2, report.groups.count { it.algorithm == "capture-host-roundtrip" })
    }

    @Test fun rotationKeepsLatestDetailsWhileCumulativeCountsAndFinalStateSurvive() {
        val root = temporary.newFolder()
        val writer = RuntimeLogWriter(root, eventSegmentBytes = 16384, eventSegments = 2)
        val id = writer.begin(emptyMap())!!
        // One bounded batch rotates several small test segments without a queue overflow.
        repeat(250) { writer.append("events.jsonl", capture(28000 + it)) }
        writer.append("events.jsonl", capture().replace("\"completed\"", "\"error\""))
        writer.end("stopped"); writer.close(); assertTrue(writer.awaitClosed(5000))
        assertFalse(writer.failureCode, writer.failure)
        val run = RuntimeLogFiles.list(root).single()
        assertEquals("250", run.metadata["visionCaptures"])
        assertEquals("1", run.metadata["visionFailed"])
        assertEquals("0", run.metadata["droppedRecords"])
        assertTrue(run.metadata.getValue("evictedEventRecords").toLong() > 0)
        assertEquals("stopped", run.metadata["status"])
        val dir = RuntimeLogFiles.directory(root, id)
        val segments = dir.listFiles()!!.filter { it.name.startsWith("events.") }
        assertEquals(2, segments.size); assertTrue(segments.all { it.length() <= 16384 })
        val content = RuntimeLogFiles.read(root, id, "events.jsonl")
        val report = VisionRunStatistics.parse(content)
        assertEquals(0, report.malformed)
        assertTrue(report.groups.first { it.algorithm == "capture-host-roundtrip" && it.status == "completed" }.count < 250)
        assertEquals(1, report.groups.first { it.algorithm == "capture-host-roundtrip" && it.status == "error" }.count)
        assertTrue(RuntimeLogHealth.visionSummary(report, run.metadata).contains("累计 截图 250"))
        assertTrue(RuntimeLogFiles.export(root, run).contains("\"s\":\"error\""))
    }

    @Test fun productionSizeRotationKeepsEightSegmentsAndExactCumulativeCounts() {
        val root = temporary.newFolder()
        val writer = RuntimeLogWriter(root)
        val id = writer.begin(mapOf("projectId" to "qa"))!!
        // An unknown extension stays lossless, exercising the actual 8 x 256KiB quota.
        val padding = "x".repeat(6000)
        try {
            repeat(8) { batch ->
                repeat(64) { index ->
                    val record = capture().dropLast(1) + ",\"qaPadding\":\"${batch * 64 + index}-$padding\"}"
                    writer.append("events.jsonl", record)
                }
                val deadline = System.nanoTime() + 5_000_000_000L
                val expected = (batch + 1) * 64
                while (System.nanoTime() < deadline) {
                    val observed = RuntimeLogFiles.list(root).singleOrNull()?.metadata?.get("visionCaptures")?.toIntOrNull() ?: 0
                    if (observed >= expected) break
                    Thread.sleep(10)
                }
                assertEquals(expected.toString(), RuntimeLogFiles.list(root).single().metadata["visionCaptures"])
            }
            writer.end("stopped")
        } finally {
            writer.close(); assertTrue(writer.awaitClosed(5000))
        }
        assertFalse(writer.failureCode, writer.failure)
        val run = RuntimeLogFiles.list(root).single()
        val segments = RuntimeLogFiles.directory(root, id).listFiles()!!.filter { it.name.startsWith("events.") }
        assertEquals(RuntimeLogFiles.EVENT_SEGMENTS, segments.size)
        assertTrue(segments.all { it.length() <= RuntimeLogFiles.EVENT_SEGMENT_BYTES })
        val report = VisionRunStatistics.parse(RuntimeLogFiles.read(root, id, "events.jsonl"))
        assertEquals(0, report.malformed)
        val retained = report.groups.filter { it.algorithm == "capture-host-roundtrip" }.sumOf { it.count.toLong() }
        assertEquals(512L, retained + run.metadata.getValue("evictedEventRecords").toLong())
        assertEquals("512", run.metadata["visionCaptures"])
        assertEquals("0", run.metadata["droppedRecords"])
        assertEquals("stopped", run.metadata["status"])
        assertTrue(RuntimeLogFiles.export(root, run).contains("qaPadding"))
    }

    @Test fun legacyFilesRemainReadableAndOversizedSegmentIsRejected() {
        val root = temporary.newFolder()
        val writer = RuntimeLogWriter(root)
        val id = writer.begin(emptyMap())!!
        writer.close(); assertTrue(writer.awaitClosed(5000))
        val dir = RuntimeLogFiles.directory(root, id)
        File(dir, "events.jsonl").writeText(capture() + "\n")
        assertEquals(0, VisionRunStatistics.parse(RuntimeLogFiles.read(root, id, "events.jsonl")).malformed)
        val segment = File(dir, "events.00000000000000000000.jsonl")
        segment.writeBytes(ByteArray(RuntimeLogFiles.EVENT_SEGMENT_BYTES + 1))
        assertThrows(IllegalArgumentException::class.java) { RuntimeLogFiles.read(root, id, "events.jsonl") }
    }

    @Test fun failedEventStorageStillPersistsFailureCountersAndTerminalMetadata() {
        val root = temporary.newFolder()
        val writer = RuntimeLogWriter(root)
        val id = writer.begin(emptyMap())!!
        val dir = RuntimeLogFiles.directory(root, id)
        dir.mkdirs()
        File(dir, "conditions.jsonl").writeBytes(ByteArray(RuntimeLogEventCodec.CONDITION_BYTES + 1))
        writer.append("events.jsonl", capture())
        writer.end("stopped"); writer.close(); assertTrue(writer.awaitClosed(5000))
        assertTrue(writer.failure)
        val run = RuntimeLogFiles.list(root).single()
        assertEquals("stopped", run.metadata["status"])
        assertEquals("true", run.metadata["writerError"])
        assertEquals("1", run.metadata["droppedRecords"])
    }

    @Test fun crossProcessFileLockTimeoutIsBoundedAndSnapshotUsesLatestMetadata() {
        val root = temporary.newFolder()
        val writer = RuntimeLogWriter(root)
        val id = writer.begin(emptyMap())!!
        writer.append("events.jsonl", capture())
        writer.end("stopped"); writer.close(); assertTrue(writer.awaitClosed(5000))
        val dir = RuntimeLogFiles.directory(root, id)
        val snapshot = RuntimeLogFiles.snapshot(root, id, "events.jsonl")
        assertEquals("1", snapshot.run.metadata["visionCaptures"])
        assertEquals(0, VisionRunStatistics.parse(snapshot.events).malformed)
        java.io.RandomAccessFile(File(dir, "journal.lock"), "rw").use { file ->
            file.channel.lock(0, 1, false).use {
                val start = System.nanoTime()
                assertThrows(IllegalStateException::class.java) { RuntimeLogFiles.read(root, id, "events.jsonl") }
                assertTrue((System.nanoTime() - start) / 1_000_000 < 3000)
            }
        }
        assertEquals(snapshot.events, RuntimeLogFiles.read(root, id, "events.jsonl"))
    }
}

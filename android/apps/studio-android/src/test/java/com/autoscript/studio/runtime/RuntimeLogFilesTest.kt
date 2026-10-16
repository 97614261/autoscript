package com.autoscript.studio

import com.autoscript.runtime.api.RuntimeLogFiles
import com.autoscript.runtime.api.RuntimeLogWriter
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RuntimeLogFilesTest {
    @get:Rule val temporary = TemporaryFolder()
    @Test fun monotonicDurationIgnoresWallClockChangesAndDoesNotMoveBackwards() {
        val elapsed = java.util.concurrent.atomic.AtomicLong(1000)
        val wall = java.util.concurrent.atomic.AtomicLong(200000)
        val root = temporary.newFolder()
        val measured = RuntimeLogWriter(root, elapsed::get, wall::get)
        measured.begin(mapOf("rootSessionAgeAtStartMs" to "120000"))
        elapsed.set(426000); wall.set(100)
        measured.append("script.log", "fixture")
        elapsed.set(425999)
        measured.end("stopped"); measured.close()
        assertTrue(measured.awaitClosed(5000))
        val run = RuntimeLogFiles.list(root).single()
        assertEquals("425000", run.metadata["durationMs"])
        assertEquals("545000", run.metadata["rootSessionAgeMs"])
        assertEquals("100", run.metadata["endedAtMs"])
    }

    @Test fun rootDisconnectionFreezesOriginalSessionAgeAndNextRunDoesNotInheritIt() {
        val elapsed = java.util.concurrent.atomic.AtomicLong(0)
        val root = temporary.newFolder()
        val writer = RuntimeLogWriter(root, elapsed::get)
        writer.begin(mapOf("rootSessionAgeAtStartMs" to "120000"))
        elapsed.set(420000); writer.rootSessionLost(540000)
        elapsed.set(900000); writer.rootSessionLost(100) // Late duplicate must not replace the original age.
        writer.end("ended"); writer.close(); assertTrue(writer.awaitClosed(5000))
        val run = RuntimeLogFiles.list(root).single()
        assertEquals("900000", run.metadata["durationMs"])
        assertEquals("540000", run.metadata["rootSessionAgeMs"])
        assertEquals("true", run.metadata["rootSessionChanged"])
        val next = RuntimeLogWriter(root, elapsed::get)
        val id = next.begin(emptyMap())!!
        next.end("ended"); next.close(); assertTrue(next.awaitClosed(5000))
        assertNull(RuntimeLogFiles.list(root).first { it.id == id }.metadata["rootSessionChanged"])
        assertNull(RuntimeLogFiles.list(root).first { it.id == id }.metadata["rootSessionAgeMs"])
    }
    @Test fun closeDrainsTailAndKeepsChannelsSeparate() {
        val root = temporary.newFolder()
        val writer = RuntimeLogWriter(root)
        val id = writer.begin(mapOf("device" to "fixture", "projectId" to "public-project"))!!
        writer.append("script.log", "测试用户日志")
        writer.append("events.jsonl", "{\"kind\":\"trace\"}")
        writer.end("ended")
        writer.close()
        assertTrue(writer.awaitClosed(5000))
        val run = RuntimeLogFiles.list(root).single()
        assertEquals(id, run.id)
        assertEquals("ended", run.metadata["status"])
        assertTrue(RuntimeLogFiles.read(root, id, "script.log").contains("测试用户日志"))
        assertFalse(RuntimeLogFiles.read(root, id, "script.log").contains("trace"))
        assertTrue(RuntimeLogFiles.export(root, run).contains("events.jsonl"))
    }
    @Test fun boundedQueueReportsLossWithoutLosingEndState() {
        val root = temporary.newFolder()
        val writer = RuntimeLogWriter(root)
        writer.begin(emptyMap())
        repeat(5000) { writer.append("script.log", "row") }
        writer.append("script.log", "x".repeat(9000))
        writer.end("stopped")
        writer.close(); assertTrue(writer.awaitClosed(5000))
        val run = RuntimeLogFiles.list(root).single()
        assertEquals("stopped", run.metadata["status"])
        assertTrue(run.metadata.getValue("droppedRecords").toLong() > 0)
        assertTrue(File(RuntimeLogFiles.directory(root, run.id), "script.log").length() <= RuntimeLogFiles.CHANNEL_BYTES)
    }
    @Test fun pathsAndUnknownFilesAreRejected() {
        val root = temporary.newFolder()
        assertThrows(IllegalArgumentException::class.java) { RuntimeLogFiles.directory(root, "../project") }
        val writer = RuntimeLogWriter(root)
        val id = writer.begin(emptyMap())!!
        assertThrows(IllegalArgumentException::class.java) { writer.append("../source.lua", "no") }
        assertThrows(IllegalArgumentException::class.java) { RuntimeLogFiles.read(root, id, "metadata.properties/../secret") }
        writer.close(); assertTrue(writer.awaitClosed(5000))
        assertEquals("service_closed", RuntimeLogFiles.list(root).single().metadata["status"])
    }
    @Test fun oversizedChannelsStopAtQuotaAndKeepTerminalMetadata() {
        val root = temporary.newFolder()
        val writer = RuntimeLogWriter(root)
        val id = writer.begin(emptyMap())!!
        repeat(250) { writer.append("script.log", "x".repeat(8000)) }
        writer.end("ended"); writer.close(); assertTrue(writer.awaitClosed(5000))
        val run = RuntimeLogFiles.list(root).single()
        assertTrue(run.metadata.getValue("truncatedRecords").toLong() > 0)
        assertTrue(RuntimeLogFiles.read(root, id, "script.log").length <= RuntimeLogFiles.CHANNEL_BYTES)
    }

    @Test fun rotationKeepsOnlyRecentRunsAndNeverTouchesOtherFiles() {
        val root = temporary.newFolder()
        val marker = File(root, "private-project-source.lua").apply { writeText("must keep") }
        repeat(18) {
            val writer = RuntimeLogWriter(root)
            writer.begin(mapOf("device" to "fixture"))
            writer.append("script.log", "run $it")
            writer.end("ended"); writer.close(); assertTrue(writer.awaitClosed(5000))
        }
        assertEquals(16, RuntimeLogFiles.list(root).size)
        assertEquals(16, File(root, RuntimeLogFiles.DIRECTORY).listFiles()!!.size)
        assertEquals("must keep", marker.readText())
    }

    @Test fun unavailableStorageIsReportedAndNeverBlocksClose() {
        val root = temporary.newFolder()
        File(root, RuntimeLogFiles.DIRECTORY).writeText("not a directory")
        val writer = RuntimeLogWriter(root)
        writer.begin(emptyMap()); writer.append("script.log", "public fixture")
        writer.end("ended"); writer.close()
        assertTrue(writer.awaitClosed(5000))
        assertTrue(writer.failure)
        assertTrue(RuntimeLogFiles.list(root).isEmpty())
    }
}

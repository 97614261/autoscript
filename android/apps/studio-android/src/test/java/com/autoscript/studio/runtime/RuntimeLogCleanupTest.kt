package com.autoscript.studio

import com.autoscript.runtime.api.RuntimeLogFiles
import com.autoscript.runtime.api.RuntimeLogWriter
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile

class RuntimeLogCleanupTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun completed(root: File, project: String = "qa"): String {
        val writer = RuntimeLogWriter(root)
        val id = writer.begin(mapOf("projectId" to project))!!
        writer.append("script.log", "public QA output")
        writer.end("ended"); writer.close(); assertTrue(writer.awaitClosed(5000))
        return id
    }

    @Test fun exactConfirmedIdsDoNotDeleteLaterRunsOtherProjectsOrExports() {
        val root = temporary.newFolder()
        val selected = completed(root)
        val other = completed(root, "other")
        val later = completed(root)
        val export = File(root, "export.txt").apply { writeText("keep") }
        val result = RuntimeLogFiles.cleanup(root, "qa", setOf(selected, other))
        assertEquals(setOf(selected), result.deleted)
        assertEquals(setOf(other), result.protected)
        assertTrue(result.failed.isEmpty())
        assertEquals(setOf(other, later), RuntimeLogFiles.list(root).map { it.id }.toSet())
        assertEquals("keep", export.readText())
    }

    @Test fun activeAndPendingWriterAreProtectedThenCanBeDeletedAfterDrain() {
        val root = temporary.newFolder()
        val writer = RuntimeLogWriter(root)
        val id = writer.begin(mapOf("projectId" to "qa"))!!
        writer.append("script.log", "running")
        val deadline = System.nanoTime() + 5_000_000_000L
        while (RuntimeLogFiles.list(root).isEmpty() && System.nanoTime() < deadline) Thread.sleep(10)
        assertEquals(setOf(id), RuntimeLogFiles.cleanup(root, "qa", setOf(id)).protected)
        repeat(100) { writer.append("script.log", "tail $it") }
        writer.end("stopped")
        val pending = RuntimeLogFiles.cleanup(root, "qa", setOf(id))
        assertTrue(pending.failed.isEmpty())
        writer.close(); assertTrue(writer.awaitClosed(5000))
        if (id !in pending.deleted) assertEquals(setOf(id), RuntimeLogFiles.cleanup(root, "qa", setOf(id)).deleted)
        assertFalse(RuntimeLogFiles.directory(root, id).exists())
        Thread.sleep(100)
        assertFalse(RuntimeLogFiles.directory(root, id).exists())
    }

    @Test fun terminalMetadataAloneDoesNotOverrideHeldWriterLease() {
        val root = temporary.newFolder()
        val id = completed(root)
        RandomAccessFile(File(RuntimeLogFiles.directory(root, id), "writer.lock"), "rw").use { handle ->
            handle.channel.lock().use {
                val result = RuntimeLogFiles.cleanup(root, "qa", setOf(id))
                assertEquals(setOf(id), result.protected)
                assertTrue(RuntimeLogFiles.read(root, id, "script.log").contains("public QA"))
            }
        }
        assertEquals(setOf(id), RuntimeLogFiles.cleanup(root, "qa", setOf(id)).deleted)
    }

    @Test fun anotherProcessHoldingWriterLeaseIsProtectedWithoutWaitingForItsDiskWork() {
        val root = temporary.newFolder()
        val id = completed(root)
        val classpath = listOf(RuntimeLogLeaseProcess::class.java, kotlin.Unit::class.java)
            .map { File(requireNotNull(it.protectionDomain).codeSource.location.toURI()).path }.joinToString(File.pathSeparator)
        val launcher = File(requireNotNull(System.getProperty("java.home")), "bin/java${if (System.getProperty("os.name").orEmpty().startsWith("Windows")) ".exe" else ""}")
        val process = ProcessBuilder(launcher.path, "-cp", classpath, RuntimeLogLeaseProcess::class.java.name,
            File(RuntimeLogFiles.directory(root, id), "writer.lock").path).redirectErrorStream(true).start()
        try {
            val reader = process.inputStream.bufferedReader()
            val deadline = System.nanoTime() + 5_000_000_000L
            while (!reader.ready() && process.isAlive && System.nanoTime() < deadline) Thread.sleep(10)
            assertTrue("Lease helper did not start", reader.ready())
            assertEquals("READY", reader.readLine())
            val result = RuntimeLogFiles.cleanup(root, "qa", setOf(id))
            assertEquals(setOf(id), result.protected)
            assertTrue(result.failed.isEmpty())
            process.outputStream.write(10); process.outputStream.flush()
            assertTrue(process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS))
            assertEquals(0, process.exitValue())
        } finally { if (process.isAlive) process.destroyForcibly().waitFor() }
        assertEquals(setOf(id), RuntimeLogFiles.cleanup(root, "qa", setOf(id)).deleted)
    }

    @Test fun unknownFilesAndSubdirectoriesProtectEntireRecordBeforeDeletingAnything() {
        val root = temporary.newFolder()
        val id = completed(root)
        val dir = RuntimeLogFiles.directory(root, id)
        val marker = File(dir, "unknown.txt").apply { writeText("keep") }
        assertEquals(setOf(id), RuntimeLogFiles.cleanup(root, "qa", setOf(id)).protected)
        assertTrue(RuntimeLogFiles.read(root, id, "script.log").contains("public QA"))
        assertEquals("keep", marker.readText())
        assertTrue(marker.delete())
        File(dir, "script.log").delete()
        assertTrue(File(dir, "script.log").mkdir())
        assertEquals(setOf(id), RuntimeLogFiles.cleanup(root, "qa", setOf(id)).protected)
        assertTrue(File(dir, "metadata.properties").exists())
    }

    @Test fun staleSnapshotsAndExportsCannotRecreateDeletedDirectory() {
        val root = temporary.newFolder()
        val id = completed(root)
        val stale = RuntimeLogFiles.list(root).single()
        assertEquals(setOf(id), RuntimeLogFiles.cleanup(root, "qa", setOf(id)).deleted)
        assertThrows(IllegalStateException::class.java) { RuntimeLogFiles.snapshot(root, id, "script.log") }
        assertThrows(IllegalStateException::class.java) { RuntimeLogFiles.export(root, stale) }
        assertEquals("", RuntimeLogFiles.read(root, id, "script.log"))
        assertFalse(RuntimeLogFiles.directory(root, id).exists())
    }

    @Test fun legacyRunningLogsAndUnknownTerminalStatesAreNotGuessedAbandoned() {
        val root = temporary.newFolder()
        val id = completed(root)
        val meta = File(RuntimeLogFiles.directory(root, id), "metadata.properties")
        val properties = java.util.Properties().apply { meta.inputStream().use(::load) }
        listOf("running", "unknown").forEach { status ->
            properties.setProperty("status", status)
            meta.outputStream().use { properties.store(it, "fixture") }
            assertEquals(setOf(id), RuntimeLogFiles.cleanup(root, "qa", setOf(id)).protected)
        }
    }

    @Test fun cleanupRejectsEmptyProjectIdentityAndInvalidPathsWithoutTouchingValidLogs() {
        val root = temporary.newFolder()
        val id = completed(root)
        assertThrows(IllegalArgumentException::class.java) { RuntimeLogFiles.cleanup(root, "", setOf(id)) }
        assertEquals(setOf("../outside"), RuntimeLogFiles.cleanup(root, "qa", setOf("../outside")).failed)
        assertTrue(RuntimeLogFiles.directory(root, id).exists())
    }
}

object RuntimeLogLeaseProcess {
    @JvmStatic fun main(arguments: Array<String>) {
        RandomAccessFile(arguments.single(), "rw").use { handle ->
            handle.channel.lock().use {
                println("READY"); System.out.flush()
                System.`in`.read()
            }
        }
    }
}

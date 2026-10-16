package com.autoscript.runtime.api

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException

/** A separate file is important: closing a snapshot FD must not release the writer's lease. */
internal class RuntimeLogLease private constructor(
    private val handle: RandomAccessFile,
    private val lock: FileLock,
) : Closeable {
    override fun close() {
        try { lock.release() } finally { handle.close() }
    }

    companion object {
        fun acquire(dir: File): RuntimeLogLease? {
            val file = RuntimeLogFiles.checkedFile(dir, "writer.lock")
            require(file.length() == 0L)
            val handle = RandomAccessFile(file, "rw")
            try {
                val lock = try { handle.channel.tryLock() } catch (_: OverlappingFileLockException) { null }
                if (lock != null) return RuntimeLogLease(handle, lock)
                handle.close()
                return null
            } catch (failure: Exception) {
                handle.close()
                throw failure
            }
        }
    }
}

data class RuntimeLogCleanupResult(
    val deleted: Set<String>,
    val protected: Set<String>,
    val failed: Set<String>,
)

internal object RuntimeLogCleanup {
    private val segment = Regex("events\\.[0-9]{20}\\.jsonl")
    private val names = RuntimeLogFiles.channels + listOf("conditions.jsonl", "metadata.properties",
        "metadata.properties.previous", "metadata.properties.tmp", "journal.lock", "writer.lock")
    private val terminal = setOf("ended", "stopped", "failed", "start_failed", "interrupted", "service_closed")

    fun delete(files: File, projectId: String?, ids: Set<String>): RuntimeLogCleanupResult = synchronized(RuntimeLogFiles.diskLock) {
        require(ids.size <= RuntimeLogFiles.MAX_RUNS)
        val deleted = mutableSetOf<String>()
        val protected = mutableSetOf<String>()
        val failed = mutableSetOf<String>()
        ids.forEach { id ->
            try {
                val dir = RuntimeLogFiles.directory(files, id)
                if (!dir.exists()) return@forEach
                val lease = RuntimeLogLease.acquire(dir)
                if (lease == null) { protected += id; return@forEach }
                var allowed = false
                lease.use {
                    RuntimeLogFiles.withRunLock(dir, false) {
                        val run = RuntimeLogFiles.listAll(files).firstOrNull { it.id == id }
                        val children = dir.listFiles() ?: error("Cannot inspect run directory")
                        allowed = run != null && run.metadata["status"] in terminal &&
                            (projectId == null || run.metadata["projectId"] == projectId) &&
                            children.all { child ->
                                (child.name in names || segment.matches(child.name)) && child.isFile &&
                                    child.canonicalFile == File(dir.canonicalFile, child.name)
                            }
                        if (allowed) {
                            // Metadata is deleted last; never recursively traverse a run directory.
                            children.filter { it.name !in listOf("writer.lock", "journal.lock") }
                                .sortedBy { it.name.startsWith("metadata.") }.forEach { check(it.delete()) }
                        }
                    }
                }
                if (allowed) {
                    listOf("journal.lock", "writer.lock").forEach { check(RuntimeLogFiles.checkedFile(dir, it).delete()) }
                    check(dir.delete())
                    deleted += id
                } else protected += id
            } catch (_: Exception) { failed += id }
        }
        RuntimeLogCleanupResult(deleted, protected, failed)
    }
}

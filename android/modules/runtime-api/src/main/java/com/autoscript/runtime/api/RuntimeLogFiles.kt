package com.autoscript.runtime.api

import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.channels.OverlappingFileLockException
import java.util.ArrayDeque
import java.util.Properties
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Same-UID private journal contract. No JNI, script source, or external paths. */
object RuntimeLogFiles {
    const val DIRECTORY = "runtime-logs"
    const val CHANNEL_BYTES = 1_048_576L
    const val MAX_RUNS = 16
    const val EVENT_SEGMENT_BYTES = 262_144
    const val EVENT_SEGMENTS = 8
    internal val diskLock = Any()
    private val segmentPattern = Regex("events\\.[0-9]{20}\\.jsonl")
    val channels = listOf("script.log", "diagnostic.log", "events.jsonl")
    data class Run(val id: String, val metadata: Map<String, String>)
    data class Snapshot(val run: Run, val text: String, val events: String)

    /** Exact confirmed IDs only; never interpret an absent project as permission to clear all logs. */
    fun cleanup(files: File, projectId: String, ids: Set<String>): RuntimeLogCleanupResult {
        require(projectId.isNotBlank())
        return RuntimeLogCleanup.delete(files, projectId, ids)
    }

    fun directory(files: File, id: String): File {
        require(runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false))
        val root = File(files, DIRECTORY).canonicalFile
        require(root.parentFile == files.canonicalFile && root.name == DIRECTORY)
        val target = File(root, id)
        require(target.canonicalFile.parentFile == root && target.canonicalFile.name == id)
        return target
    }

    fun list(files: File): List<Run> = listAll(files).take(MAX_RUNS)

    internal fun listAll(files: File): List<Run> = synchronized(diskLock) {
        File(files, DIRECTORY).listFiles().orEmpty().mapNotNull { child ->
        runCatching {
            val dir = directory(files, child.name)
            val properties = listOf("metadata.properties", "metadata.properties.previous").firstNotNullOfOrNull { name ->
                runCatching {
                    val meta = File(dir, name)
                    require(meta.canonicalFile.parentFile == dir.canonicalFile && meta.canonicalFile.name == name && meta.length() <= 16_384)
                    Properties().apply {
                        meta.inputStream().use(::load)
                        require(getProperty("formatVersion") == "1" && getProperty("status") != null && getProperty("startedAtMs") != null)
                    }
                }.getOrNull()
            } ?: error("incomplete metadata")
            Run(child.name, properties.stringPropertyNames().associateWith { properties.getProperty(it) })
        }.getOrNull()
    }.sortedByDescending { it.metadata["startedAtMs"]?.toLongOrNull() ?: 0 }
    }

    internal fun segmentName(sequence: Long): String {
        require(sequence >= 0)
        return "events.${sequence.toString().padStart(20, '0')}.jsonl"
    }

    internal fun checkedFile(dir: File, name: String): File = File(dir, name).also {
        require(it.canonicalFile.parentFile == dir.canonicalFile && it.canonicalFile.name == name)
    }

    internal fun segmentFiles(dir: File): List<File> = dir.listFiles().orEmpty()
        .filter { segmentPattern.matches(it.name) }.sortedBy { it.name }.also { require(it.size <= EVENT_SEGMENTS) }

    internal fun <T> withRunLock(dir: File, shared: Boolean, block: () -> T): T {
        val file = checkedFile(dir, "journal.lock")
        require(file.length() == 0L)
        return RandomAccessFile(file, "rw").use { handle ->
            val started = System.nanoTime()
            var acquired: java.nio.channels.FileLock? = null
            while (acquired == null) {
                acquired = try { handle.channel.tryLock(0, 1, shared) } catch (_: OverlappingFileLockException) { null }
                if (acquired == null) {
                    check(System.nanoTime() - started < 1_000_000_000L) { "journal snapshot busy" }
                    Thread.sleep(5)
                }
            }
            try { block() } finally { acquired.release() }
        }
    }

    internal fun <T> withWriteLocks(files: File, ids: List<String>, block: () -> T): T = synchronized(diskLock) {
        val dirs = ids.distinct().sorted().map { directory(files, it) }
        dirs.forEach { check(it.mkdirs() || it.isDirectory) }
        fun acquire(index: Int): T = if (index == dirs.size) block() else withRunLock(dirs[index], false) { acquire(index + 1) }
        acquire(0)
    }

    private fun boundedRead(file: File, limit: Int): String {
        if (!file.exists()) return ""
        require(file.length() <= limit)
        return file.inputStream().use { input ->
            val bytes = ByteArray(limit + 1)
            var size = 0
            while (size < bytes.size) {
                val read = input.read(bytes, size, bytes.size - size)
                if (read < 0) break
                size += read
            }
            require(size <= limit)
            String(bytes, 0, size, Charsets.UTF_8)
        }
    }

    private fun readContent(dir: File, channel: String): String {
        require(channel in channels)
        val legacy = boundedRead(checkedFile(dir, channel), CHANNEL_BYTES.toInt())
        if (channel != "events.jsonl") return legacy
        val segments = segmentFiles(dir)
        if (segments.isEmpty()) return legacy
        return buildString {
            if (legacy.isNotEmpty()) { append(legacy); if (!legacy.endsWith('\n')) append('\n') }
            append(boundedRead(checkedFile(dir, "conditions.jsonl"), RuntimeLogEventCodec.CONDITION_BYTES))
            segments.forEach { append(boundedRead(checkedFile(dir, it.name), EVENT_SEGMENT_BYTES)) }
        }
    }

    fun read(files: File, id: String, channel: String): String = synchronized(diskLock) {
        require(channel in channels)
        val dir = directory(files, id)
        if (!dir.exists()) return@synchronized ""
        withRunLock(dir, true) { readContent(dir, channel) }
    }

    fun snapshot(files: File, id: String, channel: String): Snapshot = synchronized(diskLock) {
        val dir = directory(files, id)
        check(dir.isDirectory) { "Run log no longer exists" }
        withRunLock(dir, true) {
            val run = requireNotNull(list(files).firstOrNull { it.id == id })
            val events = readContent(dir, "events.jsonl")
            Snapshot(run, if (channel == "events.jsonl") events else readContent(dir, channel), events)
        }
    }

    fun export(files: File, run: Run): String = synchronized(diskLock) {
        val dir = directory(files, run.id)
        check(dir.isDirectory) { "Run log no longer exists" }
        withRunLock(dir, true) { buildString {
            val latest = requireNotNull(list(files).firstOrNull { it.id == run.id })
            appendLine("智构运行日志 · ${run.id}")
            latest.metadata.toSortedMap().forEach { (key, value) -> appendLine("$key=$value") }
            channels.forEach { appendLine("\n=== $it ==="); append(readContent(dir, it)) }
        } }
    }
}

/** Bounded, demand-flushed writer. A terminal state never waits behind a disk operation. */
class RuntimeLogWriter(
    private val files: File,
    private val elapsedMillis: () -> Long = { System.nanoTime() / 1_000_000 },
    private val wallMillis: () -> Long = System::currentTimeMillis,
    private val eventSegmentBytes: Int = RuntimeLogFiles.EVENT_SEGMENT_BYTES,
    private val eventSegments: Int = RuntimeLogFiles.EVENT_SEGMENTS,
) : Closeable {
    private data class Session(val id: String, val properties: MutableMap<String, String>, val startedElapsedMs: Long,
        var revision: Long = 0, var persistedRevision: Long = -1, var journal: RuntimeEventJournal? = null,
        var lease: RuntimeLogLease? = null)
    private data class Record(val session: Session, val channel: String, val text: String)
    private val executor = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "runtime-log-writer").apply { isDaemon = true }
    }
    private val lock = Any()
    private val records = ArrayDeque<Record>()
    private val sessions = linkedMapOf<String, Session>()
    private var active: Session? = null
    private var scheduled = false
    private var closed = false
    @Volatile var failure: Boolean = false
        private set
    @Volatile var failureCode: String? = null
        private set

    init {
        require(eventSegmentBytes in 16_384..RuntimeLogFiles.EVENT_SEGMENT_BYTES)
        require(eventSegments in 1..RuntimeLogFiles.EVENT_SEGMENTS)
    }

    fun begin(metadata: Map<String, String>): String? = synchronized(lock) {
        if (closed) { failure = true; return@synchronized null }
        active?.let { endLocked(it, "interrupted") }
        active = null
        if (sessions.size >= 4) { failure = true; scheduleLocked(); return@synchronized null }
        val id = UUID.randomUUID().toString()
        val properties = metadata.entries.take(24).associate { it.key.take(64) to it.value.take(256) }.toMutableMap()
        properties["formatVersion"] = "1"
        val started = elapsedMillis()
        properties["startedAtMs"] = wallMillis().toString()
        properties["durationMs"] = "0"
        properties["status"] = "running"
        properties["droppedRecords"] = "0"
        properties["truncatedRecords"] = "0"
        properties["eventFormatVersion"] = "2"
        properties["evictedEventRecords"] = "0"
        listOf("visionCaptures", "visionFound", "visionNotFound", "visionFailed", "visionCancelled", "visionEngineDropped")
            .forEach { properties[it] = "0" }
        val session = Session(id, properties, started)
        sessions[id] = session
        active = session
        scheduleLocked()
        id
    }

    fun append(channel: String, text: String) = synchronized(lock) {
        require(channel in RuntimeLogFiles.channels)
        val session = active ?: return@synchronized
        if (closed) return@synchronized
        updateDuration(session)
        val encoded = text.toByteArray(Charsets.UTF_8)
        if (records.size >= 256 || encoded.size > 8_192) {
            increment(session, "droppedRecords")
        } else records.addLast(Record(session, channel, text.trimEnd('\n') + "\n"))
        scheduleLocked()
    }

    fun end(status: String) = synchronized(lock) {
        active?.let { endLocked(it, status); active = null; scheduleLocked() }
    }

    /** Freeze the original authenticated session age; a replacement must not silently extend it. */
    fun rootSessionLost(ageMs: Long) = synchronized(lock) {
        val session = active ?: return@synchronized
        if (session.properties["rootSessionAgeAtStartMs"] == null || session.properties["rootSessionChanged"] == "true") return@synchronized
        session.properties["rootSessionChanged"] = "true"
        session.properties["rootSessionAgeMs"] = ageMs.coerceAtLeast(0).toString()
        session.revision++
        scheduleLocked()
    }

    private fun updateDuration(session: Session) {
        val duration = (elapsedMillis() - session.startedElapsedMs).coerceAtLeast(0)
            .coerceAtLeast(session.properties["durationMs"]?.toLongOrNull() ?: 0)
        session.properties["durationMs"] = duration.toString()
        if (session.properties["rootSessionChanged"] != "true") {
            session.properties["rootSessionAgeAtStartMs"]?.toLongOrNull()?.takeIf { it >= 0 }?.let { age ->
                session.properties["rootSessionAgeMs"] = (age + duration.coerceAtMost(Long.MAX_VALUE - age)).toString()
            }
        }
        session.revision++
    }

    private fun endLocked(session: Session, status: String) {
        updateDuration(session)
        session.properties["status"] = status.take(64)
        session.properties["endedAtMs"] = wallMillis().toString()
        session.revision++
    }

    private fun increment(session: Session, key: String, count: Long = 1) {
        session.properties[key] = ((session.properties[key]?.toLongOrNull() ?: 0) + count).toString()
        session.revision++
    }

    private fun scheduleLocked() {
        if (scheduled || closed) return
        scheduled = true
        executor.schedule(::flush, 50, TimeUnit.MILLISECONDS)
    }

    private fun flush() {
        val batch: List<Record>
        val runs: List<Session>
        synchronized(lock) {
            batch = records.toList(); records.clear()
            runs = sessions.values.toList()
        }
        val unwritten = batch.groupingBy { it.session.id }.eachCount().toMutableMap()
        var failureStage = "LOCK"
        try { RuntimeLogFiles.withWriteLocks(files, runs.map { it.id }) {
            failureStage = "LEASE"
            runs.forEach { session ->
                if (session.lease == null) session.lease = checkNotNull(RuntimeLogLease.acquire(RuntimeLogFiles.directory(files, session.id)))
            }
            // One buffered open per channel/batch, not one filesystem operation per script line.
            batch.groupBy { it.session.id to it.channel }.forEach channelGroup@{ (target, group) ->
                val dir = RuntimeLogFiles.directory(files, target.first)
                if (target.second == "events.jsonl") {
                    failureStage = "EVENT_JOURNAL"
                    val session = group.first().session
                    group.forEach { record ->
                        RuntimeLogEventCodec.summary(record.text)?.let { (key, count) ->
                            synchronized(lock) { increment(session, key, count) }
                        }
                    }
                    val journal = session.journal ?: RuntimeEventJournal(dir, eventSegmentBytes, eventSegments).also { session.journal = it }
                    val evicted = journal.write(group.map { it.text })
                    if (evicted > 0) synchronized(lock) { increment(session, "evictedEventRecords", evicted) }
                    unwritten[target.first] = unwritten.getValue(target.first) - group.size
                    return@channelGroup
                }
                val output = File(dir, target.second)
                failureStage = "TEXT_CHANNEL"
                require(output.canonicalFile.parentFile == dir.canonicalFile && output.canonicalFile.name == target.second)
                var length = output.length()
                var quotaSkipped = 0
                FileOutputStream(output, true).buffered().use { stream ->
                    group.forEach { record ->
                        val bytes = record.text.toByteArray(Charsets.UTF_8)
                        if (length + bytes.size <= RuntimeLogFiles.CHANNEL_BYTES) {
                            stream.write(bytes); length += bytes.size
                        } else quotaSkipped++
                    }
                }
                if (quotaSkipped > 0) synchronized(lock) { increment(group.first().session, "truncatedRecords", quotaSkipped.toLong()) }
                unwritten[target.first] = unwritten.getValue(target.first) - group.size
            }
            runs.forEach { session ->
                failureStage = "METADATA"
                val snapshot = synchronized(lock) { session.properties.toMap() to session.revision }
                val dir = RuntimeLogFiles.directory(files, session.id)
                val properties = Properties().apply { putAll(snapshot.first) }
                saveMetadata(dir, properties)
                synchronized(lock) {
                    session.persistedRevision = snapshot.second
                    if (active !== session && session.revision == snapshot.second && records.none { it.session === session }) {
                        sessions.remove(session.id)
                        session.lease?.close(); session.lease = null
                    }
                }
            }
        }
            val root = File(files, RuntimeLogFiles.DIRECTORY).canonicalFile
            root.listFiles().orEmpty().filter { candidate ->
                runCatching { RuntimeLogFiles.directory(files, candidate.name) }.isSuccess
            }.sortedByDescending { File(it, "metadata.properties").lastModified() }.drop(RuntimeLogFiles.MAX_RUNS).forEach { dir ->
                if (synchronized(lock) { dir.name !in sessions }) RuntimeLogCleanup.delete(files, null, setOf(dir.name))
            }
        } catch (error: Exception) {
            failure = true
            // Fixed structural codes only: never persist exception messages containing script data.
            val category = when (error) {
                is java.io.IOException -> "IO"
                is SecurityException -> "SECURITY"
                is IllegalArgumentException -> "INVALID"
                is IllegalStateException -> "STATE"
                else -> "OTHER"
            }
            failureCode = "JOURNAL_${failureStage}_$category"
            synchronized(lock) {
                runs.forEach {
                    increment(it, "droppedRecords", unwritten[it.id]?.toLong() ?: 0)
                    it.properties["writerError"] = "true"
                    it.properties["writerErrorCode"] = requireNotNull(failureCode)
                    it.persistedRevision = it.revision
                }
            }
            // A failed event channel must not hide its failure or terminal state when metadata is still writable.
            synchronized(RuntimeLogFiles.diskLock) {
                runs.forEach { session ->
                    val snapshot = synchronized(lock) { session.properties.toMap() }
                    runCatching {
                        val dir = RuntimeLogFiles.directory(files, session.id)
                        RuntimeLogFiles.withRunLock(dir, false) { saveMetadata(dir, Properties().apply { putAll(snapshot) }) }
                    }
                }
            }
            synchronized(lock) {
                runs.filter { it !== active && records.none { record -> record.session === it } }.forEach {
                    sessions.remove(it.id)
                    it.lease?.close(); it.lease = null
                }
            }
        } finally {
            synchronized(lock) {
                scheduled = false
                if (records.isNotEmpty() || sessions.values.any { it.revision != it.persistedRevision }) scheduleLocked()
            }
        }
    }

    private fun saveMetadata(dir: File, properties: Properties) {
        val meta = File(dir, "metadata.properties")
        val temporary = File(dir, "metadata.properties.tmp")
        val previous = File(dir, "metadata.properties.previous")
        listOf(meta, temporary, previous).forEach {
            require(it.canonicalFile.parentFile == dir.canonicalFile && it.canonicalFile.name == it.name)
        }
        temporary.outputStream().use { properties.store(it, "private runtime journal") }
        if (meta.exists()) {
            check(!previous.exists() || previous.delete())
            check(meta.renameTo(previous))
        }
        if (!temporary.renameTo(meta)) {
            if (previous.exists()) previous.renameTo(meta)
            error("metadata commit failed")
        }
        if (previous.exists()) previous.delete()
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            active?.let { endLocked(it, "service_closed") }; active = null
            closed = true
        }
        executor.execute(::flush)
        executor.shutdown()
    }

    /** Optional bounded off-main-thread shutdown join; Stop and Service.close never call this. */
    fun awaitClosed(timeoutMs: Long): Boolean = executor.awaitTermination(timeoutMs.coerceIn(1, 5_000), TimeUnit.MILLISECONDS)
}

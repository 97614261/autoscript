package com.autoscript.runtime.service

import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Fixed callback from the host worker. Project identity is configured by the service, never Lua. */
internal class NativeDataFileAdapter(
    filesDirectory: File,
    private val ops: ProjectDataFileOps = AndroidProjectDataFileOps,
    private val nanoTime: () -> Long = System::nanoTime,
    private val timeoutMillis: Long = 2_000,
) {
    private val base = File(filesDirectory.canonicalFile, "script-data")
    private val lifecycle = Any()
    private var epoch = 0L
    private var closed = false
    private var store: ProjectDataStore? = null
    private data class Pending(val work: Long, val epoch: Long, val deadline: Long, val store: ProjectDataStore, val cancelled: AtomicBoolean = AtomicBoolean())
    private val active = AtomicReference<Pending?>()
    private val cancelledThrough = AtomicLong()

    init { require(timeoutMillis in 1..3_000) }

    fun configure(projectId: String, enabled: Boolean): Boolean = synchronized(lifecycle) {
        clearLocked()
        if (closed) return@synchronized false
        if (!enabled) return@synchronized true
        if (!Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}").matches(projectId) || projectId in setOf(".", "..")) return@synchronized false
        try {
            val directory = File(base, projectId)
            if (directory.parentFile != base || directory.canonicalFile != directory.absoluteFile) return@synchronized false
            store = ProjectDataStore(directory, ops)
            true
        } catch (_: Exception) { false }
    }

    private fun clearLocked() { epoch++; active.get()?.cancelled?.set(true); store = null }
    fun clear() { synchronized(lifecycle) { clearLocked() } }
    fun close() { synchronized(lifecycle) { clearLocked(); closed = true } }
    fun openAudio(path: String): java.io.FileInputStream = synchronized(lifecycle) {
        if (closed) throw ProjectDataFailure("FILE_SESSION_STALE")
        (store ?: throw ProjectDataFailure("FILE_ADAPTER_UNAVAILABLE")).openAudio(path)
    }

    @Suppress("unused") // Exact JNI callback descriptor; no arbitrary path root or member access.
    fun dispatch(work: Long, operation: Int, path: String, content: ByteArray): ByteArray {
val extended=operation in 14110..14126 || operation in 14140..14147
        val png = operation == 14130
        if (work <= 0 || (!extended && !png && operation !in 14100..14103) || path.toByteArray(Charsets.UTF_8).size > 256 ||
            content.size > (if (png) ProjectDataStore.MAX_PNG_BYTES else if(extended) ProjectDataCommands.MAX_WIRE_BYTES else ProjectDataStore.MAX_FILE_BYTES) ||
            (!extended && !png && operation != 14101 && content.isNotEmpty())) return failure("FILE_REQUEST_INVALID")
        val pending = synchronized(lifecycle) {
            val current = store ?: return failure("FILE_ADAPTER_UNAVAILABLE")
            if (closed) return failure("FILE_SESSION_STALE")
            Pending(work, epoch, nanoTime() + timeoutMillis * 1_000_000, current)
        }
        if (!active.compareAndSet(null, pending)) return failure("FILE_QUEUE_FULL")
        fun check() {
            if (pending.cancelled.get() || work <= cancelledThrough.get()) throw ProjectDataFailure("FILE_CANCELLED")
            if (nanoTime() - pending.deadline >= 0) throw ProjectDataFailure("FILE_TIMEOUT")
            synchronized(lifecycle) { if (closed || epoch != pending.epoch || store !== pending.store) throw ProjectDataFailure("FILE_SESSION_STALE") }
        }
        val commit: (() -> Unit) -> Unit = { action -> synchronized(lifecycle) { check(); action() } }
        return try {
            check()
            val result = when (operation) {
                14100 -> pending.store.read(path, ::check)
                14101 -> { pending.store.write(path, content, ::check, commit); byteArrayOf() }
                14102 -> pending.store.list(path, ::check)
                14103 -> { pending.store.remove(path, ::check, commit); byteArrayOf() }
                14130 -> { pending.store.writePng(path, content, ::check, commit); byteArrayOf() }
                else -> ProjectDataCommands(pending.store,::check,commit).execute(operation,path,content)
            }
            check()
            byteArrayOf(0) + result
        } catch (error: ProjectDataFailure) { failure(error.code) }
        catch (_: Exception) { failure("FILE_UNAVAILABLE") }
        finally { active.compareAndSet(pending, null) }
    }

    @Suppress("unused") // Can arrive before dispatch registers work.
    fun cancel(work: Long) {
        cancelledThrough.updateAndGet { previous -> maxOf(previous, work) }
        active.get()?.takeIf { it.work == work }?.cancelled?.set(true)
    }

    companion object {
        private fun failure(code: String) = byteArrayOf(1) + code.toByteArray(Charsets.US_ASCII)
    }
}

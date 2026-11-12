package com.autoscript.runtime.api

import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.channels.OverlappingFileLockException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Small process-shared journal; JVM crashes are saved before delegating to Android's handler. */
object RuntimeSystemLog {
    private const val LIMIT = 262_144
    private val location = AtomicReference("")
    private val monitor = Any()
    private val dropped = AtomicLong()
    private val worker = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(128),
        { task -> Thread(task, "system-log-writer").apply { isDaemon = true } },
        java.util.concurrent.RejectedExecutionHandler { _, _ -> dropped.incrementAndGet() })
    private val failureWords = Regex("失败|错误|异常|超时|无法|已过期|不匹配|未通过|未能|权限不足|拒绝|未确认|未完成")

    /** UI and connection events enqueue once; never block an editor action on fsync. */
    fun notice(files: File, source: String, message: String, failure: Boolean = failureWords.containsMatchIn(message)) {
        if (message.isBlank()) return
        enqueue(files, if (failure) "ERROR" else "INFO", "${source.take(40)} · ${message.take(4096)}")
    }

    fun enqueue(files: File, level: String, message: String, wallMs: Long = System.currentTimeMillis()) {
        val frozen = message.take(16_384)
        worker.execute {
            val lost = dropped.getAndSet(0)
            if (lost > 0 && !record(files, "INFO", "系统日志繁忙：有 $lost 条记录未保存", wallMs)) dropped.addAndGet(lost)
            if (!record(files, level, frozen, wallMs)) dropped.incrementAndGet()
        }
    }

    fun flush(timeoutMs: Long = 5000): Boolean {
        val barrier = java.util.concurrent.CountDownLatch(1)
        worker.execute { barrier.countDown() }
        return barrier.await(timeoutMs, TimeUnit.MILLISECONDS)
    }

    fun location(text: String) { location.set(text.take(512)) }

    fun install(files: File, process: String) {
        val previous = Thread.getDefaultUncaughtExceptionHandler() ?: return
        Thread.setDefaultUncaughtExceptionHandler { thread, failure ->
            try {
                val stack = generateSequence(failure) { it.cause }.take(6).joinToString("\n原因：") { error ->
                    error.toString().take(2048) + "\n" + error.stackTrace.take(32).joinToString("\n") { "    $it" }
                }
                record(files, "ERROR", "应用闪退 · $process · 线程 ${thread.name}\n最后记录位置：${location.get().ifBlank { "无可用执行位置" }}\n$stack")
            } finally { previous.uncaughtException(thread, failure) }
        }
    }

    fun record(files: File, level: String, message: String, wallMs: Long = System.currentTimeMillis()): Boolean =
        runCatching {
            withLock(files) { root ->
                val current = checked(root, "system.log")
                val stamp = SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(Date(wallMs))
                val entry = "[$stamp] 系统/${if (level == "ERROR") "ERROR" else "INFO"}: ${message.take(16_384).replace('\u0000', ' ')}\n"
                val bytes = entry.toByteArray(Charsets.UTF_8)
                if (current.length() + bytes.size > LIMIT) {
                    val previous = checked(root, "system.previous.log")
                    check(!previous.exists() || previous.delete())
                    if (current.exists()) check(current.renameTo(previous))
                }
                FileOutputStream(current, true).use { output -> output.write(bytes); output.fd.sync() }
            }
            true
        }.getOrDefault(false)

    fun read(files: File): List<String> = runCatching {
        withLock(files) { root ->
            listOf("system.previous.log", "system.log").flatMap { name ->
                val file = checked(root, name)
                if (!file.exists()) emptyList() else {
                    require(file.length() <= LIMIT)
                    file.readText(Charsets.UTF_8).split(Regex("(?m)(?=^\\[[0-9]{2}:[0-9]{2}:[0-9]{2}] 系统/)")).filter(String::isNotBlank)
                }
            }.takeLast(200).map(String::trimEnd)
        }
    }.getOrDefault(emptyList())

    private fun checked(root: File, name: String): File = File(root, name).also {
        require(it.canonicalFile == it.absoluteFile && it.canonicalFile.parentFile == root.canonicalFile)
    }

    private fun <T> withLock(files: File, block: (File) -> T): T = synchronized(monitor) {
        val root = File(files.canonicalFile, "system-logs")
        require(root.canonicalFile == root.absoluteFile)
        check(root.mkdirs() || root.isDirectory)
        RandomAccessFile(checked(root, "journal.lock"), "rw").use { handle ->
            val started = System.nanoTime()
            var lease: java.nio.channels.FileLock? = null
            while (lease == null) {
                lease = try { handle.channel.tryLock() } catch (_: OverlappingFileLockException) { null }
                if (lease == null) {
                    check(System.nanoTime() - started < 250_000_000L) { "System log busy" }
                    Thread.sleep(5)
                }
            }
            try { block(root) } finally { lease.release() }
        }
    }
}

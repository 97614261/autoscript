package com.autoscript.runtime.service

import java.io.File
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** App/tool failures are useful even when no script run (and thus no run log) exists. */
internal enum class AppDiagnosticCode {
    ROOT_START, ROOT_INSTALLED_DAEMON, ROOT_EXTRACTED_DAEMON, ROOT_DIRECTORY_FAILED,
    ROOT_DAEMON_MISSING, ROOT_KEY_FAILED, ROOT_SU_START_FAILED, ROOT_START_TIMEOUT,
    ROOT_ATTACH_FAILED, ROOT_READY, ROOT_DISCONNECTED, ROOT_RETRIES_EXHAUSTED,
    ROOT_PROCESS_EXITED, ROOT_PROCESS_PERMISSION_DENIED, ROOT_PROCESS_LINK_FAILED,
    ROOT_PROCESS_COMMAND_MISSING,
    CAPTURE_REQUESTED, CAPTURE_SESSION_CHANGED, CAPTURE_ROOT_NOT_READY,
    CAPTURE_RUNTIME_BUSY, CAPTURE_NATIVE_UNAVAILABLE, CAPTURE_BACKEND_FAILED,
    CAPTURE_INVALID_FRAME, CAPTURE_READY,
    CAPTURE_DEADLINE, CAPTURE_PROCESS_START, CAPTURE_PIPE_READ, CAPTURE_PROCESS_EXIT,
    CAPTURE_ID_EXHAUSTED, CAPTURE_CONTROL_UNAVAILABLE, CAPTURE_TRANSPORT, CAPTURE_PROTOCOL,
    CAPTURE_CORRELATION, CAPTURE_CANCELLED, CAPTURE_BACKEND_UNAVAILABLE, CAPTURE_BUDGET,
    CAPTURE_CONNECTION_CLOSED, CAPTURE_QUEUE_BUSY, CAPTURE_REQUEST_FAILED,
}

internal fun capturePreviewFailureCode(error:Throwable?):AppDiagnosticCode =
    if(error is IllegalStateException) AppDiagnosticCode.entries.firstOrNull {
        it.name.startsWith("CAPTURE_") && it !in setOf(AppDiagnosticCode.CAPTURE_READY,AppDiagnosticCode.CAPTURE_REQUESTED) && it.name==error.message
    } ?: AppDiagnosticCode.CAPTURE_REQUEST_FAILED else AppDiagnosticCode.CAPTURE_BACKEND_FAILED

internal fun rootProcessFailureCode(output: String): AppDiagnosticCode? = when {
    output.contains("permission denied", true) || output.contains("access denied", true) ->
        AppDiagnosticCode.ROOT_PROCESS_PERMISSION_DENIED
    output.contains("cannot link executable", true) || output.contains("linker:", true) ->
        AppDiagnosticCode.ROOT_PROCESS_LINK_FAILED
    output.contains("not found", true) || output.contains("no such file", true) ->
        AppDiagnosticCode.ROOT_PROCESS_COMMAND_MISSING
    else -> null
}

internal class RuntimeAppDiagnostics(
    private val directory: File,
    private val log: (String) -> Unit = {},
) : AutoCloseable {
    private val worker = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(64), { task -> Thread(task, "zhigou-app-diagnostics").apply { isDaemon = true } },
        java.util.concurrent.RejectedExecutionHandler { _, _ -> incomplete = true })
    @Volatile var incomplete = false
        private set
    private val entries = ArrayDeque<String>()
    private var loaded = false

    fun record(code: AppDiagnosticCode, detail: Int? = null) {
        // Only enums and integers cross the persistence/logcat boundary, never raw exceptions.
        val line = "${System.currentTimeMillis()} · App诊断 · ${code.name}${detail?.let { " [$it]" }.orEmpty()}"
        synchronized(entries) {
            if (entries.size >= 100) entries.removeFirst()
            entries.addLast(line)
        }
        log(line)
        worker.execute { runCatching {
            check(directory.isDirectory || directory.mkdirs())
            val current = File(directory, "app.log")
            if (current.length() + line.toByteArray(Charsets.UTF_8).size + 1 > 64 * 1024) {
                val previous = File(directory, "app.previous.log")
                check(!previous.exists() || previous.delete())
                check(current.renameTo(previous))
            }
            current.appendText("$line\n", Charsets.UTF_8)
        }.onFailure { incomplete = true } }
    }

    fun recent(): List<String> = synchronized(entries) { entries.toList() }

    /** Called on the service worker once, never scan files on a Binder status query. */
    fun loadPrevious() {
        synchronized(entries) { if (loaded) return; loaded = true }
        worker.execute {
            val previous = runCatching {
                listOf("app.previous.log", "app.log").flatMap { name ->
                    val file = File(directory, name)
                    if (!file.isFile || file.length() > 64 * 1024) emptyList() else
                        file.readLines(Charsets.UTF_8).filter { line ->
                            val match = Regex("[0-9]+ · App诊断 · ([A-Z_]+)(?: \\[-?[0-9]+])?").matchEntire(line)
                            line.length <= 160 && match != null &&
                                AppDiagnosticCode.entries.any { it.name == match.groupValues[1] }
                        }
                }.takeLast(50)
            }.getOrDefault(emptyList())
            synchronized(entries) {
                val live = entries.toList()
                entries.clear()
                entries.addAll((previous + live).takeLast(100))
            }
        }
    }

    override fun close() { worker.shutdown() }
    internal fun awaitClosed(): Boolean = worker.awaitTermination(5, TimeUnit.SECONDS)
}

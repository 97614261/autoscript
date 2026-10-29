package com.autoscript.runtime.service

import android.content.Context
import android.os.Build
import android.os.FileObserver
import android.os.Handler
import android.os.Process as AndroidProcess
import android.system.Os
import com.autoscript.engine.jni.NativeEngineBridge
import java.io.File
import java.io.FileOutputStream
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipFile

internal class RootDaemonController(
    private val context: Context,
    private val handler: Handler,
    private val onStateChanged: (RootConnectionState) -> Unit,
    private val diagnostic: (AppDiagnosticCode, Int?) -> Unit = { _, _ -> },
) {
    private val random = SecureRandom()
    private val stopping = AtomicBoolean(false)
    private var observer: FileObserver? = null
    private var daemonProcess: Process? = null
    private var socketFile: File? = null
    private var keyFile: File? = null
    private var readyFile: File? = null
    private var extractedDaemonFile: File? = null
    private var sessionKey: ByteArray? = null
    private var nativeHandle: Long = 0L
    private var nativeAttached = false
    @Volatile private var nativeAttachTicket: Long? = null
    private var startupTimeout: Runnable? = null
    private val connection = RootConnectionSession(::startAttempt, ::releaseAttempt, onStateChanged)

    fun start(handle: Long) {
        check(nativeHandle == 0L) { "RootDaemonController already started" }
        stopping.set(false)
        nativeHandle = handle
    }

    /** Initial startup or user demand only; never called by a status/disconnect callback. */
    fun requestConnection() {
        if (!stopping.get() && nativeHandle != 0L) connection.request()
    }

    /** Called on the engine Handler after native I/O detects a broken authenticated channel. */
    fun disconnectTicket(): Long? = connection.readyTicket() ?: nativeAttachTicket

    fun onNativeDisconnected(ticket: Long) {
        if (!stopping.get() && nativeHandle != 0L && connection.active(ticket)) {
            diagnostic(AppDiagnosticCode.ROOT_DISCONNECTED, null)
            connection.end(ticket, failed = false)
        }
    }

    fun stop() {
        if (nativeHandle == 0L) return
        stopping.set(true)
        connection.stop()
        nativeHandle = 0L
    }

    private fun releaseAttempt() {
        startupTimeout?.let(handler::removeCallbacks)
        startupTimeout = null
        stopObserver()
        if (nativeAttached) {
            nativeAttached = false
            NativeEngineBridge.nativeDetachRoot(nativeHandle)
        }
        clearSecretAndFiles()
        val process = daemonProcess
        daemonProcess = null
        process?.destroy()
    }

    private fun startAttempt(ticket: Long) {
        if (stopping.get() || nativeHandle == 0L || !connection.starting(ticket)) return
        diagnostic(AppDiagnosticCode.ROOT_START, null)

        val runtimeDirectory = File(context.noBackupFilesDir, RUNTIME_DIRECTORY)
        if ((!runtimeDirectory.exists() && !runtimeDirectory.mkdirs()) || !runtimeDirectory.isDirectory) {
            diagnostic(AppDiagnosticCode.ROOT_DIRECTORY_FAILED, null)
            connection.end(ticket, failed = true)
            return
        }
        runCatching { Os.chmod(runtimeDirectory.absolutePath, DIRECTORY_MODE) }
            .getOrElse {
                diagnostic(AppDiagnosticCode.ROOT_DIRECTORY_FAILED, null)
                connection.end(ticket, failed = true)
                return
            }

        val token = randomToken()
        val socket = File(runtimeDirectory, "daemon-$token.sock")
        val key = File(runtimeDirectory, "key-$token.bin")
        val ready = File(runtimeDirectory, "ready-$token")
        val secret = newSessionKey()
        val daemon = resolveDaemon(runtimeDirectory, token)
        if (daemon == null) {
            diagnostic(AppDiagnosticCode.ROOT_DAEMON_MISSING, null)
            secret.fill(0)
            connection.end(ticket, failed = true)
            return
        }

        runCatching {
            FileOutputStream(key).use { stream ->
                stream.write(secret)
                stream.fd.sync()
            }
            Os.chmod(key.absolutePath, KEY_MODE)
        }.getOrElse {
            diagnostic(AppDiagnosticCode.ROOT_KEY_FAILED, null)
            secret.fill(0)
            key.delete()
            connection.end(ticket, failed = true)
            return
        }

        socketFile = socket
        keyFile = key
        readyFile = ready
        sessionKey = secret
        watchForReady(runtimeDirectory, ready.name, ticket)
        startupTimeout = Runnable {
            if (connection.starting(ticket)) {
                diagnostic(AppDiagnosticCode.ROOT_START_TIMEOUT, null)
                connection.end(ticket, failed = true)
            }
        }.also { handler.postDelayed(it, STARTUP_TIMEOUT_MILLIS) }

        val process = runCatching {
            val command = listOf(
                daemon.absolutePath,
                socket.absolutePath,
                AndroidProcess.myUid().toString(),
                key.absolutePath,
                ready.absolutePath,
                IDLE_TIMEOUT_MILLIS.toString(),
                context.applicationInfo.sourceDir,
            ).joinToString(separator = " ", transform = ::shellQuote)
            ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
        }.getOrElse {
            diagnostic(AppDiagnosticCode.ROOT_SU_START_FAILED, null)
            connection.end(ticket, failed = true)
            return
        }
        daemonProcess = process
        consumeProcessOutput(process, ticket)
    }

    @Suppress("DEPRECATION")
    private fun watchForReady(directory: File, readyName: String, ticket: Long) {
        observer = object : FileObserver(directory.absolutePath, CREATE or MOVED_TO) {
            override fun onEvent(event: Int, path: String?) {
                if (path == readyName) handler.post { attachNativeClient(ticket) }
            }
        }.also(FileObserver::startWatching)
    }

    private fun attachNativeClient(ticket: Long) {
        if (stopping.get() || nativeAttached || !connection.starting(ticket)) return
        val handle = nativeHandle
        val socket = socketFile
        val secret = sessionKey
        if (handle == 0L || socket == null || secret == null) {
            connection.end(ticket, failed = true)
            return
        }
        stopObserver()
        startupTimeout?.let(handler::removeCallbacks)
        startupTimeout = null
        nativeAttachTicket = ticket
        val result = try {
            runCatching {
                NativeEngineBridge.nativeAttachRoot(handle, socket.absolutePath, secret, REQUEST_TIMEOUT_MILLIS)
            }.getOrDefault(-1)
        } finally {
            nativeAttachTicket = null
        }
        secret.fill(0)
        sessionKey = null
        keyFile?.delete()
        keyFile = null
        readyFile?.delete()
        readyFile = null
        if (result == 0) {
            diagnostic(AppDiagnosticCode.ROOT_READY, null)
            nativeAttached = true
            connection.ready(ticket)
        } else {
            diagnostic(AppDiagnosticCode.ROOT_ATTACH_FAILED, result)
            connection.end(ticket, failed = true)
        }
    }

    private fun stopObserver() {
        observer?.stopWatching()
        observer = null
    }

    private fun clearSecretAndFiles() {
        sessionKey?.fill(0)
        sessionKey = null
        keyFile?.delete()
        keyFile = null
        readyFile?.delete()
        readyFile = null
        socketFile?.delete()
        socketFile = null
        extractedDaemonFile?.delete()
        extractedDaemonFile = null
    }

    private fun resolveDaemon(runtimeDirectory: File, token: String): File? {
        val installed = File(context.applicationInfo.nativeLibraryDir, DAEMON_LIBRARY)
        if (installed.isFile && installed.canExecute()) {
            diagnostic(AppDiagnosticCode.ROOT_INSTALLED_DAEMON, null)
            return installed
        }

        val target = File(runtimeDirectory, "daemon-$token")
        val temporary = File(runtimeDirectory, "daemon-$token.tmp")
        val apkPaths = buildList {
            add(context.applicationInfo.sourceDir)
            context.applicationInfo.splitSourceDirs?.let(::addAll)
        }
        return runCatching {
            var extracted = false
            for (apkPath in apkPaths) {
                ZipFile(apkPath).use { archive ->
                    val entry = Build.SUPPORTED_ABIS.asSequence()
                        .map { abi -> archive.getEntry("lib/$abi/$DAEMON_LIBRARY") }
                        .firstOrNull { it != null }
                    if (entry != null) {
                        require(!entry.isDirectory && entry.size <= MAX_DAEMON_BYTES) {
                            "Root daemon APK entry is invalid"
                        }
                        archive.getInputStream(entry).use { input ->
                            FileOutputStream(temporary).use { output ->
                                val buffer = ByteArray(DAEMON_COPY_BUFFER_BYTES)
                                var total = 0L
                                while (true) {
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    total = Math.addExact(total, count.toLong())
                                    require(total <= MAX_DAEMON_BYTES) {
                                        "Root daemon exceeds the extraction limit"
                                    }
                                    output.write(buffer, 0, count)
                                }
                                require(total > 0L) { "Root daemon is empty" }
                                output.fd.sync()
                            }
                        }
                        extracted = true
                    }
                }
                if (extracted) break
            }
            require(extracted) { "Root daemon is missing for the process ABI" }
            Os.chmod(temporary.absolutePath, DAEMON_MODE)
            require(temporary.renameTo(target)) { "Unable to commit the Root daemon" }
            require(target.isFile && target.canExecute()) { "Root daemon is not executable" }
            extractedDaemonFile = target
            diagnostic(AppDiagnosticCode.ROOT_EXTRACTED_DAEMON, null)
            target
        }.getOrElse {
            temporary.delete()
            target.delete()
            null
        }
    }

    private fun consumeProcessOutput(process: Process, ticket: Long) {
        Thread({
            var category: AppDiagnosticCode? = null
            runCatching {
                process.inputStream.use { input ->
                    val buffer = ByteArray(PROCESS_OUTPUT_BUFFER_BYTES)
                    var tail = ""
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        tail = (tail + String(buffer, 0, count, Charsets.UTF_8)).takeLast(2048)
                        category = category ?: rootProcessFailureCode(tail)
                    }
                }
            }
            val exit = runCatching { process.waitFor() }.getOrNull()
            handler.post {
                if (!stopping.get() && daemonProcess === process) {
                    val idle = category == AppDiagnosticCode.ROOT_IDLE_RELEASED
                    diagnostic(category ?: AppDiagnosticCode.ROOT_PROCESS_EXITED, exit)
                    connection.end(ticket, failed = !idle)
                }
            }
        }, "autoscript-root-daemon-watch").apply {
            isDaemon = true
            start()
        }
    }

    private fun newSessionKey(): ByteArray {
        var key: ByteArray
        do {
            key = ByteArray(SESSION_KEY_BYTES).also(random::nextBytes)
        } while (key.all { it == 0.toByte() })
        return key
    }

    private fun randomToken(): String = ByteArray(TOKEN_BYTES)
        .also(random::nextBytes)
        .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private fun shellQuote(argument: String): String =
        "'${argument.replace("'", "'\\''")}'"

    private companion object {
        const val RUNTIME_DIRECTORY = "root-runtime"
        const val DAEMON_LIBRARY = "libautoscript_root_daemon.so"
        const val DIRECTORY_MODE = 448 // 0700
        const val DAEMON_MODE = 448 // 0700
        const val KEY_MODE = 384 // 0600
        const val MAX_DAEMON_BYTES = 64L * 1024 * 1024
        const val DAEMON_COPY_BUFFER_BYTES = 64 * 1024
        const val SESSION_KEY_BYTES = 32
        const val TOKEN_BYTES = 16
        const val REQUEST_TIMEOUT_MILLIS = 7_000
        const val STARTUP_TIMEOUT_MILLIS = 60_000L
        const val IDLE_TIMEOUT_MILLIS = 300_000L
        const val PROCESS_OUTPUT_BUFFER_BYTES = 1_024
    }
}

package com.autoscript.runtime.service

import android.content.Context
import android.os.FileObserver
import android.os.Handler
import android.os.Process as AndroidProcess
import android.system.Os
import com.autoscript.engine.jni.NativeEngineBridge
import java.io.File
import java.io.FileOutputStream
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean

internal class RootDaemonController(
    private val context: Context,
    private val handler: Handler,
    private val onStateChanged: (State) -> Unit,
) {
    enum class State {
        STOPPED,
        STARTING,
        READY,
        FAILED,
    }

    private val random = SecureRandom()
    private val stopping = AtomicBoolean(false)
    private var observer: FileObserver? = null
    private var daemonProcess: Process? = null
    private var socketFile: File? = null
    private var keyFile: File? = null
    private var readyFile: File? = null
    private var sessionKey: ByteArray? = null
    private var nativeHandle: Long = 0L
    private var nativeAttached = false
    private var restartAttempts = 0
    private var restartScheduled = false

    private val startupTimeout = Runnable {
        if (!stopping.get() && nativeHandle != 0L && observer != null) {
            recoverFromFailure()
        }
    }

    private val restart = Runnable {
        restartScheduled = false
        if (!stopping.get() && nativeHandle != 0L) startAttempt()
    }

    fun start(handle: Long) {
        check(nativeHandle == 0L) { "RootDaemonController already started" }
        stopping.set(false)
        nativeHandle = handle
        restartAttempts = 0
        startAttempt()
    }

    /** Called on the engine Handler after native I/O detects a broken authenticated channel. */
    fun onNativeDisconnected() {
        if (!stopping.get() && nativeHandle != 0L) recoverFromFailure()
    }

    fun stop() {
        if (nativeHandle == 0L) return
        stopping.set(true)
        handler.removeCallbacks(startupTimeout)
        handler.removeCallbacks(restart)
        restartScheduled = false
        stopObserver()
        val handle = nativeHandle
        nativeHandle = 0L
        if (nativeAttached) {
            NativeEngineBridge.nativeDetachRoot(handle)
            nativeAttached = false
        }
        clearSecretAndFiles()
        val process = daemonProcess
        daemonProcess = null
        process?.destroy()
        onStateChanged(State.STOPPED)
    }

    private fun startAttempt() {
        if (stopping.get() || nativeHandle == 0L) return
        onStateChanged(State.STARTING)

        val runtimeDirectory = File(context.noBackupFilesDir, RUNTIME_DIRECTORY)
        if ((!runtimeDirectory.exists() && !runtimeDirectory.mkdirs()) || !runtimeDirectory.isDirectory) {
            recoverFromFailure()
            return
        }
        runCatching { Os.chmod(runtimeDirectory.absolutePath, DIRECTORY_MODE) }
            .getOrElse {
                recoverFromFailure()
                return
            }

        val token = randomToken()
        val socket = File(runtimeDirectory, "daemon-$token.sock")
        val key = File(runtimeDirectory, "key-$token.bin")
        val ready = File(runtimeDirectory, "ready-$token")
        val secret = newSessionKey()
        val daemon = File(context.applicationInfo.nativeLibraryDir, DAEMON_LIBRARY)
        if (!daemon.isFile || !daemon.canExecute()) {
            secret.fill(0)
            recoverFromFailure()
            return
        }

        runCatching {
            FileOutputStream(key).use { stream ->
                stream.write(secret)
                stream.fd.sync()
            }
            Os.chmod(key.absolutePath, KEY_MODE)
        }.getOrElse {
            secret.fill(0)
            key.delete()
            recoverFromFailure()
            return
        }

        socketFile = socket
        keyFile = key
        readyFile = ready
        sessionKey = secret
        watchForReady(runtimeDirectory, ready.name)
        handler.postDelayed(startupTimeout, STARTUP_TIMEOUT_MILLIS)

        val process = runCatching {
            val command = listOf(
                daemon.absolutePath,
                socket.absolutePath,
                AndroidProcess.myUid().toString(),
                key.absolutePath,
                ready.absolutePath,
                IDLE_TIMEOUT_MILLIS.toString(),
            ).joinToString(separator = " ", transform = ::shellQuote)
            ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
        }.getOrElse {
            recoverFromFailure()
            return
        }
        daemonProcess = process
        consumeProcessOutput(process)
    }

    @Suppress("DEPRECATION")
    private fun watchForReady(directory: File, readyName: String) {
        observer = object : FileObserver(directory.absolutePath, CREATE or MOVED_TO) {
            override fun onEvent(event: Int, path: String?) {
                if (path == readyName) handler.post(::attachNativeClient)
            }
        }.also(FileObserver::startWatching)
    }

    private fun attachNativeClient() {
        if (stopping.get() || nativeAttached) return
        val handle = nativeHandle
        val socket = socketFile
        val secret = sessionKey
        if (handle == 0L || socket == null || secret == null) {
            recoverFromFailure()
            return
        }
        stopObserver()
        handler.removeCallbacks(startupTimeout)
        val result = NativeEngineBridge.nativeAttachRoot(
            handle,
            socket.absolutePath,
            secret,
            REQUEST_TIMEOUT_MILLIS,
        )
        secret.fill(0)
        sessionKey = null
        keyFile?.delete()
        keyFile = null
        readyFile?.delete()
        readyFile = null
        if (result == 0) {
            nativeAttached = true
            restartAttempts = 0
            onStateChanged(State.READY)
        } else {
            recoverFromFailure()
        }
    }

    private fun recoverFromFailure() {
        if (stopping.get() || nativeHandle == 0L || restartScheduled) return
        handler.removeCallbacks(startupTimeout)
        stopObserver()
        if (nativeAttached) {
            NativeEngineBridge.nativeDetachRoot(nativeHandle)
            nativeAttached = false
        }
        clearSecretAndFiles()
        val process = daemonProcess
        daemonProcess = null
        process?.destroy()

        if (restartAttempts >= MAX_RESTART_ATTEMPTS) {
            onStateChanged(State.FAILED)
            return
        }
        val delay = RESTART_DELAYS_MILLIS[restartAttempts]
        restartAttempts += 1
        restartScheduled = true
        onStateChanged(State.STARTING)
        handler.postDelayed(restart, delay)
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
    }

    private fun consumeProcessOutput(process: Process) {
        Thread({
            runCatching {
                process.inputStream.use { input ->
                    val buffer = ByteArray(PROCESS_OUTPUT_BUFFER_BYTES)
                    while (input.read(buffer) >= 0) {
                        // Private paths and runtime details must not enter ordinary logs.
                    }
                }
            }
            runCatching { process.waitFor() }
            handler.post {
                if (!stopping.get() && daemonProcess === process) recoverFromFailure()
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
        const val KEY_MODE = 384 // 0600
        const val SESSION_KEY_BYTES = 32
        const val TOKEN_BYTES = 16
        const val REQUEST_TIMEOUT_MILLIS = 7_000
        const val STARTUP_TIMEOUT_MILLIS = 15_000L
        const val IDLE_TIMEOUT_MILLIS = 300_000L
        const val PROCESS_OUTPUT_BUFFER_BYTES = 1_024
        const val MAX_RESTART_ATTEMPTS = 3
        val RESTART_DELAYS_MILLIS = longArrayOf(250L, 1_000L, 3_000L)
    }
}

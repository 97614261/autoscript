package com.autoscript.runtime.service

import android.app.Service
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Point
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.RemoteCallbackList
import android.os.RemoteException
import android.os.SystemClock
import android.view.WindowManager
import android.system.Os
import android.system.OsConstants
import com.autoscript.engine.jni.NativeEngineBridge
import com.autoscript.runtime.api.IRuntimeService
import com.autoscript.runtime.api.IRuntimeStateListener
import com.autoscript.runtime.api.RuntimeProtocol
import com.autoscript.runtime.api.ScriptValidationReply
import com.autoscript.runtime.api.VisualCompileReply
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject

class AutomationRuntimeService : Service() {
    private val sessionGenerationRef = AtomicLong(0L)
    private val nativeHandleRef = AtomicLong(0L)
    private val coordinateSnapshotRef = AtomicLong(1L)
    private val rootStateRef = AtomicReference(RootDaemonController.State.STOPPED)
    private val stateListeners = RemoteCallbackList<IRuntimeStateListener>()
    private val lastNotifiedStateRef = AtomicInteger(UNPUBLISHED_STATE)
    private val sessionRandom = SecureRandom()
    private lateinit var engineThread: HandlerThread
    private lateinit var engineHandler: Handler
    private lateinit var rootDaemonController: RootDaemonController
    private lateinit var nativeWakeListener: NativeWakeListener

    private val pump = object : Runnable {
        override fun run() {
            val handle = nativeHandleRef.get()
            if (handle == 0L) return
            val now = SystemClock.elapsedRealtimeNanos()
            NativeEngineBridge.nativePump(handle, now)
            notifyRuntimeStateIfChanged()
            scheduleFromNative(handle, now)
        }
    }

    private val binder = object : IRuntimeService.Stub() {
        override fun getProtocolVersion(): Int = RuntimeProtocol.VERSION

        override fun getSessionGeneration(): Long = sessionGenerationRef.get()

        override fun getRuntimeStateCode(): Int {
            val handle = nativeHandleRef.get()
            return if (handle == 0L) RuntimeProtocol.STATE_FAILED
            else NativeEngineBridge.nativeState(handle)
        }

        override fun registerStateListener(listener: IRuntimeStateListener) {
            stateListeners.register(listener)
            try {
                listener.onRuntimeStateChanged(
                    sessionGenerationRef.get(),
                    currentRuntimeState(),
                    currentRuntimeDiagnostic(),
                )
            } catch (_: RemoteException) {
                stateListeners.unregister(listener)
            }
        }

        override fun unregisterStateListener(listener: IRuntimeStateListener) {
            stateListeners.unregister(listener)
        }

        override fun validateScript(
            requestId: Long,
            expectedGeneration: Long,
            chunkName: String,
            source: ByteArray,
        ): ScriptValidationReply {
            if (expectedGeneration != sessionGenerationRef.get()) {
                return ScriptValidationReply(RuntimeProtocol.VALIDATION_SESSION_MISMATCH, null)
            }
            if (source.size > MAX_SCRIPT_BYTES || !isValidChunkName(chunkName)) {
                return ScriptValidationReply(
                    RuntimeProtocol.VALIDATION_INVALID,
                    "脚本超过16 MiB或入口名称无效",
                )
            }
            val diagnostic = NativeEngineBridge.nativeValidateLua(source, chunkName)
                ?: return ScriptValidationReply(
                    RuntimeProtocol.VALIDATION_ENGINE_ERROR,
                    "Lua校验器返回失败",
                )
            return if (diagnostic.isEmpty()) {
                ScriptValidationReply(RuntimeProtocol.VALIDATION_VALID, null)
            } else {
                ScriptValidationReply(RuntimeProtocol.VALIDATION_INVALID, diagnostic)
            }
        }

        override fun compileVisualProject(
            requestId: Long,
            expectedGeneration: Long,
            projectId: String,
        ): VisualCompileReply {
            if (expectedGeneration != sessionGenerationRef.get()) {
                return visualCompileReply(RuntimeProtocol.VISUAL_COMPILE_SESSION_MISMATCH)
            }
            val directory = resolveProjectDirectory(projectId)
                ?: return visualCompileReply(
                    RuntimeProtocol.VISUAL_COMPILE_INVALID,
                    code = "PROJECT_DIRECTORY",
                    diagnostic = "项目目录无效或不存在",
                )
            val encoded = NativeEngineBridge.nativeCompileVisualProject(directory.path)
                ?: return visualCompileReply(
                    RuntimeProtocol.VISUAL_COMPILE_ENGINE_ERROR,
                    code = "JNI_FAILURE",
                    diagnostic = "可视化编译器不可用",
                )
            return parseVisualCompileReply(encoded)
        }

        override fun validateVisualDraft(
            requestId: Long,
            expectedGeneration: Long,
            projectId: String,
            flowId: String,
            draftFile: ParcelFileDescriptor,
        ): VisualCompileReply = draftFile.use { descriptor ->
            if (expectedGeneration != sessionGenerationRef.get()) {
                return visualCompileReply(RuntimeProtocol.VISUAL_COMPILE_SESSION_MISMATCH)
            }
            val directory = resolveProjectDirectory(projectId)
                ?: return visualCompileReply(
                    RuntimeProtocol.VISUAL_COMPILE_INVALID,
                    code = "PROJECT_DIRECTORY",
                    diagnostic = "项目目录无效或不存在",
                )
            if (!FLOW_ID.matches(flowId)) {
                return visualCompileReply(
                    RuntimeProtocol.VISUAL_COMPILE_INVALID,
                    code = "INVALID_FLOW_ID",
                    diagnostic = "Flow ID无效",
                )
            }
            val bytes = runCatching { readVisualDraft(descriptor) }.getOrElse {
                return visualCompileReply(
                    RuntimeProtocol.VISUAL_COMPILE_INVALID,
                    code = "RESOURCE_LIMIT",
                    diagnostic = "Flow草稿不可读或超过64 MiB",
                )
            }
            val encoded = NativeEngineBridge.nativeValidateVisualDraft(
                directory.path,
                flowId,
                bytes,
            ) ?: return visualCompileReply(
                RuntimeProtocol.VISUAL_COMPILE_ENGINE_ERROR,
                code = "JNI_FAILURE",
                diagnostic = "可视化草稿校验器不可用",
            )
            return parseVisualCompileReply(encoded)
        }

        override fun prepareProject(requestId: Long, expectedGeneration: Long): Int {
            if (expectedGeneration != sessionGenerationRef.get()) {
                return RuntimeProtocol.PREPARE_SESSION_MISMATCH
            }
            val handle = nativeHandleRef.get()
            if (handle == 0L) return RuntimeProtocol.PREPARE_ENGINE_ERROR
            return when (currentRuntimeState()) {
                RuntimeProtocol.STATE_RUNNING, RuntimeProtocol.STATE_STOPPING -> {
                    RuntimeProtocol.PREPARE_BUSY
                }
                RuntimeProtocol.STATE_IDLE,
                RuntimeProtocol.STATE_STOPPED,
                RuntimeProtocol.STATE_FAILED,
                -> {
                    engineHandler.removeCallbacks(pump)
                    val displaySize = currentDisplaySize()
                    if (NativeEngineBridge.nativeReset(handle, displaySize.x, displaySize.y) == 0) {
                        coordinateSnapshotRef.incrementAndGet()
                        notifyRuntimeStateIfChanged()
                        RuntimeProtocol.PREPARE_ACCEPTED
                    } else {
                        notifyRuntimeStateIfChanged()
                        RuntimeProtocol.PREPARE_ENGINE_ERROR
                    }
                }
                else -> RuntimeProtocol.PREPARE_ENGINE_ERROR
            }
        }

        override fun registerTemplate(
            requestId: Long,
            expectedGeneration: Long,
            assetPath: String,
            imageFile: ParcelFileDescriptor,
        ): Int {
            if (expectedGeneration != sessionGenerationRef.get()) {
                imageFile.close()
                return RuntimeProtocol.TEMPLATE_SESSION_MISMATCH
            }
            if (!isValidTemplatePath(assetPath)) {
                imageFile.close()
                return RuntimeProtocol.TEMPLATE_INVALID_PATH
            }
            val decoded = runCatching { imageFile.use(::decodeTemplate) }.getOrNull()
                ?: return RuntimeProtocol.TEMPLATE_INVALID_IMAGE
            val handle = nativeHandleRef.get()
            if (handle == 0L || NativeEngineBridge.nativeRegisterTemplate(
                    handle,
                    assetPath,
                    decoded.width,
                    decoded.height,
                    decoded.rgba,
                ) != 0
            ) {
                return RuntimeProtocol.TEMPLATE_ENGINE_ERROR
            }
            return RuntimeProtocol.TEMPLATE_ACCEPTED
        }

        override fun registerDictionary(
            requestId: Long,
            expectedGeneration: Long,
            resourcePath: String,
            dictionaryFile: ParcelFileDescriptor,
        ): Int {
            if (expectedGeneration != sessionGenerationRef.get()) {
                dictionaryFile.close()
                return RuntimeProtocol.DICTIONARY_SESSION_MISMATCH
            }
            if (!isValidDictionaryPath(resourcePath)) {
                dictionaryFile.close()
                return RuntimeProtocol.DICTIONARY_INVALID_PATH
            }
            val bytes = runCatching { readDictionary(dictionaryFile) }.getOrNull()
                ?: return RuntimeProtocol.DICTIONARY_INVALID_FILE
            val handle = nativeHandleRef.get()
            if (handle == 0L || NativeEngineBridge.nativeRegisterDictionary(
                    handle,
                    resourcePath,
                    bytes,
                ) != 0
            ) {
                return RuntimeProtocol.DICTIONARY_ENGINE_ERROR
            }
            return RuntimeProtocol.DICTIONARY_ACCEPTED
        }

        override fun startScript(
            requestId: Long,
            expectedGeneration: Long,
            generatedLuaModule: ByteArray,
            designWidth: Int,
            designHeight: Int,
            scaleMode: Int,
        ): Int {
            if (expectedGeneration != sessionGenerationRef.get()) {
                return RuntimeProtocol.START_SESSION_MISMATCH
            }
            if (generatedLuaModule.isEmpty() || generatedLuaModule.size > MAX_SCRIPT_BYTES) {
                return RuntimeProtocol.START_INVALID_SCRIPT
            }
            if (designWidth !in 1..MAX_DESIGN_DIMENSION ||
                designHeight !in 1..MAX_DESIGN_DIMENSION ||
                scaleMode !in RuntimeProtocol.SCALE_LETTERBOX..RuntimeProtocol.SCALE_STRETCH
            ) {
                return RuntimeProtocol.START_INVALID_PROJECT
            }
            if (rootStateRef.get() != RootDaemonController.State.READY) {
                return RuntimeProtocol.START_BACKEND_NOT_READY
            }
            val handle = nativeHandleRef.get()
            if (handle == 0L) return RuntimeProtocol.START_INVALID_SCRIPT
            val snapshotId = coordinateSnapshotRef.incrementAndGet()
            if (NativeEngineBridge.nativeConfigureProject(
                    handle,
                    snapshotId,
                    designWidth,
                    designHeight,
                    scaleMode,
                ) != 0
            ) {
                return RuntimeProtocol.START_INVALID_PROJECT
            }
            if (NativeEngineBridge.nativeStart(handle, generatedLuaModule) != 0) {
                return RuntimeProtocol.START_INVALID_SCRIPT
            }
            engineHandler.removeCallbacks(pump)
            engineHandler.post(pump)
            notifyRuntimeStateIfChanged()
            return RuntimeProtocol.START_ACCEPTED
        }

        override fun requestStop(requestId: Long, expectedGeneration: Long): Int {
            if (expectedGeneration != sessionGenerationRef.get()) {
                return RuntimeProtocol.STOP_SESSION_MISMATCH
            }
            engineHandler.removeCallbacks(pump)
            val handle = nativeHandleRef.get()
            if (handle != 0L) {
                return if (NativeEngineBridge.nativeStop(
                        handle,
                        SystemClock.elapsedRealtimeNanos(),
                    ) == 0
                ) {
                    notifyRuntimeStateIfChanged()
                    RuntimeProtocol.STOP_ACCEPTED
                } else {
                    RuntimeProtocol.STOP_ENGINE_ERROR
                }
            }
            return RuntimeProtocol.STOP_ACCEPTED
        }
    }

    override fun onCreate() {
        super.onCreate()
        engineThread = HandlerThread("autoscript-engine-control").apply { start() }
        engineHandler = Handler(engineThread.looper)
        val displaySize = currentDisplaySize()
        nativeHandleRef.set(NativeEngineBridge.nativeCreate(displaySize.x, displaySize.y))
        sessionGenerationRef.set(newSessionGeneration())
        lastNotifiedStateRef.set(currentRuntimeState())
        rootDaemonController = RootDaemonController(this, engineHandler, rootStateRef::set)
        val handle = nativeHandleRef.get()
        if (handle != 0L) {
            nativeWakeListener = NativeWakeListener(
                engineHandler,
                pump,
                rootDaemonController::onNativeDisconnected,
            )
            if (NativeEngineBridge.nativeSetWakeListener(handle, nativeWakeListener) == 0) {
                engineHandler.post { rootDaemonController.start(handle) }
            } else {
                rootStateRef.set(RootDaemonController.State.FAILED)
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val displaySize = currentDisplaySize()
        val handle = nativeHandleRef.get()
        val snapshotId = coordinateSnapshotRef.incrementAndGet()
        if (handle != 0L) {
            engineHandler.post {
                if (nativeHandleRef.get() == handle) {
                    NativeEngineBridge.nativeUpdateDisplay(
                        handle,
                        snapshotId,
                        displaySize.x,
                        displaySize.y,
                    )
                }
            }
        }
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onDestroy() {
        engineHandler.removeCallbacksAndMessages(null)
        val handle = nativeHandleRef.getAndSet(0L)
        if (handle != 0L) {
            rootDaemonController.stop()
            NativeEngineBridge.nativeStop(handle, SystemClock.elapsedRealtimeNanos())
            NativeEngineBridge.nativeDestroy(handle)
        }
        engineThread.quitSafely()
        stateListeners.kill()
        super.onDestroy()
    }

    private fun currentRuntimeState(): Int {
        val handle = nativeHandleRef.get()
        return if (handle == 0L) RuntimeProtocol.STATE_FAILED
        else NativeEngineBridge.nativeState(handle)
    }

    @Synchronized
    private fun notifyRuntimeStateIfChanged() {
        val state = currentRuntimeState()
        if (lastNotifiedStateRef.get() == state) return
        lastNotifiedStateRef.set(state)
        val generation = sessionGenerationRef.get()
        val count = stateListeners.beginBroadcast()
        try {
            for (index in 0 until count) {
                runCatching {
                    stateListeners.getBroadcastItem(index)
                        .onRuntimeStateChanged(generation, state, currentRuntimeDiagnostic())
                }
            }
        } finally {
            stateListeners.finishBroadcast()
        }
    }

    private fun scheduleFromNative(handle: Long, now: Long) {
        when (val deadline = NativeEngineBridge.nativeNextWakeNanos(handle)) {
            NEXT_IDLE, NEXT_STOPPED -> Unit
            NEXT_RUNNABLE -> engineHandler.post(pump)
            else -> {
                val remainingNanos = (deadline - now).coerceAtLeast(0L)
                val delayMillis = (remainingNanos + NANOS_PER_MILLISECOND - 1) / NANOS_PER_MILLISECOND
                engineHandler.postDelayed(pump, delayMillis)
            }
        }
    }

    private fun currentRuntimeDiagnostic(): String? {
        val handle = nativeHandleRef.get()
        if (handle == 0L || currentRuntimeState() != RuntimeProtocol.STATE_FAILED) return null
        return NativeEngineBridge.nativeLastDiagnostic(handle)
            ?.take(MAX_RUNTIME_DIAGNOSTIC_LENGTH)
            ?.ifBlank { "引擎运行失败，未返回诊断详情" }
    }

    private fun newSessionGeneration(): Long {
        var generation: Long
        do {
            generation = sessionRandom.nextLong()
        } while (generation == 0L)
        return generation
    }

    private fun decodeTemplate(descriptor: ParcelFileDescriptor): DecodedTemplate {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFileDescriptor(descriptor.fileDescriptor, null, bounds)
        val width = bounds.outWidth
        val height = bounds.outHeight
        val pixelCount = width.toLong() * height.toLong()
        require(width in 1..MAX_TEMPLATE_DIMENSION && height in 1..MAX_TEMPLATE_DIMENSION)
        require(pixelCount in 1..MAX_TEMPLATE_PIXELS)

        Os.lseek(descriptor.fileDescriptor, 0L, OsConstants.SEEK_SET)
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inScaled = false
        }
        val bitmap = requireNotNull(
            BitmapFactory.decodeFileDescriptor(descriptor.fileDescriptor, null, options),
        )
        require(bitmap.width == width && bitmap.height == height)
        val argb = IntArray(pixelCount.toInt())
        bitmap.getPixels(argb, 0, width, 0, 0, width, height)
        bitmap.recycle()
        val rgba = ByteArray(argb.size * 4)
        argb.forEachIndexed { index, color ->
            val offset = index * 4
            rgba[offset] = ((color ushr 16) and 0xff).toByte()
            rgba[offset + 1] = ((color ushr 8) and 0xff).toByte()
            rgba[offset + 2] = (color and 0xff).toByte()
            rgba[offset + 3] = ((color ushr 24) and 0xff).toByte()
        }
        return DecodedTemplate(width, height, rgba)
    }

    private fun isValidTemplatePath(path: String): Boolean =
        path.length in 1..MAX_TEMPLATE_PATH_LENGTH &&
            path.startsWith("assets/images/") &&
            '\\' !in path &&
            '\u0000' !in path &&
            path.split('/').all { it.isNotEmpty() && it != "." && it != ".." }

    private fun readDictionary(descriptor: ParcelFileDescriptor): ByteArray {
        val initialCapacity = descriptor.statSize
            .takeIf { it in 1..MAX_DICTIONARY_BYTES.toLong() }
            ?.toInt()
            ?: DICTIONARY_READ_BUFFER_BYTES
        val output = ByteArrayOutputStream(initialCapacity)
        ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
            val buffer = ByteArray(DICTIONARY_READ_BUFFER_BYTES)
            var total = 0
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read == 0) continue
                total = Math.addExact(total, read)
                require(total <= MAX_DICTIONARY_BYTES)
                output.write(buffer, 0, read)
            }
        }
        return output.toByteArray().also { require(it.isNotEmpty()) }
    }

    private fun readVisualDraft(descriptor: ParcelFileDescriptor): ByteArray {
        val size = descriptor.statSize
        require(size in 0..MAX_FLOW_BYTES.toLong())
        val initialCapacity = size.takeIf { it > 0 }?.toInt() ?: FLOW_READ_BUFFER_BYTES
        val output = ByteArrayOutputStream(initialCapacity)
        ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
            val buffer = ByteArray(FLOW_READ_BUFFER_BYTES)
            var total = 0
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read == 0) continue
                total = Math.addExact(total, read)
                require(total <= MAX_FLOW_BYTES)
                output.write(buffer, 0, read)
            }
        }
        return output.toByteArray()
    }

    private fun isValidDictionaryPath(path: String): Boolean =
        path.length in 1..MAX_DICTIONARY_PATH_LENGTH &&
            path.startsWith("dictionaries/") &&
            '\\' !in path &&
            '\u0000' !in path &&
            path.split('/').all { it.isNotEmpty() && it != "." && it != ".." }

    private fun isValidChunkName(name: String): Boolean =
        name.length in 1..MAX_CHUNK_NAME_LENGTH && '\u0000' !in name

    private fun resolveProjectDirectory(projectId: String): File? {
        if (!PROJECT_ID.matches(projectId)) return null
        return runCatching {
            val root = File(filesDir, "projects").canonicalFile
            val directory = File(root, projectId).canonicalFile
            directory.takeIf { it.parentFile == root && it.isDirectory }
        }.getOrNull()
    }

    private fun parseVisualCompileReply(encoded: String): VisualCompileReply = runCatching {
        val json = JSONObject(encoded)
        if (json.optString("status") == "ok") {
            val generationId = json.nullableString("generationId")
            if (generationId.isNullOrBlank()) {
                visualCompileReply(
                    RuntimeProtocol.VISUAL_COMPILE_ENGINE_ERROR,
                    code = "INVALID_REPLY",
                    diagnostic = "编译器成功结果缺少generationId",
                )
            } else {
                visualCompileReply(
                    RuntimeProtocol.VISUAL_COMPILE_ACCEPTED,
                    generationId = generationId.take(MAX_GENERATION_ID_LENGTH),
                )
            }
        } else {
            visualCompileReply(
                RuntimeProtocol.VISUAL_COMPILE_INVALID,
                code = json.nullableString("code")?.take(MAX_DIAGNOSTIC_CODE_LENGTH),
                diagnostic = json.nullableString("diagnostic")?.take(MAX_DIAGNOSTIC_LENGTH),
                flowId = json.nullableString("flowId")?.take(MAX_ID_LENGTH),
                nodeId = json.nullableString("nodeId")?.take(MAX_ID_LENGTH),
                line = json.optInt("line", -1).coerceAtLeast(-1),
            )
        }
    }.getOrElse {
        visualCompileReply(
            RuntimeProtocol.VISUAL_COMPILE_ENGINE_ERROR,
            code = "INVALID_REPLY",
            diagnostic = "编译器返回了无法解析的结果",
        )
    }

    private fun JSONObject.nullableString(name: String): String? =
        if (isNull(name)) null else optString(name).takeIf(String::isNotEmpty)

    private fun visualCompileReply(
        status: Int,
        generationId: String? = null,
        code: String? = null,
        diagnostic: String? = null,
        flowId: String? = null,
        nodeId: String? = null,
        line: Int = -1,
    ) = VisualCompileReply(status, generationId, code, diagnostic, flowId, nodeId, line)

    @Suppress("DEPRECATION")
    private fun currentDisplaySize(): Point {
        val windowManager = getSystemService(WindowManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.maximumWindowMetrics.bounds
            return Point(bounds.width(), bounds.height())
        }
        return Point().also(windowManager.defaultDisplay::getRealSize)
    }

    private companion object {
        const val MAX_SCRIPT_BYTES = 16 * 1024 * 1024
        const val MAX_CHUNK_NAME_LENGTH = 256
        const val MAX_GENERATION_ID_LENGTH = 128
        const val MAX_DIAGNOSTIC_CODE_LENGTH = 64
        const val MAX_DIAGNOSTIC_LENGTH = 4_096
        const val MAX_ID_LENGTH = 128
        const val MAX_DESIGN_DIMENSION = 32_768
        const val MAX_TEMPLATE_DIMENSION = 4_096
        const val MAX_TEMPLATE_PIXELS = 4_194_304L
        const val MAX_TEMPLATE_PATH_LENGTH = 256
        const val MAX_DICTIONARY_BYTES = 8 * 1024 * 1024
        const val MAX_FLOW_BYTES = 64 * 1024 * 1024
        const val MAX_RUNTIME_DIAGNOSTIC_LENGTH = 4_096
        const val MAX_DICTIONARY_PATH_LENGTH = 256
        const val DICTIONARY_READ_BUFFER_BYTES = 32 * 1024
        const val FLOW_READ_BUFFER_BYTES = 64 * 1024
        const val NANOS_PER_MILLISECOND = 1_000_000L
        const val NEXT_RUNNABLE = 0L
        const val NEXT_IDLE = -1L
        const val NEXT_STOPPED = -2L
        const val UNPUBLISHED_STATE = Int.MIN_VALUE
        val PROJECT_ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")
        val FLOW_ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")
    }
}

private data class DecodedTemplate(
    val width: Int,
    val height: Int,
    val rgba: ByteArray,
)

internal class NativeWakeListener(
    private val handler: Handler,
    private val pump: Runnable,
    private val onRootDisconnected: () -> Unit,
) {
    @Suppress("unused")
    fun onNativeWake() {
        handler.post(pump)
    }

    @Suppress("unused")
    fun onNativeRootDisconnected() {
        handler.post(onRootDisconnected)
    }
}

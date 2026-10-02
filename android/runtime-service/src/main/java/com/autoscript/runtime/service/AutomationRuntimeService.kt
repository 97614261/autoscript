package com.autoscript.runtime.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
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
import android.os.Binder
import android.os.Looper
import android.os.Process
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
import com.autoscript.runtime.api.IInputPointPickListener
import com.autoscript.runtime.api.InputPointAction
import com.autoscript.runtime.api.InputPointPickReply
import com.autoscript.runtime.api.RuntimeProtocol
import com.autoscript.runtime.api.TemplateMatchReply
import com.autoscript.runtime.api.RuntimeDebugReply
import com.autoscript.runtime.api.RuntimeDebugValue
import com.autoscript.runtime.api.ScriptValidationReply
import com.autoscript.runtime.api.VisualCompileReply
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.security.SecureRandom
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean
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
    private val lastNotifiedRootStateRef = AtomicInteger(UNPUBLISHED_STATE)
    private val stepControlPending = AtomicBoolean(false)
    private val foregroundActive = AtomicBoolean(false)
    private val floatingControlEnabled = AtomicBoolean(true)
    private val captureOverlayEnabled = AtomicBoolean(false)
    private val promptSessionActive = AtomicBoolean(false)
    private val toastSessionActive = AtomicBoolean(false)
    /** The only Studio-specific context retained by Runner; it is an opaque validated ID. */
    private val lastProjectIdRef = AtomicReference<String?>(null)
    private val runtimeLogs = ArrayDeque<String>(MAX_RUNTIME_LOG_ENTRIES)
    private val runtimeLogLock = Any()
    private val sessionRandom = SecureRandom()
    private lateinit var engineThread: HandlerThread
    private lateinit var engineHandler: Handler
    private lateinit var scriptUiController: RuntimeScriptUiController
    private val pendingScriptUiEvents = AtomicInteger()
    private lateinit var rootDaemonController: RootDaemonController
    private lateinit var nativeWakeListener: NativeWakeListener
    private lateinit var overlayController: RuntimeOverlayController
    private lateinit var nativeVisionAdapter: NativeVisionAdapter
    private val inputPickActive = AtomicBoolean(false)
    private val inputPickHandler = Handler(Looper.getMainLooper())
    private lateinit var inputPickOverlay: RuntimeInputPointOverlay
    // Main-thread-only session state; the atomic flag bounds pending Binder starts to one.
    private var inputPickSession: InputPickSession? = null
    private data class InputPickSession(
        val requestId: Long, val generation: Long, val projectId: String, val flowId: String,
        val action: String, val features: Int, val listener: IInputPointPickListener,
        val death: IBinder.DeathRecipient, val expiry: Runnable,
    )

    private val pump = object : Runnable {
        override fun run() {
            val handle = nativeHandleRef.get()
            if (handle == 0L) return
            val state = NativeEngineBridge.nativeState(handle)
            if (state != RuntimeProtocol.STATE_RUNNING && state != RuntimeProtocol.STATE_PAUSED) {
                notifyRuntimeStateIfChanged()
                return
            }
            val now = SystemClock.elapsedRealtimeNanos()
            NativeEngineBridge.nativePump(handle, now)
            drainNativeScriptLogs(handle)
            drainNativeScriptPrompts(handle)
            notifyRuntimeStateIfChanged()
            // Native wake and a scheduled deadline can enqueue the same singleton Runnable.
            // Drop stale copies before scheduling the next authoritative wake.
            engineHandler.removeCallbacks(this)
            scheduleFromNative(handle, now)
        }
    }

    private val stopStateObserver = object : Runnable {
        override fun run() {
            val handle = nativeHandleRef.get()
            if (handle == 0L) return
            notifyRuntimeStateIfChanged()
            if (NativeEngineBridge.nativeState(handle) == RuntimeProtocol.STATE_STOPPING) {
                engineHandler.postDelayed(this, STOP_STATE_OBSERVER_DELAY_MILLIS)
            }
        }
    }

    private val binder = object : IRuntimeService.Stub() {
        override fun beginInputPointPick(
            requestId: Long, expectedGeneration: Long, projectId: String, flowId: String,
            action: String, listener: IInputPointPickListener,
        ): Int {
            if (Binder.getCallingUid() != Process.myUid()) return RuntimeProtocol.CONTROL_INVALID_STATE
            if (expectedGeneration != sessionGenerationRef.get()) return RuntimeProtocol.CONTROL_SESSION_MISMATCH
            val selected = InputPointAction.fromWire(action) ?: return RuntimeProtocol.CONTROL_INVALID_STATE
            val features = getInputFeatures(expectedGeneration)
            if (requestId <= 0 || !isValidProjectId(projectId) || !isValidProjectId(flowId) ||
                !selected.available(features) || currentRuntimeState() in setOf(RuntimeProtocol.STATE_RUNNING,
                    RuntimeProtocol.STATE_PAUSED, RuntimeProtocol.STATE_STOPPING)
            ) return RuntimeProtocol.CONTROL_INVALID_STATE
            if (!overlayController.canShow()) return RuntimeProtocol.SURFACE_PERMISSION_DENIED
            if (!inputPickActive.compareAndSet(false, true)) return RuntimeProtocol.CONTROL_INVALID_STATE
            // Start while the requesting Activity is still visible; only then may Studio move behind the target app.
            if (!ensureForegroundForRun()) {
                inputPickActive.set(false)
                return RuntimeProtocol.CONTROL_ENGINE_ERROR
            }
            inputPickHandler.post {
                val death = IBinder.DeathRecipient { inputPickHandler.post { cancelInputPick(requestId, "编辑器已断开") } }
                val expiry = Runnable { cancelInputPick(requestId, "选点超时，请重新选择") }
                inputPickSession = InputPickSession(requestId, expectedGeneration, projectId, flowId, action, features, listener, death, expiry)
                val ready = expectedGeneration == sessionGenerationRef.get() && selected.available(getInputFeatures(expectedGeneration)) &&
                    currentRuntimeState() !in setOf(RuntimeProtocol.STATE_RUNNING, RuntimeProtocol.STATE_PAUSED, RuntimeProtocol.STATE_STOPPING)
                if (!ready || !runCatching { listener.asBinder().linkToDeath(death, 0); ensureForegroundForRun() }.getOrDefault(false)) {
                    completeInputPick(InputPointPickReply(requestId, InputPointPickReply.FAILED, action, message = "选点会话无法启动"))
                } else {
                    captureOverlayEnabled.set(false)
                    overlayController.hide()
                    inputPickHandler.postDelayed(expiry, 120_000L)
                    inputPickOverlay.show(requestId, selected, features)
                }
            }
            return RuntimeProtocol.CONTROL_ACCEPTED
        }

        override fun cancelInputPointPick(requestId: Long, expectedGeneration: Long): Int {
            if (Binder.getCallingUid() != Process.myUid()) return RuntimeProtocol.CONTROL_INVALID_STATE
            if (expectedGeneration != sessionGenerationRef.get()) return RuntimeProtocol.CONTROL_SESSION_MISMATCH
            inputPickHandler.post { cancelInputPick(requestId, "已取消选点") }
            return RuntimeProtocol.CONTROL_ACCEPTED
        }

        override fun getProtocolVersion(): Int = RuntimeProtocol.VERSION
        override fun getInputFeatures(expectedGeneration: Long): Int =
            if (expectedGeneration == sessionGenerationRef.get() && rootStateRef.get() == RootDaemonController.State.READY) {
                NativeEngineBridge.nativeInputFeatures(nativeHandleRef.get())
            } else 0

        override fun testTemplate(
            expectedGeneration: Long, frameFile: ParcelFileDescriptor, templateFile: ParcelFileDescriptor,
            tolerance: Int, similarityPermille: Int,
        ): TemplateMatchReply = frameFile.use { frameDescriptor -> templateFile.use { templateDescriptor ->
            fun failure(status: Int) = TemplateMatchReply(status, -1, -1, 0)
            if (expectedGeneration != sessionGenerationRef.get()) return failure(TemplateMatchReply.SESSION_MISMATCH)
            if (tolerance !in 0..255 || similarityPermille !in 0..1000) return failure(TemplateMatchReply.INVALID_IMAGE)
            if (frameDescriptor.statSize !in 1..MAX_PREVIEW_FILE_BYTES || templateDescriptor.statSize !in 1..MAX_PREVIEW_FILE_BYTES) return failure(TemplateMatchReply.INVALID_IMAGE)
            if (currentRuntimeState() in setOf(RuntimeProtocol.STATE_RUNNING, RuntimeProtocol.STATE_PAUSED, RuntimeProtocol.STATE_STOPPING) ||
                !previewRecognitionBusy.compareAndSet(false, true)
            ) return failure(TemplateMatchReply.BUSY)
            try {
                val frame = decodeTemplate(frameDescriptor)
                val template = decodeTemplate(templateDescriptor)
                val result = NativeEngineBridge.nativeTestTemplate(
                    frame.width, frame.height, frame.rgba, template.width, template.height, template.rgba,
                    tolerance, similarityPermille,
                ) ?: return failure(TemplateMatchReply.INVALID_IMAGE)
                if (result.size != 4 || expectedGeneration != sessionGenerationRef.get()) return failure(TemplateMatchReply.SESSION_MISMATCH)
                TemplateMatchReply(result[0], result[1], result[2], result[3])
            } catch (_: Exception) {
                failure(TemplateMatchReply.INVALID_IMAGE)
            } finally {
                previewRecognitionBusy.set(false)
            }
        } }

        override fun getSessionGeneration(): Long = sessionGenerationRef.get()

        override fun getRuntimeStateCode(): Int {
            val handle = nativeHandleRef.get()
            return if (handle == 0L) RuntimeProtocol.STATE_FAILED
            else NativeEngineBridge.nativeState(handle)
        }

        override fun getRootStateCode(): Int = currentRootStateCode()

        override fun registerStateListener(listener: IRuntimeStateListener) {
            stateListeners.register(listener)
            try {
                listener.onRuntimeStateChanged(
                    sessionGenerationRef.get(),
                    currentRuntimeState(),
                    currentRootStateCode(),
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
            if (!NativeEngineBridge.ensureVisualCompilerLoaded()) {
                return visualCompileReply(RuntimeProtocol.VISUAL_COMPILE_ENGINE_ERROR,
                    code = "COMPILER_UNAVAILABLE", diagnostic = "当前应用不包含 Studio 编译器")
            }
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
            if (!NativeEngineBridge.ensureVisualCompilerLoaded()) {
                return visualCompileReply(RuntimeProtocol.VISUAL_COMPILE_ENGINE_ERROR,
                    code = "COMPILER_UNAVAILABLE", diagnostic = "当前应用不包含 Studio 编译器")
            }
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
            if (inputPickActive.get()) return RuntimeProtocol.PREPARE_BUSY
            if (expectedGeneration != sessionGenerationRef.get()) {
                return RuntimeProtocol.PREPARE_SESSION_MISMATCH
            }
            val handle = nativeHandleRef.get()
            if (handle == 0L) return RuntimeProtocol.PREPARE_ENGINE_ERROR
            return when (currentRuntimeState()) {
                RuntimeProtocol.STATE_RUNNING,
                RuntimeProtocol.STATE_PAUSED,
                RuntimeProtocol.STATE_STOPPING,
                -> {
                    RuntimeProtocol.PREPARE_BUSY
                }
                RuntimeProtocol.STATE_IDLE,
                RuntimeProtocol.STATE_STOPPED,
                RuntimeProtocol.STATE_FAILED,
                -> {
                    engineHandler.removeCallbacks(pump)
                    val displaySize = currentDisplaySize()
                    if (NativeEngineBridge.nativeReset(handle, displaySize.x, displaySize.y) == 0) {
                        scriptUiController.clear()
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

        override fun configureScriptUi(expectedGeneration: Long, projectId: String, definitionJson: String,
            valuesJson: String, imagePaths: Array<out String>, imageFiles: Array<out String>): Int {
            if (expectedGeneration != sessionGenerationRef.get()) return RuntimeProtocol.CONTROL_SESSION_MISMATCH
            if (currentRuntimeState() != RuntimeProtocol.STATE_IDLE) return RuntimeProtocol.CONTROL_INVALID_STATE
            return if (runCatching { scriptUiController.prepare(definitionJson, valuesJson, imagePaths, imageFiles) &&
                NativeEngineBridge.nativeConfigureUiValues(nativeHandleRef.get(), scriptUiController.initialValuesJson) == 0 }.getOrDefault(false))
                RuntimeProtocol.CONTROL_ACCEPTED else RuntimeProtocol.CONTROL_ENGINE_ERROR
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
            projectId: String,
            generatedLuaModule: ByteArray,
            designWidth: Int,
            designHeight: Int,
            scaleMode: Int,
            capabilities: Array<out String>,
        ): Int {
            if (inputPickActive.get()) return RuntimeProtocol.START_BACKEND_NOT_READY
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
            if (!validCapabilities(capabilities)) return RuntimeProtocol.START_INVALID_PROJECT
            if (projectId.isNotEmpty() && !isValidProjectId(projectId)) {
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
            if (!ensureForegroundForRun()) {
                return RuntimeProtocol.START_FOREGROUND_UNAVAILABLE
            }
            promptSessionActive.set(false)
            toastSessionActive.set(false)
            overlayController.resetRunPrompts()
            if (NativeEngineBridge.nativeStart(
                    handle,
                    generatedLuaModule,
                    capabilities.toList().toTypedArray(),
                ) != 0
            ) {
                finishForegroundRun()
                return RuntimeProtocol.START_INVALID_SCRIPT
            }
            stepControlPending.set(false)
            lastProjectIdRef.set(projectId.takeIf(String::isNotEmpty))
            scriptUiController.activateRuntime()
            synchronized(runtimeLogLock) { runtimeLogs.clear() }
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
            stepControlPending.set(false)
            val handle = nativeHandleRef.get()
            if (handle != 0L) {
                return if (stopNativeEngine()) {
                    RuntimeProtocol.STOP_ACCEPTED
                } else {
                    RuntimeProtocol.STOP_ENGINE_ERROR
                }
            }
            finishForegroundRun()
            return RuntimeProtocol.STOP_ACCEPTED
        }

        override fun requestPause(requestId: Long, expectedGeneration: Long): Int {
            if (expectedGeneration != sessionGenerationRef.get()) {
                return RuntimeProtocol.CONTROL_SESSION_MISMATCH
            }
            if (currentRuntimeState() != RuntimeProtocol.STATE_RUNNING) {
                return RuntimeProtocol.CONTROL_INVALID_STATE
            }
            return if (pauseNativeEngine()) {
                RuntimeProtocol.CONTROL_ACCEPTED
            } else {
                RuntimeProtocol.CONTROL_ENGINE_ERROR
            }
        }

        override fun requestResume(requestId: Long, expectedGeneration: Long): Int {
            if (expectedGeneration != sessionGenerationRef.get()) {
                return RuntimeProtocol.CONTROL_SESSION_MISMATCH
            }
            if (currentRuntimeState() != RuntimeProtocol.STATE_PAUSED) {
                return RuntimeProtocol.CONTROL_INVALID_STATE
            }
            return if (resumeNativeEngine()) {
                RuntimeProtocol.CONTROL_ACCEPTED
            } else {
                RuntimeProtocol.CONTROL_ENGINE_ERROR
            }
        }

        override fun requestStep(requestId: Long, expectedGeneration: Long): Int {
            if (expectedGeneration != sessionGenerationRef.get()) return RuntimeProtocol.CONTROL_SESSION_MISMATCH
            if (currentRuntimeState() != RuntimeProtocol.STATE_PAUSED) return RuntimeProtocol.CONTROL_INVALID_STATE
            if (!stepControlPending.compareAndSet(false, true)) return RuntimeProtocol.CONTROL_INVALID_STATE
            val handle = nativeHandleRef.get()
            if (handle == 0L || NativeEngineBridge.nativeStep(handle, SystemClock.elapsedRealtimeNanos()) != 0) {
                stepControlPending.set(false)
                return RuntimeProtocol.CONTROL_ENGINE_ERROR
            }
            engineHandler.removeCallbacks(pump)
            engineHandler.post {
                // Publish before pumping: short steps may return to PAUSED within the same pump.
                notifyRuntimeStateIfChanged()
                pump.run()
            }
            return RuntimeProtocol.CONTROL_ACCEPTED
        }

        override fun getDebugSnapshot(expectedGeneration: Long, projectId: String): RuntimeDebugReply {
            if (expectedGeneration != sessionGenerationRef.get() || projectId != lastProjectIdRef.get()) return RuntimeDebugReply.unavailable(RuntimeDebugReply.SESSION_MISMATCH)
            return runCatching {
                val encoded = NativeEngineBridge.nativeDebugSnapshot(nativeHandleRef.get()) ?: return RuntimeDebugReply.unavailable()
                require(encoded.length <= 192 * 1024)
                val json = org.json.JSONObject(encoded)
                val rows = json.getJSONArray("variables")
                require(rows.length() <= 128)
                val result = RuntimeDebugReply(RuntimeDebugReply.SUCCESS, json.getString("flowId"), json.getString("nodeId"),
                    List(rows.length()) { index ->
                        val row = rows.getJSONObject(index)
                        RuntimeDebugValue(row.getString("scope"), row.getString("name"), row.getString("type"), row.getString("value"), row.optBoolean("truncated", false))
                    })
                if (expectedGeneration == sessionGenerationRef.get()) result else RuntimeDebugReply.unavailable(RuntimeDebugReply.SESSION_MISMATCH)
            }.getOrElse { RuntimeDebugReply.unavailable() }
        }

        override fun setFloatingControlEnabled(
            requestId: Long,
            expectedGeneration: Long,
            enabled: Boolean,
        ): Int {
            if (expectedGeneration != sessionGenerationRef.get()) {
                return RuntimeProtocol.SURFACE_SESSION_MISMATCH
            }
            if (enabled && !overlayController.canShow()) {
                floatingControlEnabled.set(false)
                overlayController.hide()
                return RuntimeProtocol.SURFACE_PERMISSION_DENIED
            }
            floatingControlEnabled.set(enabled)
            if (enabled && foregroundActive.get()) {
                overlayController.show(currentRuntimeState())
            } else if (!enabled) {
                overlayController.hide()
            }
            return RuntimeProtocol.SURFACE_ACCEPTED
        }

        override fun setCaptureOverlayEnabled(
            requestId: Long,
            expectedGeneration: Long,
            projectId: String,
            enabled: Boolean,
        ): Int {
            if (enabled && inputPickActive.get()) return RuntimeProtocol.CONTROL_INVALID_STATE
            if (expectedGeneration != sessionGenerationRef.get()) {
                return RuntimeProtocol.SURFACE_SESSION_MISMATCH
            }
            if (enabled && (!isValidProjectId(projectId) || !overlayController.canShow())) {
                captureOverlayEnabled.set(false)
                overlayController.hide()
                return RuntimeProtocol.SURFACE_PERMISSION_DENIED
            }
            captureOverlayEnabled.set(enabled)
            if (!enabled) {
                if (currentRuntimeState() !in setOf(RuntimeProtocol.STATE_RUNNING, RuntimeProtocol.STATE_PAUSED, RuntimeProtocol.STATE_STOPPING)) {
                    finishForegroundRun()
                }
                return RuntimeProtocol.SURFACE_ACCEPTED
            }
            lastProjectIdRef.set(projectId)
            return if (ensureForegroundForRun()) {
                overlayController.showCapture(currentRuntimeState())
                RuntimeProtocol.SURFACE_ACCEPTED
            } else {
                captureOverlayEnabled.set(false)
                RuntimeProtocol.SURFACE_PERMISSION_DENIED
            }
        }

        override fun capturePreview(
            requestId: Long,
            expectedGeneration: Long,
        ): ParcelFileDescriptor? {
            if (expectedGeneration != sessionGenerationRef.get()) {
                appendPreviewRuntimeLog("截图预览失败：Runner会话已重建")
                return null
            }
            if (rootStateRef.get() != RootDaemonController.State.READY) {
                appendPreviewRuntimeLog("截图预览失败：Root后端尚未就绪")
                return null
            }
            if (currentRuntimeState() !in setOf(RuntimeProtocol.STATE_IDLE, RuntimeProtocol.STATE_STOPPED, RuntimeProtocol.STATE_FAILED)) {
                appendPreviewRuntimeLog("截图预览失败：脚本运行时不能占用截图通道")
                return null
            }
            val handle = nativeHandleRef.get()
            if (handle == 0L) {
                appendPreviewRuntimeLog("截图预览失败：Native会话不可用")
                return null
            }
            val raw = NativeEngineBridge.nativeCapturePreview(handle)
            if (raw == null) {
                appendPreviewRuntimeLog("截图预览失败：Root后端拒绝截图或连接已断开")
                return null
            }
            return runCatching { writePreviewCapture(raw) }
                .onSuccess { appendPreviewRuntimeLog("截图预览已生成") }
                .onFailure { appendPreviewRuntimeLog("截图预览失败：画面格式无效或超出预览预算") }
                .getOrNull()
        }

        override fun getRecentRuntimeLogs(
            expectedGeneration: Long,
            maximumEntries: Int,
        ): Array<String> {
            if (expectedGeneration != sessionGenerationRef.get() || maximumEntries !in 1..100) {
                return emptyArray()
            }
            return synchronized(runtimeLogLock) {
                runtimeLogs.toList().takeLast(maximumEntries).toTypedArray()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        engineThread = HandlerThread("autoscript-engine-control").apply { start() }
        engineHandler = Handler(engineThread.looper)
        scriptUiController = RuntimeScriptUiController(this,
            onEvent = { token, id, event, value, dispatch ->
                if (pendingScriptUiEvents.incrementAndGet() > 64) {
                    pendingScriptUiEvents.decrementAndGet()
                    requestScriptUiStop()
                } else engineHandler.post {
                try {
                if (scriptUiController.accepts(token) && currentRuntimeState() in setOf(RuntimeProtocol.STATE_RUNNING, RuntimeProtocol.STATE_PAUSED)) {
                    val handle = nativeHandleRef.get()
                    if (handle != 0L) {
                        if (NativeEngineBridge.nativePushUiEvent(handle, id, event, value, dispatch) == 0) {
                            engineHandler.removeCallbacks(pump); engineHandler.post(pump)
                        } else {
                            stopNativeEngine()
                        }
                    }
                }
                } finally { pendingScriptUiEvents.decrementAndGet() }
            } },
            onStop = { requestScriptUiStop() })
        overlayController = RuntimeOverlayController(
            this,
            onPause = { engineHandler.post { pauseNativeEngine() } },
            onResume = { engineHandler.post { resumeNativeEngine() } },
            onStop = { engineHandler.post { stopNativeEngine() } },
            onCapture = { engineHandler.post(::captureOverlayForStudio) },
            onPromptClosed = { engineHandler.post {
                promptSessionActive.set(false)
                if (currentRuntimeState() !in setOf(RuntimeProtocol.STATE_RUNNING, RuntimeProtocol.STATE_PAUSED,
                        RuntimeProtocol.STATE_STOPPING)) finishForegroundRun()
            } },
            onToastExpired = { engineHandler.post {
                toastSessionActive.set(false)
                if (currentRuntimeState() !in setOf(RuntimeProtocol.STATE_RUNNING, RuntimeProtocol.STATE_PAUSED,
                        RuntimeProtocol.STATE_STOPPING)) finishForegroundRun()
            } },
        )
        inputPickOverlay = RuntimeInputPointOverlay(this, ::completeInputPick)
        val displaySize = currentDisplaySize()
        nativeHandleRef.set(NativeEngineBridge.nativeCreate(displaySize.x, displaySize.y))
        sessionGenerationRef.set(newSessionGeneration())
        lastNotifiedStateRef.set(currentRuntimeState())
        lastNotifiedRootStateRef.set(currentRootStateCode())
        rootDaemonController = RootDaemonController(this, engineHandler, ::updateRootState)
        val handle = nativeHandleRef.get()
        if (handle != 0L) {
            nativeVisionAdapter = NativeVisionAdapter(assets)
            if (NativeEngineBridge.nativeSetVisionListener(handle, nativeVisionAdapter) != 0) {
                updateRootState(RootDaemonController.State.FAILED)
                return
            }
            nativeWakeListener = NativeWakeListener(
                engineHandler,
                pump,
                rootDaemonController::onNativeDisconnected,
            )
            if (NativeEngineBridge.nativeSetWakeListener(handle, nativeWakeListener) == 0) {
                engineHandler.post { rootDaemonController.start(handle) }
            } else {
                updateRootState(RootDaemonController.State.FAILED)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_SCRIPT -> engineHandler.post {
                if (!stopNativeEngine()) updateForegroundNotification()
            }
            ACTION_PAUSE_SCRIPT -> engineHandler.post { pauseNativeEngine() }
            ACTION_RESUME_SCRIPT -> engineHandler.post { resumeNativeEngine() }
            ACTION_KEEP_ALIVE, null -> {
                if (foregroundActive.get()) updateForegroundNotification()
            }
        }
        return START_NOT_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        scriptUiController.onConfigurationChanged()
        inputPickHandler.post { inputPickSession?.let { cancelInputPick(it.requestId, "屏幕方向或尺寸变化，请重新选点") } }
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
        scriptUiController.clear()
        inputPickSession?.let { cancelInputPick(it.requestId, "Runner会话结束") }
        inputPickHandler.removeCallbacksAndMessages(null)
        if (::inputPickOverlay.isInitialized) inputPickOverlay.hide()
        overlayController.hide()
        removeForegroundNotification()
        engineHandler.removeCallbacksAndMessages(null)
        val handle = nativeHandleRef.getAndSet(0L)
        if (handle != 0L) {
            rootDaemonController.stop()
            NativeEngineBridge.nativeStop(handle, SystemClock.elapsedRealtimeNanos())
            NativeEngineBridge.nativeDestroy(handle)
        }
        if (::nativeVisionAdapter.isInitialized) nativeVisionAdapter.close()
        engineThread.quitSafely()
        stateListeners.kill()
        super.onDestroy()
    }

    private fun currentRuntimeState(): Int {
        val handle = nativeHandleRef.get()
        return if (handle == 0L) RuntimeProtocol.STATE_FAILED
        else NativeEngineBridge.nativeState(handle)
    }

    private fun currentRootStateCode(): Int = when (rootStateRef.get()) {
        RootDaemonController.State.STOPPED -> RuntimeProtocol.ROOT_STOPPED
        RootDaemonController.State.STARTING -> RuntimeProtocol.ROOT_STARTING
        RootDaemonController.State.READY -> RuntimeProtocol.ROOT_READY
        RootDaemonController.State.FAILED -> RuntimeProtocol.ROOT_FAILED
    }

    private fun updateRootState(state: RootDaemonController.State) {
        rootStateRef.set(state)
        if (state != RootDaemonController.State.READY) inputPickHandler.post {
            inputPickSession?.let { cancelInputPick(it.requestId, "Root连接已断开，请重新选点") }
        }
        notifyRuntimeStateIfChanged()
    }

    @Synchronized
    private fun notifyRuntimeStateIfChanged() {
        val state = currentRuntimeState()
        val rootState = currentRootStateCode()
        if (inputPickActive.get() && (rootState != RuntimeProtocol.ROOT_READY ||
            state in setOf(RuntimeProtocol.STATE_RUNNING, RuntimeProtocol.STATE_PAUSED, RuntimeProtocol.STATE_STOPPING))) {
            inputPickHandler.post { inputPickSession?.let { cancelInputPick(it.requestId, "运行状态变化，选点已取消") } }
        }
        val stepPause = state == RuntimeProtocol.STATE_PAUSED && stepControlPending.getAndSet(false)
        if (state in setOf(RuntimeProtocol.STATE_STOPPED, RuntimeProtocol.STATE_FAILED, RuntimeProtocol.STATE_STOPPING)) stepControlPending.set(false)
        if (!shouldPublishRuntimeState(state, rootState, lastNotifiedStateRef.get(), lastNotifiedRootStateRef.get(), stepPause)) return
        lastNotifiedStateRef.set(state)
        lastNotifiedRootStateRef.set(rootState)
        appendRuntimeLog(state, rootState)
        val generation = sessionGenerationRef.get()
        val count = stateListeners.beginBroadcast()
        try {
            for (index in 0 until count) {
                runCatching {
                    stateListeners.getBroadcastItem(index)
                        .onRuntimeStateChanged(generation, state, rootState, currentRuntimeDiagnostic())
                }
            }
        } finally {
            stateListeners.finishBroadcast()
        }
        when (state) {
            RuntimeProtocol.STATE_STOPPED, RuntimeProtocol.STATE_FAILED -> {
                finishForegroundRun()
            }
            RuntimeProtocol.STATE_RUNNING,
            RuntimeProtocol.STATE_PAUSED,
            RuntimeProtocol.STATE_STOPPING,
            -> {
                updateForegroundNotification()
                if (floatingControlEnabled.get()) overlayController.show(state)
            }
        }
    }

    private fun pauseNativeEngine(): Boolean {
        val handle = nativeHandleRef.get()
        if (handle == 0L || currentRuntimeState() != RuntimeProtocol.STATE_RUNNING) return false
        val accepted = NativeEngineBridge.nativePause(
            handle,
            SystemClock.elapsedRealtimeNanos(),
        ) == 0
        if (accepted) {
            engineHandler.removeCallbacks(pump)
            engineHandler.post(pump)
        }
        return accepted
    }

    private fun resumeNativeEngine(): Boolean {
        val handle = nativeHandleRef.get()
        if (handle == 0L || currentRuntimeState() != RuntimeProtocol.STATE_PAUSED) return false
        val accepted = NativeEngineBridge.nativeResume(
            handle,
            SystemClock.elapsedRealtimeNanos(),
        ) == 0
        if (accepted) {
            engineHandler.removeCallbacks(pump)
            engineHandler.post(pump)
        }
        return accepted
    }

    /** Publish the native priority flag immediately, never queue Stop behind UI business events. */
    private fun requestScriptUiStop() {
        val generation = sessionGenerationRef.get()
        val handle = nativeHandleRef.get()
        scriptUiController.clear()
        if (handle != 0L) NativeEngineBridge.nativeStop(handle, SystemClock.elapsedRealtimeNanos())
        engineHandler.postAtFrontOfQueue {
            if (generation == sessionGenerationRef.get()) stopNativeEngine()
        }
    }

    private fun stopNativeEngine(): Boolean {
        scriptUiController.clear()
        inputPickHandler.post { inputPickSession?.let { cancelInputPick(it.requestId, "运行已停止，选点已取消") } }
        engineHandler.removeCallbacks(pump)
        engineHandler.removeCallbacks(stopStateObserver)
        val handle = nativeHandleRef.get()
        if (handle == 0L) {
            finishForegroundRun()
            return true
        }
        val stopped = NativeEngineBridge.nativeStop(
            handle,
            SystemClock.elapsedRealtimeNanos(),
        ) == 0
        if (stopped) {
            notifyRuntimeStateIfChanged()
            engineHandler.post(stopStateObserver)
        }
        return stopped
    }

    @Synchronized
    private fun ensureForegroundForRun(): Boolean {
        if (foregroundActive.get()) {
            updateForegroundNotification()
            return true
        }
        val keepAliveIntent = Intent(this, AutomationRuntimeService::class.java)
            .setAction(ACTION_KEEP_ALIVE)
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(keepAliveIntent)
            } else {
                startService(keepAliveIntent)
            }
            startForeground(NOTIFICATION_ID, buildRuntimeNotification(RuntimeProtocol.STATE_IDLE))
            foregroundActive.set(true)
            if (floatingControlEnabled.get()) overlayController.show(RuntimeProtocol.STATE_IDLE)
            true
        }.getOrElse {
            foregroundActive.set(false)
            runCatching { stopService(keepAliveIntent) }
            false
        }
    }

    private fun updateForegroundNotification() {
        if (!foregroundActive.get()) return
        runCatching {
            getSystemService(NotificationManager::class.java).notify(
                NOTIFICATION_ID,
                buildRuntimeNotification(currentRuntimeState()),
            )
        }
    }

    @Synchronized
    private fun finishForegroundRun() {
        scriptUiController.clear()
        if (inputPickActive.get()) { updateForegroundNotification(); return }
        if ((promptSessionActive.get() || toastSessionActive.get()) && overlayController.canShow()) {
            overlayController.finishWithRunPrompt()
            updateForegroundNotification()
            return
        }
        if (!foregroundActive.getAndSet(false)) return
        if (captureOverlayEnabled.get()) {
            foregroundActive.set(true)
            overlayController.showCapture(currentRuntimeState())
            updateForegroundNotification()
            return
        }
        overlayController.hide()
        removeForegroundNotification()
        stopSelf()
    }

    private fun removeForegroundNotification() {
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        runCatching {
            getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            "脚本运行状态",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "显示本地自动化脚本的运行状态和停止入口"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildRuntimeNotification(state: Int): Notification {
        val stopIntent = Intent(this, AutomationRuntimeService::class.java)
            .setAction(ACTION_STOP_SCRIPT)
        val stopPendingIntent = PendingIntent.getService(
            this,
            STOP_REQUEST_CODE,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val pauseAction = if (state == RuntimeProtocol.STATE_PAUSED) {
            ACTION_RESUME_SCRIPT to "继续脚本"
        } else {
            ACTION_PAUSE_SCRIPT to "暂停脚本"
        }
        val pausePendingIntent = PendingIntent.getService(
            this,
            PAUSE_REQUEST_CODE,
            Intent(this, AutomationRuntimeService::class.java).setAction(pauseAction.first),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val contentIntent = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(
                this,
                CONTENT_REQUEST_CODE,
                it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
        val status = when (state) {
            RuntimeProtocol.STATE_RUNNING -> "脚本运行中"
            RuntimeProtocol.STATE_PAUSED -> "脚本已暂停"
            RuntimeProtocol.STATE_STOPPING -> "脚本停止中"
            else -> if (captureOverlayEnabled.get()) "截图工具已就绪" else "正在启动脚本"
        }
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(applicationInfo.loadLabel(packageManager))
            .setContentText(status)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setContentIntent(contentIntent)
            .apply {
                if (state == RuntimeProtocol.STATE_RUNNING || state == RuntimeProtocol.STATE_PAUSED) {
                    val icon = if (state == RuntimeProtocol.STATE_PAUSED) {
                        android.R.drawable.ic_media_play
                    } else {
                        android.R.drawable.ic_media_pause
                    }
                    addAction(icon, pauseAction.second, pausePendingIntent)
                }
                addAction(android.R.drawable.ic_delete, "停止脚本", stopPendingIntent)
            }
            .build()
    }

    private fun appendRuntimeLog(state: Int, rootState: Int) {
        val entry = "${SystemClock.elapsedRealtime()}ms · Runner状态 · ${runtimeStateName(state)} · " +
            "Root ${rootStateName(rootState)}"
        synchronized(runtimeLogLock) {
            while (runtimeLogs.size >= MAX_RUNTIME_LOG_ENTRIES) runtimeLogs.removeFirst()
            runtimeLogs.addLast(entry.take(MAX_RUNTIME_LOG_LENGTH))
            if (state == RuntimeProtocol.STATE_FAILED) {
                currentRuntimeDiagnostic()?.let { diagnostic ->
                    while (runtimeLogs.size >= MAX_RUNTIME_LOG_ENTRIES) runtimeLogs.removeFirst()
                    runtimeLogs.addLast("Runner诊断 · ${diagnostic.take(MAX_RUNTIME_LOG_LENGTH - 8)}")
                }
            }
        }
    }

    private fun drainNativeScriptLogs(handle: Long) {
        NativeEngineBridge.nativeDrainScriptLogs(handle)
            .asSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .take(MAX_SCRIPT_LOG_BATCH)
            .forEach(::appendScriptRuntimeLog)
    }

    private fun drainNativeScriptPrompts(handle: Long) {
        val prompts = NativeEngineBridge.nativeDrainScriptPrompts(handle)
            .asSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .take(MAX_SCRIPT_PROMPT_BATCH)
            .toList()
        if (prompts.isEmpty()) return
        prompts.forEach { payload ->
            val event = runCatching { JSONObject(payload) }.getOrNull()
            if (event?.optString("kind") == "ui") { scriptUiController.command(event); return@forEach }
            val message = event?.optString("message") ?: payload
            if (message.isBlank()) return@forEach
            when (event?.optString("kind") ?: "window") {
                "window" -> {
                    if (!overlayController.acceptsRunPrompts()) return@forEach
                    if (overlayController.canShow()) promptSessionActive.set(true)
                    overlayController.showRunPrompt(message)
                }
                "toast" -> {
                    val style = event?.optJSONObject("style")?.let(RuntimePopupStyle::parse)
                        ?: return@forEach
                    if (overlayController.canShow()) toastSessionActive.set(true)
                    overlayController.showPopupToast(message, style)
                }
            }
        }
    }

    private fun appendScriptRuntimeLog(line: String) {
        val entry = "${SystemClock.elapsedRealtime()}ms · 脚本调试日志 · ${line.take(MAX_RUNTIME_LOG_LENGTH - 24)}"
        synchronized(runtimeLogLock) {
            while (runtimeLogs.size >= MAX_RUNTIME_LOG_ENTRIES) runtimeLogs.removeFirst()
            runtimeLogs.addLast(entry)
        }
    }

    private fun appendPreviewRuntimeLog(message: String) {
        val entry = "${SystemClock.elapsedRealtime()}ms · Runner诊断 · ${message.take(MAX_RUNTIME_LOG_LENGTH - 20)}"
        synchronized(runtimeLogLock) {
            while (runtimeLogs.size >= MAX_RUNTIME_LOG_ENTRIES) runtimeLogs.removeFirst()
            runtimeLogs.addLast(entry)
        }
    }

    private fun runtimeStateName(state: Int): String = when (state) {
        RuntimeProtocol.STATE_IDLE -> "空闲"
        RuntimeProtocol.STATE_RUNNING -> "运行中"
        RuntimeProtocol.STATE_PAUSED -> "已暂停"
        RuntimeProtocol.STATE_STOPPING -> "停止中"
        RuntimeProtocol.STATE_STOPPED -> "已停止"
        RuntimeProtocol.STATE_FAILED -> "失败"
        else -> "未知"
    }

    private fun rootStateName(state: Int): String = when (state) {
        RuntimeProtocol.ROOT_STOPPED -> "未启动"
        RuntimeProtocol.ROOT_STARTING -> "启动中"
        RuntimeProtocol.ROOT_READY -> "已认证"
        RuntimeProtocol.ROOT_FAILED -> "失败"
        else -> "未知"
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

    private val previewRecognitionBusy = AtomicBoolean(false)

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

    /** Converts a bounded JNI raw frame into a PNG FD so Binder never carries image bytes. */
    private fun writePreviewCapture(raw: ByteArray): ParcelFileDescriptor {
        require(raw.size >= PREVIEW_HEADER_BYTES)
        val output = File.createTempFile("runtime-preview-", ".png", cacheDir)
        return try {
            writePreviewCaptureToFile(raw, output)
            ParcelFileDescriptor.open(output, ParcelFileDescriptor.MODE_READ_ONLY)
        } finally {
            output.delete()
        }
    }

    /** Writes the one-shot Studio handoff directly, avoiding an unnecessary FD-to-file copy. */
    private fun writePreviewCaptureToFile(raw: ByteArray, output: File) {
        require(raw.size >= PREVIEW_HEADER_BYTES)
        val width = readLittleEndianInt(raw, 0)
        val height = readLittleEndianInt(raw, 4)
        val rowStride = readLittleEndianInt(raw, 8)
        val format = raw[12].toInt() and 0xff
        val pixelCount = width.toLong() * height.toLong()
        val expectedPixels = rowStride.toLong() * height.toLong()
        require(width in 1..MAX_PREVIEW_DIMENSION && height in 1..MAX_PREVIEW_DIMENSION)
        require(pixelCount in 1..MAX_PREVIEW_PIXELS)
        require(rowStride >= width * 4)
        require(format == PREVIEW_RGBA || format == PREVIEW_BGRA)
        require(expectedPixels <= MAX_PREVIEW_RAW_BYTES)
        require(raw.size.toLong() == PREVIEW_HEADER_BYTES + expectedPixels)

        val colors = IntArray(pixelCount.toInt())
        for (y in 0 until height) {
            var source = PREVIEW_HEADER_BYTES + y * rowStride
            var target = y * width
            repeat(width) {
                val first = raw[source].toInt() and 0xff
                val second = raw[source + 1].toInt() and 0xff
                val third = raw[source + 2].toInt() and 0xff
                val alpha = raw[source + 3].toInt() and 0xff
                colors[target] = if (format == PREVIEW_RGBA) {
                    (alpha shl 24) or (first shl 16) or (second shl 8) or third
                } else {
                    (alpha shl 24) or (third shl 16) or (second shl 8) or first
                }
                source += 4
                target += 1
            }
        }
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            bitmap.setPixels(colors, 0, width, 0, 0, width, height)
            FileOutputStream(output).use { stream ->
                require(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream))
                stream.flush()
                stream.fd.sync()
            }
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * Captures while another app is visible, then hands a one-shot private PNG to Studio.
     * This runs on the engine thread, the same serialization point used for Root operations.
     */
    private fun captureOverlayForStudio() {
        overlayController.showCaptureMessage("正在截图…")
        val projectId = lastProjectIdRef.get()
        if (projectId == null) {
            reportOverlayCaptureFailure("当前没有可返回的 Studio 项目")
            return
        }
        if (rootStateRef.get() != RootDaemonController.State.READY) {
            reportOverlayCaptureFailure("Root 后端尚未就绪")
            return
        }
        if (currentRuntimeState() !in setOf(
                RuntimeProtocol.STATE_IDLE,
                RuntimeProtocol.STATE_STOPPED,
                RuntimeProtocol.STATE_FAILED,
            )
        ) {
            reportOverlayCaptureFailure("请先停止脚本再截图")
            return
        }
        val handle = nativeHandleRef.get()
        val raw = handle.takeIf { it != 0L }?.let(NativeEngineBridge::nativeCapturePreview)
        if (raw == null) {
            reportOverlayCaptureFailure("Root 后端拒绝截图或连接已断开")
            return
        }
        val width = readLittleEndianInt(raw, 0)
        val height = readLittleEndianInt(raw, 4)
        val token = newCaptureToken()
        val handoffDirectory = File(cacheDir, CAPTURE_HANDOFF_DIRECTORY)
        val output = File(handoffDirectory, "$token.png")
        try {
            require(handoffDirectory.exists() || handoffDirectory.mkdirs())
            require(handoffDirectory.isDirectory)
            writePreviewCaptureToFile(raw, output)
            require(output.length() in 1..MAX_PREVIEW_FILE_BYTES)
            startActivity(
                Intent(RuntimeProtocol.ACTION_OPEN_CAPTURE_EDITOR)
                    .setPackage(packageName)
                    .putExtra(RuntimeProtocol.EXTRA_CAPTURE_PROJECT_ID, projectId)
                    .putExtra(RuntimeProtocol.EXTRA_CAPTURE_TOKEN, token)
                    .putExtra(RuntimeProtocol.EXTRA_CAPTURE_WIDTH, width)
                    .putExtra(RuntimeProtocol.EXTRA_CAPTURE_HEIGHT, height)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            )
            appendPreviewRuntimeLog("截图已交给 Studio 图像工具")
            // This is a one-shot capture session. Studio owns the decoded bitmap from here on.
            captureOverlayEnabled.set(false)
            finishForegroundRun()
        } catch (failure: Exception) {
            output.delete()
            val detail = failure.message?.replace(Regex("\\s+"), " ")?.take(120)
            reportOverlayCaptureFailure(
                "${failure.javaClass.simpleName}${detail?.let { "：$it" }.orEmpty()}",
            )
        }
    }

    private fun reportOverlayCaptureFailure(reason: String) {
        appendPreviewRuntimeLog("截图失败：$reason")
        overlayController.showCaptureMessage("截图失败：$reason")
    }

    private fun newCaptureToken(): String {
        val bytes = ByteArray(CAPTURE_TOKEN_BYTES).also(sessionRandom::nextBytes)
        return bytes.joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    private fun cancelInputPick(requestId: Long, message: String) {
        val current = inputPickSession ?: return
        if (requestId != current.requestId) return
        completeInputPick(InputPointPickReply(requestId, InputPointPickReply.CANCELLED, current.action, message = message))
    }

    private fun completeInputPick(reply: InputPointPickReply) {
        val current = inputPickSession ?: return
        if (reply.requestId != current.requestId) return
        inputPickSession = null
        inputPickHandler.removeCallbacks(current.expiry)
        runCatching { current.listener.asBinder().unlinkToDeath(current.death, 0) }
        inputPickOverlay.hide()
        inputPickActive.set(false)
        val checked = if (reply.status == InputPointPickReply.SUCCESS &&
            (current.generation != sessionGenerationRef.get() || !reply.validFor(current.requestId, binder.getInputFeatures(current.generation)))
        ) reply.copy(status = InputPointPickReply.FAILED, message = "选点结果已失效，请重新选择") else reply
        val delivered = runCatching { current.listener.onFinished(checked) }.isSuccess
        if (delivered && checked.status == InputPointPickReply.SUCCESS) {
            // Restore the existing editor Activity; the result travels only over the bound callback.
            packageManager.getLaunchIntentForPackage(packageName)?.let { launch ->
                runCatching { startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)) }
            }
        }
        finishForegroundRun()
    }

    private fun readLittleEndianInt(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or
            ((bytes[offset + 1].toInt() and 0xff) shl 8) or
            ((bytes[offset + 2].toInt() and 0xff) shl 16) or
            ((bytes[offset + 3].toInt() and 0xff) shl 24)

    private fun isValidTemplatePath(path: String): Boolean =
        path.length in 1..MAX_TEMPLATE_PATH_LENGTH &&
            path.startsWith("assets/images/") &&
            '\\' !in path &&
            '\u0000' !in path &&
            path.split('/').all { it.isNotEmpty() && it != "." && it != ".." }

    private fun isValidProjectId(value: String): Boolean =
        PROJECT_ID.matches(value)

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

    private fun validCapabilities(capabilities: Array<out String>): Boolean =
        capabilities.size <= MAX_PROJECT_CAPABILITIES &&
            capabilities.toSet().size == capabilities.size &&
            capabilities.all { it.length <= MAX_CAPABILITY_LENGTH && CAPABILITY.matches(it) }

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
        const val MAX_PREVIEW_DIMENSION = 8_192
        const val MAX_PREVIEW_PIXELS = 4_800_000L
        const val MAX_PREVIEW_RAW_BYTES = 20L * 1024 * 1024
        const val MAX_PREVIEW_FILE_BYTES = 24L * 1024 * 1024
        const val PREVIEW_COPY_BUFFER_BYTES = 32 * 1024
        const val PREVIEW_HEADER_BYTES = 16
        const val PREVIEW_RGBA = 1
        const val PREVIEW_BGRA = 2
        const val MAX_DICTIONARY_BYTES = 8 * 1024 * 1024
        const val MAX_FLOW_BYTES = 64 * 1024 * 1024
        const val MAX_RUNTIME_DIAGNOSTIC_LENGTH = 4_096
        const val MAX_PROJECT_CAPABILITIES = 64
        const val MAX_CAPABILITY_LENGTH = 128
        const val MAX_DICTIONARY_PATH_LENGTH = 256
        const val DICTIONARY_READ_BUFFER_BYTES = 32 * 1024
        const val FLOW_READ_BUFFER_BYTES = 64 * 1024
        const val NANOS_PER_MILLISECOND = 1_000_000L
        const val STOP_STATE_OBSERVER_DELAY_MILLIS = 16L
        const val NEXT_RUNNABLE = 0L
        const val NEXT_IDLE = -1L
        const val NEXT_STOPPED = -2L
        const val UNPUBLISHED_STATE = Int.MIN_VALUE
        const val NOTIFICATION_CHANNEL_ID = "autoscript_runtime"
        const val NOTIFICATION_ID = 0x4153
        const val CONTENT_REQUEST_CODE = 0x4153
        const val STOP_REQUEST_CODE = 0x4154
        const val PAUSE_REQUEST_CODE = 0x4155
        const val ACTION_KEEP_ALIVE = "com.autoscript.runtime.action.KEEP_ALIVE"
        const val ACTION_STOP_SCRIPT = "com.autoscript.runtime.action.STOP_SCRIPT"
        const val ACTION_PAUSE_SCRIPT = "com.autoscript.runtime.action.PAUSE_SCRIPT"
        const val ACTION_RESUME_SCRIPT = "com.autoscript.runtime.action.RESUME_SCRIPT"
        const val MAX_RUNTIME_LOG_ENTRIES = 200
        const val MAX_RUNTIME_LOG_LENGTH = 512
        const val MAX_SCRIPT_LOG_BATCH = 50
        const val MAX_SCRIPT_PROMPT_BATCH = 64
        const val CAPTURE_HANDOFF_DIRECTORY = "capture-handoffs"
        const val CAPTURE_TOKEN_BYTES = 16
        val PROJECT_ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")
        val FLOW_ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")
        val CAPABILITY = Regex("[a-z][a-z0-9]*(\\.[a-z][a-z0-9]*)+")
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

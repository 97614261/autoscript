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
import com.autoscript.runtime.api.RuntimeLogWriter
import com.autoscript.runtime.api.RuntimeScriptLog
import com.autoscript.runtime.api.RuntimeScriptLogState
import com.autoscript.runtime.api.TemplateMatchReply
import com.autoscript.runtime.api.RecognitionPreviewReply
import com.autoscript.runtime.api.RecordingReply
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
    private val rootStateRef = AtomicReference(RootConnectionState.STOPPED)
    private val rootFailureCode = AtomicReference<AppDiagnosticCode?>(null)
    private val rootConnectionRequestLock = Any()
    private val rootConnectionAvailable = AtomicBoolean(false)
    private val appDiagnostics by lazy {
        RuntimeAppDiagnostics(File(filesDir, "app-diagnostics")) { line ->
            android.util.Log.i("ZhigouDiagnostics", line)
            synchronized(runtimeLogLock) {
                while (runtimeLogs.size >= MAX_RUNTIME_LOG_ENTRIES) runtimeLogs.removeFirst()
                runtimeLogs.addLast(line)
            }
        }
    }
    private val stateListeners = RemoteCallbackList<IRuntimeStateListener>()
    private val lastNotifiedStateRef = AtomicInteger(UNPUBLISHED_STATE)
    private val lastNotifiedRootStateRef = AtomicInteger(UNPUBLISHED_STATE)
    private val stepControlPending = AtomicBoolean(false)
    private val foregroundActive = AtomicBoolean(false)
    private val floatingControlEnabled = AtomicBoolean(true)
    private val captureOverlayEnabled = AtomicBoolean(false)
    private val captureOverlayRevision = AtomicLong(0)
    private data class PendingOverlayCapture(val generation: Long, val projectId: String, val revision: Long)
    private var pendingOverlayCapture: PendingOverlayCapture? = null
    /** The only Studio-specific context retained by Runner; it is an opaque validated ID. */
    private val lastProjectIdRef = AtomicReference<String?>(null)
    private val debugSession = RuntimeDebugSession()
    private val debugReadPending = AtomicBoolean(false)
    private var pendingExecutionPosition: com.autoscript.runtime.api.RuntimeExecutionPosition? = null
    private var publishedExecutionPosition: com.autoscript.runtime.api.RuntimeExecutionPosition? = null
    private var executionPositionScheduled = false
    private var executionPositionSequence = 0L
    private val publishExecutionPosition = Runnable { publishLatestExecutionPosition() }
    private val runtimeLogs = ArrayDeque<String>(MAX_RUNTIME_LOG_ENTRIES)
    private val runtimeLogLock = Any()
    private val runStopRequested = AtomicBoolean(false)
    private data class FrozenRun(
        val generation: Long, val handle: Long, val projectId: String, val source: ByteArray,
        val width: Int, val height: Int, val scale: Int, val capabilities: Array<String>, val uiValues: String, val uiToken: Long,
        val restarts: Int = 0,
    )
    private val frozenRun = AtomicReference<FrozenRun?>(null)
    private val restartRequested = AtomicBoolean(false)
    private val runLogWriter by lazy { RuntimeLogWriter(filesDir, elapsedMillis = SystemClock::elapsedRealtime) }
    private val scriptLogState = RuntimeScriptLogState()
    private val rootReadyAtElapsedMs = AtomicLong(0L)
    private val sessionRandom = SecureRandom()
    private lateinit var engineThread: HandlerThread
    private lateinit var engineHandler: Handler
    private lateinit var scriptUiController: RuntimeScriptUiController
    private val pendingScriptUiEvents = AtomicInteger()
    private lateinit var rootDaemonController: RootDaemonController
    private lateinit var nativeWakeListener: NativeWakeListener
    private lateinit var overlayController: RuntimeOverlayController
    private lateinit var nativeVisionAdapter: NativeVisionAdapter
    private val visionResolver by lazy { androidRuntimeVisionResolver(this) }
    private val visionPreparation = RuntimeVisionPreparation(prepare = { kind, work -> visionResolver.prepare(kind, work) })
    private lateinit var nativeScriptUiAdapter: NativeScriptUiAdapter
    private lateinit var nativeDataFileAdapter: NativeDataFileAdapter
    private val audioResources by lazy { RuntimeAudioResources(File(cacheDir, "runtime-audio")) }
    private val floatingImages=RuntimeFloatingImages()
    private val projectStartLock = Any()
    private val inputPickActive = AtomicBoolean(false)
    private val inputPickHandler = Handler(Looper.getMainLooper())
    private lateinit var inputPickOverlay: RuntimeInputPointOverlay
    private lateinit var inputRecording: RuntimeInputRecordingController
    private lateinit var scriptRecording: RuntimeInputRecordingController
    private lateinit var runtimeTools: RuntimeToolsController
    private val recordingStarting = AtomicBoolean(false)
    // Main-thread-only session state; the atomic flag bounds pending Binder starts to one.
    private var inputPickSession: InputPickSession? = null
    private data class InputPickSession(
        val requestId: Long, val generation: Long, val projectId: String, val flowId: String,
        val action: String, val features: Int, val listener: IInputPointPickListener,
        val death: IBinder.DeathRecipient, val expiry: Runnable,
    )

    private val pump = object : Runnable {
        override fun run() {
            runCoalescedRuntimePump({ engineHandler.removeCallbacks(this) }) {
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
                drainNativeRunEvents(handle)
                drainNativeScriptPrompts(handle)
                stageExecutionPosition(handle)
                notifyRuntimeStateIfChanged()
                scheduleFromNative(handle, now)
            }
        }
    }

    private val stopStateObserver = object : Runnable {
        override fun run() {
            val handle = nativeHandleRef.get()
            if (handle == 0L) return
            observeRuntimeStop(::notifyRuntimeStateIfChanged) {
                engineHandler.postDelayed(this, STOP_STATE_OBSERVER_DELAY_MILLIS)
            }
        }
    }

    private val binder = object : IRuntimeService.Stub() {
        override fun requestRootConnection(expectedGeneration: Long): Int = synchronized(rootConnectionRequestLock) {
            if (Binder.getCallingUid() != Process.myUid()) return RuntimeProtocol.CONTROL_INVALID_STATE
            if (expectedGeneration != sessionGenerationRef.get()) return RuntimeProtocol.CONTROL_SESSION_MISMATCH
            if (!rootConnectionAvailable.get() || nativeHandleRef.get() == 0L) return RuntimeProtocol.CONTROL_ENGINE_ERROR
            if (rootStateRef.get() != RootConnectionState.READY && currentRuntimeState() in setOf(
                    RuntimeProtocol.STATE_RUNNING, RuntimeProtocol.STATE_PAUSED, RuntimeProtocol.STATE_STOPPING))
                return RuntimeProtocol.CONTROL_INVALID_STATE
            if (rootStateRef.get() !in setOf(RootConnectionState.STARTING, RootConnectionState.READY)) {
                rootFailureCode.set(null)
                updateRootState(RootConnectionState.STARTING)
                engineHandler.post {
                    if (rootConnectionAvailable.get() && expectedGeneration == sessionGenerationRef.get())
                        rootDaemonController.requestConnection()
                }
            }
            RuntimeProtocol.CONTROL_ACCEPTED
        }

        override fun startInputRecording(requestId: Long, expectedGeneration: Long, recordingId: Long,
            projectId: String, flowId: String, maxDurationMs: Int, eventBudget: Int): RecordingReply {
            return startObservedInputRecording(requestId, expectedGeneration, recordingId, projectId, flowId,
                maxDurationMs, eventBudget, null)
        }
        override fun startObservedInputRecording(requestId: Long, expectedGeneration: Long, recordingId: Long,
            projectId: String, flowId: String, maxDurationMs: Int, eventBudget: Int,
            listener: com.autoscript.runtime.api.IInputRecordingListener?): RecordingReply {
            fun failure(status: Int) = RecordingReply(recordingId,expectedGeneration,status)
            if (Binder.getCallingUid() != Process.myUid() || requestId <= 0 || recordingId <= 0 ||
                expectedGeneration != sessionGenerationRef.get()) return failure(RecordingReply.SESSION_MISMATCH)
            if (!recordingProjectAllowed(projectId,flowId)) return failure(RecordingReply.INVALID_EVENT)
            if (!recordingStarting.compareAndSet(false,true)) return failure(RecordingReply.BUSY)
            try {
                val allowed = synchronized(projectStartLock) { !inputPickActive.get() && !inputRecording.busy &&
                    currentRuntimeState() !in setOf(RuntimeProtocol.STATE_RUNNING,RuntimeProtocol.STATE_PAUSED,RuntimeProtocol.STATE_STOPPING) }
                if (!allowed) return failure(RecordingReply.BUSY)
                if (rootStateRef.get() != RootConnectionState.READY || getInputFeatures(expectedGeneration) and 4 == 0)
                    return failure(RecordingReply.UNSUPPORTED_DEVICE)
                if (!overlayController.canShow() || !ensureForegroundForRun()) return failure(RecordingReply.ENGINE_ERROR)
                captureOverlayEnabled.set(false); overlayController.hide()
                val size = currentDisplaySize()
                @Suppress("DEPRECATION") val rotation = getSystemService(WindowManager::class.java).defaultDisplay.rotation
                val reply = inputRecording.start(recordingId,expectedGeneration,size.x,size.y,rotation,maxDurationMs,eventBudget,
                    completion = listener?.let { callback -> { result ->
                        if (result.generation == sessionGenerationRef.get()) runCatching { callback.onFinished(result) }
                        Unit
                    } })
                if (reply.status != RecordingReply.SUCCESS) engineHandler.post(::finishForegroundRun)
                return reply
            } finally { recordingStarting.set(false) }
        }
        override fun finishInputRecording(requestId: Long, expectedGeneration: Long, recordingId: Long): RecordingReply {
            if (Binder.getCallingUid() != Process.myUid() || requestId <= 0 || recordingId <= 0 || expectedGeneration != sessionGenerationRef.get())
                return RecordingReply(recordingId,expectedGeneration,RecordingReply.SESSION_MISMATCH)
            return inputRecording.finish(recordingId,expectedGeneration)
        }
        override fun cancelInputRecording(requestId: Long, expectedGeneration: Long, recordingId: Long): Int {
            if (Binder.getCallingUid() != Process.myUid() || requestId <= 0 || recordingId <= 0) return RuntimeProtocol.CONTROL_INVALID_STATE
            if (expectedGeneration != sessionGenerationRef.get()) return RuntimeProtocol.CONTROL_SESSION_MISMATCH
            return if (inputRecording.cancel(recordingId,expectedGeneration)) RuntimeProtocol.CONTROL_ACCEPTED else RuntimeProtocol.CONTROL_INVALID_STATE
        }
        override fun testRecognitionPreview(requestId: Long, expectedGeneration: Long, operation: Int,
            frameFile: ParcelFileDescriptor, assetFile: ParcelFileDescriptor?, options: IntArray): RecognitionPreviewReply {
            if (Binder.getCallingUid() != Process.myUid() || !RecognitionPreviewReply.validOptions(operation,options)) {
                frameFile.close(); assetFile?.close()
                return RecognitionPreviewReply(requestId, expectedGeneration, RecognitionPreviewReply.INVALID_INPUT, operation)
            }
            return recognitionPreview.test(requestId, expectedGeneration, operation, frameFile, assetFile, options.copyOf())
        }
        override fun cancelRecognitionPreview(requestId: Long, expectedGeneration: Long): Int {
            if (Binder.getCallingUid() != Process.myUid() || requestId <= 0) return RuntimeProtocol.CONTROL_INVALID_STATE
            if (expectedGeneration != sessionGenerationRef.get()) return RuntimeProtocol.CONTROL_SESSION_MISMATCH
            recognitionPreview.cancel(requestId, expectedGeneration)
            return RuntimeProtocol.CONTROL_ACCEPTED
        }
        override fun beginInputPointPick(
            requestId: Long, expectedGeneration: Long, projectId: String, flowId: String,
            action: String, listener: IInputPointPickListener,
        ): Int {
            if (inputRecording.busy || recordingStarting.get()) return RuntimeProtocol.CONTROL_INVALID_STATE
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
            if (expectedGeneration == sessionGenerationRef.get() && rootStateRef.get() == RootConnectionState.READY) {
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
                replayPublishedExecutionPosition(listener)
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

        override fun compileVisualNodeTest(requestId: Long, expectedGeneration: Long, projectId: String, flowId: String, nodeId: String): VisualCompileReply {
            if (Binder.getCallingUid() != Process.myUid() || requestId <= 0 || expectedGeneration != sessionGenerationRef.get())
                return visualCompileReply(RuntimeProtocol.VISUAL_COMPILE_SESSION_MISMATCH)
            if (flowId.toByteArray().size !in 1..128 || nodeId.toByteArray().size !in 1..128)
                return visualCompileReply(RuntimeProtocol.VISUAL_COMPILE_INVALID, code = "TEST_IDENTITY", diagnostic = "测试积木身份无效")
            val directory = resolveProjectDirectory(projectId) ?: return visualCompileReply(RuntimeProtocol.VISUAL_COMPILE_INVALID, code = "PROJECT_DIRECTORY", diagnostic = "项目不存在")
            if (!NativeEngineBridge.ensureVisualCompilerLoaded()) return visualCompileReply(RuntimeProtocol.VISUAL_COMPILE_ENGINE_ERROR, code = "COMPILER_UNAVAILABLE", diagnostic = "当前应用不包含Studio编译器")
            val encoded = NativeEngineBridge.nativeCompileVisualNodeTest(directory.path, flowId, nodeId)
                ?: return visualCompileReply(RuntimeProtocol.VISUAL_COMPILE_ENGINE_ERROR, code = "JNI_FAILURE", diagnostic = "测试编译器不可用")
            if (expectedGeneration != sessionGenerationRef.get()) return visualCompileReply(RuntimeProtocol.VISUAL_COMPILE_SESSION_MISMATCH)
            return parseVisualCompileReply(encoded)
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

        override fun validateVisualDraftSet(requestId: Long, expectedGeneration: Long, projectId: String,
            draftFile: ParcelFileDescriptor): VisualCompileReply = draftFile.use { descriptor ->
            if (expectedGeneration != sessionGenerationRef.get()) return visualCompileReply(RuntimeProtocol.VISUAL_COMPILE_SESSION_MISMATCH)
            val directory = resolveProjectDirectory(projectId)
                ?: return visualCompileReply(RuntimeProtocol.VISUAL_COMPILE_INVALID, code = "PROJECT_DIRECTORY", diagnostic = "项目目录无效或不存在")
            if (!NativeEngineBridge.ensureVisualCompilerLoaded()) return visualCompileReply(RuntimeProtocol.VISUAL_COMPILE_ENGINE_ERROR,
                code = "COMPILER_UNAVAILABLE", diagnostic = "当前应用不包含 Studio 编译器")
            val bytes = runCatching { readVisualDraft(descriptor, com.autoscript.runtime.api.VisualDraftSetCodec.MAX_BYTES) }
                .getOrElse { return visualCompileReply(RuntimeProtocol.VISUAL_COMPILE_INVALID, code = "RESOURCE_LIMIT", diagnostic = "组合草稿不可读或超过资源限制") }
            val encoded = NativeEngineBridge.nativeValidateVisualDraftSet(directory.path, bytes)
                ?: return visualCompileReply(RuntimeProtocol.VISUAL_COMPILE_ENGINE_ERROR, code = "JNI_FAILURE", diagnostic = "组合校验器不可用")
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

        override fun prepareProject(requestId: Long, expectedGeneration: Long): Int = synchronized(projectStartLock) {
            if (visionPreparation.busy) return RuntimeProtocol.PREPARE_BUSY
            if (inputPickActive.get() || inputRecording.busy || scriptRecording.busy || recordingStarting.get()) return RuntimeProtocol.PREPARE_BUSY
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
                    debugSession.clear()
                    if (::nativeDataFileAdapter.isInitialized) nativeDataFileAdapter.clear()
                    audioResources.clear()
                    floatingImages.configure(false)
                    val displaySize = currentDisplaySize()
                    if (NativeEngineBridge.nativeReset(handle, displaySize.x, displaySize.y) == 0) {
                        visionPreparation.reset()
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
            val imageTicket=floatingImages.ticket()
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
            if(expectedGeneration==sessionGenerationRef.get() && currentRuntimeState()==RuntimeProtocol.STATE_IDLE)
                floatingImages.register(imageTicket,assetPath,decoded.width,decoded.height,decoded.rgba)
            return RuntimeProtocol.TEMPLATE_ACCEPTED
        }

        override fun configureFloatingResources(expectedGeneration:Long,enabled:Boolean):Int {
            if(expectedGeneration!=sessionGenerationRef.get() || currentRuntimeState()!=RuntimeProtocol.STATE_IDLE) return RuntimeProtocol.CONTROL_SESSION_MISMATCH
            floatingImages.configure(enabled)
            return RuntimeProtocol.CONTROL_ACCEPTED
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

        override fun registerAudio(requestId: Long, expectedGeneration: Long, resourcePath: String, audioFile: ParcelFileDescriptor): Int {
            fun valid() = requestId > 0 && expectedGeneration == sessionGenerationRef.get() && currentRuntimeState() == RuntimeProtocol.STATE_IDLE
            return runCatching {
                audioFile.use { descriptor ->
                    require(valid())
                    val stat = android.system.Os.fstat(descriptor.fileDescriptor)
                    require(android.system.OsConstants.S_ISREG(stat.st_mode) && stat.st_size in 1..20L * 1024 * 1024)
                    ParcelFileDescriptor.AutoCloseInputStream(ParcelFileDescriptor.dup(descriptor.fileDescriptor)).use {
                        audioResources.register(resourcePath, it, ::valid)
                    }
                }
                RuntimeProtocol.CONTROL_ACCEPTED
            }.getOrDefault(RuntimeProtocol.CONTROL_ENGINE_ERROR)
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
        ): Int = synchronized(projectStartLock) {
            if (inputPickActive.get() || previewRecognitionBusy.get() || inputRecording.busy || scriptRecording.busy || recordingStarting.get()) return RuntimeProtocol.START_BACKEND_NOT_READY
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
            if (rootStateRef.get() != RootConnectionState.READY) {
                return RuntimeProtocol.START_BACKEND_NOT_READY
            }
            val handle = nativeHandleRef.get()
            if (handle == 0L) return RuntimeProtocol.START_INVALID_SCRIPT
            if (currentRuntimeState() != RuntimeProtocol.STATE_IDLE) return RuntimeProtocol.START_INVALID_PROJECT
            val sourceDigest = java.security.MessageDigest.getInstance("SHA-256").digest(generatedLuaModule)
                .joinToString("") { "%02x".format(it.toInt() and 255) }
            if (!visionPreparation.takeReceipt(expectedGeneration, sourceDigest, visionResolver::selectionsMatch))
                return RuntimeProtocol.START_VISION_PREPARATION_REQUIRED
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
            if (!::nativeDataFileAdapter.isInitialized ||
                !nativeDataFileAdapter.configure(projectId, "storage.data" in capabilities)) {
                finishForegroundRun()
                return RuntimeProtocol.START_INVALID_PROJECT
            }
            runtimeTools.reset()
            overlayController.resetRunPrompts()
            runStopRequested.set(false)
            val displaySize = Point().also {
                @Suppress("DEPRECATION")
                getSystemService(WindowManager::class.java).defaultDisplay.getRealSize(it)
            }
            val rootReadyAt = rootReadyAtElapsedMs.get()
            runLogWriter.begin(buildMap {
                putAll(mapOf(
                    "projectId" to projectId, "device" to "${Build.MANUFACTURER} ${Build.MODEL}",
                    "androidApi" to Build.VERSION.SDK_INT.toString(), "abi" to Build.SUPPORTED_ABIS.firstOrNull().orEmpty(),
                    "screen" to "${displaySize.x}x${displaySize.y}", "design" to "${designWidth}x${designHeight}",
                    "startedElapsedMs" to SystemClock.elapsedRealtime().toString(),
                ))
                if (rootReadyAt > 0 && rootStateRef.get() == RootConnectionState.READY) {
                    val age = (SystemClock.elapsedRealtime() - rootReadyAt).coerceAtLeast(0).toString()
                    put("rootSessionAgeAtStartMs", age)
                    put("rootSessionAgeMs", age)
                }
            })
            if (NativeEngineBridge.nativeStart(
                    handle,
                    generatedLuaModule,
                    capabilities.toList().toTypedArray(),
                ) != 0
            ) {
                nativeDataFileAdapter.clear()
                appendScriptExecutionLog(RuntimeScriptLog.entry("ERROR", "脚本启动失败", SystemClock.elapsedRealtime()))
                runLogWriter.end("start_failed")
                finishForegroundRun()
                return RuntimeProtocol.START_INVALID_SCRIPT
            }
            stepControlPending.set(false)
            lastProjectIdRef.set(projectId.takeIf(String::isNotEmpty))
            restartRequested.set(false)
            frozenRun.set(FrozenRun(expectedGeneration, handle, projectId, generatedLuaModule.copyOf(),
                designWidth, designHeight, scaleMode, capabilities.toList().toTypedArray(), scriptUiController.initialValuesJson,
                scriptUiController.currentToken()))
            debugSession.started(expectedGeneration, handle, projectId)
            scriptUiController.activateRuntime()
            synchronized(runtimeLogLock) { runtimeLogs.clear() }
            appendScriptExecutionLog(scriptLogState.start(SystemClock.elapsedRealtime()))
            engineHandler.removeCallbacks(pump)
            engineHandler.post(pump)
            notifyRuntimeStateIfChanged()
            return RuntimeProtocol.START_ACCEPTED
        }

        override fun requestStop(requestId: Long, expectedGeneration: Long): Int {
            if (expectedGeneration != sessionGenerationRef.get()) {
                return RuntimeProtocol.STOP_SESSION_MISMATCH
            }
            visionPreparation.cancel()
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
            val handle = nativeHandleRef.get()
            val ticket = debugSession.ticket(expectedGeneration, handle, projectId)
                ?: return RuntimeDebugReply.unavailable(RuntimeDebugReply.SESSION_MISMATCH)
            if (!isValidProjectId(projectId) || expectedGeneration != sessionGenerationRef.get()) return RuntimeDebugReply.unavailable(RuntimeDebugReply.SESSION_MISMATCH)
            if (!debugReadPending.compareAndSet(false, true)) return RuntimeDebugReply.unavailable(RuntimeDebugReply.BUSY)
            return try { runCatching {
                val encoded = NativeEngineBridge.nativeDebugSnapshot(handle) ?: return RuntimeDebugReply.unavailable()
                require(encoded.toByteArray(Charsets.UTF_8).size <= 192 * 1024)
                val json = org.json.JSONObject(encoded)
                val rows = json.getJSONArray("variables")
                require(rows.length() <= 128)
                val result = RuntimeDebugReply(RuntimeDebugReply.SUCCESS, json.getString("flowId"), json.getString("nodeId"),
                    List(rows.length()) { index ->
                        val row = rows.getJSONObject(index)
                        RuntimeDebugValue(row.getString("scope"), row.getString("name"), row.getString("type"), row.getString("value"), row.optBoolean("truncated", false), row.optString("declaredType", ""))
                    }, json.getInt("runtimeState"), expectedGeneration, ticket.revision, json.optBoolean("variablesTruncated", false))
                if (debugSession.accepts(ticket, sessionGenerationRef.get(), nativeHandleRef.get())) result else RuntimeDebugReply.unavailable(RuntimeDebugReply.SESSION_MISMATCH)
            }.getOrElse { RuntimeDebugReply.unavailable() } } finally { debugReadPending.set(false) }
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
                overlayController.hideControl()
                return RuntimeProtocol.SURFACE_PERMISSION_DENIED
            }
            floatingControlEnabled.set(enabled)
            if (enabled) overlayController.enableControl()
            if (enabled && foregroundActive.get()) {
                overlayController.show(currentRuntimeState())
            } else if (!enabled) {
                overlayController.hideControl()
            }
            return RuntimeProtocol.SURFACE_ACCEPTED
        }

        override fun setCaptureOverlayEnabled(
            requestId: Long,
            expectedGeneration: Long,
            projectId: String,
            enabled: Boolean,
        ): Int {
            if (enabled && (inputPickActive.get() || inputRecording.busy || recordingStarting.get())) return RuntimeProtocol.CONTROL_INVALID_STATE
            if (expectedGeneration != sessionGenerationRef.get()) {
                return RuntimeProtocol.SURFACE_SESSION_MISMATCH
            }
            if (enabled && (!isValidProjectId(projectId) || !overlayController.canShow())) {
                captureOverlayEnabled.set(false)
                overlayController.hide()
                return RuntimeProtocol.SURFACE_PERMISSION_DENIED
            }
            captureOverlayRevision.incrementAndGet()
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
            appDiagnostics.record(AppDiagnosticCode.CAPTURE_REQUESTED)
            if (expectedGeneration != sessionGenerationRef.get()) {
                appDiagnostics.record(AppDiagnosticCode.CAPTURE_SESSION_CHANGED)
                appendPreviewRuntimeLog("截图预览失败：Runner会话已重建")
                return null
            }
            if (rootStateRef.get() != RootConnectionState.READY) {
                appDiagnostics.record(AppDiagnosticCode.CAPTURE_ROOT_NOT_READY)
                appendPreviewRuntimeLog("截图预览失败：Root后端尚未就绪")
                return null
            }
            if (currentRuntimeState() !in setOf(RuntimeProtocol.STATE_IDLE, RuntimeProtocol.STATE_STOPPED, RuntimeProtocol.STATE_FAILED)) {
                appDiagnostics.record(AppDiagnosticCode.CAPTURE_RUNTIME_BUSY)
                appendPreviewRuntimeLog("截图预览失败：脚本运行时不能占用截图通道")
                return null
            }
            val handle = nativeHandleRef.get()
            if (handle == 0L) {
                appDiagnostics.record(AppDiagnosticCode.CAPTURE_NATIVE_UNAVAILABLE)
                appendPreviewRuntimeLog("截图预览失败：Native会话不可用")
                return null
            }
            val capture = runCatching { NativeEngineBridge.nativeCapturePreview(handle) }
            val raw = capture.getOrNull()
            if (raw == null) {
                val code=capturePreviewFailureCode(capture.exceptionOrNull())
                appDiagnostics.record(code)
                appendPreviewRuntimeLog("截图预览失败：[${code.name}]；我的 → 运行环境 → Root / 截图诊断")
                return null
            }
            return runCatching { writePreviewCapture(raw) }
                .onSuccess { appDiagnostics.record(AppDiagnosticCode.CAPTURE_READY); appendPreviewRuntimeLog("截图预览已生成") }
                .onFailure { appDiagnostics.record(AppDiagnosticCode.CAPTURE_INVALID_FRAME); appendPreviewRuntimeLog("截图预览失败：画面格式无效或超出预览预算") }
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
                val entries = runtimeLogs.toList() + if (runLogWriter.failure) listOf("Runner诊断 · 文件日志保存不完整，请检查存储空间；实时日志仍可查看") else emptyList()
                (appDiagnostics.recent().filterNot { it in entries } + entries +
                    if (appDiagnostics.incomplete) listOf("App诊断 · 部分文件记录丢弃或写盘失败，实时诊断仍可查看") else emptyList())
                    .takeLast(maximumEntries).toTypedArray()
            }
        }

        // Cached data only; any explicit-query refresh runs on bounded provider IO.
        override fun getVisionAvailability(): Array<String> = visionResolver.availability()

        override fun isVisionPreparationActive(): Boolean = visionPreparation.busy

        override fun beginVisionPreparation(requestId: Long, expectedGeneration: Long, sourceDigest: String,
            components: Array<out String>, listener: com.autoscript.runtime.api.IVisionPreparationListener): Int = synchronized(projectStartLock) {
            val handle = nativeHandleRef.get()
            fun current() = expectedGeneration == sessionGenerationRef.get() && handle != 0L &&
                nativeHandleRef.get() == handle && currentRuntimeState() == RuntimeProtocol.STATE_IDLE &&
                rootStateRef.get() == RootConnectionState.READY && !inputPickActive.get() && !recordingStarting.get() &&
                !inputRecording.busy && !scriptRecording.busy && !previewRecognitionBusy.get()
            if (components.size > 2) return RuntimeProtocol.VISION_PREPARE_INVALID
            visionPreparation.begin(requestId, expectedGeneration, sourceDigest, components.toList(), ::current) { status, component, state ->
                runCatching { listener.onFinished(requestId, expectedGeneration, status, component, state) }
            }
        }

        override fun cancelVisionPreparation(requestId: Long, expectedGeneration: Long): Int {
            if (requestId <= 0 || expectedGeneration != sessionGenerationRef.get()) return RuntimeProtocol.VISION_PREPARE_STALE
            visionPreparation.cancel(requestId, expectedGeneration)
            return RuntimeProtocol.VISION_PREPARE_ACCEPTED
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
            onStop = { requestScriptUiStop() },
            onCapture = { engineHandler.post(::captureOverlayForStudio) },
            onPromptClosed = { engineHandler.post {
                if (currentRuntimeState() !in setOf(RuntimeProtocol.STATE_RUNNING, RuntimeProtocol.STATE_PAUSED,
                        RuntimeProtocol.STATE_STOPPING)) finishForegroundRun()
            } },
            onToastExpired = { engineHandler.post {
                if (currentRuntimeState() !in setOf(RuntimeProtocol.STATE_RUNNING, RuntimeProtocol.STATE_PAUSED,
                        RuntimeProtocol.STATE_STOPPING)) finishForegroundRun()
            } },
        )
        inputPickOverlay = RuntimeInputPointOverlay(this, ::completeInputPick)
        inputRecording = RuntimeInputRecordingController(this,nativeHandleRef::get,sessionGenerationRef::get,
            ready = { rootStateRef.get() == RootConnectionState.READY && !inputPickActive.get() &&
                currentRuntimeState() !in setOf(RuntimeProtocol.STATE_RUNNING,RuntimeProtocol.STATE_PAUSED,RuntimeProtocol.STATE_STOPPING) },
            onFinished = { engineHandler.post(::finishForegroundRun) })
        scriptRecording = RuntimeInputRecordingController(this, nativeHandleRef::get, sessionGenerationRef::get,
            ready = { rootStateRef.get() == RootConnectionState.READY && !runStopRequested.get() &&
                !inputPickActive.get() && !inputRecording.busy && currentRuntimeState() == RuntimeProtocol.STATE_RUNNING },
            onFinished = { engineHandler.post {
                if (currentRuntimeState() !in setOf(RuntimeProtocol.STATE_RUNNING, RuntimeProtocol.STATE_PAUSED,
                        RuntimeProtocol.STATE_STOPPING)) finishForegroundRun()
            } }, scripted = true)
        runtimeTools = RuntimeToolsController(this, scriptRecording, sessionGenerationRef::get,
            ready = { !runStopRequested.get() && rootStateRef.get() == RootConnectionState.READY &&
                currentRuntimeState() in setOf(RuntimeProtocol.STATE_RUNNING, RuntimeProtocol.STATE_PAUSED) },
            screen = {
                @Suppress("DEPRECATION")
                val display = getSystemService(WindowManager::class.java).defaultDisplay
                Point().also(display::getRealSize) to display.rotation
            }, openAudio = { if (it.startsWith("assets/audio/")) audioResources.open(it) else nativeDataFileAdapter.openAudio(it) }, floatingImages=floatingImages,
            inputFeatures={if(rootStateRef.get()==RootConnectionState.READY) NativeEngineBridge.nativeInputFeatures(nativeHandleRef.get()) else 0})
        val displaySize = currentDisplaySize()
        nativeHandleRef.set(NativeEngineBridge.nativeCreate(displaySize.x, displaySize.y))
        sessionGenerationRef.set(newSessionGeneration())
        lastNotifiedStateRef.set(currentRuntimeState())
        lastNotifiedRootStateRef.set(currentRootStateCode())
        appDiagnostics.loadPrevious()
        rootDaemonController = RootDaemonController(this, engineHandler, ::updateRootState) { code, detail ->
            appDiagnostics.record(code, detail)
            if (code == AppDiagnosticCode.ROOT_START || code == AppDiagnosticCode.ROOT_READY) rootFailureCode.set(null)
            else if (code !in setOf(AppDiagnosticCode.ROOT_INSTALLED_DAEMON, AppDiagnosticCode.ROOT_EXTRACTED_DAEMON,
                    AppDiagnosticCode.ROOT_RETRIES_EXHAUSTED)) rootFailureCode.set(code)
        }
        val handle = nativeHandleRef.get()
        if (handle != 0L) {
            nativeDataFileAdapter = NativeDataFileAdapter(filesDir)
            if (NativeEngineBridge.nativeSetDataFileListener(handle, nativeDataFileAdapter) != 0) {
                nativeDataFileAdapter.close()
                updateRootState(RootConnectionState.FAILED)
                return
            }
            nativeScriptUiAdapter = NativeScriptUiAdapter(
                post = { engineHandler.post(it) },
                currentToken = scriptUiController::currentToken,
                beforeRequest = { token ->
                    check(nativeHandleRef.get() == handle && scriptUiController.accepts(token))
                    // Enqueue legacy writes before a property request so an immediate read sees them.
                    drainNativeScriptPrompts(handle)
                },
                submit = scriptUiController::requestProperty,
                abort = scriptUiController::cancelDialogRequest,
                toolsBeforeRequest = { check(nativeHandleRef.get() == handle && !runStopRequested.get()) },
                toolsSubmit = runtimeTools::submit,
                toolsAbort = runtimeTools::cancel,
            )
            if (NativeEngineBridge.nativeSetScriptUiListener(handle, nativeScriptUiAdapter) != 0) {
                updateRootState(RootConnectionState.FAILED)
                return
            }
            nativeVisionAdapter = NativeVisionAdapter(visionResolver, ownsSource = false)
            if (NativeEngineBridge.nativeSetVisionListener(handle, nativeVisionAdapter) != 0) {
                updateRootState(RootConnectionState.FAILED)
                return
            }
            nativeWakeListener = NativeWakeListener(
                engineHandler,
                pump,
                rootDaemonController::onNativeDisconnected,
                rootDaemonController::disconnectTicket,
            )
            if (NativeEngineBridge.nativeSetWakeListener(handle, nativeWakeListener) == 0) {
                rootDaemonController.start(handle)
                rootConnectionAvailable.set(true)
                // Keep the initial authorization used by Studio and standalone Runner.
                // Subsequent disconnects stay stopped until a user requests the backend.
                engineHandler.post { rootDaemonController.requestConnection() }
            } else {
                updateRootState(RootConnectionState.FAILED)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_SCRIPT -> requestScriptUiStop()
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
        inputRecording.abort(RecordingReply.INVALID_EVENT)
        overlayController.onDisplayChanged()
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
        rootConnectionAvailable.set(false)
        appDiagnostics.close()
        if (::runtimeTools.isInitialized) runtimeTools.close()
        restartRequested.set(false)
        frozenRun.set(null)
        runLogWriter.close()
        if (::inputRecording.isInitialized) inputRecording.close()
        if (::nativeDataFileAdapter.isInitialized) nativeDataFileAdapter.close()
        audioResources.clear()
        floatingImages.configure(false)
        visionPreparation.close()
        recognitionPreview.close()
        debugSession.clear()
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
        runCatching { visionResolver.close() }.onFailure {
            // Whitelist code only; do not log SDK exception text or provider paths.
            android.util.Log.w("AutomationRuntimeService", "VISION_PROVIDER_CLOSE_FAILED")
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

    private fun currentRootStateCode(): Int = when (rootStateRef.get()) {
        RootConnectionState.STOPPED -> RuntimeProtocol.ROOT_STOPPED
        RootConnectionState.STARTING -> RuntimeProtocol.ROOT_STARTING
        RootConnectionState.READY -> RuntimeProtocol.ROOT_READY
        RootConnectionState.FAILED -> RuntimeProtocol.ROOT_FAILED
    }

    private fun stageExecutionPosition(handle: Long) {
        if (currentRuntimeState() != RuntimeProtocol.STATE_RUNNING) return
        val projectId = lastProjectIdRef.get() ?: return
        val ticket = debugSession.ticket(sessionGenerationRef.get(), handle, projectId) ?: return
        val encoded = NativeEngineBridge.nativeExecutionPosition(handle) ?: return
        if (encoded.toByteArray().size > 16_384) return
        val candidate = runCatching {
            val data = JSONObject(encoded)
            val rows = data.optJSONArray("recent")
            if (rows != null && rows.length() > 32) return
            val recent = List(rows?.length() ?: 0) { index ->
                val row = requireNotNull(rows).getJSONObject(index)
                com.autoscript.runtime.api.RuntimeNodePosition(row.getString("flowId"), row.getString("nodeId"))
            }
            com.autoscript.runtime.api.RuntimeExecutionPosition(ticket.generation, ticket.revision, projectId,
                data.getString("flowId"), data.getString("nodeId"), recent = recent)
        }.getOrNull() ?: return
        synchronized(this) {
            if (!debugSession.accepts(ticket, sessionGenerationRef.get(), nativeHandleRef.get())) return
            val last = pendingExecutionPosition ?: publishedExecutionPosition
            if (candidate.recent.isEmpty() && last?.let { it.sessionGeneration == candidate.sessionGeneration &&
                    it.runRevision == candidate.runRevision && it.flowId == candidate.flowId && it.nodeId == candidate.nodeId } == true) return
            executionPositionSequence = runCatching { Math.incrementExact(executionPositionSequence) }.getOrElse { return }
            pendingExecutionPosition = com.autoscript.runtime.api.mergeExecutionPositions(pendingExecutionPosition,
                candidate.copy(sampleSequence = executionPositionSequence))
            if (!executionPositionScheduled) {
                executionPositionScheduled = true
                engineHandler.postDelayed(publishExecutionPosition, 50L)
            }
        }
    }

    /** New listeners need the unchanged current row even during a long host wait. */
    @Synchronized private fun replayPublishedExecutionPosition(listener: IRuntimeStateListener) {
        val position = publishedExecutionPosition ?: return
        if (currentRuntimeState() != RuntimeProtocol.STATE_RUNNING) return
        val ticket = debugSession.ticket(sessionGenerationRef.get(), nativeHandleRef.get(), position.projectId) ?: return
        if (position.sessionGeneration != ticket.generation || position.runRevision != ticket.revision ||
            !debugSession.accepts(ticket, sessionGenerationRef.get(), nativeHandleRef.get())) return
        listener.onExecutionPosition(position.sessionGeneration, position.runRevision, position.projectId,
            position.flowId, position.nodeId, position.sampleSequence,
            position.recent.map { it.flowId }.toTypedArray(), position.recent.map { it.nodeId }.toTypedArray())
    }

    @Synchronized private fun publishLatestExecutionPosition() {
        executionPositionScheduled = false
        val position = pendingExecutionPosition ?: return
        pendingExecutionPosition = null
        val ticket = debugSession.ticket(position.sessionGeneration, nativeHandleRef.get(), position.projectId) ?: return
        if (ticket.revision != position.runRevision || currentRuntimeState() != RuntimeProtocol.STATE_RUNNING ||
            !debugSession.accepts(ticket, sessionGenerationRef.get(), nativeHandleRef.get())) return
        publishedExecutionPosition = position
        val count = stateListeners.beginBroadcast()
        try { for (index in 0 until count) runCatching {
            stateListeners.getBroadcastItem(index).onExecutionPosition(position.sessionGeneration, position.runRevision,
                position.projectId, position.flowId, position.nodeId, position.sampleSequence,
                position.recent.map { it.flowId }.toTypedArray(), position.recent.map { it.nodeId }.toTypedArray())
        } } finally { stateListeners.finishBroadcast() }
    }

    private fun updateRootState(state: RootConnectionState) {
        val previous = rootStateRef.getAndSet(state)
        if (state == RootConnectionState.READY && previous != state) {
            rootReadyAtElapsedMs.set(SystemClock.elapsedRealtime())
        } else if (state != RootConnectionState.READY && previous == RootConnectionState.READY) {
            val started = rootReadyAtElapsedMs.getAndSet(0)
            if (started > 0) runLogWriter.rootSessionLost(SystemClock.elapsedRealtime() - started)
        }
        if (state != RootConnectionState.READY && ::inputRecording.isInitialized) inputRecording.abort()
        if (state != RootConnectionState.READY) inputPickHandler.post {
            inputPickSession?.let { cancelInputPick(it.requestId, "Root连接已断开，请重新选点") }
        }
        if (state == RootConnectionState.READY || state == RootConnectionState.FAILED) {
            pendingOverlayCapture?.let { pending ->
                pendingOverlayCapture = null
                engineHandler.post {
                    if (pending.generation == sessionGenerationRef.get() && pending.projectId == lastProjectIdRef.get() &&
                        pending.revision == captureOverlayRevision.get() && captureOverlayEnabled.get()) {
                        if (state == RootConnectionState.READY) captureOverlayForStudio()
                        else reportOverlayCaptureFailure("Root 授权失败或等待超时，请确认授权后再次点击截图")
                    }
                }
            }
        }
        notifyRuntimeStateIfChanged()
    }

    @Synchronized
    private fun notifyRuntimeStateIfChanged(): Int {
        val state = currentRuntimeState()
        val rootState = currentRootStateCode()
        if (state == RuntimeProtocol.STATE_STOPPED && restartRequested.getAndSet(false) && restartFrozenRun()) return currentRuntimeState()
        if (inputPickActive.get() && (rootState != RuntimeProtocol.ROOT_READY ||
            state in setOf(RuntimeProtocol.STATE_RUNNING, RuntimeProtocol.STATE_PAUSED, RuntimeProtocol.STATE_STOPPING))) {
            inputPickHandler.post { inputPickSession?.let { cancelInputPick(it.requestId, "运行状态变化，选点已取消") } }
        }
        val stepPause = state == RuntimeProtocol.STATE_PAUSED && stepControlPending.getAndSet(false)
        if (state in setOf(RuntimeProtocol.STATE_STOPPED, RuntimeProtocol.STATE_FAILED, RuntimeProtocol.STATE_STOPPING)) stepControlPending.set(false)
        if (!shouldPublishRuntimeState(state, rootState, lastNotifiedStateRef.get(), lastNotifiedRootStateRef.get(), stepPause)) return state
        if (state != RuntimeProtocol.STATE_RUNNING) {
            pendingExecutionPosition = null
            publishedExecutionPosition = null
            engineHandler.removeCallbacks(publishExecutionPosition)
            executionPositionScheduled = false
        }
        lastNotifiedStateRef.set(state)
        lastNotifiedRootStateRef.set(rootState)
        appendRuntimeLog(state, rootState)
        val generation = sessionGenerationRef.get()
        publishFinalizedRuntimeState(finalizeState = {
            when (state) {
                RuntimeProtocol.STATE_STOPPED, RuntimeProtocol.STATE_FAILED -> {
                    if (terminalClearsAllRuntimePrompts(state, runStopRequested.get())) overlayController.clearRunPrompts()
                    frozenRun.set(null)
                    restartRequested.set(false)
                    val handle = nativeHandleRef.get()
                    if (handle != 0L) { drainNativeScriptLogs(handle); drainNativeRunEvents(handle) }
                    appendScriptExecutionState(state, stepPause)
                    runLogWriter.end(if (state == RuntimeProtocol.STATE_FAILED) "failed" else if (runStopRequested.get()) "stopped" else "ended")
                    finishForegroundRun()
                }
                RuntimeProtocol.STATE_RUNNING,
                RuntimeProtocol.STATE_PAUSED,
                RuntimeProtocol.STATE_STOPPING,
                -> {
                    appendScriptExecutionState(state, stepPause)
                    updateForegroundNotification()
                    if (floatingControlEnabled.get()) overlayController.show(state)
                }
            }
        }) {
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
        }
        return state
    }

    private fun restartFrozenRun(): Boolean = synchronized(projectStartLock) {
        val run = frozenRun.get() ?: return false
        var uiToken = run.uiToken
        fun valid() = canRestartRuntime(runStopRequested.get(), run.generation, sessionGenerationRef.get(),
            run.handle, nativeHandleRef.get(), rootStateRef.get() == RootConnectionState.READY,
            run.restarts, inputRecording.busy || scriptRecording.busy || recordingStarting.get()) && scriptUiController.accepts(uiToken)
        if (!valid()) return false
        drainNativeScriptLogs(run.handle)
        drainNativeRunEvents(run.handle)
        overlayController.clearRunPrompts()
        runtimeTools.reset()
        if (NativeEngineBridge.nativeRestartReset(run.handle, run.width, run.height) != 0 || !valid()) return false
        val configured = NativeEngineBridge.nativeConfigureProject(run.handle, coordinateSnapshotRef.incrementAndGet(),
            run.width, run.height, run.scale) == 0 &&
            NativeEngineBridge.nativeConfigureUiValues(run.handle, run.uiValues) == 0
        if (!configured || !valid()) return false
        uiToken = scriptUiController.restartRuntime(run.uiValues, uiToken)
        if (uiToken == 0L) return false
        if (!configured || !valid()) return false
        runLogWriter.end("restarted")
        val displaySize = Point().also {
            @Suppress("DEPRECATION")
            getSystemService(WindowManager::class.java).defaultDisplay.getRealSize(it)
        }
        runLogWriter.begin(mapOf("projectId" to run.projectId, "restartIndex" to (run.restarts + 1).toString(),
            "design" to "${run.width}x${run.height}", "abi" to Build.SUPPORTED_ABIS.firstOrNull().orEmpty(),
            "screen" to "${displaySize.x}x${displaySize.y}", "device" to "${Build.MANUFACTURER} ${Build.MODEL}",
            "androidApi" to Build.VERSION.SDK_INT.toString(), "startedElapsedMs" to SystemClock.elapsedRealtime().toString()))
        if (!valid() || NativeEngineBridge.nativeStart(run.handle, run.source, run.capabilities) != 0) return false
        if (!valid()) { stopNativeEngine(); return false }
        frozenRun.set(run.copy(restarts = run.restarts + 1, uiToken = uiToken))
        overlayController.resetRunPrompts()
        stepControlPending.set(false)
        debugSession.started(run.generation, run.handle, run.projectId)
        scriptUiController.activateRuntime()
        appendScriptExecutionLog(scriptLogState.start(SystemClock.elapsedRealtime(), restarted = true))
        engineHandler.removeCallbacks(pump)
        engineHandler.post(pump)
        updateForegroundNotification()
        if (floatingControlEnabled.get()) overlayController.show(RuntimeProtocol.STATE_RUNNING)
        true
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
        runStopRequested.set(true)
        val generation = sessionGenerationRef.get()
        val handle = nativeHandleRef.get()
        overlayController.clearRunPrompts()
        if (previewRecognitionBusy.get()) recognitionPreview.cancelActive()
        scriptUiController.clear()
        if (handle != 0L) NativeEngineBridge.nativeStop(handle, SystemClock.elapsedRealtimeNanos())
        engineHandler.postAtFrontOfQueue {
            if (generation == sessionGenerationRef.get() && !stopNativeEngine()) updateForegroundNotification()
        }
    }

    private fun stopNativeEngine(): Boolean {
        runStopRequested.set(true)
        if (::runtimeTools.isInitialized) runtimeTools.reset()
        restartRequested.set(false)
        frozenRun.set(null)
        runLogWriter.append("diagnostic.log", "${SystemClock.elapsedRealtime()}ms · stop_requested")
        overlayController.clearRunPrompts()
        if (previewRecognitionBusy.get()) recognitionPreview.cancelActive()
        if (::inputRecording.isInitialized) inputRecording.abort(RecordingReply.CANCELLED)
        if (::nativeDataFileAdapter.isInitialized) nativeDataFileAdapter.clear()
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
        if (::runtimeTools.isInitialized) runtimeTools.reset()
        scriptUiController.clear()
        overlayController.closeRunPrompt()
        if (inputPickActive.get() || inputRecording.busy || scriptRecording.busy || recordingStarting.get()) { updateForegroundNotification(); return }
        if (overlayController.hasPopupToast() && overlayController.canShow()) {
            // Existing short-lived tips may outlive normal completion. Animate that existing
            // control only; never retain a window or delay stop cleanup just for branding.
            overlayController.update(RuntimeProtocol.STATE_STOPPED,
                completedNormally = currentRuntimeState() == RuntimeProtocol.STATE_STOPPED && !runStopRequested.get())
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
        // Raw Lua diagnostics can contain private source; persist only whitelisted codes.
        runLogWriter.append("diagnostic.log", entry)
        if (state == RuntimeProtocol.STATE_FAILED) {
            runLogWriter.append("diagnostic.log", "Runner错误码 · ${runtimeFailureCode(currentRuntimeDiagnostic())}")
        }
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

    private fun drainNativeRunEvents(handle: Long) {
        NativeEngineBridge.nativeDrainRunEvents(handle).take(257).forEach { payload ->
            if (payload.toByteArray(Charsets.UTF_8).size <= 8_192) {
                val event = runCatching { JSONObject(payload) }.getOrNull() ?: return@forEach
                event.put("receivedAtElapsedMs", SystemClock.elapsedRealtime())
                runLogWriter.append("events.jsonl", event.toString())
                if (event.optString("kind") in setOf("trace", "task", "dropped")) {
                    RuntimeScriptLog.observation(event.toString())?.let { line ->
                        synchronized(runtimeLogLock) {
                            while (runtimeLogs.size >= MAX_RUNTIME_LOG_ENTRIES) runtimeLogs.removeFirst()
                            runtimeLogs.addLast(line.take(MAX_RUNTIME_LOG_LENGTH))
                        }
                    }
                }
            }
        }
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
            if (event?.optString("kind") == "scriptStop") {
                runStopRequested.set(true)
                overlayController.clearRunPrompts()
                return@forEach
            }
            if (event?.optString("kind") == "scriptRestart") {
                if (!runStopRequested.get()) restartRequested.set(true)
                overlayController.clearRunPrompts()
                return@forEach
            }
            if (runStopRequested.get()) return@forEach
            if (event?.optString("kind") == "ui") { scriptUiController.command(event); return@forEach }
            if (event?.optString("kind") == "promptEnabled") {
                if (event.opt("enabled") !is Boolean) return@forEach
                if (event.getBoolean("enabled")) overlayController.resetRunPrompts() else overlayController.clearRunPrompts()
                return@forEach
            }
            val message = event?.optString("message") ?: payload
            if (message.isBlank()) return@forEach
            when (event?.optString("kind") ?: "window") {
                "window" -> {
                    if (!overlayController.acceptsRunPrompts()) return@forEach
                    val style = if (event?.has("style") == true) {
                        event.optJSONObject("style")?.let(RuntimePopupStyle::parse) ?: return@forEach
                    } else RuntimePopupStyle()
                    overlayController.showRunPrompt(message, style)
                }
                "toast" -> {
                    val style = event?.optJSONObject("style")?.let(RuntimePopupStyle::parse)
                        ?: return@forEach
                    overlayController.showPopupToast(message, style)
                }
            }
        }
    }

    private fun appendScriptRuntimeLog(line: String) {
        runLogWriter.append("script.log", "${SystemClock.elapsedRealtime()}ms · $line")
        val entry = "${SystemClock.elapsedRealtime()}ms · 脚本调试日志 · ${line.take(MAX_RUNTIME_LOG_LENGTH - 24)}"
        synchronized(runtimeLogLock) {
            while (runtimeLogs.size >= MAX_RUNTIME_LOG_ENTRIES) runtimeLogs.removeFirst()
            runtimeLogs.addLast(entry)
        }
    }

    private fun appendScriptExecutionState(state: Int, stepPause: Boolean) {
        scriptLogState.transition(state, SystemClock.elapsedRealtime(), runStopRequested.get(), stepPause,
            runtimeFailureCode(currentRuntimeDiagnostic()))?.let(::appendScriptExecutionLog)
    }

    private fun appendScriptExecutionLog(line: String) {
        runLogWriter.append("script.log", line)
        synchronized(runtimeLogLock) {
            while (runtimeLogs.size >= MAX_RUNTIME_LOG_ENTRIES) runtimeLogs.removeFirst()
            runtimeLogs.addLast(line.take(MAX_RUNTIME_LOG_LENGTH))
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
        if (rootStateRef.get() == RootConnectionState.FAILED) {
            return "Root 后端启动失败 [${rootFailureCode.get()?.name ?: "ROOT_START_FAILED"}]；请查看运行环境的调试详情"
        }
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
    private val recognitionPreview by lazy { RuntimeRecognitionPreview(visionResolver, previewRecognitionBusy,
        generation = sessionGenerationRef::get, runtimeState = { if (visionPreparation.busy) RuntimeProtocol.STATE_RUNNING else currentRuntimeState() }) }

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
        appDiagnostics.record(AppDiagnosticCode.CAPTURE_REQUESTED)
        overlayController.showCaptureMessage("正在截图…")
        val projectId = lastProjectIdRef.get()
        if (projectId == null) {
            reportOverlayCaptureFailure("当前没有可返回的 Studio 项目")
            return
        }
        if (currentRuntimeState() !in setOf(
                RuntimeProtocol.STATE_IDLE,
                RuntimeProtocol.STATE_STOPPED,
                RuntimeProtocol.STATE_FAILED,
            )
        ) {
            reportOverlayCaptureFailure("请先停止脚本再截图")
            appDiagnostics.record(AppDiagnosticCode.CAPTURE_RUNTIME_BUSY)
            return
        }
        if (rootStateRef.get() != RootConnectionState.READY) {
            pendingOverlayCapture = PendingOverlayCapture(sessionGenerationRef.get(), projectId, captureOverlayRevision.get())
            overlayController.showCaptureMessage("正在等待 Root 授权…")
            rootDaemonController.requestConnection()
            return
        }
        val handle = nativeHandleRef.get()
        val capture=runCatching { handle.takeIf { it != 0L }?.let(NativeEngineBridge::nativeCapturePreview) }
        val raw = capture.getOrNull()
        if (raw == null) {
            val code=capturePreviewFailureCode(capture.exceptionOrNull())
            reportOverlayCaptureFailure("[${code.name}]；请到运行环境查看 Root / 截图诊断")
            appDiagnostics.record(code)
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
            appDiagnostics.record(AppDiagnosticCode.CAPTURE_READY)
            // This is a one-shot capture session. Studio owns the decoded bitmap from here on.
            captureOverlayEnabled.set(false)
            finishForegroundRun()
        } catch (_: Exception) {
            output.delete()
            appDiagnostics.record(AppDiagnosticCode.CAPTURE_INVALID_FRAME)
            reportOverlayCaptureFailure("截图无法保存或返回编辑器 [CAPTURE_HANDOFF_FAILED]")
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
        // The requesting editor owns its return path; a launcher Intent steals the target app's foreground.
        runCatching { current.listener.onFinished(checked) }
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

    private fun readVisualDraft(descriptor: ParcelFileDescriptor, maximum: Int = MAX_FLOW_BYTES): ByteArray {
        val size = descriptor.statSize
        require(size in 0..maximum.toLong())
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
                require(total <= maximum)
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

    /** project.json is the only permission source; recording never silently adds a capability. */
    private fun recordingProjectAllowed(projectId: String, flowId: String): Boolean = runCatching {
        val directory = resolveProjectDirectory(projectId) ?: return false
        if (!FLOW_ID.matches(flowId)) return false
        val manifest = File(directory,"project.json")
        if (!manifest.isFile || manifest.length() !in 1..1_048_576) return false
        val json = JSONObject(manifest.readText(Charsets.UTF_8))
        val capabilities = json.optJSONArray("capabilities") ?: return false
        val hasInput = (0 until capabilities.length()).any { capabilities.optString(it) == "input.basic" }
        val flows = json.optJSONArray("flows") ?: return false
        hasInput && (0 until flows.length()).any { flows.optJSONObject(it)?.optString("flowId") == flowId }
    }.getOrDefault(false)

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
        const val MAX_SCRIPT_LOG_BATCH = 200
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
    private val onRootDisconnected: (Long) -> Unit,
    private val rootDisconnectTicket: () -> Long?,
) {
    @Suppress("unused")
    fun onNativeWake() {
        handler.post(pump)
    }

    @Suppress("unused")
    fun onNativeRootDisconnected() {
        val ticket = rootDisconnectTicket() ?: return
        handler.post { onRootDisconnected(ticket) }
    }
}

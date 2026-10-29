package com.autoscript.runtime.client

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import com.autoscript.core.model.RuntimeConnectionPhase
import com.autoscript.core.model.RuntimeConnectionState
import com.autoscript.core.model.RuntimeEngineState
import com.autoscript.core.model.RuntimeRootState
import com.autoscript.runtime.api.IRuntimeService
import com.autoscript.runtime.api.IRuntimeStateListener
import com.autoscript.runtime.api.IInputPointPickListener
import com.autoscript.runtime.api.InputPointAction
import com.autoscript.runtime.api.InputPointPickReply
import com.autoscript.runtime.api.RuntimeProtocol
import com.autoscript.runtime.api.VisualCompileReply
import com.autoscript.runtime.api.TemplateMatchReply
import com.autoscript.runtime.api.RecognitionPreviewReply
import com.autoscript.runtime.api.RecordingReply
import com.autoscript.runtime.api.RuntimeDebugReply
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class RuntimeProjectResourceKind {
    IMAGE,
    GLYPH_DICTIONARY,
    AUDIO,
}

data class RuntimeProjectResource(
    val kind: RuntimeProjectResourceKind,
    val path: String,
    val file: File,
)

sealed interface ScriptValidationResult {
    data object Valid : ScriptValidationResult
    data class Invalid(val diagnostic: String) : ScriptValidationResult
    data class Unavailable(val message: String) : ScriptValidationResult
}

data class VisualCompileDiagnostic(
    val code: String?,
    val message: String,
    val flowId: String?,
    val nodeId: String?,
    val line: Int?,
)

sealed interface VisualCompileResult {
    data class Success(val generationId: String) : VisualCompileResult
    data class Invalid(val diagnostic: VisualCompileDiagnostic) : VisualCompileResult
    data class Unavailable(val message: String) : VisualCompileResult
}

sealed interface ScreenshotPreviewResult {
    data class Success(val file: File) : ScreenshotPreviewResult
    data class Unavailable(val message: String) : ScreenshotPreviewResult
}

class RuntimeClient(context: Context) {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var remote: IRuntimeService? = null
    private val startGate = RuntimeStartGate()
    private var bound = false
    @Volatile private var lastSessionGeneration: Long? = null
    @Volatile private var lastEngineState: RuntimeEngineState = RuntimeEngineState.UNKNOWN
    @Volatile private var lastRootState: RuntimeRootState = RuntimeRootState.UNKNOWN
    private val stateRevision = java.util.concurrent.atomic.AtomicLong(0)
    private val debugReadGate = RuntimeDebugReadGate()
    private val connectionStates = MutableStateFlow(RuntimeConnectionState())
    val runtimeStates = connectionStates.asStateFlow()
    private val executionPositions = MutableStateFlow<com.autoscript.runtime.api.RuntimeExecutionPosition?>(null)
    val executionPosition = executionPositions.asStateFlow()
    private var endedPositionRevision = 0L
    private fun clearExecutionPositionDelivery() = synchronized(positionInbox) {
        executionPositions.value = null
        endedPositionRevision = 0
        positionInbox.set(null)
        mainHandler.removeCallbacks(deliverPosition)
        positionDeliveryPending.set(false)
    }
    private val positionInbox = java.util.concurrent.atomic.AtomicReference<com.autoscript.runtime.api.RuntimeExecutionPosition?>()
    private val positionDeliveryPending = java.util.concurrent.atomic.AtomicBoolean(false)
    private val deliverPosition = Runnable {
        val position = positionInbox.getAndSet(null)
        positionDeliveryPending.set(false)
        if (position != null && remote != null && acceptsExecutionPosition(position, connectionStates.value, endedPositionRevision, executionPositions.value))
            executionPositions.value = position
        schedulePositionDelivery()
    }
    private fun schedulePositionDelivery() {
        if (positionInbox.get() != null && positionDeliveryPending.compareAndSet(false, true)) mainHandler.post(deliverPosition)
    }
    private data class PendingInputPick(val id: Long, val generation: Long, val features: Int, val callback: (InputPointPickReply) -> Unit)
    private var pendingInputPick: PendingInputPick? = null
    private data class RecordingLease(val id: Long, val generation: Long, val service: IRuntimeService)
    private val recordingLease = java.util.concurrent.atomic.AtomicReference<RecordingLease?>()
    private val completedRecording = MutableStateFlow<RecordingReply?>(null)
    val recordingCompletion = completedRecording.asStateFlow()
    private val recordingRandom = java.security.SecureRandom()
    private val inputPickListener = object : IInputPointPickListener.Stub() {
        override fun onFinished(reply: InputPointPickReply) {
            mainHandler.post {
                val pending = pendingInputPick ?: return@post
                if (pending.id != reply.requestId) return@post
                pendingInputPick = null
                val valid = reply.status in InputPointPickReply.SUCCESS..InputPointPickReply.FAILED && pending.generation == lastSessionGeneration &&
                    (reply.status != InputPointPickReply.SUCCESS || reply.validFor(pending.id, pending.features))
                pending.callback(if (valid) reply.copy(message = reply.message.take(160)) else InputPointPickReply(pending.id, InputPointPickReply.FAILED,
                    reply.action.take(20), message = "选点结果已失效，请重新选择"))
            }
        }
    }

    var onStateChanged: ((RuntimeConnectionState) -> Unit)? = null

    @Volatile private var stateListener: IRuntimeStateListener? = null

    private fun invalidateStateListener(): IRuntimeStateListener? = synchronized(positionInbox) {
        val previous = stateListener
        stateListener = null
        clearExecutionPositionDelivery()
        previous
    }

    private fun newStateListener(): IRuntimeStateListener = object : IRuntimeStateListener.Stub() {
        override fun onExecutionPosition(sessionGeneration: Long, runRevision: Long, projectId: String, flowId: String, nodeId: String,
            sampleSequence: Long, recentFlows: Array<out String>?, recentNodes: Array<out String>?) {
            val position = runCatching {
                require(recentFlows != null && recentNodes != null && recentFlows.size == recentNodes.size && recentFlows.size <= 32)
                com.autoscript.runtime.api.RuntimeExecutionPosition(sessionGeneration, runRevision, projectId, flowId, nodeId,
                    sampleSequence, recentFlows.indices.map { com.autoscript.runtime.api.RuntimeNodePosition(recentFlows[it], recentNodes[it]) })
            }.getOrNull() ?: return
            synchronized(positionInbox) {
                if (stateListener !== this) return
                positionInbox.updateAndGet { previous ->
                    if (previous?.sessionGeneration == position.sessionGeneration && previous.runRevision > position.runRevision) previous
                    else com.autoscript.runtime.api.mergeExecutionPositions(previous, position)
                }
                schedulePositionDelivery()
            }
        }
        override fun onRuntimeStateChanged(
            sessionGeneration: Long,
            stateCode: Int,
            rootStateCode: Int,
            diagnostic: String?,
        ) {
            mainHandler.post {
                // A callback already in flight can outlive unregister/rebind on the same service.
                if (remote == null || stateListener !== this) return@post
                if (lastSessionGeneration != sessionGeneration) endedPositionRevision = 0
                if (stateCode != RuntimeProtocol.STATE_RUNNING) {
                    if (stateCode != RuntimeProtocol.STATE_PAUSED) endedPositionRevision = maxOf(endedPositionRevision, executionPositions.value?.runRevision ?: 0)
                    executionPositions.value = null
                }
                if (pendingInputPick?.generation?.let { it != sessionGeneration } == true) failInputPointPick("Runner会话已重建")
                lastSessionGeneration = sessionGeneration
                lastEngineState = mapEngineState(stateCode)
                lastRootState = mapRootState(rootStateCode)
                publish(
                    phase = RuntimeConnectionPhase.CONNECTED,
                    protocolVersion = RuntimeProtocol.VERSION,
                    sessionGeneration = sessionGeneration,
                    engineState = lastEngineState,
                    rootState = lastRootState,
                    message = diagnostic,
                )
            }
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            invalidateStateListener()
            val service = IRuntimeService.Stub.asInterface(binder)
            remote = service
            try {
                val version = service.protocolVersion
                if (version != RuntimeProtocol.VERSION) {
                    lastSessionGeneration = null
                    publish(
                        RuntimeConnectionPhase.ERROR,
                        protocolVersion = version,
                        message = "Runner协议不兼容：需要v${RuntimeProtocol.VERSION}，实际v$version",
                    )
                    return
                }
                val generation = service.sessionGeneration
                if (lastSessionGeneration != generation) clearExecutionPositionDelivery()
                lastSessionGeneration = generation
                lastEngineState = mapEngineState(service.runtimeStateCode)
                lastRootState = mapRootState(service.rootStateCode)
                publish(
                    phase = RuntimeConnectionPhase.CONNECTED,
                    protocolVersion = version,
                    sessionGeneration = generation,
                    engineState = lastEngineState,
                    rootState = lastRootState,
                )
                val listener = newStateListener()
                stateListener = listener
                service.registerStateListener(listener)
            } catch (error: RemoteException) {
                invalidateStateListener()
                lastSessionGeneration = null
                publish(RuntimeConnectionPhase.ERROR, message = error.message ?: "连接Runner失败")
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            invalidateStateListener()
            startGate.cancel()
            failInputPointPick("Runner连接已断开")
            remote = null
            lastSessionGeneration = null
            lastEngineState = RuntimeEngineState.UNKNOWN
            lastRootState = RuntimeRootState.UNKNOWN
            publish(RuntimeConnectionPhase.DISCONNECTED, message = "Runner连接已断开")
        }

        override fun onBindingDied(name: ComponentName) {
            invalidateStateListener()
            startGate.cancel()
            failInputPointPick("Runner进程已退出")
            remote = null
            bound = false
            lastSessionGeneration = null
            lastEngineState = RuntimeEngineState.UNKNOWN
            lastRootState = RuntimeRootState.UNKNOWN
            publish(RuntimeConnectionPhase.ERROR, message = "Runner进程已退出")
        }

        override fun onNullBinding(name: ComponentName) {
            invalidateStateListener()
            startGate.cancel()
            failInputPointPick("Runner未提供控制接口")
            remote = null
            lastSessionGeneration = null
            lastEngineState = RuntimeEngineState.UNKNOWN
            lastRootState = RuntimeRootState.UNKNOWN
            publish(RuntimeConnectionPhase.ERROR, message = "Runner未提供控制接口")
        }
    }

    fun bind() {
        if (bound) return
        publish(RuntimeConnectionPhase.CONNECTING)
        val intent = Intent().setClassName(appContext.packageName, RuntimeProtocol.SERVICE_CLASS)
        bound = appContext.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        if (!bound) {
            publish(RuntimeConnectionPhase.ERROR, message = "无法绑定Runner服务")
        }
    }

    fun unbind() {
        val previousListener = invalidateStateListener()
        startGate.cancel()
        completedRecording.value = null
        recordingLease.getAndSet(null)?.let { lease -> runCatching { lease.service.cancelInputRecording(System.nanoTime().and(Long.MAX_VALUE).coerceAtLeast(1),lease.generation,lease.id) } }
        cancelInputPointPick()
        runCatching { previousListener?.let { remote?.unregisterStateListener(it) } }
        if (bound) appContext.unbindService(connection)
        bound = false
        remote = null
        lastSessionGeneration = null
        lastEngineState = RuntimeEngineState.UNKNOWN
        lastRootState = RuntimeRootState.UNKNOWN
        publish(RuntimeConnectionPhase.DISCONNECTED)
    }

    fun refresh() = publishRemoteState()

    /** Explicit connect/retry. Refresh and feature queries remain read-only. */
    suspend fun connectRoot(): Boolean {
        val service = remote ?: return false
        val generation = lastSessionGeneration ?: return false
        val ready = awaitRootForOperation(service, generation)
        if (ready != RuntimeStartReadiness.READY) publishOperationError(
            if (ready == RuntimeStartReadiness.TIMEOUT) "等待 Root 授权超时，请确认授权后主动重试"
            else "Root 未连接，请确认设备授权后主动重试；运行环境可查看诊断",
        )
        return ready == RuntimeStartReadiness.READY
    }

    private suspend fun awaitRootForOperation(service: IRuntimeService, generation: Long): RuntimeStartReadiness =
        requestRuntimeReady(connectionStates, generation, request = {
            currentCoroutineContext().ensureActive()
            withContext(Dispatchers.IO) {
                if (remote !== service || lastSessionGeneration != generation) false
                else {
                    val accepted = runCatching { service.requestRootConnection(generation) == RuntimeProtocol.CONTROL_ACCEPTED }
                        .getOrDefault(false)
                    if (remote !== service || lastSessionGeneration != generation) false
                    else {
                        publishRemoteState()
                        accepted
                    }
                }
            }
        })

    fun validateScript(
        source: ByteArray,
        chunkName: String = "main.lua",
        requestId: Long = System.nanoTime(),
    ): ScriptValidationResult {
        val service = remote
        val generation = lastSessionGeneration
        if (service == null || generation == null) {
            return ScriptValidationResult.Unavailable("Runner会话尚未建立")
        }
        return try {
            val reply = service.validateScript(requestId, generation, chunkName, source)
            when (reply.status) {
                RuntimeProtocol.VALIDATION_VALID -> ScriptValidationResult.Valid
                RuntimeProtocol.VALIDATION_INVALID -> ScriptValidationResult.Invalid(
                    reply.diagnostic ?: "Lua语法无效",
                )
                RuntimeProtocol.VALIDATION_SESSION_MISMATCH -> {
                    ScriptValidationResult.Unavailable("Runner会话已重建，请重试")
                }
                RuntimeProtocol.VALIDATION_ENGINE_ERROR -> {
                    ScriptValidationResult.Unavailable(reply.diagnostic ?: "Lua校验器不可用")
                }
                else -> ScriptValidationResult.Unavailable("Runner返回未知校验结果")
            }
        } catch (error: Exception) {
            ScriptValidationResult.Unavailable(error.message ?: "Lua校验请求失败")
        }
    }

    fun compileVisualNodeTest(projectId: String, flowId: String, nodeId: String): VisualCompileResult {
        val service = remote ?: return VisualCompileResult.Unavailable("Runner会话尚未建立")
        val generation = lastSessionGeneration ?: return VisualCompileResult.Unavailable("Runner会话尚未建立")
        return try { mapVisualCompileReply(service.compileVisualNodeTest(System.nanoTime(), generation, projectId, flowId, nodeId), "选中积木无法单独测试") }
        catch (failure: Exception) { VisualCompileResult.Unavailable(failure.message ?: "测试编译失败") }
    }

    fun compileVisualProject(
        projectId: String,
        requestId: Long = System.nanoTime(),
    ): VisualCompileResult {
        val service = remote
        val generation = lastSessionGeneration
        if (service == null || generation == null) {
            return VisualCompileResult.Unavailable("Runner会话尚未建立")
        }
        return try {
            val reply = service.compileVisualProject(requestId, generation, projectId)
            mapVisualCompileReply(reply, "可视化项目无法编译")
        } catch (error: Exception) {
            VisualCompileResult.Unavailable(error.message ?: "可视化编译请求失败")
        }
    }

    private fun mapVisualCompileReply(
        reply: VisualCompileReply,
        invalidFallback: String,
    ): VisualCompileResult = when (reply.status) {
        RuntimeProtocol.VISUAL_COMPILE_ACCEPTED -> {
            val generationId = reply.generationId
            if (generationId.isNullOrBlank()) {
                VisualCompileResult.Unavailable("编译结果缺少generationId")
            } else {
                VisualCompileResult.Success(generationId)
            }
        }
        RuntimeProtocol.VISUAL_COMPILE_INVALID -> VisualCompileResult.Invalid(
            VisualCompileDiagnostic(
                code = reply.code,
                message = reply.diagnostic ?: invalidFallback,
                flowId = reply.flowId,
                nodeId = reply.nodeId,
                line = reply.line.takeIf { it > 0 },
            ),
        )
        RuntimeProtocol.VISUAL_COMPILE_SESSION_MISMATCH -> {
            VisualCompileResult.Unavailable("Runner会话已重建，请重试")
        }
        RuntimeProtocol.VISUAL_COMPILE_ENGINE_ERROR -> {
            VisualCompileResult.Unavailable(reply.diagnostic ?: "可视化编译器不可用")
        }
        else -> VisualCompileResult.Unavailable("Runner返回未知编译结果")
    }

    fun validateVisualDraft(
        projectId: String,
        flowId: String,
        draft: ByteArray,
        requestId: Long = System.nanoTime(),
    ): VisualCompileResult {
        if (draft.size > MAX_FLOW_BYTES) {
            return VisualCompileResult.Invalid(
                VisualCompileDiagnostic(
                    code = "RESOURCE_LIMIT",
                    message = "Flow草稿超过64 MiB",
                    flowId = flowId,
                    nodeId = null,
                    line = null,
                ),
            )
        }
        return validateDraftPayload(projectId, flowId, draft, requestId)
    }

    fun validateVisualDraftSet(projectId: String, drafts: Map<String, ByteArray>, requestId: Long = System.nanoTime()): VisualCompileResult {
        val bytes = try { com.autoscript.runtime.api.VisualDraftSetCodec.encode(drafts) }
        catch (_: Exception) { return VisualCompileResult.Unavailable("组合草稿无效或超过资源上限") }
        return validateDraftPayload(projectId, null, bytes, requestId)
    }

    private fun validateDraftPayload(projectId: String, flowId: String?, draft: ByteArray, requestId: Long): VisualCompileResult {
        val service = remote
        val generation = lastSessionGeneration
        if (service == null || generation == null) {
            return VisualCompileResult.Unavailable("Runner会话尚未建立")
        }
        var temporary: File? = null
        return try {
            temporary = File.createTempFile("visual-draft-", ".jsonl", appContext.cacheDir)
            FileOutputStream(temporary).use { output ->
                output.write(draft)
                output.flush()
                output.fd.sync()
            }
            val reply = ParcelFileDescriptor.open(
                temporary,
                ParcelFileDescriptor.MODE_READ_ONLY,
            ).use { descriptor ->
                if (flowId == null) service.validateVisualDraftSet(requestId, generation, projectId, descriptor)
                else service.validateVisualDraft(requestId, generation, projectId, flowId, descriptor)
            }
            mapVisualCompileReply(reply, "可视化草稿无法通过校验")
        } catch (error: Exception) {
            VisualCompileResult.Unavailable(error.message ?: "可视化草稿校验请求失败")
        } finally {
            temporary?.delete()
        }
    }

    fun registerTemplate(
        assetPath: String,
        imageFile: File,
        requestId: Long = System.nanoTime(),
    ): Boolean = registerTemplate(remote, lastSessionGeneration, assetPath, imageFile, requestId)

    private fun registerTemplate(service: IRuntimeService?, expectedGeneration: Long?,
        assetPath: String, imageFile: File, requestId: Long): Boolean {
        if (expectedGeneration == null) {
            publishOperationError("Runner会话尚未建立")
            return false
        }
        return try {
            val result = ParcelFileDescriptor.open(
                imageFile,
                ParcelFileDescriptor.MODE_READ_ONLY,
            ).use { descriptor ->
                service?.registerTemplate(requestId, expectedGeneration, assetPath, descriptor)
            }
            when (result) {
                RuntimeProtocol.TEMPLATE_ACCEPTED -> true
                RuntimeProtocol.TEMPLATE_SESSION_MISMATCH -> {
                    publishOperationError("Runner会话已重建，请重新导入资源")
                    false
                }
                RuntimeProtocol.TEMPLATE_INVALID_PATH -> {
                    publishOperationError("图片必须位于assets/images规范路径")
                    false
                }
                RuntimeProtocol.TEMPLATE_INVALID_IMAGE -> {
                    publishOperationError("图片损坏、尺寸过大或格式不受支持")
                    false
                }
                RuntimeProtocol.TEMPLATE_ENGINE_ERROR -> {
                    publishOperationError("图片资源重复或超过Runner资源预算")
                    false
                }
                else -> {
                    publishOperationError("Runner返回未知图片导入结果")
                    false
                }
            }
        } catch (error: Exception) {
            publishOperationError(error.message ?: "图片资源导入失败")
            false
        }
    }

    fun registerDictionary(
        resourcePath: String,
        dictionaryFile: File,
        requestId: Long = System.nanoTime(),
    ): Boolean = registerDictionary(remote, lastSessionGeneration, resourcePath, dictionaryFile, requestId)

    private fun registerDictionary(service: IRuntimeService?, expectedGeneration: Long?,
        resourcePath: String, dictionaryFile: File, requestId: Long): Boolean {
        if (expectedGeneration == null) {
            publishOperationError("Runner会话尚未建立")
            return false
        }
        return try {
            val result = ParcelFileDescriptor.open(
                dictionaryFile,
                ParcelFileDescriptor.MODE_READ_ONLY,
            ).use { descriptor ->
                service?.registerDictionary(
                    requestId,
                    expectedGeneration,
                    resourcePath,
                    descriptor,
                )
            }
            when (result) {
                RuntimeProtocol.DICTIONARY_ACCEPTED -> true
                RuntimeProtocol.DICTIONARY_SESSION_MISMATCH -> {
                    publishOperationError("Runner会话已重建，请重新导入资源")
                    false
                }
                RuntimeProtocol.DICTIONARY_INVALID_PATH -> {
                    publishOperationError("字库必须位于dictionaries规范路径")
                    false
                }
                RuntimeProtocol.DICTIONARY_INVALID_FILE -> {
                    publishOperationError("字库为空、损坏或超过8 MiB")
                    false
                }
                RuntimeProtocol.DICTIONARY_ENGINE_ERROR -> {
                    publishOperationError("字库格式无效、资源重复或超过Runner预算")
                    false
                }
                else -> {
                    publishOperationError("Runner返回未知字库导入结果")
                    false
                }
            }
        } catch (error: Exception) {
            publishOperationError(error.message ?: "字库资源导入失败")
            false
        }
    }

    /** Registers every declared project resource in stable path order, then starts the script. */
    suspend fun startProject(
        projectId: String = "",
        generatedLuaModule: ByteArray,
        resources: List<RuntimeProjectResource>,
        capabilities: List<String>,
        designWidth: Int = 720,
        designHeight: Int = 1280,
        scaleMode: Int = RuntimeProtocol.SCALE_LETTERBOX,
        requestId: Long = System.nanoTime(),
        requiresPointerInput: Boolean = false,
        scriptUiJson: String? = null,
        scriptUiValuesJson: String = "{}",
        floatingControl: Boolean? = null,
        requiredVisionComponents: Set<String>? = null,
    ): Boolean {
        val job = currentCoroutineContext().job
        if (!startGate.begin(job)) {
            publishOperationError("已有启动请求正在准备，请勿重复运行")
            return false
        }
        return try {
            val frozenSource = generatedLuaModule.copyOf()
            val frozenVision = requiredVisionComponents?.toSet()
            // Bitmap decoding, file descriptors and synchronous Binder preparation must not block
            // the editor/UI thread. Cancellation is checked between every session-bound operation.
            withContext(Dispatchers.IO) {
                startPreparedProject(projectId, frozenSource, resources, capabilities, designWidth,
                    designHeight, scaleMode, requestId, requiresPointerInput, scriptUiJson, scriptUiValuesJson, floatingControl, frozenVision)
            }
        } finally {
            startGate.finish(job)
        }
    }

    private suspend fun startPreparedProject(projectId: String, generatedLuaModule: ByteArray,
        resources: List<RuntimeProjectResource>, capabilities: List<String>, designWidth: Int,
        designHeight: Int, scaleMode: Int, requestId: Long, requiresPointerInput: Boolean,
        scriptUiJson: String?, scriptUiValuesJson: String, floatingControl: Boolean?, requiredVisionComponents: Set<String>?): Boolean {
        // Binding is not Root readiness. Keep the first run request alive while Root starts,
        // using state notifications rather than blocking the UI or polling the daemon.
        publishRemoteState()
        val service = remote
        val generation = lastSessionGeneration
        if (service == null || generation == null) {
            publishOperationError("Runner会话尚未建立")
            return false
        }
        val ready = awaitRootForOperation(service, generation)
        if (ready != RuntimeStartReadiness.READY) {
            publishOperationError(if (ready == RuntimeStartReadiness.TIMEOUT) {
                "等待 Root 就绪超时，请检查 Root 授权后重试"
            } else "运行环境已变化或 Root 授权失败，请检查运行环境")
            return false
        }
        currentCoroutineContext().ensureActive()
        suspend fun ensureSession() {
            currentCoroutineContext().ensureActive()
            check(remote === service && lastSessionGeneration == generation) { "Runner会话已变化，请重试" }
        }
        ensureSession()
        val features = if (requiresPointerInput) runCatching { service.getInputFeatures(generation) }.getOrDefault(0) else 0
        if (requiresPointerInput && features and RuntimeProtocol.INPUT_FEATURE_SINGLE_POINTER == 0) {
            publishOperationError("当前 Root 后端不支持持续触点，请移除按下/移动/弹起或更换支持的 Android 系统")
            return false
        }
        val invalid = validateProjectResources(resources)
        if (invalid != null) {
            publishOperationError(invalid)
            return false
        }
        val invalidCapabilities = validateProjectCapabilities(capabilities)
        if (invalidCapabilities != null) {
            publishOperationError(invalidCapabilities)
            return false
        }
        val requestIds = runCatching {
            List(resources.size + 3) { offset -> Math.addExact(requestId, offset.toLong()) }
        }.getOrElse {
            publishOperationError("项目请求编号溢出")
            return false
        }
        ensureSession()
        if (!prepareProject(requestIds[0], service, generation)) return false
        if(service?.configureFloatingResources(generation,"ui.floating" in capabilities || "ui.floating.interactive" in capabilities)!=RuntimeProtocol.CONTROL_ACCEPTED) {
            publishOperationError("Runner浮窗资源准备失败")
            return false
        }
        var started = false
        try {
            if (floatingControl != null) {
                ensureSession()
                val surface = service.setFloatingControlEnabled(requestIds.last(), generation, floatingControl)
                // Formal launches still have notification controls without overlay permission.
                check(surface == RuntimeProtocol.SURFACE_ACCEPTED ||
                    (floatingControl && surface == RuntimeProtocol.SURFACE_PERMISSION_DENIED)) {
                    "运行控制窗口准备失败"
                }
            }
            for ((index, resource) in resources.sortedBy(RuntimeProjectResource::path).withIndex()) {
                ensureSession()
                val resourceRequestId = requestIds[index + 1]
                val registered = when (resource.kind) {
                    RuntimeProjectResourceKind.AUDIO -> ParcelFileDescriptor.open(resource.file, ParcelFileDescriptor.MODE_READ_ONLY).use {
                        service?.registerAudio(requestIds[index + 1], generation, resource.path, it) == RuntimeProtocol.CONTROL_ACCEPTED
                    }
                    RuntimeProjectResourceKind.IMAGE -> registerTemplate(
                        service, generation,
                        resource.path,
                        resource.file,
                        resourceRequestId,
                    )
                    RuntimeProjectResourceKind.GLYPH_DICTIONARY -> registerDictionary(
                        service, generation,
                        resource.path,
                        resource.file,
                        resourceRequestId,
                    )
                }
                if (!registered) {
                    return false
                }
            }
            if (scriptUiJson != null) {
                ensureSession()
                val images = resources.filter { it.kind == RuntimeProjectResourceKind.IMAGE }
                val configured = runCatching {
                    service.configureScriptUi(generation, projectId, scriptUiJson,
                        scriptUiValuesJson, images.map { it.path }.toTypedArray(),
                        images.map { it.file.canonicalPath }.toTypedArray()) == RuntimeProtocol.CONTROL_ACCEPTED
                }.getOrDefault(false)
                if (!configured) {
                    publishOperationError("脚本界面准备失败，请检查界面配置与悬浮窗权限")
                    return false
                }
            }
            ensureSession()
            if (requiredVisionComponents != null && !prepareVisionForStart(service, generation,
                    generatedLuaModule, requiredVisionComponents)) return false
            ensureSession()
            started = startScript(
                service = service, expectedGeneration = generation,
                projectId = projectId,
                generatedLuaModule = generatedLuaModule,
                capabilities = capabilities,
                designWidth = designWidth,
                designHeight = designHeight,
                scaleMode = scaleMode,
                requestId = requestIds[resources.size + 1],
            )
            return started
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            publishOperationError(failure.message ?: "项目启动准备失败")
            return false
        } finally {
            if (!started || !currentCoroutineContext().job.isActive) {
                stopPartiallyPreparedSession(requestIds.last(), service, generation)
            }
        }
    }

    /** Tiny status read only; provider management calls this from its IO checkpoints. */
    fun canChangeVisionProviders(): Boolean {
        val service = remote ?: return false
        val generation = lastSessionGeneration ?: return false
        return runCatching { !service.isVisionPreparationActive() && service === remote && generation == lastSessionGeneration }.getOrDefault(false)
    }

    private suspend fun prepareVisionForStart(service: IRuntimeService, generation: Long,
        source: ByteArray, requirements: Set<String>): Boolean {
        if (requirements.any { it !in setOf("opencv", "onnx") } || requirements.size > 2) {
            publishOperationError("视觉组件需求无效，请重新编译")
            return false
        }
        val id = java.security.SecureRandom().nextLong().and(Long.MAX_VALUE).coerceAtLeast(1)
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(source)
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        val result = kotlinx.coroutines.CompletableDeferred<VisionStartupOutcome>()
        val listener = object : com.autoscript.runtime.api.IVisionPreparationListener.Stub() {
            override fun onFinished(requestId: Long, sessionGeneration: Long, status: Int, component: String, state: String) {
                if (android.os.Binder.getCallingUid() != android.os.Process.myUid() || requestId != id || sessionGeneration != generation ||
                    remote !== service || lastSessionGeneration != generation) return
                result.complete(visionStartupOutcome(status, component, state))
            }
        }
        var ready = false
        try {
            val begin = service.beginVisionPreparation(id, generation, digest, listOf("opencv", "onnx").filter { it in requirements }.toTypedArray(), listener)
            if (begin != RuntimeProtocol.VISION_PREPARE_ACCEPTED) {
                publishOperationError(visionStartupOutcome(begin, "", "").message)
                return false
            }
            val outcome = kotlinx.coroutines.withTimeoutOrNull(35_000) { result.await() }
                ?: visionStartupOutcome(RuntimeProtocol.VISION_PREPARE_TIMEOUT, "", "")
            currentCoroutineContext().ensureActive()
            ready = outcome.ready && remote === service && lastSessionGeneration == generation
            if (!ready) publishOperationError(outcome.message)
            return ready
        } catch (cancelled: CancellationException) { throw cancelled }
        finally {
            if (!ready) withContext(NonCancellable + Dispatchers.IO) { runCatching { service.cancelVisionPreparation(id, generation) } }
        }
    }

    fun requestStop(requestId: Long = System.nanoTime()): Boolean {
        startGate.cancel()
        val expectedGeneration = lastSessionGeneration
        if (expectedGeneration == null) {
            publishOperationError("Runner会话尚未建立")
            return false
        }
        return try {
            when (remote?.requestStop(requestId, expectedGeneration)) {
                RuntimeProtocol.STOP_ACCEPTED -> true
                RuntimeProtocol.STOP_SESSION_MISMATCH -> {
                    publishOperationError("Runner会话已重建，请刷新后重试")
                    false
                }
                RuntimeProtocol.STOP_ENGINE_ERROR -> {
                    publishOperationError("Runner未能接受停止请求")
                    false
                }
                else -> {
                    publishOperationError("Runner返回未知停止结果")
                    false
                }
            }
        } catch (error: RemoteException) {
            publishOperationError(error.message ?: "停止请求失败")
            false
        }
    }

    fun requestPause(requestId: Long = System.nanoTime()): Boolean =
        requestRuntimeControl(requestId, pause = true)

    fun requestResume(requestId: Long = System.nanoTime()): Boolean =
        requestRuntimeControl(requestId, pause = false)

    private fun requestRuntimeControl(requestId: Long, pause: Boolean): Boolean {
        val service = remote
        val generation = lastSessionGeneration
        if (service == null || generation == null) {
            publishOperationError("Runner会话尚未建立")
            return false
        }
        return try {
            val result = if (pause) {
                service.requestPause(requestId, generation)
            } else {
                service.requestResume(requestId, generation)
            }
            when (result) {
                RuntimeProtocol.CONTROL_ACCEPTED -> true
                RuntimeProtocol.CONTROL_SESSION_MISMATCH -> {
                    publishOperationError("Runner会话已重建，请刷新后重试")
                    false
                }
                RuntimeProtocol.CONTROL_INVALID_STATE -> {
                    publishOperationError(if (pause) "当前状态不能暂停" else "当前状态不能继续")
                    false
                }
                RuntimeProtocol.CONTROL_ENGINE_ERROR -> {
                    publishOperationError(if (pause) "引擎未能接受暂停请求" else "引擎未能接受继续请求")
                    false
                }
                else -> {
                    publishOperationError("Runner返回未知控制结果")
                    false
                }
            }
        } catch (error: Exception) {
            publishOperationError(error.message ?: "Runner控制请求失败")
            false
        }
    }

    fun setFloatingControlEnabled(
        enabled: Boolean,
        requestId: Long = System.nanoTime(),
    ): Boolean {
        val service = remote
        val generation = lastSessionGeneration
        if (service == null || generation == null) return false
        return try {
            when (service.setFloatingControlEnabled(requestId, generation, enabled)) {
                RuntimeProtocol.SURFACE_ACCEPTED -> true
                RuntimeProtocol.SURFACE_SESSION_MISMATCH -> {
                    publishOperationError("Runner会话已重建，请刷新后重试")
                    false
                }
                RuntimeProtocol.SURFACE_PERMISSION_DENIED -> {
                    publishOperationError("尚未授予悬浮窗权限")
                    false
                }
                else -> false
            }
        } catch (error: Exception) {
            publishOperationError(error.message ?: "悬浮控制设置失败")
            false
        }
    }

    /** Keeps a small system camera on top of the target app until one Root screenshot is taken. */
    fun setCaptureOverlayEnabled(
        projectId: String,
        enabled: Boolean,
        requestId: Long = System.nanoTime(),
    ): Boolean {
        val service = remote
        val generation = lastSessionGeneration
        if (service == null || generation == null) {
            publishOperationError("Runner会话尚未建立")
            return false
        }
        return try {
            when (service.setCaptureOverlayEnabled(requestId, generation, projectId, enabled)) {
                RuntimeProtocol.SURFACE_ACCEPTED -> true
                RuntimeProtocol.SURFACE_SESSION_MISMATCH -> {
                    publishOperationError("Runner会话已重建，请刷新后重试")
                    false
                }
                RuntimeProtocol.SURFACE_PERMISSION_DENIED -> {
                    publishOperationError("尚未授予悬浮窗权限")
                    false
                }
                else -> {
                    publishOperationError("截图悬浮窗未能启动")
                    false
                }
            }
        } catch (error: Exception) {
            publishOperationError(error.message ?: "截图悬浮窗设置失败")
            false
        }
    }

    fun recentRuntimeLogs(maximumEntries: Int = 50): List<String> {
        val service = remote
        val generation = lastSessionGeneration
        if (service == null || generation == null || maximumEntries !in 1..100) return emptyList()
        return runCatching {
            service.getRecentRuntimeLogs(generation, maximumEntries).toList()
        }.getOrDefault(emptyList())
    }

    /** Cached bundled/provider state, with safe source/loaded metadata; not recognition success. */
    fun visionAvailability(): Map<String, String> {
        val service = remote ?: return emptyMap()
        val generation = lastSessionGeneration
        return runCatching {
            val records = service.getVisionAvailability()
            if (service !== remote || generation != lastSessionGeneration) emptyMap()
            else parseVisionAvailabilityRecords(records)
        }.getOrDefault(emptyMap())
    }

    fun debugSnapshot(projectId: String, expectedGeneration: Long? = lastSessionGeneration): RuntimeDebugReply {
        return debugReadGate.read { readDebugSnapshot(projectId, expectedGeneration) }
    }

    private fun readDebugSnapshot(projectId: String, expectedGeneration: Long?): RuntimeDebugReply {
        val service = remote ?: return RuntimeDebugReply.unavailable()
        val generation = expectedGeneration ?: return RuntimeDebugReply.unavailable()
        if (generation != lastSessionGeneration) return RuntimeDebugReply.unavailable(RuntimeDebugReply.SESSION_MISMATCH)
        val reply = runCatching { service.getDebugSnapshot(generation, projectId) }.getOrElse { RuntimeDebugReply.unavailable() }
        return if (service !== remote || generation != lastSessionGeneration ||
            (reply.status == RuntimeDebugReply.SUCCESS && reply.sessionGeneration != generation)) {
            RuntimeDebugReply.unavailable(RuntimeDebugReply.SESSION_MISMATCH)
        } else reply
    }

    fun inputFeatures(): Int {
        val service = remote ?: return 0
        val generation = lastSessionGeneration ?: return 0
        val features = runCatching { service.getInputFeatures(generation) and 15 }.getOrDefault(0)
        return if (remote === service && lastSessionGeneration == generation) features else 0
    }

    /** Waits for authoritative Root readiness; never polls and never enables Accessibility. */
    suspend fun startRecording(projectId: String, flowId: String, maxDurationMs: Int = 60_000, eventBudget: Int = 8192): Result<Long> {
        val service = remote ?: return Result.failure(IllegalStateException("Runner会话尚未建立"))
        val generation = lastSessionGeneration ?: return Result.failure(IllegalStateException("Runner会话尚未建立"))
        val id = recordingRandom.nextLong().and(Long.MAX_VALUE).coerceAtLeast(1)
        val lease = RecordingLease(id,generation,service)
        if (!recordingLease.compareAndSet(null,lease)) return Result.failure(IllegalStateException("已有录制，请先停止或取消"))
        completedRecording.value = null
        val completionQueued = java.util.concurrent.atomic.AtomicBoolean(false)
        val listener = object : com.autoscript.runtime.api.IInputRecordingListener.Stub() {
            override fun onFinished(reply: RecordingReply) {
                if (android.os.Binder.getCallingUid() != android.os.Process.myUid()) return
                if (recordingLease.get() !== lease || !acceptsRecordingCompletion(reply, lease.id, lease.generation,
                        remote === lease.service && lastSessionGeneration == lease.generation) ||
                    !completionQueued.compareAndSet(false, true)) return
                mainHandler.post {
                    if (recordingLease.get() === lease && acceptsRecordingCompletion(reply, lease.id, lease.generation,
                            remote === lease.service && lastSessionGeneration == lease.generation)) {
                        // One bounded slot; the editor consumes it only after start has returned its ID.
                        completedRecording.value = reply
                    }
                }
            }
        }
        var accepted = false
        try {
            require(maxDurationMs in 1000..60_000 && eventBudget in 128..16_384) { "录制预算无效" }
            require(awaitRootForOperation(service, generation) == RuntimeStartReadiness.READY) { "Root后端未就绪，或脚本仍在运行；请确认授权后主动重试" }
            currentCoroutineContext().ensureActive()
            require(recordingLease.get() === lease && remote === service && lastSessionGeneration == generation) { "录制会话已变化" }
            val reply = withContext(Dispatchers.IO) { service.startObservedInputRecording(id,generation,id,projectId,flowId,maxDurationMs,eventBudget,listener) }
            require(reply.valid() && reply.recordingId == id && reply.generation == generation && recordingLease.get() === lease && remote === service && lastSessionGeneration == generation) { "录制会话已变化" }
            require(reply.status == RecordingReply.SUCCESS) { recordingError(reply.status) }
            accepted = true
            return Result.success(id)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { return Result.failure(error) }
        finally {
            if (!accepted) {
                recordingLease.compareAndSet(lease,null)
                clearRecordingCompletion(lease.id, lease.generation)
                withContext(NonCancellable + Dispatchers.IO) { runCatching { service.cancelInputRecording(id,generation,id) } }
            }
        }
    }

    /** Identity-checked one-shot UI handoff. It never reads Binder or stops a newer recording. */
    fun consumeRecordingCompletion(recordingId: Long): RecordingReply? {
        val lease = recordingLease.get() ?: return null
        val reply = completedRecording.value ?: return null
        if (lease.id != recordingId) return null
        val result = takeRecordingCompletion(recordingLease, lease, reply, lease.id, lease.generation,
            remote === lease.service && lastSessionGeneration == lease.generation) ?: return null
        completedRecording.compareAndSet(reply, null)
        return result
    }

    private fun clearRecordingCompletion(id: Long, generation: Long) {
        val reply = completedRecording.value ?: return
        if (reply.recordingId == id && reply.generation == generation) completedRecording.compareAndSet(reply, null)
    }

    suspend fun finishRecording(recordingId: Long): Result<RecordingReply> = withContext(Dispatchers.IO) {
        val lease = recordingLease.get() ?: return@withContext Result.failure(IllegalStateException("录制会话已失效"))
        if (lease.id != recordingId || remote !== lease.service || lastSessionGeneration != lease.generation)
            return@withContext Result.failure(IllegalStateException("录制会话已变化"))
        try {
            val reply = lease.service.finishInputRecording(recordingId,lease.generation,recordingId)
            require(reply.valid() && reply.recordingId == recordingId && reply.generation == lease.generation &&
                remote === lease.service && lastSessionGeneration == lease.generation) { "录制结果已失效" }
            if (reply.status != RecordingReply.BUSY) {
                recordingLease.compareAndSet(lease,null)
                clearRecordingCompletion(lease.id, lease.generation)
            }
            Result.success(reply)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { Result.failure(error) }
    }

    suspend fun cancelRecording(recordingId: Long): Result<Unit> = withContext(NonCancellable + Dispatchers.IO) {
        val lease = recordingLease.get() ?: return@withContext Result.failure(IllegalStateException("录制会话已失效，未确认取消"))
        if (lease.id != recordingId) return@withContext Result.failure(IllegalStateException("录制会话已变化"))
        confirmRecordingCancel(recordingLease,lease,
            sessionCurrent = { remote === lease.service && lastSessionGeneration == lease.generation },
            cancel = { lease.service.cancelInputRecording(recordingId,lease.generation,recordingId) })
    }

    /** Window disposal has no coroutine lifetime; only a one-shot structured cancel is sent. */
    fun cancelRecordingOnClose() {
        completedRecording.value = null
        val lease = recordingLease.getAndSet(null) ?: return
        Thread({ runCatching { lease.service.cancelInputRecording(lease.id,lease.generation,lease.id) } },"recording-close").start()
    }

    private fun recordingError(status: Int): String = when (status) {
        RecordingReply.UNSUPPORTED_DEVICE -> "设备没有受支持的Root输入事件源"
        RecordingReply.BUSY -> "请先停止脚本或已有录制"
        RecordingReply.INVALID_EVENT -> "录制项目/插件无效，或project.json未声明input.basic能力"
        RecordingReply.SESSION_MISMATCH -> "录制会话已变化"
        else -> "录制无法启动：请检查Root与悬浮窗权限（$status）"
    }

    /** Called from the editor coroutine. Blocking feature/Binder reads never run on the UI thread. */
    suspend fun beginInputPointPick(projectId: String, flowId: String, action: InputPointAction, onResult: (InputPointPickReply) -> Unit): Long? {
        if (pendingInputPick != null) { publishOperationError("已有选点会话，请先取消"); return null }
        val service = remote ?: return null
        val generation = lastSessionGeneration ?: return null
        if (awaitRootForOperation(service, generation) != RuntimeStartReadiness.READY) {
            publishOperationError("Root 输入未连接，请确认授权后主动重试")
            return null
        }
        val features = withContext(Dispatchers.IO) { inputFeatures() }
        currentCoroutineContext().ensureActive()
        if (remote !== service || lastSessionGeneration != generation || pendingInputPick != null) return null
        if (!action.available(features)) { publishOperationError("Root输入能力不可用"); return null }
        val requestId = System.nanoTime().and(Long.MAX_VALUE).coerceAtLeast(1L)
        val pending = PendingInputPick(requestId, generation, features, onResult)
        pendingInputPick = pending
        val outcome = startInputPickOffUi(
            start = {
                service.beginInputPointPick(requestId, generation, projectId, flowId, action.wire, inputPickListener) == RuntimeProtocol.CONTROL_ACCEPTED
            },
            isCurrent = { remote === service && lastSessionGeneration == generation && pendingInputPick === pending },
            clearPending = { if (pendingInputPick === pending) pendingInputPick = null },
            cancel = { service.cancelInputPointPick(requestId, generation) },
        )
        if (outcome == InputPickStartOutcome.STALE) return null
        if (outcome == InputPickStartOutcome.REJECTED) {
            pendingInputPick = null
            publishOperationError("选点未能启动：请检查悬浮窗权限、Root状态，并先停止脚本")
            return null
        }
        return requestId
    }

    fun cancelInputPointPick(requestId: Long? = pendingInputPick?.id) {
        val pending = pendingInputPick ?: return
        if (requestId != pending.id) return
        pendingInputPick = null
        runCatching { remote?.cancelInputPointPick(pending.id, pending.generation) }
    }

    private fun failInputPointPick(message: String) {
        val pending = pendingInputPick ?: return
        pendingInputPick = null
        pending.callback(InputPointPickReply(pending.id, InputPointPickReply.FAILED, "", message = message))
    }

    fun requestStep(projectId: String, expectedGeneration: Long?): Boolean {
        return requestDebugControl(projectId, expectedGeneration, step = true)
    }

    fun resumeDebugProject(projectId: String, expectedGeneration: Long?): Boolean =
        requestDebugControl(projectId, expectedGeneration, step = false)

    private fun requestDebugControl(projectId: String, expectedGeneration: Long?, step: Boolean): Boolean {
        val service = remote ?: return false
        val generation = expectedGeneration ?: return false
        if (generation != lastSessionGeneration) return false
        return runCatching {
            if (service.getDebugSnapshot(generation, projectId).status != RuntimeDebugReply.SUCCESS) false
            else (if (step) service.requestStep(System.nanoTime(), generation)
                else service.requestResume(System.nanoTime(), generation)) == RuntimeProtocol.CONTROL_ACCEPTED
        }.getOrDefault(false)
    }

    /** Explicit preview on saved images; callers execute this bounded operation off the UI thread. */
    fun testRecognitionPreview(requestId: Long, operation: Int, frame: File, asset: File?, options: IntArray): RecognitionPreviewReply {
        val service = remote
        val generation = lastSessionGeneration ?: 0L
        fun failure(status: Int) = RecognitionPreviewReply(requestId, generation, status, operation)
        if (service == null || generation == 0L) return failure(RecognitionPreviewReply.SESSION_MISMATCH)
        if (requestId <= 0 || !RecognitionPreviewReply.validOptions(operation, options) || frame.length() !in 1..MAX_PREVIEW_FILE_BYTES ||
            (RecognitionPreviewReply.requiresTarget(operation) && (asset == null || asset.length() !in 1..if (operation in setOf(RecognitionPreviewReply.GLYPH,RecognitionPreviewReply.GLYPH_DETAILS)) 8L * 1024 * 1024 else MAX_PREVIEW_FILE_BYTES)) ||
            (!RecognitionPreviewReply.requiresTarget(operation) && asset != null)) return failure(RecognitionPreviewReply.INVALID_INPUT)
        return runCatching {
            ParcelFileDescriptor.open(frame, ParcelFileDescriptor.MODE_READ_ONLY).use { source ->
                asset?.let { ParcelFileDescriptor.open(it, ParcelFileDescriptor.MODE_READ_ONLY) }.use { target ->
                    val reply = service.testRecognitionPreview(requestId, generation, operation, source, target, options.copyOf())
                    if (lastSessionGeneration != generation || remote !== service || reply.requestId != requestId ||
                        reply.generation != generation || reply.operation != operation || reply.text.length > RecognitionPreviewReply.MAX_TEXT_LENGTH ||
                        reply.status !in RecognitionPreviewReply.SUCCESS..RecognitionPreviewReply.ENGINE_ERROR ||
                        reply.scorePermille !in 0..1000 || reply.coveragePermille !in 0..1000) failure(RecognitionPreviewReply.SESSION_MISMATCH) else reply
                }
            }
        }.getOrElse { failure(RecognitionPreviewReply.ENGINE_ERROR) }
    }

    fun cancelRecognitionPreview(requestId: Long) {
        val generation = lastSessionGeneration ?: return
        if (requestId > 0) runCatching { remote?.cancelRecognitionPreview(requestId, generation) }
    }

    fun isRecognitionPreviewCurrent(reply: RecognitionPreviewReply): Boolean =
        remote != null && lastSessionGeneration == reply.generation && reply.requestId > 0L

    /** Explicit preview on saved images; callers execute this bounded operation off the UI thread. */
    fun testTemplate(frame: File, template: File, tolerance: Int, similarityPermille: Int): TemplateMatchReply {
        val service = remote
        val generation = lastSessionGeneration
        if (service == null || generation == null) return TemplateMatchReply(TemplateMatchReply.SESSION_MISMATCH, -1, -1, 0)
        if (tolerance !in 0..255 || similarityPermille !in 0..1000 ||
            frame.length() !in 1..MAX_PREVIEW_FILE_BYTES || template.length() !in 1..MAX_PREVIEW_FILE_BYTES
        ) return TemplateMatchReply(TemplateMatchReply.INVALID_IMAGE, -1, -1, 0)
        return runCatching {
            ParcelFileDescriptor.open(frame, ParcelFileDescriptor.MODE_READ_ONLY).use { source ->
                ParcelFileDescriptor.open(template, ParcelFileDescriptor.MODE_READ_ONLY).use { target ->
                    service.testTemplate(generation, source, target, tolerance, similarityPermille)
                }
            }
        }.getOrElse { TemplateMatchReply(TemplateMatchReply.INVALID_IMAGE, -1, -1, 0) }
    }

    /** Requests a single Root-backed screenshot and copies its FD into the Studio-readable cache. */
    fun capturePreview(requestId: Long = System.nanoTime()): ScreenshotPreviewResult {
        val service = remote
        val generation = lastSessionGeneration
        if (service == null || generation == null) {
            return ScreenshotPreviewResult.Unavailable("Runner会话尚未建立")
        }
        return capturePreview(service, generation, requestId)
    }

    /** Keep the picker visible while Root starts; hide only for the actual captured frame. */
    suspend fun capturePreviewWhenReady(beforeCapture: suspend () -> Unit): ScreenshotPreviewResult {
        android.util.Log.i("ZhigouDiagnostics", "UI_CAPTURE_REQUESTED")
        val service = remote ?: return ScreenshotPreviewResult.Unavailable("Runner 未连接，请到我的 / 运行环境重新连接")
        val generation = lastSessionGeneration ?: return ScreenshotPreviewResult.Unavailable("Runner 会话尚未建立")
        when (awaitRootForOperation(service, generation)) {
            RuntimeStartReadiness.TIMEOUT -> {
                android.util.Log.w("ZhigouDiagnostics", "UI_CAPTURE_ROOT_WAIT_TIMEOUT")
                return ScreenshotPreviewResult.Unavailable("等待 Root 就绪超时；请确认已授权智构 Root，并查看运行环境诊断")
            }
            RuntimeStartReadiness.REJECTED -> {
                android.util.Log.w("ZhigouDiagnostics", "UI_CAPTURE_ROOT_WAIT_REJECTED")
                return ScreenshotPreviewResult.Unavailable(captureUnavailableMessage(connectionStates.value))
            }
            RuntimeStartReadiness.READY -> Unit
            RuntimeStartReadiness.WAITING -> error("Unexpected readiness")
        }
        beforeCapture()
        currentCoroutineContext().ensureActive()
        if (remote !== service || lastSessionGeneration != generation) {
            return ScreenshotPreviewResult.Unavailable("Runner 会话已变化，请重新截图")
        }
        var result: ScreenshotPreviewResult? = null
        try {
            withContext(Dispatchers.IO) {
                val work = currentCoroutineContext()
                result = capturePreview(service, generation, System.nanoTime()) { work.ensureActive() }
            }
            if (remote !== service || lastSessionGeneration != generation) {
                (result as? ScreenshotPreviewResult.Success)?.file?.delete()
                return ScreenshotPreviewResult.Unavailable("Runner 会话已变化，已丢弃旧截图，请重新截图")
            }
            return requireNotNull(result)
        } catch (cancelled: CancellationException) {
            (result as? ScreenshotPreviewResult.Success)?.file?.delete()
            throw cancelled
        }
    }

    private fun capturePreview(service: IRuntimeService, generation: Long, requestId: Long,
        checkpoint: () -> Unit = {}): ScreenshotPreviewResult {
        var target: File? = null
        return try {
            checkpoint()
            if (remote !== service || lastSessionGeneration != generation)
                return ScreenshotPreviewResult.Unavailable("Runner 会话已变化，请重新截图")
            val descriptor = service.capturePreview(requestId, generation)
                ?: return ScreenshotPreviewResult.Unavailable(captureUnavailableMessage(connectionStates.value))
            ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
                checkpoint()
                val targetFile = File.createTempFile("studio-preview-", ".png", appContext.cacheDir)
                target = targetFile
                FileOutputStream(targetFile).use { output ->
                    val buffer = ByteArray(PREVIEW_COPY_BUFFER_BYTES)
                    var total = 0L
                    while (true) {
                        checkpoint()
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (read == 0) continue
                        total = Math.addExact(total, read.toLong())
                        require(total <= MAX_PREVIEW_FILE_BYTES)
                        output.write(buffer, 0, read)
                    }
                    require(total > 0L)
                    output.flush()
                    output.fd.sync()
                }
            }
            checkpoint()
            if (remote !== service || lastSessionGeneration != generation) {
                target?.delete()
                ScreenshotPreviewResult.Unavailable("Runner 会话已变化，已丢弃旧截图，请重新截图")
            } else ScreenshotPreviewResult.Success(requireNotNull(target))
        } catch (cancelled: CancellationException) {
            target?.delete()
            throw cancelled
        } catch (error: Exception) {
            target?.delete()
            ScreenshotPreviewResult.Unavailable("截图预览读取失败 [CAPTURE_READ_FAILED]；请查看运行环境诊断")
        }
    }

    fun startScript(
        projectId: String = "",
        generatedLuaModule: ByteArray,
        capabilities: List<String>,
        designWidth: Int = 720,
        designHeight: Int = 1280,
        scaleMode: Int = RuntimeProtocol.SCALE_LETTERBOX,
        requestId: Long = System.nanoTime(),
    ): Boolean = startScript(remote, lastSessionGeneration, projectId, generatedLuaModule, capabilities,
        designWidth, designHeight, scaleMode, requestId)

    private fun startScript(service: IRuntimeService?, expectedGeneration: Long?, projectId: String,
        generatedLuaModule: ByteArray, capabilities: List<String>, designWidth: Int,
        designHeight: Int, scaleMode: Int, requestId: Long): Boolean {
        if (expectedGeneration == null) {
            publishOperationError("Runner会话尚未建立")
            return false
        }
        val invalidCapabilities = validateProjectCapabilities(capabilities)
        if (invalidCapabilities != null) {
            publishOperationError(invalidCapabilities)
            return false
        }
        return try {
            when (service?.startScript(
                requestId,
                expectedGeneration,
                projectId,
                generatedLuaModule,
                designWidth,
                designHeight,
                scaleMode,
                capabilities.sorted().toTypedArray(),
            )) {
                RuntimeProtocol.START_ACCEPTED -> {
                    true
                }
                RuntimeProtocol.START_SESSION_MISMATCH -> {
                    publishOperationError("Runner会话已重建，请刷新后重试")
                    false
                }
                RuntimeProtocol.START_INVALID_SCRIPT -> {
                    publishOperationError("脚本无效或Runner已在运行")
                    false
                }
                RuntimeProtocol.START_BACKEND_NOT_READY -> {
                    publishOperationError("Root运行后端尚未就绪或授权失败")
                    false
                }
                RuntimeProtocol.START_INVALID_PROJECT -> {
                    publishOperationError("项目设计参数或能力清单无效")
                    false
                }
                RuntimeProtocol.START_FOREGROUND_UNAVAILABLE -> {
                    publishOperationError("系统不允许启动前台运行服务，请保持应用在前台后重试")
                    false
                }
                RuntimeProtocol.START_VISION_PREPARATION_REQUIRED -> {
                    publishOperationError("视觉组件准备已取消、过期或发生变化，请重新运行")
                    false
                }
                else -> {
                    publishOperationError("Runner返回未知启动结果")
                    false
                }
            }
        } catch (error: RemoteException) {
            publishOperationError(error.message ?: "启动脚本失败")
            false
        }
    }

    private fun validateProjectResources(resources: List<RuntimeProjectResource>): String? {
        if (resources.size > MAX_PROJECT_RESOURCES) return "项目资源数量超过256"
        val paths = HashSet<String>(resources.size)
        for (resource in resources) {
            if (!resource.file.isFile || !resource.file.canRead()) return "项目资源不存在或不可读"
            if (!paths.add(resource.path)) return "项目资源路径重复：${resource.path}"
            val validPath = when (resource.kind) {
                RuntimeProjectResourceKind.AUDIO -> validCanonicalPath(resource.path, "assets/audio/") &&
                    resource.path.substringAfterLast('.').lowercase() in setOf("wav", "mp3", "ogg", "flac", "m4a") &&
                    resource.file.length() in 1..20L * 1024 * 1024
                RuntimeProjectResourceKind.IMAGE -> validCanonicalPath(
                    resource.path,
                    "assets/images/",
                ) && resource.file.length() in 1..MAX_IMAGE_SOURCE_BYTES
                RuntimeProjectResourceKind.GLYPH_DICTIONARY -> validCanonicalPath(
                    resource.path,
                    "dictionaries/",
                ) && resource.path.endsWith(".asglyph") &&
                    resource.file.length() in 1..MAX_DICTIONARY_BYTES
            }
            if (!validPath) return "项目资源路径或文件无效：${resource.path}"
        }
        return null
    }

    private fun validateProjectCapabilities(capabilities: List<String>): String? {
        if (capabilities.size > MAX_PROJECT_CAPABILITIES) return "项目能力数量超过64"
        if (capabilities.toSet().size != capabilities.size) return "项目能力声明重复"
        if (capabilities.any { it.length > MAX_CAPABILITY_LENGTH || !CAPABILITY.matches(it) }) {
            return "项目能力声明格式无效"
        }
        return null
    }

    private fun prepareProject(requestId: Long, service: IRuntimeService?, generation: Long?): Boolean {
        if (service == null || generation == null) {
            publishOperationError("Runner会话尚未建立")
            return false
        }
        return try {
            when (service.prepareProject(requestId, generation)) {
                RuntimeProtocol.PREPARE_ACCEPTED -> true
                RuntimeProtocol.PREPARE_SESSION_MISMATCH -> {
                    publishOperationError("Runner会话已重建，请重试")
                    false
                }
                RuntimeProtocol.PREPARE_BUSY -> {
                    publishOperationError("脚本正在运行或停止中")
                    false
                }
                RuntimeProtocol.PREPARE_ENGINE_ERROR -> {
                    publishOperationError("Runner无法初始化项目会话")
                    false
                }
                else -> {
                    publishOperationError("Runner返回未知准备结果")
                    false
                }
            }
        } catch (error: Exception) {
            publishOperationError(error.message ?: "准备项目失败")
            false
        }
    }

    private fun validCanonicalPath(path: String, prefix: String): Boolean =
        path.length in 1..MAX_RESOURCE_PATH_LENGTH &&
            path.startsWith(prefix) &&
            '\\' !in path &&
            '\u0000' !in path &&
            path.split('/').all { it.isNotEmpty() && it != "." && it != ".." }

    private fun stopPartiallyPreparedSession(requestId: Long, service: IRuntimeService?, generation: Long) {
        runCatching { service?.requestStop(requestId, generation) }
    }

    private fun publishRemoteState() {
        val service = remote ?: return
        try {
            val version = service.protocolVersion
            if (version != RuntimeProtocol.VERSION) {
                lastSessionGeneration = null
                publish(
                    RuntimeConnectionPhase.ERROR,
                    protocolVersion = version,
                    message = "Runner协议不兼容：需要v${RuntimeProtocol.VERSION}，实际v$version",
                )
                return
            }
            val generation = service.sessionGeneration
            lastSessionGeneration = generation
            lastEngineState = mapEngineState(service.runtimeStateCode)
            lastRootState = mapRootState(service.rootStateCode)
            publish(
                phase = RuntimeConnectionPhase.CONNECTED,
                protocolVersion = version,
                sessionGeneration = generation,
                engineState = lastEngineState,
                rootState = lastRootState,
            )
        } catch (error: RemoteException) {
            publish(RuntimeConnectionPhase.ERROR, message = error.message ?: "读取Runner状态失败")
        }
    }

    private fun publishOperationError(message: String) {
        val generation = lastSessionGeneration
        if (remote != null && generation != null) {
            publish(
                phase = RuntimeConnectionPhase.CONNECTED,
                protocolVersion = RuntimeProtocol.VERSION,
                sessionGeneration = generation,
                engineState = lastEngineState,
                rootState = lastRootState,
                message = message,
            )
        } else {
            publish(RuntimeConnectionPhase.ERROR, message = message)
        }
    }

    private fun publish(
        phase: RuntimeConnectionPhase,
        protocolVersion: Int? = null,
        sessionGeneration: Long? = null,
        engineState: RuntimeEngineState = RuntimeEngineState.UNKNOWN,
        rootState: RuntimeRootState = RuntimeRootState.UNKNOWN,
        message: String? = null,
    ) {
        val state = RuntimeConnectionState(
            phase = phase,
            protocolVersion = protocolVersion,
            sessionGeneration = sessionGeneration,
            engineState = engineState,
            rootState = rootState,
            message = message,
            stateRevision = stateRevision.incrementAndGet(),
        )
        connectionStates.value = state
        if (Looper.myLooper() == Looper.getMainLooper()) {
            onStateChanged?.invoke(state)
        } else {
            mainHandler.post { onStateChanged?.invoke(state) }
        }
    }

    private fun mapEngineState(code: Int): RuntimeEngineState = when (code) {
        RuntimeProtocol.STATE_IDLE -> RuntimeEngineState.IDLE
        RuntimeProtocol.STATE_RUNNING -> RuntimeEngineState.RUNNING
        RuntimeProtocol.STATE_PAUSED -> RuntimeEngineState.PAUSED
        RuntimeProtocol.STATE_STOPPING -> RuntimeEngineState.STOPPING
        RuntimeProtocol.STATE_STOPPED -> RuntimeEngineState.STOPPED
        RuntimeProtocol.STATE_FAILED -> RuntimeEngineState.FAILED
        else -> RuntimeEngineState.UNKNOWN
    }

    private fun mapRootState(code: Int): RuntimeRootState = when (code) {
        RuntimeProtocol.ROOT_STOPPED -> RuntimeRootState.STOPPED
        RuntimeProtocol.ROOT_STARTING -> RuntimeRootState.STARTING
        RuntimeProtocol.ROOT_READY -> RuntimeRootState.READY
        RuntimeProtocol.ROOT_FAILED -> RuntimeRootState.FAILED
        else -> RuntimeRootState.UNKNOWN
    }

    private companion object {
        const val MAX_PROJECT_RESOURCES = 256
        const val MAX_RESOURCE_PATH_LENGTH = 256
        const val MAX_DICTIONARY_BYTES = 8L * 1024 * 1024
        const val MAX_IMAGE_SOURCE_BYTES = 32L * 1024 * 1024
        const val MAX_FLOW_BYTES = 64 * 1024 * 1024
        const val MAX_PREVIEW_FILE_BYTES = 24L * 1024 * 1024
        const val PREVIEW_COPY_BUFFER_BYTES = 32 * 1024
        const val MAX_PROJECT_CAPABILITIES = 64
        const val MAX_CAPABILITY_LENGTH = 128
        val CAPABILITY = Regex("[a-z][a-z0-9]*(\\.[a-z][a-z0-9]*)+")
    }
}

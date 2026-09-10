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
import com.autoscript.runtime.api.IRuntimeService
import com.autoscript.runtime.api.IRuntimeStateListener
import com.autoscript.runtime.api.RuntimeProtocol
import com.autoscript.runtime.api.VisualCompileReply
import java.io.File
import java.io.FileOutputStream

enum class RuntimeProjectResourceKind {
    IMAGE,
    GLYPH_DICTIONARY,
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

class RuntimeClient(context: Context) {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var remote: IRuntimeService? = null
    private var bound = false
    @Volatile private var lastSessionGeneration: Long? = null
    @Volatile private var lastEngineState: RuntimeEngineState = RuntimeEngineState.UNKNOWN

    var onStateChanged: ((RuntimeConnectionState) -> Unit)? = null

    private val stateListener = object : IRuntimeStateListener.Stub() {
        override fun onRuntimeStateChanged(
            sessionGeneration: Long,
            stateCode: Int,
            diagnostic: String?,
        ) {
            mainHandler.post {
                if (remote == null) return@post
                lastSessionGeneration = sessionGeneration
                lastEngineState = mapEngineState(stateCode)
                publish(
                    phase = RuntimeConnectionPhase.CONNECTED,
                    protocolVersion = RuntimeProtocol.VERSION,
                    sessionGeneration = sessionGeneration,
                    engineState = lastEngineState,
                    message = diagnostic,
                )
            }
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
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
                lastSessionGeneration = generation
                lastEngineState = mapEngineState(service.runtimeStateCode)
                publish(
                    phase = RuntimeConnectionPhase.CONNECTED,
                    protocolVersion = version,
                    sessionGeneration = generation,
                    engineState = lastEngineState,
                )
                service.registerStateListener(stateListener)
            } catch (error: RemoteException) {
                lastSessionGeneration = null
                publish(RuntimeConnectionPhase.ERROR, message = error.message ?: "连接Runner失败")
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            remote = null
            lastSessionGeneration = null
            lastEngineState = RuntimeEngineState.UNKNOWN
            publish(RuntimeConnectionPhase.DISCONNECTED, message = "Runner连接已断开")
        }

        override fun onBindingDied(name: ComponentName) {
            remote = null
            bound = false
            lastSessionGeneration = null
            lastEngineState = RuntimeEngineState.UNKNOWN
            publish(RuntimeConnectionPhase.ERROR, message = "Runner进程已退出")
        }

        override fun onNullBinding(name: ComponentName) {
            remote = null
            lastSessionGeneration = null
            lastEngineState = RuntimeEngineState.UNKNOWN
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
        runCatching { remote?.unregisterStateListener(stateListener) }
        if (bound) appContext.unbindService(connection)
        bound = false
        remote = null
        lastSessionGeneration = null
        lastEngineState = RuntimeEngineState.UNKNOWN
        publish(RuntimeConnectionPhase.DISCONNECTED)
    }

    fun refresh() = publishRemoteState()

    fun validateScript(
        source: ByteArray,
        chunkName: String = "@main.lua",
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
                service.validateVisualDraft(
                    requestId,
                    generation,
                    projectId,
                    flowId,
                    descriptor,
                )
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
    ): Boolean {
        val expectedGeneration = lastSessionGeneration
        if (expectedGeneration == null) {
            publishOperationError("Runner会话尚未建立")
            return false
        }
        return try {
            val result = ParcelFileDescriptor.open(
                imageFile,
                ParcelFileDescriptor.MODE_READ_ONLY,
            ).use { descriptor ->
                remote?.registerTemplate(requestId, expectedGeneration, assetPath, descriptor)
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
    ): Boolean {
        val expectedGeneration = lastSessionGeneration
        if (expectedGeneration == null) {
            publishOperationError("Runner会话尚未建立")
            return false
        }
        return try {
            val result = ParcelFileDescriptor.open(
                dictionaryFile,
                ParcelFileDescriptor.MODE_READ_ONLY,
            ).use { descriptor ->
                remote?.registerDictionary(
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
    fun startProject(
        generatedLuaModule: ByteArray,
        resources: List<RuntimeProjectResource>,
        designWidth: Int = 720,
        designHeight: Int = 1280,
        scaleMode: Int = RuntimeProtocol.SCALE_LETTERBOX,
        requestId: Long = System.nanoTime(),
    ): Boolean {
        val invalid = validateProjectResources(resources)
        if (invalid != null) {
            publishOperationError(invalid)
            return false
        }
        val requestIds = runCatching {
            List(resources.size + 3) { offset -> Math.addExact(requestId, offset.toLong()) }
        }.getOrElse {
            publishOperationError("项目请求编号溢出")
            return false
        }
        if (!prepareProject(requestIds[0])) return false
        for ((index, resource) in resources.sortedBy(RuntimeProjectResource::path).withIndex()) {
            val resourceRequestId = requestIds[index + 1]
            val registered = when (resource.kind) {
                RuntimeProjectResourceKind.IMAGE -> registerTemplate(
                    resource.path,
                    resource.file,
                    resourceRequestId,
                )
                RuntimeProjectResourceKind.GLYPH_DICTIONARY -> registerDictionary(
                    resource.path,
                    resource.file,
                    resourceRequestId,
                )
            }
            if (!registered) {
                stopPartiallyPreparedSession(requestIds.last())
                return false
            }
        }
        val started = startScript(
            generatedLuaModule = generatedLuaModule,
            designWidth = designWidth,
            designHeight = designHeight,
            scaleMode = scaleMode,
            requestId = requestIds[resources.size + 1],
        )
        if (!started) stopPartiallyPreparedSession(requestIds.last())
        return started
    }

    fun requestStop(requestId: Long = System.nanoTime()) {
        val expectedGeneration = lastSessionGeneration
        if (expectedGeneration == null) {
            publishOperationError("Runner会话尚未建立")
            return
        }
        try {
            when (remote?.requestStop(requestId, expectedGeneration)) {
                RuntimeProtocol.STOP_ACCEPTED -> Unit
                RuntimeProtocol.STOP_SESSION_MISMATCH -> {
                    publishOperationError("Runner会话已重建，请刷新后重试")
                }
                RuntimeProtocol.STOP_ENGINE_ERROR -> {
                    publishOperationError("Runner未能接受停止请求")
                }
                else -> publishOperationError("Runner返回未知停止结果")
            }
        } catch (error: RemoteException) {
            publishOperationError(error.message ?: "停止请求失败")
        }
    }

    fun startScript(
        generatedLuaModule: ByteArray,
        designWidth: Int = 720,
        designHeight: Int = 1280,
        scaleMode: Int = RuntimeProtocol.SCALE_LETTERBOX,
        requestId: Long = System.nanoTime(),
    ): Boolean {
        val expectedGeneration = lastSessionGeneration
        if (expectedGeneration == null) {
            publishOperationError("Runner会话尚未建立")
            return false
        }
        return try {
            when (remote?.startScript(
                requestId,
                expectedGeneration,
                generatedLuaModule,
                designWidth,
                designHeight,
                scaleMode,
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
                    publishOperationError("项目设计尺寸或缩放模式无效")
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

    private fun prepareProject(requestId: Long): Boolean {
        val service = remote
        val generation = lastSessionGeneration
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

    private fun stopPartiallyPreparedSession(requestId: Long) {
        val generation = lastSessionGeneration ?: return
        runCatching { remote?.requestStop(requestId, generation) }
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
            publish(
                phase = RuntimeConnectionPhase.CONNECTED,
                protocolVersion = version,
                sessionGeneration = generation,
                engineState = lastEngineState,
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
        message: String? = null,
    ) {
        val state = RuntimeConnectionState(
            phase,
            protocolVersion,
            sessionGeneration,
            engineState,
            message,
        )
        if (Looper.myLooper() == Looper.getMainLooper()) {
            onStateChanged?.invoke(state)
        } else {
            mainHandler.post { onStateChanged?.invoke(state) }
        }
    }

    private fun mapEngineState(code: Int): RuntimeEngineState = when (code) {
        RuntimeProtocol.STATE_IDLE -> RuntimeEngineState.IDLE
        RuntimeProtocol.STATE_RUNNING -> RuntimeEngineState.RUNNING
        RuntimeProtocol.STATE_STOPPING -> RuntimeEngineState.STOPPING
        RuntimeProtocol.STATE_STOPPED -> RuntimeEngineState.STOPPED
        RuntimeProtocol.STATE_FAILED -> RuntimeEngineState.FAILED
        else -> RuntimeEngineState.UNKNOWN
    }

    private companion object {
        const val MAX_PROJECT_RESOURCES = 256
        const val MAX_RESOURCE_PATH_LENGTH = 256
        const val MAX_DICTIONARY_BYTES = 8L * 1024 * 1024
        const val MAX_IMAGE_SOURCE_BYTES = 32L * 1024 * 1024
        const val MAX_FLOW_BYTES = 64 * 1024 * 1024
    }
}

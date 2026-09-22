package com.autoscript.studio

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.autoscript.core.designsystem.AutoScriptPalette
import com.autoscript.project.store.MAX_LUA_SOURCE_BYTES
import com.autoscript.project.store.ProjectVariable
import com.autoscript.project.store.ProjectVariableScope
import com.autoscript.project.store.ProjectSnapshot
import com.autoscript.project.store.ProjectStore
import com.autoscript.core.model.RuntimeConnectionPhase
import com.autoscript.core.model.RuntimeConnectionState
import com.autoscript.core.model.RuntimeEngineState
import com.autoscript.runtime.client.RuntimeClient
import com.autoscript.runtime.client.ScreenshotPreviewResult
import com.autoscript.runtime.client.ScriptValidationResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

@Composable
internal fun LuaEditorScreen(
    snapshot: ProjectSnapshot,
    store: ProjectStore,
    runtimeClient: RuntimeClient,
    runtimeState: RuntimeConnectionState,
    consoleLines: List<String>,
    active: Boolean,
    initialImageToolBitmap: Bitmap? = null,
    initialImageToolToken: String? = null,
    modifier: Modifier = Modifier,
    onSnapshotChanged: (ProjectSnapshot) -> Unit,
    onInitialImageToolConsumed: () -> Unit = {},
    onExit: () -> Unit,
) {
    val projectId = snapshot.manifest.projectId
    var activeLuaPath by remember(projectId) { mutableStateOf(snapshot.manifest.entryPoint ?: "main.lua") }
    val initialSource = snapshot.luaSources[activeLuaPath].orEmpty()
    var buffer by remember(projectId) { mutableStateOf(LuaEditorBuffer.open(projectId, initialSource)) }
    var fieldValue by remember(projectId) { mutableStateOf(TextFieldValue(initialSource)) }
    var saving by remember(projectId) { mutableStateOf(false) }
    var runtimeAction by remember(projectId) { mutableStateOf<RuntimeAction?>(null) }
    var error by remember(projectId) { mutableStateOf<String?>(null) }
    var notice by remember(projectId) { mutableStateOf<String?>(null) }
    var confirmExit by remember(projectId) { mutableStateOf(false) }
    var activeToolPopup by remember(projectId) { mutableStateOf<LuaToolPopup?>(null) }
    var luaFileManagerVisible by remember(projectId) { mutableStateOf(false) }
    var luaVariableManagerVisible by remember(projectId) { mutableStateOf(false) }
    var screenshotLauncherVisible by remember(projectId) { mutableStateOf(false) }
    var showingImageTools by remember(projectId) { mutableStateOf(false) }
    var imageToolBitmap by remember(projectId) { mutableStateOf<Bitmap?>(null) }
    var imageToolCapturing by remember(projectId) { mutableStateOf(false) }
    var imageToolMessage by remember(projectId) { mutableStateOf<String?>(null) }
    var imageToolRequestId by remember(projectId) { mutableStateOf(0L) }
    var imageToolCaptureJob by remember(projectId) { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val sourceBytes = remember(buffer.text) { buffer.text.toByteArray(Charsets.UTF_8).size }
    val lineCount = remember(buffer.text) { buffer.text.count { it == '\n' } + 1 }
    val runtimeBusy = runtimeAction != null
    val canRun = runtimeState.phase == RuntimeConnectionPhase.CONNECTED &&
        runtimeState.engineState !in setOf(
            RuntimeEngineState.RUNNING,
            RuntimeEngineState.PAUSED,
            RuntimeEngineState.STOPPING,
        ) &&
        !runtimeBusy && !saving && sourceBytes <= MAX_LUA_SOURCE_BYTES
    val canStop = runtimeState.phase == RuntimeConnectionPhase.CONNECTED &&
        runtimeState.engineState in setOf(RuntimeEngineState.RUNNING, RuntimeEngineState.PAUSED) &&
        !runtimeBusy
    val canPauseResume = canStop
    val canEditProjectSettings = !buffer.isDirty && !saving && !runtimeBusy &&
        runtimeState.engineState !in setOf(
            RuntimeEngineState.RUNNING,
            RuntimeEngineState.PAUSED,
            RuntimeEngineState.STOPPING,
        )

    LaunchedEffect(active) {
        if (!active) {
            focusManager.clearFocus(force = true)
            confirmExit = false
        }
    }

    // Runtime's system camera returns here after it captured the *target app*. Take ownership
    // once so a normal recomposition cannot reopen the workbench or recycle this bitmap.
    LaunchedEffect(initialImageToolToken) {
        val incoming = initialImageToolBitmap ?: return@LaunchedEffect
        if (initialImageToolToken.isNullOrBlank()) return@LaunchedEffect
        imageToolCaptureJob?.cancel()
        imageToolRequestId += 1L
        imageToolCapturing = false
        imageToolBitmap?.recycle()
        imageToolBitmap = incoming
        imageToolMessage = "截图 ${incoming.width}×${incoming.height}"
        screenshotLauncherVisible = false
        showingImageTools = true
        onInitialImageToolConsumed()
    }

    DisposableEffect(projectId) {
        onDispose {
            imageToolCaptureJob?.cancel()
            imageToolBitmap?.recycle()
        }
    }

    fun requestExit() {
        if (buffer.isDirty) confirmExit = true else onExit()
    }

    fun save() {
        if (saving || !buffer.isDirty || sourceBytes > MAX_LUA_SOURCE_BYTES) return
        val submitted = buffer.text
        val expected = buffer.savedText
        saving = true
        error = null
        notice = null
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    store.saveLuaFile(projectId, activeLuaPath, submitted, expectedSource = expected)
                }
            }.onSuccess { persisted ->
                buffer = buffer.markSaved(submitted)
                onSnapshotChanged(persisted)
            }.onFailure { failure ->
                error = failure.message ?: "保存失败"
            }
            saving = false
        }
    }

    fun validate() {
        if (runtimeBusy || sourceBytes > MAX_LUA_SOURCE_BYTES) return
        val submitted = buffer.text.toByteArray(Charsets.UTF_8)
        runtimeAction = RuntimeAction.VALIDATING
        error = null
        notice = null
        scope.launch {
            when (val result = withContext(Dispatchers.IO) {
                runtimeClient.validateScript(submitted)
            }) {
                ScriptValidationResult.Valid -> notice = "Lua 5.4 语法校验通过"
                is ScriptValidationResult.Invalid -> error = result.diagnostic
                is ScriptValidationResult.Unavailable -> error = result.message
            }
            runtimeAction = null
        }
    }

    fun runProject() {
        if (!canRun) {
            if (runtimeState.phase != RuntimeConnectionPhase.CONNECTED) error = "Runner尚未连接"
            return
        }
        val submitted = buffer.text
        val expected = buffer.savedText
        runtimeAction = RuntimeAction.RUNNING
        error = null
        notice = null
        scope.launch {
            val persistedResult = runCatching {
                if (submitted == expected) snapshot else withContext(Dispatchers.IO) {
                    store.saveLuaFile(projectId, activeLuaPath, submitted, expectedSource = expected)
                }
            }
            val persisted = persistedResult.getOrElse { failure ->
                error = failure.message ?: "保存失败，未启动脚本"
                runtimeAction = null
                return@launch
            }
            if (submitted != expected) {
                buffer = buffer.markSaved(submitted)
                onSnapshotChanged(persisted)
            }
            val plan = runCatching {
                withContext(Dispatchers.IO) {
                    // A selected helper module is editable, but the project still starts from
                    // its declared entry.  The helper is already present in persisted.luaSources.
                    val entrySource = if (activeLuaPath == persisted.manifest.entryPoint) {
                        submitted
                    } else {
                        requireNotNull(persisted.luaSource) { "Lua 项目缺少入口源码" }
                    }
                    RuntimeProjectPlan.fromSnapshot(persisted, entrySource)
                }
            }.getOrElse { failure ->
                error = failure.message ?: "项目资源装载计划无效"
                runtimeAction = null
                return@launch
            }
            when (val validation = withContext(Dispatchers.IO) { runtimeClient.validateScript(plan.luaSource) }) {
                is ScriptValidationResult.Invalid -> {
                    error = validation.diagnostic
                    runtimeAction = null
                    return@launch
                }
                is ScriptValidationResult.Unavailable -> {
                    error = validation.message
                    runtimeAction = null
                    return@launch
                }
                ScriptValidationResult.Valid -> Unit
            }
            val started = withContext(Dispatchers.IO) {
                runtimeClient.startProject(
                    projectId = projectId,
                    generatedLuaModule = plan.luaSource,
                    resources = plan.resources,
                    capabilities = plan.capabilities,
                    designWidth = plan.designWidth,
                    designHeight = plan.designHeight,
                    scaleMode = plan.scaleMode,
                )
            }
            if (started) {
                notice = "脚本已提交运行"
            } else {
                error = runtimeState.message ?: "Runner拒绝启动脚本"
            }
            runtimeAction = null
        }
    }

    fun stopProject() {
        if (!canStop) return
        runtimeAction = RuntimeAction.STOPPING
        error = null
        notice = null
        scope.launch {
            val accepted = withContext(Dispatchers.IO) { runtimeClient.requestStop() }
            if (!accepted) error = runtimeState.message ?: "停止请求被Runner拒绝"
            runtimeAction = null
        }
    }

    fun pauseOrResumeProject() {
        if (!canPauseResume) return
        val resume = runtimeState.engineState == RuntimeEngineState.PAUSED
        runtimeAction = RuntimeAction.CONTROLLING
        error = null
        notice = null
        scope.launch {
            val accepted = withContext(Dispatchers.IO) {
                if (resume) runtimeClient.requestResume() else runtimeClient.requestPause()
            }
            if (!accepted) error = if (resume) "继续请求被拒绝" else "暂停请求被拒绝"
            runtimeAction = null
        }
    }

    /**
     * 截图文件只在 Studio cache 短暂停留：解码后立即删除，关闭工作台或切换项目会取消请求并回收位图。
     * 延迟期间保持工作台透明，用户可切换到目标 App，避免把编辑器本身截进去。
     */
    fun captureForImageTool(delayMillis: Long = 0L) {
        if (imageToolCapturing) return
        val requestId = imageToolRequestId + 1
        imageToolRequestId = requestId
        imageToolCapturing = true
        imageToolMessage = if (delayMillis > 0) "请在 3 秒内切换到需要取图的界面" else null
        imageToolCaptureJob?.cancel()
        imageToolCaptureJob = scope.launch {
            var decoded: Bitmap? = null
            try {
                delay(delayMillis)
                when (val result = withContext(Dispatchers.IO) { runtimeClient.capturePreview() }) {
                    is ScreenshotPreviewResult.Success -> {
                        decoded = try {
                            withContext(Dispatchers.IO) { BitmapFactory.decodeFile(result.file.path) }
                        } finally {
                            result.file.delete()
                        }
                        ensureActive()
                        if (requestId == imageToolRequestId && showingImageTools) {
                            imageToolBitmap?.recycle()
                            imageToolBitmap = decoded
                            decoded = null
                            imageToolMessage = imageToolBitmap?.let { "截图 ${it.width}×${it.height}" }
                                ?: "截图文件无法解码"
                        }
                    }
                    is ScreenshotPreviewResult.Unavailable -> if (requestId == imageToolRequestId) {
                        imageToolMessage = result.message
                    }
                }
            } catch (_: CancellationException) {
                // Leaving the workbench deliberately cancels the preview request.
            } catch (failure: Exception) {
                if (requestId == imageToolRequestId) imageToolMessage = failure.message ?: "截图失败"
            } finally {
                decoded?.recycle()
                if (requestId == imageToolRequestId) {
                    imageToolCapturing = false
                    imageToolCaptureJob = null
                }
            }
        }
    }

    BackHandler(enabled = active, onBack = ::requestExit)

    // 撤销/恢复：按停顿合并的文本快照栈，只在编辑器内有效。
    var undoStack by remember(projectId) { mutableStateOf(emptyList<TextFieldValue>()) }
    var redoStack by remember(projectId) { mutableStateOf(emptyList<TextFieldValue>()) }
    var lastEditAt by remember(projectId) { mutableStateOf(0L) }
    var settingsMenuVisible by remember(projectId) { mutableStateOf(false) }
    var projectSettingsVisible by remember(projectId) { mutableStateOf(false) }

    fun applyEdit(updated: TextFieldValue, coalesce: Boolean = true) {
        if (updated.text != fieldValue.text) {
            val now = System.currentTimeMillis()
            if (!coalesce || now - lastEditAt > EDIT_COALESCE_MILLIS || undoStack.isEmpty()) {
                undoStack = (undoStack + fieldValue).takeLast(MAX_UNDO_STEPS)
            }
            redoStack = emptyList()
            lastEditAt = now
        }
        fieldValue = updated
        buffer = buffer.edit(updated.text)
        error = null
        notice = null
    }

    fun undoEdit() {
        val previous = undoStack.lastOrNull() ?: return
        redoStack = redoStack + fieldValue
        undoStack = undoStack.dropLast(1)
        fieldValue = previous
        buffer = buffer.edit(previous.text)
        lastEditAt = 0L
    }

    fun redoEdit() {
        val next = redoStack.lastOrNull() ?: return
        undoStack = undoStack + fieldValue
        redoStack = redoStack.dropLast(1)
        fieldValue = next
        buffer = buffer.edit(next.text)
        lastEditAt = 0L
    }

    fun insertSnippet(snippet: String, summary: String = "已插入脚本片段") {
        LuaSnippetGate.reject(snippet)?.let { reason ->
            error = reason
            return
        }
        val selection = fieldValue.selection
        applyEdit(
            fieldValue.copy(
                text = fieldValue.text.replaceRange(selection.start, selection.end, snippet),
                selection = androidx.compose.ui.text.TextRange(selection.start + snippet.length),
            ),
            coalesce = false,
        )
        notice = summary
    }

    val projectFiles = remember(snapshot) { projectFileCatalog(snapshot) }
    var apiGroupTarget by remember(projectId) { mutableStateOf<String?>(null) }
    var rightToolDialog by remember(projectId) { mutableStateOf<LuaRightToolDialog?>(null) }
    fun showLibrary(group: String? = null) {
        apiGroupTarget = group
        activeToolPopup = LuaToolPopup.API
    }
    fun showSystemCamera() {
        if (runtimeState.phase != RuntimeConnectionPhase.CONNECTED) {
            error = "Runner尚未连接"
            return
        }
        error = null
        notice = "正在打开截图相机…"
        scope.launch {
            val shown = withContext(Dispatchers.IO) {
                runtimeClient.setCaptureOverlayEnabled(projectId = projectId, enabled = true)
            }
            if (shown) {
                screenshotLauncherVisible = false
                notice = "相机已显示在右侧；切到目标 App 后点相机截图"
            } else {
                // 悬浮窗权限被系统拒绝时，不能让“标注截屏”看起来像没有响应。
                // 保留同样的拖动/点击交互，在 Studio 内提供可用的兜底相机入口。
                screenshotLauncherVisible = true
                notice = "系统悬浮窗不可用，已显示编辑器内相机；点相机可立即取图"
            }
        }
    }
    Box(modifier = modifier.fillMaxSize().background(Color(0xFFE9EDF3))) {
        LuaFloatingWorkbench(
            projectName = snapshot.manifest.name,
            sourceName = activeLuaPath,
            lineCount = lineCount,
            dirty = buffer.isDirty,
            saving = saving,
            error = error ?: runtimeState.message,
            notice = notice,
            sourceTooLarge = sourceBytes > MAX_LUA_SOURCE_BYTES,
            sourceValue = fieldValue,
            onSourceValueChange = ::applyEdit,
            canSave = buffer.isDirty && !saving && !runtimeBusy && sourceBytes <= MAX_LUA_SOURCE_BYTES,
            canRun = if (canStop) true else canRun,
            runLabel = if (canStop) "停止" else if (runtimeAction == RuntimeAction.RUNNING) "启动中" else "运行",
            canScreenshot = runtimeState.phase == RuntimeConnectionPhase.CONNECTED && !imageToolCapturing,
            canUndo = undoStack.isNotEmpty(),
            onRun = if (canStop) ::stopProject else ::runProject,
            onScreenshot = ::showSystemCamera,
            onSave = ::save,
            onUndo = ::undoEdit,
            onFiles = { luaFileManagerVisible = true },
            onVariables = { luaVariableManagerVisible = true },
            onTools = { rightToolDialog = LuaRightToolDialog.TOOLS },
            onImage = { rightToolDialog = LuaRightToolDialog.IMAGE },
            onJudgment = { rightToolDialog = LuaRightToolDialog.JUDGMENT },
            onLoop = { rightToolDialog = LuaRightToolDialog.LOOP },
            onCommon = { rightToolDialog = LuaRightToolDialog.COMMON },
            onDebug = { rightToolDialog = LuaRightToolDialog.DEBUG },
            onFunction = { showLibrary(null) },
            onAi = { notice = "AI 辅助接口尚未开放" },
            onMenu = { settingsMenuVisible = true },
            onExit = ::requestExit,
        )
        DropdownMenu(
            expanded = settingsMenuVisible,
            onDismissRequest = { settingsMenuVisible = false },
            modifier = Modifier.align(Alignment.TopEnd).padding(top = 46.dp, end = 8.dp),
        ) {
            LuaChromeMenuItem("校验语法", enabled = !runtimeBusy && sourceBytes <= MAX_LUA_SOURCE_BYTES) { settingsMenuVisible = false; validate() }
            LuaChromeMenuItem(if (runtimeState.engineState == RuntimeEngineState.PAUSED) "继续运行" else "暂停运行", enabled = canPauseResume) { settingsMenuVisible = false; pauseOrResumeProject() }
            LuaChromeMenuItem("恢复", enabled = redoStack.isNotEmpty()) { settingsMenuVisible = false; redoEdit() }
            LuaChromeMenuItem("插入缩进") { settingsMenuVisible = false; applyEdit(insertEditorIndent(fieldValue), coalesce = false) }
            LuaChromeMenuItem("项目设置", enabled = canEditProjectSettings) { settingsMenuVisible = false; projectSettingsVisible = true }
            LuaChromeMenuItem("退出编辑") { settingsMenuVisible = false; requestExit() }
        }
        if (projectSettingsVisible) {
            ProjectSettingsDialog(snapshot, store, onSnapshotChanged) { projectSettingsVisible = false }
        }
        if (luaVariableManagerVisible) {
            VisualVariableManagerDialog(
                variables = snapshot.manifest.variables,
                currentFlowId = "",
                flows = emptyList(),
                allowFlowScope = false,
                onDismiss = { luaVariableManagerVisible = false },
                onSave = { variables ->
                    scope.launch {
                        runCatching {
                            withContext(Dispatchers.IO) {
                                store.updateVariables(projectId, variables, snapshot.manifest.variables)
                            }
                        }.onSuccess { persisted ->
                            onSnapshotChanged(persisted)
                            luaVariableManagerVisible = false
                            notice = "变量已保存"
                        }.onFailure { failure -> error = failure.message ?: "变量保存失败" }
                    }
                },
            )
        }
        if (luaFileManagerVisible) {
            LuaProjectFileManagerDialog(
                files = snapshot.manifest.luaFiles,
                directories = snapshot.luaDirectories,
                entryPath = snapshot.manifest.entryPoint ?: "main.lua",
                selectedPath = activeLuaPath,
                onDismiss = { luaFileManagerVisible = false },
                onSelect = { path ->
                    if (buffer.isDirty) {
                        error = "请先保存当前文件，再切换 Lua 文件"
                    } else {
                        val text = snapshot.luaSources[path]
                        if (text == null) error = "Lua 文件读取失败：$path" else {
                            activeLuaPath = path
                            buffer = LuaEditorBuffer.open("$projectId:$path", text)
                            fieldValue = TextFieldValue(text)
                            undoStack = emptyList()
                            redoStack = emptyList()
                            luaFileManagerVisible = false
                            notice = "已打开 $path"
                        }
                    }
                },
                onCreateFile = { path ->
                    scope.launch {
                        runCatching { withContext(Dispatchers.IO) { store.createLuaFile(projectId, path) } }
                            .onSuccess { persisted -> onSnapshotChanged(persisted); notice = "已创建 $path" }
                            .onFailure { failure -> error = failure.message ?: "创建 Lua 文件失败" }
                    }
                },
                onCreateFolder = { path ->
                    scope.launch {
                        runCatching { withContext(Dispatchers.IO) { store.createLuaDirectory(projectId, path) } }
                            .onSuccess { persisted -> onSnapshotChanged(persisted); notice = "已创建文件夹 $path" }
                            .onFailure { failure -> error = failure.message ?: "创建 Lua 文件夹失败" }
                    }
                },
            )
        }
        if (showingImageTools) {
            ImageToolWindow(
                bitmap = imageToolBitmap,
                designWidth = snapshot.manifest.design.width,
                designHeight = snapshot.manifest.design.height,
                scaleMode = snapshot.manifest.design.scaleMode,
                capturing = imageToolCapturing,
                message = imageToolMessage,
                onCapture = ::captureForImageTool,
                onEmit = { snippet -> insertSnippet(snippet.code, snippet.summary) },
                onCropToTemplate = { imageToolMessage = "Lua 工作台当前只生成代码；模板请从项目图像工具导入" },
                onClose = {
                    showingImageTools = false
                    imageToolRequestId += 1
                    imageToolCaptureJob?.cancel()
                    imageToolCapturing = false
                    imageToolBitmap?.recycle()
                    imageToolBitmap = null
                },
            )
        }
        if (screenshotLauncherVisible && !showingImageTools) {
            LuaScreenshotLauncher(
                onCapture = {
                    // The launcher must disappear before requesting the frame; otherwise it can
                    // be captured along with the editor. The visible delay remains zero.
                    screenshotLauncherVisible = false
                    showingImageTools = true
                    imageToolMessage = "正在截图…"
                    captureForImageTool(delayMillis = 0L)
                },
            )
        }
        activeToolPopup?.let { popup ->
            LuaToolPopupDialog(
                popup = popup,
                projectFiles = projectFiles,
                consoleLines = consoleLines,
                initialApiGroup = apiGroupTarget,
                onDismiss = { activeToolPopup = null },
                onInsert = ::insertSnippet,
                onOpenFile = { file ->
                    when (file.kind) {
                        StudioProjectFileKind.LUA -> notice = "main.lua 已在当前编辑器中打开"
                        StudioProjectFileKind.IMAGE, StudioProjectFileKind.GLYPH_DICTIONARY -> insertSnippet(ImageToolCodeGen.luaString(file.path), "已插入资源路径：${file.path}")
                        StudioProjectFileKind.MANIFEST -> notice = "项目配置请在“更多 > 项目设置”修改"
                        StudioProjectFileKind.FLOW -> notice = "Lua 项目不使用积木流程"
                    }
                },
            )
        }
        rightToolDialog?.let { dialog ->
            // 工具入口沿用编辑器参考面板：分区卡片、截图延迟与底部双操作，
            // 而不是在 Lua 工作台里另起一套通用 AlertDialog 样式。
            if (dialog == LuaRightToolDialog.TOOLS) {
                EditorEntryDialog(
                    entry = LegacyToolDialog.TOOLS,
                    projectName = snapshot.manifest.name,
                    files = projectFiles,
                    onDismiss = { rightToolDialog = null },
                    onInsert = { code ->
                        rightToolDialog = null
                        insertSnippet(code, "已插入截图语句")
                    },
                    onOpenImageTools = { delayMillis ->
                        rightToolDialog = null
                        if (delayMillis == 0L) {
                            // 标注截屏必须与 Lua 工作台原有截图入口共用同一条已验证链路：
                            // 先显示工作台右侧相机，再由 capturePreview() 取帧并打开图像工具。
                            // 不再经过系统悬浮窗的 PNG 跨界面交接路径。
                            screenshotLauncherVisible = true
                            notice = "相机已显示在右侧；点击相机开始截图"
                        } else {
                            showingImageTools = true
                            imageToolMessage = "请在 ${delayMillis / 1_000} 秒内切换到需要取图的界面"
                            captureForImageTool(delayMillis)
                        }
                    },
                    onOpenImageLibrary = {
                        rightToolDialog = null
                        activeToolPopup = LuaToolPopup.FILES
                    },
                    onRemoveCaptureOverlay = {
                        rightToolDialog = null
                        scope.launch {
                            val removed = withContext(Dispatchers.IO) {
                                runtimeClient.setCaptureOverlayEnabled(projectId = projectId, enabled = false)
                            }
                            if (removed) notice = "截图悬浮相机已移除" else {
                                error = runtimeState.message ?: "截图悬浮相机未能移除"
                            }
                        }
                    },
                )
            } else {
                LuaRightToolDialogScreen(
                    dialog = dialog,
                    onDismiss = { rightToolDialog = null },
                    onInsert = { code, summary ->
                        rightToolDialog = null
                        insertSnippet(code, summary)
                    },
                    onOpenCamera = {
                        rightToolDialog = null
                        showSystemCamera()
                    },
                    onOpenLibrary = { group ->
                        rightToolDialog = null
                        showLibrary(group)
                    },
                    onOpenLog = {
                        rightToolDialog = null
                        activeToolPopup = LuaToolPopup.LOG
                    },
                )
            }
        }
    }

    if (confirmExit) {
        LegacyPromptDialog(
            title = "放弃未保存修改？",
            text = "main.lua 中的修改尚未保存。返回后这些修改将丢失。",
            cancelLabel = "继续编辑",
            confirmLabel = "放弃修改",
            confirmDanger = true,
            onDismiss = { confirmExit = false },
            onConfirm = {
                confirmExit = false
                onExit()
            },
        )
    }
}

private enum class LuaToolPopup(val title: String) {
    API("方法库"),
    LOG("运行日志"),
    FILES("项目资源"),
}

/** File manager mirrors 易编's title entry: files are selected here; creation remains constrained by ProjectStore. */
@Composable
private fun LuaProjectFileManagerDialog(
    files: List<String>,
    directories: List<String>,
    entryPath: String,
    selectedPath: String,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
    onCreateFile: (String) -> Unit,
    onCreateFolder: (String) -> Unit,
) {
    var creating by remember { mutableStateOf<String?>(null) }
    var path by remember { mutableStateOf("") }
    StudioPanelDialog(title = "Lua 文件", onDismiss = onDismiss) {
        Column(Modifier.fillMaxWidth().heightIn(max = 440.dp).padding(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                LuaManagerAction("＋ 源文件", Modifier.weight(1f)) { creating = "file"; path = "lua/" }
                LuaManagerAction("＋ 文件夹", Modifier.weight(1f)) { creating = "folder"; path = "lua/" }
            }
            creating?.let { mode ->
                OutlinedTextField(
                    value = path,
                    onValueChange = { path = it },
                    label = { Text(if (mode == "file") "Lua 文件路径（如 lua/tools/helper.lua）" else "文件夹路径（如 lua/tools）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Text("取消", color = AutoScriptPalette.TextSecondary, fontSize = 11.sp, modifier = Modifier.clickable { creating = null }.padding(8.dp))
                    Text("创建", color = AutoScriptPalette.Accent, fontSize = 11.sp, modifier = Modifier.clickable {
                        val submitted = path.trim()
                        if (submitted.isNotEmpty()) {
                            if (mode == "file") onCreateFile(submitted) else onCreateFolder(submitted)
                            creating = null
                        }
                    }.padding(8.dp))
                }
            }
            Column(Modifier.fillMaxWidth().weight(1f, false).verticalScroll(rememberScrollState()).padding(top = 7.dp)) {
                Text("项目 Lua", color = AutoScriptPalette.TextSecondary, fontSize = 10.sp, modifier = Modifier.padding(vertical = 5.dp))
                directories.sorted().forEach { folder ->
                    Text("▾  $folder", color = AutoScriptPalette.Accent, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp))
                }
                files.sorted().forEach { file ->
                    val selected = file == selectedPath
                    Row(
                        Modifier.fillMaxWidth()
                            .background(if (selected) Color(0xFFEAF1FF) else Color.Transparent, RoundedCornerShape(3.dp))
                            .clickable { onSelect(file) }
                            .padding(horizontal = 9.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Lua", color = AutoScriptPalette.Accent, fontSize = 10.sp, modifier = Modifier.padding(end = 8.dp))
                        Text(file, color = AutoScriptPalette.TextPrimary, fontSize = 12.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (file == entryPath) Text("入口", color = AutoScriptPalette.TextSecondary, fontSize = 10.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun LuaManagerAction(label: String, modifier: Modifier, onClick: () -> Unit) {
    Text(
        label,
        color = AutoScriptPalette.Accent,
        textAlign = TextAlign.Center,
        fontSize = 11.sp,
        modifier = modifier.border(1.dp, AutoScriptPalette.Divider, RoundedCornerShape(3.dp)).clickable(onClick = onClick).padding(vertical = 8.dp),
    )
}

/** Mirrors the reference right rail: each entry owns a focused composer instead of a vague API list. */
private enum class LuaRightToolDialog(val title: String) {
    TOOLS("工具"),
    IMAGE("图像"),
    JUDGMENT("判断"),
    LOOP("循环"),
    COMMON("常用"),
    DEBUG("调试"),
}

@Composable
private fun LuaRightToolDialogScreen(
    dialog: LuaRightToolDialog,
    onDismiss: () -> Unit,
    onInsert: (String, String) -> Unit,
    onOpenCamera: () -> Unit,
    onOpenLibrary: (String?) -> Unit,
    onOpenLog: () -> Unit,
) {
    var leftValue by remember(dialog) { mutableStateOf("value") }
    var operator by remember(dialog) { mutableStateOf("==") }
    var rightValue by remember(dialog) { mutableStateOf("true") }
    var judgmentMode by remember(dialog) { mutableStateOf<String?>(null) }
    var loopMode by remember(dialog) { mutableStateOf<String?>(null) }
    var loopValue by remember(dialog) { mutableStateOf("3") }
    StudioPanelDialog(
        title = dialog.title,
        onDismiss = onDismiss,
        confirmLabel = "关闭",
    ) {
            Column(Modifier.fillMaxWidth().heightIn(max = 390.dp).verticalScroll(rememberScrollState()).padding(12.dp)) {
                when (dialog) {
                    LuaRightToolDialog.TOOLS -> {
                        Text("截屏与标注", color = AutoScriptPalette.Accent, fontSize = 11.sp, modifier = Modifier.padding(bottom = 5.dp))
                        LuaRightToolChoice("打开截图相机", "默认即时；切换到目标 App 后点右侧相机。", onOpenCamera)
                        LuaRightToolChoice("插入截图语句", "在脚本内采集并缓存当前屏幕帧。") {
                            onInsert("local frame = Screen.cache(Screen.capture())\n", "已插入截图与缓存")
                        }
                        LuaRightToolChoice("标注与图像处理", "使用截图工具生成坐标、区域、找色和找图代码。", onOpenCamera)
                    }
                    LuaRightToolDialog.IMAGE -> {
                        Text("图像识别", color = AutoScriptPalette.Accent, fontSize = 11.sp, modifier = Modifier.padding(bottom = 5.dp))
                        LuaRightToolChoice("区域找图", "插入模板加载与区域找图骨架。") {
                            onInsert(
                                "local frame = Screen.cache(Screen.capture())\n" +
                                    "local template = Screen.loadImage(\"assets/images/template.png\")\n" +
                                    "local hit = Screen.findImage(frame, template, 16, 900, 0, 0, 720, 1280)\n",
                                "已插入区域找图骨架",
                            )
                        }
                        LuaRightToolChoice("多点找色", "插入截图与多点找色骨架。") {
                            onInsert(
                                "local frame = Screen.cache(Screen.capture())\n" +
                                    "local samples = {}\n" +
                                    "local hit = Screen.findMultiColor(frame, 0xFFFFFF, 16, samples, 0, 0, 720, 1280)\n",
                                "已插入多点找色骨架",
                            )
                        }
                        LuaRightToolChoice("字库识字", "插入字库加载与字库 OCR 骨架。") {
                            onInsert(
                                "local frame = Screen.cache(Screen.capture())\n" +
                                    "local dictionary = Ocr.loadDictionary(\"dictionaries/main.asglyph\")\n" +
                                    "local text = Ocr.glyph(frame, dictionary, \"FFFFFF\", 16, 900, 0, 0, 720, 1280, 2)\n",
                                "已插入字库识字骨架",
                            )
                        }
                        LuaRightToolChoice("浏览图像 API", "查看全部找色、找图和 OCR 调用。") { onOpenLibrary("屏幕") }
                    }
                    LuaRightToolDialog.JUDGMENT -> {
                        if (judgmentMode == null) {
                            Text("先选择判断类型", color = AutoScriptPalette.TextSecondary, fontSize = 11.sp)
                            LuaRightToolChoice("如果", "满足条件时执行一段代码") { judgmentMode = "如果" }
                            LuaRightToolChoice("否则如果", "前一个条件不满足时继续判断") { judgmentMode = "否则如果" }
                            LuaRightToolChoice("否则", "所有条件不满足时执行") { judgmentMode = "否则" }
                        } else if (judgmentMode == "否则") {
                            Text("否则", color = AutoScriptPalette.Accent, fontSize = 14.sp)
                            Text("此分支不需要条件；请确保光标位于同一条 if 判断链中。", color = AutoScriptPalette.TextSecondary, fontSize = 11.sp)
                            LuaRightToolChoice("插入否则分支", "生成 else …") { onInsert("else\n    \n", "已插入否则分支") }
                            Text("← 返回选择", color = AutoScriptPalette.Accent, fontSize = 11.sp, modifier = Modifier.clickable { judgmentMode = null }.padding(top = 8.dp))
                        } else {
                            Text("${judgmentMode} · 左值 / 运算符 / 右值", color = AutoScriptPalette.TextSecondary, fontSize = 10.sp)
                            OutlinedTextField(leftValue, { leftValue = it }, label = { Text("左值（变量或表达式）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                            Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                listOf("==", "~=", ">", "<", ">=", "<=").forEach { candidate ->
                                    Text(
                                        candidate,
                                        color = if (operator == candidate) Color.White else AutoScriptPalette.TextPrimary,
                                        fontSize = 11.sp,
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier.weight(1f).background(if (operator == candidate) AutoScriptPalette.Accent else Color(0xFFF1F3F7), RoundedCornerShape(3.dp))
                                            .clickable { operator = candidate }.padding(vertical = 6.dp),
                                    )
                                }
                            }
                            OutlinedTextField(rightValue, { rightValue = it }, label = { Text("右值（变量或字面量）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                            val keyword = if (judgmentMode == "如果") "if" else "elseif"
                            LuaRightToolChoice("插入$judgmentMode", "生成 $keyword $leftValue $operator $rightValue then …") {
                                val code = if (judgmentMode == "如果") {
                                    "if $leftValue $operator $rightValue then\n    \nend\n"
                                } else {
                                    "elseif $leftValue $operator $rightValue then\n    \n"
                                }
                                onInsert(code, "已插入$judgmentMode")
                            }
                            Text("← 返回选择", color = AutoScriptPalette.Accent, fontSize = 11.sp, modifier = Modifier.clickable { judgmentMode = null }.padding(top = 8.dp))
                        }
                    }
                    LuaRightToolDialog.LOOP -> {
                        if (loopMode == null) {
                            Text("先选择循环类型", color = AutoScriptPalette.TextSecondary, fontSize = 11.sp)
                            LuaRightToolChoice("无限循环", "持续运行，循环内默认等待 100 毫秒") { loopMode = "无限循环" }
                            LuaRightToolChoice("限次循环", "设置重复次数") { loopMode = "限次循环" }
                            LuaRightToolChoice("限时循环", "设置循环总时长（毫秒）") { loopMode = "限时循环" }
                        } else {
                            Text("$loopMode", color = AutoScriptPalette.Accent, fontSize = 14.sp)
                            if (loopMode != "无限循环") {
                                OutlinedTextField(loopValue, { loopValue = it }, label = { Text(if (loopMode == "限次循环") "次数" else "毫秒") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                            }
                            LuaRightToolChoice("插入$loopMode", "循环内默认留出代码位置。") {
                            val code = when (loopMode) {
                                "无限循环" -> "while true do\n    Task.sleep(100)\nend\n"
                                "限时循环" -> "local deadlineMs = $loopValue\nwhile deadlineMs > 0 do\n    Task.sleep(100)\n    deadlineMs = deadlineMs - 100\nend\n"
                                else -> "for index = 1, ${loopValue.toLongOrNull()?.coerceAtLeast(1) ?: 1} do\n    \nend\n"
                            }
                            onInsert(code, "已插入$loopMode")
                        }
                            Text("← 返回选择", color = AutoScriptPalette.Accent, fontSize = 11.sp, modifier = Modifier.clickable { loopMode = null }.padding(top = 8.dp))
                        }
                    }
                    LuaRightToolDialog.COMMON -> {
                        LuaRightToolChoice("等待", "暂停 1000 毫秒。") { onInsert("Task.sleep(1000)\n", "已插入等待") }
                        LuaRightToolChoice("跳出循环", "仅能在循环体内使用。") { onInsert("break\n", "已插入 break") }
                        LuaRightToolChoice("返回", "结束当前 Lua 函数。") { onInsert("return\n", "已插入 return") }
                        LuaRightToolChoice("放置标记", "为 goto 定义跳转位置。") { onInsert("::label::\n", "已插入标记") }
                        LuaRightToolChoice("跳转标记", "跳到同一作用域的 label。") { onInsert("goto label\n", "已插入跳转") }
                    }
                    LuaRightToolDialog.DEBUG -> {
                        LuaRightToolChoice("信息日志", "向 Runner 控制台写入普通信息。") { onInsert("Log.info(\"message\")\n", "已插入信息日志") }
                        LuaRightToolChoice("警告日志", "向 Runner 控制台写入警告。") { onInsert("Log.warn(\"message\")\n", "已插入警告日志") }
                        LuaRightToolChoice("错误日志", "向 Runner 控制台写入错误。") { onInsert("Log.error(\"message\")\n", "已插入错误日志") }
                        LuaRightToolChoice("查看运行日志", "不插入代码，打开当前 Runner 日志。", onOpenLog)
                    }
                }
            }
    }
}

@Composable
private fun LuaRightToolChoice(title: String, detail: String, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(top = 5.dp).background(Color(0xFFF6F8FC), RoundedCornerShape(4.dp))
            .clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Text(title, color = AutoScriptPalette.TextPrimary, fontSize = 12.sp)
        if (detail.isNotBlank()) Text(detail, color = AutoScriptPalette.TextSecondary, fontSize = 9.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * Lua 的主工作台沿用旧编辑器的悬浮面板结构：标题、右侧工具轨、底部快捷操作，
 * 但中间始终是可直接编辑的 Lua，而不是积木树。这样准备脚本时不会混入项目列表页面。
 */
@Composable
private fun LuaFloatingWorkbench(
    projectName: String,
    sourceName: String,
    lineCount: Int,
    dirty: Boolean,
    saving: Boolean,
    error: String?,
    notice: String?,
    sourceTooLarge: Boolean,
    sourceValue: TextFieldValue,
    onSourceValueChange: (TextFieldValue) -> Unit,
    canSave: Boolean,
    canRun: Boolean,
    runLabel: String,
    canScreenshot: Boolean,
    canUndo: Boolean,
    onRun: () -> Unit,
    onScreenshot: () -> Unit,
    onSave: () -> Unit,
    onUndo: () -> Unit,
    onFiles: () -> Unit,
    onVariables: () -> Unit,
    onTools: () -> Unit,
    onImage: () -> Unit,
    onJudgment: () -> Unit,
    onLoop: () -> Unit,
    onCommon: () -> Unit,
    onDebug: () -> Unit,
    onFunction: () -> Unit,
    onAi: () -> Unit,
    onMenu: () -> Unit,
    onExit: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxSize().padding(6.dp),
        color = Color.White,
        shape = RoundedCornerShape(5.dp),
        shadowElevation = 5.dp,
    ) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().height(46.dp).padding(horizontal = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "☰",
                    color = AutoScriptPalette.Accent,
                    fontSize = 25.sp,
                    modifier = Modifier.size(36.dp).clickable(onClick = onMenu).padding(start = 7.dp, top = 2.dp),
                )
                Column(
                    Modifier.weight(1f).clickable(onClick = onFiles),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("Lua · $sourceName", color = AutoScriptPalette.TextPrimary, fontSize = 17.sp, maxLines = 1)
                    Text(projectName, color = AutoScriptPalette.TextSecondary, fontSize = 9.sp, maxLines = 1)
                }
                Text(
                    "×",
                    color = AutoScriptPalette.TextPrimary,
                    fontSize = 28.sp,
                    modifier = Modifier.size(36.dp).clickable(onClick = onExit).padding(start = 8.dp),
                )
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(AutoScriptPalette.Divider))
            Row(Modifier.fillMaxWidth().weight(1f)) {
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    (error ?: notice)?.let { message ->
                        Text(
                            message,
                            color = if (error != null) AutoScriptPalette.Danger else AutoScriptPalette.Accent,
                            fontSize = 10.sp,
                            modifier = Modifier.fillMaxWidth().background(Color(0xFFF8FAFD)).padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    }
                    if (sourceTooLarge) {
                        Text(
                            "源码超过 16 MiB，无法保存或运行",
                            color = AutoScriptPalette.Danger,
                            fontSize = 10.sp,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 3.dp),
                        )
                    }
                    LuaSourceField(
                        value = sourceValue,
                        onValueChange = onSourceValueChange,
                        lineCount = lineCount,
                        modifier = Modifier.fillMaxWidth().weight(1f),
                    )
                }
                Column(
                    Modifier.width(62.dp).fillMaxHeight().background(Color(0xFFF7F8FB))
                        .padding(horizontal = 4.dp, vertical = 5.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    LuaDockSideAction("变量", onVariables)
                    LuaDockSideAction("工具", onTools)
                    LuaDockSideAction("图像", onImage, enabled = canScreenshot)
                    LuaDockSideAction("判断", onJudgment)
                    LuaDockSideAction("循环", onLoop)
                    LuaDockSideAction("常用", onCommon)
                    LuaDockSideAction("调试", onDebug)
                    LuaDockSideAction("函数", onFunction)
                    LuaDockSideAction("AI", onAi)
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(AutoScriptPalette.Divider))
            Row(
                Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 7.dp, vertical = 5.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LuaDockBottomAction(runLabel, canRun, onRun, Modifier.weight(1f))
                LuaDockBottomAction("截图", canScreenshot, onScreenshot, Modifier.weight(1f))
                LuaDockBottomAction(if (saving) "保存中" else "保存", canSave, onSave, Modifier.weight(1f))
                LuaDockBottomAction("撤销", canUndo, onUndo, Modifier.weight(1f))
                LuaDockBottomAction("方法", true, onFunction, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun LuaDockSideAction(label: String, onClick: () -> Unit, enabled: Boolean = true) {
    Box(
        modifier = Modifier.fillMaxWidth().height(31.dp)
            .border(1.dp, Color(0xFFD0D5DE), RoundedCornerShape(4.dp))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (enabled) AutoScriptPalette.TextPrimary else AutoScriptPalette.TextSecondary,
            fontSize = 11.sp,
            lineHeight = 11.sp,
            style = CenteredLuaButtonTextStyle,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun LuaDockBottomAction(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    Box(
        modifier = modifier.fillMaxHeight()
            .background(if (enabled) Color(0xFFF1F5FF) else Color(0xFFF5F5F5), RoundedCornerShape(4.dp))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (enabled) AutoScriptPalette.Accent else AutoScriptPalette.TextSecondary,
            fontSize = 11.sp,
            lineHeight = 11.sp,
            style = CenteredLuaButtonTextStyle,
            textAlign = TextAlign.Center,
        )
    }
}

// Chinese glyphs otherwise retain asymmetric font padding inside an already-centered Box.
private val CenteredLuaButtonTextStyle = TextStyle(
    platformStyle = PlatformTextStyle(includeFontPadding = false),
)

/**
 * Lua 截图入口的相机浮标。它不直接截图：用户先把它挪到不挡操作的位置，再点相机取当前画面。
 *
 * 吸附规则与可视化编辑器的浮球一致：球体保持原尺寸，只把一部分收到屏幕边外。
 * 这样仍能看见相机图标并且容易再次拖出，而不是把整颗球缩小后留在内容区域里。
 */
@Composable
private fun LuaScreenshotLauncher(onCapture: () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val latestOnCapture by rememberUpdatedState(onCapture)
        val ballSize = 35.dp
        val ballSizePx = with(density) { ballSize.toPx() }
        val ballPeekPx = with(density) { 10.dp.toPx() }
        var ballX by remember { mutableStateOf(Float.NaN) }
        var ballY by remember { mutableStateOf(Float.NaN) }
        val maxWidth = constraints.maxWidth.toFloat()
        val maxBallX = (maxWidth - ballSizePx + ballPeekPx).coerceAtLeast(-ballPeekPx)
        val maxBallY = (constraints.maxHeight.toFloat() - ballSizePx).coerceAtLeast(0f)
        // 截图入口默认停在右侧；可视化编辑器同样使用这组边界和半屏判定规则。
        val resolvedBallX = (if (ballX.isNaN()) maxBallX else ballX).coerceIn(-ballPeekPx, maxBallX)
        val resolvedBallY = (if (ballY.isNaN()) maxBallY / 2f else ballY).coerceIn(0f, maxBallY)

        Box(
            modifier = Modifier
                .offset { IntOffset(resolvedBallX.roundToInt(), resolvedBallY.roundToInt()) }
                .size(ballSize)
                .background(Color(0xEE1F5EFF), CircleShape)
                .pointerInput(maxWidth, maxBallX, maxBallY) {
                    // 参考易编精灵的 m20 OnTouch：同一个触摸状态机同时处理点击和拖动。
                    // 不能把 clickable 与 detectDragGestures 叠加，否则二者会竞争同一串 PointerEvent，
                    // 容易出现“拖不动”或拖完误触发截图。
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val startBallX = resolvedBallX
                        val startBallY = resolvedBallY
                        val touchSlop = viewConfiguration.touchSlop
                        var dragging = false

                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            val displacement = change.position - down.position
                            val distanceSquared = displacement.x * displacement.x + displacement.y * displacement.y
                            if (!dragging && distanceSquared > touchSlop * touchSlop) {
                                dragging = true
                            }
                            if (dragging) {
                                ballX = (startBallX + displacement.x).coerceIn(-ballPeekPx, maxBallX)
                                ballY = (startBallY + displacement.y).coerceIn(0f, maxBallY)
                                change.consume()
                            }
                            if (!change.pressed) {
                                if (dragging) {
                                    val currentX = (startBallX + displacement.x)
                                        .coerceIn(-ballPeekPx, maxBallX)
                                    val center = currentX + ballSizePx / 2f
                                    ballX = if (center < maxWidth / 2f) -ballPeekPx else maxBallX
                                } else {
                                    latestOnCapture()
                                }
                                break
                            }
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(R.drawable.editor_capture_camera_24),
                contentDescription = "开始截图",
                tint = Color.White,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun LuaWorkbenchHeader(
    projectName: String,
    sourceName: String,
    lineCount: Int,
    dirty: Boolean,
    saving: Boolean,
    runtimeState: RuntimeEngineState,
    canScreenshot: Boolean,
    canSave: Boolean,
    primaryLabel: String,
    canPrimary: Boolean,
    onPrimary: () -> Unit,
    onScreenshot: () -> Unit,
    onSave: () -> Unit,
    onMore: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().background(LuaChromeBackground)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 9.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(projectName, color = LuaChromeText, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "$sourceName · ${if (dirty) "未保存" else "已保存"}",
                    color = if (dirty) LuaChromeUnsaved else LuaChromeMuted,
                    fontSize = 10.sp,
                    maxLines = 1,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                LuaWorkbenchAction("截图", canScreenshot, onScreenshot)
                LuaWorkbenchAction(if (saving) "保存中" else "保存", canSave, onSave)
                LuaWorkbenchAction(primaryLabel, canPrimary, onPrimary)
                LuaWorkbenchAction("菜单", true, onMore)
            }
        }
        Text(
            "$lineCount 行 · Runner $runtimeState",
            color = LuaChromeMuted,
            fontSize = 9.sp,
            modifier = Modifier.fillMaxWidth().background(LuaChromeDivider).padding(horizontal = 9.dp, vertical = 3.dp),
        )
    }
}

@Composable
private fun LuaWorkbenchAction(label: String, enabled: Boolean, onClick: () -> Unit) {
    Text(
        label,
        color = Color.White.copy(alpha = if (enabled) 1f else 0.35f),
        fontSize = 11.sp,
        modifier = Modifier
            .padding(start = 5.dp)
            .background(if (enabled) Color(0xFF344052) else Color(0xFF2B313D))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 7.dp, vertical = 5.dp),
    )
}

@Composable
private fun LuaToolPopupDialog(
    popup: LuaToolPopup,
    projectFiles: List<StudioProjectFile>,
    consoleLines: List<String>,
    initialApiGroup: String?,
    onDismiss: () -> Unit,
    onInsert: (String, String) -> Unit,
    onOpenFile: (StudioProjectFile) -> Unit,
) {
    // 易编精灵 guagua_fun_main.xml 是独立的三列函数页，而不是普通的搜索弹窗。
    // Lua 方法库直接沿用这个页面层级；详情页再承接函数参数与加入操作。
    if (popup == LuaToolPopup.API) {
        LuaFunctionBrowserDialog(initialApiGroup, onDismiss, onInsert)
        return
    }
    var query by remember { mutableStateOf("") }
    var selectedApiGroup by remember { mutableStateOf(initialApiGroup ?: "全部") }
    LaunchedEffect(initialApiGroup) { selectedApiGroup = initialApiGroup ?: "全部" }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(popup.title, color = AutoScriptPalette.TextPrimary, fontSize = 16.sp) },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 410.dp)) {
                when (popup) {
            LuaToolPopup.API -> {
                val groups = remember { LegacyFunctionCatalog.luaGroups() }
                BasicTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    textStyle = TextStyle(color = AutoScriptPalette.TextPrimary, fontSize = 12.sp),
                    modifier = Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 12.dp, vertical = 8.dp),
                    decorationBox = { input ->
                        Box {
                            if (query.isEmpty()) Text("搜索 API，例如 Input.tap、Screen.capture", color = AutoScriptPalette.TextSecondary, fontSize = 12.sp)
                            input()
                        }
                    },
                )
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                ) {
                    (listOf("全部") + groups.map { it.label }).forEach { label ->
                        val selected = selectedApiGroup == label
                        Text(
                            label,
                            color = if (selected) Color.White else AutoScriptPalette.TextSecondary,
                            fontSize = 11.sp,
                            modifier = Modifier
                                .padding(end = 5.dp)
                                .background(if (selected) AutoScriptPalette.Accent else Color(0xFFF0F3F8), CircleShape)
                                .clickable { selectedApiGroup = label }
                                .padding(horizontal = 9.dp, vertical = 5.dp),
                        )
                    }
                }
                val matchedGroups = remember(groups, query, selectedApiGroup) {
                    groups.mapNotNull { group ->
                        if (selectedApiGroup != "全部" && selectedApiGroup != group.label) return@mapNotNull null
                        val entries = group.entries.filter { entry ->
                            query.isBlank() || entry.title.contains(query, ignoreCase = true) ||
                                entry.detail.contains(query, ignoreCase = true) || entry.snippet.contains(query, ignoreCase = true)
                        }
                        entries.takeIf { it.isNotEmpty() }?.let { group.label to it }
                    }
                }
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 2.dp)) {
                    if (matchedGroups.isEmpty()) {
                        Text("没有匹配的已实现 API", color = AutoScriptPalette.TextSecondary, fontSize = 12.sp, modifier = Modifier.padding(12.dp))
                    }
                    matchedGroups.forEach { (label, entries) ->
                        Text(
                            label,
                            color = AutoScriptPalette.TextSecondary,
                            fontSize = 10.sp,
                            modifier = Modifier.padding(start = 3.dp, top = 5.dp, bottom = 3.dp),
                        )
                        entries.chunked(2).forEach { rowEntries ->
                            Row(Modifier.fillMaxWidth().padding(bottom = 5.dp)) {
                                rowEntries.forEach { entry ->
                                    Column(
                                        Modifier.weight(1f).heightIn(min = 54.dp)
                                            .background(Color(0xFFF5F7FB))
                                            .clickable { onInsert(entry.snippet, "已插入 ${entry.title}") }
                                            .padding(horizontal = 9.dp, vertical = 6.dp),
                                    ) {
                                        Text(entry.title, color = AutoScriptPalette.TextPrimary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(entry.detail, color = AutoScriptPalette.TextSecondary, fontSize = 9.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    }
                                    if (entry != rowEntries.last()) Spacer(Modifier.size(5.dp))
                                }
                                if (rowEntries.size == 1) Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
            LuaToolPopup.LOG -> Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(vertical = 3.dp)) {
                if (consoleLines.isEmpty()) Text("暂无运行日志。运行脚本后，Log.info / warn / error 会显示在这里。", color = AutoScriptPalette.TextSecondary, fontSize = 11.sp)
                consoleLines.takeLast(80).forEach { line -> Text(line, color = AutoScriptPalette.TextPrimary, fontSize = 10.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(vertical = 2.dp)) }
            }
            LuaToolPopup.FILES -> Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(vertical = 3.dp)) {
                projectFiles.forEach { file ->
                    Row(Modifier.fillMaxWidth().clickable { onOpenFile(file) }.padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(file.kind.symbol, color = AutoScriptPalette.TextSecondary, fontSize = 12.sp, modifier = Modifier.padding(end = 8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(file.path, color = AutoScriptPalette.TextPrimary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${file.kind.label} · ${file.sizeBytes} B", color = AutoScriptPalette.TextSecondary, fontSize = 10.sp)
                        }
                    }
                }
                Text("资源删除和项目配置在“更多 > 项目设置”中管理。", color = AutoScriptPalette.TextSecondary, fontSize = 10.sp, modifier = Modifier.padding(12.dp))
            }
        }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

private data class LuaFunctionCategory(val label: String, val groups: List<String>)

private val LuaFunctionCategories = listOf(
    LuaFunctionCategory("脚本控制", listOf("任务", "判断", "循环")),
    LuaFunctionCategory("按键函数", listOf("按键")),
    LuaFunctionCategory("图像函数", listOf("屏幕", "文字")),
    LuaFunctionCategory("系统函数", listOf("其它", "调试")),
    LuaFunctionCategory("兼容函数", listOf("旧版兼容")),
)

@Composable
private fun LuaFunctionBrowserDialog(
    initialGroup: String?,
    onDismiss: () -> Unit,
    onInsert: (String, String) -> Unit,
) {
    val groups = remember { LegacyFunctionCatalog.luaGroups() }
    val supported = remember(groups) { LuaFunctionCategories.filter { category -> category.groups.any { group -> groups.any { it.label == group } } } }
    var category by remember { mutableStateOf(supported.firstOrNull()?.label.orEmpty()) }
    var group by remember { mutableStateOf(initialGroup ?: supported.firstOrNull()?.groups?.firstOrNull().orEmpty()) }
    var entry by remember { mutableStateOf<LegacyFunctionEntry?>(null) }
    LaunchedEffect(initialGroup) {
        val initial = initialGroup ?: return@LaunchedEffect
        supported.firstOrNull { initial in it.groups }?.let { category = it.label; group = initial }
    }
    val categoryGroups = supported.firstOrNull { it.label == category }?.groups.orEmpty().filter { name -> groups.any { it.label == name } }
    val functions = groups.firstOrNull { it.label == group }?.entries.orEmpty()
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        // Keep this dialog in the same compact visual family as the Lua "工具" panel.
        // The browser still has three levels, but it must not cover the whole editor.
        Surface(
            Modifier.fillMaxWidth(.92f).height(390.dp).widthIn(max = 560.dp),
            color = Color.White,
            shape = RoundedCornerShape(3.dp),
            shadowElevation = 10.dp,
        ) {
            Column {
                Row(
                    Modifier.fillMaxWidth().height(38.dp).padding(start = 14.dp, end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("函数库", color = AutoScriptPalette.Accent, fontSize = 16.sp, modifier = Modifier.weight(1f))
                    Text(
                        "×",
                        color = AutoScriptPalette.TextSecondary,
                        fontSize = 23.sp,
                        modifier = Modifier.clickable(onClick = onDismiss).padding(horizontal = 8.dp),
                    )
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(AutoScriptPalette.Divider))
                Row(
                    Modifier.weight(1f).padding(8.dp)
                        .border(1.dp, Color(0xFFC7CBD1), RoundedCornerShape(3.dp))
                        .background(Color(0xFFFAFBFC), RoundedCornerShape(3.dp)),
                ) {
                    LuaFunctionColumn("分类", supported.map { it.label }, category, Modifier.weight(.86f), LuaFunctionColumnStyle.LEFT) { selected ->
                        category = selected
                        group = supported.first { it.label == selected }.groups.firstOrNull { name -> groups.any { it.label == name } }.orEmpty()
                    }
                    Box(Modifier.width(1.dp).fillMaxHeight().background(AutoScriptPalette.Divider))
                    LuaFunctionColumn("分组", categoryGroups, group, Modifier.weight(.90f), LuaFunctionColumnStyle.UNDERLINE) { group = it }
                    Box(Modifier.width(1.dp).fillMaxHeight().background(AutoScriptPalette.Divider))
                    LuaFunctionColumn("函数", functions.map { it.title }, "", Modifier.weight(1.24f), LuaFunctionColumnStyle.PLAIN) { selected -> entry = functions.firstOrNull { it.title == selected } }
                }
                Box(Modifier.fillMaxWidth().height(36.dp).border(1.dp, AutoScriptPalette.Divider).clickable(onClick = onDismiss), contentAlignment = Alignment.Center) {
                    Text("关闭", color = AutoScriptPalette.TextPrimary, fontSize = 13.sp, style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false)))
                }
            }
        }
    }
    entry?.let { selected -> LuaFunctionDetailDialog(selected, onDismiss = { entry = null }, onInsert = { onInsert(selected.snippet, "已插入 ${selected.title}"); entry = null; onDismiss() }) }
}

private enum class LuaFunctionColumnStyle { LEFT, UNDERLINE, PLAIN }

@Composable
private fun LuaFunctionColumn(title: String, items: List<String>, selected: String, modifier: Modifier, style: LuaFunctionColumnStyle, onSelect: (String) -> Unit) {
    Column(modifier.fillMaxHeight()) {
        Text(
            title,
            color = AutoScriptPalette.Accent,
            fontSize = 10.sp,
            modifier = Modifier.fillMaxWidth().height(25.dp).padding(start = 8.dp, top = 7.dp),
        )
        Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFFE7EAF0)))
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            items.forEach { item ->
                val active = item == selected
                Box(
                    Modifier.fillMaxWidth().height(32.dp)
                        .background(if (active) Color(0xFFEFF4FF) else Color.Transparent)
                        .clickable { onSelect(item) },
                ) {
                    if (active && style == LuaFunctionColumnStyle.LEFT) Box(Modifier.fillMaxHeight().width(2.dp).background(AutoScriptPalette.Accent))
                    if (active && style == LuaFunctionColumnStyle.UNDERLINE) Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(2.dp).background(AutoScriptPalette.Accent))
                    Text(
                        item,
                        color = if (active) AutoScriptPalette.Accent else AutoScriptPalette.TextPrimary,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxSize().padding(horizontal = 7.dp),
                        textAlign = TextAlign.Center,
                        style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false)),
                    )
                }
            }
        }
    }
}

@Composable
private fun LuaFunctionDetailDialog(entry: LegacyFunctionEntry, onDismiss: () -> Unit, onInsert: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth(.94f).fillMaxHeight(.78f).widthIn(max = 620.dp), color = Color(0xFFF6F7FA), shape = RoundedCornerShape(2.dp), shadowElevation = 10.dp) {
            Column {
                Text("${entry.title} · 函数说明", color = AutoScriptPalette.Accent, fontSize = 16.sp, modifier = Modifier.fillMaxWidth().background(Color.White).padding(14.dp))
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(10.dp)) {
                    LuaFunctionDocumentSection("参数") {
                        if (entry.parameters.isEmpty()) Text("无参数", fontSize = 12.sp, modifier = Modifier.padding(8.dp))
                        entry.parameters.forEachIndexed { index, parameter -> Text("${index + 1}  ·  $parameter", fontSize = 12.sp, modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 7.dp)) }
                    }
                    Spacer(Modifier.height(8.dp))
                    LuaFunctionDocumentSection("函数说明") { Text(entry.detail, fontSize = 12.sp, modifier = Modifier.padding(8.dp)) }
                    Spacer(Modifier.height(8.dp))
                    LuaFunctionDocumentSection("Lua 调用") { Text(entry.snippet.trim(), fontFamily = FontFamily.Monospace, fontSize = 12.sp, modifier = Modifier.padding(8.dp)) }
                }
                Row(Modifier.fillMaxWidth().height(42.dp).background(Color.White)) {
                    Box(Modifier.weight(1f).fillMaxHeight().clickable(onClick = onDismiss), contentAlignment = Alignment.Center) { Text("取消") }
                    Box(Modifier.width(1.dp).fillMaxHeight().background(AutoScriptPalette.Divider))
                    Box(Modifier.weight(1f).fillMaxHeight().clickable(onClick = onInsert), contentAlignment = Alignment.Center) { Text("加入", color = AutoScriptPalette.Accent) }
                }
            }
        }
    }
}

@Composable private fun LuaFunctionDocumentSection(title: String, content: @Composable () -> Unit) = Column(Modifier.fillMaxWidth().border(1.dp, AutoScriptPalette.Divider, RoundedCornerShape(3.dp)).background(Color.White)) { Text(title, color = AutoScriptPalette.Accent, fontSize = 11.sp, modifier = Modifier.fillMaxWidth().background(Color(0xFFF0F3F8)).padding(horizontal = 8.dp, vertical = 6.dp)); content() }

@Composable
private fun LuaSourceField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    lineCount: Int,
    modifier: Modifier = Modifier,
) {
    val verticalScroll = rememberScrollState()
    val horizontalScroll = rememberScrollState()
    val lineNumbers = remember(lineCount) {
        if (lineCount <= MAX_RENDERED_LINE_NUMBERS) {
            (1..lineCount).joinToString("\n")
        } else {
            "1\n…\n$lineCount"
        }
    }
    Box(modifier = modifier.background(Color.White)) {
        Row(Modifier.fillMaxSize().verticalScroll(verticalScroll)) {
            Text(
                text = lineNumbers,
                modifier = Modifier
                    .widthIn(min = 34.dp)
                    .background(LuaGutterBackground)
                    .padding(horizontal = 6.dp, vertical = 8.dp),
                color = AutoScriptPalette.TextSecondary,
                textAlign = TextAlign.End,
                style = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                ),
            )
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 480.dp)
                    .horizontalScroll(horizontalScroll)
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                textStyle = TextStyle(
                    color = AutoScriptPalette.TextPrimary,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                ),
                cursorBrush = SolidColor(AutoScriptPalette.Accent),
            )
        }
    }
}

@Composable
private fun LuaChromeMenuItem(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label, fontSize = 13.sp, color = AutoScriptPalette.TextPrimary) },
        enabled = enabled,
        onClick = onClick,
    )
}

private val LuaChromeBackground = Color(0xFF242A33)
private val LuaChromeDivider = Color(0xFF344052)
private val LuaChromeText = Color(0xFFE8EAED)
private val LuaChromeMuted = Color(0xFFC8CCD2)
private val LuaChromeUnsaved = Color(0xFFFFB4B4)
private val LuaGutterBackground = Color(0xFFF3F4F6)
private const val EDIT_COALESCE_MILLIS = 700L
private const val MAX_UNDO_STEPS = 200
private const val MAX_RENDERED_LINE_NUMBERS = 20_000

private enum class RuntimeAction {
    VALIDATING,
    RUNNING,
    CONTROLLING,
    STOPPING,
}

package com.autoscript.studio

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.random.Random
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.autoscript.core.designsystem.hairline
import com.autoscript.project.store.BackupProjectSummary
import com.autoscript.project.store.BackupSlot
import com.autoscript.project.store.ProjectFlow
import com.autoscript.project.store.ProjectSnapshot
import com.autoscript.project.store.ProjectSourceMode
import com.autoscript.project.store.ProjectStore
import com.autoscript.project.store.ProjectSummary
import com.autoscript.core.designsystem.AutoScriptPalette
import com.autoscript.core.model.RuntimeConnectionPhase
import com.autoscript.core.model.RuntimeConnectionState
import com.autoscript.core.model.RuntimeEngineState
import com.autoscript.runtime.client.RuntimeClient
import com.autoscript.runtime.client.ScreenshotPreviewResult
import com.autoscript.runtime.client.ScriptValidationResult
import com.autoscript.runtime.client.VisualCompileResult
import com.autoscript.runtime.api.InputPointPickReply
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun ProjectPage(
    store: ProjectStore,
    runtimeClient: RuntimeClient,
    runtimeState: RuntimeConnectionState,
    consoleLines: List<String>,
    active: Boolean,
    onFullScreenChanged: (Boolean) -> Unit,
    captureHandoff: StudioCaptureHandoff?,
    onCaptureHandoffConsumed: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var projects by remember { mutableStateOf<List<ProjectSummary>>(emptyList()) }
    var workspacePanel by remember { mutableStateOf(WorkspacePanel.PROJECTS) }
    var opened by remember { mutableStateOf<ProjectSnapshot?>(null) }
    var editingOpenedProject by remember { mutableStateOf(false) }
    var requestedFlowId by remember { mutableStateOf<String?>(null) }
    var designingRunnerUi by remember { mutableStateOf(false) }
    var showingImageTools by remember { mutableStateOf(false) }
    var showingImageLibrary by remember { mutableStateOf(false) }
    var floatingRecognitionTest by remember { mutableStateOf(false) }
    var floatingDebugInspector by remember { mutableStateOf<EditorToolPanel?>(null) }
    var floatingImageBlockKind by remember { mutableStateOf<String?>(null) }
    var floatingImageBlockValues by remember { mutableStateOf<JsonObject?>(null) }
    var floatingRecognitionInsert by remember { mutableStateOf<RecognitionInsertRequest?>(null) }
    var recognitionCaptureActive by remember { mutableStateOf(false) }
    var recognitionCaptureSelection by remember { mutableStateOf<ImageToolCodeGen.VisualSelection?>(null) }
    var recognitionCaptureTemplatePath by remember { mutableStateOf<String?>(null) }
    var floatingImageRegion by remember { mutableStateOf<ImageToolCodeGen.Roi?>(null) }
    var imageToolInitialMode by remember { mutableStateOf(ImageToolMode.TAP) }
    // 图像工具悬浮窗的状态。截图是 Root 通道的真实画面，不是占位图。
    var imageToolBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var imageToolCapturing by remember { mutableStateOf(false) }
    var imageToolCropping by remember { mutableStateOf(false) }
    var pendingImageTemplateRoi by remember { mutableStateOf<ImageToolCodeGen.Roi?>(null) }
    var imageToolMessage by remember { mutableStateOf<String?>(null) }
    var imageToolRequestId by remember { mutableIntStateOf(0) }
    var imageToolCaptureJob by remember { mutableStateOf<Job?>(null) }
    var handoffCaptureBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var handoffCaptureToken by remember { mutableStateOf<String?>(null) }
    var imageToolCropJob by remember { mutableStateOf<Job?>(null) }
    var showingPackager by remember { mutableStateOf(false) }
    var showingRecorder by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var expandedProjectIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var showCreate by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<ProjectSummary?>(null) }
    var deleteTarget by remember { mutableStateOf<ProjectSummary?>(null) }
    var settingsSnapshot by remember { mutableStateOf<ProjectSnapshot?>(null) }
    var exportTarget by remember { mutableStateOf<ProjectSummary?>(null) }
    var backupSlotsTarget by remember { mutableStateOf<BackupSlotsTarget?>(null) }
    var backupSlots by remember { mutableStateOf<List<BackupSlot>>(emptyList()) }
    var backupProgress by remember { mutableStateOf<String?>(null) }
    var backupMessage by remember { mutableStateOf<String?>(null) }
    var backups by remember { mutableStateOf<List<BackupProjectSummary>>(emptyList()) }
    var backupsRevision by remember { mutableStateOf(0) }
    var floatingEditorProject by remember { mutableStateOf<ProjectSummary?>(null) }
    var floatingEditorSnapshot by remember { mutableStateOf<ProjectSnapshot?>(null) }
    var floatingVariablesVisible by remember { mutableStateOf(false) }
    var floatingVisualEditor by remember { mutableStateOf<VisualEditorState?>(null) }
    var floatingEditorRevision by remember { mutableStateOf(0) }
    var floatingInsertSnippet by remember { mutableStateOf<String?>(null) }
    var floatingCalculationInsert by remember { mutableStateOf<RecognitionInsertRequest?>(null) }
    var floatingClipboard by remember { mutableStateOf<List<VisualSubtreeClipboard>?>(null) }
    var floatingPastePending by remember { mutableStateOf(false) }
    var floatingIndentSlots by remember { mutableStateOf<List<String>?>(null) }
    var floatingEditingNodeId by remember { mutableStateOf<String?>(null) }
    var floatingReflectionPosition by remember { mutableStateOf<EditorInsertPosition?>(null) }
    var floatingReflectionChildSlot by remember { mutableStateOf<String?>(null) }
    var showInterfacePreview by remember { mutableStateOf(false) }
    var floatingFlowId by remember { mutableStateOf<String?>(null) }
    val floatingStepState = rememberVisualStepState(floatingEditorProject?.projectId, runtimeState, runtimeClient)
    var floatingInputTarget by remember { mutableStateOf<InputPickTarget?>(null) }
    var floatingInputInsertion by remember { mutableStateOf<InputInsertionDraft?>(null) }
    var floatingInputRequest by remember { mutableStateOf<Long?>(null) }
    var floatingInputOpenRequest by remember { mutableIntStateOf(0) }
    var floatingTree by remember { mutableStateOf(SourceFileTree()) }
    var floatingSourceBusy by remember { mutableStateOf(false) }
    var floatingSourceMessage by remember { mutableStateOf<String?>(null) }
    var busyProjectId by remember { mutableStateOf<String?>(null) }
    val sourceFiles = remember(store) { ProjectSourceFiles(store) }
    val scope = rememberCoroutineScope()
    var inputBackendFeatures by remember { mutableIntStateOf(0) }
    LaunchedEffect(runtimeState.rootState, runtimeState.sessionGeneration) {
        inputBackendFeatures = 0
        inputBackendFeatures = withContext(Dispatchers.IO) { runtimeClient.inputFeatures() }
    }
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val currentExportTarget by rememberUpdatedState(exportTarget)

    fun clearImageToolState(cancelWork: Boolean = true) {
        imageToolRequestId += 1
        if (cancelWork) {
            imageToolCaptureJob?.cancel()
            imageToolCropJob?.cancel()
        }
        imageToolCaptureJob = null
        imageToolCropJob = null
        imageToolCapturing = false
        imageToolCropping = false
        pendingImageTemplateRoi = null
        imageToolBitmap?.recycle()
        imageToolBitmap = null
        imageToolMessage = null
    }

    SideEffect {
        onFullScreenChanged(opened != null || workspacePanel != WorkspacePanel.PROJECTS || backupSlotsTarget != null)
    }
    DisposableEffect(Unit) {
        onDispose {
            floatingInputRequest?.let(runtimeClient::cancelInputPointPick)
            onFullScreenChanged(false)
            handoffCaptureBitmap?.recycle()
        }
    }

    LaunchedEffect(floatingEditorProject?.projectId, floatingFlowId) {
        floatingInputRequest?.let(runtimeClient::cancelInputPointPick)
        floatingInputRequest = null
        val target = floatingInputTarget
        if (target?.projectId != floatingEditorProject?.projectId || target?.flowId != floatingFlowId) {
            floatingInputTarget = null
            floatingInputInsertion = null
        }
    }

    LaunchedEffect(captureHandoff?.token) {
        val handoff = captureHandoff ?: return@LaunchedEffect
        loading = true
        error = null
        val result = runCatching {
            withContext(Dispatchers.IO) {
                val snapshot = store.openProject(handoff.projectId)
                require(snapshot.manifest.sourceMode == ProjectSourceMode.LUA) {
                    "截图入口当前只支持 Lua 代码项目"
                }
                snapshot to decodeCaptureHandoff(context, handoff)
            }
        }
        result.onSuccess { (snapshot, bitmap) ->
            handoffCaptureBitmap?.recycle()
            handoffCaptureBitmap = bitmap
            handoffCaptureToken = handoff.token
            opened = snapshot
            editingOpenedProject = true
        }.onFailure { failure ->
            error = failure.message ?: "无法打开截图工具"
        }
        loading = false
        onCaptureHandoffConsumed()
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri ->
        val project = currentExportTarget
        exportTarget = null
        if (uri == null || project == null) return@rememberLauncherForActivityResult
        busyProjectId = project.projectId
        error = null
        notice = null
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val output = requireNotNull(context.contentResolver.openOutputStream(uri, "w")) {
                        "无法创建备份文件"
                    }
                    output.use { store.exportProjectBackup(project.projectId, it) }
                }
            }.onSuccess {
                notice = "“${project.name}”已导出为本地备份"
            }.onFailure { failure ->
                error = failure.message ?: "项目备份导出失败"
            }
            busyProjectId = null
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busyProjectId = IMPORT_BUSY_ID
        error = null
        notice = null
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val input = requireNotNull(context.contentResolver.openInputStream(uri)) {
                        "无法读取备份文件"
                    }
                    val imported = input.use(store::importProjectBackup)
                    imported to store.listProjects()
                }
            }.onSuccess { (imported, refreshed) ->
                projects = refreshed
                expandedProjectIds = expandedProjectIds + imported.manifest.projectId
                notice = "“${imported.manifest.name}”已作为新项目导入"
            }.onFailure { failure ->
                error = failure.message ?: "项目备份导入失败"
            }
            busyProjectId = null
        }
    }

    /**
     * 执行一次 Store 写操作并刷新项目列表。只有工具页（界面/打包/图片/录制）需要把结果留在
     * [opened] 里；新建/改名/删除只刷新列表，参考产品也不会在创建后跳进项目。
     */
    fun runStoreAction(openResult: Boolean = false, action: () -> ProjectSnapshot?) {
        scope.launch {
            loading = true
            error = null
            runCatching {
                withContext(Dispatchers.IO) {
                    val snapshot = action()
                    snapshot to store.listProjects()
                }
            }.onSuccess { (snapshot, refreshed) ->
                if (openResult && snapshot != null) opened = snapshot
                projects = refreshed
                expandedProjectIds = expandedProjectIds.intersect(refreshed.map { it.projectId }.toSet())
                    .ifEmpty { refreshed.firstOrNull()?.let { setOf(it.projectId) }.orEmpty() }
            }.onFailure { failure ->
                error = failure.message ?: "项目操作失败"
            }
            loading = false
        }
    }

    /** 槽位备份/恢复/删除：进行中显示 58dp 进度面板，结束后刷新槽位、备份列表和项目列表。 */
    fun runBackupSlotAction(label: String, action: () -> String) {
        if (backupProgress != null || busyProjectId != null) return
        backupProgress = label
        backupMessage = null
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { action() } }
                .onSuccess { message -> backupMessage = message }
                .onFailure { failure -> backupMessage = failure.message ?: "备份操作失败" }
            projects = withContext(Dispatchers.IO) { runCatching { store.listProjects() }.getOrDefault(projects) }
            backupsRevision++
            backupProgress = null
        }
    }

    fun openSettings(project: ProjectSummary) {
        if (busyProjectId != null) return
        busyProjectId = project.projectId
        error = null
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { store.openProject(project.projectId) } }
                .onSuccess { settingsSnapshot = it }
                .onFailure { failure -> error = failure.message ?: "读取项目设置失败" }
            busyProjectId = null
        }
    }

    // 悬浮小球的“停止”态：与 VisualProjectScreen.canStop 同一判定。
    val runtimeRunning = runtimeState.phase == RuntimeConnectionPhase.CONNECTED &&
        runtimeState.engineState in setOf(RuntimeEngineState.RUNNING, RuntimeEngineState.PAUSED)

    fun stopRunningProject() {
        if (!runtimeRunning || busyProjectId != null) return
        error = null
        scope.launch {
            val accepted = withContext(Dispatchers.IO) { runtimeClient.requestStop() }
            if (accepted) notice = "正在停止运行" else error = "停止请求未被 Runner 接受"
        }
    }

    var pendingRunInterface by remember { mutableStateOf<Triple<ProjectSummary, ProjectSnapshot, Boolean>?>(null) }
    fun runProject(project: ProjectSummary, saveCurrentFlowBeforeRun: Boolean = false, entry: StudioRunEntry = StudioRunEntry.EDITOR,
        uiValues: Map<String, String>? = null, expectedUi: com.google.gson.JsonObject? = null) {
        val singleStep = entry == StudioRunEntry.SINGLE_STEP
        if (busyProjectId != null) return
        if (runtimeState.phase != RuntimeConnectionPhase.CONNECTED) {
            error = "Runner尚未连接"
            return
        }
        if (runtimeState.engineState in setOf(
                RuntimeEngineState.RUNNING,
                RuntimeEngineState.PAUSED,
                RuntimeEngineState.STOPPING,
            )
        ) {
            error = "已有脚本正在运行，请先停止"
            return
        }
        val pendingEditor = if (saveCurrentFlowBeforeRun && project.sourceMode == ProjectSourceMode.VISUAL) {
            floatingVisualEditor ?: run {
                error = "程序树尚未加载完成"
                return
            }
        } else null
        val pendingFlow = pendingEditor?.takeIf { it.isDirty }?.let { editor ->
            val flowId = floatingFlowId ?: run {
                error = "当前插件尚未加载完成"
                return
            }
            Triple(flowId, editor.currentSource, editor.savedSource)
        }
        val selectedRunFlowId = if (saveCurrentFlowBeforeRun && project.sourceMode == ProjectSourceMode.VISUAL) {
            floatingFlowId ?: run {
                error = "当前插件尚未加载完成"
                return
            }
        } else null
        val stepNodeId = if (singleStep) floatingVisualEditor?.selectedNodeId else null
        if (singleStep && (stepNodeId == null || floatingVisualEditor?.isNodeDisabled(stepNodeId) != false)) {
            error = "请选择可执行积木"; return
        }
        busyProjectId = project.projectId
        error = null
        notice = null
        scope.launch {
            try {
                if (entry.opensInterface(hasDefinition = true, hasSubmittedValues = uiValues != null)) {
                    val uiSnapshot = runCatching { withContext(Dispatchers.IO) { store.openProject(project.projectId) } }
                        .getOrElse { error = it.message ?: "读取项目失败"; busyProjectId = null; return@launch }
                    if (entry.opensInterface(uiSnapshot.manifest.runnerUi != null)) {
                        pendingRunInterface = Triple(project, uiSnapshot, saveCurrentFlowBeforeRun)
                        busyProjectId = null; return@launch
                    }
                }
                var savedFlow: ProjectSnapshot? = null
                val result = runCatching {
                    withContext(Dispatchers.IO) {
                        pendingFlow?.let { (flowId, submitted, expected) ->
                            savedFlow = store.saveFlow(project.projectId, flowId, submitted, expected)
                        }
                        var snapshot = store.openProject(project.projectId)
                        if (uiValues != null) require(snapshot.manifest.runnerUi == expectedUi) { "脚本界面已变化，请重新运行" }
                        val plan = when (snapshot.manifest.sourceMode) {
                            ProjectSourceMode.LUA -> {
                                val source = requireNotNull(snapshot.luaSource) { "Lua项目缺少 main.lua" }
                                when (val validation = runtimeClient.validateScript(source.toByteArray(Charsets.UTF_8))) {
                                    ScriptValidationResult.Valid -> Unit
                                    is ScriptValidationResult.Invalid -> throw IllegalArgumentException(validation.diagnostic)
                                    is ScriptValidationResult.Unavailable -> throw IllegalStateException(validation.message)
                                }
                                RuntimeProjectPlan.fromSnapshot(snapshot, source)
                            }
                            ProjectSourceMode.VISUAL -> {
                                val generationId = when (
                                    val result = runtimeClient.compileVisualProject(project.projectId)
                                ) {
                                    is VisualCompileResult.Success -> result.generationId
                                    is VisualCompileResult.Invalid -> {
                                        val location = listOfNotNull(
                                            result.diagnostic.flowId?.let { "Flow $it" },
                                            result.diagnostic.nodeId?.let { "节点 $it" },
                                            result.diagnostic.line?.let { "第 $it 行" },
                                        ).joinToString(" · ")
                                        throw IllegalArgumentException(
                                            if (location.isEmpty()) result.diagnostic.message
                                            else "$location：${result.diagnostic.message}",
                                        )
                                    }
                                    is VisualCompileResult.Unavailable -> throw IllegalStateException(result.message)
                                }
                                snapshot = store.openProject(project.projectId)
                                RuntimeProjectPlan.fromVisualSnapshot(snapshot, generationId, selectedRunFlowId).let { if (singleStep) it.forSingleStep(selectedRunFlowId, stepNodeId) else it }
                            }
                        }
                        val runtimePlan = if (uiValues != null) plan.withInterface(snapshot, uiValues)
                            else if (!singleStep) plan.forEditorRun(snapshot) else plan
                        require(
                            runtimeClient.startProject(
                                projectId = project.projectId,
                                generatedLuaModule = runtimePlan.luaSource,
                                resources = plan.resources,
                                requiresPointerInput = plan.requiresPointerInput,
                                capabilities = plan.capabilities,
                                designWidth = plan.designWidth,
                                designHeight = plan.designHeight,
                                scaleMode = plan.scaleMode,
                                scriptUiJson = if (uiValues != null) snapshot.manifest.runnerUi?.toString() else null,
                                scriptUiValuesJson = com.google.gson.Gson().toJson(uiValues ?: emptyMap<String, String>()),
                            ),
                        ) { "Runner拒绝启动，请查看引擎状态" }
                        snapshot
                    }
                }
                savedFlow?.let { saved ->
                    pendingEditor?.markSaved(requireNotNull(pendingFlow).second)
                    floatingEditorSnapshot = result.getOrNull() ?: saved
                }
                result.onSuccess {
                    notice = if (singleStep) "单步已提交：前置步骤会执行，所选步骤后停在下一检查点；未到达所选步骤则结束"
                        else if (selectedRunFlowId == null) "“${project.name}”已提交运行"
                        else "插件 $selectedRunFlowId 已提交运行"
                    projects = withContext(Dispatchers.IO) { store.listProjects() }
                }.onFailure { failure ->
                    if (failure is CancellationException) throw failure
                    error = failure.message ?: "项目启动失败"
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error = failure.message ?: "项目启动失败"
            } finally {
                busyProjectId = null
            }
        }
    }

    pendingRunInterface?.let { (project, uiSnapshot, saveFlow) ->
        ScriptInterfaceDialog(requireNotNull(uiSnapshot.manifest.runnerUi), uiSnapshot.directory, project.projectId,
            onDismiss = { pendingRunInterface = null }, onConfirm = { values ->
                pendingRunInterface = null; runProject(project, saveFlow, StudioRunEntry.LAUNCH, uiValues = values, expectedUi = uiSnapshot.manifest.runnerUi)
            })
    }
    fun saveFloatingFlow(inputSummary: String? = null) {
        val project = floatingEditorProject ?: return
        if (floatingEditorSnapshot == null) return
        val editor = floatingVisualEditor ?: return
        val flowId = floatingFlowId ?: return
        if (!editorCanMutate(busyProjectId != null, editor.isReadOnly, runtimeState.engineState)) { error = "请停止运行后再修改插件"; return }
        if (busyProjectId != null || !editor.isDirty) return
        val submitted = editor.currentSource
        val expected = editor.savedSource
        busyProjectId = project.projectId
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    // Retry uses the same validation as the first input insertion; saving alone cannot bypass it.
                    when (val validation = runtimeClient.validateVisualDraft(project.projectId, flowId, submitted.toByteArray(Charsets.UTF_8))) {
                        is VisualCompileResult.Success -> Unit
                        is VisualCompileResult.Invalid -> error(validation.diagnostic.message)
                        is VisualCompileResult.Unavailable -> error(validation.message)
                    }
                    store.saveFlow(project.projectId, flowId, submitted, expected)
                }
            }.onSuccess { saved ->
                editor.markSaved(submitted)
                floatingEditorSnapshot = saved
                projects = withContext(Dispatchers.IO) { store.listProjects() }
                notice = inputSummary?.let { "已加入并保存：$it" } ?: "程序树已保存"
                error = null
            }.onFailure { failure ->
                // currentSource is intentionally retained; the user can retry
                // or open the full editor to resolve an external write conflict.
                error = if (inputSummary != null) "动作已加入草稿，尚未保存；请用保存重试：${failure.message.orEmpty()}"
                    else failure.message ?: "程序树保存失败"
            }
            busyProjectId = null
        }
    }

    fun insertFloatingBlock(
        snippet: String,
        childSlot: String?,
        position: EditorInsertPosition = EditorInsertPosition.BELOW,
    ) {
        val snapshot = floatingEditorSnapshot ?: return
        val editor = floatingVisualEditor ?: return
        val flowId = floatingFlowId ?: return
        if (!editorCanMutate(busyProjectId != null, editor.isReadOnly, runtimeState.engineState)) { error = "请停止运行后再修改插件"; return }
        if (snippet.trimStart().startsWith(LOOP_CONFIG_HINT) && loopInsertionFromHint(snippet) == null) {
            error = "循环配置无效，未加入积木"
            return
        }
        val capabilities = snapshot.manifest.capabilities.toSet()
        // 函数库直接给出积木 kind 时不做模糊搜索；能力不足要明确说明。
        val direct = FunctionCatalog.blockKindOf(snippet)?.let(BlockCatalog::find)
            ?.let { BlockSearchResult(it, it.requiredCapabilities - capabilities) }
        if (direct != null && !direct.isAvailable) {
            error = "${direct.contract.title}需要能力：${direct.missingCapabilities.joinToString()}"
            return
        }
        val matches = direct?.let(::listOf)
            ?: BlockCatalog.search(legacyDockBlockQuery(snippet), capabilities).filter(BlockSearchResult::isAvailable)
        val contract = matches.singleOrNull()
        if (contract == null) {
            error = if (matches.isEmpty()) "当前命令还没有可用的正式积木" else "命令匹配到多个积木，请进入完整编辑器选择"
            return
        }
        if (contract.contract.kind in visualRecognitionKinds) {
            floatingRecognitionInsert = RecognitionInsertRequest(contract.contract,
                legacyDockBlockArguments(contract.contract, snippet, initialRecognitionArguments(contract.contract, snapshot)), childSlot, position)
            recognitionCaptureSelection = null; recognitionCaptureTemplatePath = null
            return
        }
        val defaults = initialBlockArguments(
            contract.contract,
            snapshot.manifest.flows,
            snapshot.manifest.resources,
            flowId,
            preferredFlowId = if (contract.contract.kind == "flow.call") JumpPanelCode.callTargetId(snippet) else null,
        )
        if (defaults == null) {
            error = "缺少${contract.contract.title}所需的项目资源"
            return
        }
        val arguments = legacyDockBlockArguments(contract.contract, snippet, defaults)
        visualJumpInsertionError(contract.contract.kind, arguments, editor, childSlot, position)?.let {
            error = it
            return
        }
        if (contract.contract.kind == "variable.calculate" && variableCalculationArguments(snippet) == null) {
            floatingCalculationInsert = RecognitionInsertRequest(contract.contract, arguments, childSlot, position)
            return
        }
        if (position == EditorInsertPosition.REPLACE) {
            if (!editor.replaceSelectedBlock(contract.contract, arguments)) {
                error = "无法修改当前选择行"
                return
            }
            floatingEditorRevision++
            notice = "已修改：${contract.contract.title}"
            error = null
            saveFloatingFlow()
            return
        }
        val originalSelection = editor.selectedNodeId
        if (position == EditorInsertPosition.LIST_BOTTOM) {
            editor.selectedNodeId = editor.rows.lastOrNull { it.depth == 0 }?.nodeId
        }
        val inserted = editor.insertBlock(contract.contract, arguments, intoChildBlockName = childSlot)
        if (inserted != null && position == EditorInsertPosition.ABOVE && originalSelection != null) {
            editor.moveSelected(-1)
        }
        if (inserted == null) {
            error = if (contract.contract.kind in positionLoopKinds) "${contract.contract.title}只能加入循环体，请先选中循环体中的积木或选择循环体插入位置"
                else "无法把${contract.contract.title}加入当前位置"
            return
        }
        floatingEditorRevision++
        notice = "已加入：${contract.contract.title}"
        error = null
        if (contract.contract.kind == "flow.call") floatingEditingNodeId = inserted
        saveFloatingFlow()
    }

    /** Insert a related gesture as one editor transaction, then persist it once. */
    fun queueFloatingInput(snippet: ImageToolCodeGen.Snippet) {
        val project = floatingEditorProject ?: return
        val editor = floatingVisualEditor ?: return
        val flowId = floatingFlowId ?: return
        val target = floatingInputTarget ?: InputPickTarget(project.projectId, flowId, editor.currentSource, editor.selectedNodeId)
        val draft = target.draft(snippet)
        if (busyProjectId != null || !draft.matches(project.projectId, flowId, editor)) {
            error = "插件或内容已变化，请重新选点"
            showingImageTools = false
            clearImageToolState()
            return
        }
        runCatching { prepareInputBlocks(snippet, floatingEditorSnapshot?.manifest?.capabilities.orEmpty().toSet()) }
            .onFailure { error = it.message ?: "输入动作不可用"; imageToolMessage = error }
            .onSuccess {
                floatingInputInsertion = draft
                showingImageTools = false
                clearImageToolState()
                floatingInputOpenRequest++
            }
    }

    fun confirmFloatingInput(draft: InputInsertionDraft, position: EditorInsertPosition, slot: String?) {
        val project = floatingEditorProject ?: return
        val editor = floatingVisualEditor ?: return
        val flowId = floatingFlowId ?: return
        if (busyProjectId != null || runtimeState.engineState in setOf(RuntimeEngineState.RUNNING, RuntimeEngineState.PAUSED, RuntimeEngineState.STOPPING) ||
            !draft.matches(project.projectId, flowId, editor)) {
            error = "插件内容已变化或正在运行，请重新选点"
            floatingInputInsertion = null
            return
        }
        val prepared = runCatching { prepareInputBlocks(draft.snippet, floatingEditorSnapshot?.manifest?.capabilities.orEmpty().toSet()) }
            .getOrElse { error = it.message ?: "输入动作不可用"; return }
        if (!editor.insertBlocks(prepared, position, draft.anchorNodeId, slot)) {
            error = "无法在指定位置加入完整动作，未保留部分积木"
            return
        }
        floatingInputInsertion = null
        floatingInputTarget = null
        floatingEditorRevision++
        showingImageTools = false
        clearImageToolState()
        notice = "已加入草稿，正在保存：${draft.snippet.summary}"
        saveFloatingFlow(draft.snippet.summary)
    }

    /**
     * 图像工具的输出必须回到它打开时的悬浮编辑器，不能只停在系统剪贴板。
     * 可视化项目的输入与取色/多点结果走结构化数据；未映射的 Lua 片段仍只复制，不伪装成积木。
     */
    fun emitImageToolSnippet(snippet: ImageToolCodeGen.Snippet) {
        if (floatingEditorProject?.sourceMode == ProjectSourceMode.VISUAL && snippet.imageSelection != null) {
            val selection = snippet.imageSelection
            if (recognitionCaptureActive) {
                recognitionCaptureSelection = selection
                recognitionCaptureActive = false
                showingImageTools = false
                clearImageToolState()
                return
            }
            if (selection.mode == ImageToolMode.REGION) {
                floatingImageRegion = selection.roi
                imageToolMessage = "已选识别范围；返回图像面板选择识别积木"
                notice = imageToolMessage
                return
            }
            val draft = visualImageToolDraft(selection)
            if (draft == null) {
                imageToolMessage = "多点找色至少需要锚点和一个采样点"
                return
            }
            floatingImageBlockKind = draft.first
            floatingImageBlockValues = draft.second
            showingImageTools = false
            clearImageToolState()
            return
        }
        val project = floatingEditorProject
        if (project == null) {
            clipboard.setText(AnnotatedString(snippet.code))
            imageToolMessage = "没有可写入的编辑器，代码已复制：${snippet.summary}"
            return
        }
        if (project.sourceMode == ProjectSourceMode.VISUAL) {
            if (snippet.flowBlocks.isNotEmpty()) {
                queueFloatingInput(snippet)
                return
            }
            val visualKind = when {
                snippet.code.trimStart().startsWith("Input.tap(") -> "input.tap"
                snippet.code.trimStart().startsWith("Input.swipe(") -> "input.swipe"
                else -> null
            }
            if (visualKind == null) {
                clipboard.setText(AnnotatedString(snippet.code))
                imageToolMessage = "当前可视化项目只能直接加入单击/滑动；此 Lua 片段已复制"
                return
            }
            insertFloatingBlock("${FunctionCatalog.BLOCK_HINT_PREFIX}$visualKind\n${snippet.code}", null)
            imageToolMessage = if (error == null) "已加入程序树：${snippet.summary}" else error ?: "加入程序树失败"
            return
        }
        LuaSnippetGate.reject(snippet.code)?.let { reason ->
            imageToolMessage = reason
            return
        }
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val snapshot = store.openProject(project.projectId)
                    require(snapshot.manifest.sourceMode == ProjectSourceMode.LUA) {
                        "当前项目不是 Lua 源码项目"
                    }
                    val current = snapshot.luaSource.orEmpty()
                    val separator = if (current.isEmpty() || current.endsWith('\n')) "" else "\n"
                    store.saveLua(
                        projectId = project.projectId,
                        source = current + separator + snippet.code,
                        expectedSource = current,
                    )
                }
            }.onSuccess {
                imageToolMessage = "已加入 main.lua：${snippet.summary}"
                error = null
            }.onFailure { failure ->
                imageToolMessage = failure.message ?: "加入 main.lua 失败"
            }
        }
    }

    fun openFloatingInputPicker(mode: ImageToolMode) {
        val project = floatingEditorProject ?: return
        val editor = floatingVisualEditor ?: return
        val current = floatingEditorSnapshot ?: return
        val flowId = floatingFlowId ?: return
        if (busyProjectId != null || runtimeState.engineState in setOf(RuntimeEngineState.RUNNING, RuntimeEngineState.PAUSED, RuntimeEngineState.STOPPING)) {
            error = "请先停止运行"; return
        }
        val target = InputPickTarget(project.projectId, flowId, editor.currentSource, editor.selectedNodeId)
        floatingInputTarget = target
        val design = current.manifest.design
        floatingInputRequest = runtimeClient.beginInputPointPick(project.projectId, flowId, inputPointAction(mode)) { result ->
            floatingInputRequest = null
            if (result.status != InputPointPickReply.SUCCESS) error = result.message.ifBlank { "选点已取消" }
            else if (floatingInputTarget != target || floatingEditorSnapshot?.manifest?.design != design) error = "插件或设计分辨率已变化，请重新选点"
            else runCatching { inputPointSnippet(result, design.width, design.height, design.scaleMode) }
                .onSuccess(::queueFloatingInput).onFailure { error = it.message ?: "选点转换失败" }
        }
        if (floatingInputRequest != null) context.inputPickerActivity()?.moveTaskToBack(true)
        else error = "悬浮选点未启动，请检查悬浮窗权限和Root状态"
    }

    fun finishFloatingMutation(changed: Boolean, message: String) {
        if (!changed) {
            error = "当前节点不能执行此操作"
            return
        }
        floatingEditorRevision++
        notice = message
        error = null
        saveFloatingFlow()
    }

    fun pasteFloatingClipboard(childSlot: String?) {
        val editor = floatingVisualEditor ?: return
        val clipboard = floatingClipboard ?: run { error = "剪贴板为空"; return }
        finishFloatingMutation(
            editor.pasteSelection(clipboard, childSlot),
            "已粘贴节点副本",
        )
    }

    fun runFloatingCommand(command: EditorProgramCommand) {
        val editor = floatingVisualEditor ?: return
        if (!editorCanMutate(busyProjectId != null, editor.isReadOnly, runtimeState.engineState)) return
        when (command) {
            EditorProgramCommand.MOVE_UP -> finishFloatingMutation(editor.moveSelection(-1), "节点已上移")
            EditorProgramCommand.MOVE_DOWN -> finishFloatingMutation(editor.moveSelection(1), "节点已下移")
            EditorProgramCommand.OUTDENT -> finishFloatingMutation(editor.outdentSelection(), "节点已减少一级缩进")
            EditorProgramCommand.INDENT -> {
                val selected = editor.rows.firstOrNull { it.nodeId == editor.selectedRoots().firstOrNull() }
                val siblings = editor.rows.filter { it.blockId == selected?.blockId }.sortedBy { it.orderKey }
                val previous = siblings.getOrNull(siblings.indexOfFirst { it.nodeId == selected?.nodeId } - 1)
                val slots = previous?.kind?.let(BlockCatalog::find)?.childBlocks.orEmpty()
                when (slots.size) {
                    0 -> error = "上一节点不是容器"
                    1 -> finishFloatingMutation(editor.indentSelection(slots.single()), "节点已缩进")
                    else -> floatingIndentSlots = slots
                }
            }
            EditorProgramCommand.COPY -> {
                floatingClipboard = editor.copySelection().takeIf { it.isNotEmpty() }
                if (floatingClipboard == null) error = "请先选择节点" else {
                    error = null
                    notice = "已复制整棵子树"
                }
            }
            EditorProgramCommand.CUT -> {
                val copied = editor.copySelection().takeIf { it.isNotEmpty() }
                if (copied == null) error = "请先选择节点" else {
                    floatingClipboard = copied
                    finishFloatingMutation(editor.deleteSelection(), "已剪切整棵子树")
                }
            }
            EditorProgramCommand.PASTE -> {
                if (floatingClipboard == null) error = "剪贴板为空" else {
                    val selected = editor.rows.firstOrNull { it.nodeId == editor.selectedNodeId }
                    val slots = selected?.kind?.let(BlockCatalog::find)?.childBlocks.orEmpty()
                    if (selected != null && slots.isNotEmpty()) floatingPastePending = true
                    else pasteFloatingClipboard(null)
                }
            }
            EditorProgramCommand.UNDO -> finishFloatingMutation(editor.undo(), "已撤销")
            EditorProgramCommand.REDO -> finishFloatingMutation(editor.redo(), "已重做")
            EditorProgramCommand.DATA_BACKFILL -> {
                error = null
                floatingDebugInspector = EditorToolPanel.DATA_BACKFILL
            }
            EditorProgramCommand.TOGGLE_DISABLED -> finishFloatingMutation(editor.toggleDisabledSelection(), "已更新积木执行状态")
            EditorProgramCommand.SEARCH,
            EditorProgramCommand.EXPAND_ALL,
            EditorProgramCommand.COLLAPSE_ALL -> Unit
        }
    }

    /** 把悬浮程序树切到指定 Flow；源文件管理的“确定”、新建后自动打开都走这里。 */
    fun showFloatingFlow(snapshot: ProjectSnapshot, flowId: String) {
        val flow = snapshot.manifest.flows.singleOrNull { it.flowId == flowId } ?: return
        floatingFlowId = flowId
        floatingEditingNodeId = null
        floatingReflectionPosition = null
        floatingCalculationInsert = null
        floatingVisualEditor = VisualEditorState.create(snapshot.flowSources[flowId].orEmpty(), flow.rootBlockId)
        floatingClipboard = null
        floatingEditorRevision++
    }
    LaunchedEffect(floatingStepState.position) {
        val position = floatingStepState.position ?: return@LaunchedEffect
        val current = floatingEditorSnapshot ?: return@LaunchedEffect
        if (position.flowId != floatingFlowId && floatingVisualEditor?.isDirty != true) {
            showFloatingFlow(current, position.flowId)
        }
    }

    /** 源文件操作前先把当前程序树落盘；失败时返回 null 并保留未保存内容。 */
    suspend fun persistFloatingFlow(): ProjectSnapshot? {
        val project = floatingEditorProject ?: return null
        val current = floatingEditorSnapshot ?: return null
        val editor = floatingVisualEditor ?: return current
        val flowId = floatingFlowId ?: return current
        if (!editor.isDirty) return current
        val submitted = editor.currentSource
        val expected = editor.savedSource
        return runCatching {
            withContext(Dispatchers.IO) { store.saveFlow(project.projectId, flowId, submitted, expected) }
        }.onSuccess { saved ->
            editor.markSaved(submitted)
            floatingEditorSnapshot = saved
        }.onFailure { failure ->
            error = failure.message ?: "程序树保存失败"
        }.getOrNull()
    }

    fun handleFloatingSourceAction(sourceAction: SourceManagerAction) {
        if (floatingEditorProject == null || busyProjectId != null || floatingSourceBusy) return
        floatingSourceBusy = true
        floatingSourceMessage = null
        scope.launch {
            val base = persistFloatingFlow()
            if (base == null) {
                floatingSourceBusy = false
                return@launch
            }
            val result = runCatching {
                when (sourceAction) {
                    is SourceManagerAction.Open -> {
                        showFloatingFlow(base, sourceAction.flowId)
                        null
                    }
                    is SourceManagerAction.InsertCall -> {
                        val target = base.manifest.flows.firstOrNull { it.flowId == sourceAction.flowId }
                        val editor = floatingVisualEditor
                        if (target == null || editor == null) error = "源文件不存在" else {
                            finishFloatingMutation(
                                editor.insertFlowCall(target.flowId, defaultFlowCallArguments(target)) != null,
                                "已加入调用：${target.displayName()}",
                            )
                        }
                        null
                    }
                    is SourceManagerAction.CreateFile -> withContext(Dispatchers.IO) {
                        sourceFiles.createFlow(base, sourceAction.name, sourceAction.group)
                    }.also { updated ->
                        val created = updated.manifest.flows.map(ProjectFlow::flowId) -
                            base.manifest.flows.map(ProjectFlow::flowId).toSet()
                        created.singleOrNull()?.let { showFloatingFlow(updated, it) }
                    }
                    is SourceManagerAction.SaveAs -> withContext(Dispatchers.IO) {
                        sourceFiles.copyFlow(base, sourceAction.flowId, sourceAction.name)
                    }
                    is SourceManagerAction.RenameFile -> withContext(Dispatchers.IO) {
                        sourceFiles.renameFlow(base, sourceAction.flowId, sourceAction.name)
                    }
                    is SourceManagerAction.Delete -> {
                        val deletion = withContext(Dispatchers.IO) {
                            if (sourceAction.groups.isNotEmpty()) sourceFiles.deleteGroups(base, sourceAction.groups)
                            sourceFiles.deleteFlows(base, sourceAction.flowIds)
                        }
                        if (floatingFlowId in deletion.deleted) {
                            showFloatingFlow(deletion.snapshot, requireNotNull(deletion.snapshot.manifest.entryFlowId))
                        }
                        if (deletion.failures.isNotEmpty()) floatingSourceMessage = deletion.failures.joinToString("\n")
                        deletion.snapshot
                    }
                    is SourceManagerAction.CreateGroup -> {
                        withContext(Dispatchers.IO) { sourceFiles.createGroup(base, sourceAction.name) }
                        null
                    }
                    is SourceManagerAction.RenameGroup -> {
                        withContext(Dispatchers.IO) { sourceFiles.renameGroup(base, sourceAction.group, sourceAction.name) }
                        null
                    }
                    is SourceManagerAction.AddToGroup -> {
                        withContext(Dispatchers.IO) { sourceFiles.addToGroup(base, sourceAction.group, sourceAction.flowIds) }
                        null
                    }
                    is SourceManagerAction.RemoveFromGroup -> {
                        withContext(Dispatchers.IO) { sourceFiles.removeFromGroup(base, sourceAction.flowIds) }
                        null
                    }
                }
            }
            result.onFailure { failure -> floatingSourceMessage = failure.message ?: "源文件操作失败" }
            val current = result.getOrNull() ?: base
            floatingEditorSnapshot = current
            floatingTree = withContext(Dispatchers.IO) { runCatching { sourceFiles.load(current) }.getOrDefault(floatingTree) }
            projects = withContext(Dispatchers.IO) { store.listProjects() }
            floatingSourceBusy = false
        }
    }

    /** 旧版插件管理里需要宿主执行的动作：检错走 Rust 编译，未调用列出没被引用的源文件。 */
    fun handleFloatingPluginAction(pluginAction: PluginManagerAction) {
        val project = floatingEditorProject ?: return
        val snapshot = floatingEditorSnapshot ?: return
        when (pluginAction) {
            PluginManagerAction.CHECK, PluginManagerAction.CHECK_ALL -> {
                if (busyProjectId != null) return
                busyProjectId = project.projectId
                error = null
                scope.launch {
                    if (persistFloatingFlow() != null) {
                        when (val result = withContext(Dispatchers.IO) { runtimeClient.compileVisualProject(project.projectId) }) {
                            is VisualCompileResult.Success -> notice = "检错通过 · ${result.generationId.take(20)}"
                            is VisualCompileResult.Invalid -> {
                                val location = listOfNotNull(
                                    result.diagnostic.flowId?.let { "Flow $it" },
                                    result.diagnostic.nodeId?.let { "节点 $it" },
                                    result.diagnostic.line?.let { "第 $it 行" },
                                ).joinToString(" · ")
                                error = if (location.isEmpty()) result.diagnostic.message else "$location：${result.diagnostic.message}"
                            }
                            is VisualCompileResult.Unavailable -> error = result.message
                        }
                    }
                    busyProjectId = null
                }
            }
            PluginManagerAction.UNUSED -> {
                val unused = sourceFiles.unreferencedFlows(snapshot)
                notice = if (unused.isEmpty()) "所有源文件都被调用或是入口" else "未调用源文件：" + unused.joinToString("、") { it.displayName() }
            }
            PluginManagerAction.TEMPLATE -> notice = "存储为模版属于预留功能，尚未开放"
            PluginManagerAction.CREATE,
            PluginManagerAction.DELETE,
            PluginManagerAction.SAVE_AS,
            PluginManagerAction.GROUP,
            -> Unit // 面板已转到源文件管理
        }
    }

    /** “文件”弹窗只允许删除图片和字库资源，走 Store 的引用检查与事务删除。 */
    fun deleteFloatingProjectFiles(files: List<StudioProjectFile>) {
        val project = floatingEditorProject ?: return
        val targets = files.filter {
            it.kind == StudioProjectFileKind.IMAGE || it.kind == StudioProjectFileKind.GLYPH_DICTIONARY
        }
        if (targets.isEmpty()) {
            error = "这里只能删除图片和字库资源"
            return
        }
        if (busyProjectId != null) return
        busyProjectId = project.projectId
        scope.launch {
            var current = floatingEditorSnapshot
            val failures = mutableListOf<String>()
            targets.forEach { file ->
                val expected = current?.manifest?.resources?.map { it.get("path").asString }?.toSet().orEmpty()
                runCatching {
                    withContext(Dispatchers.IO) { store.deleteResource(project.projectId, file.path, expected) }
                }.onSuccess { current = it }
                    .onFailure { failures += "${file.path.substringAfterLast('/')}：${it.message ?: "删除失败"}" }
            }
            floatingEditorSnapshot = current
            error = failures.takeIf { it.isNotEmpty() }?.joinToString("\n")
            if (failures.isEmpty()) notice = "已删除 ${targets.size} 个资源"
            busyProjectId = null
        }
    }

    LaunchedEffect(store) {
        runCatching { withContext(Dispatchers.IO) { store.listProjects() } }
            .onSuccess {
                projects = it
                expandedProjectIds = it.firstOrNull()?.let { project -> setOf(project.projectId) }.orEmpty()
            }
            .onFailure { error = it.message ?: "读取项目失败" }
        loading = false
    }

    LaunchedEffect(floatingEditorProject?.projectId) {
        val project = floatingEditorProject
        floatingEditorSnapshot = null
        floatingVariablesVisible = false
        floatingVisualEditor = null
        floatingFlowId = null
        floatingTree = SourceFileTree()
        floatingSourceMessage = null
        floatingInsertSnippet = null
        floatingClipboard = null
        floatingPastePending = false
        floatingIndentSlots = null
        floatingEditingNodeId = null
        floatingRecognitionInsert = null
        recognitionCaptureActive = false
        recognitionCaptureSelection = null
        recognitionCaptureTemplatePath = null
        if (project == null) return@LaunchedEffect
        val snapshot = runCatching { withContext(Dispatchers.IO) { store.openProject(project.projectId) } }
            .getOrElse { failure ->
                error = failure.message ?: "读取程序树失败"
                return@LaunchedEffect
            }
        // Lua 项目也需要快照：“文件”弹窗要列出 main.lua 和资源。
        floatingEditorSnapshot = snapshot
        if (project.sourceMode != ProjectSourceMode.VISUAL) return@LaunchedEffect
        val entryFlow = snapshot.manifest.flows.singleOrNull { it.flowId == snapshot.manifest.entryFlowId }
        if (entryFlow == null) {
            error = "可视化项目缺少入口插件"
            return@LaunchedEffect
        }
        showFloatingFlow(snapshot, entryFlow.flowId)
        floatingTree = withContext(Dispatchers.IO) {
            runCatching { sourceFiles.load(snapshot) }.getOrDefault(SourceFileTree())
        }
    }

    when (workspacePanel) {
        WorkspacePanel.BACKUPS -> {
            LaunchedEffect(backupsRevision) {
                backups = withContext(Dispatchers.IO) {
                    runCatching { store.listBackedUpProjects() }.getOrDefault(emptyList())
                }
            }
            BackupManagementScreen(
                backups = backups,
                busy = busyProjectId != null || backupProgress != null,
                message = backupMessage ?: error ?: notice,
                onBack = {
                    workspacePanel = WorkspacePanel.PROJECTS
                    backupMessage = null
                },
                onImport = {
                    importLauncher.launch(
                        arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream"),
                    )
                },
                onOpen = { backup ->
                    backupMessage = null
                    backupSlotsTarget = BackupSlotsTarget(backup.projectId, backup.projectName)
                },
                onDelete = { backup ->
                    runBackupSlotAction("正在删除“${backup.projectName}”的备份…") {
                        store.deleteProjectBackups(backup.projectId)
                        "已删除“${backup.projectName}”的全部备份槽位"
                    }
                },
                modifier = modifier,
            )
            return
        }
        WorkspacePanel.LEARNING -> {
            LearningProjectsScreen(
                onBack = { workspacePanel = WorkspacePanel.PROJECTS },
                modifier = modifier,
            )
            return
        }
        WorkspacePanel.BACKEND -> {
            DeveloperBackendScreen(
                projects = projects,
                onBack = { workspacePanel = WorkspacePanel.PROJECTS },
                modifier = modifier,
            )
            return
        }
        WorkspacePanel.PROJECTS -> Unit
    }

    backupSlotsTarget?.let { target ->
        val localProject = projects.firstOrNull { it.projectId == target.projectId }
        LaunchedEffect(target.projectId, backupsRevision) {
            backupSlots = withContext(Dispatchers.IO) {
                runCatching { store.listBackupSlots(target.projectId) }.getOrDefault(emptyList())
            }
        }
        ProjectBackupSlotsScreen(
            projectName = localProject?.name ?: target.name,
            localProjectExists = localProject != null,
            slots = backupSlots,
            progressLabel = backupProgress,
            message = backupMessage,
            onBack = {
                backupSlotsTarget = null
                backupSlots = emptyList()
                backupMessage = null
            },
            onBackup = { slot, remark ->
                runBackupSlotAction("正在备份到槽位 $slot…") {
                    store.backupToSlot(target.projectId, slot, remark)
                    "已备份到槽位 $slot"
                }
            },
            onRestore = { slot ->
                runBackupSlotAction("正在从槽位 $slot 恢复…") {
                    val restored = store.restoreBackupSlot(target.projectId, slot)
                    "已恢复为新项目“${restored.manifest.name}”"
                }
            },
            onDelete = { slot ->
                runBackupSlotAction("正在清空槽位 $slot…") {
                    store.deleteBackupSlot(target.projectId, slot)
                    "已清空槽位 $slot"
                }
            },
            onExportFile = localProject?.let { project ->
                {
                    if (busyProjectId == null && backupProgress == null) {
                        exportTarget = project
                        exportLauncher.launch(backupFileName(project.name))
                    }
                }
            },
            modifier = modifier,
        )
        return
    }

    // 工具页和 Lua 源码页仍是独立页面；可视化编辑直接叠在项目列表上。
    fun leaveOpenedProject() {
        floatingRecognitionInsert = null
        recognitionCaptureActive = false
        recognitionCaptureSelection = null
        recognitionCaptureTemplatePath = null
        floatingImageBlockKind = null
        floatingImageBlockValues = null
        floatingImageRegion = null
        designingRunnerUi = false
        showingImageTools = false
        showingImageLibrary = false
        showingPackager = false
        showingRecorder = false
        editingOpenedProject = false
        requestedFlowId = null
        opened = null
        // 悬浮窗的截图是 Root 抓的真实屏幕，离开项目时必须回收，不能随项目切换泄漏。
        clearImageToolState()
    }

    val runnerUiProject = opened?.takeIf { designingRunnerUi }
    if (runnerUiProject != null) {
        RunnerUiDesignerScreen(
            snapshot = runnerUiProject,
            store = store,
            onSnapshotChanged = {
                opened = it
                if (settingsSnapshot?.manifest?.projectId == it.manifest.projectId) {
                    settingsSnapshot = it
                }
            },
            onBack = ::leaveOpenedProject,
            modifier = modifier,
        )
        return
    }

    // 取图/取色/坐标那套工具已改为悬浮窗（见下方 ImageToolWindow 挂载处）：
    // 参考产品那个工具本来就是浮在编辑器之上的，全屏会把被取图的界面挡掉。
    // 这里保留的是「打开标注库」——浏览与导入项目图片，职责不同，仍适合全屏列表。
    val imageLibraryProject = opened?.takeIf { showingImageLibrary }
    if (imageLibraryProject != null) {
        ImageToolsScreen(
            snapshot = imageLibraryProject,
            store = store,
            runtimeClient = runtimeClient,
            runtimeState = runtimeState,
            onSnapshotChanged = { opened = it },
            onBack = ::leaveOpenedProject,
            modifier = modifier,
        )
        return
    }

    val packageProject = opened?.takeIf { showingPackager }
    if (packageProject != null) {
        ProjectPackageScreen(
            snapshot = packageProject,
            onBack = ::leaveOpenedProject,
            modifier = modifier,
        )
        return
    }

    val recorderProject = opened?.takeIf { showingRecorder }
    if (recorderProject != null) {
        RecordingWorkspaceScreen(
            snapshot = recorderProject,
            onBack = ::leaveOpenedProject,
            modifier = modifier,
        )
        return
    }

    val luaProject = opened?.takeIf {
        editingOpenedProject && it.manifest.sourceMode == ProjectSourceMode.LUA
    }
    if (luaProject != null) {
        LuaEditorScreen(
            snapshot = luaProject,
            store = store,
            runtimeClient = runtimeClient,
            runtimeState = runtimeState,
            consoleLines = consoleLines,
            active = active,
            initialImageToolBitmap = handoffCaptureBitmap,
            initialImageToolToken = handoffCaptureToken,
            modifier = modifier,
            onSnapshotChanged = { opened = it },
            onInitialImageToolConsumed = {
                handoffCaptureBitmap = null
                handoffCaptureToken = null
            },
            onExit = ::leaveOpenedProject,
        )
        return
    }

    val visualProject = opened?.takeIf {
        editingOpenedProject && it.manifest.sourceMode == ProjectSourceMode.VISUAL
    }

    floatingInputInsertion?.let { draft ->
        floatingVisualEditor?.let { editor ->
            InputInsertPositionDialog(draft, editor,
                onDismiss = { floatingInputInsertion = null; floatingInputTarget = null },
                onConfirm = { position, slot -> confirmFloatingInput(draft, position, slot) })
        }
    }

    run {
        Box(modifier.fillMaxSize()) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 14.dp),
            ) {
            item {
                WorkspaceHeader(
                    projectCount = projects.size,
                    loading = loading,
                    onCreate = { showCreate = true },
                )
            }
            item {
                WorkspaceQuickActions { action ->
                    when (action) {
                        WorkspaceShortcut.BACKUP -> workspacePanel = WorkspacePanel.BACKUPS
                        WorkspaceShortcut.LEARNING -> workspacePanel = WorkspacePanel.LEARNING
                        WorkspaceShortcut.BACKEND -> workspacePanel = WorkspacePanel.BACKEND
                    }
                }
            }

            error?.let { message ->
                item {
                    Box(Modifier.padding(horizontal = 12.dp, vertical = 5.dp)) {
                        WorkspaceMessage(message, MaterialTheme.colorScheme.error)
                    }
                }
            }
            notice?.let { message ->
                item {
                    Box(Modifier.padding(horizontal = 12.dp, vertical = 5.dp)) {
                        WorkspaceMessage(message, MaterialTheme.colorScheme.primary)
                    }
                }
            }

            item {
                Text(
                    "项目列表",
                    color = WorkspaceTextPrimary,
                    fontSize = 16.sp,
                    lineHeight = 20.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .padding(start = 20.dp, end = 20.dp, top = 17.dp),
                )
            }

            if (!loading && projects.isEmpty()) {
                item {
                    Surface(
                        modifier = Modifier.padding(horizontal = 12.dp).fillMaxWidth(),
                        color = Color.White,
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        Column(
                            Modifier.padding(horizontal = 40.dp, vertical = 18.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Box(
                                Modifier.size(52.dp).background(Color(0xFFEAF0FF), RoundedCornerShape(26.dp)),
                                contentAlignment = Alignment.Center,
                            ) {
                                WorkspaceIcon(WorkspaceIconKind.FOLDER, Modifier.size(26.dp), WorkspaceAccent)
                            }
                            Text("还没有项目", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = WorkspaceTextPrimary, modifier = Modifier.padding(top = 12.dp))
                            Text("创建一个项目，开始编排自动化流程", fontSize = 11.sp, color = WorkspaceTextSecondary, modifier = Modifier.padding(top = 5.dp))
                            Surface(
                                modifier = Modifier.padding(top = 14.dp).size(width = 128.dp, height = 38.dp),
                                color = WorkspaceAccent,
                                contentColor = Color.White,
                                shape = RoundedCornerShape(11.dp),
                                onClick = { showCreate = true },
                                enabled = !loading,
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text("新建第一个项目", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }

            items(projects, key = ProjectSummary::projectId) { project ->
                val expanded = project.projectId in expandedProjectIds
                Box(Modifier.padding(horizontal = 12.dp)) {
                    ProjectRow(
                        project = project,
                        expanded = expanded,
                        enabled = !loading && busyProjectId == null && visualProject == null,
                        busy = busyProjectId == project.projectId,
                        onToggle = {
                            expandedProjectIds = if (expanded) {
                                expandedProjectIds - project.projectId
                            } else {
                                expandedProjectIds + project.projectId
                            }
                        },
                        onOpen = {
                            // 使用正式可视化编辑状态直接展开弹窗，不切换到旧全屏诊断界面。
                            editingOpenedProject = true
                            requestedFlowId = null
                            floatingEditorProject = null
                            runStoreAction(openResult = true) { store.openProject(project.projectId) }
                        },
                        onRun = { runProject(project, entry = StudioRunEntry.LAUNCH) },
                        onSettings = { openSettings(project) },
                        onUi = {
                            designingRunnerUi = true
                            runStoreAction(openResult = true) { store.openProject(project.projectId) }
                        },
                        onPackage = {
                            showingPackager = true
                            runStoreAction(openResult = true) { store.openProject(project.projectId) }
                        },
                        onExport = {
                            if (busyProjectId == null) {
                                backupMessage = null
                                backupSlotsTarget = BackupSlotsTarget(project.projectId, project.name)
                            }
                        },
                        onRename = { renameTarget = project },
                        onDelete = { deleteTarget = project },
                    )
                }
            }
            }
            visualProject?.let { visual ->
                key(visual.manifest.projectId) {
                    VisualProjectScreen(
                        snapshot = visual, store = store, runtimeClient = runtimeClient,
                        runtimeState = runtimeState, consoleLines = consoleLines, active = active,
                        initialFlowId = requestedFlowId, modifier = Modifier.fillMaxSize(),
                        onSnapshotChanged = { opened = it }, onExit = ::leaveOpenedProject,
                    )
                }
            }
            // EditorDock 自己的 BackHandler 只在面板展开（或菜单打开）时启用，
            // 收成小球后返回键会穿透到 Activity 直接退出 App。这里兜底：退出悬浮编辑，回项目列表。
            BackHandler(enabled = floatingEditorProject != null && !showingImageTools) {
                floatingEditorProject = null
            }
            // 图像工具的截图：参考产品是先把自己的悬浮窗 hideFloating() 藏起来、截好图存成
            // temporaryBMP.bmp，再打开工具页去加载它——工具页里根本没有"截图"按钮。
            // 默认不等待用户延时，仅给弹窗隐藏保留200ms；工具在截图期间不铺不透明背景。
            fun captureForImageTool(delayMillis: Long = 0L) {
                if (imageToolCapturing || imageToolCropping) return
                val requestId = imageToolRequestId + 1
                imageToolRequestId = requestId
                imageToolCapturing = true
                imageToolMessage = null
                notice = if (delayMillis > 0) "请在 ${delayMillis / 1_000} 秒内切换到需要取图的界面" else null
                imageToolCaptureJob = scope.launch {
                    var decoded: Bitmap? = null
                    try {
                        delay(delayMillis.coerceAtLeast(200L))
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
                        // Closing the tool or leaving the project deliberately cancels this request.
                    } catch (error: Exception) {
                        if (requestId == imageToolRequestId) {
                            imageToolMessage = error.message ?: "截图失败"
                        }
                    } finally {
                        decoded?.recycle()
                        if (requestId == imageToolRequestId) {
                            notice = null
                            imageToolCapturing = false
                            imageToolCaptureJob = null
                        }
                    }
                }
            }
            val imageToolSnapshot = opened?.takeIf { showingImageTools }
            if (imageToolSnapshot != null) {
                // ImageToolWindow 自带 BackHandler 关窗；这里不再叠一层，避免两个
                // BackHandler 争同一次返回键（EditorDock 那次踩过类似的坑）。
                ImageToolWindow(
                    pointerInputSupported = inputBackendFeatures and 2 != 0,
                    bitmap = imageToolBitmap,
                    // 基准分辨率在 manifest.design 里；ProjectSummary 上那对同名字段是列表用的投影。
                    designWidth = imageToolSnapshot.manifest.design.width,
                    designHeight = imageToolSnapshot.manifest.design.height,
                    scaleMode = imageToolSnapshot.manifest.design.scaleMode,
                    initialMode = imageToolInitialMode,
                    capturing = imageToolCapturing,
                    message = imageToolMessage,
                    onCapture = { captureForImageTool() },
                    onEmit = ::emitImageToolSnippet,
                    onCropToTemplate = { roi ->
                        if (imageToolBitmap == null) imageToolMessage = "请先截图"
                        else pendingImageTemplateRoi = roi
                    },
                    onClose = {
                        floatingCalculationInsert = null
                        showingImageTools = false
                        recognitionCaptureActive = false
                        clearImageToolState()
                    },
                    modifier = Modifier.zIndex(1f),
                )
                pendingImageTemplateRoi?.let { roi ->
                    ImageTemplateSaveDialog(
                        imagePaths = imageToolSnapshot.manifest.resources.mapNotNull { it.get("path")?.asString },
                        onDismiss = { pendingImageTemplateRoi = null },
                        onSave = { folder, name ->
                        pendingImageTemplateRoi = null
                        val source = imageToolBitmap
                        if (source == null) {
                            imageToolMessage = "请先截图"
                        } else {
                            val sourceCopy = runCatching {
                                source.copy(source.config ?: Bitmap.Config.ARGB_8888, false)
                            }.getOrElse {
                                imageToolMessage = it.message ?: "无法准备模板图片"
                                return@ImageTemplateSaveDialog
                            }
                            val requestId = imageToolRequestId + 1
                            imageToolRequestId = requestId
                            imageToolCropping = true
                            imageToolCropJob = scope.launch {
                                try {
                                    val result = withContext(Dispatchers.IO) {
                                        runCatching {
                                            cropAndImportTemplate(store, imageToolSnapshot, sourceCopy, roi, context, folder, name)
                                        }
                                    }
                                    if (requestId != imageToolRequestId) return@launch
                                    result.onSuccess { (snapshot, path) ->
                                    opened = snapshot
                                    if (floatingEditorSnapshot?.manifest?.projectId == snapshot.manifest.projectId) {
                                        floatingEditorSnapshot = snapshot
                                    }
                                    // 存完模板顺手给出能用的找图代码：路径这时才确定，
                                    // 提前猜路径会生成一段运行时必然失败的调用。
                                    val snippet = runCatching {
                                        ImageToolCodeGen.findImage(
                                            resourcePath = path,
                                            roi = roi,
                                            mapping = ImageToolCodeGen.DesignMapping(
                                                captureWidth = sourceCopy.width,
                                                captureHeight = sourceCopy.height,
                                                designWidth = imageToolSnapshot.manifest.design.width,
                                                designHeight = imageToolSnapshot.manifest.design.height,
                                                scaleMode = imageToolSnapshot.manifest.design.scaleMode,
                                            ),
                                        )
                                    }.getOrNull()
                                    val name = path.substringAfterLast('/')
                                    imageToolMessage = if (floatingEditorProject?.sourceMode == ProjectSourceMode.VISUAL) {
                                        "已存为模板：$name；返回图像面板可直接选择"
                                    } else if (snippet?.rejection == null && snippet != null) {
                                        emitImageToolSnippet(snippet)
                                        imageToolMessage ?: "已存为模板：$name"
                                    } else {
                                        "已存为模板：$name"
                                    }
                                    if (recognitionCaptureActive) {
                                        recognitionCaptureTemplatePath = path
                                        recognitionCaptureActive = false
                                        showingImageTools = false
                                        clearImageToolState()
                                    }
                                    }.onFailure {
                                        imageToolMessage = it.message ?: "模板保存失败"
                                    }
                                } finally {
                                    sourceCopy.recycle()
                                    if (requestId == imageToolRequestId) {
                                        imageToolCropping = false
                                        imageToolCropJob = null
                                    }
                                }
                            }
                        }
                        },
                    )
                }
            }
            floatingEditorProject?.let { project ->
                val visualSnapshot = floatingEditorSnapshot
                val visualEditor = floatingVisualEditor
                @Suppress("UNUSED_VARIABLE")
                val revision = floatingEditorRevision
                if (showInterfacePreview) ProjectInterfacePreview(visualSnapshot?.manifest?.runnerUi, visualSnapshot?.directory) { showInterfacePreview = false }
                EditorDock(
                    projectName = project.name,
                    onOpenSettings = {
                        when {
                            busyProjectId != null || floatingSourceBusy -> notice = "正在处理项目，请稍后打开设置"
                            runtimeRunning || runtimeState.engineState == RuntimeEngineState.STOPPING -> notice = "请先停止运行，再打开项目设置"
                            visualEditor?.isDirty == true -> notice = "请先保存当前插件，再打开项目设置"
                            else -> openSettings(project)
                        }
                    },
                    onRun = { runProject(project, saveCurrentFlowBeforeRun = true) },
                    running = runtimeRunning,
                    runEnabled = busyProjectId == null &&
                        runtimeState.engineState != RuntimeEngineState.STOPPING &&
                        (project.sourceMode != ProjectSourceMode.VISUAL || visualEditor != null),
                    onStop = ::stopRunningProject,
                    paused = runtimeState.engineState == RuntimeEngineState.PAUSED,
                    resumeEnabled = busyProjectId == null && !floatingStepState.pending && !floatingStepState.loading,
                    onPause = {
                        if (busyProjectId == null) {
                            busyProjectId = project.projectId
                            scope.launch {
                                try {
                                    if (!withContext(Dispatchers.IO) { runtimeClient.requestPause() }) error = "暂停请求被拒绝"
                                } finally { busyProjectId = null }
                            }
                        }
                    },
                    onResume = {
                        if (floatingStepState.position == null && busyProjectId == null) {
                            busyProjectId = project.projectId
                            scope.launch {
                                try {
                                    if (!withContext(Dispatchers.IO) { runtimeClient.requestResume() }) error = "继续请求被拒绝"
                                } finally { busyProjectId = null }
                            }
                        } else if (floatingStepState.begin(continueRun = true)) scope.launch {
                            if (!withContext(Dispatchers.IO) { runtimeClient.resumeDebugProject(project.projectId, runtimeState.sessionGeneration) }) {
                                floatingStepState.rejected(); error = "继续请求被拒绝，会话可能已变化"
                            }
                        }
                    },
                    consoleLines = consoleLines,
                    sourceName = if (project.sourceMode == ProjectSourceMode.VISUAL) {
                        floatingFlowId?.let { flowId ->
                            visualSnapshot?.manifest?.flows?.firstOrNull { it.flowId == flowId }?.displayName()
                        } ?: "加载中…"
                    } else "main.lua",
                    sourceTree = if (project.sourceMode == ProjectSourceMode.VISUAL && visualSnapshot != null) floatingTree else null,
                    currentFlowId = floatingFlowId,
                    sourceBusy = floatingSourceBusy,
                    sourceMessage = floatingSourceMessage,
                    onSourceAction = ::handleFloatingSourceAction,
                    onManageVariables = { floatingVariablesVisible = true },
                    availableVariables = if (visualEditor != null && visualSnapshot != null) visualKnownVariables(visualEditor, visualSnapshot.manifest.variables, floatingFlowId.orEmpty()) else emptyList(),
                    availableLabels = visualEditor?.rows?.filter { it.kind == "control.label" && it.depth == 0 }
                        ?.mapNotNull { row -> visualEditor.nodeArguments(row.nodeId)?.get("name")?.asString }?.distinct().orEmpty(),
                    projectVariables = visualSnapshot?.manifest?.variables.orEmpty(),
                    projectFlows = visualSnapshot?.manifest?.flows.orEmpty(),
                    debugSettings = visualSnapshot?.manifest?.debugSettings
                        ?: com.autoscript.project.store.ProjectDebugSettings(),
                    onSaveDebugSettings = { settings ->
                        val current = visualSnapshot ?: return@EditorDock
                        scope.launch {
                            runCatching {
                                withContext(Dispatchers.IO) {
                                    store.updateDebugSettings(
                                        projectId = current.manifest.projectId,
                                        debugSettings = settings,
                                        expected = current.manifest.debugSettings,
                                    )
                                }
                            }.onSuccess { updated ->
                                floatingEditorSnapshot = updated
                                notice = "调试设置已保存"
                            }.onFailure { failure ->
                                error = failure.message ?: "保存调试设置失败"
                            }
                        }
                    },
                    projectFiles = visualSnapshot?.let { current -> remember(current) { projectFileCatalog(current) } }.orEmpty(),
                    onOpenProjectFile = { file ->
                        val current = visualSnapshot ?: return@EditorDock
                        when (file.kind) {
                            StudioProjectFileKind.LUA -> {
                                opened = current
                                editingOpenedProject = true
                                floatingEditorProject = null
                            }
                            StudioProjectFileKind.FLOW -> {
                                requestedFlowId = current.manifest.flows.singleOrNull { it.path == file.path }?.flowId
                                opened = current
                                editingOpenedProject = true
                                floatingEditorProject = null
                            }
                            StudioProjectFileKind.MANIFEST,
                            StudioProjectFileKind.IMAGE,
                            StudioProjectFileKind.GLYPH_DICTIONARY,
                            -> settingsSnapshot = current
                        }
                    },
                    onDeleteProjectFiles = ::deleteFloatingProjectFiles,
                    onPluginAction = ::handleFloatingPluginAction,
                    capabilities = visualSnapshot?.manifest?.capabilities?.toSet().orEmpty(),
                    inputBackendFeatures = inputBackendFeatures,
                    onOpenRecorder = visualSnapshot?.let { current ->
                        {
                            opened = current
                            showingRecorder = true
                            floatingEditorProject = null
                        }
                    },
                    onOpenImageTools = visualSnapshot?.let { current ->
                        { delayMillis ->
                            opened = current
                            showingImageTools = true
                            imageToolInitialMode = ImageToolMode.CROP
                            // 和参考一样：进入工具就先截图，工具打开时图已经在了。
                            captureForImageTool(delayMillis)
                        }
                    },
                    onOpenImageToolMode = visualSnapshot?.let { current ->
                        { mode, delayMillis ->
                            opened = current
                            imageToolInitialMode = mode
                            showingImageTools = true
                            captureForImageTool(delayMillis)
                        }
                    },
                    onCreateImageBlock = if (visualSnapshot != null && visualEditor != null) {
                        { kind ->
                            floatingImageBlockValues = null
                            floatingImageBlockKind = kind
                            recognitionCaptureSelection = null
                            recognitionCaptureTemplatePath = null
                        }
                    } else null,
                    onTestRecognition = if (visualSnapshot != null && visualEditor != null) {
                        { floatingRecognitionTest = true }
                    } else null,
                    onOpenDebugTool = if (visualSnapshot != null && visualEditor != null) { { floatingDebugInspector = it } } else null,
                    onOpenInputPointPicker = visualSnapshot?.let { current ->
                        { mode ->
                            floatingVisualEditor?.let { editor -> floatingFlowId?.let { flowId ->
                                floatingInputTarget = InputPickTarget(current.manifest.projectId, flowId, editor.currentSource, editor.selectedNodeId)
                            } }
                            opened = current
                            if (mode == ImageToolMode.POINTER_UP) queueFloatingInput(ImageToolCodeGen.pointerUp())
                            else {
                                imageToolInitialMode = mode
                                showingImageTools = true
                                captureForImageTool(0L)
                            }
                        }
                    },
                    onOpenInputFloatingPicker = if (visualSnapshot != null) ::openFloatingInputPicker else null,
                    inputPickOpenRequest = floatingInputOpenRequest,
                    onOpenImageLibrary = visualSnapshot?.let { current ->
                        {
                            opened = current
                            showingImageLibrary = true
                            floatingEditorProject = null
                        }
                    },
                    programNodes = if (project.sourceMode == ProjectSourceMode.VISUAL) {
                        visualEditor?.rows?.map { node ->
                            val contract = BlockCatalog.find(node.kind)
                            EditorProgramNode(
                                nodeId = node.nodeId,
                                label = buildString {
                                    node.childSlot?.let { append('[').append(childBlockLabel(it)).append("] ") }
                                    append(legacyDockProgramLabel(contract, visualEditor.nodeArguments(node.nodeId), node.kind))
                                },
                                kind = node.kind,
                                depth = node.depth,
                                childSlots = contract?.childBlocks.orEmpty(),
                                disabled = visualEditor.isNodeDisabled(node.nodeId, inherited = false),
                            )
                        }.orEmpty()
                    } else null,
                    programSelectedNodeId = visualEditor?.selectedNodeId,
                    programCurrentNodeId = floatingStepState.position?.takeIf { it.flowId == floatingFlowId }?.nodeId,
                    executionStatus = when {
                        floatingStepState.pending -> floatingStepState.pendingMessage
                        runtimeState.engineState == RuntimeEngineState.PAUSED && floatingStepState.loading -> "已暂停 · 正在读取执行位置…"
                        runtimeState.engineState == RuntimeEngineState.PAUSED && floatingStepState.position != null -> "已暂停 · 黄色 ▶ 为下一步，点击单步执行；点运行继续"
                        else -> null
                    },
                    programSelectedNodeIds = visualEditor?.selectedNodeIds.orEmpty(),
                    onProgramNodeSelectionToggled = { nodeId -> visualEditor?.toggleSelection(nodeId); floatingEditorRevision++ },
                    editingEnabled = editorCanMutate(busyProjectId != null, false, runtimeState.engineState) && (
                        project.sourceMode == ProjectSourceMode.LUA ||
                            visualEditor?.isReadOnly == false
                        ),
                    programSource = visualEditor?.currentSource,
                    onReplaceProgramText = if (visualEditor == null) null else { query, replacement, selectedOnly ->
                        if (!editorCanMutate(busyProjectId != null, visualEditor.isReadOnly, runtimeState.engineState)) "请停止运行后再替换"
                        else {
                            val count = visualEditor.replaceDisplayText(query, replacement, selectedOnly)
                            if (count == 0) "没有可替换的文字，或替换结果超过长度限制"
                            else { finishFloatingMutation(true, "已替换 $count 个积木的文字"); null }
                        }
                    },
                    stepEnabled = !floatingStepState.pending && !floatingStepState.loading &&
                        (runtimeState.engineState != RuntimeEngineState.PAUSED || floatingStepState.position != null) &&
                        project.sourceMode == ProjectSourceMode.VISUAL && editorCanStep(busyProjectId != null,
                        runtimeState.phase == RuntimeConnectionPhase.CONNECTED, runtimeState.engineState,
                        visualEditor?.selectedNodeId?.let { !visualEditor.isNodeDisabled(it) } == true),
                    onProgramNodeSelected = { nodeId ->
                        visualEditor?.selectedNodeId = nodeId
                        floatingEditorRevision++
                    },
                    onInsertPositioned = { snippet, position ->
                        val selected = visualEditor?.rows?.firstOrNull { it.nodeId == visualEditor.selectedNodeId }
                        val slots = selected?.kind?.let(BlockCatalog::find)?.childBlocks.orEmpty()
                        when {
                            position == EditorInsertPosition.INSIDE && slots.isEmpty() -> error = "当前选择行不能加入内部"
                            position == EditorInsertPosition.INSIDE -> insertFloatingBlock(snippet, slots.first(), position)
                            else -> insertFloatingBlock(snippet, null, position)
                        }
                    },
                    onProgramNodeDeleted = {
                        if (editorCanMutate(busyProjectId != null, visualEditor?.isReadOnly != false, runtimeState.engineState) && visualEditor?.deleteSelection() == true) {
                            floatingEditorRevision++
                            saveFloatingFlow()
                        }
                    },
                    onProgramNodeEdited = {
                        floatingReflectionPosition = null
                        floatingReflectionChildSlot = null
                        if (visualSnapshot != null && editorCanMutate(busyProjectId != null, visualEditor?.isReadOnly != false, runtimeState.engineState)) {
                            val selectedId = visualEditor?.selectedNodeId
                            val selected = visualEditor?.rows?.firstOrNull { it.nodeId == selectedId }
                            val contract = selected?.kind?.let(BlockCatalog::find)
                            if (selectedId != null && contract?.properties?.isNotEmpty() == true) {
                                floatingEditingNodeId = selectedId
                            } else {
                                error = "当前节点没有可编辑参数"
                            }
                        }
                    },
                    onProgramNodeReflected = { nodeId, position, slot ->
                        if (editorCanMutate(busyProjectId != null, visualEditor?.isReadOnly != false, runtimeState.engineState)) {
                            floatingReflectionPosition = position.takeUnless { it == EditorInsertPosition.REPLACE }
                            floatingReflectionChildSlot = slot
                            floatingEditingNodeId = nodeId
                        }
                    },
                    onShowInterface = { showInterfacePreview = true },
                    onProgramCommand = { command ->
                        if (project.sourceMode == ProjectSourceMode.VISUAL) runFloatingCommand(command)
                        else error = "结构化节点操作仅适用于可视化项目，Lua 请进入源码编辑器"
                    },
                    onStep = {
                        if (project.sourceMode != ProjectSourceMode.VISUAL) error = "单步仅支持可视化插件"
                        else if (runtimeState.engineState == RuntimeEngineState.PAUSED) {
                            if (floatingStepState.begin()) scope.launch {
                                if (!withContext(Dispatchers.IO) { runtimeClient.requestStep(project.projectId, runtimeState.sessionGeneration) }) {
                                    floatingStepState.rejected(); error = "无法单步：当前项目的会话或暂停状态已变化"
                                }
                            }
                        } else if (editorCanStep(busyProjectId != null, runtimeState.phase == RuntimeConnectionPhase.CONNECTED,
                                runtimeState.engineState, visualEditor?.selectedNodeId?.let { !visualEditor.isNodeDisabled(it) } == true)) {
                            runProject(project, saveCurrentFlowBeforeRun = true, entry = StudioRunEntry.SINGLE_STEP)
                        } else error = "请先选择可执行积木，并暂停或停止当前任务"
                    },
                    onClose = {
                        floatingImageBlockKind = null
                        floatingImageBlockValues = null
                        floatingRecognitionInsert = null
                        recognitionCaptureActive = false
                        recognitionCaptureSelection = null
                        recognitionCaptureTemplatePath = null
                        floatingImageRegion = null
                        floatingEditorProject = null
                    },
                    onInsert = insert@{ snippet ->
                        if (project.sourceMode == ProjectSourceMode.VISUAL) {
                            val selected = visualEditor?.rows?.firstOrNull { it.nodeId == visualEditor.selectedNodeId }
                            val childSlots = selected?.kind?.let(BlockCatalog::find)?.childBlocks.orEmpty()
                            if (selected != null && childSlots.isNotEmpty()) {
                                floatingInsertSnippet = snippet
                            } else {
                                insertFloatingBlock(snippet, null)
                            }
                            return@insert
                        }
                        // Lua 项目直接写源码，先过契约守门，不把不存在的 API 写进 main.lua。
                        LuaSnippetGate.reject(snippet)?.let { reason ->
                            error = reason
                            return@insert
                        }
                        scope.launch {
                            runCatching {
                                withContext(Dispatchers.IO) {
                                    val snapshot = store.openProject(project.projectId)
                                    require(snapshot.manifest.sourceMode == ProjectSourceMode.LUA) {
                                        "可视化项目需要转换为节点后加入，不能直接写入 Lua"
                                    }
                                    val current = snapshot.luaSource.orEmpty()
                                    val separator = if (current.isEmpty() || current.endsWith('\n')) "" else "\n"
                                    store.saveLua(
                                        projectId = project.projectId,
                                        source = current + separator + snippet,
                                        expectedSource = current,
                                    )
                                }
                            }.onSuccess {
                                notice = "已加入：${snippet.lineSequence().firstOrNull().orEmpty()}"
                                error = null
                            }.onFailure { failure ->
                                error = failure.message ?: "加入编辑命令失败"
                            }
                        }
                    },
                )
                if (floatingVariablesVisible && visualSnapshot != null) {
                    VisualVariableManagerDialog(
                        variables = visualSnapshot.manifest.variables,
                        currentFlowId = floatingFlowId.orEmpty(),
                        flows = visualSnapshot.manifest.flows,
                        allowFlowScope = project.sourceMode == ProjectSourceMode.VISUAL,
                        onDismiss = { floatingVariablesVisible = false },
                        onSave = { variables ->
                            scope.launch {
                                if (!editorCanMutate(busyProjectId != null, visualEditor?.isReadOnly != false, runtimeState.engineState)) {
                                    error = "请停止运行后再修改变量"; return@launch
                                }
                                runCatching {
                                    withContext(Dispatchers.IO) {
                                        store.updateVariables(
                                            visualSnapshot.manifest.projectId,
                                            variables,
                                            visualSnapshot.manifest.variables,
                                        )
                                    }
                                }.onSuccess { updated ->
                                    floatingEditorSnapshot = updated
                                    floatingVariablesVisible = false
                                    notice = "变量已保存"
                                }.onFailure { failure ->
                                    error = failure.message ?: "变量保存失败"
                                }
                            }
                        },
                        onSaveAndInsert = if (project.sourceMode == ProjectSourceMode.VISUAL) { variables, arguments ->
                            scope.launch {
                                if (!editorCanMutate(busyProjectId != null, visualEditor?.isReadOnly != false, runtimeState.engineState)) {
                                    error = "请停止运行后再修改变量"; return@launch
                                }
                                runCatching {
                                    withContext(Dispatchers.IO) { store.updateVariables(visualSnapshot.manifest.projectId, variables, visualSnapshot.manifest.variables) }
                                }.onSuccess { updated ->
                                    floatingEditorSnapshot = updated
                                    floatingVariablesVisible = false
                                    floatingCalculationInsert = null
                                    insertFloatingBlock(variableCalculationHint(arguments), null)
                                }.onFailure { error = it.message ?: "变量表保存失败" }
                            }
                        } else null,
                    )
                }
                floatingCalculationInsert?.let { pending ->
                    if (visualSnapshot != null) VisualVariableCalculationDialog(
                        pending.arguments, visualSnapshot.manifest.variables, floatingFlowId.orEmpty(), visualSnapshot.manifest.flows,
                        onDismiss = { floatingCalculationInsert = null },
                        onManageVariables = { floatingVariablesVisible = true },
                        onConfirm = { configured ->
                            floatingCalculationInsert = null
                            insertFloatingBlock(variableCalculationHint(configured), pending.childSlot, pending.position)
                            null
                        },
                    )
                }
                val pendingSnippet = floatingInsertSnippet
                if (pendingSnippet != null && visualEditor != null) {
                    val selected = visualEditor.rows.firstOrNull { it.nodeId == visualEditor.selectedNodeId }
                    val childSlots = selected?.kind?.let(BlockCatalog::find)?.childBlocks.orEmpty()
                    EditorOptionDialog(
                        title = "选择插入位置",
                        options = listOf<Pair<String?, String>>(null to "当前节点之后") +
                            childSlots.map { slot -> slot to "${childBlockLabel(slot)}内新增" },
                        onDismiss = { floatingInsertSnippet = null },
                        onConfirm = { slot ->
                            floatingInsertSnippet = null
                            insertFloatingBlock(pendingSnippet, slot)
                        },
                    )
                }
                floatingIndentSlots?.let { slots ->
                    EditorOptionDialog(
                        title = "选择缩进分支",
                        options = slots.map { slot -> slot to childBlockLabel(slot) },
                        onDismiss = { floatingIndentSlots = null },
                        onConfirm = { slot ->
                            floatingIndentSlots = null
                            finishFloatingMutation(
                                visualEditor?.indentSelection(slot) == true,
                                "节点已缩进到${childBlockLabel(slot)}",
                            )
                        },
                    )
                }
                if (floatingPastePending && visualEditor != null) {
                    val selected = visualEditor.rows.firstOrNull { it.nodeId == visualEditor.selectedNodeId }
                    val slots = selected?.kind?.let(BlockCatalog::find)?.childBlocks.orEmpty()
                    EditorOptionDialog(
                        title = "选择粘贴位置",
                        options = listOf<Pair<String?, String>>(null to "当前节点之后") +
                            slots.map { slot -> slot to "${childBlockLabel(slot)}内" },
                        onDismiss = { floatingPastePending = false },
                        onConfirm = { slot ->
                            floatingPastePending = false
                            pasteFloatingClipboard(slot)
                        },
                    )
                }
                val editingNodeId = floatingEditingNodeId
                val imageKind = floatingImageBlockKind
                floatingRecognitionInsert?.takeIf { visualEditor != null && visualSnapshot != null }?.let { request ->
                    val current = requireNotNull(visualSnapshot)
                    val editor = requireNotNull(visualEditor)
                    VisualImageRecognitionDialog(
                        request.contract, request.arguments, current.manifest.resources,
                        visualKnownVariables(editor, current.manifest.variables, floatingFlowId.orEmpty()), current,
                        confirmLabel = if (request.position == EditorInsertPosition.REPLACE) "确定" else "加入",
                        visible = !showingImageTools, captureSelection = recognitionCaptureSelection,
                        captureTemplatePath = recognitionCaptureTemplatePath,
                        onPickFromScreen = { mode ->
                            recognitionCaptureSelection = null; recognitionCaptureTemplatePath = null
                            recognitionCaptureActive = true; opened = current
                            imageToolInitialMode = mode; showingImageTools = true; captureForImageTool(0L)
                        },
                        onDismiss = { floatingRecognitionInsert = null; recognitionCaptureSelection = null; recognitionCaptureTemplatePath = null },
                        onConfirm = { _, configured ->
                            val changed = insertConfiguredRecognition(editor, request, configured)
                            if (changed) {
                                floatingRecognitionInsert = null
                                recognitionCaptureSelection = null; recognitionCaptureTemplatePath = null
                            }
                            finishFloatingMutation(changed, "已加入：${request.contract.title}")
                        },
                    )
                }
                floatingDebugInspector?.takeIf { visualEditor != null && visualSnapshot != null }?.let { mode ->
                    val current = requireNotNull(visualSnapshot)
                    val editor = requireNotNull(visualEditor)
                    val flowId = floatingFlowId.orEmpty()
                    VisualDebugInspector(mode, current, flowId, editor, runtimeClient,
                        onDismiss = { floatingDebugInspector = null },
                        onBackfill = { nodeId, args ->
                            if (busyProjectId != null || editor.isReadOnly || runtimeState.engineState in setOf(RuntimeEngineState.RUNNING, RuntimeEngineState.PAUSED, RuntimeEngineState.STOPPING)) "请停止运行后再回填" else {
                                val original = editor.currentSource
                                val root = current.manifest.flows.first { it.flowId == flowId }.rootBlockId
                                val candidate = VisualEditorState.create(original, root)
                                if (!candidate.updateArguments(nodeId, args)) "节点已变化" else {
                                    when (val validation = withContext(Dispatchers.IO) {
                                        runtimeClient.validateVisualDraft(current.manifest.projectId, flowId, candidate.currentSource.toByteArray(Charsets.UTF_8))
                                    }) {
                                        is VisualCompileResult.Success -> {
                                            if (editor.currentSource != original) "编辑内容已变化，请重新选择" else {
                                                editor.updateArguments(nodeId, args)
                                                floatingEditorRevision++
                                                saveFloatingFlow()
                                                null
                                            }
                                        }
                                        is VisualCompileResult.Invalid -> validation.diagnostic.message
                                        is VisualCompileResult.Unavailable -> validation.message
                                    }
                                }
                            }
                        },
                    )
                }
                if (floatingRecognitionTest && visualEditor != null && visualSnapshot != null) {
                    ImageRecognitionTestDialog(visualSnapshot, runtimeClient,
                        onDismiss = { floatingRecognitionTest = false },
                        onCapture = {
                            floatingRecognitionTest = false
                            opened = visualSnapshot
                            imageToolInitialMode = ImageToolMode.CROP
                            showingImageTools = true
                            captureForImageTool(0L)
                        },
                        onInsert = { arguments ->
                            val blocks = recognitionBlockSequence(arguments)
                            val missing = blocks.flatMap { it.first.requiredCapabilities }.toSet() - visualSnapshot.manifest.capabilities.toSet()
                            if (missing.isNotEmpty()) error = "找图需要能力：${missing.joinToString()}"
                            else if (busyProjectId == null && visualEditor.insertBlocks(blocks)) {
                                floatingRecognitionTest = false
                                floatingEditorRevision++
                                saveFloatingFlow()
                            }
                        },
                    )
                }
                if (imageKind != null && visualEditor != null && visualSnapshot != null) {
                    val contract = BlockCatalog.find(imageKind)
                    val capture = BlockCatalog.find("screen.capture")
                    val release = BlockCatalog.find("screen.release")
                    val missing = listOfNotNull(contract, capture, release).flatMap { it.requiredCapabilities }
                        .toSet() - visualSnapshot.manifest.capabilities.toSet()
                    val arguments = remember(imageKind, visualSnapshot.manifest.projectId, floatingFlowId, floatingImageBlockValues) { contract?.let {
                        initialRecognitionArguments(it, visualSnapshot)
                    }?.let { base ->
                        val picked = floatingImageBlockValues
                        if (picked != null) base.withImageToolValues(picked) else base.apply {
                            floatingImageRegion?.takeIf { has("region") }?.let { roi ->
                                add("region", JsonObject().apply {
                                    addProperty("left", roi.left); addProperty("top", roi.top)
                                    addProperty("right", roi.right); addProperty("bottom", roi.bottom)
                                })
                            }
                        }
                    } }
                    when {
                        contract == null || capture == null || release == null -> {
                            error = "缺少图像积木契约：$imageKind"
                            floatingImageBlockKind = null
                        }
                        missing.isNotEmpty() -> {
                            error = "${contract.title}需要能力：${missing.joinToString()}"
                            floatingImageBlockKind = null
                        }
                        arguments == null -> {
                            error = "请先导入${if (imageKind == "ocr.glyph") "字库" else "截图模板"}资源"
                            floatingImageBlockKind = null
                        }
                        else -> VisualImageRecognitionDialog(
                            contract = contract,
                            arguments = arguments,
                            resources = visualSnapshot.manifest.resources,
                            knownVariables = visualKnownVariables(visualEditor, visualSnapshot.manifest.variables, floatingFlowId.orEmpty()),
                            snapshot = visualSnapshot,
                            confirmLabel = "加入",
                            automaticCapture = true,
                            allowKindChange = true,
                            visible = !showingImageTools,
                            captureSelection = recognitionCaptureSelection,
                            captureTemplatePath = recognitionCaptureTemplatePath,
                            onPickFromScreen = { mode ->
                                recognitionCaptureSelection = null
                                recognitionCaptureTemplatePath = null
                                recognitionCaptureActive = true
                                opened = visualSnapshot
                                imageToolInitialMode = mode
                                showingImageTools = true
                                captureForImageTool(0L)
                            },
                            onDismiss = { floatingImageBlockKind = null; floatingImageBlockValues = null; recognitionCaptureSelection = null; recognitionCaptureTemplatePath = null },
                            onConfirm = { selectedContract, configured ->
                                val frame = configured.get("frameVariable")?.asString ?: "frame"
                                val captureArgs = JsonObject().apply { addProperty("resultVariable", frame) }
                                val releaseArgs = JsonObject().apply { addProperty("frameVariable", frame) }
                                val blocks = if (selectedContract.kind in setOf("vision.findimage", "vision.findgray", "ocr.alphanumeric")) {
                                    configured.addProperty("autoCapture", true)
                                    listOf(selectedContract to configured)
                                } else listOf(capture to captureArgs, selectedContract to configured, release to releaseArgs)
                                val required = blocks.flatMap { it.first.requiredCapabilities }.toSet() - visualSnapshot.manifest.capabilities.toSet()
                                if (required.isNotEmpty()) error = "识别需要能力：${required.joinToString()}"
                                else {
                                    floatingImageBlockKind = null
                                    floatingImageBlockValues = null
                                    recognitionCaptureSelection = null
                                    recognitionCaptureTemplatePath = null
                                    finishFloatingMutation(visualEditor.insertBlocks(blocks), "已加入：截图 → ${selectedContract.title} → 释放截图")
                                }
                            },
                        )
                    }
                }
                if (editingNodeId != null && visualEditor != null && visualSnapshot != null) {
                    val row = visualEditor.rows.firstOrNull { it.nodeId == editingNodeId }
                    val contract = row?.kind?.let(BlockCatalog::find)
                    val arguments = visualEditor.nodeArguments(editingNodeId)
                    val originalArguments = remember(visualEditor, editingNodeId) { arguments?.deepCopy() }
                    if (contract == null || arguments == null) {
                        floatingEditingNodeId = null
                    } else {
                        VisualNodeArgumentsDialog(
                            contract = contract,
                            arguments = arguments,
                            flows = visualSnapshot.manifest.flows,
                            currentFlowId = floatingFlowId.orEmpty(),
                            knownLabels = visualEditor.rows.filter { it.kind == "control.label" && !visualEditor.isNodeDisabled(it.nodeId) }
                                .mapNotNull { visualEditor.nodeArguments(it.nodeId)?.get("name")?.asString },
                            confirmLabel = if (floatingReflectionPosition == null) "保存" else "加入",
                            onManageVariables = { floatingVariablesVisible = true },
                            resources = visualSnapshot.manifest.resources,
                            knownVariables = visualKnownVariables(visualEditor, visualSnapshot.manifest.variables, floatingFlowId.orEmpty()),
                            imageProject = visualSnapshot,
                            imageDialogVisible = !showingImageTools,
                            imageCaptureSelection = recognitionCaptureSelection,
                            imageCaptureTemplatePath = recognitionCaptureTemplatePath,
                            onPickImageFromScreen = { mode ->
                                recognitionCaptureSelection = null
                                recognitionCaptureTemplatePath = null
                                recognitionCaptureActive = true
                                opened = visualSnapshot
                                imageToolInitialMode = mode
                                showingImageTools = true
                                captureForImageTool(0L)
                            },
                            onDismiss = { floatingEditingNodeId = null; recognitionCaptureSelection = null; recognitionCaptureTemplatePath = null },
                            onConfirm = { updated ->
                                recognitionCaptureSelection = null
                                recognitionCaptureTemplatePath = null
                                floatingEditingNodeId = null
                                if (!editorCanMutate(busyProjectId != null, visualEditor.isReadOnly, runtimeState.engineState)) {
                                    error = "请停止运行后再修改插件"
                                } else if (floatingReflectionPosition == null && visualEditor.nodeArguments(editingNodeId) == updated) {
                                    error = null
                                } else {
                                    finishFloatingMutation(
                                        applyReflectedArguments(visualEditor, editingNodeId, contract, updated, floatingReflectionPosition,
                                            floatingReflectionChildSlot, originalArguments),
                                        "节点参数已更新",
                                    )
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    if (showCreate) {
        ProjectEditorDialog(
            title = "创建项目",
            initialName = "",
            allowModeSelection = true,
            onDismiss = { showCreate = false },
            onConfirm = { name, mode, width, height ->
                showCreate = false
                runStoreAction { store.createProject(name, mode, width, height) }
            },
        )
    }
    renameTarget?.let { project ->
        ProjectEditorDialog(
            title = "重命名项目",
            initialName = project.name,
            allowModeSelection = false,
            onDismiss = { renameTarget = null },
            onConfirm = { name, _, _, _ ->
                renameTarget = null
                runStoreAction { store.renameProject(project.projectId, name) }
            },
        )
    }
    deleteTarget?.let { project ->
        ProjectDeleteDialog(
            projectName = project.name,
            onDismiss = { deleteTarget = null },
            onConfirm = {
                deleteTarget = null
                if (opened?.manifest?.projectId == project.projectId) opened = null
                // 槽位备份刻意保留：备份管理页用 localProjectExists 区分“项目已删、备份还在”。
                runStoreAction {
                    store.deleteProject(project.projectId)
                    null
                }
            },
        )
    }
    settingsSnapshot?.let { snapshot ->
        ProjectSettingsDialog(
            snapshot = snapshot,
            store = store,
            onSnapshotChanged = { updated ->
                settingsSnapshot = updated
                if (opened?.manifest?.projectId == updated.manifest.projectId) opened = updated
                if (floatingEditorSnapshot?.manifest?.projectId == updated.manifest.projectId) {
                    floatingEditorSnapshot = updated
                }
                scope.launch {
                    projects = withContext(Dispatchers.IO) { store.listProjects() }
                }
            },
            onDismiss = { settingsSnapshot = null },
        )
    }
}

private enum class WorkspaceShortcut {
    BACKUP,
    LEARNING,
    BACKEND,
}

private enum class WorkspacePanel {
    PROJECTS,
    BACKUPS,
    LEARNING,
    BACKEND,
}

@Composable
private fun WorkspaceHeader(
    projectCount: Int,
    loading: Boolean,
    onCreate: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                "工作台",
                color = WorkspaceTextPrimary,
                fontSize = 19.sp,
                lineHeight = 23.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                if (loading) "正在读取项目" else "$projectCount 个项目",
                color = WorkspaceTextSecondary,
                fontSize = 10.sp,
                lineHeight = 13.sp,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Surface(
            modifier = Modifier.size(width = 100.dp, height = 36.dp),
            color = WorkspaceAccent,
            contentColor = Color.White,
            shape = RoundedCornerShape(11.dp),
            onClick = onCreate,
            enabled = !loading,
        ) {
            Row(
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("＋", fontSize = 20.sp, lineHeight = 20.sp, fontWeight = FontWeight.Normal)
                Spacer(Modifier.width(6.dp))
                Text("新建项目", fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun WorkspaceQuickActions(onClick: (WorkspaceShortcut) -> Unit) {
    Surface(
        modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(48.dp),
        shape = RoundedCornerShape(16.dp),
        color = Color.White,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            WorkspaceQuickEntry(WorkspaceIconKind.CLOUD, "备份管理", WorkspaceShortcut.BACKUP, Modifier.weight(1f), onClick)
            Box(Modifier.width(1.dp).height(20.dp).background(WorkspaceBorder))
            WorkspaceQuickEntry(WorkspaceIconKind.BOOK, "学习项目", WorkspaceShortcut.LEARNING, Modifier.weight(1f), onClick)
            Box(Modifier.width(1.dp).height(20.dp).background(WorkspaceBorder))
            WorkspaceQuickEntry(WorkspaceIconKind.CODE_BOX, "开发者后台", WorkspaceShortcut.BACKEND, Modifier.weight(1f), onClick)
        }
    }
}

@Composable
private fun WorkspaceQuickEntry(
    icon: WorkspaceIconKind,
    label: String,
    shortcut: WorkspaceShortcut,
    modifier: Modifier,
    onClick: (WorkspaceShortcut) -> Unit,
) {
    Row(
        modifier = modifier.fillMaxSize().clickable { onClick(shortcut) },
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        WorkspaceIcon(icon, Modifier.size(22.dp), WorkspaceAccent)
        Spacer(Modifier.width(6.dp))
        Text(
            label,
            color = WorkspaceTextPrimary,
            fontSize = 10.sp,
            lineHeight = 13.sp,
            maxLines = 1,
        )
    }
}

private val WorkspaceAccent = AutoScriptPalette.Accent
private val WorkspaceTextPrimary = AutoScriptPalette.TextPrimary
private val WorkspaceTextSecondary = AutoScriptPalette.TextSecondary
private val WorkspaceBorder = AutoScriptPalette.Border
private val WorkspaceMint = AutoScriptPalette.Mint
private val WorkspacePurple = AutoScriptPalette.Purple
private val WorkspaceDanger = AutoScriptPalette.Danger
private val WorkspaceDialogTitle = AutoScriptPalette.DialogTitle

/** ey.java：项目名输入框 `LengthFilter(30)`。 */
private const val MAX_PROJECT_NAME_INPUT = 30

/** 工作台用到的矢量图标，对应 `fragment_dashboard.xml` / `project_tree_1.xml` 里的 drawable。 */
private enum class WorkspaceIconKind(@DrawableRes val drawable: Int) {
    CLOUD(R.drawable.ic_cloud_outline_24),
    BOOK(R.drawable.ic_book_outline_24),
    CODE_BOX(R.drawable.ic_code_window_24),
    FOLDER(R.drawable.ic_folder_outline_24),
    CHEVRON(R.drawable.ic_chevron_right_20),
    PLAY(R.drawable.ic_play_outline_24),
    EDIT(R.drawable.ic_code_24),
    UI(R.drawable.ic_page_template_24),
    BACKUP(R.drawable.ic_backup_outline_24),
    PACKAGE(R.drawable.ic_android_24),
    DELETE(R.drawable.ic_delete_outline_24),
}

@Composable
private fun WorkspaceIcon(kind: WorkspaceIconKind, modifier: Modifier, tint: Color) {
    Icon(painterResource(kind.drawable), contentDescription = null, tint = tint, modifier = modifier)
}

@Composable
private fun WorkspaceMessage(message: String, accent: Color) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = accent.copy(alpha = 0.09f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("●", color = accent, style = MaterialTheme.typography.labelSmall)
            Spacer(Modifier.width(7.dp))
            Text(message, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun ProjectRow(
    project: ProjectSummary,
    expanded: Boolean,
    enabled: Boolean,
    busy: Boolean,
    onToggle: () -> Unit,
    onOpen: () -> Unit,
    onRun: () -> Unit,
    onSettings: () -> Unit,
    onUi: () -> Unit,
    onPackage: () -> Unit,
    onExport: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    // bc1：展开区高度 120ms 动画，箭头 100ms 旋转 90°。
    val chevronRotation by animateFloatAsState(if (expanded) 90f else 0f, tween(100), label = "project-chevron")
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = Color.White,
    ) {
        Column(Modifier.animateContentSize(tween(120))) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(72.dp)
                    .clickable(enabled = enabled, onClick = onToggle)
                    .padding(start = 12.dp, end = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .background(Color(0xFFEAF0FF), RoundedCornerShape(16.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    // project_tree_1：32dp 图标框 padding 8dp → 内径 16dp。
                    WorkspaceIcon(WorkspaceIconKind.FOLDER, Modifier.size(16.dp), WorkspaceAccent)
                }
                Spacer(Modifier.width(9.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        project.name,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = WorkspaceTextPrimary,
                        fontSize = 15.sp,
                        lineHeight = 19.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        "创建 ${formatTime(project.createdAt)}  ·  ${project.designWidth}×${project.designHeight}  ·  ID ${shortProjectId(project.projectId)}",
                        color = WorkspaceTextSecondary,
                        fontSize = 9.sp,
                        lineHeight = 12.sp,
                        modifier = Modifier.padding(top = 4.dp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.width(7.dp))
                WorkspaceIcon(
                    WorkspaceIconKind.CHEVRON,
                    Modifier.size(18.dp).graphicsLayer { rotationZ = chevronRotation },
                    WorkspaceTextSecondary,
                )
            }
            if (expanded) {
                Box(
                    Modifier.padding(start = 16.dp, end = 16.dp, top = 1.dp, bottom = 4.dp)
                        .fillMaxWidth().height(1.dp).background(WorkspaceBorder),
                )
                Row(
                    modifier = Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ProjectCardAction(if (busy) "处理中" else "启动", WorkspaceIconKind.PLAY, WorkspaceMint, enabled, onRun, Modifier.weight(1f))
                    ProjectCardAction("编辑", WorkspaceIconKind.EDIT, WorkspaceAccent, enabled, onOpen, Modifier.weight(1f))
                    ProjectCardAction("界面", WorkspaceIconKind.UI, WorkspacePurple, enabled, onUi, Modifier.weight(1f))
                    ProjectCardAction("备份", WorkspaceIconKind.BACKUP, WorkspaceMint, enabled, onExport, Modifier.weight(1f))
                    ProjectCardAction("打包", WorkspaceIconKind.PACKAGE, WorkspaceTextSecondary, enabled, onPackage, Modifier.weight(1f))
                    ProjectCardAction("删除", WorkspaceIconKind.DELETE, WorkspaceDanger, enabled, onDelete, Modifier.weight(1f))
                }
            }
        }
    }
}

/**
 * `service_tk_dashboard_delete_project.xml`：28dp 外边距、12dp 圆角、48dp 红标题、
 * 项目名、`#f8fafc` 算术验证区、64dp 双按钮。
 *
 * 参考用一道随机减法当二次确认，防误删；答错只提示不关闭弹窗，答对才真正删除。
 */
@Composable
private fun ProjectDeleteDialog(projectName: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    val challenge = remember(projectName) {
        val left = Random.nextInt(6, 20)
        left to Random.nextInt(1, left - 1)
    }
    var answer by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().padding(horizontal = 28.dp), contentAlignment = Alignment.Center) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = Color.White,
                shape = RoundedCornerShape(12.dp),
                shadowElevation = 10.dp,
            ) {
                Column {
                    Text(
                        "删除当前项目",
                        color = WorkspaceDanger,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 16.dp).wrapContentHeight(),
                    )
                    Box(Modifier.fillMaxWidth().height(1.dp).background(WorkspaceBorder))
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                        Text(
                            "当前项目：$projectName",
                            color = WorkspaceTextPrimary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Row(
                            Modifier.fillMaxWidth().padding(top = 12.dp)
                                .background(Color(0xFFF8FAFC))
                                .padding(horizontal = 12.dp, vertical = 11.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("计算验证", color = Color(0xFF697386), fontSize = 10.sp)
                                Text(
                                    "${challenge.first} − ${challenge.second} = ?",
                                    color = WorkspaceTextPrimary,
                                    fontSize = 21.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(top = 3.dp),
                                )
                            }
                            BasicTextField(
                                value = answer,
                                onValueChange = { value ->
                                    answer = value.filter(Char::isDigit).take(3)
                                    wrong = false
                                },
                                singleLine = true,
                                textStyle = TextStyle(
                                    fontSize = 17.sp,
                                    color = WorkspaceTextPrimary,
                                    textAlign = TextAlign.Center,
                                ),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                cursorBrush = SolidColor(WorkspaceAccent),
                                modifier = Modifier
                                    .size(width = 64.dp, height = 42.dp)
                                    .border(1.dp, WorkspaceBorder, RoundedCornerShape(6.dp))
                                    .wrapContentHeight(),
                            )
                        }
                        if (wrong) {
                            Text(
                                "答案不正确，请重新计算",
                                color = WorkspaceDanger,
                                fontSize = 10.sp,
                                modifier = Modifier.padding(top = 6.dp),
                            )
                        }
                    }
                    Box(Modifier.fillMaxWidth().height(1.dp).background(WorkspaceBorder))
                    Row(
                        Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier.weight(1f).height(38.dp).padding(end = 6.dp)
                                .background(AutoScriptPalette.PageBackground, RoundedCornerShape(6.dp))
                                .clickable(onClick = onDismiss),
                            contentAlignment = Alignment.Center,
                        ) { Text("取消", color = WorkspaceTextPrimary, fontSize = 12.sp) }
                        Box(
                            Modifier.weight(1f).height(38.dp).padding(start = 6.dp)
                                .background(WorkspaceDanger, RoundedCornerShape(6.dp))
                                .clickable {
                                    if (answer.toIntOrNull() == challenge.first - challenge.second) {
                                        onConfirm()
                                    } else {
                                        wrong = true
                                    }
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("确认删除", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProjectCardAction(
    label: String,
    icon: WorkspaceIconKind,
    color: Color,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().clickable(enabled = enabled, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        WorkspaceIcon(icon, Modifier.size(16.dp), if (enabled) color else color.copy(alpha = .45f))
        Spacer(Modifier.height(2.dp))
        Text(
            label,
            fontSize = 8.sp,
            lineHeight = 10.sp,
            fontWeight = if (icon == WorkspaceIconKind.PLAY) FontWeight.Bold else FontWeight.Normal,
            color = if (enabled) color else color.copy(alpha = .45f),
        )
    }
}

@Composable
private fun ProjectEditorDialog(
    title: String,
    initialName: String,
    allowModeSelection: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String, ProjectSourceMode, Int, Int) -> Unit,
) {
    var name by remember(initialName) { mutableStateOf(initialName) }
    var mode by remember { mutableStateOf(ProjectSourceMode.VISUAL) }
    var showingHelp by remember { mutableStateOf(false) }
    val configuration = LocalConfiguration.current
    val context = LocalContext.current
    // ey.java：本机分辨率取屏幕短边×长边，文案用 ASCII 的 x（"%dx%d"）。
    val localResolution = remember(configuration.screenWidthDp, configuration.screenHeightDp) {
        val metrics = context.resources.displayMetrics
        val shortSide = minOf(metrics.widthPixels, metrics.heightPixels)
        val longSide = maxOf(metrics.widthPixels, metrics.heightPixels)
        "${shortSide}x$longSide"
    }
    // 按选项身份而不是分辨率数值记选中：本机恰好是 720x1280 时，
    // 用字符串比较会让「推荐」和「本机」两行同时点亮成选中态。
    var selectedOption by remember(localResolution) { mutableIntStateOf(0) }
    val selectedResolution = when (selectedOption) {
        1 -> "1080x1920"
        2 -> localResolution
        else -> "720x1280"
    }
    val valid = name.trim().isNotEmpty() && name.trim().length <= MAX_PROJECT_NAME_INPUT
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        val dialogWindow = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect { dialogWindow?.setDimAmount(.15f) }
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Surface(
                modifier = Modifier.fillMaxWidth(.722f).widthIn(max = 320.dp).offset(y = (-2).dp),
                color = Color.White,
                shape = RoundedCornerShape(2.dp),
                shadowElevation = 4.dp,
            ) {
                Column {
                    // 帮助态：标题居中改为“创建说明”，左侧出现返回箭头，右侧问号隐藏（ey.c(true)）。
                    Box(Modifier.fillMaxWidth().height(42.dp)) {
                        if (showingHelp) {
                            Icon(
                                painterResource(R.drawable.visual_back_24),
                                contentDescription = "返回",
                                tint = WorkspaceDialogTitle,
                                modifier = Modifier.align(Alignment.CenterStart).size(42.dp)
                                    .clickable { showingHelp = false }.padding(9.dp),
                            )
                            Text(
                                "创建说明",
                                color = WorkspaceDialogTitle,
                                fontSize = 16.sp,
                                lineHeight = 20.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.align(Alignment.Center),
                            )
                        } else {
                            Text(
                                title,
                                color = WorkspaceDialogTitle,
                                fontSize = 16.sp,
                                lineHeight = 20.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.align(Alignment.CenterStart).padding(start = 12.dp, end = 42.dp),
                            )
                            Text(
                                "?",
                                color = WorkspaceDialogTitle,
                                fontSize = 20.sp,
                                lineHeight = 20.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier
                                    .align(Alignment.CenterEnd)
                                    .size(42.dp)
                                    .clickable { showingHelp = true }
                                    .wrapContentSize(Alignment.Center),
                            )
                        }
                    }
                    Box(Modifier.fillMaxWidth().height(hairline()).background(AutoScriptPalette.Divider))
                    if (showingHelp) {
                        ProjectCreationHelp(
                            mode = mode,
                            onModeChanged = { mode = it },
                            allowModeSelection = allowModeSelection,
                        )
                    } else {
                        Column(Modifier.padding(12.dp)) {
                            ProjectDialogSection("项目名称") {
                                BasicTextField(
                                    value = name,
                                    onValueChange = { if (it.length <= MAX_PROJECT_NAME_INPUT) name = it },
                                    singleLine = true,
                                    textStyle = TextStyle(color = WorkspaceTextPrimary, fontSize = 12.sp),
                                    modifier = Modifier.fillMaxWidth().height(32.dp).background(Color.White),
                                    decorationBox = { field ->
                                        Box(Modifier.fillMaxSize().padding(horizontal = 8.dp), contentAlignment = Alignment.CenterStart) {
                                            if (name.isEmpty()) Text("请输入项目名", color = Color(0xFF9CA3AF), fontSize = 12.sp)
                                            field()
                                        }
                                    },
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                            ProjectDialogSection("基准分辨率") {
                                ResolutionOption(
                                    label = "720x1280",
                                    badge = "推荐",
                                    selected = selectedOption == 0,
                                    onClick = { selectedOption = 0 },
                                )
                                Spacer(Modifier.height(6.dp))
                                ResolutionOption(
                                    label = "1080x1920",
                                    selected = selectedOption == 1,
                                    onClick = { selectedOption = 1 },
                                )
                                Spacer(Modifier.height(6.dp))
                                ResolutionOption(
                                    label = localResolution,
                                    badge = "本机",
                                    selected = selectedOption == 2,
                                    onClick = { selectedOption = 2 },
                                )
                            }
                        }
                    }
                    Box(Modifier.fillMaxWidth().height(hairline()).background(AutoScriptPalette.Divider))
                    Row(Modifier.fillMaxWidth().height(40.dp)) {
                        Text(
                            "取消",
                            color = Color.Black,
                            fontSize = 13.sp,
                            modifier = Modifier.weight(1f).fillMaxSize().clickable(onClick = onDismiss).wrapContentSize(Alignment.Center),
                        )
                        Box(Modifier.width(hairline()).fillMaxSize().background(AutoScriptPalette.Divider))
                        Text(
                            "创建",
                            color = Color.Black.copy(alpha = if (valid) 1f else .35f),
                            fontSize = 13.sp,
                            modifier = Modifier.weight(1f).fillMaxSize().clickable(enabled = valid) {
                                val parts = selectedResolution.split('x')
                                onConfirm(name.trim(), mode, parts[0].toInt(), parts[1].toInt())
                            }.wrapContentSize(Alignment.Center),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ProjectDialogSection(title: String, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().background(Color(0xFFF8FAFC)).padding(horizontal = 10.dp, vertical = 9.dp),
    ) {
        Text(title, color = Color(0xFF1F2937), fontSize = 12.sp, lineHeight = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(7.dp))
        content()
    }
}

@Composable
private fun ResolutionOption(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    badge: String? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth().height(36.dp)
            .background(if (selected) Color(0xFFEAF0FF) else Color.White)
            .clickable(onClick = onClick)
            .padding(horizontal = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Canvas(Modifier.size(22.dp)) {
            val c = if (selected) WorkspaceAccent else Color(0xFF94A3B8)
            if (selected) drawCircle(c, radius = size.minDimension * .26f, center = center)
            else drawCircle(c, radius = size.minDimension * .26f, center = center, style = Stroke(size.minDimension * .045f))
        }
        Spacer(Modifier.width(4.dp))
        Text(label, color = Color(0xFF1F2937), fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        badge?.let {
            Surface(color = WorkspaceAccent, shape = RoundedCornerShape(9.dp)) {
                Text(it, color = Color.White, fontSize = 10.sp, modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp))
            }
        }
    }
}

@Composable
private fun ProjectCreationHelp(
    mode: ProjectSourceMode,
    onModeChanged: (ProjectSourceMode) -> Unit,
    allowModeSelection: Boolean,
) {
    Column(Modifier.padding(12.dp)) {
        ProjectDialogSection("如何选择基准分辨率") {
            Text(
                "基准分辨率不会修改设备的屏幕分辨率，它决定项目使用的截图采集目标和统一坐标系。" +
                    "横竖屏切换时长短边自动对调；采集尺寸不会超过真实屏幕，只会降采样，不会放大低分辨率画面。" +
                    "图像识别按采集图坐标执行，点击和绘制时再换算到实际屏幕。\n\n" +
                    "720x1280（推荐）：图像处理量较小，适合多数自动化项目。\n" +
                    "1080x1920：设备分辨率足够时可保留更多图像细节，但会增加处理耗时和内存占用。\n" +
                    "本机分辨率：适合主要在当前设备上使用。\n\n" +
                    "更换到宽高比、显示缩放或页面布局差异较大的设备后，坐标和图片模板可能需要重新校对。",
                color = Color(0xFF64748B),
                fontSize = 11.sp,
                lineHeight = 16.sp,
            )
            if (allowModeSelection) {
                Spacer(Modifier.height(10.dp))
                Text("项目编辑方式", color = Color(0xFF1F2937), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Row(Modifier.padding(top = 5.dp)) {
                    ModeChoice("可视化积木", mode == ProjectSourceMode.VISUAL, Modifier.weight(1f)) { onModeChanged(ProjectSourceMode.VISUAL) }
                    Spacer(Modifier.width(6.dp))
                    ModeChoice("Lua 脚本", mode == ProjectSourceMode.LUA, Modifier.weight(1f)) { onModeChanged(ProjectSourceMode.LUA) }
                }
            }
        }
    }
}

@Composable
private fun ModeChoice(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Surface(
        modifier = modifier.height(30.dp).clickable(onClick = onClick),
        color = if (selected) Color(0xFFEAF0FF) else Color.White,
        shape = RoundedCornerShape(4.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(label, color = if (selected) WorkspaceAccent else WorkspaceTextSecondary, fontSize = 10.sp)
        }
    }
}

private fun ProjectSourceMode.displayName(): String =
    if (this == ProjectSourceMode.LUA) "手写 Lua" else "可视化积木"

private fun shortProjectId(projectId: String): String =
    if (projectId.length <= 8) projectId else "…${projectId.takeLast(8)}"

private fun formatTime(timestamp: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(timestamp))

private fun backupFileName(projectName: String): String {
    val safeName = projectName.map { character ->
        if (character.isISOControl() || character in "\\/:*?\"<>|") '_' else character
    }.joinToString("").trim().take(80).ifEmpty { "AutoScript项目" }
    return "$safeName.asproject"
}

/** Reads and consumes a Runner-owned one-shot capture without accepting an arbitrary file path. */
private fun decodeCaptureHandoff(context: android.content.Context, handoff: StudioCaptureHandoff): Bitmap {
    val directory = File(context.applicationContext.cacheDir, "capture-handoffs").canonicalFile
    val capture = File(directory, "${handoff.token}.png").canonicalFile
    require(capture.parentFile == directory && capture.isFile) { "截图交接文件已失效" }
    try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(capture.path, bounds)
        require(bounds.outWidth == handoff.width && bounds.outHeight == handoff.height) {
            "截图尺寸与交接信息不一致"
        }
        require(handoff.width.toLong() * handoff.height.toLong() <= 4_800_000L) {
            "截图尺寸超出编辑器预算"
        }
        return requireNotNull(
            BitmapFactory.decodeFile(
                capture.path,
                BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 },
            ),
        ) { "截图文件无法解码" }
    } finally {
        capture.delete()
    }
}

private const val IMPORT_BUSY_ID = "__import_backup__"

/** 槽位页的目标：本地项目可能已经删除，只剩备份，所以不依赖 [ProjectSummary]。 */
private data class BackupSlotsTarget(val projectId: String, val name: String)

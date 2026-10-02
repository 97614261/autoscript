package com.autoscript.studio

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.zIndex
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.autoscript.core.designsystem.AutoScriptPalette
import com.autoscript.core.model.RuntimeConnectionPhase
import com.autoscript.core.model.RuntimeConnectionState
import com.autoscript.core.model.RuntimeEngineState
import com.autoscript.project.store.ProjectSnapshot
import com.autoscript.project.store.ProjectFlow
import com.autoscript.project.store.ProjectStore
import com.autoscript.project.store.ProjectVariable
import com.autoscript.project.store.ProjectVariableScope
import com.autoscript.project.store.ProjectVariableType
import com.autoscript.runtime.client.RuntimeClient
import com.autoscript.runtime.client.ScreenshotPreviewResult
import com.autoscript.runtime.client.VisualCompileDiagnostic
import com.autoscript.runtime.client.VisualCompileResult
import com.autoscript.runtime.api.InputPointPickReply
import com.autoscript.studio.generated.BlockCategory
import com.autoscript.studio.generated.BlockContract
import com.autoscript.studio.generated.BlockPropertyEditor
import com.autoscript.studio.generated.BlockResourceKind
import com.google.gson.JsonParser
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.coroutines.coroutineContext

@Composable
internal fun VisualProjectScreen(
    snapshot: ProjectSnapshot,
    store: ProjectStore,
    runtimeClient: RuntimeClient,
    runtimeState: RuntimeConnectionState,
    consoleLines: List<String>,
    active: Boolean,
    initialFlowId: String? = null,
    modifier: Modifier = Modifier,
    onSnapshotChanged: (ProjectSnapshot) -> Unit,
    onExit: () -> Unit,
) {
    val projectId = snapshot.manifest.projectId
    val stepState = rememberVisualStepState(projectId, runtimeState, runtimeClient)
    val context = LocalContext.current
    val currentInputSnapshot by rememberUpdatedState(snapshot)
    val currentRuntimeState by rememberUpdatedState(runtimeState)
    var inputBackendFeatures by remember { mutableIntStateOf(0) }
    LaunchedEffect(runtimeState.rootState, runtimeState.sessionGeneration) {
        inputBackendFeatures = 0
        inputBackendFeatures = withContext(Dispatchers.IO) { runtimeClient.inputFeatures() }
    }
    var selectedFlowId by remember(projectId) {
        mutableStateOf(
            initialFlowId?.takeIf { requested -> snapshot.manifest.flows.any { it.flowId == requested } }
                ?: snapshot.manifest.entryFlowId
                ?: snapshot.manifest.flows.first().flowId,
        )
    }
    var action by remember(projectId) { mutableStateOf<VisualAction?>(null) }
    var error by remember(projectId) { mutableStateOf<String?>(null) }
    var notice by remember(projectId) { mutableStateOf<String?>(null) }
    var diagnostic by remember(projectId) { mutableStateOf<VisualCompileDiagnostic?>(null) }
    val editors = remember(projectId) {
        snapshot.manifest.flows.associate { flow ->
            flow.flowId to VisualEditorState.create(
                snapshot.flowSources[flow.flowId].orEmpty(),
                flow.rootBlockId,
            )
        }.toMutableMap()
    }
    var editorRevision by remember(projectId) { mutableIntStateOf(0) }
    var showDockProjectSettings by remember(projectId) { mutableStateOf(false) }
    var showVariableManager by remember(projectId) { mutableStateOf(false) }
    var showRecognitionTest by remember(projectId) { mutableStateOf(false) }
    var debugInspector by remember(projectId) { mutableStateOf<EditorToolPanel?>(null) }
    var showImageLibrary by remember(projectId) { mutableStateOf(false) }
    var showBlockPicker by remember(projectId) { mutableStateOf(false) }
    var blockPickerAutoSave by remember(projectId) { mutableStateOf(false) }
    var blockQuery by remember(projectId) { mutableStateOf("") }
    var blockCategory by remember(projectId) { mutableStateOf<BlockCategory?>(null) }
    var insertionChildBlockName by remember(projectId) { mutableStateOf<String?>(null) }
    var pendingDockInsertHint by remember(projectId) { mutableStateOf<String?>(null) }
    var dockClipboard by remember(projectId) { mutableStateOf<List<VisualSubtreeClipboard>?>(null) }
    var pendingDockPaste by remember(projectId) { mutableStateOf(false) }
    var pendingDockIndentSlots by remember(projectId) { mutableStateOf<List<String>?>(null) }
    var pendingBlockConfiguration by remember(projectId) { mutableStateOf<VisualPendingBlockInsert?>(null) }
    var pendingImageBlock by remember(projectId) { mutableStateOf<Pair<BlockContract, JsonObject>?>(null) }
    var pendingRecognitionInsert by remember(projectId) { mutableStateOf<RecognitionInsertRequest?>(null) }
    var recognitionCaptureActive by remember(projectId) { mutableStateOf(false) }
    var recognitionCaptureSelection by remember(projectId) { mutableStateOf<ImageToolCodeGen.VisualSelection?>(null) }
    var recognitionCaptureTemplatePath by remember(projectId) { mutableStateOf<String?>(null) }
    var editingCallNodeId by remember(projectId) { mutableStateOf<String?>(null) }
    var reflectionPosition by remember(projectId) { mutableStateOf<EditorInsertPosition?>(null) }
    var reflectionChildSlot by remember(projectId) { mutableStateOf<String?>(null) }
    var showInterfacePreview by remember(projectId) { mutableStateOf(false) }
    var editingCallTargetId by remember(projectId) { mutableStateOf<String?>(null) }
    var propertyInputs by remember(projectId) { mutableStateOf<Map<String, String>>(emptyMap()) }
    var callArgumentInputs by remember(projectId) { mutableStateOf<Map<String, String>>(emptyMap()) }
    var callArgumentError by remember(projectId) { mutableStateOf<String?>(null) }
    var showingInputPointPicker by remember(projectId) { mutableStateOf(false) }
    var inputPointMode by remember(projectId) { mutableStateOf(ImageToolMode.TAP) }
    var inputPointBitmap by remember(projectId) { mutableStateOf<Bitmap?>(null) }
    var pendingTemplateRoi by remember(projectId) { mutableStateOf<ImageToolCodeGen.Roi?>(null) }
    var inputPointCapturing by remember(projectId) { mutableStateOf(false) }
    var inputPointMessage by remember(projectId) { mutableStateOf<String?>(null) }
    var inputPointRequestId by remember(projectId) { mutableIntStateOf(0) }
    var inputPointCaptureJob by remember(projectId) { mutableStateOf<Job?>(null) }
    var inputPickTarget by remember(projectId) { mutableStateOf<InputPickTarget?>(null) }
    var inputFloatingRequest by remember(projectId) { mutableStateOf<Long?>(null) }
    var pendingInputInsertion by remember(projectId) { mutableStateOf<InputInsertionDraft?>(null) }
    var inputPickOpenRequest by remember(projectId) { mutableIntStateOf(0) }
    var imageToolRequest by remember(projectId) { mutableStateOf(false) }
    var pickedImageRegion by remember(projectId) { mutableStateOf<ImageToolCodeGen.Roi?>(null) }
    val scope = rememberCoroutineScope()
    val sourceFiles = remember(store) { ProjectSourceFiles(store) }
    var sourceTree by remember(projectId) { mutableStateOf(SourceFileTree()) }
    var sourceBusy by remember(projectId) { mutableStateOf(false) }
    var sourceMessage by remember(projectId) { mutableStateOf<String?>(null) }
    val editor = requireNotNull(editors[selectedFlowId])
    LaunchedEffect(stepState.position) {
        stepState.position?.let { position ->
            if (position.flowId in editors) selectedFlowId = position.flowId
        }
    }

    DisposableEffect(projectId) {
        onDispose {
            inputFloatingRequest?.let(runtimeClient::cancelInputPointPick)
            inputPointRequestId++
            inputPointCaptureJob?.cancel()
            inputPointBitmap?.recycle()
        }
    }

    LaunchedEffect(selectedFlowId) {
        editingCallNodeId = null
        inputFloatingRequest?.let(runtimeClient::cancelInputPointPick)
        inputFloatingRequest = null
        if (inputPickTarget?.flowId != selectedFlowId) {
            inputPickTarget = null
            pendingInputInsertion = null
        }
    }

    LaunchedEffect(snapshot) {
        sourceTree = withContext(Dispatchers.IO) {
            runCatching { sourceFiles.load(snapshot) }.getOrDefault(SourceFileTree())
        }
    }
    val nodes = remember(editor, editorRevision) { editor.rows }
    val runtimeBusy = action != null
    val running = runtimeState.engineState in setOf(
        RuntimeEngineState.RUNNING,
        RuntimeEngineState.PAUSED,
    )
    val stopping = runtimeState.engineState == RuntimeEngineState.STOPPING
    val canStartAction = !runtimeBusy && !running && !stopping
    val canStop = runtimeState.phase == RuntimeConnectionPhase.CONNECTED && running && !runtimeBusy
    val canPauseResume = canStop
    val canEditProjectSettings = !runtimeBusy && !running && !stopping &&
        editors.values.none { it.isDirty }

    if (showDockProjectSettings) {
        ProjectSettingsDialog(
            snapshot = snapshot,
            store = store,
            onSnapshotChanged = onSnapshotChanged,
            onDismiss = { showDockProjectSettings = false },
        )
    }

    fun showCompileResult(result: VisualCompileResult): String? = when (result) {
        is VisualCompileResult.Success -> {
            diagnostic = null
            notice = "编译成功 · ${result.generationId.take(20)}"
            result.generationId
        }
        is VisualCompileResult.Invalid -> {
            diagnostic = result.diagnostic
            result.diagnostic.flowId?.let { flowId ->
                if (snapshot.manifest.flows.any { it.flowId == flowId }) selectedFlowId = flowId
            }
            error = formatDiagnostic(result.diagnostic)
            null
        }
        is VisualCompileResult.Unavailable -> {
            error = result.message
            null
        }
    }

    suspend fun saveEditorIfNeeded(flowId: String, state: VisualEditorState): Boolean {
        if (!state.isDirty) return true
        if (state.isReadOnly) {
            selectedFlowId = flowId
            error = "插件结构无效，当前只能只读查看"
            return false
        }
        val submitted = state.currentSource
        val expected = state.savedSource
        val validation = withContext(Dispatchers.IO) {
            runtimeClient.validateVisualDraft(
                projectId,
                flowId,
                submitted.toByteArray(Charsets.UTF_8),
            )
        }
        if (validation !is VisualCompileResult.Success) {
            selectedFlowId = flowId
            showCompileResult(validation)
            return false
        }
        val saved = runCatching {
            withContext(Dispatchers.IO) {
                store.saveFlow(projectId, flowId, submitted, expected)
            }
        }.getOrElse { failure ->
            selectedFlowId = flowId
            error = failure.message ?: "插件保存失败"
            return false
        }
        state.markSaved(submitted)
        editorRevision++
        onSnapshotChanged(saved)
        notice = if (state.isDirty) "已保存提交快照，仍有后续修改" else "插件 $flowId 已安全保存"
        return true
    }

    suspend fun saveAllIfNeeded(): Boolean {
        for ((flowId, state) in editors.toSortedMap()) {
            if (!saveEditorIfNeeded(flowId, state)) return false
        }
        return true
    }

    fun saveCurrent() {
        if (runtimeBusy || !editor.isDirty) return
        action = VisualAction.SAVING
        error = null
        notice = null
        scope.launch {
            saveEditorIfNeeded(selectedFlowId, editor)
            action = null
        }
    }

    fun compileOnly() {
        if (!canStartAction) return
        action = VisualAction.COMPILING
        error = null
        notice = null
        scope.launch {
            if (!saveAllIfNeeded()) {
                action = null
                return@launch
            }
            val result = withContext(Dispatchers.IO) {
                runtimeClient.compileVisualProject(projectId)
            }
            showCompileResult(result)
            action = null
        }
    }

    fun runProject(singleStep: Boolean = false) {
        if (action != null || !canStartAction) return
        if (runtimeState.phase != RuntimeConnectionPhase.CONNECTED) {
            error = "Runner尚未连接"
            return
        }
        action = VisualAction.STARTING
        val runFlowId = selectedFlowId
        val stepNodeId = if (singleStep) editor.selectedNodeId else null
        if (singleStep && (stepNodeId == null || editor.isNodeDisabled(stepNodeId))) { action = null; error = "请选择可执行积木"; return }
        error = null
        notice = null
        scope.launch {
            try {
                if (!saveAllIfNeeded()) return@launch
                val compileResult = withContext(Dispatchers.IO) {
                    runtimeClient.compileVisualProject(projectId)
                }
                val generationId = showCompileResult(compileResult) ?: return@launch
                val refreshed = withContext(Dispatchers.IO) { store.openProject(projectId) }
                onSnapshotChanged(refreshed)
                val plan = withContext(Dispatchers.IO) {
                    RuntimeProjectPlan.fromVisualSnapshot(refreshed, generationId, runFlowId).let {
                        if (singleStep) it.forSingleStep(runFlowId, stepNodeId) else it.forEditorRun(refreshed)
                    }
                }
                val accepted = withContext(Dispatchers.IO) {
                    runtimeClient.startProject(
                        projectId = projectId,
                        generatedLuaModule = plan.luaSource,
                        resources = plan.resources,
                        requiresPointerInput = plan.requiresPointerInput,
                        capabilities = plan.capabilities,
                        designWidth = plan.designWidth,
                        designHeight = plan.designHeight,
                        scaleMode = plan.scaleMode,
                        scriptUiJson = null,
                    )
                }
                if (accepted) {
                    notice = if (singleStep) "已执行单步请求：前置步骤会执行，所选步骤后停在下一检查点；未到达所选步骤则结束" else "积木脚本已提交运行"
                } else {
                    error = currentRuntimeState.message ?: "Runner拒绝启动，请检查运行环境"
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error = failure.message ?: "项目启动失败"
            } finally {
                action = null
            }
        }
    }

    fun stopProject() {
        if (!canStop) return
        action = VisualAction.STOPPING
        error = null
        notice = null
        scope.launch {
            withContext(Dispatchers.IO) { runtimeClient.requestStop() }
            action = null
        }
    }

    fun controlRunningProject(pause: Boolean) {
        if (!canPauseResume) return
        action = VisualAction.CONTROLLING
        scope.launch {
            try {
                val accepted = withContext(Dispatchers.IO) {
                    if (pause) runtimeClient.requestPause() else runtimeClient.requestResume()
                }
                if (!accepted) error = "运行控制被拒绝，请检查当前会话状态"
            } finally {
                action = null
            }
        }
    }


    if (showVariableManager) {
        VisualVariableManagerDialog(
            variables = snapshot.manifest.variables,
            currentFlowId = selectedFlowId,
            flows = snapshot.manifest.flows,
            onDismiss = { showVariableManager = false },
            onSave = { variables ->
                scope.launch {
                    if (!editorCanMutate(runtimeBusy, editor.isReadOnly, runtimeState.engineState)) {
                        error = "请停止运行后再修改变量"; return@launch
                    }
                    runCatching {
                        withContext(Dispatchers.IO) { store.updateVariables(projectId, variables, snapshot.manifest.variables) }
                    }.onSuccess {
                        onSnapshotChanged(it); notice = "变量表已保存"; showVariableManager = false
                    }.onFailure { error = it.message ?: "变量表保存失败" }
                }
            },
            onSaveAndInsert = { variables, arguments ->
                scope.launch {
                    if (!editorCanMutate(runtimeBusy, editor.isReadOnly, runtimeState.engineState)) {
                        error = "请停止运行后再修改变量"; return@launch
                    }
                    runCatching {
                        withContext(Dispatchers.IO) { store.updateVariables(projectId, variables, snapshot.manifest.variables) }
                    }.onSuccess { updated ->
                        onSnapshotChanged(updated)
                        val contract = BlockCatalog.find("variable.calculate")
                        if (contract != null && editor.insertBlock(contract, arguments) != null) {
                            editorRevision++; showVariableManager = false
                            pendingBlockConfiguration = null
                            notice = "已加入变量计算"; saveCurrent()
                        } else error = "变量表已保存，但无法把计算加入当前位置"
                    }.onFailure { error = it.message ?: "变量表保存失败" }
                }
            },
        )
    }

    fun insertDockBlock(
        hint: String,
        childSlot: String?,
        position: EditorInsertPosition = EditorInsertPosition.BELOW,
    ) {
        if (!editorCanMutate(runtimeBusy, editor.isReadOnly, runtimeState.engineState)) { error = "请停止运行后再修改插件"; return }
        val command = hint.lineSequence().firstOrNull()?.trim().orEmpty()
        when (command) {
            "--@autoscript-control:elseIf" -> {
                val selected = editor.selectedNodeId
                if (selected == null || !editor.addElseIfBranch(selected)) {
                    error = "请先选中一个“如果”节点，再添加否则如果"
                    return
                }
                editorRevision++
                propertyInputs = conditionPropertyTexts(
                    requireNotNull(BlockCatalog.find("control.if")),
                    requireNotNull(editor.nodeArguments(selected)),
                )
                editingCallNodeId = selected
                reflectionPosition = null
                reflectionChildSlot = null
                notice = "已新增否则如果分支，请填写它的条件"
                return
            }
            "--@autoscript-control:else" -> {
                val selected = editor.selectedNodeId
                if (selected == null || "else" !in editor.childBlockNames(selected)) {
                    error = "请先选中一个“如果”节点，再进入否则分支"
                    return
                }
                insertionChildBlockName = "else"
                showBlockPicker = true
                notice = "在“否则”分支中选择要加入的积木"
                return
            }
        }
        val capabilities = snapshot.manifest.capabilities.toSet()
        if (hint.trimStart().startsWith(LOOP_CONFIG_HINT) && loopInsertionFromHint(hint) == null) {
            error = "循环配置无效，未加入积木"
            return
        }
        val query = legacyDockBlockQuery(hint)
        // 函数库直接给出积木 kind 时不做模糊搜索；能力不足要明确说明而不是静默落到选择器。
        val direct = FunctionCatalog.blockKindOf(hint)?.let(BlockCatalog::find)
            ?.let { BlockSearchResult(it, it.requiredCapabilities - capabilities) }
        if (direct != null && !direct.isAvailable) {
            error = "${direct.contract.title}需要能力：${direct.missingCapabilities.joinToString()}"
            return
        }
        val matching = direct?.let(::listOf)
            ?: BlockCatalog.search(query, capabilities).filter(BlockSearchResult::isAvailable)
        if (matching.size == 1) {
            val contract = matching.single().contract
            if (contract.kind in visualRecognitionKinds) {
                pendingRecognitionInsert = RecognitionInsertRequest(contract,
                    legacyDockBlockArguments(contract, hint, initialRecognitionArguments(contract, snapshot)), childSlot, position)
                recognitionCaptureSelection = null
                recognitionCaptureTemplatePath = null
                return
            }
            val preferredFlowId = if (contract.kind == "flow.call") JumpPanelCode.callTargetId(hint) else null
            val args = initialBlockArguments(
                contract,
                snapshot.manifest.flows,
                snapshot.manifest.resources,
                selectedFlowId,
                preferredFlowId = preferredFlowId,
            )
            if (args == null) {
                error = "缺少${contract.title}所需的目标插件或项目资源"
                return
            }
            val migratedArgs = legacyDockBlockArguments(contract, hint, args)
            visualJumpInsertionError(contract.kind, migratedArgs, editor, childSlot, position)?.let {
                error = it
                return
            }
            if (contract.kind == "ui.get") {
                pendingBlockConfiguration = VisualPendingBlockInsert(contract, migratedArgs, childSlot, position)
                return
            }
            if (contract.kind in setOf("variable.set", "variable.copy", "variable.calculate", "control.if") && position != EditorInsertPosition.REPLACE && variableCalculationArguments(hint) == null) {
                pendingBlockConfiguration = VisualPendingBlockInsert(contract, migratedArgs, childSlot, position)
                return
            }
            if (position == EditorInsertPosition.REPLACE) {
                if (!editor.replaceSelectedBlock(contract, migratedArgs)) {
                    error = "无法修改当前选择行"
                } else {
                    editorRevision++
                    error = null
                    notice = "已修改：${contract.title}"
                    saveCurrent()
                }
                return
            }
            val originalSelection = editor.selectedNodeId
            if (position == EditorInsertPosition.LIST_BOTTOM) {
                editor.selectedNodeId = editor.rows.lastOrNull { it.depth == 0 }?.nodeId
            }
            val inserted = editor.insertBlock(contract, migratedArgs, intoChildBlockName = childSlot)
            if (inserted != null && position == EditorInsertPosition.ABOVE && originalSelection != null) {
                editor.moveSelected(-1)
            }
            if (inserted == null) {
                error = if (contract.kind in positionLoopKinds) "${contract.title}只能加入循环体，请先选中循环体中的积木或选择循环体插入位置"
                    else "无法把${contract.title}加入当前位置"
            } else {
                editorRevision++
                error = null
                notice = "已加入：${contract.title}"
                if (contract.kind == "flow.call") {
                    reflectionPosition = null
                    reflectionChildSlot = null
                    editingCallNodeId = inserted
                }
                saveCurrent()
            }
            return
        }
        blockQuery = query.takeIf { matching.isNotEmpty() }.orEmpty()
        insertionChildBlockName = childSlot
        blockPickerAutoSave = true
        showBlockPicker = true
    }

    fun captureInputPointScreenshot(delayMillis: Long = 0L) {
        if (inputPointCapturing) return
        val requestId = inputPointRequestId + 1
        inputPointRequestId = requestId
        inputPointCapturing = true
        inputPointMessage = "正在截图…"
        inputPointCaptureJob?.cancel()
        inputPointCaptureJob = scope.launch {
            var decoded: Bitmap? = null
            try {
                // Even an immediate capture needs one composition frame for the dialog to disappear.
                delay(delayMillis.coerceAtLeast(200L))
                when (val result = withContext(Dispatchers.IO) { runtimeClient.capturePreview() }) {
                    is ScreenshotPreviewResult.Success -> {
                        decoded = try {
                            withContext(Dispatchers.IO) { BitmapFactory.decodeFile(result.file.path) }
                        } finally {
                            result.file.delete()
                        }
                        coroutineContext.ensureActive()
                        if (requestId == inputPointRequestId && showingInputPointPicker) {
                            inputPointBitmap?.recycle()
                            inputPointBitmap = decoded
                            decoded = null
                            inputPointMessage = inputPointBitmap?.let { "截图 ${it.width}×${it.height}，请在图上定位" }
                                ?: "截图文件无法解码"
                        }
                    }
                    is ScreenshotPreviewResult.Unavailable -> if (requestId == inputPointRequestId) {
                        inputPointMessage = result.message
                    }
                }
            } catch (_: CancellationException) {
                // Closing or replacing the picker cancels the in-flight capture.
            } catch (failure: Exception) {
                if (requestId == inputPointRequestId) inputPointMessage = failure.message ?: "截图失败"
            } finally {
                decoded?.recycle()
                if (requestId == inputPointRequestId) {
                    inputPointCapturing = false
                    inputPointCaptureJob = null
                }
            }
        }
    }

    fun closeInputCanvas() {
        showingInputPointPicker = false
        inputPointRequestId++
        inputPointCaptureJob?.cancel()
        inputPointCaptureJob = null
        inputPointCapturing = false
        inputPointBitmap?.recycle()
        inputPointBitmap = null
    }

    fun queueInputInsertion(snippet: ImageToolCodeGen.Snippet) {
        val target = inputPickTarget ?: InputPickTarget(projectId, selectedFlowId, editor.currentSource, editor.selectedNodeId)
        val draft = target.draft(snippet)
        if (runtimeBusy || running || !draft.matches(projectId, selectedFlowId, editor)) {
            error = "插件或内容已变化，请重新选择点位"
            closeInputCanvas()
            return
        }
        runCatching { prepareInputBlocks(snippet, snapshot.manifest.capabilities.toSet()) }.onFailure {
            error = it.message ?: "输入动作无法加入"
            inputPointMessage = error
        }.onSuccess {
            pendingInputInsertion = draft
            closeInputCanvas()
            inputPickOpenRequest++
        }
    }

    fun openInputFloatingPicker(mode: ImageToolMode) {
        if (runtimeBusy || running) { error = "请先停止运行"; return }
        val target = InputPickTarget(projectId, selectedFlowId, editor.currentSource, editor.selectedNodeId)
        inputPickTarget = target
        val design = snapshot.manifest.design
        inputFloatingRequest = runtimeClient.beginInputPointPick(projectId, selectedFlowId, inputPointAction(mode)) { result ->
            inputFloatingRequest = null
            if (result.status != InputPointPickReply.SUCCESS) error = result.message.ifBlank { "选点已取消" }
            else if (inputPickTarget != target || currentInputSnapshot.manifest.design != design) error = "插件或设计分辨率已变化，请重新选点"
            else runCatching { inputPointSnippet(result, design.width, design.height, design.scaleMode) }
                .onSuccess(::queueInputInsertion).onFailure { error = it.message ?: "选点转换失败" }
        }
        if (inputFloatingRequest != null) context.inputPickerActivity()?.moveTaskToBack(true)
        else error = "悬浮选点未启动，请检查悬浮窗权限和Root状态"
    }

    fun openInputPointPicker(mode: ImageToolMode, delayMillis: Long = 0L) {
        inputPickTarget = InputPickTarget(projectId, selectedFlowId, editor.currentSource, editor.selectedNodeId)
        if (mode == ImageToolMode.POINTER_UP) { queueInputInsertion(ImageToolCodeGen.pointerUp()); return }
        imageToolRequest = false
        inputPointRequestId++
        inputPointCaptureJob?.cancel()
        inputPointCaptureJob = null
        inputPointCapturing = false
        inputPointMode = mode
        inputPointBitmap?.recycle()
        inputPointBitmap = null
        showingInputPointPicker = true
        inputPointMessage = null
        captureInputPointScreenshot(delayMillis)
    }

    fun openImageTools(delayMillis: Long) {
        openInputPointPicker(ImageToolMode.CROP, delayMillis)
        imageToolRequest = true
    }

    fun openImageToolMode(mode: ImageToolMode, delayMillis: Long) {
        openInputPointPicker(mode, delayMillis)
        imageToolRequest = true
    }

    fun confirmInputInsertion(draft: InputInsertionDraft, position: EditorInsertPosition, slot: String?) {
        if (runtimeBusy || running || !draft.matches(projectId, selectedFlowId, editor)) {
            error = "插件内容已变化，请重新选点"
            pendingInputInsertion = null
            return
        }
        val prepared = runCatching { prepareInputBlocks(draft.snippet, snapshot.manifest.capabilities.toSet()) }.getOrElse {
            error = it.message ?: "输入动作不可用"
            return
        }
        if (!editor.insertBlocks(prepared, position, draft.anchorNodeId, slot)) {
            error = "无法在指定位置加入完整动作，未保留部分积木"
            return
        }
        pendingInputInsertion = null
        inputPickTarget = null
        editorRevision++
        closeInputCanvas()
        notice = "已加入草稿，正在保存：${draft.snippet.summary}"
        error = null
        action = VisualAction.SAVING
        scope.launch {
            if (saveEditorIfNeeded(draft.flowId, editor)) notice = "已加入并保存：${draft.snippet.summary}"
            else error = "动作已加入草稿，尚未保存；请用保存重试：${error.orEmpty()}"
            action = null
        }
    }

    fun emitInputPointSnippet(snippet: ImageToolCodeGen.Snippet) {
        if (snippet.rejection != null) {
            inputPointMessage = snippet.rejection
            return
        }
        snippet.imageSelection?.let { selection ->
            if (recognitionCaptureActive) {
                recognitionCaptureSelection = selection
                recognitionCaptureActive = false
                showingInputPointPicker = false
                inputPointRequestId++
                inputPointCaptureJob?.cancel()
                inputPointCaptureJob = null
                inputPointCapturing = false
                inputPointBitmap?.recycle()
                inputPointBitmap = null
                return
            }
            if (selection.mode == ImageToolMode.REGION) {
                pickedImageRegion = selection.roi
                inputPointMessage = "已选识别范围；返回图像面板选择识别积木"
                notice = inputPointMessage
                return
            }
            val draft = visualImageToolDraft(selection)
            if (draft == null) {
                inputPointMessage = "多点找色至少需要锚点和一个采样点"
                return
            }
            val contract = BlockCatalog.find(draft.first)
            val missing = contract?.requiredCapabilities.orEmpty() - snapshot.manifest.capabilities.toSet()
            val initial = contract?.let {
                initialBlockArguments(it, snapshot.manifest.flows, snapshot.manifest.resources, selectedFlowId)
            }
            if (contract == null || initial == null || missing.isNotEmpty()) {
                inputPointMessage = if (missing.isNotEmpty()) "需要能力：${missing.joinToString()}" else "图像积木不可用"
                return
            }
            pendingImageBlock = contract to initial.withImageToolValues(draft.second)
            showingInputPointPicker = false
            inputPointRequestId++
            inputPointCaptureJob?.cancel()
            inputPointCaptureJob = null
            inputPointBitmap?.recycle()
            inputPointBitmap = null
            return
        }
        if (snippet.flowBlocks.isNotEmpty()) {
            queueInputInsertion(snippet)
            return
        }
        val kind = when {
            snippet.code.trimStart().startsWith("Input.tap(") -> "input.tap"
            snippet.code.trimStart().startsWith("Input.swipe(") -> "input.swipe"
            else -> null
        }
        if (kind == null) {
            inputPointMessage = "该输入动作无法映射到插件积木，未插入"
            return
        }
        insertDockBlock("${FunctionCatalog.BLOCK_HINT_PREFIX}$kind\n${snippet.code}", null)
        inputPointMessage = error ?: "已加入插件：${snippet.summary}"
    }

    fun finishDockMutation(changed: Boolean, message: String) {
        if (!changed) {
            error = "当前节点不能执行此操作"
            return
        }
        editorRevision++
        error = null
        notice = message
        saveCurrent()
    }

    fun pasteDockClipboard(childSlot: String?) {
        val clipboard = dockClipboard ?: run {
            error = "剪贴板为空"
            return
        }
        finishDockMutation(
            editor.pasteSelection(clipboard, childSlot),
            "已粘贴节点副本",
        )
    }

    fun runDockCommand(command: EditorProgramCommand) {
        if (!editorCanMutate(runtimeBusy, editor.isReadOnly, runtimeState.engineState)) return
        when (command) {
            EditorProgramCommand.MOVE_UP -> finishDockMutation(editor.moveSelection(-1), "节点已上移")
            EditorProgramCommand.MOVE_DOWN -> finishDockMutation(editor.moveSelection(1), "节点已下移")
            EditorProgramCommand.OUTDENT -> finishDockMutation(editor.outdentSelection(), "节点已减少一级缩进")
            EditorProgramCommand.INDENT -> {
                val selected = nodes.firstOrNull { it.nodeId == editor.selectedRoots().firstOrNull() }
                val siblings = nodes.filter { it.blockId == selected?.blockId }.sortedBy { it.orderKey }
                val previous = siblings.getOrNull(siblings.indexOfFirst { it.nodeId == selected?.nodeId } - 1)
                val slots = previous?.nodeId?.let(editor::childBlockNames).orEmpty()
                when (slots.size) {
                    0 -> error = "上一节点不是容器"
                    1 -> finishDockMutation(editor.indentSelection(slots.single()), "节点已缩进")
                    else -> pendingDockIndentSlots = slots
                }
            }
            EditorProgramCommand.COPY -> {
                dockClipboard = editor.copySelection().takeIf { it.isNotEmpty() }
                if (dockClipboard == null) error = "请先选择节点" else {
                    error = null
                    notice = "已复制整棵子树"
                }
            }
            EditorProgramCommand.CUT -> {
                val copied = editor.copySelection().takeIf { it.isNotEmpty() }
                if (copied == null) error = "请先选择节点" else {
                    dockClipboard = copied
                    finishDockMutation(editor.deleteSelection(), "已剪切整棵子树")
                }
            }
            EditorProgramCommand.PASTE -> {
                if (dockClipboard == null) error = "剪贴板为空" else {
                    val selected = nodes.firstOrNull { it.nodeId == editor.selectedNodeId }
                    val slots = selected?.nodeId?.let(editor::childBlockNames).orEmpty()
                    if (selected != null && slots.isNotEmpty()) pendingDockPaste = true else pasteDockClipboard(null)
                }
            }
            EditorProgramCommand.UNDO -> finishDockMutation(editor.undo(), "已撤销")
            EditorProgramCommand.REDO -> finishDockMutation(editor.redo(), "已重做")
            EditorProgramCommand.DATA_BACKFILL -> {
                error = null
                debugInspector = EditorToolPanel.DATA_BACKFILL
            }
            EditorProgramCommand.TOGGLE_DISABLED -> finishDockMutation(editor.toggleDisabledSelection(), "已更新积木执行状态")
            EditorProgramCommand.SEARCH,
            EditorProgramCommand.EXPAND_ALL,
            EditorProgramCommand.COLLAPSE_ALL
            -> Unit // handled as view-only state by EditorDock
        }
    }

    fun ensureEditor(updated: ProjectSnapshot, flowId: String) {
        val flow = updated.manifest.flows.singleOrNull { it.flowId == flowId } ?: return
        if (editors[flowId] == null) {
            editors[flowId] = VisualEditorState.create(updated.flowSources[flowId].orEmpty(), flow.rootBlockId)
        }
    }

    /** 源文件管理的动作全部经 [ProjectSourceFiles]；返回新快照的操作会同步给宿主。 */
    fun handleSourceAction(sourceAction: SourceManagerAction) {
        if (sourceBusy || runtimeBusy) return
        sourceBusy = true
        sourceMessage = null
        scope.launch {
            val result = runCatching {
                when (sourceAction) {
                    is SourceManagerAction.Open -> {
                        if (saveAllIfNeeded()) selectedFlowId = sourceAction.flowId
                        null
                    }
                    is SourceManagerAction.InsertCall -> {
                        val target = snapshot.manifest.flows.firstOrNull { it.flowId == sourceAction.flowId }
                        if (target == null) error = "源文件不存在" else {
                            finishDockMutation(
                                editor.insertFlowCall(target.flowId, defaultFlowCallArguments(target)) != null,
                                "已加入调用：${target.displayName()}",
                            )
                        }
                        null
                    }
                    is SourceManagerAction.CreateFile -> withContext(Dispatchers.IO) {
                        sourceFiles.createFlow(snapshot, sourceAction.name, sourceAction.group)
                    }.also { updated ->
                        val created = updated.manifest.flows.map(ProjectFlow::flowId) -
                            snapshot.manifest.flows.map(ProjectFlow::flowId).toSet()
                        created.singleOrNull()?.let { flowId ->
                            ensureEditor(updated, flowId)
                            selectedFlowId = flowId
                        }
                    }
                    is SourceManagerAction.SaveAs -> withContext(Dispatchers.IO) {
                        sourceFiles.copyFlow(snapshot, sourceAction.flowId, sourceAction.name)
                    }
                    is SourceManagerAction.RenameFile -> withContext(Dispatchers.IO) {
                        sourceFiles.renameFlow(snapshot, sourceAction.flowId, sourceAction.name)
                    }
                    is SourceManagerAction.Delete -> {
                        if (!saveAllIfNeeded()) return@runCatching null
                        val deletion = withContext(Dispatchers.IO) {
                            if (sourceAction.groups.isNotEmpty()) sourceFiles.deleteGroups(snapshot, sourceAction.groups)
                            sourceFiles.deleteFlows(snapshot, sourceAction.flowIds)
                        }
                        deletion.deleted.forEach(editors::remove)
                        if (selectedFlowId in deletion.deleted) {
                            selectedFlowId = requireNotNull(deletion.snapshot.manifest.entryFlowId)
                        }
                        if (deletion.failures.isNotEmpty()) sourceMessage = deletion.failures.joinToString("\n")
                        deletion.snapshot
                    }
                    is SourceManagerAction.CreateGroup -> {
                        withContext(Dispatchers.IO) { sourceFiles.createGroup(snapshot, sourceAction.name) }
                        null
                    }
                    is SourceManagerAction.RenameGroup -> {
                        withContext(Dispatchers.IO) { sourceFiles.renameGroup(snapshot, sourceAction.group, sourceAction.name) }
                        null
                    }
                    is SourceManagerAction.AddToGroup -> {
                        withContext(Dispatchers.IO) { sourceFiles.addToGroup(snapshot, sourceAction.group, sourceAction.flowIds) }
                        null
                    }
                    is SourceManagerAction.RemoveFromGroup -> {
                        withContext(Dispatchers.IO) { sourceFiles.removeFromGroup(snapshot, sourceAction.flowIds) }
                        null
                    }
                }
            }
            result.onFailure { failure -> sourceMessage = failure.message ?: "源文件操作失败" }
            val current = result.getOrNull() ?: snapshot
            if (current !== snapshot) onSnapshotChanged(current)
            sourceTree = withContext(Dispatchers.IO) { runCatching { sourceFiles.load(current) }.getOrDefault(sourceTree) }
            sourceBusy = false
        }
    }

    /** 旧版插件管理里需要宿主执行的动作：检错走 Rust 编译，未调用列出没被引用的源文件。 */
    fun handlePluginAction(pluginAction: PluginManagerAction) {
        when (pluginAction) {
            PluginManagerAction.CHECK, PluginManagerAction.CHECK_ALL -> compileOnly()
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
    fun deleteProjectFiles(files: List<StudioProjectFile>) {
        if (sourceBusy || runtimeBusy) return
        val targets = files.filter {
            it.kind == StudioProjectFileKind.IMAGE || it.kind == StudioProjectFileKind.GLYPH_DICTIONARY
        }
        if (targets.isEmpty()) {
            error = "这里只能删除图片和字库资源"
            return
        }
        sourceBusy = true
        scope.launch {
            var current = snapshot
            val failures = mutableListOf<String>()
            targets.forEach { file ->
                val expected = current.manifest.resources.map { it.get("path").asString }.toSet()
                runCatching {
                    withContext(Dispatchers.IO) { store.deleteResource(projectId, file.path, expected) }
                }.onSuccess { current = it }
                    .onFailure { failures += "${file.path.substringAfterLast('/')}：${it.message ?: "删除失败"}" }
            }
            if (current !== snapshot) onSnapshotChanged(current)
            error = failures.takeIf { it.isNotEmpty() }?.joinToString("\n")
            if (failures.isEmpty()) notice = "已删除 ${targets.size} 个资源"
            sourceBusy = false
        }
    }

    pendingInputInsertion?.let { draft ->
        InputInsertPositionDialog(draft, editor,
            onDismiss = { pendingInputInsertion = null; inputPickTarget = null },
            onConfirm = { position, slot -> confirmInputInsertion(draft, position, slot) })
    }

    pendingDockInsertHint?.let { hint ->
        val selectedRow = nodes.firstOrNull { it.nodeId == editor.selectedNodeId }
        val childSlots = selectedRow?.nodeId?.let(editor::childBlockNames).orEmpty()
        EditorOptionDialog(
            title = "选择插入位置",
            options = listOf<Pair<String?, String>>(null to "当前节点之后") +
                childSlots.map { slot -> slot to "${childBlockLabel(slot)}内新增" },
            onDismiss = { pendingDockInsertHint = null },
            onConfirm = { slot ->
                pendingDockInsertHint = null
                insertDockBlock(hint, slot)
            },
        )
    }

    pendingDockIndentSlots?.let { slots ->
        EditorOptionDialog(
            title = "选择缩进分支",
            options = slots.map { slot -> slot to childBlockLabel(slot) },
            onDismiss = { pendingDockIndentSlots = null },
            onConfirm = { slot ->
                pendingDockIndentSlots = null
                finishDockMutation(editor.indentSelection(slot), "节点已缩进到${childBlockLabel(slot)}")
            },
        )
    }

    if (pendingDockPaste) {
        val selected = nodes.firstOrNull { it.nodeId == editor.selectedNodeId }
        val slots = selected?.nodeId?.let(editor::childBlockNames).orEmpty()
        EditorOptionDialog(
            title = "选择粘贴位置",
            options = listOf<Pair<String?, String>>(null to "当前节点之后") +
                slots.map { slot -> slot to "${childBlockLabel(slot)}内" },
            onDismiss = { pendingDockPaste = false },
            onConfirm = { slot ->
                pendingDockPaste = false
                pasteDockClipboard(slot)
            },
        )
    }

    pendingBlockConfiguration?.let { pending ->
        if (pending.contract.kind == "ui.get") VisualUiParameterDialog(
            pending.arguments, snapshot.manifest.runnerUi, snapshot.manifest.variables, selectedFlowId,
            snapshot.manifest.flows, { showVariableManager = true }, { pendingBlockConfiguration = null },
            onConfirm = confirm@{ configured ->
                if (!editorCanMutate(runtimeBusy, editor.isReadOnly, runtimeState.engineState)) return@confirm "请停止运行后再修改插件"
                if (pending.position == EditorInsertPosition.LIST_BOTTOM) editor.selectedNodeId = editor.rows.lastOrNull { it.depth == 0 }?.nodeId
                val accepted = if (pending.position == EditorInsertPosition.REPLACE) editor.replaceSelectedBlock(pending.contract, configured)
                    else editor.insertBlock(pending.contract, configured, intoChildBlockName = pending.childSlot) != null
                if (!accepted) return@confirm "无法把${pending.contract.title}加入当前位置"
                if (pending.position == EditorInsertPosition.ABOVE) editor.moveSelected(-1)
                editorRevision++; pendingBlockConfiguration = null; error = null
                notice = "已加入：${pending.contract.title}"; saveCurrent()
                null
            }, confirmLabel = if (pending.position == EditorInsertPosition.REPLACE) "保存" else "加入",
        ) else VisualVariableConditionDialog(
            contract = pending.contract,
            inputs = conditionPropertyTexts(pending.contract, pending.arguments),
            knownVariables = visualKnownVariables(editor, snapshot.manifest.variables, selectedFlowId),
            projectVariables = snapshot.manifest.variables,
            currentFlowId = selectedFlowId,
            flows = snapshot.manifest.flows,
            onManageVariables = { showVariableManager = true },
            onDismiss = { pendingBlockConfiguration = null },
            onConfirm = { inputs ->
                val parsed = parseBlockArguments(
                    pending.contract,
                    inputs,
                    null,
                    emptyMap(),
                    snapshot.manifest.resources,
                )
                val configured = parsed.first ?: return@VisualVariableConditionDialog parsed.second ?: "参数无效"
                if (pending.position == EditorInsertPosition.LIST_BOTTOM) {
                    editor.selectedNodeId = editor.rows.lastOrNull { it.depth == 0 }?.nodeId
                }
                val inserted = editor.insertBlock(pending.contract, configured, intoChildBlockName = pending.childSlot)
                if (inserted != null && pending.position == EditorInsertPosition.ABOVE) editor.moveSelected(-1)
                if (inserted == null) return@VisualVariableConditionDialog "无法把${pending.contract.title}加入当前位置"
                editorRevision++
                pendingBlockConfiguration = null
                error = null
                notice = "已加入：${pending.contract.title}"
                saveCurrent()
                null
            },
        )
    }

    debugInspector?.let { mode ->
        VisualDebugInspector(mode, snapshot, selectedFlowId, editor, runtimeClient,
            onDismiss = { debugInspector = null },
            onBackfill = { nodeId, args ->
                if (!editorCanMutate(runtimeBusy, editor.isReadOnly, runtimeState.engineState)) "请停止运行后再回填" else {
                    val original = editor.currentSource
                    val root = snapshot.manifest.flows.first { it.flowId == selectedFlowId }.rootBlockId
                    val candidate = VisualEditorState.create(original, root)
                    if (!candidate.updateArguments(nodeId, args)) "节点已变化" else {
                        when (val validation = withContext(Dispatchers.IO) {
                            runtimeClient.validateVisualDraft(projectId, selectedFlowId, candidate.currentSource.toByteArray(Charsets.UTF_8))
                        }) {
                            is VisualCompileResult.Success -> {
                                if (editor.currentSource != original) "编辑内容已变化，请重新选择" else {
                                    editor.updateArguments(nodeId, args)
                                    editorRevision++
                                    saveCurrent()
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

    if (showRecognitionTest) {
        ImageRecognitionTestDialog(snapshot, runtimeClient,
            onDismiss = { showRecognitionTest = false },
            onCapture = { showRecognitionTest = false; openImageTools(0L) },
            onInsert = { arguments ->
                val blocks = recognitionBlockSequence(arguments)
                val missing = blocks.flatMap { it.first.requiredCapabilities }.toSet() - snapshot.manifest.capabilities.toSet()
                if (missing.isNotEmpty()) error = "找图需要能力：${missing.joinToString()}"
                else if (!runtimeBusy && editor.insertBlocks(blocks)) {
                    showRecognitionTest = false
                    editorRevision++
                    saveCurrent()
                }
            },
        )
    }
    if (showImageLibrary) {
        Dialog(onDismissRequest = { showImageLibrary = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            ImageToolsScreen(snapshot, store, runtimeClient, runtimeState, onSnapshotChanged,
                onBack = { showImageLibrary = false }, modifier = Modifier.fillMaxSize())
        }
    }

    pendingImageBlock?.let { (contract, initialArgs) ->
        VisualImageRecognitionDialog(
            contract = contract,
            arguments = initialArgs,
            resources = snapshot.manifest.resources,
            knownVariables = visualKnownVariables(editor, snapshot.manifest.variables, selectedFlowId),
            snapshot = snapshot,
            confirmLabel = "加入",
            automaticCapture = true,
            allowKindChange = true,
            visible = !showingInputPointPicker,
            captureSelection = recognitionCaptureSelection,
            captureTemplatePath = recognitionCaptureTemplatePath,
            onPickFromScreen = { mode ->
                recognitionCaptureSelection = null
                recognitionCaptureTemplatePath = null
                recognitionCaptureActive = true
                openImageToolMode(mode, 0L)
            },
            onDismiss = { pendingImageBlock = null; recognitionCaptureSelection = null; recognitionCaptureTemplatePath = null },
            onConfirm = { selectedContract, configured ->
                recognitionCaptureSelection = null
                recognitionCaptureTemplatePath = null
                val capture = requireNotNull(BlockCatalog.find("screen.capture"))
                val release = requireNotNull(BlockCatalog.find("screen.release"))
                val frameVariable = configured.get("frameVariable")?.asString ?: "frame"
                val captureArgs = JsonObject().apply { addProperty("resultVariable", frameVariable) }
                val releaseArgs = JsonObject().apply { addProperty("frameVariable", frameVariable) }
                val blocks = if (selectedContract.kind in setOf("vision.findimage", "vision.findgray", "ocr.alphanumeric")) {
                    configured.addProperty("autoCapture", true)
                    listOf(selectedContract to configured)
                } else listOf(capture to captureArgs, selectedContract to configured, release to releaseArgs)
                val missing = blocks.flatMap { it.first.requiredCapabilities }.toSet() - snapshot.manifest.capabilities.toSet()
                if (missing.isNotEmpty()) error = "识别需要能力：${missing.joinToString()}"
                else if (editor.insertBlocks(blocks)) {
                    editorRevision++
                    error = null
                    notice = "已加入：截图 → ${selectedContract.title} → 释放截图"
                    pendingImageBlock = null
                    saveCurrent()
                } else error = "无法把${selectedContract.title}加入当前位置"
            },
        )
    }

    pendingRecognitionInsert?.let { request ->
        VisualImageRecognitionDialog(
            request.contract, request.arguments, snapshot.manifest.resources,
            visualKnownVariables(editor, snapshot.manifest.variables, selectedFlowId), snapshot,
            confirmLabel = if (request.position == EditorInsertPosition.REPLACE) "确定" else "加入",
            visible = !showingInputPointPicker, captureSelection = recognitionCaptureSelection,
            captureTemplatePath = recognitionCaptureTemplatePath,
            onPickFromScreen = { mode ->
                recognitionCaptureSelection = null; recognitionCaptureTemplatePath = null
                recognitionCaptureActive = true; openImageToolMode(mode, 0L)
            },
            onDismiss = { pendingRecognitionInsert = null; recognitionCaptureSelection = null; recognitionCaptureTemplatePath = null },
            onConfirm = { _, configured ->
                if (insertConfiguredRecognition(editor, request, configured)) {
                    editorRevision++
                    pendingRecognitionInsert = null
                    recognitionCaptureSelection = null; recognitionCaptureTemplatePath = null
                    error = null
                    saveCurrent()
                } else error = "无法把${request.contract.title}加入当前位置"
            },
        )
    }

    // 顶栏和悬浮面板共用函数库；可直接加入或查看说明，能力不足仍可阅读但不能加入。
    if (showBlockPicker) {
        fun closePicker() {
            showBlockPicker = false
            blockPickerAutoSave = false
            insertionChildBlockName = null
        }
        FunctionLibraryDialog(
            groups = FunctionCatalog.visualGroups(),
            capabilities = snapshot.manifest.capabilities.toSet(),
            onDismiss = ::closePicker,
            onInsert = { hint ->
                val contract = FunctionCatalog.blockKindOf(hint)?.let(BlockCatalog::find)
                if (contract == null) {
                    error = "未找到对应积木"
                } else {
                    val args = if (contract.kind in visualRecognitionKinds) initialRecognitionArguments(contract, snapshot)
                        else initialBlockArguments(contract, snapshot.manifest.flows, snapshot.manifest.resources, selectedFlowId)
                    if (args == null) {
                        error = "缺少积木所需的目标插件或项目资源"
                    } else if (contract.kind in visualRecognitionKinds) {
                        pendingRecognitionInsert = RecognitionInsertRequest(contract, args, insertionChildBlockName)
                        recognitionCaptureSelection = null; recognitionCaptureTemplatePath = null
                    } else if (contract.kind in setOf("variable.set", "variable.copy", "variable.calculate", "control.if", "ui.get")) {
                        // The library is another insertion entry point: never silently add a
                        // placeholder `value == true` condition from here.
                        pendingBlockConfiguration = VisualPendingBlockInsert(
                            contract = contract,
                            arguments = args,
                            childSlot = insertionChildBlockName,
                            position = EditorInsertPosition.BELOW,
                        )
                    } else {
                        val inserted = editor.insertBlock(contract, args, intoChildBlockName = insertionChildBlockName)
                        if (inserted == null) {
                            error = "无法把${contract.title}加入当前位置"
                        } else {
                            editorRevision++
                            error = null
                            notice = "已加入：${contract.title}"
                            if (blockPickerAutoSave) saveCurrent()
                        }
                    }
                }
                closePicker()
            },
        )
    }

    editingCallNodeId?.let { nodeId ->
        val nodeKind = nodes.firstOrNull { it.nodeId == nodeId }?.kind
        val contract = nodeKind?.let(BlockCatalog::find)
        val arguments = editor.nodeArguments(nodeId)
        val originalArguments = remember(editor, nodeId) { arguments?.deepCopy() }
        if (contract == null || arguments == null) {
            LaunchedEffect(nodeId) { editingCallNodeId = null }
        } else if (contract.kind in setOf("variable.set", "variable.copy", "variable.calculate", "control.if")) {
            VisualVariableConditionDialog(
                contract = contract,
                inputs = propertyInputs,
                knownVariables = visualKnownVariables(editor, snapshot.manifest.variables, selectedFlowId),
                projectVariables = snapshot.manifest.variables,
                currentFlowId = selectedFlowId,
                flows = snapshot.manifest.flows,
                onManageVariables = { showVariableManager = true },
                onDismiss = { editingCallNodeId = null },
                confirmLabel = if (reflectionPosition == null) "保存" else "加入",
                onConfirm = { inputs ->
                    val target = snapshot.manifest.flows.firstOrNull { it.flowId == editingCallTargetId }
                    val parsed = parseBlockArguments(contract, inputs, target, callArgumentInputs, snapshot.manifest.resources)
                    if (parsed.second != null) {
                        parsed.second
                    } else if (editorCanMutate(runtimeBusy, editor.isReadOnly, runtimeState.engineState) && applyReflectedArguments(editor, nodeId, contract, requireNotNull(parsed.first), reflectionPosition,
                            reflectionChildSlot, originalArguments)) {
                        editorRevision++
                        editingCallNodeId = null
                        saveCurrent()
                        null
                    } else "无法保存积木参数"
                },
            )
        } else {
            VisualNodeArgumentsDialog(
                contract = contract,
                arguments = arguments,
                flows = snapshot.manifest.flows,
                currentFlowId = selectedFlowId,
                resources = snapshot.manifest.resources,
                knownLabels = nodes.filter { it.kind == "control.label" && !editor.isNodeDisabled(it.nodeId) }
                    .mapNotNull { editor.nodeArguments(it.nodeId)?.get("name")?.asString },
                confirmLabel = if (reflectionPosition == null) "保存" else "加入",
                knownVariables = visualKnownVariables(editor, snapshot.manifest.variables, selectedFlowId),
                onManageVariables = { showVariableManager = true },
                imageProject = snapshot,
                imageDialogVisible = !showingInputPointPicker,
                imageCaptureSelection = recognitionCaptureSelection,
                imageCaptureTemplatePath = recognitionCaptureTemplatePath,
                onPickImageFromScreen = { mode ->
                    recognitionCaptureSelection = null
                    recognitionCaptureTemplatePath = null
                    recognitionCaptureActive = true
                    openImageToolMode(mode, 0L)
                },
                onDismiss = { editingCallNodeId = null; recognitionCaptureSelection = null; recognitionCaptureTemplatePath = null },
                onConfirm = { configured ->
                    recognitionCaptureSelection = null
                    recognitionCaptureTemplatePath = null
                    if (!editorCanMutate(runtimeBusy, editor.isReadOnly, runtimeState.engineState)) {
                        error = "请停止运行后再修改插件"
                    } else if (reflectionPosition == null && editor.nodeArguments(nodeId) == configured) {
                        editingCallNodeId = null
                        error = null
                    } else if (applyReflectedArguments(editor, nodeId, contract, configured, reflectionPosition,
                            reflectionChildSlot, originalArguments)) {
                        editorRevision++
                        editingCallNodeId = null
                        error = null
                        saveCurrent()
                    } else {
                        error = "无法保存积木参数"
                    }
                },
            )
        }
    }

    BackHandler(enabled = active, onBack = onExit)
    error?.let { message ->
        EditorPromptDialog(title = "编辑提示", text = message, onDismiss = { error = null },
            onConfirm = { error = null }, cancelLabel = "关闭", confirmLabel = "知道了")
    }
    if (showInterfacePreview) ProjectInterfacePreview(snapshot.manifest.runnerUi, snapshot.directory) { showInterfacePreview = false }

    Box(modifier.fillMaxSize()) {
        val projectFiles = remember(snapshot) { projectFileCatalog(snapshot) }
        EditorDock(
            projectName = snapshot.manifest.name,
            initiallyExpanded = true,
            onClose = onExit,
            onRun = { runProject() },
            running = running,
            paused = runtimeState.engineState == RuntimeEngineState.PAUSED,
            resumeEnabled = canPauseResume && !stepState.pending && !stepState.loading,
            onPause = { controlRunningProject(pause = true) },
            onResume = {
                if (stepState.position == null) controlRunningProject(pause = false)
                else if (stepState.begin(continueRun = true)) scope.launch {
                    if (!withContext(Dispatchers.IO) { runtimeClient.resumeDebugProject(projectId, runtimeState.sessionGeneration) }) {
                        stepState.rejected(); error = "无法继续：当前项目的会话或暂停状态已变化"
                    }
                }
            },
            runEnabled = if (running) canStop else canStartAction,
            onStop = ::stopProject,
            consoleLines = consoleLines,
            sourceName = snapshot.manifest.flows.firstOrNull { it.flowId == selectedFlowId }?.displayName() ?: selectedFlowId,
            sourceTree = sourceTree,
            currentFlowId = selectedFlowId,
            sourceBusy = sourceBusy,
            sourceMessage = sourceMessage,
            onSourceAction = ::handleSourceAction,
            onManageVariables = { showVariableManager = true },
            onOpenSettings = {
                if (canEditProjectSettings) showDockProjectSettings = true
                else notice = "请先保存插件并停止运行，再打开项目设置"
            },
            availableVariables = visualKnownVariables(editor, snapshot.manifest.variables, selectedFlowId),
            availableLabels = editor.rows.filter { it.kind == "control.label" && it.depth == 0 }
                .mapNotNull { row -> editor.nodeArguments(row.nodeId)?.get("name")?.asString }.distinct(),
            projectVariables = snapshot.manifest.variables,
            projectFlows = snapshot.manifest.flows,
            debugSettings = snapshot.manifest.debugSettings,
            onSaveDebugSettings = { settings ->
                scope.launch {
                    runCatching {
                        withContext(Dispatchers.IO) {
                            store.updateDebugSettings(
                                projectId = projectId,
                                debugSettings = settings,
                                expected = snapshot.manifest.debugSettings,
                            )
                        }
                    }.onSuccess { updated ->
                        onSnapshotChanged(updated)
                        notice = "调试设置已保存"
                    }.onFailure { failure ->
                        error = failure.message ?: "保存调试设置失败"
                    }
                }
            },
            projectFiles = projectFiles,
            onOpenProjectFile = { file ->
                when (file.kind) {
                    StudioProjectFileKind.FLOW -> snapshot.manifest.flows.firstOrNull { it.path == file.path }
                        ?.let { selectedFlowId = it.flowId }
                    StudioProjectFileKind.LUA -> notice = "可视化项目没有 main.lua"
                    StudioProjectFileKind.MANIFEST,
                    StudioProjectFileKind.IMAGE,
                    StudioProjectFileKind.GLYPH_DICTIONARY,
                    -> notice = "图片、字库和 project.json 请通过顶部“项目设置”管理"
                }
            },
            onDeleteProjectFiles = ::deleteProjectFiles,
            onPluginAction = ::handlePluginAction,
            capabilities = snapshot.manifest.capabilities.toSet(),
            onOpenInputPointPicker = { mode -> openInputPointPicker(mode) },
            onOpenInputFloatingPicker = ::openInputFloatingPicker,
            inputPickOpenRequest = inputPickOpenRequest,
            inputBackendFeatures = inputBackendFeatures,
            onOpenImageTools = ::openImageTools,
            onOpenImageLibrary = { showImageLibrary = true },
            onTestRecognition = { showRecognitionTest = true },
            onOpenDebugTool = { debugInspector = it },
            onOpenImageToolMode = ::openImageToolMode,
            onCreateImageBlock = { kind ->
                val contract = BlockCatalog.find(kind)
                val capture = BlockCatalog.find("screen.capture")
                val release = BlockCatalog.find("screen.release")
                val missing = listOfNotNull(contract, capture, release).flatMap { it.requiredCapabilities }
                    .toSet() - snapshot.manifest.capabilities.toSet()
                when {
                    contract == null || capture == null || release == null -> error = "缺少图像积木契约：$kind"
                    missing.isNotEmpty() -> error = "${contract.title}需要能力：${missing.joinToString()}"
                    else -> {
                        val args = initialRecognitionArguments(contract, snapshot)
                        run {
                            pickedImageRegion?.takeIf { args.has("region") }?.let { roi ->
                                args.add("region", JsonObject().apply {
                                    addProperty("left", roi.left); addProperty("top", roi.top)
                                    addProperty("right", roi.right); addProperty("bottom", roi.bottom)
                                })
                            }
                            pendingImageBlock = contract to args
                            recognitionCaptureSelection = null
                            recognitionCaptureTemplatePath = null
                        }
                    }
                }
            },
            programNodes = nodes.map { node ->
                val contract = BlockCatalog.find(node.kind)
                EditorProgramNode(
                    nodeId = node.nodeId,
                    label = buildString {
                        node.childSlot?.let { append('[').append(childBlockLabel(it)).append("] ") }
                        append(legacyDockProgramLabel(contract, editor.nodeArguments(node.nodeId), node.kind))
                    },
                    kind = node.kind,
                    depth = node.depth,
                    childSlots = editor.childBlockNames(node.nodeId),
                    disabled = editor.isNodeDisabled(node.nodeId, inherited = false),
                )
            },
            programSelectedNodeId = editor.selectedNodeId,
            programCurrentNodeId = stepState.position?.takeIf { it.flowId == selectedFlowId }?.nodeId,
            executionStatus = when {
                error != null -> error
                stepState.pending -> stepState.pendingMessage
                runtimeState.engineState == RuntimeEngineState.PAUSED && stepState.loading -> "已暂停 · 正在读取执行位置…"
                runtimeState.engineState == RuntimeEngineState.PAUSED && stepState.position != null -> "已暂停 · 黄色 ▶ 为下一步，点击单步执行；点运行继续"
                editor.isReadOnly -> "插件结构无效，只读查看"
                else -> notice ?: runtimeState.message
            },
            programSelectedNodeIds = editor.selectedNodeIds,
            onProgramNodeSelectionToggled = { nodeId -> editor.toggleSelection(nodeId); editorRevision++ },
            editingEnabled = editorCanMutate(runtimeBusy, editor.isReadOnly, runtimeState.engineState),
            programSource = editor.currentSource,
            onReplaceProgramText = { query, replacement, selectedOnly ->
                if (!editorCanMutate(runtimeBusy, editor.isReadOnly, runtimeState.engineState)) "请停止运行后再替换"
                else {
                    val count = editor.replaceDisplayText(query, replacement, selectedOnly)
                    if (count == 0) "没有可替换的文字，或替换结果超过长度限制"
                    else { finishDockMutation(true, "已替换 $count 个积木的文字"); null }
                }
            },
            onShowInterface = { showInterfacePreview = true },
            stepEnabled = !stepState.pending && !stepState.loading &&
                (runtimeState.engineState != RuntimeEngineState.PAUSED || stepState.position != null) &&
                editorCanStep(runtimeBusy, runtimeState.phase == RuntimeConnectionPhase.CONNECTED, runtimeState.engineState,
                    editor.selectedNodeId?.let { !editor.isNodeDisabled(it) } == true),
            onProgramNodeSelected = { nodeId ->
                editor.selectedNodeId = nodeId
                editorRevision++
            },
            onProgramNodeDeleted = {
                if (editorCanMutate(runtimeBusy, editor.isReadOnly, runtimeState.engineState) && editor.deleteSelection()) {
                    editorRevision++
                    error = null
                    notice = "已删除选中节点及其子树"
                    saveCurrent()
                } else error = "无法删除当前节点"
            },
            onProgramNodeEdited = {
                if (editorCanMutate(runtimeBusy, editor.isReadOnly, runtimeState.engineState)) {
                reflectionPosition = null
                reflectionChildSlot = null
                val selectedId = editor.selectedNodeId
                val selectedRow = nodes.firstOrNull { it.nodeId == selectedId }
                val contract = selectedRow?.kind?.let(BlockCatalog::find)
                if (selectedId != null && contract != null && contract.properties.isNotEmpty()) {
                    val args = editor.nodeArguments(selectedId) ?: JsonObject()
                    val targets = snapshot.manifest.flows.filter { it.flowId != selectedFlowId }
                    val currentTarget = args.get("targetFlowId")?.takeIf { it.isJsonPrimitive }?.asString
                    val target = targets.firstOrNull { it.flowId == currentTarget } ?: targets.firstOrNull()
                    editingCallNodeId = selectedId
                    editingCallTargetId = target?.flowId
                    propertyInputs = conditionPropertyTexts(contract, args)
                    callArgumentInputs = target?.let {
                        flowCallArgumentTexts(it, args.getAsJsonObject("arguments"))
                    }.orEmpty()
                    callArgumentError = null
                } else notice = "当前节点没有可修改的参数"
                }
            },
            onProgramNodeReflected = { selectedId, position, slot ->
                val selectedContract = nodes.firstOrNull { it.nodeId == selectedId }?.kind?.let(BlockCatalog::find)
                if (editorCanMutate(runtimeBusy, editor.isReadOnly, runtimeState.engineState) && selectedContract != null) {
                    reflectionPosition = position.takeUnless { it == EditorInsertPosition.REPLACE }
                    reflectionChildSlot = slot
                    val args = requireNotNull(editor.nodeArguments(selectedId))
                    editingCallTargetId = args.get("targetFlowId")?.takeIf { it.isJsonPrimitive }?.asString
                    callArgumentInputs = snapshot.manifest.flows.firstOrNull { it.flowId == editingCallTargetId }
                        ?.let { flowCallArgumentTexts(it, args.getAsJsonObject("arguments")) }.orEmpty()
                    propertyInputs = conditionPropertyTexts(selectedContract, requireNotNull(editor.nodeArguments(selectedId)))
                    editingCallNodeId = selectedId
                }
            },
            onProgramNodeAnnotated = {
                val contract = BlockCatalog.find("task.comment")
                val selectedId = editor.selectedNodeId
                if (contract == null || selectedId == null) {
                    error = "请先选择要注释的节点"
                } else {
                    pendingBlockConfiguration = VisualPendingBlockInsert(
                        contract = contract,
                        arguments = requireNotNull(initialBlockArguments(
                            contract, snapshot.manifest.flows, snapshot.manifest.resources, selectedFlowId,
                        )),
                        childSlot = null,
                        position = EditorInsertPosition.BELOW,
                    )
                }
            },
            onProgramCommand = ::runDockCommand,
            canUndoProgram = editor.canUndo,
            canRedoProgram = editor.canRedo,
            hasProgramClipboard = dockClipboard != null,
            onStep = {
                if (runtimeState.engineState == RuntimeEngineState.PAUSED) {
                    if (stepState.begin()) scope.launch {
                        if (!withContext(Dispatchers.IO) { runtimeClient.requestStep(projectId, runtimeState.sessionGeneration) }) {
                            stepState.rejected(); error = "无法单步：当前项目的会话或暂停状态已变化"
                        }
                    }
                } else if (editorCanStep(runtimeBusy, runtimeState.phase == RuntimeConnectionPhase.CONNECTED, runtimeState.engineState,
                        editor.selectedNodeId?.let { !editor.isNodeDisabled(it) } == true)) runProject(singleStep = true)
                else notice = "请先暂停或停止脚本，再执行单步"
            },
            onInsertPositioned = { legacyHint, position ->
                val selectedRow = nodes.firstOrNull { it.nodeId == editor.selectedNodeId }
                val childSlots = selectedRow?.nodeId?.let(editor::childBlockNames).orEmpty()
                when {
                    position == EditorInsertPosition.INSIDE && childSlots.isEmpty() -> error = "当前选择行不能加入内部"
                    position == EditorInsertPosition.INSIDE -> insertDockBlock(legacyHint, childSlots.first(), position)
                    else -> insertDockBlock(legacyHint, null, position)
                }
            },
        onInsert = { legacyHint ->
                val selectedRow = nodes.firstOrNull { it.nodeId == editor.selectedNodeId }
                val childSlots = selectedRow?.nodeId?.let(editor::childBlockNames).orEmpty()
                when {
                    selectedRow == null || childSlots.isEmpty() -> {
                        insertDockBlock(legacyHint, null)
                    }
                    else -> pendingDockInsertHint = legacyHint
                }
                notice = "请选择要加入的积木和插入位置"
            },
        )
        if (showingInputPointPicker) {
            ImageToolWindow(
                pointerInputSupported = inputBackendFeatures and 2 != 0,
                bitmap = inputPointBitmap,
                designWidth = snapshot.manifest.design.width,
                designHeight = snapshot.manifest.design.height,
                scaleMode = snapshot.manifest.design.scaleMode,
                initialMode = inputPointMode,
                capturing = inputPointCapturing,
                message = inputPointMessage,
                onCapture = { captureInputPointScreenshot() },
                onEmit = ::emitInputPointSnippet,
                onCropToTemplate = { roi ->
                    if (!imageToolRequest || inputPointBitmap == null) inputPointMessage = "请先从图像入口截图"
                    else pendingTemplateRoi = roi
                },
                onClose = {
                    showingInputPointPicker = false
                    recognitionCaptureActive = false
                    pendingTemplateRoi = null
                    inputPointRequestId++
                    inputPointCaptureJob?.cancel()
                    inputPointCaptureJob = null
                    inputPointCapturing = false
                    inputPointBitmap?.recycle()
                    inputPointBitmap = null
                },
                modifier = Modifier.zIndex(2f),
            )
        }
        pendingTemplateRoi?.let { roi ->
            ImageTemplateSaveDialog(
                imagePaths = snapshot.manifest.resources.mapNotNull { it.get("path")?.asString },
                onDismiss = { pendingTemplateRoi = null },
                onSave = { folder, name ->
                    pendingTemplateRoi = null
                    val source = inputPointBitmap
                    if (!imageToolRequest || source == null) {
                        inputPointMessage = "请先从图像入口截图"
                    } else {
                        val sourceCopy = runCatching {
                            source.copy(source.config ?: Bitmap.Config.ARGB_8888, false)
                        }.getOrNull()
                        if (sourceCopy == null) {
                            inputPointMessage = "无法准备模板图片"
                        } else {
                            inputPointMessage = "正在保存模板…"
                            scope.launch {
                                try {
                                    val (updated, path) = withContext(Dispatchers.IO) {
                                        try { cropAndImportTemplate(store, snapshot, sourceCopy, roi, context, folder, name) }
                                        finally { sourceCopy.recycle() }
                                    }
                                    onSnapshotChanged(updated)
                                    inputPointMessage = "模板已保存：$path"
                                    notice = inputPointMessage
                                    if (recognitionCaptureActive) {
                                        recognitionCaptureTemplatePath = path
                                        recognitionCaptureActive = false
                                        showingInputPointPicker = false
                                        inputPointRequestId++
                                        inputPointCaptureJob?.cancel()
                                        inputPointCaptureJob = null
                                        inputPointBitmap?.recycle()
                                        inputPointBitmap = null
                                    }
                                } catch (failure: Exception) {
                                    inputPointMessage = failure.message ?: "保存模板失败"
                                }
                            }
                        }
                    }
                },
            )
        }
    }
}

internal data class VisualNodeRow(
    val nodeId: String,
    val blockId: String,
    val orderKey: String,
    val kind: String,
    val depth: Int,
    val childSlot: String?,
    val sourceLine: Int,
)

internal fun parseVisualNodes(source: String, rootBlockId: String): List<VisualNodeRow> {
    val drafts = source.lineSequence().filter(String::isNotBlank).mapIndexed { index, line ->
        val json = runCatching { JsonParser.parseString(line).asJsonObject }.getOrElse {
            return@mapIndexed VisualNodeDraft(
                nodeId = "invalid-line-${index + 1}",
                blockId = "?",
                orderKey = "?",
                kind = "损坏的JSON行 ${index + 1}",
                childBlocks = emptyList(),
                sourceLine = index + 1,
            )
        }
        val childBlocks = runCatching {
            json.getAsJsonObject("childBlocks")?.entrySet().orEmpty()
                .mapNotNull { (name, value) ->
                    value.takeIf { it.isJsonPrimitive }?.asString?.let { name to it }
                }
                .sortedBy { it.first }
        }.getOrDefault(emptyList())
        VisualNodeDraft(
            nodeId = json.stringOr("nodeId", "missing-node-${index + 1}"),
            blockId = json.stringOr("blockId", "?"),
            orderKey = json.stringOr("orderKey", "?"),
            kind = json.stringOr("kind", "未知节点"),
            childBlocks = childBlocks,
            sourceLine = index + 1,
        )
    }.toList()
    val members = drafts.withIndex().groupBy { it.value.blockId }
    val childSlots = drafts.flatMap { owner ->
        owner.childBlocks.map { (name, blockId) -> blockId to name }
    }.toMap()
    val visited = mutableSetOf<Int>()
    val projected = mutableListOf<VisualNodeRow>()
    fun visitBlock(blockId: String, depth: Int) {
        members[blockId].orEmpty().sortedBy { it.value.orderKey }.forEach { indexed ->
            if (!visited.add(indexed.index)) return@forEach
            val node = indexed.value
            projected += node.toRow(depth, childSlots[node.blockId])
            node.childBlocks.forEach { (_, childBlockId) ->
                visitBlock(childBlockId, depth + 1)
            }
        }
    }
    visitBlock(rootBlockId, 0)
    drafts.forEachIndexed { index, node ->
        if (visited.add(index)) projected += node.toRow(0, childSlots[node.blockId])
    }
    return projected
}

private data class VisualNodeDraft(
    val nodeId: String,
    val blockId: String,
    val orderKey: String,
    val kind: String,
    val childBlocks: List<Pair<String, String>>,
    val sourceLine: Int,
)

private fun VisualNodeDraft.toRow(depth: Int, childSlot: String?) = VisualNodeRow(
    nodeId = nodeId,
    blockId = blockId,
    orderKey = orderKey,
    kind = kind,
    depth = depth,
    childSlot = childSlot,
    sourceLine = sourceLine,
)

internal fun childBlockLabel(name: String): String = when (name) {
    "then" -> "满足"
    "else" -> "否则"
    "body" -> "循环体"
    else -> if (name.startsWith("elseIf")) {
        "否则如果 ${name.removePrefix("elseIf").toIntOrNull()?.plus(1) ?: ""}"
    } else name
}

internal fun legacyDockBlockQuery(snippet: String): String {
    val line = snippet.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
    return when {
        line.startsWith(FunctionCatalog.LOOP_HINT_PREFIX + "repeat:") -> "重复次数"
        line.startsWith(FunctionCatalog.LOOP_HINT_PREFIX) -> "条件循环"
        line.contains("Onnx", ignoreCase = true) -> "ONNX OCR"
        line.contains("findImage", ignoreCase = true) -> "区域找图"
        line.contains("findColor", ignoreCase = true) -> "区域找色"
        line.contains("findText", ignoreCase = true) -> "字库识字"
        line.startsWith("if ") -> "如果"
        line.startsWith("while ") || line.startsWith("repeat") -> "条件循环"
        line.startsWith("for ") -> "重复次数"
        line == "break" -> "跳出循环"
        line == "return" -> "返回上层"
        line.startsWith("::") && line.endsWith("::") -> "放置标记"
        line.startsWith("goto ") -> "跳转标记"
        line.contains("Input.tap", ignoreCase = true) -> "点击"
        line.contains("Input.swipe", ignoreCase = true) -> "滑动"
        line.contains("sleep", ignoreCase = true) -> "等待"
        // 运行提示、短时弹窗和开发日志分开落成对应的积木。
        line.contains("Prompt.show", ignoreCase = true) -> "运行提示"
        line.contains("Prompt.toast", ignoreCase = true) -> "弹出提示"
        line.contains("Log.", ignoreCase = true) -> "输出日志"
        line.startsWith("--") -> "注释"
        line.contains("Capture.", ignoreCase = true) -> "截图"
        line.contains("Runtime.setParameter", ignoreCase = true) -> "设置变量"
        line.contains("Runtime.getParameter", ignoreCase = true) -> "读取变量"
        else -> line.substringBefore('(').substringBefore('\n').trim()
    }
}

/** Shared pre-insert checks for the full and floating visual editors. */
internal fun visualJumpInsertionError(
    kind: String,
    arguments: JsonObject,
    editor: VisualEditorState,
    childSlot: String?,
    position: EditorInsertPosition,
): String? {
    val selected = editor.rows.firstOrNull { it.nodeId == editor.selectedNodeId }
    if (kind == "control.break") {
        val insideLoop = childSlot == "body" && selected?.kind in setOf("control.repeat", "control.while") ||
            selected?.depth?.let { it > 0 } == true &&
            editor.nearestAncestorOfKind(kinds = setOf("control.repeat", "control.while")) != null
        if (position == EditorInsertPosition.LIST_BOTTOM || !insideLoop) return "跳出循环只能加入循环体"
    }
    if (kind !in setOf("control.label", "control.goto")) return null
    if (childSlot != null || (position != EditorInsertPosition.LIST_BOTTOM && selected?.depth?.let { it > 0 } == true)) {
        return "标记和跳转只能加入插件的根层级"
    }
    val name = arguments.get("name")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
    if (!JumpPanelCode.validName(name)) return "标记名无效"
    val matchingLabels = editor.rows.filter { row ->
        row.kind == "control.label" && editor.nodeArguments(row.nodeId)?.get("name")?.asString == name &&
            !(position == EditorInsertPosition.REPLACE && row.nodeId == editor.selectedNodeId)
    }
    if (kind == "control.label" && matchingLabels.isNotEmpty()) return "当前插件已有同名标记：$name"
    if (kind == "control.goto" && matchingLabels.isEmpty()) return "请先在当前插件放置标记：$name"
    return null
}

internal fun legacyDockProgramLabel(
    contract: BlockContract?,
    arguments: JsonObject?,
    fallbackKind: String,
): String {
    if (contract == null) return fallbackKind
    val details = contract.properties.asSequence().mapNotNull { property ->
        val value = arguments?.get(property.path)?.takeIf { it.isJsonPrimitive } ?: return@mapNotNull null
        val text = runCatching { value.asString }.getOrNull()?.take(24) ?: return@mapNotNull null
        "${property.label}=$text"
    }.take(3).toList()
    return if (details.isEmpty()) contract.title else "${contract.title} · ${details.joinToString(" · ")}"
}

/**
 * Migrates the compact legacy editor's Lua-shaped command into the typed
 * arguments owned by the Flow node. Unknown or symbolic values deliberately
 * keep the catalog defaults so a dock action can never create an invalid node.
 */
internal fun legacyDockBlockArguments(
    contract: BlockContract,
    snippet: String,
    defaults: JsonObject,
): JsonObject = defaults.deepCopy().apply {
    if (contract.kind in setOf("task.runprompt", "task.prompt", "task.log", "task.comment",
            "flow.call", "flow.argument.set", "flow.argument.get", "flow.return.set", "flow.return.get") &&
        snippet.startsWith(FunctionCatalog.BLOCK_HINT_PREFIX + contract.kind + "\n")) {
        val encoded = snippet.lineSequence().drop(1).firstOrNull().orEmpty()
        val payload = runCatching { com.google.gson.JsonParser.parseString(encoded).asJsonObject }.getOrNull()
        if (payload != null) {
            if (contract.kind in setOf("flow.argument.set", "flow.return.set")) {
                if (payload.has("valueVariable")) remove("value") else remove("valueVariable")
            }
            payload.entrySet().forEach { (key, value) -> add(key, value.deepCopy()) }
            return@apply
        }
    }
    loopInsertionFromHint(snippet)?.takeIf { it.kind == contract.kind }?.let { insertion ->
        keySet().toList().forEach(::remove)
        insertion.arguments.entrySet().forEach { (key, value) -> add(key, value.deepCopy()) }
        return@apply
    }
    val callArguments = legacyLuaCallArguments(snippet)
    fun integer(index: Int): Long? = callArguments.getOrNull(index)?.trim()?.toLongOrNull()
    fun decimal(index: Int): Double? = callArguments.getOrNull(index)?.trim()?.toDoubleOrNull()
        ?.takeIf(Double::isFinite)
    fun quoted(index: Int): String? = callArguments.getOrNull(index)?.trim()?.let(::legacyLuaString)

    when (contract.kind) {
        "variable.calculate" -> variableCalculationArguments(snippet)?.let {
            addProperty("name", it["name"].asString)
            addProperty("expression", it["expression"].asString)
        }
        "flow.argument.set", "flow.return.set" -> {
            val parts = snippet.lineSequence().drop(1).firstOrNull().orEmpty().split('|', limit = 2)
            parts.getOrNull(0)?.toIntOrNull()?.let { addProperty("index", it) }
            val source = parts.getOrNull(1).orEmpty()
            if (source.startsWith("variable:")) {
                remove("value")
                addProperty("valueVariable", source.removePrefix("variable:"))
            } else if (source.startsWith("value:")) {
                remove("valueVariable")
                add("value", parseScalar(source.removePrefix("value:"))
                    ?: com.google.gson.JsonPrimitive(source.removePrefix("value:")))
            }
        }
        "flow.argument.get", "flow.return.get" -> {
            val parts = snippet.lineSequence().drop(1).firstOrNull().orEmpty().split('|', limit = 2)
            parts.getOrNull(0)?.toIntOrNull()?.let { addProperty("index", it) }
            parts.getOrNull(1)?.let { addProperty("targetVariable", it) }
        }
        "input.tap" -> {
            integer(0)?.let { addProperty("x", it) }
            integer(1)?.let { addProperty("y", it) }
        }
        "input.swipe" -> {
            integer(0)?.let { addProperty("x1", it) }
            integer(1)?.let { addProperty("y1", it) }
            integer(2)?.let { addProperty("x2", it) }
            integer(3)?.let { addProperty("y2", it) }
            integer(4)?.takeIf { it in 1..5_000 }?.let { addProperty("durationMs", it) }
        }
        "task.sleep" -> {
            val marker = snippet.lineSequence().drop(1).firstOrNull()?.trim().orEmpty()
            if (marker.startsWith("variable:")) {
                addProperty("milliseconds", 0)
                addProperty("millisecondsVariable", marker.removePrefix("variable:"))
            } else {
                remove("millisecondsVariable")
                integer(0)?.takeIf { it >= 0 }?.let { addProperty("milliseconds", it) }
            }
        }
        "control.label" -> snippet.trim().removeSurrounding("::")
            .takeIf { it.matches(Regex("[A-Za-z_][A-Za-z0-9_]{0,63}")) }
            ?.let { addProperty("name", it) }
        "control.goto" -> snippet.trim().removePrefix("goto ")
            .takeIf { it.matches(Regex("[A-Za-z_][A-Za-z0-9_]{0,63}")) }
            ?.let { addProperty("name", it) }
        "task.log" -> {
            Regex("Log\\.(info|warn|error)", RegexOption.IGNORE_CASE)
                .find(snippet)?.groupValues?.getOrNull(1)?.lowercase()?.let { addProperty("level", it) }
            val variable = Regex("__vars\\s*\\[\\s*[\\\"']([A-Za-z_][A-Za-z0-9_]*)[\\\"']\\s*]")
                .find(snippet)?.groupValues?.getOrNull(1)
            if (variable != null) {
                addProperty("valueVariable", variable)
                addProperty("message", variable)
            } else {
                quoted(0)?.let { addProperty("message", it) }
            }
        }
        "task.prompt", "task.runprompt" -> {
            val variable = Regex("__vars\\s*\\[\\s*[\\\"']([A-Za-z_][A-Za-z0-9_]*)[\\\"']\\s*]")
                .find(snippet)?.groupValues?.getOrNull(1)
            if (variable != null) {
                addProperty("valueVariable", variable)
                addProperty("message", variable)
            } else {
                quoted(0)?.let { addProperty("message", it) }
            }
        }
        "task.comment" -> addProperty("message", snippet.trim().removePrefix("--").trim().take(2048))
        "control.repeat" -> {
            val marker = snippet.lineSequence().firstOrNull()?.trim().orEmpty()
            if (marker.startsWith(FunctionCatalog.LOOP_HINT_PREFIX + "repeat:")) {
                val value = marker.removePrefix(FunctionCatalog.LOOP_HINT_PREFIX + "repeat:")
                when {
                    value.startsWith("fixed:") -> value.removePrefix("fixed:").toLongOrNull()
                        ?.takeIf { it in 0..1_000_000 }?.let { addProperty("times", it) }
                    value.startsWith("variable:") -> value.removePrefix("variable:")
                        .takeIf { it.matches(Regex("[A-Za-z_][A-Za-z0-9_]{0,63}")) }
                        ?.let { addProperty("times", 0); addProperty("timesVariable", it) }
                }
                return@apply
            }
            Regex("for\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*1\\s*,\\s*(\\d+)", RegexOption.IGNORE_CASE)
                .find(snippet)?.let { match ->
                    match.groupValues[2].toLongOrNull()?.takeIf { it > 0 }?.let { addProperty("times", it) }
                    addProperty("indexVariable", match.groupValues[1])
                }
        }
        "control.if", "control.while" -> {
            val marker = snippet.lineSequence().firstOrNull()?.trim().orEmpty()
            if (contract.kind == "control.while" && marker.startsWith(FunctionCatalog.LOOP_HINT_PREFIX)) {
                addProperty("always", true)
                addProperty("variable", "loopEnabled")
                addProperty("operator", "equals")
                addProperty("value", true)
                when {
                    marker.startsWith(FunctionCatalog.LOOP_HINT_PREFIX + "timed:fixed:") -> marker
                        .substringAfterLast(':').toLongOrNull()?.takeIf { it in 1..86_400_000 }
                        ?.let { addProperty("durationMs", it) }
                    marker.startsWith(FunctionCatalog.LOOP_HINT_PREFIX + "timed:variable:") -> marker
                        .substringAfterLast(':').takeIf { it.matches(Regex("[A-Za-z_][A-Za-z0-9_]{0,63}")) }
                        ?.let { addProperty("durationVariable", it) }
                }
                return@apply
            }
            Regex("(?:if|while)\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*(==|~=|!=|<=|>=|<|>)\\s*(.+?)\\s+(?:then|do)", RegexOption.IGNORE_CASE)
                .find(snippet)?.let { match ->
                    addProperty("variable", match.groupValues[1])
                    addProperty("operator", legacyComparisonOperator(match.groupValues[2]))
                    add("value", parseScalar(match.groupValues[3].trim())
                        ?: com.google.gson.JsonPrimitive(match.groupValues[3].trim()))
                }
            Regex("--\\s*maxIterations=(\\d+)").find(snippet)?.groupValues?.get(1)
                ?.toLongOrNull()?.takeIf { it > 0 }?.let { addProperty("maxIterations", it) }
        }
        "vision.findimage" -> {
            decimal(1)?.takeIf { it in 0.0..1.0 }
                ?.let { addProperty("similarityPermille", (it * 1_000).toInt()) }
            // Only replace a catalog-selected resource when the command names
            // that same project asset. Arbitrary picker examples must not leak
            // invalid paths into the persisted Flow.
            quoted(0)?.substringAfterLast('/')?.let { requestedName ->
                get("imagePath")?.asString?.takeIf { it.substringAfterLast('/') == requestedName }
                    ?.let { addProperty("imagePath", it) }
            }
        }
        "vision.findcolor" -> quoted(0)?.let(::parseColor)?.let { addProperty("rgb", it) }
        "ocr.glyph" -> {
            decimal(1)?.takeIf { it in 0.0..1.0 }
                ?.let { addProperty("similarityPermille", (it * 1_000).toInt()) }
        }
    }
}

private fun legacyLuaCallArguments(snippet: String): List<String> {
    val body = snippet.substringAfter('(', "").substringBeforeLast(')', "")
    if (body.isBlank()) return emptyList()
    val result = mutableListOf<String>()
    val current = StringBuilder()
    var quote: Char? = null
    var escaped = false
    body.forEach { character ->
        when {
            escaped -> { current.append(character); escaped = false }
            character == '\\' && quote != null -> { current.append(character); escaped = true }
            quote != null && character == quote -> { current.append(character); quote = null }
            quote != null -> current.append(character)
            character == '\'' || character == '"' -> { current.append(character); quote = character }
            character == ',' -> { result += current.toString().trim(); current.clear() }
            else -> current.append(character)
        }
    }
    result += current.toString().trim()
    return result
}

private fun legacyLuaString(value: String): String? {
    if (value.length < 2 || value.first() !in charArrayOf('\'', '"') || value.last() != value.first()) return null
    return value.substring(1, value.lastIndex)
        .replace("\\${value.first()}", value.first().toString())
        .replace("\\\\", "\\")
}

private fun legacyComparisonOperator(value: String): String = when (value) {
    "==" -> "equals"
    "!=", "~=" -> "notEquals"
    "<" -> "lessThan"
    "<=" -> "lessOrEqual"
    ">" -> "greaterThan"
    ">=" -> "greaterOrEqual"
    else -> "equals"
}

private fun com.google.gson.JsonObject.stringOr(name: String, fallback: String): String =
    get(name)?.takeIf { it.isJsonPrimitive }?.asString ?: fallback

private fun formatDiagnostic(diagnostic: VisualCompileDiagnostic): String = buildString {
    append(diagnostic.message)
    val location = listOfNotNull(
        diagnostic.flowId?.let { "Flow $it" },
        diagnostic.nodeId?.let { "节点 $it" },
        diagnostic.line?.let { "第 $it 行" },
    )
    if (location.isNotEmpty()) append(" · ").append(location.joinToString(" · "))
}

private enum class VisualAction {
    SAVING,
    COMPILING,
    STARTING,
    STOPPING,
    CONTROLLING,
}

private data class VisualPendingBlockInsert(
    val contract: BlockContract,
    val arguments: JsonObject,
    val childSlot: String?,
    val position: EditorInsertPosition,
)

internal fun defaultFlowCallArguments(flow: ProjectFlow): JsonObject = JsonObject().apply {
    flow.params.forEach { parameter ->
        if (parameter.get("required")?.asBoolean != true) return@forEach
        val name = parameter.get("name")?.asString ?: return@forEach
        when (parameter.get("type")?.asString) {
            "boolean" -> addProperty(name, false)
            "integer" -> addProperty(name, 0)
            "number" -> addProperty(name, 0.0)
            "string" -> addProperty(name, "")
        }
    }
}

internal fun initialBlockArguments(
    contract: BlockContract,
    flows: List<ProjectFlow>,
    resources: List<JsonObject>,
    currentFlowId: String,
    allowMissingResources: Boolean = false,
    preferredFlowId: String? = null,
): JsonObject? {
    val target = if (preferredFlowId == null) flows.firstOrNull { it.flowId != currentFlowId }
        else flows.firstOrNull { it.flowId == preferredFlowId && it.flowId != currentFlowId }
    val arguments = JsonObject()
    contract.properties.forEach { property ->
        if (!property.required) return@forEach
        when (property.editor) {
            BlockPropertyEditor.BOOLEAN -> arguments.addProperty(
                property.path,
                property.defaultValue?.toBooleanStrictOrNull() ?: false,
            )
            BlockPropertyEditor.INTEGER -> arguments.addProperty(
                property.path,
                property.defaultValue?.toLongOrNull() ?: 0,
            )
            BlockPropertyEditor.INTEGER_ENUM -> arguments.addProperty(
                property.path,
                property.defaultValue
                    ?.takeIf { it in property.choices }
                    ?.toLongOrNull() ?: property.choices.firstOrNull()?.toLongOrNull() ?: return null,
            )
            BlockPropertyEditor.NUMBER -> arguments.addProperty(
                property.path,
                property.defaultValue?.toDoubleOrNull()?.takeIf(Double::isFinite) ?: 0.0,
            )
            BlockPropertyEditor.STRING -> {
                arguments.addProperty(property.path, property.defaultValue.orEmpty())
            }
            BlockPropertyEditor.RESOURCE -> arguments.addProperty(
                property.path,
                resourcePaths(resources, property.resourceKind).firstOrNull() ?: if (allowMissingResources) "" else return null,
            )
            BlockPropertyEditor.SCALAR -> arguments.add(
                property.path,
                parseScalar(property.defaultValue ?: "null") ?: return null,
            )
            BlockPropertyEditor.POINT -> arguments.add(
                property.path,
                parsePoint(property.defaultValue ?: "0,0") ?: return null,
            )
            BlockPropertyEditor.RECT -> arguments.add(
                property.path,
                parseRect(property.defaultValue ?: "0,0,1,1") ?: return null,
            )
            BlockPropertyEditor.COLOR -> arguments.addProperty(
                property.path,
                parseColor(property.defaultValue ?: "#FFFFFF") ?: return null,
            )
            BlockPropertyEditor.MULTI_COLOR_SAMPLES -> arguments.add(
                property.path,
                parseMultiColorSamples(property.defaultValue ?: "1,0,#FFFFFF,0") ?: return null,
            )
            BlockPropertyEditor.LEGACY_PATTERN -> arguments.add(
                property.path,
                parseLegacyPattern(property.defaultValue ?: "0,0,#FFFFFF,0,0,0") ?: return null,
            )
            BlockPropertyEditor.LEGACY_FIXED_PATTERN -> arguments.add(
                property.path,
                parseLegacyFixedPattern(property.defaultValue ?: "0,0,#FFFFFF,0,0,0") ?: return null,
            )
            BlockPropertyEditor.LEGACY_COLOR_GROUP -> arguments.add(
                property.path,
                parseLegacyColorGroup(property.defaultValue ?: "#FFFFFF,0,0,0") ?: return null,
            )
            BlockPropertyEditor.LEGACY_REGION -> arguments.add(
                property.path,
                parseLegacyRegion(property.defaultValue ?: "0,0,1,1") ?: return null,
            )
            BlockPropertyEditor.ENUM -> arguments.addProperty(
                property.path,
                property.defaultValue?.takeIf { it in property.choices }
                    ?: property.choices.firstOrNull() ?: return null,
            )
            BlockPropertyEditor.FLOW_REFERENCE -> {
                arguments.addProperty(property.path, target?.flowId ?: return null)
            }
            BlockPropertyEditor.FLOW_ARGUMENTS -> {
                arguments.add(property.path, defaultFlowCallArguments(target ?: return null))
            }
        }
    }
    if (contract.kind in setOf("flow.argument.set", "flow.return.set") &&
        !arguments.has("value") && !arguments.has("valueVariable")) {
        arguments.addProperty("value", 0)
    }
    return arguments
}

/** Variables that are already meaningful in this Flow, offered by the variable/condition editor. */
internal fun visualKnownVariables(
    editor: VisualEditorState,
    declared: List<ProjectVariable>,
    currentFlowId: String,
): List<String> = buildSet {
    declared.filter { it.scope == ProjectVariableScope.GLOBAL || it.flowId == currentFlowId }
        .forEach { add(it.name) }
    editor.rows.forEach { row ->
        val args = editor.nodeArguments(row.nodeId) ?: return@forEach
        when (row.kind) {
            "variable.set", "variable.calculate" -> args.get("name")?.takeIf { it.isJsonPrimitive }?.asString?.let(::add)
            "variable.copy" -> listOf("sourceName", "name").forEach { key ->
                args.get(key)?.takeIf { it.isJsonPrimitive }?.asString?.let(::add)
            }
            else -> args.entrySet().forEach { (key, value) ->
                if ((key == "variable" || key.endsWith("Variable")) && value.isJsonPrimitive) add(value.asString)
            }
        }
    }
}.filter { it.matches(Regex("[A-Za-z_][A-Za-z0-9_]{0,63}")) }.sorted()


/**
 * A real editor for the first two visual language building blocks.  It deliberately writes the
 * existing `variable.*` / `control.if` contract, so saved flows compile through the same Rust path
 * as blocks inserted from the library.
 */
@Composable
private fun VisualVariableConditionDialog(
    contract: BlockContract,
    inputs: Map<String, String>,
    knownVariables: List<String>,
    projectVariables: List<ProjectVariable> = emptyList(),
    currentFlowId: String = "",
    flows: List<ProjectFlow> = emptyList(),
    onManageVariables: (() -> Unit)? = null,
    onDismiss: () -> Unit,
    onConfirm: (Map<String, String>) -> String?,
    confirmLabel: String = "确定",
) {
    if (contract.kind == "variable.calculate") {
        VisualVariableCalculationDialog(JsonObject().apply {
            addProperty("name", inputs["name"].orEmpty())
            addProperty("expression", inputs["expression"].orEmpty())
        }, projectVariables, currentFlowId, flows, onDismiss,
            onConfirm = { configured -> onConfirm(configured.entrySet().associate { it.key to it.value.asString }) },
            onManageVariables = onManageVariables, confirmLabel = confirmLabel)
        return
    }
    var values by remember(contract.kind, inputs) { mutableStateOf(inputs) }
    var elseIfBranches by remember(contract.kind, inputs) {
        mutableStateOf(parseElseIfDrafts(inputs["elseIf"]))
    }
    var validationError by remember(contract.kind, inputs) { mutableStateOf<String?>(null) }
    val title = when (contract.kind) {
        "control.if" -> "如果"
        "variable.set" -> "设置变量"
        "task.log" -> "输出日志"
        "task.prompt" -> "弹出提示"
        "task.runprompt" -> "运行提示"
        "task.comment" -> "注释"
        else -> "读取变量"
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.fillMaxWidth(.92f).widthIn(max = 520.dp),
            color = androidx.compose.ui.graphics.Color.White,
            shape = RoundedCornerShape(3.dp),
            shadowElevation = 10.dp,
        ) {
            Column {
                Row(
                    Modifier.fillMaxWidth().height(40.dp).padding(start = 14.dp, end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(title, color = AutoScriptPalette.Accent, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Text("×", color = AutoScriptPalette.TextSecondary, fontSize = 22.sp, modifier = Modifier.clickable(onClick = onDismiss).padding(horizontal = 8.dp))
                }
                HorizontalDivider(color = AutoScriptPalette.Divider)
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    when (contract.kind) {
                        "control.if" -> {
                            VisualVariableField("左值（变量）", values["variable"].orEmpty(), knownVariables) { values = values + ("variable" to it) }
                            Text("比较运算符", color = AutoScriptPalette.Accent, fontSize = 11.sp)
                            Row(Modifier.fillMaxWidth()) {
                                listOf(
                                    "equals" to "等于", "notEquals" to "不等于", "lessThan" to "小于",
                                    "lessOrEqual" to "≤", "greaterThan" to "大于", "greaterOrEqual" to "≥",
                                ).forEach { (value, label) ->
                                    val selected = values["operator"] == value
                                    Text(label, color = if (selected) androidx.compose.ui.graphics.Color.White else AutoScriptPalette.TextPrimary, fontSize = 10.sp,
                                        textAlign = TextAlign.Center, modifier = Modifier.weight(1f).padding(horizontal = 2.dp)
                                            .background(if (selected) AutoScriptPalette.Accent else androidx.compose.ui.graphics.Color(0xFFF1F4F8), RoundedCornerShape(3.dp))
                                            .clickable { values = values + ("operator" to value) }.padding(vertical = 7.dp))
                                }
                            }
                            OutlinedTextField(
                                value = values["value"].orEmpty(), onValueChange = { values = values + ("value" to it) },
                                label = { Text("右值（字符串请用双引号）") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                            )
                            VisualVariableField("右值变量（优先于右值）", values["valueVariable"].orEmpty(), knownVariables) { values = values + ("valueVariable" to it) }
                            if (elseIfBranches.isNotEmpty()) {
                                Text("否则如果分支", color = AutoScriptPalette.Accent, fontSize = 11.sp)
                                elseIfBranches.forEachIndexed { index, branch ->
                                    Column(
                                        Modifier.fillMaxWidth().border(1.dp, AutoScriptPalette.Divider, RoundedCornerShape(3.dp)).padding(7.dp),
                                        verticalArrangement = Arrangement.spacedBy(4.dp),
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text("否则如果 ${index + 1}", fontSize = 11.sp, modifier = Modifier.weight(1f))
                                        }
                                        VisualVariableField("左值", branch.variable, knownVariables) { next ->
                                            elseIfBranches = elseIfBranches.toMutableList().apply { set(index, branch.copy(variable = next)) }
                                        }
                                        Row(Modifier.fillMaxWidth()) {
                                            listOf("equals" to "=", "notEquals" to "≠", "lessThan" to "<", "lessOrEqual" to "≤", "greaterThan" to ">", "greaterOrEqual" to "≥").forEach { (operator, label) ->
                                                val selected = branch.operator == operator
                                                Text(label, textAlign = TextAlign.Center, fontSize = 10.sp,
                                                    color = if (selected) androidx.compose.ui.graphics.Color.White else AutoScriptPalette.TextPrimary,
                                                    modifier = Modifier.weight(1f).padding(horizontal = 1.dp).background(if (selected) AutoScriptPalette.Accent else androidx.compose.ui.graphics.Color(0xFFF1F4F8), RoundedCornerShape(3.dp)).clickable {
                                                        elseIfBranches = elseIfBranches.toMutableList().apply { set(index, branch.copy(operator = operator)) }
                                                    }.padding(vertical = 5.dp))
                                            }
                                        }
                                        OutlinedTextField(branch.value, { next -> elseIfBranches = elseIfBranches.toMutableList().apply { set(index, branch.copy(value = next)) } }, label = { Text("右值（JSON 标量）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                                        VisualVariableField("右值变量（优先）", branch.valueVariable, knownVariables) { next ->
                                            elseIfBranches = elseIfBranches.toMutableList().apply { set(index, branch.copy(valueVariable = next)) }
                                        }
                                    }
                                }
                            }
                            Text("新增或删除分支请用顶栏“＋/－否则如果”；每个分支都有独立的“否则如果 N 内新增”入口。", color = AutoScriptPalette.TextSecondary, fontSize = 10.sp)
                        }
                        "variable.set" -> {
                            VisualVariableField("变量名", values["name"].orEmpty(), knownVariables) { values = values + ("name" to it) }
                            OutlinedTextField(value = values["value"].orEmpty(), onValueChange = { values = values + ("value" to it) }, label = { Text("值（字符串请用双引号）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        }
                        "task.log" -> {
                            Text("日志级别", color = AutoScriptPalette.Accent, fontSize = 11.sp)
                            Row(Modifier.fillMaxWidth()) {
                                listOf("info" to "信息", "warn" to "警告", "error" to "错误").forEach { (level, label) ->
                                    val selected = values["level"] == level
                                    Text(
                                        label,
                                        color = if (selected) androidx.compose.ui.graphics.Color.White else AutoScriptPalette.TextPrimary,
                                        fontSize = 10.sp,
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier.weight(1f).padding(horizontal = 2.dp)
                                            .background(if (selected) AutoScriptPalette.Accent else androidx.compose.ui.graphics.Color(0xFFF1F4F8), RoundedCornerShape(3.dp))
                                            .clickable { values = values + ("level" to level) }
                                            .padding(vertical = 7.dp),
                                    )
                                }
                            }
                            OutlinedTextField(
                                value = values["message"].orEmpty(),
                                onValueChange = { values = values + ("message" to it) },
                                label = { Text("日志内容") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            VisualVariableField("变量值（可选，优先输出）", values["valueVariable"].orEmpty(), knownVariables) {
                                values = values + ("valueVariable" to it)
                            }
                        }
                        "task.prompt", "task.runprompt" -> {
                            OutlinedTextField(
                                value = values["message"].orEmpty(),
                                onValueChange = { values = values + ("message" to it) },
                                label = { Text("提示内容") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            VisualVariableField("变量值（可选，优先提示）", values["valueVariable"].orEmpty(), knownVariables) {
                                values = values + ("valueVariable" to it)
                            }
                        }
                        "task.comment" -> {
                            OutlinedTextField(
                                value = values["message"].orEmpty(),
                                onValueChange = { values = values + ("message" to it) },
                                label = { Text("注释内容") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        else -> {
                            VisualVariableField("来源变量", values["sourceName"].orEmpty(), knownVariables) { values = values + ("sourceName" to it) }
                            VisualVariableField("目标变量", values["name"].orEmpty(), knownVariables) { values = values + ("name" to it) }
                        }
                    }
                    validationError?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 11.sp) }
                }
                HorizontalDivider(color = AutoScriptPalette.Divider)
                Row(Modifier.fillMaxWidth().height(40.dp)) {
                    Text("取消", textAlign = TextAlign.Center, color = AutoScriptPalette.TextPrimary, fontSize = 13.sp, modifier = Modifier.weight(1f).fillMaxSize().clickable(onClick = onDismiss).wrapContentSize(Alignment.Center))
                    Text(confirmLabel, textAlign = TextAlign.Center, color = AutoScriptPalette.Accent, fontSize = 13.sp, modifier = Modifier.weight(1f).fillMaxSize().clickable {
                        val complete = if (contract.kind == "control.if") values + ("elseIf" to serializeElseIfDrafts(elseIfBranches)) else values
                        validationError = onConfirm(complete)
                    }.wrapContentSize(Alignment.Center))
                }
            }
        }
    }
}

private data class VisualElseIfDraft(
    val variable: String = "value",
    val operator: String = "equals",
    val value: String = "true",
    val valueVariable: String = "",
)

private fun parseElseIfDrafts(value: String?): List<VisualElseIfDraft> = runCatching {
    JsonParser.parseString(value ?: "[]").asJsonArray.mapNotNull { item ->
        item.takeIf { it.isJsonObject }?.asJsonObject?.let { branch ->
            VisualElseIfDraft(
                variable = branch.get("variable")?.asString ?: return@let null,
                operator = branch.get("operator")?.asString ?: "equals",
                value = branch.get("value")?.toString() ?: "true",
                valueVariable = branch.get("valueVariable")?.asString.orEmpty(),
            )
        }
    }
}.getOrDefault(emptyList())

private fun serializeElseIfDrafts(branches: List<VisualElseIfDraft>): String = JsonArray().apply {
    branches.forEach { branch -> add(JsonObject().apply {
        addProperty("variable", branch.variable)
        addProperty("operator", branch.operator)
        add("value", parseScalar(branch.value) ?: com.google.gson.JsonPrimitive(branch.value))
        if (branch.valueVariable.isNotBlank()) addProperty("valueVariable", branch.valueVariable)
    }) }
}.toString()

@Composable
private fun VisualVariableField(label: String, value: String, choices: List<String>, onValueChange: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(value = value, onValueChange = onValueChange, label = { Text(label) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        if (choices.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                Text("已有变量：", color = AutoScriptPalette.TextSecondary, fontSize = 10.sp, modifier = Modifier.padding(top = 5.dp, end = 4.dp))
                choices.forEach { choice ->
                    Text(choice, color = AutoScriptPalette.Accent, fontSize = 10.sp, modifier = Modifier.padding(end = 5.dp)
                        .border(1.dp, AutoScriptPalette.Divider, RoundedCornerShape(3.dp)).clickable { onValueChange(choice) }.padding(horizontal = 7.dp, vertical = 4.dp))
                }
            }
        }
    }
}

@Composable
internal fun VisualNodeArgumentsDialog(
    contract: BlockContract,
    arguments: JsonObject,
    flows: List<ProjectFlow>,
    currentFlowId: String,
    resources: List<JsonObject>,
    knownVariables: List<String> = emptyList(),
    imageProject: ProjectSnapshot? = null,
    imageDialogVisible: Boolean = true,
    imageCaptureSelection: ImageToolCodeGen.VisualSelection? = null,
    imageCaptureTemplatePath: String? = null,
    onPickImageFromScreen: ((ImageToolMode) -> Unit)? = null,
    onManageVariables: (() -> Unit)? = null,
    onDismiss: () -> Unit,
    onConfirm: (JsonObject) -> Unit,
    confirmLabel: String = "保存",
    knownLabels: List<String> = emptyList(),
) {
    if (contract.kind == "ui.get" && imageProject != null) {
        VisualUiParameterDialog(arguments, imageProject.manifest.runnerUi, imageProject.manifest.variables, currentFlowId,
            flows, onManageVariables, onDismiss, { onConfirm(it); null }, confirmLabel)
        return
    }
    if (contract.kind.startsWith("input.")) {
        CompactInputArgumentsDialog(contract, arguments, onDismiss, onConfirm, confirmLabel)
        return
    }
    if (contract.kind in setOf("variable.set", "variable.copy", "control.if")) {
        VisualVariableConditionDialog(contract, conditionPropertyTexts(contract, arguments), knownVariables,
            imageProject?.manifest?.variables.orEmpty(), currentFlowId, flows, onManageVariables, onDismiss,
            onConfirm = { configured ->
                val parsed = parseBlockArguments(contract, configured, null, emptyMap(), resources)
                if (parsed.first == null) parsed.second else { onConfirm(requireNotNull(parsed.first)); null }
            }, confirmLabel = confirmLabel)
        return
    }
    if (ConfiguredEntryArgumentsDialog(contract, arguments, imageProject?.manifest?.variables.orEmpty(), flows,
            currentFlowId, knownVariables, imageProject?.manifest?.debugSettings ?: com.autoscript.project.store.ProjectDebugSettings(),
            onManageVariables, onDismiss, onConfirm, confirmLabel, knownLabels)) return
    if (contract.kind == "variable.calculate") {
        VisualVariableCalculationDialog(arguments, imageProject?.manifest?.variables.orEmpty(), currentFlowId, flows,
            onDismiss, onConfirm = { onConfirm(it); null }, onManageVariables = onManageVariables, confirmLabel = confirmLabel)
        return
    }
    if (contract.kind in visualRecognitionKinds) {
        VisualImageRecognitionDialog(
            contract, arguments, resources, knownVariables, imageProject,
            visible = imageDialogVisible, captureSelection = imageCaptureSelection,
            captureTemplatePath = imageCaptureTemplatePath, onPickFromScreen = onPickImageFromScreen,
            confirmLabel = confirmLabel,
            onDismiss = onDismiss, onConfirm = { _, configured -> onConfirm(configured) },
        )
        return
    }
    val targets = flows.filter { it.flowId != currentFlowId }
    var targetFlowId by remember(contract.kind, arguments) {
        mutableStateOf(arguments.get("targetFlowId")?.takeIf { it.isJsonPrimitive }?.asString ?: targets.firstOrNull()?.flowId)
    }
    var inputs by remember(contract.kind, arguments) { mutableStateOf(blockPropertyTexts(contract, arguments)) }
    var flowInputs by remember(contract.kind, arguments, targetFlowId) {
        val target = targets.firstOrNull { it.flowId == targetFlowId }
        mutableStateOf(target?.let { flowCallArgumentTexts(it, arguments.getAsJsonObject("arguments")) }.orEmpty())
    }
    var validationError by remember(contract.kind, arguments) { mutableStateOf<String?>(null) }
    val target = targets.firstOrNull { it.flowId == targetFlowId }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.fillMaxWidth(.92f).widthIn(max = 520.dp),
            color = androidx.compose.ui.graphics.Color.White,
            shape = RoundedCornerShape(3.dp),
            shadowElevation = 10.dp,
        ) {
            Column {
                Row(
                    Modifier.fillMaxWidth().height(40.dp).padding(start = 14.dp, end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(contract.title, color = AutoScriptPalette.Accent, fontSize = 16.sp,
                        fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Text("×", color = AutoScriptPalette.TextSecondary, fontSize = 22.sp,
                        modifier = Modifier.clickable(onClick = onDismiss).padding(horizontal = 8.dp))
                }
                HorizontalDivider(color = AutoScriptPalette.Divider)
                Column(
                    Modifier.fillMaxWidth().heightIn(max = 480.dp).verticalScroll(rememberScrollState()).padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    contract.properties.forEach { property ->
                        when (property.editor) {
                            BlockPropertyEditor.FLOW_REFERENCE -> {
                                Text(property.label, color = AutoScriptPalette.Accent, fontSize = 11.sp)
                                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                                    targets.forEach { candidate ->
                                        TextButton(onClick = {
                                            targetFlowId = candidate.flowId
                                            inputs = inputs + (property.path to candidate.flowId)
                                            flowInputs = defaultFlowCallArgumentTexts(candidate)
                                            validationError = null
                                        }) { Text(if (candidate.flowId == targetFlowId) "● ${candidate.flowId}" else candidate.flowId) }
                                    }
                                }
                            }
                            BlockPropertyEditor.FLOW_ARGUMENTS -> {
                                target?.params.orEmpty().forEach { parameter ->
                                    val name = parameter.get("name")?.asString.orEmpty()
                                    OutlinedTextField(
                                        value = flowInputs[name].orEmpty(),
                                        onValueChange = { flowInputs = flowInputs + (name to it) },
                                        label = { Text("调用参数 $name") },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                }
                            }
                            BlockPropertyEditor.RESOURCE -> {
                                Text(property.label, color = AutoScriptPalette.Accent, fontSize = 11.sp)
                                val choices = resourcePaths(resources, property.resourceKind)
                                if (choices.isEmpty()) Text("项目中没有可选资源", color = MaterialTheme.colorScheme.error)
                                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                                    choices.forEach { path ->
                                        TextButton(onClick = { inputs = inputs + (property.path to path) }) {
                                            Text(if (inputs[property.path] == path) "● $path" else path)
                                        }
                                    }
                                }
                            }
                            BlockPropertyEditor.BOOLEAN,
                            BlockPropertyEditor.ENUM,
                            BlockPropertyEditor.INTEGER_ENUM -> {
                                Text(property.label, color = AutoScriptPalette.Accent, fontSize = 11.sp)
                                val choices = if (property.editor == BlockPropertyEditor.BOOLEAN) listOf("true", "false") else property.choices
                                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                                    choices.forEach { choice ->
                                        TextButton(onClick = { inputs = inputs + (property.path to choice) }) {
                                            Text(if (inputs[property.path] == choice) "● $choice" else choice)
                                        }
                                    }
                                }
                            }
                            BlockPropertyEditor.STRING -> if (property.path.endsWith("Variable")) {
                                VisualVariableField(property.label, inputs[property.path].orEmpty(), knownVariables) {
                                    inputs = inputs + (property.path to it)
                                }
                            } else OutlinedTextField(
                                value = inputs[property.path].orEmpty(),
                                onValueChange = { inputs = inputs + (property.path to it) },
                                label = { Text(property.label) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            else -> OutlinedTextField(
                                value = inputs[property.path].orEmpty(),
                                onValueChange = { inputs = inputs + (property.path to it) },
                                label = { Text(property.label) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                    if (contract.properties.isEmpty()) {
                        Text("这个积木没有可编辑参数", color = AutoScriptPalette.TextSecondary, fontSize = 12.sp)
                    }
                    validationError?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 11.sp) }
                }
                HorizontalDivider(color = AutoScriptPalette.Divider)
                Row(Modifier.fillMaxWidth().height(40.dp)) {
                    Text("取消", textAlign = TextAlign.Center, color = AutoScriptPalette.TextPrimary,
                        fontSize = 13.sp, modifier = Modifier.weight(1f).fillMaxSize()
                            .clickable(onClick = onDismiss).wrapContentSize(Alignment.Center))
                    Box(Modifier.size(width = 1.dp, height = 40.dp).background(AutoScriptPalette.Divider))
                    Text(confirmLabel, textAlign = TextAlign.Center, color = AutoScriptPalette.Accent,
                        fontSize = 13.sp, modifier = Modifier.weight(1f).fillMaxSize()
                            .clickable {
                                val parsed = parseBlockArguments(contract, inputs, target, flowInputs, resources)
                                if (parsed.first == null) validationError = parsed.second ?: "参数无效"
                                else onConfirm(requireNotNull(parsed.first))
                            }.wrapContentSize(Alignment.Center))
                }
            }
        }
    }
}

private fun defaultFlowCallArgumentTexts(flow: ProjectFlow): Map<String, String> =
    flowCallArgumentTexts(flow, defaultFlowCallArguments(flow))

private fun flowCallArgumentTexts(flow: ProjectFlow, arguments: JsonObject?): Map<String, String> =
    flow.params.associate { parameter ->
        val name = parameter.get("name")?.asString.orEmpty()
        name to arguments?.get(name)?.let { value ->
            if (value.isJsonPrimitive && value.asJsonPrimitive.isString) value.asString else value.toString()
        }.orEmpty()
    }

internal fun blockPropertyTexts(
    contract: BlockContract,
    arguments: JsonObject,
): Map<String, String> = contract.properties
    .filter { it.editor != BlockPropertyEditor.FLOW_ARGUMENTS }
    .associate { property ->
        val value = arguments.get(property.path)
        property.path to when {
            value == null || value.isJsonNull -> property.defaultValue.orEmpty()
            property.editor == BlockPropertyEditor.POINT -> formatPoint(value.asJsonObject)
            property.editor == BlockPropertyEditor.RECT -> formatRect(value.asJsonObject)
            property.editor == BlockPropertyEditor.COLOR -> formatColor(value.asInt)
            property.editor == BlockPropertyEditor.MULTI_COLOR_SAMPLES -> {
                formatMultiColorSamples(value.asJsonArray)
            }
            property.editor == BlockPropertyEditor.LEGACY_PATTERN -> {
                formatLegacyPattern(value.asJsonArray)
            }
            property.editor == BlockPropertyEditor.LEGACY_FIXED_PATTERN -> {
                formatLegacyFixedPattern(value.asJsonArray)
            }
            property.editor == BlockPropertyEditor.LEGACY_COLOR_GROUP -> {
                formatLegacyColorGroup(value.asJsonArray)
            }
            property.editor == BlockPropertyEditor.LEGACY_REGION -> {
                formatLegacyRegion(value.asJsonObject)
            }
            property.editor != BlockPropertyEditor.SCALAR &&
                value.isJsonPrimitive && value.asJsonPrimitive.isString -> value.asString
            else -> value.toString()
    }
}

private fun conditionPropertyTexts(contract: BlockContract, arguments: JsonObject): Map<String, String> =
    blockPropertyTexts(contract, arguments).let { values ->
        if (contract.kind != "control.if") values else values + (
            "elseIf" to (arguments.getAsJsonArray("elseIf")?.toString() ?: "[]")
        )
    }

internal fun parseBlockArguments(
    contract: BlockContract,
    inputs: Map<String, String>,
    targetFlow: ProjectFlow?,
    flowInputs: Map<String, String>,
    resources: List<JsonObject>,
): Pair<JsonObject?, String?> {
    val arguments = JsonObject()
    contract.properties.forEach { property ->
        val text = inputs[property.path].orEmpty()
        if (!property.required && text.isBlank() && property.editor != BlockPropertyEditor.FLOW_ARGUMENTS) {
            return@forEach
        }
        when (property.editor) {
            BlockPropertyEditor.BOOLEAN -> arguments.addProperty(
                property.path,
                text.toBooleanStrictOrNull()
                    ?: return null to "${property.label}必须是 true 或 false",
            )
            BlockPropertyEditor.INTEGER -> arguments.addProperty(
                property.path,
                text.toLongOrNull() ?: return null to "${property.label}必须是整数",
            )
            BlockPropertyEditor.INTEGER_ENUM -> {
                if (text !in property.choices) return null to "${property.label}不在允许值中"
                val value = text.toLongOrNull() ?: return null to "${property.label}必须是整数"
                arguments.addProperty(property.path, value)
            }
            BlockPropertyEditor.NUMBER -> arguments.addProperty(
                property.path,
                text.toDoubleOrNull()?.takeIf { it.isFinite() }
                    ?: return null to "${property.label}必须是有限数字",
            )
            BlockPropertyEditor.STRING -> {
                arguments.addProperty(property.path, text)
            }
            BlockPropertyEditor.RESOURCE -> {
                if (text !in resourcePaths(resources, property.resourceKind)) {
                    return null to "${property.label}不是已登记的项目资源"
                }
                arguments.addProperty(property.path, text)
            }
            BlockPropertyEditor.SCALAR -> arguments.add(
                property.path,
                parseScalar(text) ?: return null to "${property.label}必须是JSON标量",
            )
            BlockPropertyEditor.POINT -> arguments.add(
                property.path,
                parsePoint(text) ?: return null to "${property.label}必须是 x,y",
            )
            BlockPropertyEditor.RECT -> arguments.add(
                property.path,
                parseRect(text) ?: return null to "${property.label}必须是 left,top,right,bottom",
            )
            BlockPropertyEditor.COLOR -> arguments.addProperty(
                property.path,
                parseColor(text) ?: return null to "${property.label}必须是 #RRGGBB 或 0-16777215",
            )
            BlockPropertyEditor.MULTI_COLOR_SAMPLES -> arguments.add(
                property.path,
                parseMultiColorSamples(text)
                    ?: return null to "${property.label}格式必须是 x,y,#RRGGBB,容差；最多64组并用分号分隔",
            )
            BlockPropertyEditor.LEGACY_PATTERN -> arguments.add(
                property.path,
                parseLegacyPattern(text)
                    ?: return null to
                        "${property.label}格式必须是 dx,dy,#RRGGBB,红容差,绿容差,蓝容差；首项偏移必须为 0,0，最多65组并用分号分隔",
            )
            BlockPropertyEditor.LEGACY_FIXED_PATTERN -> arguments.add(
                property.path,
                parseLegacyFixedPattern(text)
                    ?: return null to
                        "${property.label}格式必须是 x,y,#RRGGBB,红容差,绿容差,蓝容差；坐标非负，最多256组并用分号分隔",
            )
            BlockPropertyEditor.LEGACY_COLOR_GROUP -> arguments.add(
                property.path,
                parseLegacyColorGroup(text)
                    ?: return null to
                        "${property.label}格式必须是 #RRGGBB,红容差,绿容差,蓝容差；最多64组并用分号分隔",
            )
            BlockPropertyEditor.LEGACY_REGION -> arguments.add(
                property.path,
                parseLegacyRegion(text)
                    ?: return null to "${property.label}必须是 左,上,宽,高，且宽高大于0",
            )
            BlockPropertyEditor.ENUM -> {
                if (text !in property.choices) return null to "${property.label}不在允许值中"
                arguments.addProperty(property.path, text)
            }
            BlockPropertyEditor.FLOW_REFERENCE -> {
                val target = targetFlow ?: return null to "请选择目标插件"
                arguments.addProperty(property.path, target.flowId)
            }
            BlockPropertyEditor.FLOW_ARGUMENTS -> {
                val target = targetFlow ?: return null to "请选择目标插件"
                val parsed = parseFlowCallArguments(target, flowInputs)
                if (parsed.second != null) return null to parsed.second
                arguments.add(property.path, requireNotNull(parsed.first))
            }
        }
    }
    if (contract.kind == "control.if") {
        val branches = runCatching { JsonParser.parseString(inputs["elseIf"].orEmpty()).asJsonArray }
            .getOrNull() ?: return null to "否则如果分支格式无效"
        if (branches.size() > 32 || branches.any { branch ->
                !branch.isJsonObject || branch.asJsonObject.get("variable")?.isJsonPrimitive != true ||
                    branch.asJsonObject.get("operator")?.isJsonPrimitive != true ||
                    branch.asJsonObject.get("value") == null
            }) return null to "否则如果分支参数无效"
        if (branches.size() > 0) arguments.add("elseIf", branches)
    }
    if (contract.kind == "task.comment" && arguments.get("message")?.asString.isNullOrBlank()) {
        return null to "注释内容不能为空"
    }
    if (contract.kind.startsWith("vision.") || contract.kind.startsWith("ocr.")) {
        val bounds = mapOf(
            "tolerance" to (0..255),
            "anchorTolerance" to (0..255),
            "similarityPermille" to (0..1000),
            "limit" to (1..256),
            "spaceGapColumns" to (0..65535),
        )
        bounds.forEach { (name, allowed) ->
            arguments.get(name)?.let { value ->
                if (value.asInt !in allowed) return null to "$name 必须在 ${allowed.first}..${allowed.last} 之间"
            }
        }
        arguments.getAsJsonObject("region")?.let { region ->
            if (region.get("right").asInt <= region.get("left").asInt ||
                region.get("bottom").asInt <= region.get("top").asInt) {
                return null to "识别区域的右/下坐标必须大于左/上坐标"
            }
        }
    }
    return arguments to null
}

private fun parseScalar(text: String) = runCatching { JsonParser.parseString(text) }
    .getOrNull()
    ?.takeUnless { it.isJsonArray || it.isJsonObject }

internal fun parsePoint(text: String): JsonObject? {
    val values = text.split(',').map(String::trim)
    if (values.size != 2) return null
    val x = values[0].toIntOrNull() ?: return null
    val y = values[1].toIntOrNull() ?: return null
    return JsonObject().apply {
        addProperty("x", x)
        addProperty("y", y)
    }
}

internal fun parseRect(text: String): JsonObject? {
    val values = text.split(',').map(String::trim)
    if (values.size != 4) return null
    val edges = values.map { it.toIntOrNull() ?: return null }
    if (edges[2] <= edges[0] || edges[3] <= edges[1]) return null
    return JsonObject().apply {
        addProperty("left", edges[0])
        addProperty("top", edges[1])
        addProperty("right", edges[2])
        addProperty("bottom", edges[3])
    }
}

internal fun parseColor(text: String): Int? {
    val value = text.trim()
    val parsed = when {
        value.startsWith("#") -> value.drop(1).takeIf { it.length == 6 }?.toIntOrNull(16)
        value.startsWith("0x", ignoreCase = true) -> value.drop(2).toIntOrNull(16)
        else -> value.toIntOrNull()
    }
    return parsed?.takeIf { it in 0..0xFFFFFF }
}

internal fun parseMultiColorSamples(text: String): JsonArray? {
    val entries = text.split(';').map(String::trim)
    if (entries.isEmpty() || entries.size > 64 || entries.any(String::isEmpty)) return null
    val samples = JsonArray()
    entries.forEach { entry ->
        val values = entry.split(',').map(String::trim)
        if (values.size != 4) return null
        val x = values[0].toIntOrNull() ?: return null
        val y = values[1].toIntOrNull() ?: return null
        val rgb = parseColor(values[2]) ?: return null
        val tolerance = values[3].toIntOrNull()?.takeIf { it in 0..255 } ?: return null
        samples.add(JsonObject().apply {
            addProperty("x", x)
            addProperty("y", y)
            addProperty("rgb", rgb)
            addProperty("tolerance", tolerance)
        })
    }
    return samples
}

private fun formatMultiColorSamples(samples: JsonArray): String = samples.joinToString(";") { value ->
    val sample = value.asJsonObject
    "${sample.get("x").asInt},${sample.get("y").asInt},${formatColor(sample.get("rgb").asInt)},${sample.get("tolerance").asInt}"
}

private fun JsonObject.channelTolerances(): String =
    "${get("toleranceRed").asInt},${get("toleranceGreen").asInt},${get("toleranceBlue").asInt}"

private fun JsonObject.addChannelTolerances(red: Int, green: Int, blue: Int) {
    addProperty("toleranceRed", red)
    addProperty("toleranceGreen", green)
    addProperty("toleranceBlue", blue)
}

/**
 * Parses per-channel legacy tolerances. The legacy grammar keeps red, green and blue independent,
 * so a single collapsed tolerance would silently change matching behaviour.
 */
private fun parseChannelTolerances(values: List<String>): Triple<Int, Int, Int>? {
    val channels = values.map { it.toIntOrNull()?.takeIf { channel -> channel in 0..255 } ?: return null }
    return Triple(channels[0], channels[1], channels[2])
}

internal fun parseLegacyPattern(text: String): JsonArray? {
    val entries = text.split(';').map(String::trim)
    if (entries.isEmpty() || entries.size > 65 || entries.any(String::isEmpty)) return null
    val samples = JsonArray()
    entries.forEachIndexed { index, entry ->
        val values = entry.split(',').map(String::trim)
        if (values.size != 6) return null
        val dx = values[0].toIntOrNull() ?: return null
        val dy = values[1].toIntOrNull() ?: return null
        if (index == 0 && (dx != 0 || dy != 0)) return null
        val rgb = parseColor(values[2]) ?: return null
        val (red, green, blue) = parseChannelTolerances(values.subList(3, 6)) ?: return null
        samples.add(JsonObject().apply {
            addProperty("dx", dx)
            addProperty("dy", dy)
            addProperty("rgb", rgb)
            addChannelTolerances(red, green, blue)
        })
    }
    return samples
}

private fun formatLegacyPattern(samples: JsonArray): String = samples.joinToString(";") { value ->
    val sample = value.asJsonObject
    "${sample.get("dx").asInt},${sample.get("dy").asInt}," +
        "${formatColor(sample.get("rgb").asInt)},${sample.channelTolerances()}"
}

internal fun parseLegacyFixedPattern(text: String): JsonArray? {
    val entries = text.split(';').map(String::trim)
    if (entries.isEmpty() || entries.size > 256 || entries.any(String::isEmpty)) return null
    val samples = JsonArray()
    entries.forEach { entry ->
        val values = entry.split(',').map(String::trim)
        if (values.size != 6) return null
        val x = values[0].toIntOrNull()?.takeIf { it >= 0 } ?: return null
        val y = values[1].toIntOrNull()?.takeIf { it >= 0 } ?: return null
        val rgb = parseColor(values[2]) ?: return null
        val (red, green, blue) = parseChannelTolerances(values.subList(3, 6)) ?: return null
        samples.add(JsonObject().apply {
            addProperty("x", x)
            addProperty("y", y)
            addProperty("rgb", rgb)
            addChannelTolerances(red, green, blue)
        })
    }
    return samples
}

private fun formatLegacyFixedPattern(samples: JsonArray): String = samples.joinToString(";") { value ->
    val sample = value.asJsonObject
    "${sample.get("x").asInt},${sample.get("y").asInt}," +
        "${formatColor(sample.get("rgb").asInt)},${sample.channelTolerances()}"
}

internal fun parseLegacyColorGroup(text: String): JsonArray? {
    val entries = text.split(';').map(String::trim)
    if (entries.isEmpty() || entries.size > 64 || entries.any(String::isEmpty)) return null
    val colors = JsonArray()
    entries.forEach { entry ->
        val values = entry.split(',').map(String::trim)
        if (values.size != 4) return null
        val rgb = parseColor(values[0]) ?: return null
        val (red, green, blue) = parseChannelTolerances(values.subList(1, 4)) ?: return null
        colors.add(JsonObject().apply {
            addProperty("rgb", rgb)
            addChannelTolerances(red, green, blue)
        })
    }
    return colors
}

private fun formatLegacyColorGroup(colors: JsonArray): String = colors.joinToString(";") { value ->
    val color = value.asJsonObject
    "${formatColor(color.get("rgb").asInt)},${color.channelTolerances()}"
}

/** Legacy regions are origin plus extent, not a half-open rectangle. */
internal fun parseLegacyRegion(text: String): JsonObject? {
    val values = text.split(',').map(String::trim)
    if (values.size != 4) return null
    val left = values[0].toIntOrNull()?.takeIf { it >= 0 } ?: return null
    val top = values[1].toIntOrNull()?.takeIf { it >= 0 } ?: return null
    val width = values[2].toIntOrNull()?.takeIf { it > 0 } ?: return null
    val height = values[3].toIntOrNull()?.takeIf { it > 0 } ?: return null
    return JsonObject().apply {
        addProperty("left", left)
        addProperty("top", top)
        addProperty("width", width)
        addProperty("height", height)
    }
}

private fun formatLegacyRegion(value: JsonObject): String =
    "${value.get("left").asInt},${value.get("top").asInt}," +
        "${value.get("width").asInt},${value.get("height").asInt}"

private fun formatPoint(value: JsonObject): String =
    "${value.get("x").asInt},${value.get("y").asInt}"

private fun formatRect(value: JsonObject): String =
    "${value.get("left").asInt},${value.get("top").asInt}," +
        "${value.get("right").asInt},${value.get("bottom").asInt}"

internal fun formatColor(value: Int): String = String.format(Locale.ROOT, "#%06X", value)

internal fun resourcePaths(
    resources: List<JsonObject>,
    kind: BlockResourceKind?,
): List<String> {
    val manifestKind = when (kind) {
        BlockResourceKind.IMAGE -> "image"
        BlockResourceKind.GLYPH_DICTIONARY -> "glyphDictionary"
        null -> return emptyList()
    }
    return resources.mapNotNull { resource ->
        resource.get("path")?.asString?.takeIf { resource.get("kind")?.asString == manifestKind }
    }.sorted()
}

private fun parseFlowCallArguments(
    flow: ProjectFlow,
    inputs: Map<String, String>,
): Pair<JsonObject?, String?> {
    val arguments = JsonObject()
    flow.params.forEach { parameter ->
        val name = parameter.get("name")?.asString ?: return null to "参数定义缺少 name"
        val type = parameter.get("type")?.asString ?: return null to "参数 $name 缺少类型"
        val required = parameter.get("required")?.asBoolean == true
        val text = inputs[name].orEmpty()
        if (!required && text.isBlank()) return@forEach
        when (type) {
            "boolean" -> arguments.addProperty(
                name,
                text.toBooleanStrictOrNull() ?: return null to "$name 必须是 true 或 false",
            )
            "integer" -> arguments.addProperty(
                name,
                text.toLongOrNull() ?: return null to "$name 必须是整数",
            )
            "number" -> arguments.addProperty(
                name,
                text.toDoubleOrNull()?.takeIf(Double::isFinite)
                    ?: return null to "$name 必须是有限数字",
            )
            "string" -> arguments.addProperty(name, text)
            else -> return null to "$name 使用了不支持的类型"
        }
    }
    return arguments to null
}

package com.autoscript.studio

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.zIndex
import com.autoscript.core.model.RuntimeConnectionPhase
import com.autoscript.core.model.RuntimeConnectionState
import com.autoscript.core.model.RuntimeEngineState
import com.autoscript.project.store.ProjectSnapshot
import com.autoscript.project.store.ProjectDesign
import com.autoscript.project.store.ProjectFlow
import com.autoscript.project.store.ProjectStore
import com.autoscript.runtime.client.RuntimeClient
import com.autoscript.runtime.client.ScreenshotPreviewResult
import com.autoscript.runtime.client.VisualCompileDiagnostic
import com.autoscript.runtime.client.VisualCompileResult
import com.autoscript.runtime.api.InputPointPickReply
import com.autoscript.studio.generated.BlockCategory
import com.autoscript.studio.generated.BlockContract
import com.google.gson.JsonObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    initialImageToolBitmap: Bitmap? = null,
    initialImageToolToken: String? = null,
    onInitialImageToolConsumed: () -> Unit = {},
    modifier: Modifier = Modifier,
    onSnapshotChanged: (ProjectSnapshot) -> Unit,
    onExit: () -> Unit,
) {
    val projectId = snapshot.manifest.projectId
    val stepState = rememberVisualStepState(projectId, runtimeState, runtimeClient)
    val context = LocalContext.current
    val overlayHost = LocalEditorOverlay.current
    val currentInputSnapshot by rememberUpdatedState(snapshot)
    val currentRuntimeState by rememberUpdatedState(runtimeState)
    var inputFeatureSnapshot by remember { mutableStateOf<InputFeatureSnapshot?>(null) }
    var inputCapabilityRevision by remember { mutableIntStateOf(0) }
    val inputBackendFeatures = if (runtimeState.phase != RuntimeConnectionPhase.CONNECTED ||
        runtimeState.rootState != com.autoscript.core.model.RuntimeRootState.READY) 0
        else inputFeatureSnapshot?.available(runtimeState, inputCapabilityRevision)
    val openSoftwarePermissions = rememberSoftwarePermissionsLauncher(runtimeClient)
    LaunchedEffect(runtimeState.phase, runtimeState.rootState, runtimeState.sessionGeneration, inputCapabilityRevision) {
        inputFeatureSnapshot = null
        if (runtimeState.phase == RuntimeConnectionPhase.CONNECTED &&
            runtimeState.rootState == com.autoscript.core.model.RuntimeRootState.READY) {
            val features = withContext(Dispatchers.IO) { runtimeClient.inputFeatures() }
            inputFeatureSnapshot = InputFeatureSnapshot(runtimeState.sessionGeneration, inputCapabilityRevision, features)
        }
    }
    var selectedFlowId by remember(projectId) {
        mutableStateOf(
            initialFlowId?.takeIf { requested -> snapshot.manifest.flows.any { it.flowId == requested } }
                ?: snapshot.manifest.entryFlowId
                ?: snapshot.manifest.flows.first().flowId,
        )
    }
    var action by remember(projectId) { mutableStateOf<VisualAction?>(null) }
    var overlayAwaitingRunRevision by remember(projectId) { mutableStateOf<Long?>(null) }
    var runFeedback by remember(projectId) { mutableStateOf<EditorRunFeedback?>(null) }
    var overlayStartJob by remember(projectId) { mutableStateOf<Job?>(null) }
    var error by remember(projectId) { mutableStateOf<String?>(null) }
    var notice by remember(projectId) { mutableStateOf<String?>(null) }
    var diagnostic by remember(projectId) { mutableStateOf<VisualCompileDiagnostic?>(null) }
    var diagnosticMessage by remember(projectId) { mutableStateOf<String?>(null) }
    var diagnosticTarget by remember(projectId) { mutableStateOf<EditorProblemTarget?>(null) }
    var projectSearchFlows by remember(projectId) { mutableStateOf<List<EditorSearchFlow>?>(null) }
    val projectSearchSession = remember(projectId) { EditorSearchSession() }
    var projectSearchAnchor by remember(projectId) { mutableStateOf(selectedFlowId) }
    var replacementFlows by remember(projectId) { mutableStateOf<List<EditorSearchFlow>?>(null) }
    var replacementBaseline by remember(projectId) { mutableStateOf<ProjectSnapshot?>(null) }
    var replacementAnchor by remember(projectId) { mutableStateOf(selectedFlowId) }
    val projectEdits = remember(projectId) { EditorProjectEdits() }
    var pendingBatch by remember(projectId) { mutableStateOf<EditorBatchParameters?>(null) }
    var batchBaseline by remember(projectId) { mutableStateOf<ProjectSnapshot?>(null) }
    var batchDrafts by remember(projectId) { mutableStateOf<Map<String, String>>(emptyMap()) }
    var locateRequest by remember(projectId) { mutableIntStateOf(0) }
    var parameterNavigation by remember(projectId) { mutableStateOf<EditorProblemTarget?>(null) }
    val projectIds = remember(projectId) { VisualProjectIds(snapshot.flowSources.values, snapshot.manifest.flows.map { it.rootBlockId }) }
    val editors = remember(projectId) {
        snapshot.manifest.flows.associate { flow ->
            flow.flowId to VisualEditorState.createProjectScoped(
                snapshot.flowSources[flow.flowId].orEmpty(),
                flow.rootBlockId,
                projectIds,
            )
        }.toMutableMap()
    }
    var editorRevision by remember(projectId) { mutableIntStateOf(0) }
    var showDockProjectSettings by remember(projectId) { mutableStateOf(false) }
    var showVariableManager by remember(projectId) { mutableStateOf(false) }
    var showRecognitionTest by remember(projectId) { mutableStateOf(false) }
    var showGlyphMaker by remember(projectId) { mutableStateOf(false) }
    var variableCheckVisible by remember(projectId) { mutableStateOf(false) }
    var runtimeVariableWatchOpen by remember(projectId) { mutableStateOf(false) }
    var runtimeVariableRestoreRequest by remember(projectId) { mutableIntStateOf(0) }
    var showImageLibrary by remember(projectId) { mutableStateOf(false) }
    var showBlockPicker by remember(projectId) { mutableStateOf(false) }
    var blockPickerAutoSave by remember(projectId) { mutableStateOf(false) }
    var blockQuery by remember(projectId) { mutableStateOf("") }
    var blockCategory by remember(projectId) { mutableStateOf<BlockCategory?>(null) }
    var insertionChildBlockName by remember(projectId) { mutableStateOf<String?>(null) }
    var pickerBranchContext by remember(projectId) { mutableStateOf<EditorPasteRequest?>(null) }
    var pendingDockInsert by remember(projectId) { mutableStateOf<EditorDockInsertRequest?>(null) }
    var dockClipboard by remember(projectId) { mutableStateOf<List<VisualSubtreeClipboard>?>(null) }
    var pendingDockPaste by remember(projectId) { mutableStateOf<EditorPasteRequest?>(null) }
    var pendingDockIndentSlots by remember(projectId) { mutableStateOf<EditorIndentRequest?>(null) }
    var pendingBlockConfiguration by remember(projectId) { mutableStateOf<VisualPendingBlockInsert?>(null) }
    var pendingImageBlock by remember(projectId) { mutableStateOf<Pair<BlockContract, JsonObject>?>(null) }
    var showRecognitionReader by remember(projectId) { mutableStateOf(false) }
    var pendingTextTool by remember(projectId) { mutableStateOf<Pair<BlockContract, JsonObject>?>(null) }
    var pendingImageInsertion by remember(projectId) { mutableStateOf<ImageBlockInsertionRequest?>(null) }
    var imageInsertionBusy by remember(projectId) { mutableStateOf(false) }
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
    var inputFloatingJob by remember(projectId) { mutableStateOf<Job?>(null) }
    val inputFloatingLaunch = remember(projectId) { InputPickerLaunchState() }
    var pendingInputInsertion by remember(projectId) { mutableStateOf<InputInsertionDraft?>(null) }
    var recordingTarget by remember(projectId) { mutableStateOf<Pair<InputPickTarget, ProjectDesign>?>(null) }
    var recordingActive by remember(projectId) { mutableStateOf(false) }
    var inputPickOpenRequest by remember(projectId) { mutableIntStateOf(0) }
    var imageToolRequest by remember(projectId) { mutableStateOf(false) }
    var pickedImageRegion by remember(projectId) { mutableStateOf<ImageToolCodeGen.Roi?>(null) }
    val scope = rememberCoroutineScope()
    val sourceFiles = remember(store) { ProjectSourceFiles(store) }
    var sourceTree by remember(projectId) { mutableStateOf(SourceFileTree()) }
    var sourceBusy by remember(projectId) { mutableStateOf(false) }
    var sourceMessage by remember(projectId) { mutableStateOf<String?>(null) }
    val editor = requireNotNull(editors[selectedFlowId])
    var followExecution by remember(projectId, runtimeState.sessionGeneration) { mutableStateOf(true) }
    LaunchedEffect(runtimeState.engineState) {
        if (runtimeState.engineState !in setOf(RuntimeEngineState.RUNNING, RuntimeEngineState.PAUSED)) followExecution = true
    }
    var branchSelection by remember(projectId, selectedFlowId) { mutableStateOf<EditorBranchSelection?>(null) }
    val selectedBranch = remember(editor, editor.currentSource, editor.selectedNodeId, branchSelection) {
        branchSelection?.takeIf { it.matches(editor) }
    }
    LaunchedEffect(editorRevision, runtimeState.engineState) {
        if (branchSelection?.matches(editor) == false || runtimeState.engineState in setOf(
                RuntimeEngineState.RUNNING, RuntimeEngineState.PAUSED, RuntimeEngineState.STOPPING)) branchSelection = null
    }
    val executionPosition = rememberVisualExecutionPosition(projectId, runtimeState, runtimeClient)
    LaunchedEffect(executionPosition, stepState.position, followExecution) {
        editorFollowFlow(followExecution, executionPosition ?: stepState.position, editors.keys)?.let { selectedFlowId = it }
    }

    DisposableEffect(projectId) {
        onDispose {
            inputFloatingLaunch.invalidate()
            inputFloatingJob?.cancel()
            inputFloatingRequest?.let(runtimeClient::cancelInputPointPick)
            inputPointRequestId++
            inputPointCaptureJob?.cancel()
            inputPointBitmap?.recycle()
        }
    }

    LaunchedEffect(selectedFlowId) {
        editingCallNodeId = null
        inputFloatingLaunch.invalidate()
        inputFloatingJob?.cancel()
        inputFloatingJob = null
        inputFloatingRequest?.let(runtimeClient::cancelInputPointPick)
        inputFloatingRequest = null
        if (inputPickTarget?.flowId != selectedFlowId) {
            inputPickTarget = null
            pendingInputInsertion = null
            showingInputPointPicker = false
            recognitionCaptureActive = false
            pendingTemplateRoi = null
            inputPointRequestId++
            inputPointCaptureJob?.cancel()
            inputPointCaptureJob = null
            inputPointCapturing = false
            inputPointBitmap?.recycle()
            inputPointBitmap = null
        }
        if (recordingTarget?.first?.flowId != selectedFlowId) {
            recordingTarget = null
            recordingActive = false
        }
    }

    LaunchedEffect(snapshot) {
        projectIds.reserveSources(snapshot.flowSources.values)
        projectIds.reserveIds(snapshot.manifest.flows.map { it.rootBlockId })
        sourceTree = withContext(Dispatchers.IO) {
            runCatching { sourceFiles.load(snapshot) }.getOrDefault(SourceFileTree())
        }
    }
    val contentSource = editor.currentSource
    val flowNames = remember(snapshot.manifest.flows) { snapshot.manifest.flows.associate { it.flowId to it.displayName() } }
    val programNodes = remember(editor, contentSource, flowNames) {
        editor.programDisplayNodes(flowNames)
    }
    val nodes = remember(editor, contentSource) { editor.rows }
    val selectedExecutable = programNodes.firstOrNull { it.nodeId == editor.selectedNodeId }
        ?.let { !it.disabled && !it.inheritedDisabled } == true
    val availableLabels = remember(editor, contentSource) {
        nodes.filter { it.kind == "control.label" && it.depth == 0 }
            .mapNotNull { row -> editor.nodeArguments(row.nodeId)?.get("name")?.asString }.distinct()
    }
    val knownVariables = remember(editor, contentSource, snapshot.manifest.variables, selectedFlowId) {
        visualKnownVariables(editor, snapshot.manifest.variables, selectedFlowId)
    }
    val runtimeBusy = action != null || imageInsertionBusy
    val running = runtimeState.engineState in setOf(
        RuntimeEngineState.RUNNING,
        RuntimeEngineState.PAUSED,
    )
    val stopping = runtimeState.engineState == RuntimeEngineState.STOPPING
    val canStartAction = !runtimeBusy && !sourceBusy && !running && !stopping
    val canStop = editorCanStop(runtimeState.phase == RuntimeConnectionPhase.CONNECTED,
        runtimeState.engineState, action == VisualAction.STOPPING)
    val canPauseResume = runtimeState.phase == RuntimeConnectionPhase.CONNECTED && running && !runtimeBusy
    val canEditProjectSettings = !runtimeBusy && !running && !stopping &&
        editors.values.none { it.isDirty }

    if (showDockProjectSettings) {
        ProjectSettingsDialog(
            snapshot = snapshot,
            store = store,
            runtimeClient = runtimeClient,
            onSnapshotChanged = onSnapshotChanged,
            onDismiss = { showDockProjectSettings = false },
        )
    }

    fun frozenSearchFlows(): List<EditorSearchFlow> = snapshot.manifest.flows.map { flow ->
        val state = editors[flow.flowId]
        EditorSearchFlow(flow.flowId, flow.displayName(), state?.currentSource.orEmpty(), flow.rootBlockId, state?.isReadOnly != false)
    }

    fun showDraftValidationProblem(result: VisualCompileResult) {
        if (result !is VisualCompileResult.Invalid) return
        diagnostic = result.diagnostic
        diagnosticTarget = editorProblemTarget(result.diagnostic, frozenSearchFlows())
        error = formatDiagnostic(result.diagnostic)
        diagnosticMessage = error
    }

    fun showCompileResult(result: VisualCompileResult): String? = when (result) {
        is VisualCompileResult.Success -> {
            diagnostic = null
            diagnosticMessage = null
            diagnosticTarget = null
            notice = "编译成功 · ${result.generationId.take(20)}"
            result.generationId
        }
        is VisualCompileResult.Invalid -> {
            diagnostic = result.diagnostic
            diagnosticTarget = editorProblemTarget(result.diagnostic, frozenSearchFlows())
            result.diagnostic.flowId?.let { flowId ->
                if (snapshot.manifest.flows.any { it.flowId == flowId }) selectedFlowId = flowId
            }
            error = formatDiagnostic(result.diagnostic)
            diagnosticMessage = error
            null
        }
        is VisualCompileResult.Unavailable -> {
            error = result.message
            null
        }
    }

    suspend fun saveEditorDrafts(flowIds: Set<String>): Boolean {
        val targets = editors.filter { (id, state) -> id in flowIds && state.isDirty }
        if (targets.isEmpty()) return true
        targets.entries.firstOrNull { it.value.isReadOnly }?.let {
            selectedFlowId = it.key
            error = "插件结构无效，当前只能只读查看"
            return false
        }
        val inspected = currentInputSnapshot
        if (targets.any { (id, state) -> inspected.flowSources[id] != state.savedSource }) {
            error = "正式插件已变化，未覆盖，请先重新打开项目"
            return false
        }
        val expectedDrafts = editors.mapValues { it.value.currentSource }
        val submitted = targets.mapValues { it.value.currentSource }
        val combined = editors.filterValues { it.isDirty }.keys.associateWith { expectedDrafts.getValue(it) }
        val validation = withContext(Dispatchers.IO) {
            runtimeClient.validateVisualDraftSet(projectId, submitted.mapValues { it.value.toByteArray(Charsets.UTF_8) })
        }
        if (validation !is VisualCompileResult.Success) {
            showCompileResult(validation)
            return false
        }
        if (combined != submitted) {
            val combinedValidation = withContext(Dispatchers.IO) {
                runtimeClient.validateVisualDraftSet(projectId, combined.mapValues { it.value.toByteArray(Charsets.UTF_8) })
            }
            if (combinedValidation !is VisualCompileResult.Success) {
                showCompileResult(combinedValidation)
                return false
            }
        }
        if (currentInputSnapshot.manifest != inspected.manifest || currentInputSnapshot.flowSources != inspected.flowSources ||
            editors.mapValues { it.value.currentSource } != expectedDrafts || overlayHost?.isClosed == true) {
            error = "校验期间项目或编辑草稿已变化，未保存，请重试"
            return false
        }
        runCatching {
            editorSaveCommit(write = { withContext(Dispatchers.IO) { store.saveFlows(inspected, submitted) } }) { saved ->
                targets.forEach { (id, state) -> state.markSaved(submitted.getValue(id)) }
                editorRevision++
                onSnapshotChanged(saved)
                notice = if (targets.values.any { it.isDirty }) "已保存提交快照，仍有后续修改" else "已安全保存${targets.size}个插件"
            }
        }.getOrElse { failure ->
            if (failure is CancellationException) throw failure
            error = failure.message ?: "插件批次保存失败，草稿保留"
            return false
        }
        return true
    }

    suspend fun saveEditorIfNeeded(flowId: String, state: VisualEditorState): Boolean =
        if (!state.isDirty) true else saveEditorDrafts(setOf(flowId))

    suspend fun saveAllIfNeeded(): Boolean = saveEditorDrafts(editors.keys.toSet())

    fun requestEditorExit() {
        if (overlayHost?.hasOpenDialogs == true) {
            error = "请先完成或取消当前参数弹窗，再保存关闭编辑"
            return
        }
        if (overlayHost != null && action == VisualAction.STARTING) {
            val pending = overlayStartJob
            if (pending == null) { error = "启动正在结束，请稍后关闭"; return }
            pending.cancel()
            scope.launch { pending.join(); requestEditorExit() }
            return
        }
        if (action != null || sourceBusy) { error = "正在处理项目，请完成或取消后关闭编辑"; return }
        action = VisualAction.SAVING
        scope.launch {
            editorOperation({ error = it.message ?: "保存失败，编辑草稿保留" }, { action = null }) {
                if (saveAllIfNeeded()) {
                    if (overlayHost == null && currentRuntimeState.engineState in setOf(RuntimeEngineState.RUNNING, RuntimeEngineState.PAUSED)) {
                        withContext(Dispatchers.IO) { runtimeClient.setFloatingControlEnabled(true) }
                    }
                    overlayHost?.closeDialogs(); onExit()
                }
            }
        }
    }
    val latestExit by rememberUpdatedState(::requestEditorExit)
    DisposableEffect(overlayHost) {
        overlayHost?.closeRequest = { latestExit() }
        onDispose { overlayHost?.closeRequest = null }
    }
    SideEffect {
        overlayHost?.canvas(showingInputPointPicker && !inputPointCapturing)
        overlayHost?.suppress(when {
            inputPointCapturing -> "capture:$inputPointRequestId"
            inputFloatingLaunch.preparing -> "pick-preparing:$projectId"
            inputFloatingRequest != null -> "pick:$inputFloatingRequest"
            recordingActive -> "record:$selectedFlowId"
            else -> null
        })
    }
    LaunchedEffect(runtimeState, runFeedback) {
        runFeedback?.observe(runtimeState)?.let { notice = it }
    }
    LaunchedEffect(overlayAwaitingRunRevision, runtimeState.stateRevision) {
        val before = overlayAwaitingRunRevision ?: return@LaunchedEffect
        if (editorRunAcknowledged(before, runtimeState))
            overlayAwaitingRunRevision = null
    }
    LaunchedEffect(overlayAwaitingRunRevision) {
        val ticket = overlayAwaitingRunRevision ?: return@LaunchedEffect
        delay(15_000)
        if (overlayAwaitingRunRevision == ticket) {
            overlayAwaitingRunRevision = null
            error = "尚未收到执行状态确认，请检查运行环境后重试"
        }
    }

    fun saveCurrent() {
        if (runtimeBusy || !editor.isDirty) return
        action = VisualAction.SAVING
        error = null
        notice = null
        scope.launch {
            editorOperation({ error = it.message ?: "插件保存失败，草稿保留" }, { action = null }) {
                saveEditorIfNeeded(selectedFlowId, editor)
            }
        }
    }

    fun compileOnly() {
        if (!canStartAction) return
        action = VisualAction.COMPILING
        error = null
        notice = null
        scope.launch {
            editorOperation({ error = it.message ?: "编译失败，草稿保留" }, { action = null }) {
                if (saveAllIfNeeded()) {
                    val result = withContext(Dispatchers.IO) { runtimeClient.compileVisualProject(projectId) }
                    showCompileResult(result)
                }
            }
        }
    }

    fun runProject(entry: StudioRunEntry) {
        val singleStep = entry == StudioRunEntry.SINGLE_STEP
        if (action != null || !canStartAction) return
        if (inputFloatingJob?.isActive == true || inputFloatingRequest != null) { notice = "请先完成或取消选点，再运行"; return }
        if (runtimeState.phase != RuntimeConnectionPhase.CONNECTED) {
            error = "Runner尚未连接"
            return
        }
        action = VisualAction.STARTING
        runFeedback = null
        val beforeRunRevision = currentRuntimeState.stateRevision
        val runFlowId = selectedFlowId
        val stepNodeId = if (singleStep) editor.selectedNodeId else null
        if (singleStep && (stepNodeId == null || editor.isNodeDisabled(stepNodeId))) { action = null; error = "请选择可执行积木"; return }
        error = null
        notice = null
        overlayStartJob = scope.launch {
            var submitted = false
            try {
                if (!saveAllIfNeeded()) return@launch
                val compileResult = withContext(Dispatchers.IO) {
                    if (singleStep) runtimeClient.compileVisualNodeTest(projectId, runFlowId, requireNotNull(stepNodeId))
                    else runtimeClient.compileVisualProject(projectId)
                }
                val generationId = showCompileResult(compileResult) ?: return@launch
                val refreshed = withContext(Dispatchers.IO) { store.openProject(projectId) }
                onSnapshotChanged(refreshed)
                val plan = withContext(Dispatchers.IO) {
                    RuntimeProjectPlan.fromVisualSnapshot(refreshed, generationId, runFlowId, stepNodeId).let {
                        if (singleStep) it else it.forEditorRun(refreshed)
                    }
                }
                val feedback = EditorRunFeedback(currentRuntimeState, singleStep)
                notice = if (singleStep) "单步准备 · 仅执行选中积木及子节点" else "编辑运行准备 · 等待 Root 就绪"
                val accepted = withContext(Dispatchers.IO) {
                    runtimeClient.startProject(
                        projectId = projectId,
                        generatedLuaModule = plan.luaSource,
                        requiredVisionComponents = plan.requiredVisionComponents,
                        resources = plan.resources,
                        requiresPointerInput = plan.requiresPointerInput,
                        capabilities = plan.capabilities,
                        designWidth = plan.designWidth,
                        designHeight = plan.designHeight,
                        scaleMode = plan.scaleMode,
                        // Prepare default controls without opening the configuration window.
                        scriptUiJson = plan.scriptUiJson,
                        floatingControl = false,
                    )
                }
                if (accepted) {
                    submitted = true
                    overlayAwaitingRunRevision = beforeRunRevision
                    runFeedback = feedback
                    notice = feedback.submitted
                } else {
                    error = currentRuntimeState.message ?: "Runner拒绝启动，请检查运行环境"
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error = failure.message ?: "项目启动失败"
            } finally {
                if (action == VisualAction.STARTING) action = null
                overlayStartJob = null
                if (!submitted && overlayHost != null) {
                    overlayHost.suppress(null)
                }
            }
        }
    }

    fun stopProject() {
        if (!canStop) return
        runFeedback?.stopRequested = true
        action = VisualAction.STOPPING
        error = null
        notice = null
        scope.launch {
            try {
                val accepted = withContext(Dispatchers.IO) { runtimeClient.requestStop() }
                if (!accepted) error = currentRuntimeState.message ?: "停止请求未被 Runner 接受"
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error = failure.message ?: "停止请求失败"
            } finally {
                if (action == VisualAction.STOPPING) action = null
            }
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
                if (!accepted) error = currentRuntimeState.message ?: "运行控制被拒绝，请检查当前会话状态"
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error = failure.message ?: "运行控制请求失败"
            } finally {
                if (action == VisualAction.CONTROLLING) action = null
            }
        }
    }


    if (showVariableManager) {
        VisualVariableManagerDialog(
            busy = runtimeBusy || sourceBusy || running || stopping,
            variables = snapshot.manifest.variables,
            currentFlowId = selectedFlowId,
            flows = snapshot.manifest.flows,
            onDismiss = { showVariableManager = false },
            onSave = { variables, renames ->
                scope.launch {
                    if (!editorCanMutate(action != null || sourceBusy, editor.isReadOnly, currentRuntimeState.engineState)) {
                        error = "请停止运行后再修改变量"; return@launch
                    }
                    action = VisualAction.SAVING
                    val drafts = editors.mapValues { it.value.currentSource }
                    val expectedSources = snapshot.flowSources + editors.mapValues { it.value.savedSource }
                    editorOperation({ error = it.message ?: "变量表保存失败，草稿保留" }, { action = null }) {
                        val updated = withContext(Dispatchers.IO) { store.updateVariables(projectId, variables, snapshot.manifest.variables, renames, drafts, expectedSources) }
                        if (renames.isNotEmpty()) {
                            editors.forEach { (id, state) -> updated.flowSources[id]?.let(state::reloadCommittedSource) }
                            editorRevision++
                        }
                        onSnapshotChanged(updated); notice = "变量表已保存"; showVariableManager = false
                    }
                }
            },
            onSaveAndInsert = { variables, arguments, renames ->
                val targetFlow = selectedFlowId
                val targetContext = editorInsertionContext(projectId, targetFlow, editor)
                val childSlot = selectedBranch?.slot
                scope.launch {
                    if (!editorCanMutate(action != null || sourceBusy, editor.isReadOnly, currentRuntimeState.engineState)) {
                        error = "请停止运行后再修改变量"; return@launch
                    }
                    action = VisualAction.SAVING
                    val drafts = editors.mapValues { it.value.currentSource }
                    val expectedSources = snapshot.flowSources + editors.mapValues { it.value.savedSource }
                    var inserted = false
                    editorOperation({ error = it.message ?: "变量表保存失败，草稿保留" }, { action = null }) {
                        val updated = withContext(Dispatchers.IO) { store.updateVariables(projectId, variables, snapshot.manifest.variables, renames, drafts, expectedSources) }
                        val canInsert = targetContext.matches(projectId, selectedFlowId, editor) &&
                            editorCanMutate(sourceBusy, editor.isReadOnly, currentRuntimeState.engineState)
                        if (renames.isNotEmpty()) {
                            editors.forEach { (id, state) -> updated.flowSources[id]?.let(state::reloadCommittedSource) }
                            editorRevision++
                        }
                        onSnapshotChanged(updated)
                        val contract = BlockCatalog.find("variable.calculate")
                        val problem = if (contract == null) "缺少变量计算契约" else applyConfiguredEditorInsertion(
                            editorInsertionContext(projectId, targetFlow, editor), projectId, selectedFlowId, editor, contract,
                            arguments, if (childSlot == null) EditorInsertPosition.BELOW else EditorInsertPosition.INSIDE,
                            childSlot, updated.manifest.capabilities.toSet(), canInsert)
                        if (problem == null) {
                            editorRevision++; showVariableManager = false
                            notice = "已加入变量计算草稿，正在保存"; inserted = true
                        } else error = "变量表已保存，计算未加入：$problem"
                    }
                    if (inserted) saveCurrent()
                }
            },
        )
    }

    fun insertDockBlock(
        hint: String,
        childSlot: String?,
        position: EditorInsertPosition = EditorInsertPosition.BELOW,
        onCommitted: () -> Unit = {},
    ) {
        if (!editorCanMutate(runtimeBusy || sourceBusy, editor.isReadOnly, currentRuntimeState.engineState)) { error = "请停止运行后再修改插件"; return }
        val insertionContext = editorInsertionContext(projectId, selectedFlowId, editor)
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
                onCommitted()
                return
            }
            "--@autoscript-control:else" -> {
                val selected = editor.selectedNodeId
                if (selected == null || "else" !in editor.childBlockNames(selected)) {
                    error = "请先选中一个“如果”节点，再进入否则分支"
                    return
                }
                insertionChildBlockName = "else"
                pickerBranchContext = insertionContext
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
            ?.let { BlockSearchResult(it, BlockCatalog.missingCapabilities(it.kind, it.requiredCapabilities, capabilities)) }
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
                    legacyDockBlockArguments(contract, hint, initialRecognitionArguments(contract, snapshot)), insertionContext, childSlot, position, onCommitted)
                recognitionCaptureSelection = null
                recognitionCaptureTemplatePath = null
                return
            }
            val preferredFlowId = if (contract.kind in setOf("flow.call", "flow.call.branch")) JumpPanelCode.callTargetId(hint) else null
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
            if (contract.kind in visualUtilityKinds || contract.kind in setOf("ui.get", "ui.confirm", "ui.dialog")) {
                pendingBlockConfiguration = VisualPendingBlockInsert(contract, migratedArgs, insertionContext, childSlot, position, onCommitted)
                return
            }
            if (contract.kind in setOf("variable.set", "variable.copy", "variable.calculate", "control.if") && position != EditorInsertPosition.REPLACE && variableCalculationArguments(hint) == null) {
                pendingBlockConfiguration = VisualPendingBlockInsert(contract, migratedArgs, insertionContext, childSlot, position, onCommitted)
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
            val insertionProblem = applyConfiguredEditorInsertion(insertionContext, projectId, selectedFlowId, editor,
                contract, migratedArgs, position, childSlot, snapshot.manifest.capabilities.toSet(),
                editorCanMutate(runtimeBusy || sourceBusy, editor.isReadOnly, currentRuntimeState.engineState))
            if (insertionProblem != null) {
                error = if (contract.kind in positionLoopKinds) "${contract.title}只能加入循环体，请先选中循环体中的积木或选择循环体插入位置"
                    else insertionProblem
            } else {
                editorRevision++
                error = null
                notice = "已加入：${contract.title}"
                onCommitted()
                if (contract.kind in setOf("flow.call", "flow.call.branch")) {
                    reflectionPosition = null
                    reflectionChildSlot = null
                    editingCallNodeId = editor.selectedNodeId
                }
                saveCurrent()
            }
            return
        }
        blockQuery = query.takeIf { matching.isNotEmpty() }.orEmpty()
        insertionChildBlockName = childSlot
        pickerBranchContext = insertionContext.takeIf { childSlot != null }
        blockPickerAutoSave = true
        showBlockPicker = true
    }

    fun captureInputPointScreenshot(delayMillis: Long = 0L) {
        if (inputPointCaptureJob?.isActive == true || inputPointCapturing) return
        val requestId = inputPointRequestId + 1
        inputPointRequestId = requestId
        inputPointMessage = "正在检查 Root 截图后端…"
        inputPointCaptureJob?.cancel()
        inputPointCaptureJob = scope.launch {
            var decoded: Bitmap? = null
            try {
                when (val result = runtimeClient.capturePreviewWhenReady {
                    inputPointCapturing = true
                    inputPointMessage = "正在截图…"
                    awaitEditorCaptureSurface(context, overlayHost, "capture:$requestId", delayMillis)
                }) {
                    is ScreenshotPreviewResult.Success -> {
                        try {
                            // Retain ownership even if cancellation wins the IO-to-Main handoff.
                            withContext(Dispatchers.IO) { decoded = BitmapFactory.decodeFile(result.file.path) }
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
            } catch (_: Exception) {
                if (requestId == inputPointRequestId) inputPointMessage = "截图失败 [CAPTURE_PREVIEW_FAILED]，请查看运行环境诊断"
            } finally {
                decoded?.recycle()
                overlayHost?.releaseSuppression("capture:$requestId")
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
        val target = inputPickTarget ?: InputPickTarget(projectId, selectedFlowId, editor.currentSource, editor.selectedNodeId, selectedBranch?.slot)
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

    recordingTarget?.let { (target, design) ->
        RecordingWorkspaceScreen(
            projectId = target.projectId,
            flowId = target.flowId,
            design = design,
            capabilities = snapshot.manifest.capabilities.toSet(),
            runtimeClient = runtimeClient,
            onRecordingActive = { recordingActive = it },
            choosingPosition = pendingInputInsertion != null,
            onApply = { snippet ->
                if (!target.draft(snippet).matches(projectId, selectedFlowId, editor) ||
                    design != snapshot.manifest.design || !editorCanMutate(runtimeBusy, editor.isReadOnly, runtimeState.engineState)) {
                    error = "插件内容或坐标配置已变化，请重新录制"
                    false
                } else {
                    if (runCatching { prepareInputBlocks(snippet, snapshot.manifest.capabilities.toSet()) }.isFailure) {
                        error = "录制动作不可用，请检查能力声明"
                        false
                    } else {
                        inputPickTarget = target
                        pendingInputInsertion = target.draft(snippet)
                        true
                    }
                }
            },
            onBack = { recordingActive = false; recordingTarget = null },
        )
    }

    fun openRootRecorder() {
        if (!editorCanMutate(runtimeBusy || sourceBusy, editor.isReadOnly, currentRuntimeState.engineState) ||
            (inputFeatureSnapshot?.available(currentRuntimeState, inputCapabilityRevision) ?: 0) and
                com.autoscript.runtime.api.RuntimeProtocol.INPUT_FEATURE_RECORDING == 0 || "input.basic" !in currentInputSnapshot.manifest.capabilities) {
            error = "录制需要停止脚本、声明 input.basic，并确认Root录制能力"
            return
        }
        inputFloatingLaunch.invalidate()
        inputFloatingJob?.cancel()
        inputFloatingJob = null
        inputFloatingRequest?.let(runtimeClient::cancelInputPointPick)
        inputFloatingRequest = null
        inputPickTarget = null
        pendingInputInsertion = null
        closeInputCanvas()
        recordingTarget = InputPickTarget(projectId, selectedFlowId, editor.currentSource, editor.selectedNodeId, selectedBranch?.slot) to snapshot.manifest.design
    }

    // Transfer ownership once: the visual image tool/disposal now owns the decoded frame.
    LaunchedEffect(initialImageToolToken) {
        if (initialImageToolToken != null && initialImageToolBitmap != null) {
            inputPointCaptureJob?.cancel()
            inputPointRequestId++
            inputPointBitmap?.recycle()
            inputPointBitmap = initialImageToolBitmap
            inputPointMode = ImageToolMode.CROP
            imageToolRequest = true
            showingInputPointPicker = true
            inputPointMessage = "截图已接入图像工具，可裁剪并保存模板"
            onInitialImageToolConsumed()
        }
    }

    fun openInputFloatingPicker(mode: ImageToolMode) {
        val inputAction = VisualInputAction.entries.firstOrNull { it.mode == mode }
        if (!editorCanMutate(action != null || sourceBusy, editor.isReadOnly, currentRuntimeState.engineState)) {
            error = "请先停止运行并完成当前编辑操作"; return
        }
        val reason = inputAction?.let { inputActionUnavailableReason(it, true,
            inputFeatureSnapshot?.available(currentRuntimeState, inputCapabilityRevision)) }
        if (reason != null || "input.basic" !in currentInputSnapshot.manifest.capabilities) {
            error = reason ?: "项目未声明 input.basic"; return
        }
        if (inputFloatingJob?.isActive == true || inputFloatingRequest != null) { notice = "选点正在准备或进行中"; return }
        val target = InputPickTarget(projectId, selectedFlowId, editor.currentSource, editor.selectedNodeId, selectedBranch?.slot)
        inputPickTarget = target
        val design = snapshot.manifest.design
        notice = "正在准备悬浮选点…"
        val ticket = inputFloatingLaunch.begin()
        inputFloatingJob = scope.launch {
            editorOperation({ if (inputFloatingLaunch.current(ticket)) error = "悬浮选点启动失败，请检查权限和运行环境" },
                { inputFloatingLaunch.finish(ticket) }) {
                var completed = false
                val request = runtimeClient.beginInputPointPick(projectId, target.flowId, inputPointAction(mode)) { result ->
                    if (!inputFloatingLaunch.current(ticket)) return@beginInputPointPick
                    completed = true
                    inputFloatingRequest = null
                    if (result.status != InputPointPickReply.SUCCESS) error = result.message.ifBlank { "选点已取消" }
                    else if (inputPickTarget != target || currentInputSnapshot.manifest.design != design) error = "插件或设计分辨率已变化，请重新选点"
                    else runCatching { inputPointSnippet(result, design.width, design.height, design.scaleMode) }
                        .onSuccess(::queueInputInsertion).onFailure { error = it.message ?: "选点转换失败" }
                }
                if (!inputFloatingLaunch.current(ticket)) request?.let(runtimeClient::cancelInputPointPick)
                else {
                    inputFloatingRequest = request.takeUnless { completed }
                    if (!completed && request != null) context.inputPickerActivity()?.moveTaskToBack(true)
                    else if (!completed) error = "悬浮选点未启动，请在按键面板打开权限 / 环境检查"
                }
            }
        }
    }

    fun openInputPointPicker(mode: ImageToolMode, delayMillis: Long = 0L): Boolean {
        if (!editorCanMutate(action != null || sourceBusy, editor.isReadOnly, currentRuntimeState.engineState)) {
            error = "请先停止运行并完成当前编辑操作"; return false
        }
        VisualInputAction.entries.firstOrNull { it.mode == mode }?.let { selected ->
            val reason = inputActionUnavailableReason(selected, true,
                inputFeatureSnapshot?.available(currentRuntimeState, inputCapabilityRevision))
            if (reason != null || "input.basic" !in currentInputSnapshot.manifest.capabilities) {
                error = reason ?: "项目未声明 input.basic"; return false
            }
        }
        inputPickTarget = InputPickTarget(projectId, selectedFlowId, editor.currentSource, editor.selectedNodeId, selectedBranch?.slot)
        if (mode == ImageToolMode.POINTER_UP) { queueInputInsertion(ImageToolCodeGen.pointerUp()); return true }
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
        return true
    }

    fun openImageTools(delayMillis: Long) {
        if (openInputPointPicker(ImageToolMode.CROP, delayMillis)) imageToolRequest = true
    }

    fun openImageToolMode(mode: ImageToolMode, delayMillis: Long) {
        if (openInputPointPicker(mode, delayMillis)) imageToolRequest = true
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
        recordingActive = false
        recordingTarget = null
        editorRevision++
        closeInputCanvas()
        notice = "已加入草稿，正在保存：${draft.snippet.summary}"
        error = null
        action = VisualAction.SAVING
        scope.launch {
            editorOperation({ error = it.message ?: "动作保存失败，草稿保留" }, { action = null }) {
                if (saveEditorIfNeeded(draft.flowId, editor)) notice = "已加入并保存：${draft.snippet.summary}"
                else error = "动作已加入草稿，尚未保存；请用保存重试：${error.orEmpty()}"
            }
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

    fun openNodeParameters(flowId: String, nodeId: String): String? {
        val targetEditor = editors[flowId] ?: return "插件已不存在"
        if (!editorCanMutate(action != null || sourceBusy || imageInsertionBusy, targetEditor.isReadOnly,
                runtimeClient.runtimeStates.value.engineState)) return "请停止运行并完成当前操作后再修改参数"
        val row = targetEditor.rows.firstOrNull { it.nodeId == nodeId } ?: return "这行积木已不存在"
        val contract = BlockCatalog.find(row.kind) ?: return "当前版本不支持这种积木"
        if (contract.properties.isEmpty()) return "这行没有可修改的参数"
        val args = targetEditor.nodeArguments(nodeId) ?: return "无法读取参数"
        reflectionPosition = null; reflectionChildSlot = null
        val targets = snapshot.manifest.flows.filter { it.flowId != flowId }
        val currentTarget = args.get("targetFlowId")?.takeIf { it.isJsonPrimitive }?.asString
        val target = targets.firstOrNull { it.flowId == currentTarget } ?: targets.firstOrNull()
        editingCallNodeId = nodeId
        editingCallTargetId = target?.flowId
        propertyInputs = conditionPropertyTexts(contract, args)
        callArgumentInputs = target?.let { flowCallArgumentTexts(it, args.getAsJsonObject("arguments")) }.orEmpty()
        callArgumentError = null
        return null
    }

    LaunchedEffect(parameterNavigation, selectedFlowId) {
        val target = parameterNavigation ?: return@LaunchedEffect
        parameterNavigation = null
        val targetEditor = editors[target.flowId]
        if (selectedFlowId != target.flowId || targetEditor?.currentSource != target.source) error = "积木内容已变化，请重新校验定位"
        else openNodeParameters(target.flowId, target.nodeId)?.let { error = it }
    }

    projectSearchFlows?.let { frozen ->
        EditorProjectSearchDialog(frozen, projectSearchAnchor, projectSearchSession,
            onRefresh = { projectSearchFlows = frozenSearchFlows() },
            onReplace = {
                if (!editorCanMutate(action != null || sourceBusy || imageInsertionBusy, editor.isReadOnly,
                        runtimeClient.runtimeStates.value.engineState)) error = "请停止运行并完成当前操作后再替换"
                else {
                    replacementBaseline = currentInputSnapshot
                    replacementAnchor = projectSearchAnchor
                    replacementFlows = frozenSearchFlows()
                    projectSearchFlows = null
                }
            }, onDismiss = { projectSearchFlows = null }, onSelect = { hit, close ->
            val target = editors[hit.flowId]
            when {
                action != null || sourceBusy || pendingBlockConfiguration != null || pendingInputInsertion != null -> "请先完成当前编辑操作"
                else -> editorSearchTarget(hit, frozen, target) ?: run {
                    selectedFlowId = hit.flowId
                    requireNotNull(target).selectOnly(hit.nodeId)
                    followExecution = false; branchSelection = null
                    editorRevision++; locateRequest++
                    if (close) projectSearchFlows = null
                    null
                }
            }
        })
    }

    replacementFlows?.let { frozen ->
        key(frozen) {
            val baseline = remember { replacementBaseline }
            val references = remember { editors.toMap() }
            val drafts = remember { editors.mapValues { it.value.currentSource } }
            val selections = remember { editors.mapValues { it.value.selectedNodeId to it.value.selectedNodeIds.toSet() } }
            val selectedAtOpen = remember { selectedFlowId }
            val generation = remember { currentRuntimeState.sessionGeneration }
            fun canReplace(): Boolean = replacementFlows === frozen && baseline != null && overlayHost?.isClosed != true &&
                currentInputSnapshot.manifest == baseline.manifest && currentInputSnapshot.flowSources == baseline.flowSources &&
                editors.keys == references.keys && references.all { (id, state) -> editors[id] === state } &&
                editors.mapValues { it.value.currentSource } == drafts &&
                editors.mapValues { it.value.selectedNodeId to it.value.selectedNodeIds.toSet() } == selections &&
                selectedFlowId == selectedAtOpen && runtimeClient.runtimeStates.value.sessionGeneration == generation &&
                editorCanMutate(action != null || sourceBusy || imageInsertionBusy, editor.isReadOnly,
                    runtimeClient.runtimeStates.value.engineState)
            EditorProjectReplacementDialog(frozen,
                resourcePaths(baseline?.manifest?.resources.orEmpty(), com.autoscript.studio.generated.BlockResourceKind.IMAGE).distinct(),
                replacementAnchor, projectSearchSession.query, projectSearchSession.range, projectSearchSession.enabled,
                onDismiss = { replacementFlows = null }, onApply = { candidate ->
                    if (!canReplace()) "项目、草稿、选择或运行状态已变化，请关闭后重新预览" else {
                        val validation = withContext(Dispatchers.IO) {
                            runtimeClient.validateVisualDraftSet(projectId,
                                (drafts + candidate.sources).mapValues { it.value.toByteArray(Charsets.UTF_8) })
                        }
                        coroutineContext.ensureActive()
                        when (validation) {
                            is VisualCompileResult.Invalid -> {
                                showDraftValidationProblem(validation)
                                "整项目校验未通过，未修改；请查看上层错误详情，返回后输入仍保留"
                            }
                            is VisualCompileResult.Unavailable -> "校验不可用，未修改：${validation.message}"
                            is VisualCompileResult.Success -> if (!canReplace()) "校验期间上下文已变化，未修改任何插件" else {
                                val failure = projectEdits.apply(drafts, candidate.sources, editors)
                                if (failure == null) {
                                    editorRevision++; error = null
                                    notice = "已替换${candidate.nodes}行、${candidate.fields}项参数（${candidate.sources.size}个插件），可整批撤销；尚未保存"
                                }
                                failure
                            }
                        }
                    }
                })
        }
    }

    pendingBatch?.let { request ->
        key(request) {
        EditorBatchParametersDialog(request, onDismiss = { pendingBatch = null }, onApply = { candidate ->
            val targetEditor = editors[request.context.flowId]
            val baseline = batchBaseline
            if (targetEditor == null || baseline == null) "项目上下文已变化，未应用" else {
                val frozenDrafts = batchDrafts
                val result = applyEditorBatch(request, candidate, targetEditor, canApply = {
                    pendingBatch === request && overlayHost?.isClosed != true &&
                        selectedFlowId == request.context.flowId && editors[request.context.flowId] === targetEditor &&
                        currentInputSnapshot.manifest == baseline.manifest && currentInputSnapshot.flowSources == baseline.flowSources &&
                        editors.mapValues { it.value.currentSource } == frozenDrafts &&
                        editorCanMutate(action != null || sourceBusy || imageInsertionBusy, targetEditor.isReadOnly,
                            runtimeClient.runtimeStates.value.engineState)
                }, validate = {
                    val validation = withContext(Dispatchers.IO) {
                        runtimeClient.validateVisualDraftSet(projectId,
                            (frozenDrafts + (request.context.flowId to candidate.source)).mapValues { it.value.toByteArray(Charsets.UTF_8) })
                    }
                    coroutineContext.ensureActive()
                    showDraftValidationProblem(validation)
                    validation
                })
                if (result == null) { editorRevision++; error = null; notice = "已批量修改${candidate.changed}行，可一次撤销" }
                result
            }
        })
        }
    }

    fun pasteDockClipboard(destination: EditorPasteDestination, request: EditorPasteRequest? = null) {
        if (!editorCanMutate(action != null || sourceBusy, editor.isReadOnly, runtimeClient.runtimeStates.value.engineState) ||
            request?.matches(projectId, selectedFlowId, editor) == false) {
            error = "插件、选择或运行状态已变化，请重新选择粘贴位置"; return
        }
        val clipboard = dockClipboard ?: run {
            error = "剪贴板为空"
            return
        }
        finishDockMutation(
            editor.pasteSelection(clipboard, destination.childSlot, beforeSelection = destination.before),
            "已粘贴节点副本",
        )
    }

    fun runDockCommand(command: EditorProgramCommand) {
        if (selectedBranch != null && command !in setOf(EditorProgramCommand.UNDO, EditorProgramCommand.REDO)) {
            notice = "分支是加入位置，请先选择要操作的实际积木"
            return
        }
        if (!editorCanMutate(action != null || sourceBusy, editor.isReadOnly, runtimeClient.runtimeStates.value.engineState)) return
        val historyGroup = when (command) {
            EditorProgramCommand.UNDO -> editor.undoGroup
            EditorProgramCommand.REDO -> editor.redoGroup
            else -> null
        }
        if (historyGroup != null) {
            if (imageInsertionBusy) { error = "请先完成当前图像操作"; return }
            error = projectEdits.moveHistory(editor, command == EditorProgramCommand.REDO, editors)
            if (error == null) {
                editorRevision++; locateRequest++
                notice = if (command == EditorProgramCommand.REDO) "已整批重做，尚未保存" else "已整批撤销，尚未保存"
            }
            return
        }
        when (val result = executeEditorCommand(editor, command)) {
            is EditorCommandResult.Mutation -> finishDockMutation(result.changed, result.message)
            is EditorCommandResult.Indent -> {
                if (result.slots.isEmpty()) error = "请连续选择同级积木，且上一行必须是容器"
                else pendingDockIndentSlots = EditorIndentRequest(
                    EditorPasteRequest(projectId, selectedFlowId, editor.currentSource,
                        editor.selectedNodeId, editor.selectedNodeIds.toSet()), result.slots)
            }
            is EditorCommandResult.Clipboard -> {
                if (!result.cut || result.nodes != null) dockClipboard = result.nodes
                if (result.nodes == null) error = "请先选择节点"
                else if (result.cut) finishDockMutation(result.changed, "已剪切整棵子树")
                else { error = null; notice = "已复制整棵子树" }
            }
            EditorCommandResult.Paste -> {
                if (dockClipboard == null) error = "剪贴板为空"
                else if (editor.selectedNodeId != null) pendingDockPaste = EditorPasteRequest(projectId, selectedFlowId,
                    editor.currentSource, editor.selectedNodeId, editor.selectedNodeIds.toSet())
                else pasteDockClipboard(EditorPasteDestination())
            }
        }
    }

    fun ensureEditor(updated: ProjectSnapshot, flowId: String) {
        projectIds.reserveSources(updated.flowSources.values)
        projectIds.reserveIds(updated.manifest.flows.map { it.rootBlockId })
        val flow = updated.manifest.flows.singleOrNull { it.flowId == flowId } ?: return
        if (editors[flowId] == null) {
            editors[flowId] = VisualEditorState.createProjectScoped(updated.flowSources[flowId].orEmpty(), flow.rootBlockId, projectIds)
        }
    }

    /** 源文件管理的动作全部经 [ProjectSourceFiles]；返回新快照的操作会同步给宿主。 */
    fun handleSourceAction(sourceAction: SourceManagerAction) {
        if (sourceBusy || runtimeBusy) return
        if ((running || stopping) && sourceAction !is SourceManagerAction.Open && sourceAction !is SourceManagerAction.Refresh) {
            error = "请停止运行后再修改插件或分组"
            return
        }
        sourceBusy = true
        sourceMessage = null
        scope.launch {
            try {
                val result = runCatching {
                    if (sourceAction !is SourceManagerAction.Open && sourceAction !is SourceManagerAction.InsertCall &&
                        !saveAllIfNeeded()) return@runCatching null
                    when (sourceAction) {
                        SourceManagerAction.Refresh -> withContext(Dispatchers.IO) { store.openProject(projectId) }
                        is SourceManagerAction.SetEntry -> withContext(Dispatchers.IO) { sourceFiles.setEntry(snapshot, sourceAction.flowId) }
                        is SourceManagerAction.Open -> {
                            if (saveAllIfNeeded()) {
                                if (running) followExecution = false
                                selectedFlowId = sourceAction.flowId
                            }
                            null
                        }
                        is SourceManagerAction.InsertCall -> {
                            val target = snapshot.manifest.flows.firstOrNull { it.flowId == sourceAction.flowId }
                            if (target == null) error = "插件不存在" else {
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
                result.onFailure { failure ->
                    if (failure is CancellationException) throw failure
                    sourceMessage = failure.message ?: "插件操作失败"
                }
                val current = result.getOrNull() ?: snapshot
                if (current !== snapshot) onSnapshotChanged(current)
                sourceTree = withContext(Dispatchers.IO) { runCatching { sourceFiles.load(current) }.getOrDefault(sourceTree) }
            } finally {
                sourceBusy = false
            }
        }
    }

    /** 插件管理的只读检查：完整项目走 Rust 编译，未调用统计包含当前草稿引用。 */
    fun handlePluginAction(pluginAction: PluginManagerAction) {
        when (pluginAction) {
            PluginManagerAction.CHECK, PluginManagerAction.CHECK_ALL -> compileOnly()
            PluginManagerAction.TEMPLATE -> notice = "插件流程模板库后续开放，第一版暂不提供"
            PluginManagerAction.UNUSED -> {
                val unused = sourceFiles.unreferencedFlows(snapshot.copy(
                    flowSources = snapshot.flowSources + editors.mapValues { it.value.currentSource },
                ))
                notice = if (unused.isEmpty()) "所有插件都被调用或是主插件" else "未调用插件：" + unused.joinToString("、") { it.displayName() }
            }
            PluginManagerAction.CREATE,
            PluginManagerAction.DELETE,
            PluginManagerAction.SAVE_AS,
            PluginManagerAction.GROUP,
            -> Unit // 面板已转到源文件管理
        }
    }

    pendingInputInsertion?.let { draft ->
        InputInsertPositionDialog(draft, editor,
            onDismiss = { pendingInputInsertion = null; inputPickTarget = null },
            onConfirm = { position, slot -> confirmInputInsertion(draft, position, slot) })
    }

    pendingDockInsert?.let { request ->
        val childSlots = request.anchorId?.let(editor::childBlockNames).orEmpty()
        EditorOptionDialog(
            title = if (request.insideOnly) "选择加入分支" else "选择插入位置",
            options = request.options(childSlots),
            onDismiss = { pendingDockInsert = null },
            preview = { slot -> EditorInsertionPreview(programNodes.toEditorTreeNodes(), request.anchorId,
                if (slot == null) EditorInsertPosition.BELOW else EditorInsertPosition.INSIDE, slot) },
            onConfirm = { slot ->
                pendingDockInsert = null
                if (!request.matches(projectId, selectedFlowId, editor) ||
                    !editorCanMutate(action != null || sourceBusy, editor.isReadOnly, currentRuntimeState.engineState) ||
                    (slot == null && request.insideOnly) || (slot != null && slot !in childSlots)) {
                    error = "插件、选择或运行状态已变化，请重新选择加入分支"
                } else insertDockBlock(request.hint, slot,
                    if (slot == null) EditorInsertPosition.BELOW else EditorInsertPosition.INSIDE, request.onCommitted)
            },
        )
    }

    pendingDockIndentSlots?.let { request ->
        EditorOptionDialog(
            title = "选择缩进分支",
            options = request.slots.map { slot -> slot to childBlockLabel(slot) },
            onDismiss = { pendingDockIndentSlots = null },
            onConfirm = { slot ->
                pendingDockIndentSlots = null
                if (!editorCanMutate(action != null || sourceBusy, editor.isReadOnly, currentRuntimeState.engineState) ||
                    !request.matches(projectId, selectedFlowId, editor) || slot !in request.slots) {
                    error = "插件、选择或运行状态已变化，请重新选择缩进分支"
                    return@EditorOptionDialog
                }
                finishDockMutation(editor.indentSelection(slot), "节点已缩进到${childBlockLabel(slot)}")
            },
        )
    }

    pendingDockPaste?.let { request ->
        val slots = request.primary?.let(editor::childBlockNames).orEmpty()
        EditorOptionDialog(
            title = "选择粘贴位置",
            options = editorPasteOptions(slots),
            onDismiss = { pendingDockPaste = null },
            preview = { destination -> EditorInsertionPreview(programNodes.toEditorTreeNodes(), request.primary,
                if (destination.childSlot != null) EditorInsertPosition.INSIDE
                else if (destination.before) EditorInsertPosition.ABOVE else EditorInsertPosition.BELOW, destination.childSlot) },
            onConfirm = { destination ->
                pendingDockPaste = null
                pasteDockClipboard(destination, request)
            },
        )
    }

    pendingBlockConfiguration?.let { pending ->
        fun commitConfigured(arguments: JsonObject): String? = applyConfiguredEditorInsertion(pending.context,
            projectId, selectedFlowId, editor, pending.contract, arguments, pending.position, pending.childSlot,
            snapshot.manifest.capabilities.toSet(),
            editorCanMutate(runtimeBusy || sourceBusy, editor.isReadOnly, currentRuntimeState.engineState))
        if (pending.contract.kind in visualUtilityKinds) VisualUtilityArguments(
            pending.contract,pending.arguments,snapshot,selectedFlowId,{showVariableManager=true},
            {pendingBlockConfiguration=null},onConfirm=confirm@{configured ->
                commitConfigured(configured)?.let { error = it; return@confirm }
                editorRevision++;pendingBlockConfiguration=null;error=null;notice="已加入：${pending.contract.title}"
                pending.onCommitted()
                saveCurrent()
            },confirmLabel=if(pending.position==EditorInsertPosition.REPLACE) "保存" else "加入",
        ) else if (pending.contract.kind in setOf("ui.confirm", "ui.dialog")) VisualScriptDialogArguments(
            pending.contract, pending.arguments, snapshot, selectedFlowId, { showVariableManager = true },
            { pendingBlockConfiguration = null }, onConfirm = confirm@{ configured ->
                commitConfigured(configured)?.let { error = it; return@confirm }
                editorRevision++; pendingBlockConfiguration = null; error = null
                notice = "已加入：${pending.contract.title}"
                pending.onCommitted()
                saveCurrent()
            }, confirmLabel = if (pending.position == EditorInsertPosition.REPLACE) "保存" else "加入",
        ) else if (pending.contract.kind == "ui.get") VisualUiParameterDialog(
            pending.arguments, snapshot.manifest.runnerUi, snapshot.manifest.variables, selectedFlowId,
            snapshot.manifest.flows, { showVariableManager = true }, { pendingBlockConfiguration = null },
            onConfirm = confirm@{ configured ->
                commitConfigured(configured)?.let { return@confirm it }
                editorRevision++; pendingBlockConfiguration = null; error = null
                notice = "已加入：${pending.contract.title}"
                pending.onCommitted()
                saveCurrent()
                null
            }, confirmLabel = if (pending.position == EditorInsertPosition.REPLACE) "保存" else "加入",
        ) else VisualVariableConditionDialog(
            contract = pending.contract,
            inputs = conditionPropertyTexts(pending.contract, pending.arguments),
            knownVariables = knownVariables,
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
                commitConfigured(configured)?.let { return@VisualVariableConditionDialog it }
                editorRevision++
                pendingBlockConfiguration = null
                error = null
                notice = "已加入：${pending.contract.title}"
                pending.onCommitted()
                saveCurrent()
                null
            },
        )
    }

    if (runtimeVariableWatchOpen) RuntimeVariableInspectorDialog(snapshot, selectedFlowId, runtimeClient,
        onDismiss = { runtimeVariableWatchOpen = false }, restoreRequest = runtimeVariableRestoreRequest)

    if (variableCheckVisible) {
        VariableCheckDialog(snapshot, selectedFlowId, editor, runtimeClient,
            onDismiss = { variableCheckVisible = false })
    }

    fun queueImageInsertion(blocks: List<Pair<BlockContract, JsonObject>>, origin: ImageInsertionOrigin) {
        val missing = blocks.flatMap { it.first.requiredCapabilities }.toSet() - snapshot.manifest.capabilities.toSet()
        if (missing.isNotEmpty()) error = "识别需要能力：${missing.joinToString()}"
        else if (!editorCanMutate(runtimeBusy || sourceBusy, editor.isReadOnly, currentRuntimeState.engineState)) error = "请停止运行后再加入积木"
        else pendingImageInsertion = ImageBlockInsertionRequest.create(projectId, selectedFlowId, editor, blocks, origin,
            preferredChildSlot = selectedBranch?.slot)
    }
    fun queueImageWorkflow(trees:List<VisualBlockTree>) {
        if(editorCanMutate(runtimeBusy || sourceBusy,editor.isReadOnly,currentRuntimeState.engineState))
            pendingImageInsertion=ImageBlockInsertionRequest.createTrees(projectId,selectedFlowId,editor,trees,ImageInsertionOrigin.CONFIGURATION,
                preferredChildSlot = selectedBranch?.slot)
        else error="运行或编辑状态已变化，未加入"
    }
    fun workbenchTarget(): ImageWorkbenchInsertionTarget {
        val targetFlow = selectedFlowId
        val targetEditor = editor
        return validatedWorkbenchTarget(store, runtimeClient, projectId, targetFlow, targetEditor,
            currentSnapshot = { currentInputSnapshot }, drafts = { editors.mapValues { it.value.currentSource } },
            canApply = { selectedFlowId == targetFlow && editors[targetFlow] === targetEditor &&
                editorCanMutate(action != null || sourceBusy, targetEditor.isReadOnly, runtimeClient.runtimeStates.value.engineState) },
            onManageVariables = { showVariableManager = true }, onCommitted = {
                editorRevision++; error = null; notice = "处理流水线已加入，可一次撤销"
                saveCurrent()
            }, preferredChildSlot = selectedBranch?.slot)
    }
    if (showGlyphMaker) GlyphMakerDialog(snapshot, store, runtimeClient, onSnapshotChanged,
        onDismiss = { showGlyphMaker = false }, onUseSaved = { updated, config, _ ->
            val contract = requireNotNull(BlockCatalog.find("ocr.glyph"))
            val missing = contract.requiredCapabilities.toSet() - updated.manifest.capabilities.toSet()
            if (missing.isNotEmpty()) error = "字库已保存；识字需要能力：${missing.joinToString()}"
            else {
                // This maker may use a project image: its sample ROI is NOT a screen rectangle.
                pendingImageBlock = contract to config.applyGlyphSettings(initialRecognitionArguments(contract, updated))
                recognitionCaptureSelection = null; recognitionCaptureTemplatePath = null
                notice = "已带入新字库及取色参数，请确认屏幕范围和输出变量"
            }
            showGlyphMaker = false
        })
    if (showRecognitionTest) {
        ImageRecognitionTestDialog(snapshot, runtimeClient,
            visible = !showingInputPointPicker && pendingImageInsertion == null,
            onDismiss = { showRecognitionTest = false },
            onCapture = { openImageTools(0L) },
            onInsert = { arguments ->
                val blocks = recognitionBlockSequence(arguments)
                queueImageInsertion(blocks, ImageInsertionOrigin.PREVIEW)
            },
            onInsertRecognition = { kind, arguments ->
                val blocks = ocrRecognitionSequence(kind, arguments)
                queueImageInsertion(blocks, ImageInsertionOrigin.PREVIEW)
            },
            onConfigureResultReader = { arguments -> pendingImageBlock = requireNotNull(BlockCatalog.find("vision.readresult")) to arguments },
        )
    }
    if (showImageLibrary) {
        ImageLibraryDialog(snapshot, store, runtimeClient, onSnapshotChanged,
            onDismiss = { showImageLibrary = false }, onCapture = { openImageTools(0L) }, insertionTarget = workbenchTarget(),
            onFindImageUsages = { path ->
                val drafts = editors.mapValues { it.value.currentSource }
                val report = withContext(Dispatchers.IO) { store.findResourceUsages(projectId, path, drafts) { ensureActive() } }
                check(editors.mapValues { it.value.currentSource } == drafts) { "编辑内容已变化，请重新查找使用位置" }
                report
            },
            onDeleteImage = { path, expected ->
                check(editorCanMutate(action != null || sourceBusy, editor.isReadOnly, runtimeClient.runtimeStates.value.engineState)) {
                    "请停止运行并完成当前编辑操作后再删除图片"
                }
                val drafts = editors.mapValues { it.value.currentSource }
                sourceBusy = true
                try {
                    withContext(Dispatchers.IO) { store.deleteResource(projectId, path, expected, drafts) }
                } finally { sourceBusy = false }
            })
    }

    pendingTextTool?.let { (contract, initial) ->
        TextToolInsertDialog(contract, initial, snapshot, workbenchTarget(), onDismiss = { pendingTextTool = null },
            onMessage = { notice = it })
    }
    if (showRecognitionReader) RecognitionResultReaderDialog(snapshot, selectedFlowId,
        visible = pendingImageInsertion == null, onManageVariables = { showVariableManager = true },
        onDismiss = { showRecognitionReader = false },
        onConfirm = { queueImageInsertion(it, ImageInsertionOrigin.CONFIGURATION) })
    pendingImageBlock?.let { (contract, initialArgs) ->
        EditorImageBlockDialog(contract, initialArgs, snapshot, selectedFlowId, editor, store, runtimeClient,
            visible = !showingInputPointPicker && pendingImageInsertion == null,
            captureSelection = recognitionCaptureSelection, captureTemplatePath = recognitionCaptureTemplatePath,
            onManageVariables = { showVariableManager = true }, onWorkflow = ::queueImageWorkflow,
            onInsert = { queueImageInsertion(it, ImageInsertionOrigin.CONFIGURATION) },
            onCapture = { mode ->
                recognitionCaptureSelection = null; recognitionCaptureTemplatePath = null
                recognitionCaptureActive = true; openImageToolMode(mode, 0L)
            }, onDismiss = {
                pendingImageBlock = null; recognitionCaptureSelection = null; recognitionCaptureTemplatePath = null
            })
    }

    pendingImageInsertion?.let { request ->
        if (!imageInsertionBusy) ImageInsertionPositionDialog(request, editor, onDismiss = { pendingImageInsertion = null },
            onConfirm = confirm@{ position, slot ->
                if (!editorCanMutate(runtimeBusy || sourceBusy, editor.isReadOnly, currentRuntimeState.engineState)) {
                    error = "插件、选择、能力或运行状态已变化，未加入；请取消后重新选择"
                    return@confirm
                }
                val target = workbenchTarget()
                imageInsertionBusy = true
                scope.launch {
                    editorOperation({ error = it.message ?: "校验失败，原草稿保留" }, { imageInsertionBusy = false }) {
                        val problem = target.apply(request, position, slot)
                        if (problem != null) { error = problem; return@editorOperation }
                        if (request.origin == ImageInsertionOrigin.CONFIGURATION) {
                            pendingImageBlock = null; showRecognitionReader = false
                        } else showRecognitionTest = false
                        pendingImageInsertion = null
                        recognitionCaptureSelection = null; recognitionCaptureTemplatePath = null
                        notice = "图像积木已整批校验加入，可一次撤销"
                    }
                }
            })
    }
    pendingRecognitionInsert?.let { request ->
        VisualImageRecognitionDialog(
            request.contract, request.arguments, snapshot.manifest.resources,
            knownVariables, snapshot,
            confirmLabel = if (request.position == EditorInsertPosition.REPLACE) "确定" else "加入",
            applyInitialTemplateRegion = request.position != EditorInsertPosition.REPLACE,
            currentFlowId = selectedFlowId,
            onManageVariables = { showVariableManager = true },
            visible = !showingInputPointPicker, captureSelection = recognitionCaptureSelection,
            runtimeClient = runtimeClient,
            captureTemplatePath = recognitionCaptureTemplatePath,
            onPickFromScreen = { mode ->
                recognitionCaptureSelection = null; recognitionCaptureTemplatePath = null
                recognitionCaptureActive = true; openImageToolMode(mode, 0L)
            },
            onDismiss = { pendingRecognitionInsert = null; recognitionCaptureSelection = null; recognitionCaptureTemplatePath = null },
            onConfirm = { _, configured ->
                val problem = applyConfiguredEditorInsertion(request.context, projectId, selectedFlowId, editor,
                    request.contract, configured, request.position, request.childSlot, snapshot.manifest.capabilities.toSet(),
                    editorCanMutate(runtimeBusy || sourceBusy, editor.isReadOnly, currentRuntimeState.engineState))
                if (problem == null) {
                    editorRevision++
                    pendingRecognitionInsert = null
                    recognitionCaptureSelection = null; recognitionCaptureTemplatePath = null
                    error = null
                    request.onCommitted()
                    saveCurrent()
                } else error = problem
            },
        )
    }

    // 顶栏和悬浮面板共用函数库；可直接加入或查看说明，能力不足仍可阅读但不能加入。
    if (showBlockPicker) {
        fun closePicker() {
            showBlockPicker = false
            blockPickerAutoSave = false
            insertionChildBlockName = null
            pickerBranchContext = null
        }
        fun pickerCommitted(kind: String) {
            updateFunctionUsage(context) { it.used(kind) }
            insertionChildBlockName = null; pickerBranchContext = null
        }
        FunctionLibraryDialog(
            groups = FunctionCatalog.visualGroups(),
            capabilities = snapshot.manifest.capabilities.toSet(),
            onDismiss = ::closePicker,
            onInsert = { hint ->
                val contract = FunctionCatalog.blockKindOf(hint)?.let(BlockCatalog::find)
                if (insertionChildBlockName != null && pickerBranchContext?.matches(projectId, selectedFlowId, editor) != true) {
                    error = "原分支、插件或选择已变化，请关闭函数库后重新选择分支"
                } else if (contract == null) {
                    error = "未找到对应积木"
                } else {
                    val args = if (contract.kind in visualRecognitionKinds) initialRecognitionArguments(contract, snapshot)
                        else initialBlockArguments(contract, snapshot.manifest.flows, snapshot.manifest.resources, selectedFlowId)
                    if (args == null) {
                        error = "缺少积木所需的目标插件或项目资源"
                    } else if (contract.kind in visualRecognitionKinds) {
                        pendingRecognitionInsert = RecognitionInsertRequest(contract, args,
                            editorInsertionContext(projectId, selectedFlowId, editor), insertionChildBlockName, onCommitted = { pickerCommitted(contract.kind) })
                        recognitionCaptureSelection = null; recognitionCaptureTemplatePath = null
                    } else if (contract.kind in visualUtilityKinds || contract.kind in setOf("variable.set", "variable.copy", "variable.calculate", "control.if", "ui.get", "ui.confirm", "ui.dialog")) {
                        // The library is another insertion entry point: never silently add a
                        // placeholder `value == true` condition from here.
                        pendingBlockConfiguration = VisualPendingBlockInsert(
                            contract = contract,
                            arguments = args,
                            context = editorInsertionContext(projectId, selectedFlowId, editor),
                            childSlot = insertionChildBlockName,
                            position = EditorInsertPosition.BELOW,
                            onCommitted = { pickerCommitted(contract.kind) },
                        )
                    } else {
                        val problem = applyConfiguredEditorInsertion(editorInsertionContext(projectId, selectedFlowId, editor),
                            projectId, selectedFlowId, editor, contract, args, EditorInsertPosition.BELOW, insertionChildBlockName,
                            snapshot.manifest.capabilities.toSet(),
                            editorCanMutate(runtimeBusy || sourceBusy, editor.isReadOnly, currentRuntimeState.engineState))
                        if (problem != null) {
                            error = problem
                        } else {
                            editorRevision++
                            error = null
                            notice = "已加入：${contract.title}"
                            pickerCommitted(contract.kind)
                            if (blockPickerAutoSave) saveCurrent()
                        }
                    }
                }
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
                knownVariables = knownVariables,
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
                runtimeClient = runtimeClient,
                projectStore = store,
                contract = contract,
                arguments = arguments,
                flows = snapshot.manifest.flows,
                currentFlowId = selectedFlowId,
                resources = snapshot.manifest.resources,
                knownLabels = nodes.filter { it.kind == "control.label" && !editor.isNodeDisabled(it.nodeId) }
                    .mapNotNull { editor.nodeArguments(it.nodeId)?.get("name")?.asString },
                confirmLabel = if (reflectionPosition == null) "保存" else "加入",
                knownVariables = knownVariables,
                onManageVariables = { showVariableManager = true },
                imageProject = snapshot,
                onResourcesUpdated = onSnapshotChanged,
                applyInitialTemplateRegion = reflectionPosition != null,
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

    EditorDraftRecovery(snapshot, store, editors, editorRevision, runtimeClient,
        canApply = { editorCanMutate(action != null || sourceBusy, editor.isReadOnly, currentRuntimeState.engineState) },
        onRecovered = { selectedFlowId = it; editorRevision++; notice = "已恢复插件草稿，可撤销；请检查后保存" },
        onMessage = { notice = it })
    BackHandler(enabled = active, onBack = ::requestEditorExit)
    if (showInterfacePreview) ProjectInterfacePreview(snapshot.manifest.runnerUi, snapshot.directory) { showInterfacePreview = false }

    Box(modifier.fillMaxSize()) {
        if (!recordingActive) EditorDock(
            onOpenProjectSearch = { projectSearchAnchor = selectedFlowId; projectSearchFlows = frozenSearchFlows() },
            programLocateRequest = locateRequest,
            onBatchParameters = {
                val root = snapshot.manifest.flows.firstOrNull { it.flowId == selectedFlowId }?.rootBlockId
                val request = root?.let { editorBatchParameters(projectId, selectedFlowId, it, editor) }
                if (!editorCanMutate(runtimeBusy || sourceBusy, editor.isReadOnly, runtimeClient.runtimeStates.value.engineState)) error = "请停止运行并完成当前操作后再修改"
                else if (request == null) error = "请勾选2至256行同类型积木；支持公共数值、开关和安全选项，绑定变量的参数不参与批改"
                else {
                    batchBaseline = currentInputSnapshot
                    batchDrafts = editors.mapValues { it.value.currentSource }
                    pendingBatch = request
                }
            },
            logProjectId = projectId,
            onLocateRuntimeLog = { target ->
                val targetEditor = editors[target.flowId]
                when {
                    action != null || sourceBusy || pendingBlockConfiguration != null || pendingInputInsertion != null -> "请先完成或取消当前编辑操作，再定位日志"
                    targetEditor == null || snapshot.manifest.flows.none { it.flowId == target.flowId } -> "记录中的插件已不存在"
                    targetEditor.rows.none { it.nodeId == target.nodeId } -> "记录中的积木已不存在，当前草稿可能已修改"
                    else -> {
                        selectedFlowId = target.flowId
                        targetEditor.selectOnly(target.nodeId)
                        followExecution = false; locateRequest++
                        editorRevision++
                        notice = "已定位记录中的积木 · 当前草稿可能与运行时不同"
                        null
                    }
                }
            },
            projectName = snapshot.manifest.name,
            initiallyExpanded = true,
            onClose = ::requestEditorExit,
            onRun = { runProject(StudioRunEntry.EDITOR) },
            running = running,
            paused = runtimeState.engineState == RuntimeEngineState.PAUSED,
            resumeEnabled = canPauseResume && !stepState.pending && !stepState.loading,
            onPause = { controlRunningProject(pause = true) },
            onResume = {
                if (stepState.position == null) controlRunningProject(pause = false)
                else if (stepState.begin(continueRun = true)) scope.launch {
                    requestVisualStep(stepState, { error = it }) {
                        withContext(Dispatchers.IO) { runtimeClient.resumeDebugProject(projectId, runtimeState.sessionGeneration) }
                    }
                }
            },
            runEnabled = if (running) canPauseResume else canStartAction,
            stopEnabled = canStop,
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
            availableVariables = knownVariables,
            availableLabels = availableLabels,
            projectVariables = snapshot.manifest.variables,
            projectFlows = snapshot.manifest.flows,
            debugSettings = snapshot.manifest.debugSettings,
            onSaveDebugSettings = { settings, onSaved ->
                scope.launch {
                    if (!editorCanMutate(action != null || sourceBusy, editor.isReadOnly, currentRuntimeState.engineState)) {
                        error = "请停止运行并完成当前操作后再保存调试设置"
                        onSaved(Result.failure(IllegalStateException(error)))
                        return@launch
                    }
                    action = VisualAction.SAVING
                    var completed = false
                    fun complete(result: Result<com.autoscript.project.store.ProjectDebugSettings>) {
                        if (!completed) { completed = true; onSaved(result) }
                    }
                    editorOperation({
                        error = it.message ?: "保存调试设置失败"
                        complete(Result.failure(it))
                    }, {
                        action = null
                        complete(Result.failure(CancellationException("保存已取消")))
                    }) {
                        editorSaveCommit<ProjectSnapshot>(write = {
                            withContext(Dispatchers.IO) {
                                store.updateDebugSettings(
                                    projectId = projectId,
                                    debugSettings = settings,
                                    expected = snapshot.manifest.debugSettings,
                                )
                            }
                        }, acknowledge = { updated ->
                            onSnapshotChanged(updated)
                            notice = "调试设置已保存"
                            complete(Result.success(updated.manifest.debugSettings))
                        })
                    }
                }
            },
            onPluginAction = ::handlePluginAction,
            capabilities = snapshot.manifest.capabilities.toSet(),
            onOpenInputPointPicker = { mode -> openInputPointPicker(mode) },
            onOpenInputFloatingPicker = ::openInputFloatingPicker,
            inputPickOpenRequest = inputPickOpenRequest,
            inputBackendFeatures = inputBackendFeatures,
            onOpenPermissions = openSoftwarePermissions,
            onRefreshInputCapabilities = { inputCapabilityRevision++ },
            onOpenRecorder = if ((inputBackendFeatures ?: 0) and 4 != 0) {
                ::openRootRecorder
            } else null,
            onOpenImageTools = ::openImageTools,
            onOpenImageLibrary = { showImageLibrary = true },
            onTestRecognition = { showRecognitionTest = true },
            onOpenDebugTool = {
                if (it == EditorToolPanel.RUNTIME_VARIABLES) {
                    runtimeVariableRestoreRequest++; runtimeVariableWatchOpen = true
                } else if (it == EditorToolPanel.VARIABLE_CHECK) variableCheckVisible = true
            },
            onCreateImageBlock = { kind ->
                val contract = BlockCatalog.find(if(kind==COLOR_FILTER_ENTRY) "screen.transform" else kind)
                val missing = contract?.requiredCapabilities.orEmpty().toSet() - snapshot.manifest.capabilities.toSet()
                when {
                    kind == GLYPH_MAKER_ENTRY -> showGlyphMaker = true
                    kind == RECOGNITION_RESULT_READER -> showRecognitionReader = true
                    contract == null -> error = "缺少图像积木契约：$kind"
                    missing.isNotEmpty() -> error = "${contract.title}需要能力：${missing.joinToString()}"
                    kind in textExtractionKinds -> {
                        val args = initialRecognitionArguments(contract, snapshot)
                        pendingTextTool = contract to args
                    }
                    else -> {
                        val args = initialRecognitionArguments(contract, snapshot)
                        if(kind==COLOR_FILTER_ENTRY) {
                            args.addProperty("mode","filter");args.addProperty("width",0);args.addProperty("height",0)
                            args.addProperty("filter",com.autoscript.project.store.ProjectImageFilter().encode())
                        }
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
            programNodes = programNodes,
            programSelectedNodeId = editor.selectedNodeId,
            programCurrentNodeId = (executionPosition ?: stepState.position)?.takeIf { it.flowId == selectedFlowId }?.nodeId,
            programExecutionAvailable = executionPosition != null || stepState.position != null,
            programFollowExecution = followExecution,
            onProgramFollowExecution = { followExecution = it },
            executionRecent = executionPosition?.recent == true,
            executionStatus = when {
                error != null -> error
                stepState.pending -> stepState.pendingMessage
                runtimeState.engineState == RuntimeEngineState.PAUSED && stepState.loading -> "已暂停 · 正在读取执行位置…"
                runtimeState.engineState == RuntimeEngineState.PAUSED && stepState.position != null -> "已暂停 · 黄色 ▶ 为下一步；单步执行、继续恢复、停止结束"
                editor.isReadOnly -> "插件结构无效，只读查看"
                stopping -> "停止清理中 · 等待触点清理确认"
                action == VisualAction.STARTING -> "正在启动 · 保存、编译并等待 Root 就绪"
                else -> listOfNotNull(
                    runtimeReadinessProblem(runtimeState),
                    if (runFeedback == null) editorSaveSummary(editors.values.count { it.isDirty }, action == VisualAction.SAVING) else null,
                    notice ?: runtimeState.message,
                ).joinToString(" · ")
            },
            programSelectedNodeIds = editor.selectedNodeIds,
            programSelectionDisabled = remember(programNodes, editor.selectedNodeIds) {
                editorSelectionOwnDisabled(programNodes, editor.selectedNodeIds)
            },
            projectRandomization = snapshot.manifest.randomization,
            onProgramRandomizationUpdated = {nodeId,expected,settings ->
                when {
                    !editorCanMutate(action != null || sourceBusy,editor.isReadOnly,runtimeClient.runtimeStates.value.engineState) -> "请停止运行后再编辑"
                    editor.currentSource != expected || editor.selectedNodeId != nodeId -> "插件或选中行已变化，请重新打开"
                    !editor.updateNodeRandomization(nodeId,settings) -> "无法应用随机策略，请检查积木参数"
                    else -> {finishDockMutation(true,"已更新随机策略，可撤销");null}
                }
            },
            onProgramDisplayUpdated = { nodeId, expected, settings ->
                when {
                    !editorCanMutate(action != null || sourceBusy, editor.isReadOnly, runtimeClient.runtimeStates.value.engineState) -> "请停止运行后再编辑"
                    editor.currentSource != expected || editor.selectedNodeId != nodeId -> "插件或选中行已变化，请重新打开"
                    !settings.isValid() -> "显示别名无效"
                    else -> {
                        val normalized = if (settings.alias.isBlank()) com.autoscript.studio.compiler.NodeDisplaySettings() else settings
                        when {
                            editor.nodeDisplay(nodeId) == normalized -> null
                            !editor.updateNodeDisplay(nodeId, normalized) -> "无法应用显示设置，请检查插件大小与当前选中行"
                            else -> { finishDockMutation(true, "已更新积木显示，可撤销"); null }
                        }
                    }
                }
            },
            onProgramNodeSelectionToggled = { nodeId -> branchSelection = null; editor.toggleSelection(nodeId); editorRevision++ },
            onProgramSelectionChanged = { ids -> branchSelection = null; if (editor.selectNodes(ids)) editorRevision++ },
            editingEnabled = editorCanMutate(runtimeBusy, editor.isReadOnly, runtimeState.engineState),
            programSource = editor.currentSource,
            onAllocateProgramIdentity = editor::allocateProjectIdentity,
            onApplyProgramSource = { expected, replacement ->
                val targetFlow = selectedFlowId
                val targetManifest = currentInputSnapshot.manifest
                val targetDrafts = editors.mapValues { it.value.currentSource }
                val problem = applyValidatedPluginSource(runtimeClient, snapshot.manifest.projectId,
                    targetFlow.orEmpty(), editor, expected, replacement) {
                    selectedFlowId == targetFlow && currentInputSnapshot.manifest == targetManifest &&
                        editors.mapValues { it.value.currentSource } == targetDrafts &&
                        editorCanMutate(action != null || sourceBusy, editor.isReadOnly, runtimeClient.runtimeStates.value.engineState)
                }
                if (problem == null) finishDockMutation(true, "已应用插件源码，可撤销")
                problem
            },
            onReplaceProgramText = { query, replacement, selectedOnly ->
                if (!editorCanMutate(runtimeBusy, editor.isReadOnly, runtimeState.engineState)) "请停止运行后再替换"
                else {
                    val count = editor.replaceDisplayText(query, replacement, selectedOnly)
                    if (count == 0) "没有可替换的文字，或替换结果超过长度限制"
                    else { finishDockMutation(true, "已替换 $count 个积木的文字"); null }
                }
            },
            onShowInterface = { showInterfacePreview = true },
            stepEnabled = selectedBranch == null && !stepState.pending && !stepState.loading &&
                (runtimeState.engineState != RuntimeEngineState.PAUSED || stepState.position != null) &&
                editorCanStep(runtimeBusy, runtimeState.phase == RuntimeConnectionPhase.CONNECTED, runtimeState.engineState,
                    selectedExecutable),
            onProgramNodeSelected = { nodeId ->
                branchSelection = null
                editor.selectedNodeId = nodeId
                editorRevision++
            },
            programSelectedBranch = selectedBranch,
            onProgramBranchSelected = { ownerId, slot ->
                if (editorCanMutate(runtimeBusy, editor.isReadOnly, runtimeState.engineState) &&
                    nodes.any { it.nodeId == ownerId && slot in editorBranchSlots(it.kind, editor.childBlockNames(ownerId)) }) {
                    editor.selectOnly(ownerId)
                    branchSelection = EditorBranchSelection(ownerId, slot, editor.currentSource)
                    editorRevision++
                    notice = "已选择${childBlockLabel(slot)}，新操作将加入这里"
                }
            },
            onInsertIntoBranch = { hint, branch, committed ->
                if (branchSelection != branch || !branch.matches(editor)) error = "加入分支已变化，请重新选择"
                else insertDockBlock(hint, branch.slot, EditorInsertPosition.INSIDE, committed)
            },
            onProgramNodeDeleted = {
                if (selectedBranch == null && editorCanMutate(runtimeBusy, editor.isReadOnly, runtimeState.engineState) && editor.deleteSelection()) {
                    editorRevision++
                    error = null
                    notice = "已删除选中节点及其子树"
                    saveCurrent()
                } else error = "无法删除当前节点"
            },
            onProgramNodeEdited = {
                if (selectedBranch == null) editor.selectedNodeId?.let { id ->
                    openNodeParameters(selectedFlowId, id)?.let { notice = it }
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
                        context = editorInsertionContext(projectId, selectedFlowId, editor),
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
                if (selectedBranch != null) notice = "分支是加入位置，请先选择要单步执行的积木"
                else if (runtimeState.engineState == RuntimeEngineState.PAUSED) {
                    if (stepState.begin()) scope.launch {
                        requestVisualStep(stepState, { error = it }) {
                            withContext(Dispatchers.IO) { runtimeClient.requestStep(projectId, runtimeState.sessionGeneration) }
                        }
                    }
                } else if (editorCanStep(runtimeBusy, runtimeState.phase == RuntimeConnectionPhase.CONNECTED, runtimeState.engineState,
                        editor.selectedNodeId?.let { !editor.isNodeDisabled(it) } == true)) runProject(StudioRunEntry.SINGLE_STEP)
                else notice = "请先暂停或停止脚本，再执行单步"
            },
            onInsertPositioned = { legacyHint, position, committed ->
                val selectedRow = nodes.firstOrNull { it.nodeId == editor.selectedNodeId }
                val childSlots = selectedRow?.nodeId?.let(editor::childBlockNames).orEmpty()
                when {
                    position == EditorInsertPosition.INSIDE && childSlots.isEmpty() -> error = "当前选择行不能加入内部"
                    position == EditorInsertPosition.INSIDE && childSlots.size > 1 ->
                        pendingDockInsert = EditorDockInsertRequest(projectId, selectedFlowId, editor.currentSource,
                            editor.selectedNodeId, legacyHint, insideOnly = true, selection = editor.selectedNodeIds.toSet(), onCommitted = committed)
                    position == EditorInsertPosition.INSIDE -> insertDockBlock(legacyHint, childSlots.single(), position, committed)
                    else -> insertDockBlock(legacyHint, null, position, committed)
                }
            },
        onInsert = { legacyHint ->
                val selectedRow = nodes.firstOrNull { it.nodeId == editor.selectedNodeId }
                val childSlots = selectedRow?.nodeId?.let(editor::childBlockNames).orEmpty()
                when {
                    selectedRow == null || childSlots.isEmpty() -> {
                        insertDockBlock(legacyHint, null)
                    }
                    else -> pendingDockInsert = EditorDockInsertRequest(projectId, selectedFlowId, editor.currentSource,
                        editor.selectedNodeId, legacyHint, insideOnly = false, selection = editor.selectedNodeIds.toSet())
                }
                notice = "请选择要加入的积木和插入位置"
            },
        )
        if (showingInputPointPicker) {
            ImageToolWindow(
                snapshot = snapshot,
                projectStore = store,
                onWorkbenchSnapshotChanged = onSnapshotChanged,
                insertionTarget = workbenchTarget(),
                runtimeClient = runtimeClient,
                pointerInputSupported = (inputBackendFeatures ?: 0) and 2 != 0,
                onOpenRecorder = if (!recognitionCaptureActive && (inputBackendFeatures ?: 0) and 4 != 0 &&
                    "input.basic" in snapshot.manifest.capabilities) ::openRootRecorder else null,
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
                pixelSize = roi.width to roi.height,
                store = store,
                projectId = projectId,
                imagePaths = snapshot.manifest.resources.mapNotNull { it.get("path")?.asString },
                onDismiss = { pendingTemplateRoi = null },
                onSave = save@ { folder, name, mode ->
                    val source = inputPointBitmap
                    require(imageToolRequest && source != null) { "请先从图像入口截图" }
                    val sourceCopy = requireNotNull(source.copy(source.config ?: Bitmap.Config.ARGB_8888, false)) { "无法准备模板图片" }
                    val captureRequest = inputPointRequestId
                    val captureTarget = InputPickTarget(projectId, selectedFlowId, editor.currentSource, editor.selectedNodeId, selectedBranch?.slot)
                    try {
                        val (updated, path) = withContext(Dispatchers.IO) {
                            cropAndImportTemplate(store, snapshot, sourceCopy, roi, context, folder, name,
                                rememberScreenRegion = true, mode = mode)
                        }
                        onSnapshotChanged(updated)
                        if (captureRequest != inputPointRequestId || !showingInputPointPicker ||
                            captureTarget.flowId != selectedFlowId || captureTarget.source != editor.currentSource) {
                            notice = "模板已保存到图片库；选取上下文已变化，未自动回填"
                            return@save
                        }
                        pendingTemplateRoi = null
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
                    } finally { sourceCopy.recycle() }
                },
            )
        }
    }
    // Keep root errors after every tool/parameter window, including the first composition frame.
    error?.let { message ->
        val target = diagnosticTarget.takeIf { diagnosticMessage == message }
        EditorProblemDialog(message, diagnostic?.code.takeIf { diagnosticMessage == message }, target,
            canEdit = editorCanMutate(runtimeBusy || sourceBusy, editor.isReadOnly, runtimeState.engineState),
            onDismiss = { error = null }, onLocate = { edit ->
                val targetEditor = target?.let { editors[it.flowId] }
                when {
                    action != null || sourceBusy || pendingBlockConfiguration != null || pendingInputInsertion != null -> "请先完成当前编辑操作"
                    target == null || targetEditor?.currentSource != target.source -> "积木内容已变化，请重新校验，未猜测其他行"
                    targetEditor.rows.none { it.nodeId == target.nodeId } -> "这行积木已不存在"
                    edit && !editorCanMutate(runtimeBusy || sourceBusy, targetEditor.isReadOnly, runtimeClient.runtimeStates.value.engineState) -> "请先停止运行"
                    else -> {
                        selectedFlowId = target.flowId; targetEditor.selectOnly(target.nodeId)
                        followExecution = false; branchSelection = null; editorRevision++; locateRequest++
                        if (edit) parameterNavigation = target
                        error = null
                        null
                    }
                }
            })
    }
}

private fun formatDiagnostic(diagnostic: VisualCompileDiagnostic): String = buildString {
    append(diagnostic.message)
    val location = listOfNotNull(
        diagnostic.flowId?.let { "插件 $it" },
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
    val context: EditorPasteRequest,
    val childSlot: String?,
    val position: EditorInsertPosition,
    val onCommitted: () -> Unit = {},
)

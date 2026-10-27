package com.autoscript.studio

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.TextButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.zIndex
import androidx.compose.ui.geometry.Offset as GuideOffset
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import com.autoscript.studio.StudioDialog as Dialog
import androidx.compose.ui.window.DialogProperties
import com.autoscript.core.designsystem.AutoScriptPalette
import com.autoscript.core.designsystem.hairline
import com.autoscript.project.store.ProjectDebugSettings
import com.autoscript.project.store.ProjectVariable
import com.autoscript.project.store.ProjectFlow

/**
 * Shared editor content. The Studio system-overlay host supplies the native viewport;
 * Activity callers can still reuse it for bounded previews.
 */
@Composable
internal fun EditorDock(
    projectName: String,
    onRun: () -> Unit,
    onInsert: (String) -> Unit,
    /** Activity QA previews may start collapsed; the service owns production visibility. */
    initiallyExpanded: Boolean = false,
    onInsertPositioned: ((String, EditorInsertPosition, () -> Unit) -> Unit)? = null,
    sourceName: String? = null,
    programNodes: List<EditorProgramNode>,
    programSource: String? = null,
    onAllocateProgramIdentity: ((String, Set<String>) -> String)? = null,
    onReplaceProgramText: ((String, String, Boolean) -> String?)? = null,
    onApplyProgramSource: (suspend (String, String) -> String?)? = null,
    programSelectedNodeId: String? = null,
    programCurrentNodeId: String? = null,
    programExecutionAvailable: Boolean = programCurrentNodeId != null,
    programFollowExecution: Boolean? = null,
    onProgramFollowExecution: ((Boolean) -> Unit)? = null,
    executionRecent: Boolean = false,
    executionStatus: String? = null,
    onProgramNodeSelected: (String) -> Unit = {},
    programSelectedBranch: EditorBranchSelection? = null,
    onProgramBranchSelected: ((String, String) -> Unit)? = null,
    onInsertIntoBranch: ((String, EditorBranchSelection, () -> Unit) -> Unit)? = null,
    programSelectedNodeIds: Set<String> = emptySet(),
    onProgramNodeSelectionToggled: ((String) -> Unit)? = null,
    onProgramSelectionChanged: ((Set<String>) -> Unit)? = null,
    onProgramNodeDeleted: () -> Unit = {},
    onProgramNodeEdited: () -> Unit = {},
    onProgramNodeReflected: ((String, EditorInsertPosition, String?) -> Unit)? = null,
    onProgramNodeAnnotated: (() -> Unit)? = null,
    onProgramDisplayUpdated: ((String, String, com.autoscript.studio.compiler.NodeDisplaySettings) -> String?)? = null,
    onProgramRandomizationUpdated: ((String, String, com.google.gson.JsonObject?) -> String?)? = null,
    projectRandomization: com.autoscript.project.store.ProjectRandomization = com.autoscript.project.store.ProjectRandomization(),
    programSelectionDisabled: Boolean? = null,
    onProgramCommand: (EditorProgramCommand) -> Unit = {},
    onBatchParameters: (() -> Unit)? = null,
    onOpenProjectSearch: (() -> Unit)? = null,
    programLocateRequest: Int = 0,
    canUndoProgram: Boolean = false,
    canRedoProgram: Boolean = false,
    hasProgramClipboard: Boolean = false,
    onStep: () -> Unit = {},
    stepEnabled: Boolean = true,
    onShowInterface: (() -> Unit)? = null,
    consoleLines: List<String> = emptyList(),
    logProjectId: String? = null,
    onClose: () -> Unit = {},
    /** 标题栏左侧的设置入口，由宿主打开当前项目设置。 */
    onOpenSettings: () -> Unit,
    editingEnabled: Boolean = true,
    /** 正式插件文件树；没有项目目录的 Activity QA 预览可不提供。 */
    sourceTree: SourceFileTree? = null,
    currentFlowId: String? = null,
    onLocateRuntimeLog: ((RuntimeLogLocation) -> String?)? = null,
    sourceBusy: Boolean = false,
    sourceMessage: String? = null,
    onSourceAction: (SourceManagerAction) -> Unit = {},
    /** 编辑器右侧首项：维护项目变量。 */
    onManageVariables: () -> Unit = {},
    /** 当前编辑上下文可选的变量；循环等旧面板通过它弹出真实变量列表。 */
    availableVariables: List<String> = emptyList(),
    /** Current plugin's root-level labels, for the jump picker. */
    availableLabels: List<String> = emptyList(),
    projectVariables: List<ProjectVariable> = emptyList(),
    projectFlows: List<ProjectFlow> = emptyList(),
    /** Persisted project debug configuration; Root-only options remain fixed by the Runner. */
    debugSettings: ProjectDebugSettings = ProjectDebugSettings(),
    onSaveDebugSettings: (ProjectDebugSettings, (Result<ProjectDebugSettings>) -> Unit) -> Unit = { _, complete ->
        complete(Result.failure(IllegalStateException("当前没有可保存的项目")))
    },
    /** 插件管理中的检错/未调用等需要宿主执行的动作会回调。 */
    onPluginAction: (PluginManagerAction) -> Unit = {},
    capabilities: Set<String> = emptySet(),
    /** Editor ball restores the editor regardless of execution state. */
    running: Boolean = false,
    paused: Boolean = false,
    resumeEnabled: Boolean = false,
    onResume: () -> Unit = {},
    onPause: () -> Unit = {},
    runEnabled: Boolean = true,
    stopEnabled: Boolean = runEnabled,
    onStop: () -> Unit = {},
    /** “更多 → 录制动作”的真实录制入口；未提供时禁用，不显示模拟预览。 */
    onOpenRecorder: (() -> Unit)? = null,
    /** 图像取材使用的截图工具悬浮窗。 */
    onOpenImageTools: ((Long) -> Unit)? = null,
    /** 可视化“图像”面板直接配置并插入正式图像积木。 */
    onCreateImageBlock: ((String) -> Unit)? = null,
    /** 按键面板的截图定位入口；进入时指定单击或滑动模式。 */
    onOpenInputPointPicker: ((ImageToolMode) -> Unit)? = null,
    onOpenInputFloatingPicker: ((ImageToolMode) -> Unit)? = null,
    inputPickOpenRequest: Int = 0,
    inputBackendFeatures: Int? = null,
    onOpenPermissions: (() -> Unit)? = null,
    onRefreshInputCapabilities: (() -> Unit)? = null,
    /** 图像入口中的项目图片库，复用现有处理工作台。 */
    onOpenImageLibrary: (() -> Unit)? = null,
    onTestRecognition: (() -> Unit)? = null,
    onOpenDebugTool: ((EditorToolPanel) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val overlayHost = LocalEditorOverlay.current
    var localExpanded by rememberSaveable(projectName) { mutableStateOf(initiallyExpanded) }
    // The system service and Compose must read the same panel state, including while the window is hidden.
    val expanded = overlayHost?.panelExpanded ?: localExpanded
    fun setExpanded(value: Boolean) {
        if (overlayHost != null) overlayHost.panel(value) else localExpanded = value
    }
    var functionLibrary by rememberSaveable(projectName) { mutableStateOf(false) }
    var toolDialog by rememberSaveable(projectName) { mutableStateOf<EditorToolPanel?>(null) }
    var sourceManagerVisible by rememberSaveable(projectName) { mutableStateOf(false) }
    var pluginManagerVisible by rememberSaveable(projectName) { mutableStateOf(false) }
    var createPluginRequested by rememberSaveable(projectName) { mutableStateOf(false) }
    var selectCurrentPluginRequested by rememberSaveable(projectName) { mutableStateOf(false) }
    var consoleVisible by rememberSaveable(projectName) { mutableStateOf(false) }
    val statusText = editorDockStatusText(executionStatus, dismissed = false)
    var statusDismissed by rememberSaveable(projectName, statusText) { mutableStateOf(false) }
    var collapsedProgramNodeIds by rememberSaveable(projectName, sourceName) {
        mutableStateOf<List<String>>(emptyList())
    }
    var localFollowExecution by remember(projectName, running) { mutableStateOf(true) }
    val followExecution = programFollowExecution ?: localFollowExecution
    fun setFollowExecution(value: Boolean) {
        if (onProgramFollowExecution != null) onProgramFollowExecution(value) else localFollowExecution = value
    }
    var focusedNodeId by rememberSaveable(projectName, sourceName) { mutableStateOf<String?>(null) }
    var searchingProgram by rememberSaveable(projectName, sourceName) { mutableStateOf(false) }
    var nodeEditorVisible by rememberSaveable(projectName, sourceName) { mutableStateOf(false) }
    var nodeDisplayTarget by remember(projectName, sourceName) { mutableStateOf<NodeDisplayEditTarget?>(null) }
    var nodeRandomizationTarget by remember(projectName, sourceName) { mutableStateOf<NodeRandomizationEditTarget?>(null) }
    var nodeActionsVisible by rememberSaveable(projectName, sourceName) { mutableStateOf(false) }
    var sourceEditorVisible by remember(projectName, sourceName) { mutableStateOf(false) }
    var selectedNodeSourceId by remember(projectName, sourceName) { mutableStateOf<String?>(null) }
    var inputActionDialogVisible by rememberSaveable(projectName, sourceName) { mutableStateOf(false) }
    var moreMenuVisible by rememberSaveable(projectName, sourceName) { mutableStateOf(false) }
    var reflectionVisible by rememberSaveable(projectName, sourceName) { mutableStateOf(false) }
    var reflectionNodeId by remember { mutableStateOf<String?>(null) }
    var reflectionSource by remember { mutableStateOf<String?>(null) }
    var reflectionBranchVisible by remember { mutableStateOf(false) }
    var deleteRequest by remember { mutableStateOf<Pair<List<EditorTreeNode>, Set<String>>?>(null) }
    var deleteSource by remember { mutableStateOf<String?>(null) }
    var moreAnchor by remember { mutableStateOf<Rect?>(null) }
    var dockOrigin by remember { mutableStateOf(Offset.Zero) }
    var pendingInsertSnippet by remember(projectName, sourceName) { mutableStateOf<EditorPendingSnippet?>(null) }
    var insertionError by remember(projectName, currentFlowId) { mutableStateOf<String?>(null) }
    SideEffect {
        // More stays inside the existing panel. Full native viewport belongs only to the image canvas.
        overlayHost?.dockLayout(false)
    }
    LaunchedEffect(inputPickOpenRequest) {
        if (inputPickOpenRequest > 0) setExpanded(true)
    }
    val displayedTreeNodes = remember(programNodes, collapsedProgramNodeIds) {
        programNodes.toEditorTreeNodes(collapsedProgramNodeIds)
    }
    val effectiveFocusId = focusedNodeId?.takeIf { id -> displayedTreeNodes.any { it.id == id && it.container } }
    val focusPath = remember(displayedTreeNodes, effectiveFocusId) { editorFocusPath(displayedTreeNodes, effectiveFocusId) }
    fun setFocus(id: String?) {
        focusedNodeId = id
        // Focusing is manual browsing, not an instruction to change the running script.
        if (id != null && programExecutionAvailable) setFollowExecution(false)
        if (id != null) collapsedProgramNodeIds = collapsedProgramNodeIds - id
    }
    val selectedBranch = programSelectedBranch?.takeIf { branch ->
        branch.source == programSource && branch.ownerId == programSelectedNodeId &&
            programNodes.any { it.nodeId == branch.ownerId && branch.slot in editorBranchSlots(it.kind, it.childSlots) }
    }
    val displayedSelectedNodeId = programSelectedNodeId.takeIf { selectedBranch == null }
    LaunchedEffect(programLocateRequest) {
        if (programLocateRequest > 0) {
            focusedNodeId = null
            collapsedProgramNodeIds = collapsedProgramNodeIds - editorCollapsedAncestorIds(displayedTreeNodes, programSelectedNodeId).toSet()
        }
    }
    fun submitSnippet(snippet: String, onCommitted: () -> Unit = {}) {
        val branch = selectedBranch
        if (branch != null && onInsertIntoBranch != null) onInsertIntoBranch(snippet, branch, onCommitted)
        else pendingInsertSnippet = EditorPendingSnippet(snippet, onCommitted, currentFlowId, programSource,
            programSelectedNodeId, programSelectedNodeIds.toSet())
    }
    val displayedSourceName = sourceName
    fun toggleNodeExpansion(nodeId: String) {
        val nodeIndex = displayedTreeNodes.indexOfFirst { it.id == nodeId }
        if (nodeIndex >= 0 && editorTreeHasChildren(displayedTreeNodes, nodeIndex)) {
            val node = displayedTreeNodes[nodeIndex]
            collapsedProgramNodeIds = if (node.expanded) {
                (collapsedProgramNodeIds + nodeId).distinct()
            } else collapsedProgramNodeIds - nodeId
            if (node.expanded && (editorTreeIsDescendant(displayedTreeNodes, nodeIndex, programSelectedNodeId) ||
                    selectedBranch?.ownerId == nodeId)) {
                onProgramNodeSelected(node.id)
            }
        }
    }
    LaunchedEffect(programSelectedNodeId, programCurrentNodeId, sourceName, followExecution, effectiveFocusId) {
        if (followExecution && programCurrentNodeId != null && effectiveFocusId != null &&
            editorFocusedTreeNodes(displayedTreeNodes, effectiveFocusId).none { it.id == programCurrentNodeId }) focusedNodeId = null
        if (collapsedProgramNodeIds.isNotEmpty()) {
            val target = if (programCurrentNodeId != null) programCurrentNodeId.takeIf { followExecution } else programSelectedNodeId
            val ancestors = editorCollapsedAncestorIds(displayedTreeNodes, target).toSet()
            collapsedProgramNodeIds = collapsedProgramNodeIds.filterNot { it in ancestors }
        }
    }
    val effectiveSelection = if (selectedBranch != null) emptySet()
        else programSelectedNodeIds.ifEmpty { setOfNotNull(displayedSelectedNodeId) }
    fun editSelectedParameters() {
        nodeActionsVisible = false
        if (!editingEnabled || effectiveSelection.size != 1) return
        onProgramNodeEdited()
    }
    fun reuseSelectedParameters() {
        nodeActionsVisible = false
        if (!editingEnabled || effectiveSelection.size != 1) return
        if (onProgramNodeReflected != null) {
            reflectionNodeId = displayedSelectedNodeId
            reflectionSource = programSource
            reflectionVisible = true
        } else editSelectedParameters()
    }
    fun requestDelete() {
        if (editingEnabled && effectiveSelection.isNotEmpty()) { deleteSource = programSource; deleteRequest = displayedTreeNodes to effectiveSelection }
    }
    LaunchedEffect(editingEnabled, running) {
        if (running) {
            nodeEditorVisible = false; toolDialog = null; functionLibrary = false
            nodeActionsVisible = false
            sourceEditorVisible = false
            selectedNodeSourceId = null
            inputActionDialogVisible = false; deleteRequest = null
            reflectionVisible = false; reflectionBranchVisible = false
        }
    }
    deleteRequest?.let { (originalNodes, originalSelection) ->
        val valid = editingEnabled && displayedTreeNodes == originalNodes && effectiveSelection == originalSelection && programSource == deleteSource
        val count = editorSelectionImpact(originalNodes, originalSelection).affected
        StudioConfirmDialog(onDismissRequest = { deleteRequest = null }, title = { Text("删除积木") },
            text = { Text(if (valid) "删除选中积木及其包含的子积木，共 $count 项？删除后可以撤销。" else "程序或选择已变化，请取消后重新选择。") },
            dismissButton = { TextButton(onClick = { deleteRequest = null }) { Text("取消") } },
            confirmButton = { TextButton(enabled = valid, onClick = {
                deleteRequest = null
                onProgramNodeDeleted()
            }) { Text("删除") } })
    }

    LaunchedEffect(programNodes) {
        val activeIds = programNodes.asSequence().map(EditorProgramNode::nodeId).toHashSet()
        collapsedProgramNodeIds = collapsedProgramNodeIds.filter(activeIds::contains)
        if (focusedNodeId != null && displayedTreeNodes.none { it.id == focusedNodeId && it.container }) focusedNodeId = null
    }

    BackHandler(enabled = moreMenuVisible || expanded) {
        when {
            moreMenuVisible -> moreMenuVisible = false
            else -> setExpanded(false)
        }
    }

    BoxWithConstraints(modifier.fillMaxSize().onGloballyPositioned { dockOrigin = it.positionInRoot() }) {
        val density = LocalDensity.current
        val ballSizePx = with(density) { 35.dp.toPx() }
        val maxWidthPx = constraints.maxWidth.toFloat()
        val maxHeightPx = constraints.maxHeight.toFloat()
        val workArea = overlayHost?.workArea ?: Rect(0f, 0f, maxWidthPx, maxHeightPx)
        val ballPeekPx = with(density) { 10.dp.toPx() }
        var ballX by rememberSaveable(projectName) { mutableFloatStateOf(Float.NaN) }
        var ballY by rememberSaveable(projectName) { mutableFloatStateOf(Float.NaN) }
        var panelX by rememberSaveable(projectName) { mutableFloatStateOf(with(density) { 4.dp.toPx() }) }
        var panelY by rememberSaveable(projectName) { mutableFloatStateOf(Float.NaN) }
        var panelWidthPx by rememberSaveable(projectName) { mutableFloatStateOf(with(density) { EDITOR_DEFAULT_WIDTH_DP.dp.toPx() }) }
        // Smaller saved windows retain their size; side actions scroll instead of losing the AI entry.
        var panelHeightPx by rememberSaveable(projectName) { mutableFloatStateOf(with(density) { EDITOR_DEFAULT_HEIGHT_DP.dp.toPx() }) }
        var panelResized by rememberSaveable(projectName) { mutableStateOf(false) }
        var panelMode by rememberSaveable(projectName) { mutableStateOf(EditorPanelMode.NORMAL) }
        val normalSize = editorPanelSize(workArea, density.density,
            if (panelResized) Size(panelWidthPx, panelHeightPx) else null, Size(maxWidthPx, maxHeightPx))
        val normalPanelWidthPx = normalSize.width
        val normalPanelHeightPx = normalSize.height
        val dragArea = editorPanelDragArea(workArea, normalPanelWidthPx, normalPanelHeightPx)
        val minPanelX = workArea.left
        val minPanelY = workArea.top
        val maxPanelX = dragArea.right
        val maxPanelY = dragArea.bottom
        val maxBallX = (maxWidthPx - ballSizePx + ballPeekPx).coerceAtLeast(-ballPeekPx)
        val maxBallY = (maxHeightPx - ballSizePx).coerceAtLeast(0f)
        val resolvedBallX = (if (ballX.isNaN()) -ballPeekPx else ballX).coerceIn(-ballPeekPx, maxBallX)
        val resolvedBallY = (if (ballY.isNaN()) maxBallY / 2f else ballY).coerceIn(0f, maxBallY)
        val normalPanelX = panelX.coerceIn(minPanelX, maxPanelX)
        val normalPanelY = if (panelY.isNaN()) {
            (workArea.bottom - normalPanelHeightPx - with(density) { 76.dp.toPx() }).coerceAtLeast(minPanelY)
        } else panelY.coerceIn(minPanelY, maxPanelY)
        val normalPlacement = EditorPanelPlacement(normalPanelX, normalPanelY, normalPanelWidthPx, normalPanelHeightPx)
        val panelPlacement = editorPanelDisplayBounds(normalPlacement, panelMode, maxWidthPx, maxHeightPx, workArea)
        val effectivePanelX = panelPlacement.x
        val resolvedPanelY = panelPlacement.y
        val effectivePanelWidthPx = panelPlacement.width
        val effectivePanelHeightPx = panelPlacement.height

        if (!expanded && overlayHost == null) {
            // 编辑与运行使用同一个标志；停止必须通过展开后的明确按钮触发。
            Box(
                modifier = Modifier
                    .offset {
                        IntOffset(
                            resolvedBallX.roundToInt(),
                            resolvedBallY.roundToInt(),
                        )
                    }
                    .size(35.dp)
                    .onGloballyPositioned { overlayHost?.bounds(it.boundsInRoot()) }
                    .pointerInput(projectName, maxWidthPx, maxHeightPx) {
                        detectDragGestures(
                            onDragEnd = {
                                // 松手时按当前状态判断吸附方向；不能用组合时捕获的 resolvedBallX，那是拖动前的位置。
                                val currentX = if (ballX.isNaN()) -ballPeekPx else ballX
                                val center = currentX + ballSizePx / 2f
                                ballX = if (center < maxWidthPx / 2f) -ballPeekPx else maxWidthPx - ballSizePx + ballPeekPx
                            },
                        ) { change, dragAmount ->
                            change.consume()
                            val currentX = if (ballX.isNaN()) resolvedBallX else ballX
                            val currentY = if (ballY.isNaN()) resolvedBallY else ballY
                            ballX = (currentX + dragAmount.x).coerceIn(-ballPeekPx, maxBallX)
                            ballY = (currentY + dragAmount.y).coerceIn(0f, maxBallY)
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize().clickable {
                        setExpanded(true)
                    },
                    color = Color.White,
                    shape = RoundedCornerShape(18.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFE8E8F3)),
                    shadowElevation = 5.dp,
                ) {
                    EditorBallIcon(Modifier.fillMaxSize(), "智构 · 打开编辑器")
                }
            }
        } else if (expanded) {
            EditorDockPanel(
                projectName = projectName,
                title = displayedSourceName ?: if (sourceTree != null) "选择插件文件" else projectName,
                canSelectFlow = editingEnabled && sourceTree != null,
                nodes = displayedTreeNodes,
                selectedNodeId = displayedSelectedNodeId,
                currentNodeId = programCurrentNodeId,
                followExecution = followExecution,
                onFollowExecution = { value ->
                    if (value) focusedNodeId = null
                    setFollowExecution(value)
                },
                executionAvailable = programExecutionAvailable,
                focusNodeId = effectiveFocusId,
                focusPath = focusPath,
                onFocus = ::setFocus,
                executionRecent = executionRecent,
                executionStatus = editorDockStatusText(statusText, statusDismissed),
                onDismissExecutionStatus = { statusDismissed = true },
                editingEnabled = editingEnabled,
                running = running,
                paused = paused,
                resumeEnabled = resumeEnabled,
                onResume = onResume,
                onPause = onPause,
                runEnabled = runEnabled,
                stopEnabled = stopEnabled,
                onSettings = onOpenSettings,
                panelMode = panelMode,
                onMaximize = { panelMode = editorTogglePanelMode(panelMode, EditorPanelMode.MAXIMIZED) },
                onMinimize = { setExpanded(false) },
                onClose = onClose,
                onSelectFlow = { if (editingEnabled && sourceTree != null) sourceManagerVisible = true },
                onManageVariables = onManageVariables,
                onRun = onRun,
                onStop = onStop,
                onStep = onStep,
                stepEnabled = stepEnabled,
                selectionCount = effectiveSelection.size,
                selectedNodeIds = effectiveSelection,
                onFunctions = {
                    if (editingEnabled) {
                        inputActionDialogVisible = false
                        toolDialog = null
                        functionLibrary = true
                    }
                },
                onKeys = {
                    if (editingEnabled) {
                        // These are separate entry points. Clear any previously-open library/tool state
                        // before showing the input-action chooser, so the function browser cannot cover it.
                        functionLibrary = false
                        toolDialog = null
                        inputActionDialogVisible = true
                    }
                },
                onTool = { entry ->
                    if (editingEnabled) {
                        if (entry == EditorToolPanel.IMAGE && onCreateImageBlock != null) {
                            toolDialog = EditorToolPanel.IMAGE
                            functionLibrary = false
                        } else toolDialog = entry
                    }
                },
                onSelectNode = { nodeId ->
                    onProgramNodeSelected(nodeId)
                },
                onToggleNode = ::toggleNodeExpansion,
                onEditSelection = { nodeEditorVisible = true },
                onNodeActions = { nodeId ->
                    if (editingEnabled) {
                        if (nodeId !in effectiveSelection) {
                            onProgramNodeSelected(nodeId)
                        }
                        nodeActionsVisible = true
                    }
                },
                onOpenMoreMenu = { moreMenuVisible = true },
                onMoreAnchor = { moreAnchor = it },
                onToggleConsole = { consoleVisible = !consoleVisible },
                modifier = Modifier
                    .offset { IntOffset(effectivePanelX.roundToInt(), resolvedPanelY.roundToInt()) }
                    .width(with(density) { effectivePanelWidthPx.toDp() })
                    .height(with(density) { effectivePanelHeightPx.toDp() })
                    .onGloballyPositioned { overlayHost?.bounds(it.boundsInRoot()) },
                onDragStart = { down ->
                    if (panelMode != EditorPanelMode.NORMAL) {
                        val origin = editorRestoredDragOrigin(normalPlacement, panelPlacement, down.x, down.y, minPanelX, maxPanelX, maxPanelY)
                        panelX = origin.first; panelY = origin.second.coerceAtLeast(minPanelY); panelMode = EditorPanelMode.NORMAL
                    }
                },
                onDrag = { delta ->
                    panelX = (panelX.coerceIn(minPanelX, maxPanelX) + delta.x).coerceIn(minPanelX, maxPanelX)
                    val currentY = if (panelY.isNaN()) normalPanelY else panelY
                    panelY = (currentY + delta.y).coerceIn(minPanelY, maxPanelY)
                },
                onResize = { delta ->
                    if (panelMode != EditorPanelMode.NORMAL) return@EditorDockPanel
                    val resized = editorPanelSize(workArea, density.density,
                        Size(normalPanelWidthPx + delta.x, normalPanelHeightPx + delta.y), Size(maxWidthPx, maxHeightPx))
                    panelWidthPx = resized.width.coerceAtMost(workArea.right - effectivePanelX)
                    panelHeightPx = resized.height.coerceAtMost(workArea.bottom - resolvedPanelY)
                    panelResized = true
                },
                selectedBranch = selectedBranch,
                onSelectBranch = onProgramBranchSelected,
            )
            if (moreMenuVisible) {
                val menuViewport = editorMoreViewport(panelPlacement, maxWidthPx, maxHeightPx)
                val menuMaxWidth = with(density) { menuViewport.width.toDp() }
                val menuMaxHeight = with(density) { menuViewport.height.toDp() }
                Box(Modifier.offset { IntOffset(menuViewport.x.roundToInt(), menuViewport.y.roundToInt()) }
                    .size(menuMaxWidth, menuMaxHeight).clickable { moreMenuVisible = false })
                var moreWidthPx by remember { mutableFloatStateOf(with(density) { 164.dp.toPx() }) }
                var moreHeightPx by remember { mutableFloatStateOf(with(density) { 168.dp.toPx() }) }
                val anchor = moreAnchor
                val placement = editorMorePlacement(menuViewport.width, menuViewport.height, moreWidthPx, moreHeightPx,
                    (anchor?.right?.minus(dockOrigin.x) ?: (effectivePanelX + effectivePanelWidthPx)) - menuViewport.x,
                    (anchor?.top?.minus(dockOrigin.y) ?: (resolvedPanelY + effectivePanelHeightPx - with(density) { 40.dp.toPx() })) - menuViewport.y,
                    (anchor?.bottom?.minus(dockOrigin.y) ?: (resolvedPanelY + effectivePanelHeightPx)) - menuViewport.y)
                EditorMorePopup(
                    modifier = Modifier.offset { IntOffset((menuViewport.x + placement.x).roundToInt(), (menuViewport.y + placement.y).roundToInt()) }
                        .widthIn(max = menuMaxWidth).heightIn(max = menuMaxHeight)
                        .onSizeChanged { moreWidthPx = it.width.toFloat(); moreHeightPx = it.height.toFloat() },
                    onShowMain = { moreMenuVisible = false; onShowInterface?.invoke() },
                    canShowInterface = onShowInterface != null,
                    canRecord = onOpenRecorder != null && editingEnabled && !running,
                    onRecord = {
                        moreMenuVisible = false
                        onOpenRecorder?.invoke()
                    },
                    onVariableCheck = { moreMenuVisible = false; toolDialog = EditorToolPanel.VARIABLE_CHECK },
                    onSearchReplace = { moreMenuVisible = false; searchingProgram = true },
                    onRuntimeVariables = { moreMenuVisible = false; toolDialog = EditorToolPanel.RUNTIME_VARIABLES },
                    editingEnabled = editingEnabled && !running && !paused,
                )
            }
        }
    }

    if (reflectionVisible && editingEnabled) {
        EditorInsertPositionDialog(
            hasSelection = displayedSelectedNodeId != null,
            canInsertInside = programNodes.firstOrNull { it.nodeId == displayedSelectedNodeId }?.childSlots?.isNotEmpty() == true,
            title = "以此为模板 · 选择加入位置",
            previewNodes = displayedTreeNodes,
            previewAnchorId = reflectionNodeId,
            initialPosition = EditorInsertPosition.BELOW,
            allowReplace = false,
            onDismiss = { reflectionVisible = false },
            onConfirm = { position ->
                reflectionVisible = false
                val nodeId = reflectionNodeId
                if (nodeId != null && nodeId == displayedSelectedNodeId && reflectionSource == programSource) {
                    val slots = programNodes.firstOrNull { it.nodeId == nodeId }?.childSlots.orEmpty()
                    if (position == EditorInsertPosition.INSIDE && slots.size > 1) reflectionBranchVisible = true
                    else onProgramNodeReflected?.invoke(nodeId, position, slots.singleOrNull())
                }
            },
        )
    }
    if (reflectionBranchVisible && editingEnabled) {
        EditorOptionDialog(
            title = "选择加入分支",
            options = programNodes.firstOrNull { it.nodeId == reflectionNodeId }?.childSlots.orEmpty().map { it to childBlockLabel(it) },
            onDismiss = { reflectionBranchVisible = false },
            preview = { slot -> EditorInsertionPreview(displayedTreeNodes, reflectionNodeId, EditorInsertPosition.INSIDE, slot) },
            onConfirm = { slot ->
                reflectionBranchVisible = false
                val nodeId = reflectionNodeId
                if (nodeId != null && nodeId == displayedSelectedNodeId && reflectionSource == programSource)
                    onProgramNodeReflected?.invoke(nodeId, EditorInsertPosition.INSIDE, slot)
            },
        )
    }

    if (nodeActionsVisible && editingEnabled) {
        LaunchedEffect(Unit) {
            nodeActionsVisible = false
            nodeEditorVisible = true
        }
    }

    if (nodeEditorVisible) {
        EditorNodeEditDialog(
            nodes = displayedTreeNodes,
            focusNodeId = effectiveFocusId,
            focusPath = focusPath,
            onFocus = ::setFocus,
            editingEnabled = editingEnabled,
            selectedNodeId = displayedSelectedNodeId,
            selectedNodeIds = effectiveSelection,
            onToggleNode = onProgramNodeSelectionToggled,
            onSetSelection = onProgramSelectionChanged,
            onToggleExpansion = ::toggleNodeExpansion,
            onSetFolded = { folded ->
                collapsedProgramNodeIds = if (folded) displayedTreeNodes.filter { it.container }.map { it.id } else emptyList()
                if (folded && selectedBranch != null) onProgramNodeSelected(selectedBranch.ownerId)
                if (folded) {
                    val index = displayedTreeNodes.indexOfFirst { it.id == programSelectedNodeId }
                    if (index >= 0 && displayedTreeNodes[index].depth > 0) {
                        val root = effectiveFocusId ?: displayedTreeNodes.subList(0, index + 1).lastOrNull { it.depth == 0 }?.id
                        root?.let(onProgramNodeSelected)
                    }
                }
            },
            onSelectNode = { nodeId ->
                onProgramNodeSelected(nodeId)
            },
            onEditSelected = ::editSelectedParameters,
            onBatchParameters = onBatchParameters,
            onReuseSelected = if (onProgramNodeReflected != null) {
                { reuseSelectedParameters() }
            } else null,
            onAnnotateSelected = onProgramNodeAnnotated?.let { annotate ->
                { annotate() }
            },
            onRandomizationSelected = if (onProgramRandomizationUpdated != null && programSource != null &&
                displayedSelectedNodeId?.let { id -> programNodes.find { it.nodeId == id }?.kind }?.let(com.autoscript.studio.compiler.NodeRandomizationSettings::supports) == true) {
                {
                    val id = displayedSelectedNodeId
                    val source = programSource
                    if (effectiveSelection.size == 1) {
                        val node = source.lineSequence().filter(String::isNotBlank).map { com.google.gson.JsonParser.parseString(it).asJsonObject }.firstOrNull { it["nodeId"].asString == id }
                        if (node != null) {
                            val policy = node.getAsJsonObject("randomization")
                            val gson = com.google.gson.Gson()
                            val position = policy?.get("position")?.let { gson.fromJson(it,com.autoscript.project.store.PositionRandomization::class.java) }
                                ?: if (node["kind"].asString.startsWith("input.")) projectRandomization.touch else projectRandomization.recognition
                            val wait = policy?.get("wait")?.let { gson.fromJson(it,com.autoscript.project.store.WaitRandomization::class.java) } ?: projectRandomization.wait
                            nodeRandomizationTarget = NodeRandomizationEditTarget(id,source,node["kind"].asString,
                                com.autoscript.project.store.ProjectRandomization(position,position,wait),policy?.get("mode")?.asString ?: "follow")
                        }
                    }
                }
            } else null,
            onDisplaySelected = if (onProgramDisplayUpdated != null && programSource != null) {
                {
                    val id = displayedSelectedNodeId
                    val source = programSource
                    if (id != null && effectiveSelection.size == 1) {
                        val json = source.lineSequence().filter(String::isNotBlank).map { com.google.gson.JsonParser.parseString(it).asJsonObject }
                            .firstOrNull { it["nodeId"]?.asString == id }
                        if (json != null) {
                            val kind = json["kind"].asString
                            nodeDisplayTarget = NodeDisplayEditTarget(id, source,
                                com.autoscript.studio.compiler.NodeDisplaySettings.parse(json["display"]),
                                compactProgramLabel(BlockCatalog.find(kind), json["args"]?.asJsonObject, kind,
                                    projectFlows.associate { it.flowId to it.displayName() }))
                        }
                    }
                }
            } else null,
            onDeleteSelected = ::requestDelete,
            disabledSelection = programSelectionDisabled ?: (programNodes.filter { it.nodeId in effectiveSelection }.let { it.isNotEmpty() && it.all { node -> node.disabled } } == true),
            onCommand = onProgramCommand,
            canUndo = canUndoProgram,
            canRedo = canRedoProgram,
            canPaste = hasProgramClipboard && selectedBranch == null,
            onEditSource = onApplyProgramSource?.let { { sourceEditorVisible = true } },
            onEditSelectedSource = if (onApplyProgramSource != null && programSource != null) {
                {
                    if (effectiveSelection.size == 1 && displayedSelectedNodeId != null) {
                        selectedNodeSourceId = displayedSelectedNodeId
                    }
                }
            } else null,
            onDismiss = { nodeEditorVisible = false },
            selectedBranch = selectedBranch,
            onSelectBranch = onProgramBranchSelected,
        )
    }

    nodeRandomizationTarget?.let { target ->
        RandomizationDialog(target.initial, busy = false, enabled = editingEnabled,
            onDismiss = {nodeRandomizationTarget = null},initialNodeMode = target.mode,waitNode = target.kind == "task.sleep",
            onSave = { settings, mode ->
                val problem = if (!editingEnabled || programSource != target.source || displayedSelectedNodeId != target.nodeId) "编辑上下文已变化，请重新打开"
                else onProgramRandomizationUpdated?.invoke(target.nodeId,target.source,
                    if (mode == "follow") null else randomizationNodeJson(requireNotNull(mode),settings)) ?: if (onProgramRandomizationUpdated == null) "当前不支持随机设置" else null
                if (problem == null) nodeRandomizationTarget = null
                problem
            })
    }
    nodeDisplayTarget?.let { target ->
        NodeDisplayDialog(target, editingEnabled, onDismiss = { nodeDisplayTarget = null }, onApply = { settings ->
            if (!editingEnabled || programSource != target.source || displayedSelectedNodeId != target.nodeId)
                "编辑上下文已变化，请关闭后重新打开"
            else if (onProgramDisplayUpdated == null) "当前不支持显示设置"
            else onProgramDisplayUpdated(target.nodeId, target.source, settings)
        })
    }

    if (consoleVisible) RuntimeLogDialog(consoleLines, logProjectId,
        debugSettings = debugSettings, settingsEditable = editingEnabled,
        onSaveDebugSettings = { settings -> onSaveDebugSettings(settings) {} }, onLocate = onLocateRuntimeLog?.let { locate ->
        { target -> locate(target).also { if (it == null) collapsedProgramNodeIds = emptyList() } }
    }) { consoleVisible = false }
    if ((sourceEditorVisible || selectedNodeSourceId != null) && programSource != null && onApplyProgramSource != null && !running) {
        val root = projectFlows.firstOrNull { it.flowId == currentFlowId }?.rootBlockId
        if (root != null) androidx.compose.runtime.key(currentFlowId) {
            val flow = projectFlows.first { it.flowId == currentFlowId }
            val declared = projectVariables.filter {
                it.scope == com.autoscript.project.store.ProjectVariableScope.GLOBAL || it.flowId == currentFlowId
            }.map { it.name }.toSet() + flow.params.mapNotNull { it.get("name")?.takeIf { name -> name.isJsonPrimitive }?.asString }
            val selectedSource = selectedNodeSourceId
            if (selectedSource != null) {
                SelectedNodeSourceEditorDialog(programSource, selectedSource, declared,
                    nodeOrder = displayedTreeNodes.map { it.id },
                    onSelectNode = onProgramNodeSelected, onApply = onApplyProgramSource,
                    onDismiss = { selectedNodeSourceId = null })
            } else {
                PluginSourceEditorDialog(programSource, root, declared,
                    onApplyProgramSource, { sourceEditorVisible = false }, initialSelectedNodeId = displayedSelectedNodeId,
                    allocateIdentity = onAllocateProgramIdentity)
            }
        }
    }

    if (functionLibrary) {
        val functionGroups = remember { FunctionCatalog.visualGroups() }
        FunctionLibraryDialog(
            groups = functionGroups,
            capabilities = capabilities,
            onDismiss = { functionLibrary = false },
            onInsert = { snippet ->
                submitSnippet(snippet)
            },
            onInsertWithCompletion = { snippet, committed -> submitSnippet(snippet, committed) },
        )
    }
    if (inputActionDialogVisible) {
        VisualInputActionDialog(
            canPickPoint = onOpenInputPointPicker != null && editingEnabled,
            backendFeatures = inputBackendFeatures,
            onOpenPermissions = onOpenPermissions,
            onRefresh = onRefreshInputCapabilities,
            onRecord = onOpenRecorder?.takeIf { editingEnabled && "input.basic" in capabilities }?.let { record ->
                { inputActionDialogVisible = false; record() }
            },
            onDismiss = { inputActionDialogVisible = false },
            onPick = { mode ->
                inputActionDialogVisible = false
                onOpenInputPointPicker?.invoke(mode)
            },
            onFloatingPick = onOpenInputFloatingPicker?.let { open -> { mode ->
                inputActionDialogVisible = false
                open(mode)
            } },
        )
    }
    if (sourceManagerVisible && sourceTree != null) {
        PluginFileManagerDialog(
            tree = sourceTree,
            currentFlowId = currentFlowId,
            title = "项目插件",
            projectName = projectName,
            busy = sourceBusy,
            message = sourceMessage,
            onManagePlugins = { pluginManagerVisible = true },
            createPluginOnOpen = createPluginRequested,
            onCreatePluginRequestHandled = { createPluginRequested = false },
            selectCurrentPluginOnOpen = selectCurrentPluginRequested,
            onSelectCurrentPluginRequestHandled = { selectCurrentPluginRequested = false },
            onAction = { action ->
                if (action is SourceManagerAction.Open || action is SourceManagerAction.InsertCall) {
                    sourceManagerVisible = false
                }
                onSourceAction(action)
            },
            onDismiss = { sourceManagerVisible = false },
        )
    }
    if (pluginManagerVisible) {
        PluginManagerDialog(
            enabled = sourceTree != null && editingEnabled && !sourceBusy,
            currentPluginName = sourceTree?.entry(currentFlowId.orEmpty())?.name ?: sourceName.orEmpty(),
            pluginCount = sourceTree?.entries?.size ?: 0,
            onDismiss = { pluginManagerVisible = false },
            onAction = { action ->
                pluginManagerVisible = false
                when (action) {
                    PluginManagerAction.CREATE -> {
                        sourceManagerVisible = true
                        createPluginRequested = true
                    }
                    PluginManagerAction.DELETE,
                    PluginManagerAction.SAVE_AS,
                    PluginManagerAction.GROUP,
                    -> {
                        sourceManagerVisible = true
                        selectCurrentPluginRequested = true
                    }
                    PluginManagerAction.CHECK,
                    PluginManagerAction.CHECK_ALL,
                    PluginManagerAction.TEMPLATE,
                    PluginManagerAction.UNUSED,
                    -> onPluginAction(action)
                }
            },
        )
    }
    toolDialog?.let { dialog ->
        val completion = remember(dialog, projectName, currentFlowId, sourceName) { EditorInsertionCompletion { toolDialog = null } }
        DisposableEffect(completion) { onDispose { completion.cancel() } }
        if (onOpenDebugTool != null && dialog in setOf(EditorToolPanel.VARIABLE_CHECK, EditorToolPanel.RUNTIME_VARIABLES)) {
            LaunchedEffect(dialog) { toolDialog = null; onOpenDebugTool(dialog) }
            return@let
        }
        EditorEntryDialog(
            entry = dialog,
            onDismiss = { toolDialog = null },
            onOpenImageTools = onOpenImageTools?.let { open -> { delayMillis -> toolDialog = null; open(delayMillis) } },
            onCreateImageBlock = onCreateImageBlock?.let { create -> { kind -> toolDialog = null; create(kind) } },
            onOpenImageLibrary = onOpenImageLibrary?.let { open -> { toolDialog = null; open() } },
            onTestRecognition = onTestRecognition?.let { test -> { toolDialog = null; test() } },
            availableVariables = availableVariables,
            availableLabels = availableLabels,
            projectVariables = projectVariables,
            currentFlowId = currentFlowId.orEmpty(),
            projectFlows = projectFlows,
            onManageVariables = onManageVariables,
            debugSettings = debugSettings,
            onSaveDebugSettings = onSaveDebugSettings,
        ) { snippet ->
            submitSnippet(snippet, completion::committed)
        }
    }
    pendingInsertSnippet?.let { request ->
        EditorInsertPositionDialog(
            previewNodes = remember(request) { displayedTreeNodes },
            previewAnchorId = request.primary,
            hasSelection = displayedSelectedNodeId != null,
            canInsertInside = displayedTreeNodes.firstOrNull { it.id == displayedSelectedNodeId }?.container == true,
            onDismiss = { pendingInsertSnippet = null },
            onConfirm = { position ->
                if (!editingEnabled || !request.matches(currentFlowId, programSource, programSelectedNodeId, programSelectedNodeIds)) {
                    insertionError = "插件、草稿、选择或运行状态已变化，未加入；请重新选择位置"
                } else onInsertPositioned?.invoke(request.snippet, position, request.onCommitted) ?: onInsert(request.snippet)
                pendingInsertSnippet = null
            },
        )
    }
    if (searchingProgram) {
        EditorProgramSearchDialog(
            nodes = displayedTreeNodes,
            onDismiss = { searchingProgram = false },
            onReplace = onReplaceProgramText.takeIf { editingEnabled },
            onOpenProjectSearch = onOpenProjectSearch?.let { open -> { searchingProgram = false; open() } },
            programSource = programSource,
            onSelect = { nodeId ->
                collapsedProgramNodeIds = emptyList()
                focusedNodeId = null
                if (programExecutionAvailable) setFollowExecution(false)
                onProgramNodeSelected(nodeId)
                searchingProgram = false
            },
        )
    }
    insertionError?.let { message ->
        StudioConfirmDialog(onDismissRequest = { insertionError = null }, title = { Text("未加入积木") },
            text = { Text(message) }, confirmButton = { TextButton(onClick = { insertionError = null }) { Text("确定") } })
    }
}

internal enum class EditorProgramCommand {
    MOVE_UP,
    MOVE_DOWN,
    INDENT,
    OUTDENT,
    COPY,
    CUT,
    PASTE,
    UNDO,
    REDO,
    TOGGLE_DISABLED,
}

internal data class EditorTreeNode(
    val id: String,
    val label: String,
    val code: String,
    val depth: Int = 0,
    val container: Boolean = false,
    val expanded: Boolean = true,
    val displayRow: CompactProgramRowText? = null,
    val childSlots: List<String> = emptyList(),
    val childSlot: String? = null,
    val fullDisplayRow: CompactProgramRowText? = null,
)

internal fun editorTreeHasChildren(nodes: List<EditorTreeNode>, index: Int): Boolean =
    index in nodes.indices && (nodes[index].container ||
        (index + 1 < nodes.size && nodes[index + 1].depth > nodes[index].depth))

internal fun editorTreeIsDescendant(nodes: List<EditorTreeNode>, parentIndex: Int, nodeId: String?): Boolean {
    if (nodeId == null || parentIndex !in nodes.indices) return false
    val childIndex = nodes.indexOfFirst { it.id == nodeId }
    if (childIndex <= parentIndex) return false
    val parentDepth = nodes[parentIndex].depth
    var index = parentIndex + 1
    while (index < nodes.size && nodes[index].depth > parentDepth) {
        if (index == childIndex) return true
        index++
    }
    return false
}

internal val EditorBlue = Color(0xFF3A6EFF)
internal val EditorDisabledBlue = Color(0xFFAFC5FF)
internal val EditorBorder = Color(0xFFC7CBD1)
internal enum class EditorToolPanel { IMAGE, JUDGMENT, LOOP, COMMON, DEBUG, AI, VARIABLE_CHECK, RUNTIME_VARIABLES }

internal enum class EditorInsertPosition {
    LIST_BOTTOM,
    ABOVE,
    BELOW,
    INSIDE,
    REPLACE,
}

internal data class EditorProgramNode(
    val nodeId: String,
    val label: String,
    val kind: String,
    val depth: Int,
    val childSlots: List<String> = emptyList(),
    val disabled: Boolean = false,
    val inheritedDisabled: Boolean = false,
    val displayRow: CompactProgramRowText? = null,
    val childSlot: String? = null,
    val fullDisplayRow: CompactProgramRowText? = null,
)

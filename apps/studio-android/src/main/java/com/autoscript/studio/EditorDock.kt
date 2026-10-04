package com.autoscript.studio

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.RadioButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.autoscript.core.designsystem.AutoScriptPalette
import com.autoscript.core.designsystem.hairline
import com.autoscript.project.store.ProjectDebugSettings
import com.autoscript.project.store.ProjectVariable
import com.autoscript.project.store.ProjectFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * In-app reconstruction of the current editor overlay (`service_tk.xml` and
 * `service_tk_ball.xml`). It stays inside Studio until the system-overlay
 * runtime is enabled in a later milestone.
 */
@Composable
internal fun EditorDock(
    projectName: String,
    onRun: () -> Unit,
    onInsert: (String) -> Unit,
    /** Open the normal editor directly; legacy/debug hosts may still start as a ball. */
    initiallyExpanded: Boolean = false,
    onInsertPositioned: ((String, EditorInsertPosition) -> Unit)? = null,
    sourceName: String? = null,
    programNodes: List<EditorProgramNode>? = null,
    programSource: String? = null,
    onReplaceProgramText: ((String, String, Boolean) -> String?)? = null,
    onApplyProgramSource: (suspend (String, String) -> String?)? = null,
    programSelectedNodeId: String? = null,
    programCurrentNodeId: String? = null,
    executionStatus: String? = null,
    onProgramNodeSelected: (String) -> Unit = {},
    programSelectedNodeIds: Set<String> = emptySet(),
    onProgramNodeSelectionToggled: ((String) -> Unit)? = null,
    onProgramNodeDeleted: () -> Unit = {},
    onProgramNodeEdited: () -> Unit = {},
    onProgramNodeReflected: ((String, EditorInsertPosition, String?) -> Unit)? = null,
    onProgramNodeAnnotated: (() -> Unit)? = null,
    onProgramCommand: (EditorProgramCommand) -> Unit = {},
    canUndoProgram: Boolean = false,
    canRedoProgram: Boolean = false,
    hasProgramClipboard: Boolean = false,
    onStep: () -> Unit = {},
    stepEnabled: Boolean = true,
    onShowInterface: (() -> Unit)? = null,
    consoleLines: List<String> = emptyList(),
    onClose: () -> Unit = {},
    /** 标题栏左侧的设置入口，由宿主打开当前项目设置。 */
    onOpenSettings: () -> Unit,
    editingEnabled: Boolean = true,
    /** 可视化项目的 Flow（插件）文件树；Lua 项目为 null，标题点击只在此模式打开 Flow 选择。 */
    sourceTree: SourceFileTree? = null,
    currentFlowId: String? = null,
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
    onSaveDebugSettings: (ProjectDebugSettings) -> Unit = {},
    /** “文件”弹窗展示的项目沙箱文件（来自 [projectFileCatalog]）。 */
    projectFiles: List<StudioProjectFile> = emptyList(),
    onOpenProjectFile: (StudioProjectFile) -> Unit = {},
    onDeleteProjectFiles: (List<StudioProjectFile>) -> Unit = {},
    /** 插件管理中的检错/未调用等需要宿主执行的动作会回调。 */
    onPluginAction: (PluginManagerAction) -> Unit = {},
    capabilities: Set<String> = emptySet(),
    /** 运行中保留品牌浮球，点击展开控制，不直接停止脚本。 */
    running: Boolean = false,
    paused: Boolean = false,
    resumeEnabled: Boolean = false,
    onResume: () -> Unit = {},
    onPause: () -> Unit = {},
    runEnabled: Boolean = true,
    onStop: () -> Unit = {},
    /** “更多 → 录制动作”的全屏页；为 null 时退回弹窗内预览。 */
    onOpenRecorder: (() -> Unit)? = null,
    /** “工具 → 标注截屏/图像处理”的图像工具悬浮窗。 */
    onOpenImageTools: ((Long) -> Unit)? = null,
    onOpenImageToolMode: ((ImageToolMode, Long) -> Unit)? = null,
    /** 可视化“图像”面板直接配置并插入正式图像积木。 */
    onCreateImageBlock: ((String) -> Unit)? = null,
    /** 按键面板的截图定位入口；进入时指定单击或滑动模式。 */
    onOpenInputPointPicker: ((ImageToolMode) -> Unit)? = null,
    onOpenInputFloatingPicker: ((ImageToolMode) -> Unit)? = null,
    inputPickOpenRequest: Int = 0,
    inputBackendFeatures: Int? = null,
    onOpenPermissions: (() -> Unit)? = null,
    onRefreshInputCapabilities: (() -> Unit)? = null,
    /** “工具 → 打开标注库”的项目图片列表页。 */
    onOpenImageLibrary: (() -> Unit)? = null,
    onTestRecognition: (() -> Unit)? = null,
    onOpenDebugTool: ((EditorToolPanel) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    var expanded by rememberSaveable(projectName) { mutableStateOf(initiallyExpanded) }
    var ballControlsExpanded by remember(projectName) { mutableStateOf(false) }
    LaunchedEffect(running) { if (!running) ballControlsExpanded = false }
    var functionLibrary by rememberSaveable(projectName) { mutableStateOf(false) }
    var toolDialog by rememberSaveable(projectName) { mutableStateOf<EditorToolPanel?>(null) }
    var sourceManagerVisible by rememberSaveable(projectName) { mutableStateOf(false) }
    var pluginManagerVisible by rememberSaveable(projectName) { mutableStateOf(false) }
    var createPluginRequested by rememberSaveable(projectName) { mutableStateOf(false) }
    var selectCurrentPluginRequested by rememberSaveable(projectName) { mutableStateOf(false) }
    var consoleVisible by rememberSaveable(projectName) { mutableStateOf(false) }
    var treeNodes by rememberSaveable(projectName, saver = EditorTreeNodesStateSaver) {
        mutableStateOf<List<EditorTreeNode>>(emptyList())
    }
    var selectedNodeId by rememberSaveable(projectName) { mutableStateOf<String?>(null) }
    var editingNodeId by rememberSaveable(projectName) { mutableStateOf<String?>(null) }
    var nextNodeId by rememberSaveable(projectName) { mutableStateOf(1L) }
    var collapsedProgramNodeIds by rememberSaveable(projectName, sourceName) {
        mutableStateOf<List<String>>(emptyList())
    }
    var searchingProgram by rememberSaveable(projectName, sourceName) { mutableStateOf(false) }
    var nodeEditorVisible by rememberSaveable(projectName, sourceName) { mutableStateOf(false) }
    var nodeActionsVisible by rememberSaveable(projectName, sourceName) { mutableStateOf(false) }
    var sourceEditorVisible by remember(projectName, sourceName) { mutableStateOf(false) }
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
    var pendingInsertSnippet by rememberSaveable(projectName, sourceName) { mutableStateOf<String?>(null) }
    var closed by remember(projectName) { mutableStateOf(false) }
    LaunchedEffect(inputPickOpenRequest) {
        if (inputPickOpenRequest > 0) { closed = false; expanded = true }
    }
    val externallyManaged = programNodes != null
    val displayedTreeNodes = remember(programNodes, collapsedProgramNodeIds, treeNodes) {
        val authoritativeNodes = programNodes
        authoritativeNodes?.mapIndexed { index, node ->
            EditorTreeNode(
                id = node.nodeId,
                label = (if (node.disabled) "[已禁用] " else "") + node.label,
                code = node.kind,
                entry = legacyEntryForKind(node.kind),
                depth = node.depth,
                container = node.childSlots.isNotEmpty() ||
                    authoritativeNodes.getOrNull(index + 1)?.depth?.let { it > node.depth } == true,
                expanded = node.nodeId !in collapsedProgramNodeIds,
            )
        } ?: treeNodes
    }
    val displayedSelectedNodeId = if (externallyManaged) programSelectedNodeId else selectedNodeId
    val displayedSourceName = sourceName
    LaunchedEffect(programCurrentNodeId) {
        if (programCurrentNodeId != null) collapsedProgramNodeIds = emptyList()
    }
    val effectiveSelection = if (externallyManaged) programSelectedNodeIds.ifEmpty { setOfNotNull(displayedSelectedNodeId) }
        else setOfNotNull(displayedSelectedNodeId)
    fun editSelectedParameters() {
        nodeEditorVisible = false
        nodeActionsVisible = false
        if (!editingEnabled || effectiveSelection.size != 1) return
        if (externallyManaged) onProgramNodeEdited()
        else treeNodes.firstOrNull { it.id == selectedNodeId }?.let { selected ->
            editingNodeId = selected.id
            if (selected.entry == EditorToolPanel.FUNCTIONS) functionLibrary = true else toolDialog = selected.entry
        }
    }
    fun reuseSelectedParameters() {
        nodeActionsVisible = false
        if (!editingEnabled || effectiveSelection.size != 1) return
        if (externallyManaged && onProgramNodeReflected != null) {
            reflectionNodeId = displayedSelectedNodeId
            reflectionSource = programSource
            reflectionVisible = true
        } else editSelectedParameters()
    }
    fun requestDelete() {
        if (editingEnabled && effectiveSelection.isNotEmpty()) { deleteSource = programSource; deleteRequest = displayedTreeNodes to effectiveSelection }
    }
    LaunchedEffect(editingEnabled) {
        if (!editingEnabled) {
            nodeEditorVisible = false; toolDialog = null; functionLibrary = false
            nodeActionsVisible = false
            sourceEditorVisible = false
            inputActionDialogVisible = false; deleteRequest = null
            reflectionVisible = false; reflectionBranchVisible = false
        }
    }
    deleteRequest?.let { (originalNodes, originalSelection) ->
        val valid = editingEnabled && displayedTreeNodes == originalNodes && effectiveSelection == originalSelection && programSource == deleteSource
        val count = originalNodes.indices.count { index ->
            originalNodes[index].id in originalSelection || originalSelection.any { selected ->
                val ancestor = originalNodes.indexOfFirst { it.id == selected }
                ancestor >= 0 && legacyIsDescendant(originalNodes, ancestor, originalNodes[index].id)
            }
        }
        StudioConfirmDialog(onDismissRequest = { deleteRequest = null }, title = { Text("删除积木") },
            text = { Text(if (valid) "删除选中积木及其包含的子积木，共 $count 项？删除后可以撤销。" else "程序或选择已变化，请取消后重新选择。") },
            dismissButton = { TextButton(onClick = { deleteRequest = null }) { Text("取消") } },
            confirmButton = { TextButton(enabled = valid, onClick = {
                deleteRequest = null
                if (externallyManaged) onProgramNodeDeleted() else {
                    originalSelection.forEach { id -> treeNodes = removeLegacyTreeSubtree(treeNodes, id) }
                    selectedNodeId = treeNodes.firstOrNull()?.id
                }
            }) { Text("删除") } })
    }

    LaunchedEffect(programNodes) {
        if (programNodes != null) {
            val activeIds = programNodes.asSequence().map(EditorProgramNode::nodeId).toHashSet()
            collapsedProgramNodeIds = collapsedProgramNodeIds.filter(activeIds::contains)
        }
    }

    LaunchedEffect(treeNodes) {
        val knownIds = treeNodes.asSequence().map(EditorTreeNode::id).toHashSet()
        if (selectedNodeId?.let { it !in knownIds } == true) selectedNodeId = null
        if (editingNodeId?.let { it !in knownIds } == true) editingNodeId = null
        val restoredSequence = treeNodes.asSequence()
            .mapNotNull { node ->
                node.id.removePrefix("legacy-")
                    .takeIf { suffix -> suffix != node.id }
                    ?.toLongOrNull()
            }
            .maxOrNull() ?: 0L
        nextNodeId = maxOf(nextNodeId, restoredSequence + 1L)
    }

    BackHandler(enabled = moreMenuVisible || expanded) {
        when {
            moreMenuVisible -> moreMenuVisible = false
            else -> expanded = false
        }
    }

    if (closed) return

    BoxWithConstraints(modifier.fillMaxSize().onGloballyPositioned { dockOrigin = it.positionInRoot() }) {
        val density = LocalDensity.current
        val ballSizePx = with(density) { 35.dp.toPx() }
        val minPanelWidthPx = with(density) { 210.dp.toPx() }
        // Allow compact windows; the side actions scroll below their natural height.
        val minPanelHeightPx = with(density) { 260.dp.toPx() }
        val maxWidthPx = constraints.maxWidth.toFloat()
        val maxHeightPx = constraints.maxHeight.toFloat()
        val ballPeekPx = with(density) { 10.dp.toPx() }
        var ballX by rememberSaveable(projectName) { mutableFloatStateOf(Float.NaN) }
        var ballY by rememberSaveable(projectName) { mutableFloatStateOf(Float.NaN) }
        var panelX by rememberSaveable(projectName) { mutableFloatStateOf(with(density) { 4.dp.toPx() }) }
        var panelY by rememberSaveable(projectName) { mutableFloatStateOf(Float.NaN) }
        var panelWidthPx by rememberSaveable(projectName) { mutableFloatStateOf(with(density) { 292.dp.toPx() }) }
        // 40dp title + side actions + a 44dp footer. Smaller saved
        // windows retain their size; side actions scroll instead of losing the AI entry.
        var panelHeightPx by rememberSaveable(projectName) { mutableFloatStateOf(with(density) { 378.dp.toPx() }) }
        val effectiveMinPanelWidthPx = minPanelWidthPx.coerceAtMost(maxWidthPx)
        val effectiveMinPanelHeightPx = minPanelHeightPx.coerceAtMost(maxHeightPx)
        val effectivePanelWidthPx = panelWidthPx.coerceIn(effectiveMinPanelWidthPx, maxWidthPx)
        val effectivePanelHeightPx = panelHeightPx.coerceIn(effectiveMinPanelHeightPx, maxHeightPx)
        val panelGrabWidthPx = with(density) { 48.dp.toPx() }.coerceAtMost(effectivePanelWidthPx)
        val panelHeaderHeightPx = with(density) { 35.dp.toPx() }.coerceAtMost(effectivePanelHeightPx)
        val minPanelX = -effectivePanelWidthPx + panelGrabWidthPx
        val maxPanelX = maxWidthPx - panelGrabWidthPx
        val maxPanelY = (maxHeightPx - panelHeaderHeightPx).coerceAtLeast(0f)
        val maxBallX = (maxWidthPx - ballSizePx + ballPeekPx).coerceAtLeast(-ballPeekPx)
        val maxBallY = (maxHeightPx - ballSizePx).coerceAtLeast(0f)
        val resolvedBallX = (if (ballX.isNaN()) -ballPeekPx else ballX).coerceIn(-ballPeekPx, maxBallX)
        val resolvedBallY = (if (ballY.isNaN()) maxBallY / 2f else ballY).coerceIn(0f, maxBallY)
        val effectivePanelX = panelX.coerceIn(minPanelX, maxPanelX)
        val resolvedPanelY = if (panelY.isNaN()) {
            (maxHeightPx - effectivePanelHeightPx - with(density) { 76.dp.toPx() }).coerceAtLeast(0f)
        } else panelY.coerceIn(0f, maxPanelY)

        if (!expanded) {
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
                        if (running) ballControlsExpanded = !ballControlsExpanded else expanded = true
                    },
                    color = Color.White,
                    shape = RoundedCornerShape(18.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFE8E8F3)),
                    shadowElevation = 5.dp,
                ) {
                    EditorBallIcon(Modifier.fillMaxSize(), if (running) "智构 · 展开运行控制" else "智构 · 打开编辑器")
                }
            }
            if (running && ballControlsExpanded) {
                val controlsWidth = with(density) { 160.dp.toPx() }
                val controlsHeight = with(density) { 36.dp.toPx() }
                Surface(
                    modifier = Modifier.offset {
                        IntOffset(
                            resolvedBallX.coerceIn(0f, (maxWidthPx - controlsWidth).coerceAtLeast(0f)).roundToInt(),
                            (resolvedBallY + ballSizePx).coerceIn(0f, (maxHeightPx - controlsHeight).coerceAtLeast(0f)).roundToInt(),
                        )
                    }.width(160.dp).height(36.dp),
                    color = Color.White, shape = RoundedCornerShape(10.dp), shadowElevation = 5.dp,
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFE8E8F3)),
                ) {
                    Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                        EditorBallControl(if (paused) "继续" else "暂停", if (paused) resumeEnabled else runEnabled, EditorBlue) {
                            if (paused) onResume() else onPause()
                        }
                        EditorBallControl("停止", runEnabled, Color(0xFFE55353), onStop)
                        EditorBallControl("编辑", true, EditorBlue) { ballControlsExpanded = false; expanded = true }
                    }
                }
            }
        } else {
            EditorDockPanel(
                projectName = projectName,
                title = displayedSourceName ?: if (sourceTree != null) "选择插件文件" else projectName,
                hasSource = externallyManaged || displayedSourceName != null,
                canSelectFlow = editingEnabled && sourceTree != null,
                nodes = displayedTreeNodes,
                selectedNodeId = displayedSelectedNodeId,
                currentNodeId = programCurrentNodeId,
                executionStatus = executionStatus,
                editingEnabled = editingEnabled,
                running = running,
                paused = paused,
                resumeEnabled = resumeEnabled,
                onResume = onResume,
                onPause = onPause,
                runEnabled = runEnabled,
                onSettings = onOpenSettings,
                onMinimize = { expanded = false },
                onClose = {
                    expanded = false
                    closed = true
                    onClose()
                },
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
                        editingNodeId = null
                        functionLibrary = true
                    }
                },
                onKeys = {
                    if (editingEnabled) {
                        // These are separate entry points. Clear any previously-open library/tool state
                        // before showing the input-action chooser, so the function browser cannot cover it.
                        functionLibrary = false
                        toolDialog = null
                        editingNodeId = null
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
                    if (externallyManaged) onProgramNodeSelected(nodeId) else selectedNodeId = nodeId
                },
                onToggleNode = { nodeId ->
                    val nodeIndex = displayedTreeNodes.indexOfFirst { it.id == nodeId }
                    if (nodeIndex >= 0 && legacyHasChildren(displayedTreeNodes, nodeIndex)) {
                        val node = displayedTreeNodes[nodeIndex]
                        if (externallyManaged) {
                            collapsedProgramNodeIds = if (node.expanded) {
                                (collapsedProgramNodeIds + nodeId).distinct()
                            } else {
                                collapsedProgramNodeIds - nodeId
                            }
                            if (node.expanded && legacyIsDescendant(displayedTreeNodes, nodeIndex, displayedSelectedNodeId)) {
                                onProgramNodeSelected(node.id)
                            }
                        } else {
                            val collapsing = node.expanded
                            if (collapsing && legacyIsDescendant(treeNodes, nodeIndex, selectedNodeId)) {
                                selectedNodeId = node.id
                            }
                            treeNodes = treeNodes.map { candidate ->
                                if (candidate.id == nodeId) candidate.copy(expanded = !candidate.expanded) else candidate
                            }
                        }
                    }
                },
                onEditSelection = { if (externallyManaged) nodeEditorVisible = true else editSelectedParameters() },
                onNodeActions = { nodeId ->
                    if (editingEnabled) {
                        if (nodeId !in effectiveSelection) {
                            if (externallyManaged) onProgramNodeSelected(nodeId) else selectedNodeId = nodeId
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
                    .height(with(density) { effectivePanelHeightPx.toDp() }),
                onDrag = { delta ->
                    panelX = (panelX + delta.x).coerceIn(minPanelX, maxPanelX)
                    val currentY = if (panelY.isNaN()) resolvedPanelY else panelY
                    panelY = (currentY + delta.y).coerceIn(0f, maxPanelY)
                },
                onResize = { delta ->
                    val availableWidth = (maxWidthPx - effectivePanelX).coerceAtLeast(effectiveMinPanelWidthPx)
                    val availableHeight = (maxHeightPx - resolvedPanelY).coerceAtLeast(effectiveMinPanelHeightPx)
                    panelWidthPx = (panelWidthPx + delta.x).coerceIn(effectiveMinPanelWidthPx, availableWidth)
                    panelHeightPx = (panelHeightPx + delta.y).coerceIn(effectiveMinPanelHeightPx, availableHeight)
                },
            )
            if (moreMenuVisible) {
                Box(Modifier.fillMaxSize().clickable { moreMenuVisible = false })
                val moreWidthPx = with(density) { 148.dp.toPx() }
                val moreHeightPx = with(density) { 400.dp.toPx() }.coerceAtMost(maxHeightPx)
                val anchor = moreAnchor
                val moreX = ((anchor?.left?.minus(dockOrigin.x)) ?: effectivePanelX)
                    .coerceIn(0f, (maxWidthPx - moreWidthPx).coerceAtLeast(0f))
                val moreY = ((anchor?.top?.minus(dockOrigin.y) ?: (resolvedPanelY + effectivePanelHeightPx - with(density) { 40.dp.toPx() })) - moreHeightPx)
                    .coerceIn(0f, (maxHeightPx - moreHeightPx).coerceAtLeast(0f))
                EditorMorePopup(
                    modifier = Modifier.offset { IntOffset(moreX.roundToInt(), moreY.roundToInt()) }.heightIn(max = maxHeight),
                    onShowMain = { moreMenuVisible = false; onShowInterface?.invoke() },
                    canShowInterface = onShowInterface != null,
                    canRecord = onOpenRecorder != null && editingEnabled && !running,
                    onRecord = {
                        moreMenuVisible = false
                        if (onOpenRecorder != null) onOpenRecorder() else toolDialog = EditorToolPanel.RECORDING
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
            canInsertInside = programNodes?.firstOrNull { it.nodeId == displayedSelectedNodeId }?.childSlots?.isNotEmpty() == true,
            title = "以此为模板 · 选择加入位置",
            initialPosition = EditorInsertPosition.BELOW,
            allowReplace = false,
            onDismiss = { reflectionVisible = false },
            onConfirm = { position ->
                reflectionVisible = false
                val nodeId = reflectionNodeId
                if (nodeId != null && nodeId == displayedSelectedNodeId && reflectionSource == programSource) {
                    val slots = programNodes?.firstOrNull { it.nodeId == nodeId }?.childSlots.orEmpty()
                    if (position == EditorInsertPosition.INSIDE && slots.size > 1) reflectionBranchVisible = true
                    else onProgramNodeReflected?.invoke(nodeId, position, slots.singleOrNull())
                }
            },
        )
    }
    if (reflectionBranchVisible && editingEnabled) {
        StudioConfirmDialog(
            onDismissRequest = { reflectionBranchVisible = false },
            title = { Text("选择加入分支") },
            text = {
                Column {
                    programNodes?.firstOrNull { it.nodeId == reflectionNodeId }?.childSlots.orEmpty().forEach { slot ->
                        TextButton(onClick = {
                            reflectionBranchVisible = false
                            val nodeId = reflectionNodeId
                            if (nodeId != null && nodeId == displayedSelectedNodeId && reflectionSource == programSource) {
                                onProgramNodeReflected?.invoke(nodeId, EditorInsertPosition.INSIDE, slot)
                            }
                        }) { Text(childBlockLabel(slot)) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { reflectionBranchVisible = false }) { Text("取消") } },
        )
    }

    if (nodeActionsVisible && editingEnabled) {
        LaunchedEffect(Unit) {
            nodeActionsVisible = false
            if (externallyManaged) nodeEditorVisible = true else editSelectedParameters()
        }
    }

    if (nodeEditorVisible) {
        EditorNodeEditDialog(
            nodes = displayedTreeNodes,
            selectedNodeId = displayedSelectedNodeId,
            selectedNodeIds = if (externallyManaged) programSelectedNodeIds else setOfNotNull(displayedSelectedNodeId),
            onToggleNode = if (externallyManaged) onProgramNodeSelectionToggled else null,
            onSelectNode = { nodeId ->
                if (externallyManaged) onProgramNodeSelected(nodeId) else selectedNodeId = nodeId
            },
            onEditSelected = ::editSelectedParameters,
            onReuseSelected = if (externallyManaged && onProgramNodeReflected != null) {
                { nodeEditorVisible = false; reuseSelectedParameters() }
            } else null,
            onAnnotateSelected = onProgramNodeAnnotated?.let { annotate ->
                { nodeEditorVisible = false; annotate() }
            },
            onDeleteSelected = ::requestDelete,
            disabledSelection = programNodes?.filter { it.nodeId in effectiveSelection }?.let { it.isNotEmpty() && it.all { node -> node.disabled } } == true,
            onCommand = onProgramCommand,
            canUndo = canUndoProgram,
            canRedo = canRedoProgram,
            canPaste = hasProgramClipboard,
            onEditSource = onApplyProgramSource?.let { { nodeEditorVisible = false; sourceEditorVisible = true } },
            onDismiss = { nodeEditorVisible = false },
        )
    }

    if (consoleVisible) StudioPanelDialog(title = "运行日志", onDismiss = { consoleVisible = false }) {
        Box(Modifier.fillMaxWidth().heightIn(max = 420.dp).height(360.dp)) { EditorConsolePanel(consoleLines) }
    }
    if (sourceEditorVisible && programSource != null && onApplyProgramSource != null && editingEnabled) {
        val root = projectFlows.firstOrNull { it.flowId == currentFlowId }?.rootBlockId
        if (root != null) androidx.compose.runtime.key(currentFlowId) {
            val flow = projectFlows.first { it.flowId == currentFlowId }
            val declared = projectVariables.filter {
                it.scope == com.autoscript.project.store.ProjectVariableScope.GLOBAL || it.flowId == currentFlowId
            }.map { it.name }.toSet() + flow.params.mapNotNull { it.get("name")?.takeIf { name -> name.isJsonPrimitive }?.asString }
            PluginSourceEditorDialog(programSource, root, declared,
                onApplyProgramSource, { sourceEditorVisible = false })
        }
    }

    if (functionLibrary) {
        val functionGroups = remember(externallyManaged) {
            if (externallyManaged) FunctionCatalog.visualGroups() else FunctionCatalog.luaGroups()
        }
        FunctionLibraryDialog(
            groups = functionGroups,
            capabilities = capabilities,
            onDismiss = { functionLibrary = false; editingNodeId = null },
            onInsert = { snippet ->
                val editingId = editingNodeId
                if (editingId == null) {
                    pendingInsertSnippet = snippet
                } else if (!externallyManaged) {
                    val editingIndex = treeNodes.indexOfFirst { it.id == editingId }
                    val old = treeNodes.getOrNull(editingIndex)
                    if (old != null) {
                        val replacement = legacyTreeNode(old.id, EditorToolPanel.FUNCTIONS, snippet)
                        treeNodes = treeNodes.toMutableList().also {
                            it[editingIndex] = replacement.copy(
                                depth = old.depth,
                                container = replacement.container || legacyHasChildren(treeNodes, editingIndex),
                                expanded = old.expanded,
                            )
                        }
                    }
                    onInsert(snippet)
                } else {
                    onProgramNodeEdited()
                }
                editingNodeId = null
                functionLibrary = false
            },
        )
    }
    if (inputActionDialogVisible) {
        VisualInputActionDialog(
            canPickPoint = onOpenInputPointPicker != null && editingEnabled,
            backendFeatures = inputBackendFeatures,
            onOpenPermissions = onOpenPermissions,
            onRefresh = onRefreshInputCapabilities,
            onDismiss = { inputActionDialogVisible = false },
            onPick = { mode ->
                inputActionDialogVisible = false
                onOpenInputPointPicker?.invoke(mode)
            },
            onFloatingPick = onOpenInputFloatingPicker?.let { open -> { mode ->
                inputActionDialogVisible = false
                expanded = false
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
            enabled = externallyManaged && sourceTree != null,
            onDismiss = { pluginManagerVisible = false },
            onConfirm = { action ->
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
        if (onOpenDebugTool != null && dialog in setOf(EditorToolPanel.DATA_BACKFILL, EditorToolPanel.VARIABLE_CHECK, EditorToolPanel.RUNTIME_VARIABLES)) {
            LaunchedEffect(dialog) { toolDialog = null; onOpenDebugTool(dialog) }
            return@let
        }
        EditorToolPanelDialog(
            dialog = dialog,
            projectName = projectName,
            files = projectFiles,
            onDismiss = { toolDialog = null; editingNodeId = null },
            onOpenFile = { file -> toolDialog = null; onOpenProjectFile(file) },
            onDeleteFiles = onDeleteProjectFiles,
            onOpenImageTools = onOpenImageTools?.let { open -> { delayMillis -> toolDialog = null; open(delayMillis) } },
            onOpenImageToolMode = onOpenImageToolMode?.let { open -> { mode, delayMillis -> toolDialog = null; open(mode, delayMillis) } },
            onCreateImageBlock = onCreateImageBlock?.let { create -> { kind -> toolDialog = null; create(kind) } },
            onOpenImageLibrary = onOpenImageLibrary?.let { open -> { toolDialog = null; open() } },
            onTestRecognition = onTestRecognition?.let { test -> { toolDialog = null; test() } },
            availableVariables = availableVariables,
            availableLabels = availableLabels,
            projectVariables = projectVariables,
            currentFlowId = currentFlowId.orEmpty(),
            projectFlows = projectFlows,
            onManageVariables = onManageVariables,
            visualMode = externallyManaged,
            debugSettings = debugSettings,
            onSaveDebugSettings = onSaveDebugSettings,
        ) { snippet ->
            val editingId = editingNodeId
            val command = legacyDockProgramCommand(snippet)
            if (command != null) {
                when (command) {
                    EditorProgramCommand.EXPAND_ALL -> if (externallyManaged) {
                        collapsedProgramNodeIds = emptyList()
                    } else {
                        treeNodes = treeNodes.map { it.copy(expanded = true) }
                    }
                    EditorProgramCommand.COLLAPSE_ALL -> if (externallyManaged) {
                        collapsedProgramNodeIds = displayedTreeNodes.filter(EditorTreeNode::container).map(EditorTreeNode::id)
                        val selectedIndex = displayedTreeNodes.indexOfFirst { it.id == displayedSelectedNodeId }
                        if (selectedIndex >= 0 && displayedTreeNodes[selectedIndex].depth > 0) {
                            displayedTreeNodes.subList(0, selectedIndex + 1).lastOrNull { it.depth == 0 }
                                ?.let { onProgramNodeSelected(it.id) }
                        }
                    } else {
                        treeNodes = treeNodes.map { it.copy(expanded = !it.container) }
                        val selectedIndex = treeNodes.indexOfFirst { it.id == selectedNodeId }
                        if (selectedIndex >= 0 && treeNodes[selectedIndex].depth > 0) {
                            selectedNodeId = treeNodes.subList(0, selectedIndex + 1).lastOrNull { it.depth == 0 }?.id
                        }
                    }
                    EditorProgramCommand.SEARCH -> searchingProgram = true
                    else -> onProgramCommand(command)
                }
            } else if (editingId == null) {
                pendingInsertSnippet = snippet
            } else if (!externallyManaged) {
                val editingIndex = treeNodes.indexOfFirst { it.id == editingId }
                val keepsChildren = legacyHasChildren(treeNodes, editingIndex)
                treeNodes = treeNodes.mapIndexed { index, old ->
                    if (index == editingIndex) {
                        val replacement = legacyTreeNode(old.id, dialog, snippet)
                        replacement.copy(
                            depth = old.depth,
                            container = replacement.container || keepsChildren,
                            expanded = old.expanded,
                        )
                    } else old
                }
                onInsert(snippet)
            } else {
                onProgramNodeEdited()
            }
            editingNodeId = null
        }
    }
    pendingInsertSnippet?.let { snippet ->
        EditorInsertPositionDialog(
            hasSelection = displayedSelectedNodeId != null,
            canInsertInside = displayedTreeNodes.firstOrNull { it.id == displayedSelectedNodeId }?.container == true,
            onDismiss = { pendingInsertSnippet = null },
            onConfirm = { position ->
                if (externallyManaged) {
                    onInsertPositioned?.invoke(snippet, position) ?: onInsert(snippet)
                } else {
                    val node = legacyTreeNode("legacy-${nextNodeId++}", legacyEntryForSnippet(snippet), snippet)
                    val selectedIndex = treeNodes.indexOfFirst { it.id == selectedNodeId }
                    val selected = treeNodes.getOrNull(selectedIndex)
                    when (position) {
                        EditorInsertPosition.LIST_BOTTOM -> treeNodes = treeNodes + node.copy(depth = 0)
                        EditorInsertPosition.ABOVE -> {
                            val index = selectedIndex.takeIf { it >= 0 } ?: treeNodes.size
                            treeNodes = treeNodes.toMutableList().also { it.add(index, node.copy(depth = selected?.depth ?: 0)) }
                        }
                        EditorInsertPosition.BELOW -> {
                            var index = if (selectedIndex >= 0) selectedIndex + 1 else treeNodes.size
                            while (selected != null && index < treeNodes.size && treeNodes[index].depth > selected.depth) index++
                            treeNodes = treeNodes.toMutableList().also { it.add(index, node.copy(depth = selected?.depth ?: 0)) }
                        }
                        EditorInsertPosition.INSIDE -> {
                            if (selectedIndex >= 0 && selected?.container == true) {
                                treeNodes = treeNodes.toMutableList().also {
                                    it[selectedIndex] = selected.copy(expanded = true)
                                    it.add(selectedIndex + 1, node.copy(depth = selected.depth + 1))
                                }
                            } else {
                                treeNodes = treeNodes + node.copy(depth = 0)
                            }
                        }
                        EditorInsertPosition.REPLACE -> {
                            if (selectedIndex >= 0 && selected != null) {
                                treeNodes = treeNodes.toMutableList().also {
                                    it[selectedIndex] = node.copy(
                                        depth = selected.depth,
                                        container = node.container || legacyHasChildren(treeNodes, selectedIndex),
                                        expanded = selected.expanded,
                                    )
                                }
                            } else treeNodes = treeNodes + node.copy(depth = 0)
                        }
                    }
                    selectedNodeId = node.id
                    onInsert(snippet)
                }
                pendingInsertSnippet = null
            },
        )
    }
    if (searchingProgram) {
        EditorProgramSearchDialog(
            nodes = displayedTreeNodes,
            onDismiss = { searchingProgram = false },
            onReplace = onReplaceProgramText.takeIf { editingEnabled },
            programSource = programSource,
            onSelect = { nodeId ->
                collapsedProgramNodeIds = emptyList()
                if (externallyManaged) onProgramNodeSelected(nodeId) else selectedNodeId = nodeId
                searchingProgram = false
            },
        )
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
    SEARCH,
    EXPAND_ALL,
    COLLAPSE_ALL,
    DATA_BACKFILL,
    TOGGLE_DISABLED,
}

private const val LEGACY_DOCK_COMMAND_PREFIX = "--@autoscript-editor:"

private fun legacyDockProgramCommand(snippet: String): EditorProgramCommand? = snippet
    .lineSequence().firstOrNull()?.removePrefix(LEGACY_DOCK_COMMAND_PREFIX)?.trim()
    ?.takeIf(String::isNotEmpty)?.let { value ->
        runCatching { EditorProgramCommand.valueOf(value) }.getOrNull()
    }

private data class EditorTreeNode(
    val id: String,
    val label: String,
    val code: String,
    val entry: EditorToolPanel,
    val depth: Int = 0,
    val container: Boolean = false,
    val expanded: Boolean = true,
)

private const val LEGACY_TREE_SAVED_STATE_LIMIT = 256 * 1024
private const val LEGACY_TREE_SAVED_NODE_LIMIT = 4_096
private const val LEGACY_TREE_SAVED_STATE_VERSION = 1

private val EditorTreeNodesStateSaver = Saver<
    androidx.compose.runtime.MutableState<List<EditorTreeNode>>,
    String,
>(
    save = { state ->
        state.value.takeIf { it.size <= LEGACY_TREE_SAVED_NODE_LIMIT }
            ?.let(::encodeLegacyTreeNodes)
            ?.takeIf { it.length <= LEGACY_TREE_SAVED_STATE_LIMIT }
    },
    restore = { encoded -> mutableStateOf(decodeLegacyTreeNodes(encoded)) },
)

private fun encodeLegacyTreeNodes(nodes: List<EditorTreeNode>): String {
    val array = JSONArray()
    nodes.forEach { node ->
        array.put(
            JSONObject()
                .put("id", node.id)
                .put("label", node.label)
                .put("code", node.code)
                .put("entry", node.entry.name)
                .put("depth", node.depth)
                .put("container", node.container)
                .put("expanded", node.expanded),
        )
    }
    return JSONObject()
        .put("version", LEGACY_TREE_SAVED_STATE_VERSION)
        .put("nodes", array)
        .toString()
}

private fun decodeLegacyTreeNodes(encoded: String): List<EditorTreeNode> = runCatching {
    val document = JSONObject(encoded)
    require(document.getInt("version") == LEGACY_TREE_SAVED_STATE_VERSION)
    val array = document.getJSONArray("nodes")
    require(array.length() <= LEGACY_TREE_SAVED_NODE_LIMIT)
    buildList(array.length()) {
        val ids = hashSetOf<String>()
        var previousDepth = 0
        repeat(array.length()) { index ->
            val item = array.getJSONObject(index)
            val id = item.getString("id")
            require(id.matches(Regex("[0-9A-Za-z._-]{1,128}")) && ids.add(id))
            val rawDepth = item.getInt("depth").coerceIn(0, 128)
            val normalizedDepth = if (index == 0) 0 else rawDepth.coerceAtMost(previousDepth + 1)
            add(
                EditorTreeNode(
                    id = id,
                    label = item.getString("label").take(160),
                    code = item.getString("code"),
                    entry = EditorToolPanel.valueOf(item.getString("entry")),
                    depth = normalizedDepth,
                    container = item.optBoolean("container", false),
                    expanded = item.optBoolean("expanded", true),
                ),
            )
            previousDepth = normalizedDepth
        }
    }
}.getOrElse { emptyList() }

private fun legacyTreeNode(id: String, entry: EditorToolPanel, code: String): EditorTreeNode {
    val line = code.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
    val label = when {
        line.contains("findImage", true) -> "找图  ${line.substringAfter('(').substringBefore(',').trim()}"
        line.contains("findColor", true) -> "找色  ${line.substringAfter('(').substringBefore(')').trim()}"
        line.contains("findText", true) -> "找字  ${line.substringAfter('(').substringBefore(')').trim()}"
        line.startsWith("Input.tap", true) -> "点击坐标  ${legacyArguments(line)}"
        line.startsWith("Input.swipe", true) -> "滑动  ${legacyArguments(line)}"
        line.startsWith("File.readText", true) -> "读取文本  ${legacyArguments(line)}"
        line.startsWith("File.writeText", true) -> "写入文本  ${legacyArguments(line)}"
        line.startsWith("Net.get", true) -> "网络请求  ${legacyArguments(line)}"
        line.startsWith("Net.download", true) -> "下载文件  ${legacyArguments(line)}"
        line.startsWith("Capture.", true) -> "屏幕截图"
        line.startsWith("Data.backfill", true) -> "数据回填"
        line.startsWith("Runtime.setParameter", true) -> "设置调用参数  ${legacyArguments(line)}"
        line.startsWith("local value = Runtime.getParameter", true) -> "获取调用参数  ${legacyArguments(line)}"
        line == "break" -> "跳出循环"
        line == "return" -> "返回上层"
        line.startsWith("goto ") -> "跳转标记  ${line.removePrefix("goto ")}"
        line.startsWith("::") -> "放置标记  ${line.removeSurrounding("::")}"
        line.startsWith("if ") -> "判断  ${line.removePrefix("if ").substringBefore(" then")}" 
        line.startsWith("while ") || line.startsWith("for ") || line.startsWith("repeat") -> "循环  $line"
        line.contains("sleep", true) -> "等待时间  ${legacyArguments(line)}"
        line.contains("Log.", true) -> "调试输出  ${legacyArguments(line)}"
        line.contains("Debug.", true) -> "调试断点"
        line.startsWith("-- ") -> line.removePrefix("-- ").take(42)
        line.isNotBlank() -> line.take(42)
        else -> when (entry) {
            EditorToolPanel.FILES -> "文件操作"
            EditorToolPanel.TOOLS -> "工具操作"
            EditorToolPanel.IMAGE -> "图像识别"
            EditorToolPanel.JUDGMENT -> "判断"
            EditorToolPanel.LOOP -> "循环"
            EditorToolPanel.COMMON -> "常用操作"
            EditorToolPanel.FUNCTIONS -> "函数"
            EditorToolPanel.DEBUG -> "调试"
            EditorToolPanel.AI -> "AI 节点"
            else -> "程序节点"
        }
    }
    val isContainer = entry == EditorToolPanel.JUDGMENT || entry == EditorToolPanel.LOOP ||
        line.startsWith("if ") || line.startsWith("while ") || line.startsWith("for ") || line.startsWith("repeat")
    return EditorTreeNode(id, label, code, entry, container = isContainer)
}

private fun legacyArguments(line: String): String =
    line.substringAfter('(', "").substringBeforeLast(')', "").trim().take(34)

private fun insertLegacyTreeNode(nodes: List<EditorTreeNode>, selectedId: String?, node: EditorTreeNode): List<EditorTreeNode> {
    val selectedIndex = nodes.indexOfFirst { it.id == selectedId }
    if (selectedIndex < 0) return nodes + node.copy(depth = 0)
    val selected = nodes[selectedIndex]
    if (selected.container) {
        return nodes.toMutableList().also {
            it[selectedIndex] = selected.copy(expanded = true)
            it.add(selectedIndex + 1, node.copy(depth = selected.depth + 1))
        }
    }
    var insertion = selectedIndex + 1
    while (insertion < nodes.size && nodes[insertion].depth > selected.depth) insertion++
    return nodes.toMutableList().also { it.add(insertion, node.copy(depth = selected.depth)) }
}

private fun removeLegacyTreeSubtree(nodes: List<EditorTreeNode>, nodeId: String): List<EditorTreeNode> {
    val start = nodes.indexOfFirst { it.id == nodeId }
    if (start < 0) return nodes
    val depth = nodes[start].depth
    var end = start + 1
    while (end < nodes.size && nodes[end].depth > depth) end++
    return nodes.filterIndexed { index, _ -> index !in start until end }
}

private fun legacyHasChildren(nodes: List<EditorTreeNode>, index: Int): Boolean =
    index in nodes.indices && index + 1 < nodes.size && nodes[index + 1].depth > nodes[index].depth

private fun legacyIsDescendant(nodes: List<EditorTreeNode>, parentIndex: Int, nodeId: String?): Boolean {
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

private val EditorDockGreen = Color(0xFF3D8C3D)
private val EditorDockGreenDark = Color(0xFF175E20)
private val FunctionLibraryBlue = Color(0xFF339DFF)
private val EditorBlue = Color(0xFF3A6EFF)
private val EditorDisabledBlue = Color(0xFFAFC5FF)
private val EditorBorder = Color(0xFFC7CBD1)
internal enum class EditorToolPanel { FILES, TOOLS, IMAGE, JUDGMENT, LOOP, COMMON, FUNCTIONS, RECORDING, DEBUG, AI, DATA_BACKFILL, VARIABLE_CHECK, RUNTIME_VARIABLES }

/** 旧版 P06「插件管理」的八个单选项；在本项目里“插件”= Flow（源文件）。 */
internal enum class PluginManagerAction(val label: String) {
    CREATE("插件创建"),
    DELETE("插件删除"),
    SAVE_AS("插件另存"),
    CHECK("插件检错"),
    CHECK_ALL("全部插件检错"),
    GROUP("插件分组"),
    TEMPLATE("存储为模版"),
    UNUSED("未调用插件"),
}

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
)

private fun legacyEntryForKind(kind: String): EditorToolPanel = when {
    kind.startsWith("vision.") || kind.startsWith("ocr.") -> EditorToolPanel.IMAGE
    kind.startsWith("control.if") -> EditorToolPanel.JUDGMENT
    kind.startsWith("control.repeat") || kind.startsWith("control.while") || kind == "control.cycle" -> EditorToolPanel.LOOP
    kind.startsWith("file.") -> EditorToolPanel.FILES
    kind.startsWith("debug.") || kind.startsWith("log.") || kind in setOf("task.prompt", "task.runprompt", "task.message", "task.log", "task.comment") -> EditorToolPanel.DEBUG
    kind.startsWith("ai.") -> EditorToolPanel.AI
    kind.startsWith("function.") || kind == "flow.call" -> EditorToolPanel.FUNCTIONS
    else -> EditorToolPanel.COMMON
}

private fun legacyEntryForSnippet(snippet: String): EditorToolPanel {
    val line = snippet.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
    return when {
        line.contains("Vision.", true) -> EditorToolPanel.IMAGE
        line.startsWith("if ") || line.startsWith("-- 否则") -> EditorToolPanel.JUDGMENT
        line.startsWith("while ") || line.startsWith("for ") || line.startsWith("repeat") -> EditorToolPanel.LOOP
        line.contains("File.", true) -> EditorToolPanel.FILES
        line.contains("Log.", true) || line.contains("Debug.", true) -> EditorToolPanel.DEBUG
        line.contains("Input.", true) || line.contains("Accessibility.", true) -> EditorToolPanel.FUNCTIONS
        else -> EditorToolPanel.COMMON
    }
}

@Composable
private fun EditorBallIcon(modifier: Modifier = Modifier, description: String = "智构 · 打开编辑器") {
    Image(
        painter = painterResource(R.drawable.zhigou_brand_mark),
        contentDescription = description,
        modifier = modifier.padding(2.dp),
    )
}

@Composable
private fun EditorDockPanel(
    projectName: String,
    title: String,
    hasSource: Boolean,
    canSelectFlow: Boolean,
    nodes: List<EditorTreeNode>,
    selectedNodeId: String?,
    currentNodeId: String?,
    executionStatus: String?,
    editingEnabled: Boolean,
    running: Boolean,
    paused: Boolean,
    resumeEnabled: Boolean,
    onResume: () -> Unit,
    onPause: () -> Unit,
    runEnabled: Boolean,
    onSettings: () -> Unit,
    onMinimize: () -> Unit,
    onClose: () -> Unit,
    onSelectFlow: () -> Unit,
    onManageVariables: () -> Unit,
    onRun: () -> Unit,
    onStop: () -> Unit,
    onStep: () -> Unit,
    stepEnabled: Boolean,
    selectionCount: Int,
    selectedNodeIds: Set<String>,
    onFunctions: () -> Unit,
    onKeys: () -> Unit,
    onTool: (EditorToolPanel) -> Unit,
    onSelectNode: (String) -> Unit,
    onToggleNode: (String) -> Unit,
    onEditSelection: () -> Unit,
    onNodeActions: (String) -> Unit,
    onOpenMoreMenu: () -> Unit,
    onMoreAnchor: (Rect) -> Unit,
    onToggleConsole: () -> Unit,
    modifier: Modifier,
    onDrag: (Offset) -> Unit,
    onResize: (Offset) -> Unit,
) {
    val visibleNodes = remember(nodes) { legacyVisibleTreeNodes(nodes) }
    val nodeIndexes = remember(nodes) { nodes.mapIndexed { index, node -> node.id to index }.toMap() }
    val treeListState = rememberLazyListState()
    val treeHorizontalState = rememberScrollState()
    // pointerInput 只在首次组合时捕获回调；面板缩放、旋转后边界会变，所以每次都取最新的 onDrag。
    val currentOnDrag by rememberUpdatedState(onDrag)
    LaunchedEffect(selectedNodeId, currentNodeId, visibleNodes) {
        val selectedIndex = visibleNodes.indexOfFirst { it.id == (currentNodeId ?: selectedNodeId) }
        val alreadyVisible = treeListState.layoutInfo.visibleItemsInfo.any { it.index == selectedIndex }
        if (selectedIndex >= 0 && !alreadyVisible) treeListState.animateScrollToItem(selectedIndex)
    }
    Surface(
        modifier = modifier,
        color = Color.White,
        shape = RoundedCornerShape(2.dp),
        shadowElevation = 7.dp,
    ) {
        Column {
            Box(
                modifier = Modifier.fillMaxWidth().height(40.dp).background(Color.White),
            ) {
                Box(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 64.dp, vertical = 6.dp)
                        .background(if (canSelectFlow) Color(0xFFF2F5FF) else Color.Transparent, RoundedCornerShape(6.dp))
                        .pointerInput(projectName, "title-drag") {
                            detectDragGestures { change, delta ->
                                change.consume()
                                currentOnDrag(delta)
                            }
                        }
                        .clickable(enabled = canSelectFlow, onClick = onSelectFlow),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        title,
                        color = if (canSelectFlow) EditorBlue else Color(0xFF1C2333),
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = if (canSelectFlow) 18.dp else 4.dp),
                        style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false)),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                    if (canSelectFlow) {
                        Icon(
                            painterResource(R.drawable.editor_chevron_down_16),
                            contentDescription = "选择插件文件",
                            tint = EditorBlue,
                            modifier = Modifier.align(Alignment.CenterEnd).padding(end = 3.dp).size(14.dp),
                        )
                    }
                }
                Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                    EditorDockHeaderButton(
                        description = "打开项目设置",
                        drawable = R.drawable.editor_settings_24,
                        onClick = onSettings,
                    )
                    Spacer(Modifier.weight(1f))
                    EditorDockHeaderButton(
                        description = "最小化为吸附球",
                        drawable = R.drawable.editor_minimize_24,
                        onClick = onMinimize,
                    )
                    EditorDockHeaderButton(
                        description = "关闭编辑面板",
                        drawable = R.drawable.editor_close_24,
                        onClick = onClose,
                    )
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFFF0F1F4)))
            executionStatus?.let { status ->
                Box(Modifier.fillMaxWidth().height(20.dp).background(Color(0xFFFFF5DF)).padding(horizontal = 6.dp), contentAlignment = Alignment.CenterStart) {
                    Text(status, fontSize = 10.sp, color = Color(0xFF855C12), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Row(Modifier.fillMaxWidth().weight(1f)) {
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    if (selectionCount > 1) {
                        Box(Modifier.fillMaxWidth().height(20.dp).background(Color(0xFFF8FAFD)).padding(horizontal = 5.dp),
                            contentAlignment = Alignment.CenterStart) {
                            Text("已选 $selectionCount 项", fontSize = 10.sp, color = AutoScriptPalette.TextSecondary, maxLines = 1)
                        }
                    }
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        if (hasSource) {
                            Box(Modifier.fillMaxSize().horizontalScroll(treeHorizontalState)) {
                                LazyColumn(
                                    state = treeListState,
                                    modifier = Modifier.width(960.dp).fillMaxHeight(),
                                ) {
                                    itemsIndexed(visibleNodes, key = { _, node -> node.id }) { visibleIndex, node ->
                                        val fullIndex = nodeIndexes[node.id] ?: -1
                                        EditorProgramTreeRow(
                                            node = node,
                                            hasChildren = legacyHasChildren(nodes, fullIndex),
                                            selected = node.id in selectedNodeIds,
                                            current = currentNodeId == node.id,
                                            onSelect = { onSelectNode(node.id) },
                                            onToggle = { onToggleNode(node.id) },
                                            onLongClick = { onNodeActions(node.id) },
                                        )
                                        if (visibleIndex != visibleNodes.lastIndex) {
                                            Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFFF0F1F4)))
                                        }
                                    }
                                }
                            }
                        } else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("空任务", color = AutoScriptPalette.TextSecondary, fontSize = 12.sp)
                        }
                    }
                }
                Column(modifier = Modifier.width(51.dp).verticalScroll(rememberScrollState()).padding(horizontal = 3.dp, vertical = 2.dp)) {
                    EditorDockAction("变量", editingEnabled, onManageVariables)
                    EditorDockAction("按键", editingEnabled, onKeys)
                    EditorDockAction("图像", editingEnabled) { onTool(EditorToolPanel.IMAGE) }
                    EditorDockAction("判断", editingEnabled) { onTool(EditorToolPanel.JUDGMENT) }
                    EditorDockAction("循环", editingEnabled) { onTool(EditorToolPanel.LOOP) }
                    EditorDockAction("跳转", editingEnabled) { onTool(EditorToolPanel.COMMON) }
                    EditorDockAction("调试", editingEnabled) { onTool(EditorToolPanel.DEBUG) }
                    EditorDockAction("函数", editingEnabled, onFunctions)
                    EditorDockAction("AI", editingEnabled) { onTool(EditorToolPanel.AI) }
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFFF0F1F4)))
            BoxWithConstraints(Modifier.fillMaxWidth().background(Color.White)) {
                val entries = editorToolbarEntries(running, paused, runEnabled, resumeEnabled,
                    stepEnabled, hasSource, editingEnabled)
                val columns = editorToolbarColumns(maxWidth.value)
                val rows = entries.chunked(columns)
                val showLabels = maxWidth.value >= columns * 44 + 24
                Row(Modifier.fillMaxWidth().height((rows.size * 44).dp)) {
                    Column(Modifier.weight(1f).fillMaxHeight()) {
                        rows.forEach { row ->
                            Row(Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically) {
                                row.forEach { entry ->
                                    val callback = when (entry.action) {
                                        EditorToolbarAction.RUN -> onRun
                                        EditorToolbarAction.PAUSE -> onPause
                                        EditorToolbarAction.RESUME -> onResume
                                        EditorToolbarAction.STOP -> onStop
                                        EditorToolbarAction.STEP -> onStep
                                        EditorToolbarAction.EDIT -> onEditSelection
                                        EditorToolbarAction.LOG -> onToggleConsole
                                        EditorToolbarAction.MORE -> onOpenMoreMenu
                                        else -> error("Editing commands belong to the edit dialog")
                                    }
                                    EditorBottomButton(entry.action, entry.enabled, showLabels, callback,
                                        Modifier.weight(1f).then(if (entry.action == EditorToolbarAction.MORE)
                                            Modifier.onGloballyPositioned { onMoreAnchor(it.boundsInRoot()) } else Modifier))
                                }
                                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                            }
                        }
                    }
                    Box(Modifier.width(24.dp).fillMaxHeight(), contentAlignment = Alignment.BottomEnd) {
                        ResizeHandle(Modifier.size(24.dp).padding(3.dp), onResize)
                    }
                }
            }
        }
    }
}

/**
 * `line_tk_console_container`：参考的性能行（CPU/内存）默认 GONE、只在“性能信息”开关打开时出现，
 * 我们没有性能采样，所以不画那行占位。控制台显示脚本输出、引擎/Root 生命周期与失败诊断。
 */
@Composable
private fun EditorConsolePanel(lines: List<String>) {
    var query by remember { mutableStateOf("") }
    var follow by remember { mutableStateOf(true) }
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val visible = remember(lines, query) { lines.takeLast(1000).filter { it.contains(query, ignoreCase = true) } }
    val listState = rememberLazyListState()
    LaunchedEffect(visible.size, visible.lastOrNull(), follow) {
        if (follow && visible.isNotEmpty()) listState.animateScrollToItem(visible.lastIndex)
    }
    Column(Modifier.fillMaxSize().background(Color.White)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("显示 ${visible.size} 项", fontSize = 10.sp, modifier = Modifier.weight(1f))
            TextButton(onClick = { follow = !follow }) { Text(if (follow) "跟随最新 ✓" else "跟随最新", fontSize = 11.sp) }
            TextButton(enabled = visible.isNotEmpty(), onClick = {
                clipboard.setText(androidx.compose.ui.text.AnnotatedString(visible.joinToString("\n")))
            }) { Text("复制", fontSize = 11.sp) }
        }
        Box(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) { InspectorSearchField(query, { query = it.take(128) }, "筛选日志（最多显示最近1000项）") }
        if (visible.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
                Text(
                    if (lines.isEmpty()) "暂无运行记录\nRunner 状态/诊断与脚本调试日志显示在这里；面向脚本使用者的运行提示会单独显示" else "没有符合筛选的日志",
                    color = Color(0xFF777777),
                    fontSize = 11.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        } else {
            LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 8.dp), state = listState) {
                itemsIndexed(visible) { _, line ->
                    androidx.compose.foundation.text.selection.SelectionContainer {
                        Text(
                            line,
                            color = if (editorConsoleLineIsFailure(line)) AutoScriptPalette.Danger else Color.Black,
                            fontSize = 10.sp,
                            modifier = Modifier.padding(vertical = 2.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EditorMorePopup(
    modifier: Modifier,
    onShowMain: () -> Unit,
    canShowInterface: Boolean,
    canRecord: Boolean,
    onRecord: () -> Unit,
    onVariableCheck: () -> Unit,
    onSearchReplace: () -> Unit,
    onRuntimeVariables: () -> Unit,
    editingEnabled: Boolean,
) {
    data class MenuRow(val label: String, @DrawableRes val icon: Int, val enabled: Boolean, val action: () -> Unit)
    val rows = listOf(
        MenuRow("界面预览", R.drawable.editor_more_show_ui_24, canShowInterface, onShowMain),
        MenuRow(if (canRecord) "录制动作" else "录制动作（需Root支持）", R.drawable.editor_more_record_24, canRecord, onRecord),
        MenuRow("变量检查", R.drawable.editor_more_variable_check_24, true, onVariableCheck),
        MenuRow("查找替换", R.drawable.editor_more_search_24, editingEnabled, onSearchReplace),
        MenuRow("运行变量", R.drawable.editor_more_runtime_variables_24, true, onRuntimeVariables),
    )
    Surface(
        modifier = modifier.width(148.dp),
        color = Color.White,
        shape = RoundedCornerShape(topStart = 2.dp, topEnd = 2.dp, bottomEnd = 2.dp),
        shadowElevation = 8.dp,
    ) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            rows.forEach { (label, icon, enabled, action) ->
                Row(
                    Modifier.fillMaxWidth().height(44.dp).clickable(enabled = enabled, onClick = action).padding(start = 8.dp, end = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        painter = painterResource(icon),
                        contentDescription = null,
                        tint = if (enabled) EditorBlue else EditorDisabledBlue,
                        modifier = Modifier.size(24.dp),
                    )
                    Text(label, color = if (enabled) Color(0xFF161B27) else AutoScriptPalette.TextSecondary, fontSize = 12.sp, maxLines = 2, modifier = Modifier.padding(start = 8.dp))
                }
            }
        }
    }
}


/**
 * 有界多选小窗：顶部参数/模板与选择操作，中部滚动树，底部四列命令网格。
 */
@Composable
private fun EditorNodeEditDialog(
    nodes: List<EditorTreeNode>,
    selectedNodeId: String?,
    selectedNodeIds: Set<String>,
    onToggleNode: ((String) -> Unit)?,
    onSelectNode: (String) -> Unit,
    onEditSelected: () -> Unit,
    onReuseSelected: (() -> Unit)?,
    onAnnotateSelected: (() -> Unit)?,
    onDeleteSelected: () -> Unit,
    disabledSelection: Boolean,
    onCommand: (EditorProgramCommand) -> Unit,
    canUndo: Boolean,
    canRedo: Boolean,
    canPaste: Boolean,
    onEditSource: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    val selectedIndex = nodes.indexOfFirst { it.id == selectedNodeId }
    val checkedIds = selectedNodeIds.ifEmpty { setOfNotNull(selectedNodeId) }
    val hasSelection = nodes.any { it.id in checkedIds }
    val singleSelection = selectedIndex >= 0 && checkedIds.size == 1
    val breadcrumb = remember(nodes, selectedIndex) { legacyBreadcrumb(nodes, selectedIndex) }
    StudioPanelDialog(title = "编辑 · 已选 ${checkedIds.size} 项", onDismiss = onDismiss) {
            Column(Modifier.fillMaxWidth().heightIn(max = 430.dp)) {
                Row(Modifier.fillMaxWidth().padding(5.dp).height(40.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (checkedIds.size > 1) "已选择 ${checkedIds.size} 个节点（包含子树）" else breadcrumb.ifBlank { "请选择程序节点" },
                        color = EditorBlue,
                        fontSize = 11.sp,
                        lineHeight = 12.sp,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(start = 5.dp),
                    )
                    onReuseSelected?.let { reuse ->
                        Text("以此为模板", color = if (singleSelection) EditorBlue else EditorDisabledBlue, fontSize = 11.sp,
                            modifier = Modifier.clickable(enabled = singleSelection, onClick = reuse).padding(horizontal = 8.dp, vertical = 8.dp))
                    }
                    Text(
                        "参数",
                        color = if (singleSelection) EditorBlue else EditorDisabledBlue,
                        fontSize = 11.sp,
                        modifier = Modifier.clickable(enabled = singleSelection, onClick = onEditSelected)
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
                Row(Modifier.fillMaxWidth().height(32.dp), verticalAlignment = Alignment.CenterVertically) {
                    onEditSource?.let { edit ->
                        Text("源码", fontSize = 11.sp, color = EditorBlue,
                            modifier = Modifier.weight(1f).fillMaxHeight().clickable(onClick = edit).wrapContentSize(Alignment.Center))
                    }
                    Text("全选（最多256项）", fontSize = 11.sp, color = EditorBlue,
                        modifier = Modifier.weight(1f).fillMaxHeight().clickable(enabled = onToggleNode != null) {
                            editorSelectionToggleIds(nodes.map { it.id }, checkedIds, selectAll = true).forEach { onToggleNode?.invoke(it) }
                        }.wrapContentSize(Alignment.Center))
                    Text("清除选择", fontSize = 11.sp, color = EditorBlue,
                        modifier = Modifier.weight(1f).fillMaxHeight().clickable(enabled = onToggleNode != null) {
                            editorSelectionToggleIds(nodes.map { it.id }, checkedIds, selectAll = false).forEach { onToggleNode?.invoke(it) }
                        }.wrapContentSize(Alignment.Center))
                }
                Box(Modifier.fillMaxWidth().height(hairline()).background(AutoScriptPalette.Divider))
                LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                    itemsIndexed(nodes, key = { _, node -> node.id }) { _, node ->
                        Row(
                            Modifier.fillMaxWidth().height(35.dp)
                                .background(if (node.id in checkedIds) Color(0xFFE7EEFF) else Color.White)
                                .clickable { onSelectNode(node.id) }
                                .padding(start = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // Material Checkbox enforces a 48dp touch target and spills outside
                            // the reference editor's 35dp tree row. The row itself is clickable.
                            Box(Modifier.size(30.dp).clickable { onToggleNode?.invoke(node.id) ?: onSelectNode(node.id) }, contentAlignment = Alignment.Center) {
                                Box(
                                    Modifier.size(17.dp).border(1.dp, if (node.id in checkedIds) EditorBlue else Color(0xFF8A909B), RoundedCornerShape(2.dp))
                                        .background(if (node.id in checkedIds) EditorBlue else Color.White, RoundedCornerShape(2.dp)),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    if (node.id in checkedIds) Text("✓", color = Color.White, fontSize = 12.sp, lineHeight = 13.sp)
                                }
                            }
                            Text(
                                node.label,
                                color = Color.Black,
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f).padding(start = (1 + node.depth * 12).dp, end = 6.dp),
                            )
                        }
                    }
                }
                Box(Modifier.fillMaxWidth().height(hairline()).background(AutoScriptPalette.Divider))
                data class Command(val icon: Int, val title: String, val enabled: Boolean, val rotation: Float = 0f, val click: () -> Unit)
                val commands = listOf(
                    Command(R.drawable.visual_undo_24, "撤销", canUndo) { onCommand(EditorProgramCommand.UNDO) },
                    Command(R.drawable.visual_redo_24, "重做", canRedo) { onCommand(EditorProgramCommand.REDO) },
                    Command(R.drawable.editor_file_copy_24, "复制", hasSelection) { onCommand(EditorProgramCommand.COPY) },
                    Command(R.drawable.editor_file_paste_24, "粘贴", canPaste) { onCommand(EditorProgramCommand.PASTE) },
                    Command(R.drawable.ic_arrow_right_24, "上移", hasSelection, -90f) { onCommand(EditorProgramCommand.MOVE_UP) },
                    Command(R.drawable.ic_arrow_right_24, "下移", hasSelection, 90f) { onCommand(EditorProgramCommand.MOVE_DOWN) },
                    Command(R.drawable.ic_arrow_right_24, "减少缩进", hasSelection, 180f) { onCommand(EditorProgramCommand.OUTDENT) },
                    Command(R.drawable.ic_arrow_right_24, "增加缩进", hasSelection) { onCommand(EditorProgramCommand.INDENT) },
                    Command(R.drawable.ic_content_cut_24, "剪切", hasSelection) { onCommand(EditorProgramCommand.CUT) },
                    Command(R.drawable.editor_edit_24, "注释", singleSelection && onAnnotateSelected != null) { onAnnotateSelected?.invoke() },
                    Command(R.drawable.editor_edit_24, if (disabledSelection) "恢复执行" else "禁用", hasSelection) { onCommand(EditorProgramCommand.TOGGLE_DISABLED) },
                    Command(R.drawable.editor_delete_24, "删除", hasSelection, click = onDeleteSelected),
                )
                commands.chunked(4).forEach { row ->
                    Row(Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically) {
                        row.forEach { item -> EditorDockToolbarItem(item.icon, item.title, item.enabled, Modifier.weight(1f), item.rotation, item.click) }
                    }
                }
            }
    }
}

/** 选中节点的祖先链，如 `限次循环(3次)>循环体>点击`；按 depth 逆向回溯得到。 */
private fun legacyBreadcrumb(nodes: List<EditorTreeNode>, selectedIndex: Int): String {
    if (selectedIndex !in nodes.indices) return ""
    val chain = ArrayDeque<String>()
    var depth = nodes[selectedIndex].depth
    chain.addFirst(nodes[selectedIndex].label)
    for (index in selectedIndex - 1 downTo 0) {
        if (depth == 0) break
        if (nodes[index].depth < depth) {
            chain.addFirst(nodes[index].label)
            depth = nodes[index].depth
        }
    }
    return chain.joinToString(">")
}

/** tk_bjck 底栏项：图标 + 8sp `lan3` 文字，左右 15dp、上下 3dp 内边距。 */
@Composable
private fun EditorDockToolbarItem(
    @DrawableRes icon: Int,
    label: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    rotation: Float = 0f,
    onClick: () -> Unit,
) {
    val tint = if (enabled) EditorBlue else EditorDisabledBlue
    Column(
        modifier.height(44.dp).clickable(enabled = enabled, onClick = onClick).padding(horizontal = 2.dp, vertical = 3.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(painterResource(icon), contentDescription = label, tint = tint, modifier = Modifier.size(20.dp).rotate(rotation))
        Text(label, color = tint, fontSize = 10.sp, maxLines = 1, style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false)))
    }
}

private fun legacyVisibleTreeNodes(nodes: List<EditorTreeNode>): List<EditorTreeNode> {
    val hiddenDepths = mutableListOf<Int>()
    return buildList {
        nodes.forEach { node ->
            while (hiddenDepths.isNotEmpty() && node.depth <= hiddenDepths.last()) {
                hiddenDepths.removeAt(hiddenDepths.lastIndex)
            }
            if (hiddenDepths.isEmpty()) add(node)
            if (node.container && !node.expanded && hiddenDepths.isEmpty()) hiddenDepths += node.depth
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EditorProgramTreeRow(
    node: EditorTreeNode,
    hasChildren: Boolean,
    selected: Boolean,
    current: Boolean,
    onSelect: () -> Unit,
    onToggle: () -> Unit,
    onLongClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().height(35.dp)
            .background(if (current) Color(0xFFFFE6AA) else if (selected) Color(0xFFE7EEFF) else Color.White)
            .combinedClickable(onClick = onSelect, onLongClick = onLongClick, onLongClickLabel = "积木操作")
            .padding(start = (5 + node.depth * 12).dp, end = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.width(8.dp).fillMaxHeight().clickable(enabled = hasChildren, onClick = onToggle),
            contentAlignment = Alignment.Center,
        ) {
            if (hasChildren) {
                Icon(
                    painter = painterResource(R.drawable.editor_tree_expand_15),
                    contentDescription = if (node.expanded) "折叠节点" else "展开节点",
                    tint = EditorBlue,
                    modifier = Modifier.size(8.dp).rotate(if (node.expanded) 0f else -90f),
                )
            }
        }
        Box(Modifier.weight(1f).fillMaxHeight().padding(start = 1.dp, end = 4.dp), contentAlignment = Alignment.CenterStart) {
            Text(
                (if (current) "▶ 下一步 · " else "") + node.label,
                color = Color.Black,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false)),
            )
        }
    }
}

@Composable
private fun EditorProgramSearchDialog(
    nodes: List<EditorTreeNode>, onDismiss: () -> Unit, onSelect: (String) -> Unit,
    onReplace: ((String, String, Boolean) -> String?)?, programSource: String?,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var replacement by rememberSaveable { mutableStateOf("") }
    var mode by rememberSaveable { mutableStateOf("find") }
    var selectionOnly by rememberSaveable { mutableStateOf(false) }
    var confirmSource by remember { mutableStateOf<String?>(null) }
    var confirmReplace by remember { mutableStateOf(false) }
    var replaceError by remember { mutableStateOf<String?>(null) }
    val argumentText = remember(programSource) {
        programSource.orEmpty().lineSequence().filter(String::isNotBlank).mapNotNull { line ->
            runCatching {
                val json = com.google.gson.JsonParser.parseString(line).asJsonObject
                json.get("nodeId").asString to json.get("args").toString()
            }.getOrNull()
        }.toMap()
    }
    val allMatches = remember(nodes, query, argumentText) {
        if (query.isEmpty()) emptyList() else nodes.filter { node ->
            node.label.contains(query, ignoreCase = true) || node.code.contains(query, ignoreCase = true) ||
                argumentText[node.id]?.contains(query, ignoreCase = true) == true
        }
    }
    StudioPanelDialog("查找替换", onDismiss) {
        Column(Modifier.fillMaxWidth().heightIn(max = 430.dp).padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (onReplace != null) InspectorFilterRow(listOf("find" to "查找", "replace" to "替换"), mode) { mode = it }
            InspectorSearchField(query, { query = it.take(80); replaceError = null }, "输入积木名称、类型或参数")
            if (mode == "replace" && onReplace != null) {
                InspectorSearchField(replacement, { replacement = it.take(2048); replaceError = null }, "替换为（可留空删除文字）")
                InspectorFilterRow(listOf("all" to "当前插件", "selection" to "选中积木及子树"),
                    if (selectionOnly) "selection" else "all") { selectionOnly = it == "selection" }
                Text("替换区分大小写，仅修改 message / value 字符串；变量名、表达式、路径和标记不变。", fontSize = 10.sp, color = AutoScriptPalette.TextSecondary)
                TextButton(enabled = query.isNotEmpty() && query != replacement, onClick = {
                    confirmSource = programSource; confirmReplace = true
                }, modifier = Modifier.fillMaxWidth()) { Text("确认范围并替换") }
            }
            replaceError?.let { Text(it, fontSize = 11.sp, color = AutoScriptPalette.Danger) }
            Text(if (query.isEmpty()) "输入关键词后显示结果" else "匹配 ${allMatches.size} 项 · 显示前 ${allMatches.size.coerceAtMost(100)} 项 · 点击定位", fontSize = 10.sp, color = AutoScriptPalette.TextSecondary)
            LazyColumn(Modifier.fillMaxWidth().heightIn(min = 80.dp, max = 230.dp)) {
                itemsIndexed(allMatches.take(100), key = { _, node -> node.id }) { _, node ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 38.dp).clickable { onSelect(node.id) }.padding(6.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(node.label, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Text("定位 ›", color = EditorBlue, fontSize = 10.sp)
                    }
                    Box(Modifier.fillMaxWidth().height(hairline()).background(AutoScriptPalette.Divider))
                }
            }
            if (query.isNotEmpty() && allMatches.isEmpty()) Text("没有匹配的积木。", fontSize = 12.sp)
        }
    }
    if (confirmReplace) StudioConfirmDialog(onDismissRequest = { confirmReplace = false },
        title = { Text("确认文字替换") },
        text = { Text("在${if (selectionOnly) "选中积木及子树" else "当前插件"}内，将“$query”替换为“$replacement”？仅修改字符串正文，可撤销。") },
        dismissButton = { TextButton(onClick = { confirmReplace = false }) { Text("取消") } },
        confirmButton = { TextButton(onClick = {
            confirmReplace = false
            replaceError = if (confirmSource != programSource) "程序已变化，请重新确认替换"
                else onReplace?.invoke(query, replacement, selectionOnly)
        }) { Text("替换") } })
}

@Composable
internal fun EditorInsertPositionDialog(
    hasSelection: Boolean,
    canInsertInside: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (EditorInsertPosition) -> Unit,
    allowReplace: Boolean = true,
    title: String = "加入位置",
    initialPosition: EditorInsertPosition = EditorInsertPosition.LIST_BOTTOM,
) {
    var selected by rememberSaveable { mutableStateOf(initialPosition) }
    val options = listOf(
        EditorInsertPosition.LIST_BOTTOM to "列表最底部",
        EditorInsertPosition.ABOVE to "选择行上方",
        EditorInsertPosition.BELOW to "选择行下方",
        EditorInsertPosition.INSIDE to "选择行里面",
        EditorInsertPosition.REPLACE to "修改选择行",
    ).filter { allowReplace || it.first != EditorInsertPosition.REPLACE }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.widthIn(max = 320.dp).fillMaxWidth(.90f),
            color = Color.White,
            shape = RoundedCornerShape(2.dp),
            shadowElevation = 9.dp,
        ) {
            Column {
                Row(
                    Modifier.fillMaxWidth().height(42.dp).padding(start = 14.dp, end = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(title, color = EditorBlue, fontSize = 16.sp, modifier = Modifier.weight(1f))
                    Icon(
                        painterResource(R.drawable.editor_close_24),
                        contentDescription = "关闭",
                        tint = EditorBlue,
                        modifier = Modifier.size(34.dp).clickable(onClick = onDismiss).padding(6.dp),
                    )
                }
                Box(Modifier.fillMaxWidth().height(hairline()).background(AutoScriptPalette.Divider))
                options.forEach { (position, label) ->
                    val enabled = when (position) {
                        EditorInsertPosition.LIST_BOTTOM -> true
                        EditorInsertPosition.INSIDE -> hasSelection && canInsertInside
                        else -> hasSelection
                    }
                    Row(
                        Modifier.fillMaxWidth().height(43.dp).clickable(enabled = enabled) { selected = position }
                            .padding(horizontal = 15.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(label, color = if (enabled) Color(0xFF20242C) else Color(0xFFAAB1BD), fontSize = 14.sp, modifier = Modifier.weight(1f))
                        Text(
                            if (selected == position) "◉" else "○",
                            color = if (enabled) EditorBlue else Color(0xFFAAB1BD),
                            fontSize = 28.sp,
                        )
                    }
                }
                Box(Modifier.fillMaxWidth().height(hairline()).background(AutoScriptPalette.Divider))
                Row(Modifier.fillMaxWidth().height(42.dp)) {
                    Box(Modifier.weight(1f).fillMaxHeight().clickable(onClick = onDismiss), contentAlignment = Alignment.Center) {
                        Text("取消", color = Color(0xFF20242C), fontSize = 14.sp,
                            style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false)))
                    }
                    Box(Modifier.width(hairline()).fillMaxHeight().background(AutoScriptPalette.Divider))
                    Box(Modifier.weight(1f).fillMaxHeight().clickable { onConfirm(selected) }, contentAlignment = Alignment.Center) {
                        Text("确定", color = EditorBlue, fontSize = 14.sp,
                            style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false)))
                    }
                }
            }
        }
    }
}

@Composable
private fun EditorDialogTextButton(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Text(
        label,
        color = Color(0xFF3F51B5),
        fontSize = 14.sp,
        modifier = modifier.clickable(onClick = onClick).padding(horizontal = 15.dp, vertical = 8.dp),
    )
}

@Composable
private fun EditorDialogTextButton(label: String, onClick: () -> Unit) = EditorDialogTextButton(label, Modifier, onClick)

@Composable
private fun EditorDockAction(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Text(
        label,
        color = if (enabled) Color(0xFF202839) else Color(0xFF9DA6B4),
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .height(27.dp)
            .padding(vertical = 1.dp)
            .border(1.dp, EditorBorder, RoundedCornerShape(3.dp))
            .background(Color.White, RoundedCornerShape(3.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .wrapContentSize(Alignment.Center),
    )
}

@Composable
private fun EditorDockHeaderButton(
    description: String,
    @DrawableRes drawable: Int,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .width(32.dp)
            .fillMaxHeight()
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(drawable),
            contentDescription = description,
            tint = EditorBlue,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun RowScope.EditorBallControl(label: String, enabled: Boolean, color: Color, onClick: () -> Unit) {
    Box(Modifier.weight(1f).fillMaxHeight().clickable(enabled = enabled, onClick = onClick), contentAlignment = Alignment.Center) {
        Text(label, color = if (enabled) color else EditorDisabledBlue, fontSize = 12.sp,
            style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false)))
    }
}

@Composable
private fun EditorBottomButton(action: EditorToolbarAction, enabled: Boolean, showLabel: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val tint = when (action) {
        EditorToolbarAction.RUN, EditorToolbarAction.RESUME -> if (enabled) Color(0xFF38B36D) else EditorDisabledBlue
        EditorToolbarAction.STOP, EditorToolbarAction.DELETE -> if (enabled) Color(0xFFE55353) else EditorDisabledBlue
        else -> if (enabled) EditorBlue else EditorDisabledBlue
    }
    val drawable = when (action) {
        EditorToolbarAction.RUN, EditorToolbarAction.RESUME -> R.drawable.editor_run_24
        EditorToolbarAction.PAUSE -> R.drawable.editor_pause_24
        EditorToolbarAction.STOP -> R.drawable.editor_stop_24
        EditorToolbarAction.STEP -> R.drawable.editor_step_run_24
        EditorToolbarAction.EDIT -> R.drawable.editor_edit_24
        EditorToolbarAction.LOG -> R.drawable.editor_log_24
        EditorToolbarAction.UNDO -> R.drawable.visual_undo_24
        EditorToolbarAction.REDO -> R.drawable.visual_redo_24
        EditorToolbarAction.MORE -> R.drawable.editor_more_24
        EditorToolbarAction.COPY -> R.drawable.editor_file_copy_24
        EditorToolbarAction.PASTE -> R.drawable.editor_file_paste_24
        EditorToolbarAction.DELETE -> R.drawable.editor_delete_24
        EditorToolbarAction.MOVE_UP, EditorToolbarAction.MOVE_DOWN -> R.drawable.ic_arrow_right_24
        EditorToolbarAction.ORGANIZE -> R.drawable.editor_more_24
    }
    Column(modifier.fillMaxHeight().clickable(enabled = enabled, onClick = onClick),
        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(
            painter = painterResource(drawable),
            contentDescription = action.label,
            tint = tint,
            modifier = Modifier.size(20.dp).rotate(when (action) {
                EditorToolbarAction.MOVE_UP -> -90f
                EditorToolbarAction.MOVE_DOWN -> 90f
                else -> 0f
            }),
        )
        if (showLabel) Text(action.label, color = tint, fontSize = 9.sp, maxLines = 1,
            style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false)))
    }
}

@Composable
private fun ResizeHandle(modifier: Modifier, onResize: (Offset) -> Unit) {
    val currentOnResize by rememberUpdatedState(onResize)
    Icon(
        painter = painterResource(R.drawable.editor_resize_24),
        contentDescription = "调整窗口大小",
        tint = EditorBlue.copy(alpha = .6f),
        modifier = modifier.pointerInput(Unit) {
            detectDragGestures { change, delta ->
                change.consume()
                currentOnResize(delta)
            }
        },
    )
}

/** 右栏九个入口和“更多”菜单的弹窗路由；录制仍是应用内预览，直到 Runtime 提供事件流。 */
@Composable
private fun EditorToolPanelDialog(
    dialog: EditorToolPanel,
    projectName: String,
    files: List<StudioProjectFile>,
    onDismiss: () -> Unit,
    onOpenFile: (StudioProjectFile) -> Unit,
    onDeleteFiles: (List<StudioProjectFile>) -> Unit,
    onOpenImageTools: ((Long) -> Unit)?,
    onOpenImageToolMode: ((ImageToolMode, Long) -> Unit)?,
    onCreateImageBlock: ((String) -> Unit)?,
    onOpenImageLibrary: (() -> Unit)?,
    onTestRecognition: (() -> Unit)?,
    availableVariables: List<String>,
    availableLabels: List<String>,
    projectVariables: List<ProjectVariable>,
    currentFlowId: String,
    projectFlows: List<ProjectFlow>,
    onManageVariables: (() -> Unit)?,
    visualMode: Boolean,
    debugSettings: ProjectDebugSettings,
    onSaveDebugSettings: (ProjectDebugSettings) -> Unit,
    onInsert: (String) -> Unit,
) {
    if (dialog != EditorToolPanel.RECORDING) {
        EditorEntryDialog(
            entry = dialog,
            projectName = projectName,
            files = files,
            onDismiss = onDismiss,
            onInsert = onInsert,
            onOpenFile = onOpenFile,
            onDeleteFiles = onDeleteFiles,
            onOpenImageTools = onOpenImageTools,
            onOpenImageToolMode = onOpenImageToolMode,
            onCreateImageBlock = onCreateImageBlock,
            onOpenImageLibrary = onOpenImageLibrary,
            onTestRecognition = onTestRecognition,
            availableVariables = availableVariables,
            availableLabels = availableLabels,
            projectVariables = projectVariables,
            visualMode = visualMode,
            currentFlowId = currentFlowId,
            projectFlows = projectFlows,
            onManageVariables = onManageVariables,
            debugSettings = debugSettings,
            onSaveDebugSettings = onSaveDebugSettings,
        )
        return
    }
    Dialog(onDismissRequest = onDismiss) {
        Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(5.dp), color = Color(0xFFF6F6F6)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("录制动作 · 需Root支持", color = EditorDockGreenDark, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                Text("请在可视化插件编辑器中使用；需要Root后端就绪、受支持的输入设备与悬浮窗权限。", fontSize = 13.sp, color = Color(0xFF596270))
                Text("关闭", modifier = Modifier.fillMaxWidth().border(1.dp, FunctionLibraryBlue, RoundedCornerShape(3.dp)).clickable(onClick = onDismiss).padding(10.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center, color = FunctionLibraryBlue)
            }
        }
    }
}

/**
 * 旧版 P06「插件管理」：白底、60dp 标题 18sp `#33AAFF`、右上问号、2dp `#6699FF` 分割、
 * 八个单选行、底部 取消/确定。“插件”在本项目里就是源文件（Flow）。
 */
@Composable
private fun PluginManagerDialog(
    enabled: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (PluginManagerAction) -> Unit,
) {
    var selected by rememberSaveable { mutableStateOf(PluginManagerAction.CREATE) }
    var showHelp by rememberSaveable { mutableStateOf(false) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.fillMaxWidth(.91f).widthIn(max = 480.dp),
            color = Color.White,
            shape = RoundedCornerShape(2.dp),
            shadowElevation = 10.dp,
        ) {
            Column {
                Box(Modifier.fillMaxWidth().height(60.dp)) {
                    Text(
                        "插件管理",
                        color = FunctionLibraryBlue,
                        fontSize = 18.sp,
                        modifier = Modifier.align(Alignment.CenterStart).padding(start = 10.dp),
                    )
                    Icon(
                        painterResource(R.drawable.ic_help_outline_24),
                        contentDescription = "说明",
                        tint = FunctionLibraryBlue,
                        modifier = Modifier.align(Alignment.TopEnd).size(30.dp).clickable { showHelp = !showHelp }.padding(3.dp),
                    )
                }
                Box(Modifier.fillMaxWidth().height(2.dp).background(Color(0xFF6699FF)))
                if (showHelp) {
                    Text(
                        if (enabled) {
                            "插件是独立的可视化流程文件，可以被其他插件调用。创建、删除、另存、分组在插件选择列表中维护；检错用 Rust 编译器校验项目；" +
                                "未调用插件列出没有被任何调用节点引用的源文件；存储为模版预留。"
                        } else {
                            "Lua 项目管理源码文件，不使用可视化插件。"
                        },
                        color = Color(0xFF596270),
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
                PluginManagerAction.entries.forEach { action ->
                    val available = enabled && action != PluginManagerAction.TEMPLATE
                    Row(
                        Modifier.fillMaxWidth().height(45.dp)
                            .clickable(enabled = available) { selected = action }
                            .padding(start = 10.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            if (action == PluginManagerAction.TEMPLATE) "${action.label}（预留）" else action.label,
                            color = if (available) Color.Black else Color(0xFF9DA6B4),
                            fontSize = 18.sp,
                            modifier = Modifier.weight(1f),
                        )
                        RadioButton(
                            selected = selected == action,
                            onClick = { selected = action },
                            enabled = available,
                            colors = RadioButtonDefaults.colors(selectedColor = FunctionLibraryBlue),
                        )
                    }
                    Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFFE3E3E3)))
                }
                Row(Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 10.dp, vertical = 4.dp)) {
                    PluginManagerButton("取消", Modifier.weight(1f), onClick = onDismiss)
                    Spacer(Modifier.width(4.dp))
                    PluginManagerButton("确定", Modifier.weight(1f), enabled = enabled && selected != PluginManagerAction.TEMPLATE) { onConfirm(selected) }
                }
            }
        }
    }
}

/** `guagua_but_style5`：白底灰边 16sp 黑字。 */
@Composable
private fun PluginManagerButton(label: String, modifier: Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Text(
        label,
        color = if (enabled) Color.Black else Color(0xFF9DA6B4),
        fontSize = 16.sp,
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        modifier = modifier.fillMaxHeight()
            .border(1.dp, EditorBorder, RoundedCornerShape(2.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .wrapContentSize(Alignment.Center),
    )
}

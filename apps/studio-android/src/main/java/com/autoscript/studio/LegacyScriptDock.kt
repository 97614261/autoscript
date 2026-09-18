package com.autoscript.studio

import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
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
import org.json.JSONArray
import org.json.JSONObject

/**
 * In-app reconstruction of the current editor overlay (`service_tk.xml` and
 * `service_tk_ball.xml`). It stays inside Studio until the system-overlay
 * runtime is enabled in a later milestone.
 */
@Composable
internal fun LegacyScriptDock(
    projectName: String,
    onRun: () -> Unit,
    onInsert: (String) -> Unit,
    onInsertPositioned: ((String, LegacyInsertPosition) -> Unit)? = null,
    sourceName: String? = null,
    programNodes: List<LegacyDockProgramNode>? = null,
    programSelectedNodeId: String? = null,
    onProgramNodeSelected: (String) -> Unit = {},
    onProgramNodeDeleted: () -> Unit = {},
    onProgramNodeEdited: () -> Unit = {},
    onProgramCommand: (LegacyDockProgramCommand) -> Unit = {},
    onStep: () -> Unit = {},
    consoleLines: List<String> = emptyList(),
    onClose: () -> Unit = {},
    editingEnabled: Boolean = true,
    /** 可视化项目的源文件树；Lua 项目为 null，标题点击不再弹源文件管理。 */
    sourceTree: SourceFileTree? = null,
    currentFlowId: String? = null,
    sourceBusy: Boolean = false,
    sourceMessage: String? = null,
    onSourceAction: (SourceManagerAction) -> Unit = {},
    /** “文件”弹窗展示的项目沙箱文件（来自 [projectFileCatalog]）。 */
    projectFiles: List<StudioProjectFile> = emptyList(),
    onOpenProjectFile: (StudioProjectFile) -> Unit = {},
    onDeleteProjectFiles: (List<StudioProjectFile>) -> Unit = {},
    /** 左上菜单“插件”→ 旧版插件管理；只有检错/未调用等需要宿主执行的动作会回调。 */
    onPluginAction: (LegacyPluginAction) -> Unit = {},
    capabilities: Set<String> = emptySet(),
    /** 脚本运行中小球切到 `float_ball_stop_content`（25dp 停止键，`service_tk_ball.xml`）。 */
    running: Boolean = false,
    onStop: () -> Unit = {},
    /** “更多 → 录制动作”与“工具 → 图像工具”的全屏页；为 null 时退回弹窗内预览。 */
    onOpenRecorder: (() -> Unit)? = null,
    onOpenImageTools: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    var expanded by rememberSaveable(projectName) { mutableStateOf(false) }
    var functionLibrary by rememberSaveable(projectName) { mutableStateOf(false) }
    var toolDialog by rememberSaveable(projectName) { mutableStateOf<LegacyToolDialog?>(null) }
    var sourceManagerVisible by rememberSaveable(projectName) { mutableStateOf(false) }
    var pluginManagerVisible by rememberSaveable(projectName) { mutableStateOf(false) }
    var consoleVisible by rememberSaveable(projectName) { mutableStateOf(false) }
    var treeNodes by rememberSaveable(projectName, saver = LegacyTreeNodesStateSaver) {
        mutableStateOf<List<LegacyTreeNode>>(emptyList())
    }
    var selectedNodeId by rememberSaveable(projectName) { mutableStateOf<String?>(null) }
    var editingNodeId by rememberSaveable(projectName) { mutableStateOf<String?>(null) }
    var nextNodeId by rememberSaveable(projectName) { mutableStateOf(1L) }
    var collapsedProgramNodeIds by rememberSaveable(projectName, sourceName) {
        mutableStateOf<List<String>>(emptyList())
    }
    var searchingProgram by rememberSaveable(projectName, sourceName) { mutableStateOf(false) }
    var editorMenuVisible by rememberSaveable(projectName, sourceName) { mutableStateOf(false) }
    var nodeEditorVisible by rememberSaveable(projectName, sourceName) { mutableStateOf(false) }
    var moreMenuVisible by rememberSaveable(projectName, sourceName) { mutableStateOf(false) }
    var pendingInsertSnippet by rememberSaveable(projectName, sourceName) { mutableStateOf<String?>(null) }
    var closed by remember(projectName) { mutableStateOf(false) }
    val externallyManaged = programNodes != null
    val displayedTreeNodes = remember(programNodes, collapsedProgramNodeIds, treeNodes) {
        val authoritativeNodes = programNodes
        authoritativeNodes?.mapIndexed { index, node ->
            LegacyTreeNode(
                id = node.nodeId,
                label = node.label,
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

    LaunchedEffect(programNodes) {
        if (programNodes != null) {
            val activeIds = programNodes.asSequence().map(LegacyDockProgramNode::nodeId).toHashSet()
            collapsedProgramNodeIds = collapsedProgramNodeIds.filter(activeIds::contains)
        }
    }

    LaunchedEffect(treeNodes) {
        val knownIds = treeNodes.asSequence().map(LegacyTreeNode::id).toHashSet()
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

    BackHandler(enabled = moreMenuVisible || editorMenuVisible || expanded) {
        when {
            moreMenuVisible -> moreMenuVisible = false
            editorMenuVisible -> editorMenuVisible = false
            else -> expanded = false
        }
    }

    if (closed) return

    BoxWithConstraints(modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val ballSizePx = with(density) { 35.dp.toPx() }
        val minPanelWidthPx = with(density) { 210.dp.toPx() }
        // 最小高度原来和默认值一样，缩放柄往下拖不动，所以放开到 260dp。
        // 注意这低于右侧九个入口需要的 247dp + 头 35dp + 底 40dp，缩到最小时最后一两个入口会被裁掉；
        // 参考那列是 match_parent 的非滚动 LinearLayout(service_tk.xml:24)，缩小时同样会裁，故不额外加滚动。
        val minPanelHeightPx = with(density) { 260.dp.toPx() }
        val maxWidthPx = constraints.maxWidth.toFloat()
        val maxHeightPx = constraints.maxHeight.toFloat()
        val ballPeekPx = with(density) { 10.dp.toPx() }
        var ballX by rememberSaveable(projectName) { mutableFloatStateOf(Float.NaN) }
        var ballY by rememberSaveable(projectName) { mutableFloatStateOf(Float.NaN) }
        var panelX by rememberSaveable(projectName) { mutableFloatStateOf(with(density) { 4.dp.toPx() }) }
        var panelY by rememberSaveable(projectName) { mutableFloatStateOf(Float.NaN) }
        var panelWidthPx by rememberSaveable(projectName) { mutableFloatStateOf(with(density) { 232.dp.toPx() }) }
        // 默认 324dp，由右侧九个入口撑出来，不能再按截图比例估。
        // 参考 service_tk.xml:5 的根容器 line_tk_lashen_width 是 layout_height="wrap_content"，
        // 即面板高度由内容决定；其中最高的一列就是 line_tk_right_bar(:24)：
        //   padding 3dp×2 + 9×25dp + 边距(前八个各 1+1dp，第九个 1+3dp) = 251dp，
        //   中段 :12 有 marginTop/Bottom = -5dp 抵掉 10dp → 实际占 241dp，
        //   加头 35dp(:6) 与底部 5+5+25+5=40dp(:36-38) ≈ 316dp。
        // 我们这列没有那对负边距，需要 9×27 + padding 2dp×2 = 247dp，
        // 加头 35dp + 分隔线 1dp + 底栏 40dp = 323dp，故取 324dp。
        // 曾按旧版截图「占屏高 37%」改成 300dp，结果第九个入口 AI 被挤出面板点不到——
        // 旧版截图只有八个入口，不能用来定新版的高度。
        var panelHeightPx by rememberSaveable(projectName) { mutableFloatStateOf(with(density) { 324.dp.toPx() }) }
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
            // service_tk_ball：35dp 外框固定，运行中内容换成 25dp 的停止键，居中于同一外框。
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
                if (running) {
                    Surface(
                        modifier = Modifier.size(25.dp).clickable(onClick = onStop),
                        color = Color(0xE6111827),
                        shape = RoundedCornerShape(13.dp),
                        border = androidx.compose.foundation.BorderStroke(2.dp, Color.White.copy(alpha = .65f)),
                        shadowElevation = 5.dp,
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.editor_popup_power_20),
                            contentDescription = "停止正在运行的程序",
                            tint = Color.White,
                            modifier = Modifier.fillMaxSize().padding(4.dp),
                        )
                    }
                } else {
                    Surface(
                        modifier = Modifier.fillMaxSize().clickable { editorMenuVisible = false; expanded = true },
                        color = Color(0xE6111827),
                        shape = RoundedCornerShape(18.dp),
                        border = androidx.compose.foundation.BorderStroke(2.dp, Color.White.copy(alpha = .65f)),
                        shadowElevation = 5.dp,
                    ) {
                        EditorBallIcon(Modifier.fillMaxSize())
                    }
                }
            }
        } else {
            LegacyDockPanel(
                projectName = projectName,
                title = displayedSourceName ?: "点击创建源文件",
                hasSource = externallyManaged || displayedSourceName != null,
                nodes = displayedTreeNodes,
                selectedNodeId = displayedSelectedNodeId,
                consoleVisible = consoleVisible,
                consoleLines = consoleLines,
                editingEnabled = editingEnabled,
                onMenu = { editorMenuVisible = !editorMenuVisible },
                onCreateSource = { if (editingEnabled && sourceTree != null) sourceManagerVisible = true },
                onRun = onRun,
                onStep = onStep,
                onFunctions = { if (editingEnabled) functionLibrary = true },
                onTool = { entry -> if (editingEnabled) toolDialog = entry },
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
                onDeleteNode = {
                    if (externallyManaged) {
                        onProgramNodeDeleted()
                    } else selectedNodeId?.let { selectedId ->
                        val selectedIndex = treeNodes.indexOfFirst { it.id == selectedId }
                        treeNodes = removeLegacyTreeSubtree(treeNodes, selectedId)
                        selectedNodeId = when {
                            treeNodes.isEmpty() -> null
                            selectedIndex > 0 -> treeNodes[(selectedIndex - 1).coerceAtMost(treeNodes.lastIndex)].id
                            else -> treeNodes.first().id
                        }
                    }
                },
                onEditNode = {
                    if (externallyManaged) {
                        onProgramNodeEdited()
                    } else treeNodes.firstOrNull { it.id == selectedNodeId }?.let { selected ->
                        editingNodeId = selected.id
                        if (selected.entry == LegacyToolDialog.FUNCTIONS) {
                            functionLibrary = true
                        } else {
                            toolDialog = selected.entry
                        }
                    }
                },
                onOpenNodeEditor = { nodeEditorVisible = true },
                onOpenMoreMenu = { moreMenuVisible = true },
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
            if (editorMenuVisible) {
                Box(Modifier.fillMaxSize().clickable { editorMenuVisible = false })
                LegacyEditorPopupMenu(
                    modifier = Modifier.offset {
                        val menuHeight = with(density) { 176.dp.toPx() }
                        val menuY = (resolvedPanelY + with(density) { 35.dp.toPx() })
                            .coerceAtMost((maxHeightPx - menuHeight).coerceAtLeast(0f))
                        val menuX = effectivePanelX.coerceIn(0f, (maxWidthPx - with(density) { 96.dp.toPx() }).coerceAtLeast(0f))
                        IntOffset(menuX.roundToInt(), menuY.roundToInt())
                    },
                    onMinimize = {
                        editorMenuVisible = false
                        expanded = false
                    },
                    onPlugins = {
                        editorMenuVisible = false
                        pluginManagerVisible = true
                    },
                    onSettings = {
                        editorMenuVisible = false
                        toolDialog = LegacyToolDialog.TOOLS
                    },
                    onClose = {
                        editorMenuVisible = false
                        expanded = false
                        closed = true
                        onClose()
                    },
                )
            }
            if (moreMenuVisible) {
                Box(Modifier.fillMaxSize().clickable { moreMenuVisible = false })
                val moreWidthPx = with(density) { 148.dp.toPx() }
                val moreHeightPx = with(density) { 220.dp.toPx() }
                val moreX = (effectivePanelX + with(density) { 145.dp.toPx() })
                    .coerceIn(0f, (maxWidthPx - moreWidthPx).coerceAtLeast(0f))
                val moreY = (resolvedPanelY + effectivePanelHeightPx - with(density) { 40.dp.toPx() } - moreHeightPx)
                    .coerceIn(0f, (maxHeightPx - moreHeightPx).coerceAtLeast(0f))
                LegacyMorePopup(
                    modifier = Modifier.offset { IntOffset(moreX.roundToInt(), moreY.roundToInt()) },
                    onShowMain = { moreMenuVisible = false; expanded = false },
                    onRecord = {
                        moreMenuVisible = false
                        if (onOpenRecorder != null) onOpenRecorder() else toolDialog = LegacyToolDialog.RECORDING
                    },
                    onVariableCheck = { moreMenuVisible = false; toolDialog = LegacyToolDialog.VARIABLE_CHECK },
                    onSearchReplace = { moreMenuVisible = false; searchingProgram = true },
                    onRuntimeVariables = { moreMenuVisible = false; toolDialog = LegacyToolDialog.RUNTIME_VARIABLES },
                )
            }
        }
    }

    if (nodeEditorVisible) {
        LegacyNodeEditDialog(
            nodes = displayedTreeNodes,
            selectedNodeId = displayedSelectedNodeId,
            onSelectNode = { nodeId ->
                if (externallyManaged) onProgramNodeSelected(nodeId) else selectedNodeId = nodeId
            },
            onEditSelected = {
                nodeEditorVisible = false
                if (externallyManaged) {
                    onProgramNodeEdited()
                } else {
                    treeNodes.firstOrNull { it.id == selectedNodeId }?.let { selected ->
                        editingNodeId = selected.id
                        if (selected.entry == LegacyToolDialog.FUNCTIONS) functionLibrary = true else toolDialog = selected.entry
                    }
                }
            },
            onDeleteSelected = {
                if (externallyManaged) onProgramNodeDeleted() else selectedNodeId?.let { id ->
                    treeNodes = removeLegacyTreeSubtree(treeNodes, id)
                    selectedNodeId = treeNodes.firstOrNull()?.id
                }
            },
            onCommand = onProgramCommand,
            onDismiss = { nodeEditorVisible = false },
        )
    }

    if (functionLibrary) {
        val functionGroups = remember(externallyManaged) {
            if (externallyManaged) LegacyFunctionCatalog.visualGroups() else LegacyFunctionCatalog.luaGroups()
        }
        LegacyFunctionLibrary(
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
                        val replacement = legacyTreeNode(old.id, LegacyToolDialog.FUNCTIONS, snippet)
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
    if (sourceManagerVisible && sourceTree != null) {
        LegacySourceManagerDialog(
            tree = sourceTree,
            currentFlowId = currentFlowId,
            busy = sourceBusy,
            message = sourceMessage,
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
        LegacyPluginManagerDialog(
            enabled = externallyManaged && sourceTree != null,
            onDismiss = { pluginManagerVisible = false },
            onConfirm = { action ->
                pluginManagerVisible = false
                when (action) {
                    LegacyPluginAction.CREATE,
                    LegacyPluginAction.DELETE,
                    LegacyPluginAction.SAVE_AS,
                    LegacyPluginAction.GROUP,
                    -> sourceManagerVisible = true
                    LegacyPluginAction.CHECK,
                    LegacyPluginAction.CHECK_ALL,
                    LegacyPluginAction.TEMPLATE,
                    LegacyPluginAction.UNUSED,
                    -> onPluginAction(action)
                }
            },
        )
    }
    toolDialog?.let { dialog ->
        LegacyToolDialogScreen(
            dialog = dialog,
            projectName = projectName,
            files = projectFiles,
            onDismiss = { toolDialog = null; editingNodeId = null },
            onOpenFile = { file -> toolDialog = null; onOpenProjectFile(file) },
            onDeleteFiles = onDeleteProjectFiles,
            onOpenImageTools = onOpenImageTools?.let { open -> { toolDialog = null; open() } },
        ) { snippet ->
            val editingId = editingNodeId
            val command = legacyDockProgramCommand(snippet)
            if (command != null) {
                when (command) {
                    LegacyDockProgramCommand.EXPAND_ALL -> if (externallyManaged) {
                        collapsedProgramNodeIds = emptyList()
                    } else {
                        treeNodes = treeNodes.map { it.copy(expanded = true) }
                    }
                    LegacyDockProgramCommand.COLLAPSE_ALL -> if (externallyManaged) {
                        collapsedProgramNodeIds = displayedTreeNodes.filter(LegacyTreeNode::container).map(LegacyTreeNode::id)
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
                    LegacyDockProgramCommand.SEARCH -> searchingProgram = true
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
        LegacyInsertPositionDialog(
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
                        LegacyInsertPosition.LIST_BOTTOM -> treeNodes = treeNodes + node.copy(depth = 0)
                        LegacyInsertPosition.ABOVE -> {
                            val index = selectedIndex.takeIf { it >= 0 } ?: treeNodes.size
                            treeNodes = treeNodes.toMutableList().also { it.add(index, node.copy(depth = selected?.depth ?: 0)) }
                        }
                        LegacyInsertPosition.BELOW -> {
                            var index = if (selectedIndex >= 0) selectedIndex + 1 else treeNodes.size
                            while (selected != null && index < treeNodes.size && treeNodes[index].depth > selected.depth) index++
                            treeNodes = treeNodes.toMutableList().also { it.add(index, node.copy(depth = selected?.depth ?: 0)) }
                        }
                        LegacyInsertPosition.INSIDE -> {
                            if (selectedIndex >= 0 && selected?.container == true) {
                                treeNodes = treeNodes.toMutableList().also {
                                    it[selectedIndex] = selected.copy(expanded = true)
                                    it.add(selectedIndex + 1, node.copy(depth = selected.depth + 1))
                                }
                            } else {
                                treeNodes = treeNodes + node.copy(depth = 0)
                            }
                        }
                        LegacyInsertPosition.REPLACE -> {
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
        LegacyProgramSearchDialog(
            nodes = displayedTreeNodes,
            onDismiss = { searchingProgram = false },
            onSelect = { nodeId ->
                collapsedProgramNodeIds = emptyList()
                if (externallyManaged) onProgramNodeSelected(nodeId) else selectedNodeId = nodeId
                searchingProgram = false
            },
        )
    }
}

internal enum class LegacyDockProgramCommand {
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
}

private const val LEGACY_DOCK_COMMAND_PREFIX = "--@autoscript-editor:"

private fun legacyDockProgramCommand(snippet: String): LegacyDockProgramCommand? = snippet
    .lineSequence().firstOrNull()?.removePrefix(LEGACY_DOCK_COMMAND_PREFIX)?.trim()
    ?.takeIf(String::isNotEmpty)?.let { value ->
        runCatching { LegacyDockProgramCommand.valueOf(value) }.getOrNull()
    }

private data class LegacyTreeNode(
    val id: String,
    val label: String,
    val code: String,
    val entry: LegacyToolDialog,
    val depth: Int = 0,
    val container: Boolean = false,
    val expanded: Boolean = true,
)

private const val LEGACY_TREE_SAVED_STATE_LIMIT = 256 * 1024
private const val LEGACY_TREE_SAVED_NODE_LIMIT = 4_096
private const val LEGACY_TREE_SAVED_STATE_VERSION = 1

private val LegacyTreeNodesStateSaver = Saver<
    androidx.compose.runtime.MutableState<List<LegacyTreeNode>>,
    String,
>(
    save = { state ->
        state.value.takeIf { it.size <= LEGACY_TREE_SAVED_NODE_LIMIT }
            ?.let(::encodeLegacyTreeNodes)
            ?.takeIf { it.length <= LEGACY_TREE_SAVED_STATE_LIMIT }
    },
    restore = { encoded -> mutableStateOf(decodeLegacyTreeNodes(encoded)) },
)

private fun encodeLegacyTreeNodes(nodes: List<LegacyTreeNode>): String {
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

private fun decodeLegacyTreeNodes(encoded: String): List<LegacyTreeNode> = runCatching {
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
                LegacyTreeNode(
                    id = id,
                    label = item.getString("label").take(160),
                    code = item.getString("code"),
                    entry = LegacyToolDialog.valueOf(item.getString("entry")),
                    depth = normalizedDepth,
                    container = item.optBoolean("container", false),
                    expanded = item.optBoolean("expanded", true),
                ),
            )
            previousDepth = normalizedDepth
        }
    }
}.getOrElse { emptyList() }

private fun legacyTreeNode(id: String, entry: LegacyToolDialog, code: String): LegacyTreeNode {
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
            LegacyToolDialog.FILES -> "文件操作"
            LegacyToolDialog.TOOLS -> "工具操作"
            LegacyToolDialog.IMAGE -> "图像识别"
            LegacyToolDialog.JUDGMENT -> "判断"
            LegacyToolDialog.LOOP -> "循环"
            LegacyToolDialog.COMMON -> "常用操作"
            LegacyToolDialog.FUNCTIONS -> "函数"
            LegacyToolDialog.DEBUG -> "调试"
            LegacyToolDialog.AI -> "AI 节点"
            else -> "程序节点"
        }
    }
    val isContainer = entry == LegacyToolDialog.JUDGMENT || entry == LegacyToolDialog.LOOP ||
        line.startsWith("if ") || line.startsWith("while ") || line.startsWith("for ") || line.startsWith("repeat")
    return LegacyTreeNode(id, label, code, entry, container = isContainer)
}

private fun legacyArguments(line: String): String =
    line.substringAfter('(', "").substringBeforeLast(')', "").trim().take(34)

private fun insertLegacyTreeNode(nodes: List<LegacyTreeNode>, selectedId: String?, node: LegacyTreeNode): List<LegacyTreeNode> {
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

private fun removeLegacyTreeSubtree(nodes: List<LegacyTreeNode>, nodeId: String): List<LegacyTreeNode> {
    val start = nodes.indexOfFirst { it.id == nodeId }
    if (start < 0) return nodes
    val depth = nodes[start].depth
    var end = start + 1
    while (end < nodes.size && nodes[end].depth > depth) end++
    return nodes.filterIndexed { index, _ -> index !in start until end }
}

private fun legacyHasChildren(nodes: List<LegacyTreeNode>, index: Int): Boolean =
    index in nodes.indices && index + 1 < nodes.size && nodes[index + 1].depth > nodes[index].depth

private fun legacyIsDescendant(nodes: List<LegacyTreeNode>, parentIndex: Int, nodeId: String?): Boolean {
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

private val LegacyDockGreen = Color(0xFF3D8C3D)
private val LegacyDockGreenDark = Color(0xFF175E20)
private val LegacyFunctionBlue = Color(0xFF339DFF)
private val EditorBlue = Color(0xFF3A6EFF)
private val EditorDisabledBlue = Color(0xFFAFC5FF)
private val EditorBorder = Color(0xFFC7CBD1)
internal enum class LegacyToolDialog { FILES, TOOLS, IMAGE, JUDGMENT, LOOP, COMMON, FUNCTIONS, RECORDING, DEBUG, AI, DATA_BACKFILL, VARIABLE_CHECK, RUNTIME_VARIABLES }

/** 旧版 P06「插件管理」的八个单选项；在本项目里“插件”= Flow（源文件）。 */
internal enum class LegacyPluginAction(val label: String) {
    CREATE("插件创建"),
    DELETE("插件删除"),
    SAVE_AS("插件另存"),
    CHECK("插件检错"),
    CHECK_ALL("全部插件检错"),
    GROUP("插件分组"),
    TEMPLATE("存储为模版"),
    UNUSED("未调用插件"),
}

internal enum class LegacyInsertPosition {
    LIST_BOTTOM,
    ABOVE,
    BELOW,
    INSIDE,
    REPLACE,
}

internal data class LegacyDockProgramNode(
    val nodeId: String,
    val label: String,
    val kind: String,
    val depth: Int,
    val childSlots: List<String> = emptyList(),
)

private fun legacyEntryForKind(kind: String): LegacyToolDialog = when {
    kind.startsWith("vision.") || kind.startsWith("ocr.") -> LegacyToolDialog.IMAGE
    kind.startsWith("control.if") -> LegacyToolDialog.JUDGMENT
    kind.startsWith("control.repeat") || kind.startsWith("control.while") -> LegacyToolDialog.LOOP
    kind.startsWith("file.") -> LegacyToolDialog.FILES
    kind.startsWith("debug.") || kind.startsWith("log.") -> LegacyToolDialog.DEBUG
    kind.startsWith("ai.") -> LegacyToolDialog.AI
    kind.startsWith("function.") || kind == "flow.call" -> LegacyToolDialog.FUNCTIONS
    else -> LegacyToolDialog.COMMON
}

private fun legacyEntryForSnippet(snippet: String): LegacyToolDialog {
    val line = snippet.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
    return when {
        line.contains("Vision.", true) -> LegacyToolDialog.IMAGE
        line.startsWith("if ") || line.startsWith("-- 否则") -> LegacyToolDialog.JUDGMENT
        line.startsWith("while ") || line.startsWith("for ") || line.startsWith("repeat") -> LegacyToolDialog.LOOP
        line.contains("File.", true) -> LegacyToolDialog.FILES
        line.contains("Log.", true) || line.contains("Debug.", true) -> LegacyToolDialog.DEBUG
        line.contains("Input.", true) || line.contains("Accessibility.", true) -> LegacyToolDialog.FUNCTIONS
        else -> LegacyToolDialog.COMMON
    }
}

@Composable
private fun EditorBallIcon(modifier: Modifier = Modifier) {
    Icon(
        painter = painterResource(R.drawable.editor_float_ball_logo),
        contentDescription = "悬浮操作按钮",
        tint = Color.Unspecified,
        modifier = modifier,
    )
}

@Composable
private fun LegacyEditorPopupMenu(
    modifier: Modifier = Modifier,
    onMinimize: () -> Unit,
    onPlugins: () -> Unit,
    onSettings: () -> Unit,
    onClose: () -> Unit,
) {
    Surface(
        modifier = modifier.width(96.dp),
        color = Color.White,
        shape = RoundedCornerShape(topEnd = 0.dp, bottomEnd = 3.dp),
        shadowElevation = 7.dp,
    ) {
        Column {
            LegacyEditorPopupRow(R.drawable.editor_popup_minimize_20, "最小化", onMinimize)
            LegacyEditorPopupRow(R.drawable.editor_popup_plugin_20, "插件", onPlugins)
            LegacyEditorPopupRow(R.drawable.editor_popup_settings_20, "设置", onSettings)
            LegacyEditorPopupRow(R.drawable.editor_popup_power_20, "关闭", onClose)
        }
    }
}

@Composable
private fun LegacyEditorPopupRow(
    @DrawableRes icon: Int,
    label: String,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().height(44.dp).clickable(onClick = onClick)
            .padding(start = 8.dp, end = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = null,
            tint = Color.Unspecified,
            modifier = Modifier.size(20.dp),
        )
        Text(label, color = Color(0xFF161B27), fontSize = 14.sp, modifier = Modifier.padding(start = 8.dp))
    }
}

@Composable
private fun LegacyDockPanel(
    projectName: String,
    title: String,
    hasSource: Boolean,
    nodes: List<LegacyTreeNode>,
    selectedNodeId: String?,
    consoleVisible: Boolean,
    consoleLines: List<String>,
    editingEnabled: Boolean,
    onMenu: () -> Unit,
    onCreateSource: () -> Unit,
    onRun: () -> Unit,
    onStep: () -> Unit,
    onFunctions: () -> Unit,
    onTool: (LegacyToolDialog) -> Unit,
    onSelectNode: (String) -> Unit,
    onToggleNode: (String) -> Unit,
    onDeleteNode: () -> Unit,
    onEditNode: () -> Unit,
    onOpenNodeEditor: () -> Unit,
    onOpenMoreMenu: () -> Unit,
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
    LaunchedEffect(selectedNodeId, visibleNodes) {
        val selectedIndex = visibleNodes.indexOfFirst { it.id == selectedNodeId }
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
            Row(
                modifier = Modifier.fillMaxWidth().height(35.dp).background(Color.White),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                EditorMenuIcon(Modifier.width(35.dp).fillMaxHeight().clickable(onClick = onMenu).padding(horizontal = 8.dp, vertical = 9.dp))
                Text(
                    title,
                    color = Color(0xFF1C2333),
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).fillMaxHeight()
                        .pointerInput(projectName, "title-drag") {
                            detectDragGestures { change, delta ->
                                change.consume()
                                currentOnDrag(delta)
                            }
                        }
                        .clickable(onClick = onCreateSource)
                        .wrapContentSize(Alignment.Center),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
                Spacer(Modifier.width(35.dp))
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFFF0F1F4)))
            Row(Modifier.fillMaxWidth().weight(1f)) {
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    if (consoleVisible) {
                        LegacyConsolePanel(consoleLines)
                    } else if (hasSource) {
                        Box(Modifier.fillMaxSize().horizontalScroll(treeHorizontalState)) {
                            LazyColumn(
                                state = treeListState,
                                modifier = Modifier.width(960.dp).fillMaxHeight(),
                            ) {
                                itemsIndexed(visibleNodes, key = { _, node -> node.id }) { visibleIndex, node ->
                                    val fullIndex = nodeIndexes[node.id] ?: -1
                                    LegacyProgramTreeRow(
                                        node = node,
                                        hasChildren = legacyHasChildren(nodes, fullIndex),
                                        selected = selectedNodeId == node.id,
                                        onSelect = { onSelectNode(node.id) },
                                        onToggle = { onToggleNode(node.id) },
                                    )
                                    if (visibleIndex != visibleNodes.lastIndex) {
                                        Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFFF0F1F4)))
                                    }
                                }
                            }
                        }
                    }
                }
                Column(modifier = Modifier.width(51.dp).padding(horizontal = 3.dp, vertical = 2.dp)) {
                    LegacyDockAction("文件", editingEnabled) { onTool(LegacyToolDialog.FILES) }
                    LegacyDockAction("工具", editingEnabled) { onTool(LegacyToolDialog.TOOLS) }
                    LegacyDockAction("图像", editingEnabled) { onTool(LegacyToolDialog.IMAGE) }
                    LegacyDockAction("判断", editingEnabled) { onTool(LegacyToolDialog.JUDGMENT) }
                    LegacyDockAction("循环", editingEnabled) { onTool(LegacyToolDialog.LOOP) }
                    LegacyDockAction("常用", editingEnabled) { onTool(LegacyToolDialog.COMMON) }
                    LegacyDockAction("调试", editingEnabled) { onTool(LegacyToolDialog.DEBUG) }
                    LegacyDockAction("函数", editingEnabled, onFunctions)
                    LegacyDockAction("AI", editingEnabled) { onTool(LegacyToolDialog.AI) }
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFFF0F1F4)))
            Row(
                modifier = Modifier.fillMaxWidth().height(40.dp).background(Color.White)
                    .padding(start = 1.dp, top = 7.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.Start,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                EditorBottomButton(EditorBottomIcon.RUN, true, onRun)
                EditorBottomButton(EditorBottomIcon.STEP, hasSource, onStep)
                EditorBottomButton(EditorBottomIcon.DELETE, editingEnabled && selectedNodeId != null, onDeleteNode)
                EditorBottomButton(EditorBottomIcon.EDIT, editingEnabled && selectedNodeId != null, onOpenNodeEditor)
                EditorBottomButton(EditorBottomIcon.BACKFILL, editingEnabled && hasSource) { onTool(LegacyToolDialog.DATA_BACKFILL) }
                EditorBottomButton(EditorBottomIcon.MORE, editingEnabled && hasSource, onOpenMoreMenu)
                EditorBottomButton(EditorBottomIcon.SWAP, true, onToggleConsole)
                Spacer(Modifier.weight(1f))
                ResizeHandle(Modifier.size(20.dp).padding(2.dp), onResize)
            }
        }
    }
}

/**
 * `line_tk_console_container`：参考的性能行（CPU/内存）默认 GONE、只在“性能信息”开关打开时出现，
 * 我们没有性能采样，所以不画那行占位。空态文案如实说明当前只有引擎/Root 生命周期与失败诊断，
 * 脚本 `Log(...)` 输出流还没接到 Studio。
 */
@Composable
private fun LegacyConsolePanel(lines: List<String>) {
    val listState = rememberLazyListState()
    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) listState.animateScrollToItem(lines.lastIndex)
    }
    Column(Modifier.fillMaxSize().background(Color.White)) {
        if (lines.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
                Text(
                    "暂无运行日志\n运行后引擎状态、Root 状态与失败诊断会显示在这里",
                    color = Color(0xFF777777),
                    fontSize = 11.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(horizontal = 8.dp), state = listState) {
                itemsIndexed(lines) { _, line ->
                    Text(
                        line,
                        color = if (line.contains("诊断：") || line.endsWith("失败")) AutoScriptPalette.Danger else Color.Black,
                        fontSize = 10.sp,
                        modifier = Modifier.padding(vertical = 2.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun LegacyMorePopup(
    modifier: Modifier,
    onShowMain: () -> Unit,
    onRecord: () -> Unit,
    onVariableCheck: () -> Unit,
    onSearchReplace: () -> Unit,
    onRuntimeVariables: () -> Unit,
) {
    val rows = listOf(
        Triple("显示界面", R.drawable.editor_more_show_ui_24, onShowMain),
        Triple("录制动作", R.drawable.editor_more_record_24, onRecord),
        Triple("变量检查", R.drawable.editor_more_variable_check_24, onVariableCheck),
        Triple("查找替换", R.drawable.editor_more_search_24, onSearchReplace),
        Triple("变量信息", R.drawable.editor_more_runtime_variables_24, onRuntimeVariables),
    )
    Surface(
        modifier = modifier.width(148.dp),
        color = Color.White,
        shape = RoundedCornerShape(topStart = 2.dp, topEnd = 2.dp, bottomEnd = 2.dp),
        shadowElevation = 8.dp,
    ) {
        Column {
            rows.forEach { (label, icon, action) ->
                Row(
                    Modifier.fillMaxWidth().height(44.dp).clickable(onClick = action).padding(start = 8.dp, end = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        painter = painterResource(icon),
                        contentDescription = null,
                        tint = EditorBlue,
                        modifier = Modifier.size(24.dp),
                    )
                    Text(label, color = Color(0xFF161B27), fontSize = 14.sp, modifier = Modifier.padding(start = 8.dp))
                }
            }
        }
    }
}

/**
 * `tk_bjck.xml`（底栏“编辑”）：顶部 40dp 面包屑（选中节点的祖先链，8sp `lan3`），中间是带复选框的
 * 35dp 程序树行，底部两行 40dp 图标栏——第二行 撤销/恢复/上移/下移/左移/右移，第一行
 * 关闭/参数/删除/复制/粘贴/剪切。参考里第一行第二项是“注释”，可视化项目没有注释语义，
 * 这里映射为编辑节点参数（保留原值回显）。
 */
@Composable
private fun LegacyNodeEditDialog(
    nodes: List<LegacyTreeNode>,
    selectedNodeId: String?,
    onSelectNode: (String) -> Unit,
    onEditSelected: () -> Unit,
    onDeleteSelected: () -> Unit,
    onCommand: (LegacyDockProgramCommand) -> Unit,
    onDismiss: () -> Unit,
) {
    val selectedIndex = nodes.indexOfFirst { it.id == selectedNodeId }
    val hasSelection = selectedIndex >= 0
    val breadcrumb = remember(nodes, selectedIndex) { legacyBreadcrumb(nodes, selectedIndex) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.fillMaxSize().padding(vertical = 5.dp),
            color = Color.White,
            shape = RoundedCornerShape(2.dp),
            shadowElevation = 10.dp,
        ) {
            Column {
                Text(
                    breadcrumb.ifBlank { "请选择程序节点" },
                    color = EditorBlue,
                    fontSize = 8.sp,
                    lineHeight = 12.sp,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().padding(5.dp).height(40.dp).padding(start = 5.dp),
                )
                Box(Modifier.fillMaxWidth().height(hairline()).background(AutoScriptPalette.Divider))
                LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                    itemsIndexed(nodes, key = { _, node -> node.id }) { _, node ->
                        Row(
                            Modifier.fillMaxWidth().height(35.dp)
                                .background(if (node.id == selectedNodeId) Color(0xFFE7EEFF) else Color.White)
                                .clickable { onSelectNode(node.id) }
                                .padding(start = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = node.id == selectedNodeId,
                                onCheckedChange = { onSelectNode(node.id) },
                                colors = CheckboxDefaults.colors(checkedColor = EditorBlue, uncheckedColor = Color(0xFF8A909B)),
                                modifier = Modifier.size(30.dp),
                            )
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
                Row(
                    Modifier.fillMaxWidth().height(40.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    LegacyDockToolbarItem(R.drawable.visual_undo_24, "撤销", true) { onCommand(LegacyDockProgramCommand.UNDO) }
                    LegacyDockToolbarItem(R.drawable.visual_redo_24, "恢复", true) { onCommand(LegacyDockProgramCommand.REDO) }
                    LegacyDockToolbarItem(R.drawable.ic_arrow_right_24, "上移", hasSelection, rotation = -90f) { onCommand(LegacyDockProgramCommand.MOVE_UP) }
                    LegacyDockToolbarItem(R.drawable.ic_arrow_right_24, "下移", hasSelection, rotation = 90f) { onCommand(LegacyDockProgramCommand.MOVE_DOWN) }
                    LegacyDockToolbarItem(R.drawable.ic_arrow_right_24, "左移", hasSelection, rotation = 180f) { onCommand(LegacyDockProgramCommand.OUTDENT) }
                    LegacyDockToolbarItem(R.drawable.ic_arrow_right_24, "右移", hasSelection) { onCommand(LegacyDockProgramCommand.INDENT) }
                }
                Row(
                    Modifier.fillMaxWidth().height(40.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    LegacyDockToolbarItem(R.drawable.editor_close_24, "关闭", true, onClick = onDismiss)
                    LegacyDockToolbarItem(R.drawable.editor_edit_24, "参数", hasSelection, onClick = onEditSelected)
                    LegacyDockToolbarItem(R.drawable.editor_delete_24, "删除", hasSelection, onClick = onDeleteSelected)
                    LegacyDockToolbarItem(R.drawable.editor_file_copy_24, "复制", hasSelection) { onCommand(LegacyDockProgramCommand.COPY) }
                    LegacyDockToolbarItem(R.drawable.editor_file_paste_24, "粘贴", true) { onCommand(LegacyDockProgramCommand.PASTE) }
                    LegacyDockToolbarItem(R.drawable.ic_content_cut_24, "剪切", hasSelection) { onCommand(LegacyDockProgramCommand.CUT) }
                }
            }
        }
    }
}

/** 选中节点的祖先链，如 `限次循环(3次)>循环体>点击`；按 depth 逆向回溯得到。 */
private fun legacyBreadcrumb(nodes: List<LegacyTreeNode>, selectedIndex: Int): String {
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
private fun LegacyDockToolbarItem(
    @DrawableRes icon: Int,
    label: String,
    enabled: Boolean,
    rotation: Float = 0f,
    onClick: () -> Unit,
) {
    val tint = if (enabled) EditorBlue else EditorDisabledBlue
    Column(
        Modifier.clickable(enabled = enabled, onClick = onClick).padding(horizontal = 15.dp, vertical = 3.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(painterResource(icon), contentDescription = label, tint = tint, modifier = Modifier.size(20.dp).rotate(rotation))
        Text(label, color = tint, fontSize = 8.sp)
    }
}

private fun legacyVisibleTreeNodes(nodes: List<LegacyTreeNode>): List<LegacyTreeNode> {
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

@Composable
private fun LegacyProgramTreeRow(
    node: LegacyTreeNode,
    hasChildren: Boolean,
    selected: Boolean,
    onSelect: () -> Unit,
    onToggle: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().height(35.dp)
            .background(if (selected) Color(0xFFE7EEFF) else Color.White)
            .clickable(onClick = onSelect)
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
        Text(
            node.label,
            // item_tree_list 的 tv_name 是纯黑 #000000，不是偏蓝的深灰。
            color = Color.Black,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).fillMaxHeight().padding(start = 1.dp, top = 8.dp, end = 4.dp),
        )
    }
}

@Composable
private fun LegacyProgramSearchDialog(
    nodes: List<LegacyTreeNode>,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val matches = remember(nodes, query) {
        val normalized = query.trim()
        if (normalized.isEmpty()) nodes.take(100)
        else nodes.filter { node ->
            node.label.contains(normalized, ignoreCase = true) ||
                node.code.contains(normalized, ignoreCase = true)
        }.take(100)
    }
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.fillMaxWidth().widthIn(max = 430.dp),
            color = Color.White,
            shape = RoundedCornerShape(3.dp),
            shadowElevation = 8.dp,
        ) {
            Column(Modifier.padding(10.dp)) {
                Text("搜索程序节点", color = EditorBlue, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                BasicTextField(
                    value = query,
                    onValueChange = { query = it.take(80) },
                    singleLine = true,
                    textStyle = TextStyle(color = Color(0xFF202839), fontSize = 13.sp),
                    modifier = Modifier.padding(top = 9.dp).fillMaxWidth().height(34.dp)
                        .border(1.dp, EditorBorder, RoundedCornerShape(3.dp))
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                )
                LazyColumn(Modifier.fillMaxWidth().height(245.dp).padding(top = 6.dp)) {
                    itemsIndexed(matches, key = { _, node -> node.id }) { _, node ->
                        Row(
                            Modifier.fillMaxWidth().height(36.dp).clickable { onSelect(node.id) }
                                .padding(start = (5 + node.depth.coerceAtMost(12) * 8).dp, end = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(node.label, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    LegacyDialogTextButton("关闭", onDismiss)
                }
            }
        }
    }
}

@Composable
private fun LegacyInsertPositionDialog(
    hasSelection: Boolean,
    canInsertInside: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (LegacyInsertPosition) -> Unit,
) {
    var selected by rememberSaveable { mutableStateOf(LegacyInsertPosition.LIST_BOTTOM) }
    val options = listOf(
        LegacyInsertPosition.LIST_BOTTOM to "列表最底部",
        LegacyInsertPosition.ABOVE to "选择行上方",
        LegacyInsertPosition.BELOW to "选择行下方",
        LegacyInsertPosition.INSIDE to "选择行里面",
        LegacyInsertPosition.REPLACE to "修改选择行",
    )
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.fillMaxWidth(.50f).widthIn(max = 280.dp),
            color = Color.White,
            shape = RoundedCornerShape(2.dp),
            shadowElevation = 9.dp,
        ) {
            Column {
                Row(
                    Modifier.fillMaxWidth().height(42.dp).padding(start = 14.dp, end = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("加入位置", color = EditorBlue, fontSize = 16.sp, modifier = Modifier.weight(1f))
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
                        LegacyInsertPosition.LIST_BOTTOM -> true
                        LegacyInsertPosition.INSIDE -> hasSelection && canInsertInside
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
                    Text(
                        "取消",
                        color = Color(0xFF20242C),
                        fontSize = 14.sp,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier = Modifier.weight(1f).fillMaxHeight().clickable(onClick = onDismiss).padding(top = 11.dp),
                    )
                    Box(Modifier.width(hairline()).fillMaxHeight().background(AutoScriptPalette.Divider))
                    Text(
                        "确定",
                        color = EditorBlue,
                        fontSize = 14.sp,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier = Modifier.weight(1f).fillMaxHeight().clickable { onConfirm(selected) }.padding(top = 11.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun LegacyDialogTextButton(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Text(
        label,
        color = Color(0xFF3F51B5),
        fontSize = 14.sp,
        modifier = modifier.clickable(onClick = onClick).padding(horizontal = 15.dp, vertical = 8.dp),
    )
}

@Composable
private fun LegacyDialogTextButton(label: String, onClick: () -> Unit) = LegacyDialogTextButton(label, Modifier, onClick)

@Composable
private fun LegacyDockAction(label: String, enabled: Boolean = true, onClick: () -> Unit) {
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
private fun EditorMenuIcon(modifier: Modifier) {
    Icon(
        painter = painterResource(R.drawable.editor_menu_24),
        contentDescription = "功能列表",
        tint = EditorBlue,
        modifier = modifier,
    )
}

private enum class EditorBottomIcon(@DrawableRes val drawable: Int, val description: String) {
    RUN(R.drawable.editor_run_24, "运行"),
    STEP(R.drawable.editor_step_run_24, "单步运行"),
    DELETE(R.drawable.editor_delete_24, "删除"),
    EDIT(R.drawable.editor_edit_24, "编辑"),
    BACKFILL(R.drawable.editor_backfill_24, "数据回填"),
    MORE(R.drawable.editor_more_24, "更多工具"),
    SWAP(R.drawable.editor_swap_24, "切换程序树/控制台日志"),
}

@Composable
private fun EditorBottomButton(icon: EditorBottomIcon, enabled: Boolean, onClick: () -> Unit) {
    val tint = when (icon) {
        EditorBottomIcon.RUN -> Color(0xFF9ADDB7)
        EditorBottomIcon.SWAP -> EditorBlue
        else -> if (enabled) EditorBlue else EditorDisabledBlue
    }
    Box(Modifier.width(30.dp).height(25.dp), contentAlignment = Alignment.CenterStart) {
        Icon(
            painter = painterResource(icon.drawable),
            contentDescription = icon.description,
            tint = tint,
            modifier = Modifier.size(25.dp).clickable(enabled = enabled, onClick = onClick),
        )
    }
}

@Composable
private fun ResizeHandle(modifier: Modifier, onResize: (Offset) -> Unit) {
    val currentOnResize by rememberUpdatedState(onResize)
    Icon(
        painter = painterResource(R.drawable.editor_resize_24),
        contentDescription = "调整窗口大小",
        tint = Color.Unspecified,
        modifier = modifier.pointerInput(Unit) {
            detectDragGestures { change, delta ->
                change.consume()
                currentOnResize(delta)
            }
        },
    )
}

/**
 * `service_tk_functionui.xml`：40dp 头（搜索、关闭图标）、1px 分割、三列（权重 1.2/1.2/0.9，30dp 行）。
 * 第一列分类、第二列条目、第三列是所选条目的“加入”和参数说明；搜索态整体换成结果列表。
 * 内容只来自 [LegacyFunctionCatalog]：可视化项目是积木目录，Lua 项目是真实脚本 API。
 */
@Composable
internal fun LegacyFunctionLibrary(
    groups: List<LegacyFunctionGroup>,
    capabilities: Set<String>,
    onDismiss: () -> Unit,
    onInsert: (String) -> Unit,
) {
    var groupIndex by rememberSaveable { mutableStateOf(0) }
    var entryIndex by rememberSaveable { mutableStateOf(0) }
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    val group = groups.getOrNull(groupIndex)
    val entries = group?.entries.orEmpty()
    val entry = entries.getOrNull(entryIndex)
    val matches = remember(groups, query) {
        val needle = query.trim()
        if (needle.isEmpty()) emptyList() else groups.flatMap { candidate ->
            candidate.entries.filter { item ->
                item.title.contains(needle, ignoreCase = true) ||
                    item.blockKind?.contains(needle, ignoreCase = true) == true ||
                    item.snippet.contains(needle, ignoreCase = true)
            }.map { candidate.label to it }
        }
    }
    fun available(item: LegacyFunctionEntry): Boolean =
        item.blockKind == null || item.requiredCapabilities.all(capabilities::contains)

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        // 参考根布局无横向 padding，面板是满宽的。
        Surface(
            modifier = Modifier.fillMaxWidth().fillMaxHeight(.64f),
            color = Color.White,
            shape = RoundedCornerShape(2.dp),
            shadowElevation = 8.dp,
        ) {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth().height(40.dp).background(Color.White).padding(start = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (searching) {
                        BasicTextField(
                            value = query,
                            onValueChange = { query = it },
                            singleLine = true,
                            textStyle = TextStyle(fontSize = 12.sp, color = Color(0xFF202839)),
                            modifier = Modifier.weight(1f).height(34.dp).padding(horizontal = 5.dp, vertical = 8.dp),
                            decorationBox = { field -> Box { if (query.isEmpty()) Text("搜索关键字", color = Color(0xFF8A939E), fontSize = 12.sp); field() } },
                        )
                    } else {
                        Spacer(Modifier.weight(1f))
                    }
                    Icon(
                        painter = painterResource(R.drawable.editor_function_search_24),
                        contentDescription = if (searching) "退出搜索" else "搜索",
                        tint = Color.Unspecified,
                        modifier = Modifier.size(34.dp).clickable { searching = !searching; if (!searching) query = "" }.padding(5.dp),
                    )
                    Icon(
                        painter = painterResource(R.drawable.editor_close_24),
                        contentDescription = "关闭",
                        tint = Color.Black,
                        modifier = Modifier.padding(end = 3.dp).size(34.dp).clickable(onClick = onDismiss).padding(5.dp),
                    )
                }
                Box(Modifier.fillMaxWidth().height(hairline()).background(AutoScriptPalette.Divider))
                if (searching) {
                    if (query.isBlank()) {
                        Text(
                            "输入中文函数名、英文函数名或变量名",
                            color = Color(0xFF777777),
                            fontSize = 12.sp,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(20.dp),
                        )
                    } else if (matches.isEmpty()) {
                        Text("没有匹配结果", color = Color(0xFF777777), fontSize = 12.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(20.dp))
                    } else {
                        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                            itemsIndexed(matches) { _, (label, item) ->
                                val enabled = available(item)
                                Row(
                                    Modifier.fillMaxWidth().height(30.dp).clickable(enabled = enabled) { onInsert(item.snippet) }.padding(horizontal = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(item.title, color = if (enabled) Color.Black else Color(0xFF9DA6B4), fontSize = 13.sp, modifier = Modifier.weight(1f))
                                    Text(label, color = Color(0xFF777777), fontSize = 10.sp)
                                }
                            }
                        }
                    }
                } else {
                    Row(Modifier.weight(1f).padding(horizontal = 1.dp)) {
                        Column(Modifier.weight(1.2f).fillMaxHeight().background(Color(0xFFF5F5F5)).verticalScroll(rememberScrollState())) {
                            groups.forEachIndexed { index, candidate ->
                                LegacyLibraryTab(candidate.label, groupIndex == index) {
                                    groupIndex = index
                                    entryIndex = 0
                                }
                            }
                        }
                        // 参考列间竖线用的是 hs2（与三列底色同色），近乎隐形，不是深色描边。
                        Box(Modifier.width(hairline()).fillMaxHeight().background(AutoScriptPalette.DividerSoft))
                        Column(Modifier.weight(1.2f).fillMaxHeight().background(Color(0xFFF5F5F5)).verticalScroll(rememberScrollState())) {
                            entries.forEachIndexed { index, item ->
                                LegacyLibraryTab(item.title, entryIndex == index, enabled = available(item)) { entryIndex = index }
                            }
                        }
                        // 参考列间竖线用的是 hs2（与三列底色同色），近乎隐形，不是深色描边。
                        Box(Modifier.width(hairline()).fillMaxHeight().background(AutoScriptPalette.DividerSoft))
                        Column(Modifier.weight(.9f).fillMaxHeight().background(Color(0xFFF5F5F5)).verticalScroll(rememberScrollState())) {
                            if (entry == null) {
                                Text("暂无可用内容", color = Color(0xFF8A939E), fontSize = 11.sp, modifier = Modifier.fillMaxWidth().padding(12.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                            } else {
                                val enabled = available(entry)
                                LegacyLibraryTab("加入", selected = false, enabled = enabled) { onInsert(entry.snippet) }
                                Text(entry.detail, color = Color(0xFF777777), fontSize = 10.sp, lineHeight = 14.sp, modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp))
                                entry.parameters.forEach { parameter ->
                                    Text("· $parameter", color = Color(0xFF202839), fontSize = 11.sp, modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp))
                                }
                                if (!enabled) {
                                    Text(
                                        "需要能力：${(entry.requiredCapabilities - capabilities).joinToString()}",
                                        color = AutoScriptPalette.Danger,
                                        fontSize = 10.sp,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 右栏九个入口和“更多”菜单的弹窗路由；录制仍是应用内预览，直到 Runtime 提供事件流。 */
@Composable
private fun LegacyToolDialogScreen(
    dialog: LegacyToolDialog,
    projectName: String,
    files: List<StudioProjectFile>,
    onDismiss: () -> Unit,
    onOpenFile: (StudioProjectFile) -> Unit,
    onDeleteFiles: (List<StudioProjectFile>) -> Unit,
    onOpenImageTools: (() -> Unit)?,
    onInsert: (String) -> Unit,
) {
    if (dialog != LegacyToolDialog.RECORDING) {
        EditorEntryDialog(
            entry = dialog,
            projectName = projectName,
            files = files,
            onDismiss = onDismiss,
            onInsert = onInsert,
            onOpenFile = onOpenFile,
            onDeleteFiles = onDeleteFiles,
            onOpenImageTools = onOpenImageTools,
        )
        return
    }
    Dialog(onDismissRequest = onDismiss) {
        Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(5.dp), color = Color(0xFFF6F6F6)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("录制模式", color = LegacyDockGreenDark, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                Text("原版会收起编辑面板并显示悬浮录制控制。当前先以应用内控制预览呈现，Runtime 事件流接入后会写入真实动作。", fontSize = 13.sp, color = Color(0xFF596270))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LegacyDialogControl("● REC", Color(0xFFD84949))
                    LegacyDialogControl("暂停", LegacyDockGreen)
                    LegacyDialogControl("结束", Color(0xFF555B65))
                }
                Text("录制内容：点击、长按、滑动、按键、等待", fontSize = 12.sp, color = Color(0xFF6A7480))
                Text("关闭", modifier = Modifier.fillMaxWidth().border(1.dp, LegacyFunctionBlue, RoundedCornerShape(3.dp)).clickable(onClick = onDismiss).padding(10.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center, color = LegacyFunctionBlue)
            }
        }
    }
}

/**
 * 旧版 P06「插件管理」：白底、60dp 标题 18sp `#33AAFF`、右上问号、2dp `#6699FF` 分割、
 * 八个单选行、底部 取消/确定。“插件”在本项目里就是源文件（Flow）。
 */
@Composable
private fun LegacyPluginManagerDialog(
    enabled: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (LegacyPluginAction) -> Unit,
) {
    var selected by rememberSaveable { mutableStateOf(LegacyPluginAction.CREATE) }
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
                        color = LegacyFunctionBlue,
                        fontSize = 18.sp,
                        modifier = Modifier.align(Alignment.CenterStart).padding(start = 10.dp),
                    )
                    Icon(
                        painterResource(R.drawable.ic_help_outline_24),
                        contentDescription = "说明",
                        tint = LegacyFunctionBlue,
                        modifier = Modifier.align(Alignment.TopEnd).size(30.dp).clickable { showHelp = !showHelp }.padding(3.dp),
                    )
                }
                Box(Modifier.fillMaxWidth().height(2.dp).background(Color(0xFF6699FF)))
                if (showHelp) {
                    Text(
                        if (enabled) {
                            "插件即源文件（Flow）。创建、删除、另存、分组会打开源文件管理；检错用 Rust 编译器校验项目；" +
                                "未调用插件列出没有被任何调用节点引用的源文件；存储为模版预留。"
                        } else {
                            "Lua 项目只有 main.lua，没有可管理的插件。"
                        },
                        color = Color(0xFF596270),
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
                LegacyPluginAction.entries.forEach { action ->
                    val available = enabled && action != LegacyPluginAction.TEMPLATE
                    Row(
                        Modifier.fillMaxWidth().height(45.dp)
                            .clickable(enabled = available) { selected = action }
                            .padding(start = 10.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            if (action == LegacyPluginAction.TEMPLATE) "${action.label}（预留）" else action.label,
                            color = if (available) Color.Black else Color(0xFF9DA6B4),
                            fontSize = 18.sp,
                            modifier = Modifier.weight(1f),
                        )
                        RadioButton(
                            selected = selected == action,
                            onClick = { selected = action },
                            enabled = available,
                            colors = RadioButtonDefaults.colors(selectedColor = LegacyFunctionBlue),
                        )
                    }
                    Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFFE3E3E3)))
                }
                Row(Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 10.dp, vertical = 4.dp)) {
                    LegacyPluginButton("取消", Modifier.weight(1f), onClick = onDismiss)
                    Spacer(Modifier.width(4.dp))
                    LegacyPluginButton("确定", Modifier.weight(1f), enabled = enabled && selected != LegacyPluginAction.TEMPLATE) { onConfirm(selected) }
                }
            }
        }
    }
}

/** `guagua_but_style5`：白底灰边 16sp 黑字。 */
@Composable
private fun LegacyPluginButton(label: String, modifier: Modifier, enabled: Boolean = true, onClick: () -> Unit) {
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

@Composable
private fun LegacyDialogTitle(title: String) {
    Text(title, color = Color(0xFF3D5AFE), fontSize = 17.sp, fontWeight = FontWeight.Bold)
}

@Composable
private fun LegacyDialogRows(items: List<String>) {
    items.forEach { label ->
        Row(
            Modifier.fillMaxWidth().height(34.dp).border(1.dp, EditorBorder, RoundedCornerShape(2.dp)).clickable { }.padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, color = Color(0xFF202839), fontSize = 12.sp, modifier = Modifier.weight(1f))
            Text("›", color = Color(0xFF8A939E), fontSize = 18.sp)
        }
    }
}

@Composable
private fun LegacyValueBox(label: String, modifier: Modifier = Modifier) {
    Text(
        label,
        color = Color(0xFF3D5AFE),
        fontSize = 10.sp,
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        modifier = modifier.height(32.dp).border(1.dp, EditorBorder, RoundedCornerShape(2.dp)).wrapContentSize(Alignment.Center),
    )
}

@Composable
private fun LegacyDialogControl(label: String, color: Color) {
    Text(label, modifier = Modifier.background(color, RoundedCornerShape(3.dp)).padding(horizontal = 12.dp, vertical = 8.dp), color = Color.White, fontSize = 12.sp)
}

/** item_tree_list_functionui_1：30dp 行，黑字居中；选中项加蓝色左标和下划线。 */
@Composable
private fun LegacyLibraryTab(label: String, selected: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    Box(Modifier.fillMaxWidth().height(30.dp).clickable(enabled = enabled, onClick = onClick)) {
        Box(
            Modifier.align(Alignment.CenterStart).width(1.5.dp).fillMaxHeight()
                .background(if (selected) Color(0xFF4C8DFF) else Color.Transparent),
        )
        Text(
            label,
            color = when {
                !enabled -> Color(0xFF9DA6B4)
                selected -> Color(0xFF2864F0)
                else -> Color.Black
            },
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxSize().wrapContentSize(Alignment.Center),
        )
        Box(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth(.82f).height(1.5.dp)
                .background(if (selected) Color(0xFF2864F0) else Color.Transparent),
        )
    }
}

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
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
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

// Stateless chrome and dialogs; window/session state remains in EditorDock.
@Composable
internal fun EditorBallIcon(modifier: Modifier = Modifier, description: String = "智构 · 打开编辑器") {
    Image(
        painter = painterResource(com.autoscript.brand.ui.R.drawable.zhigou_brand_mark),
        contentDescription = description,
        modifier = modifier.padding(2.dp),
    )
}

@Composable
internal fun EditorDockPanel(
    projectName: String,
    title: String,
    canSelectFlow: Boolean,
    nodes: List<EditorTreeNode>,
    selectedNodeId: String?,
    currentNodeId: String?,
    executionRecent: Boolean,
    executionStatus: String?,
    onDismissExecutionStatus: () -> Unit,
    editingEnabled: Boolean,
    running: Boolean,
    paused: Boolean,
    resumeEnabled: Boolean,
    onResume: () -> Unit,
    onPause: () -> Unit,
    runEnabled: Boolean,
    stopEnabled: Boolean,
    onSettings: () -> Unit,
    panelMode: EditorPanelMode,
    onMaximize: () -> Unit,
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
    onDragStart: (Offset) -> Unit,
    onDrag: (Offset) -> Unit,
    onResize: (Offset) -> Unit,
    selectedBranch: EditorBranchSelection? = null,
    onSelectBranch: ((String, String) -> Unit)? = null,
    followExecution: Boolean = true,
    onFollowExecution: (Boolean) -> Unit = {},
    executionAvailable: Boolean = currentNodeId != null,
    focusNodeId: String? = null,
    focusPath: List<EditorFocusCrumb> = emptyList(),
    onFocus: (String?) -> Unit = {},
) {
    val viewNodes = remember(nodes, focusNodeId) { editorFocusedTreeNodes(nodes, focusNodeId) }
    val displayRows = remember(viewNodes) { editorTreeDisplayRows(viewNodes) }
    val impact = remember(nodes, selectedNodeIds) { editorSelectionImpact(nodes, selectedNodeIds) }
    val descendantCounts = remember(nodes) { editorDescendantCounts(nodes) }
    var expandedSummaryId by remember(selectedNodeId, selectedNodeIds) { mutableStateOf<String?>(null) }
    val nodeIndexes = remember(nodes) { nodes.mapIndexed { index, node -> node.id to index }.toMap() }
    val treeListState = rememberLazyListState()
    val rowIndexes = remember(displayRows) {
        buildMap { displayRows.forEachIndexed { index, row ->
            row.nodeId?.let { id -> if (!containsKey(id)) put(id, index) }
        } }
    }
    val deepestDepth = remember(displayRows) { displayRows.maxOfOrNull { it.depth } ?: 0 }
    val treeHorizontalState = rememberScrollState()
    // pointerInput 只在首次组合时捕获回调；面板缩放、旋转后边界会变，所以每次都取最新的 onDrag。
    val currentOnDrag by rememberUpdatedState(onDrag)
    val currentOnDragStart by rememberUpdatedState(onDragStart)
    val headerDensity = LocalDensity.current
    val headerButtonWidthPx = with(headerDensity) { EDITOR_HEADER_BUTTON_WIDTH_DP.dp.toPx() }
    val headerInsetPx = with(headerDensity) { EDITOR_HEADER_INSET_DP.dp.toPx() }
    val currentOnFollow by rememberUpdatedState(onFollowExecution)
    val hasExecution by rememberUpdatedState(executionAvailable)
    val manualScroll = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && available != Offset.Zero && hasExecution) currentOnFollow(false)
                return Offset.Zero
            }
        }
    }
    LaunchedEffect(selectedNodeId, currentNodeId, displayRows, selectedBranch, followExecution, executionAvailable) {
        if (executionAvailable && !followExecution) return@LaunchedEffect
        val selectedIndex = if (currentNodeId != null) rowIndexes[currentNodeId] ?: -1
            else if (selectedBranch != null) displayRows.indexOfFirst {
                it is EditorTreeDisplayRow.Branch && it.ownerId == selectedBranch.ownerId && it.slot == selectedBranch.slot
            } else rowIndexes[selectedNodeId] ?: -1
        val alreadyVisible = treeListState.layoutInfo.visibleItemsInfo.any { it.index == selectedIndex }
        if (selectedIndex >= 0 && !alreadyVisible) {
            if (currentNodeId != null) treeListState.scrollToItem(selectedIndex) else treeListState.animateScrollToItem(selectedIndex)
        }
    }
    Surface(
        modifier = modifier,
        color = Color.White,
        shape = RoundedCornerShape(2.dp),
        shadowElevation = 7.dp,
    ) {
        Column {
            Box(
                modifier = Modifier.fillMaxWidth().height(EDITOR_HEADER_HEIGHT_DP.dp).background(Color(0xFFF8FAFE))
                    .pointerInput(projectName, headerDensity.density, "titlebar-drag") {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            if (editorHeaderIsButtonArea(down.position.x, size.width.toFloat(), headerButtonWidthPx, headerInsetPx)) return@awaitEachGesture
                            val started = awaitTouchSlopOrCancellation(down.id) { change, delta ->
                                currentOnDragStart(down.position)
                                change.consume(); currentOnDrag(delta)
                            } ?: return@awaitEachGesture
                            drag(started.id) { change ->
                                val delta = change.positionChange()
                                change.consume(); currentOnDrag(delta)
                            }
                        }
                    },
            ) {
                Row(Modifier.fillMaxSize().padding(horizontal = EDITOR_HEADER_INSET_DP.dp), verticalAlignment = Alignment.CenterVertically) {
                    EditorDockHeaderButton(
                        description = "打开项目设置", drawable = R.drawable.editor_window_settings_20, onClick = onSettings,
                    )
                    Box(
                        modifier = Modifier.weight(1f).fillMaxHeight().padding(vertical = 4.dp, horizontal = 2.dp)
                            .background(if (canSelectFlow) Color(0xFFEDF2FF) else Color.Transparent, RoundedCornerShape(4.dp))
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
                    EditorDockHeaderButton(
                        description = "最小化为智构悬浮球",
                        drawable = R.drawable.editor_window_minimize_20, onClick = onMinimize,
                    )
                    EditorDockHeaderButton(
                        description = if (panelMode == EditorPanelMode.MAXIMIZED) "还原窗口" else "最大化窗口",
                        drawable = if (panelMode == EditorPanelMode.MAXIMIZED) R.drawable.editor_window_restore_20 else R.drawable.editor_window_maximize_20,
                        active = panelMode == EditorPanelMode.MAXIMIZED, onClick = onMaximize,
                    )
                    EditorDockHeaderButton(
                        description = "保存并关闭编辑窗", drawable = R.drawable.editor_window_close_20,
                        danger = true, onClick = onClose,
                    )
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFFF0F1F4)))
            Row(Modifier.fillMaxWidth().weight(1f)) {
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    EditorFocusBreadcrumb(focusPath, onFocus)
                    if (selectionCount > 1) {
                        Box(Modifier.fillMaxWidth().heightIn(min = 20.dp).background(Color(0xFFF8FAFD)).padding(horizontal = 5.dp),
                            contentAlignment = Alignment.CenterStart) {
                            Text(impact.label, fontSize = 10.sp, color = AutoScriptPalette.TextSecondary, maxLines = 2,
                                overflow = TextOverflow.Ellipsis)
                        }
                    }
                    Box(Modifier.weight(1f).fillMaxWidth().clipToBounds().nestedScroll(manualScroll)) {
                        BoxWithConstraints(Modifier.fillMaxSize()) {
                            val contentWidth = editorTreeContentWidth(maxWidth.value, deepestDepth).dp
                            Box(Modifier.fillMaxSize().horizontalScroll(treeHorizontalState)) {
                                LazyColumn(
                                    state = treeListState,
                                    modifier = Modifier.width(contentWidth).fillMaxHeight(),
                                ) {
                                    itemsIndexed(displayRows, key = { _, row -> row.key }) { visibleIndex, row ->
                                        when (row) {
                                            is EditorTreeDisplayRow.Branch -> EditorBranchHeading(row,
                                                selected = selectedBranch?.let { it.ownerId == row.ownerId && it.slot == row.slot } == true,
                                                enabled = editingEnabled && onSelectBranch != null,
                                                onSelect = { onSelectBranch?.invoke(row.ownerId, row.slot) })
                                            is EditorTreeDisplayRow.Node -> {
                                                val node = row.node
                                                val fullIndex = nodeIndexes[node.id] ?: -1
                                                EditorProgramTreeRow(
                                                    node = node,
                                                    hasChildren = editorTreeHasChildren(nodes, fullIndex),
                                                    selected = node.id in selectedNodeIds,
                                                    current = currentNodeId == node.id,
                                                    executing = running && !paused,
                                                    recent = executionRecent,
                                                    onSelect = {
                                                        if (selectedNodeIds.size == 1 && selectedNodeId == node.id)
                                                            expandedSummaryId = if (expandedSummaryId == node.id) null else node.id
                                                        else onSelectNode(node.id)
                                                    },
                                                    onToggle = { onToggleNode(node.id) },
                                                    onLongClick = { onNodeActions(node.id) },
                                                    expandedSummary = expandedSummaryId == node.id,
                                                    descendantCount = descendantCounts[node.id] ?: 0,
                                                )
                                            }
                                        }
                                        if (visibleIndex != displayRows.lastIndex) {
                                            Box(Modifier.fillMaxWidth().height(hairline()).background(Color(0xFFF4F5F8)))
                                        }
                                    }
                                }
                            }
                        }
                        if (!followExecution && executionAvailable) {
                            EditorEditSelectionButton("回到运行行", true, Modifier.align(Alignment.TopEnd)
                                .padding(3.dp).background(Color.White, RoundedCornerShape(3.dp))) { currentOnFollow(true) }
                        }
                    }
                }
                Column(modifier = Modifier.width(51.dp).verticalScroll(rememberScrollState()).padding(horizontal = 3.dp, vertical = 2.dp)) {
                    EditorDockAction("变量", editingEnabled, onManageVariables)
                    EditorDockAction("触控", editingEnabled, onKeys)
                    EditorDockAction("图像", editingEnabled) { onTool(EditorToolPanel.IMAGE) }
                    EditorDockAction("条件", editingEnabled) { onTool(EditorToolPanel.JUDGMENT) }
                    EditorDockAction("循环", editingEnabled) { onTool(EditorToolPanel.LOOP) }
                    EditorDockAction("流程", editingEnabled) { onTool(EditorToolPanel.COMMON) }
                    EditorDockAction("输出", editingEnabled) { onTool(EditorToolPanel.DEBUG) }
                    EditorDockAction("函数", editingEnabled, onFunctions)
                    EditorDockAction("AI", editingEnabled) { onTool(EditorToolPanel.AI) }
                }
            }
            executionStatus?.let { status ->
                EditorDockStatusStrip(status, onDismissExecutionStatus)
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFFF0F1F4)))
            BoxWithConstraints(Modifier.fillMaxWidth().background(Color.White)) {
                val entries = editorToolbarEntries(running, paused, runEnabled, resumeEnabled,
                    stepEnabled, true, editingEnabled, stopEnabled)
                val columns = editorToolbarColumns(maxWidth.value)
                val rows = entries.chunked(columns)
                val showLabels = maxWidth.value >= columns * 44 + 24
                val rowHeight = with(LocalDensity.current) {
                    (16.dp + 1.dp + (if (showLabels) 11.sp.toDp() else 0.dp) + 8.dp).coerceAtLeast(36.dp)
                }
                Row(Modifier.fillMaxWidth().height(rowHeight * rows.size)) {
                    Column(Modifier.weight(1f).fillMaxHeight()) {
                        rows.forEach { row ->
                            Row(Modifier.fillMaxWidth().height(rowHeight), verticalAlignment = Alignment.CenterVertically) {
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
                                    }
                                    EditorBottomButton(entry.action, entry.enabled, showLabels, callback,
                                        Modifier.weight(1f).then(if (entry.action == EditorToolbarAction.MORE)
                                            Modifier.onGloballyPositioned { onMoreAnchor(it.boundsInRoot()) } else Modifier))
                                }
                                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                            }
                        }
                    }
                    if (panelMode == EditorPanelMode.NORMAL) Box(Modifier.width(24.dp).fillMaxHeight(), contentAlignment = Alignment.BottomEnd) {
                        ResizeHandle(Modifier.size(24.dp).padding(3.dp), onResize)
                    }
                }
            }
        }
    }
}

internal fun editorDockStatusText(status: String?, dismissed: Boolean): String? =
    if (dismissed) null else status?.trim()?.takeIf { it.isNotEmpty() }

@Composable
internal fun EditorDockStatusStrip(status: String, onDismiss: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 18.dp).background(Color(0xFFFFF8E9))
        .padding(start = 6.dp, end = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(status, fontSize = 10.sp, color = Color(0xFF855C12), maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(vertical = 1.dp),
            style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false)))
        Box(Modifier.size(18.dp).clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onDismiss),
            contentAlignment = Alignment.Center) {
            Icon(painterResource(R.drawable.editor_window_close_20), contentDescription = "关闭当前提示",
                tint = Color(0xFF855C12), modifier = Modifier.size(12.dp))
        }
    }
}

@Composable
internal fun EditorMorePopup(
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
        MenuRow("录制动作", R.drawable.editor_more_record_24, canRecord, onRecord),
        MenuRow("变量检查", R.drawable.editor_more_variable_check_24, true, onVariableCheck),
        MenuRow("查找替换", R.drawable.editor_more_search_24, editingEnabled, onSearchReplace),
        MenuRow("运行变量", R.drawable.editor_more_runtime_variables_24, true, onRuntimeVariables),
    )
    Surface(
        modifier = modifier.width(164.dp),
        color = Color.White,
        shape = RoundedCornerShape(5.dp),
        shadowElevation = 6.dp,
    ) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 4.dp)) {
            rows.forEach { (label, icon, enabled, action) ->
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 32.dp).clickable(enabled = enabled, onClick = action).padding(horizontal = 10.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        painter = painterResource(icon),
                        contentDescription = null,
                        tint = if (enabled) EditorBlue else EditorDisabledBlue,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(label, color = if (enabled) Color(0xFF161B27) else AutoScriptPalette.TextSecondary, fontSize = 11.sp,
                        modifier = Modifier.padding(start = 8.dp), style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false)))
                }
            }
        }
    }
}


/**
 * 有界多选小窗：小字号选择状态、中部滚动树、底部无分组紧凑文字按钮。
 */
@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun EditorNodeEditDialog(
    nodes: List<EditorTreeNode>,
    editingEnabled: Boolean,
    selectedNodeId: String?,
    selectedNodeIds: Set<String>,
    onToggleNode: ((String) -> Unit)?,
    onSetSelection: ((Set<String>) -> Unit)?,
    onToggleExpansion: (String) -> Unit,
    onSetFolded: (Boolean) -> Unit,
    onSelectNode: (String) -> Unit,
    onEditSelected: () -> Unit,
    onReuseSelected: (() -> Unit)?,
    onAnnotateSelected: (() -> Unit)?,
    onDisplaySelected: (() -> Unit)?,
    onRandomizationSelected: (() -> Unit)?,
    onDeleteSelected: () -> Unit,
    disabledSelection: Boolean,
    onCommand: (EditorProgramCommand) -> Unit,
    canUndo: Boolean,
    canRedo: Boolean,
    canPaste: Boolean,
    onEditSource: (() -> Unit)?,
    onEditSelectedSource: (() -> Unit)?,
    onDismiss: () -> Unit,
    selectedBranch: EditorBranchSelection? = null,
    onSelectBranch: ((String, String) -> Unit)? = null,
    focusNodeId: String? = null,
    focusPath: List<EditorFocusCrumb> = emptyList(),
    onFocus: (String?) -> Unit = {},
    onBatchParameters: (() -> Unit)? = null,
) {
    val selectedIndex = nodes.indexOfFirst { it.id == selectedNodeId }
    val viewNodes = remember(nodes, focusNodeId) { editorFocusedTreeNodes(nodes, focusNodeId) }
    val displayRows = remember(viewNodes) { editorTreeDisplayRows(viewNodes) }
    val descendantCounts = remember(nodes) { editorDescendantCounts(nodes) }
    var expandedSummaryId by remember(selectedNodeId, selectedNodeIds) { mutableStateOf<String?>(null) }
    var rangeStart by remember(displayRows) { mutableStateOf<String?>(null) }
    var rangeError by remember(displayRows) { mutableStateOf<String?>(null) }
    fun selectRow(id: String) {
        val start = rangeStart
        if (start != null) {
            val range = editorRangeSelection(displayRows.mapNotNull { it.nodeId }, start, id)
            if (range == null) rangeError = "范围超过256项或起点已变化，未改变选择"
            else { onSetSelection?.invoke(range); rangeStart = null; rangeError = null }
        } else if (selectedNodeIds.size == 1 && selectedNodeId == id) {
            expandedSummaryId = if (expandedSummaryId == id) null else id
        } else onSelectNode(id)
    }
    val visibleSelectedIndex = displayRows.indexOfFirst {
        if (selectedBranch != null && it is EditorTreeDisplayRow.Branch)
            it.ownerId == selectedBranch.ownerId && it.slot == selectedBranch.slot
        else it.nodeId != null && it.nodeId == selectedNodeId
    }
    val nodeIndexes = remember(nodes) { nodes.mapIndexed { index, node -> node.id to index }.toMap() }
    val checkedIds = selectedNodeIds.ifEmpty { setOfNotNull(selectedNodeId) }
    val impact = remember(nodes, checkedIds) { editorSelectionImpact(nodes, checkedIds) }
    val outsideSelection = remember(viewNodes, checkedIds) { checkedIds.size - viewNodes.count { it.id in checkedIds } }
    val focusTarget = remember(nodes, selectedNodeId, selectedBranch) { editorFocusTarget(nodes, selectedBranch?.ownerId ?: selectedNodeId) }
    val hasSelection = editingEnabled && rangeStart == null && nodes.any { it.id in checkedIds }
    val singleSelection = editingEnabled && rangeStart == null && selectedIndex >= 0 && checkedIds.size == 1
    val breadcrumb = remember(nodes, selectedIndex) { editorTreeBreadcrumb(nodes, selectedIndex) }
    val editListState = rememberLazyListState()
    val rowHeightPx = with(LocalDensity.current) { EDITOR_PROGRAM_ROW_MIN_HEIGHT_DP.dp.roundToPx() }
    var fullScreen by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(selectedNodeId, visibleSelectedIndex, displayRows.size) {
        if (visibleSelectedIndex >= 0) {
            val layout = editListState.layoutInfo
            editorMinimumScrollRequest(visibleSelectedIndex, layout.visibleItemsInfo.map { it.index },
                layout.viewportEndOffset - layout.viewportStartOffset,
                layout.visibleItemsInfo.lastOrNull()?.size ?: rowHeightPx)?.let { request ->
                editListState.scrollToItem(request.index, request.offset)
            }
        } else if (viewNodes.any { it.id == selectedNodeId }) {
            editorCollapsedAncestorIds(viewNodes, selectedNodeId).forEach(onToggleExpansion)
        }
    }
    StudioPanelDialog(title = "编辑积木", onDismiss = onDismiss, showFooter = false, compactHeader = true,
        fullScreen = fullScreen, onToggleFullScreen = { fullScreen = !fullScreen }) {
        BoxWithConstraints(if (fullScreen) Modifier.fillMaxSize() else Modifier.fillMaxWidth().heightIn(max = 430.dp)) {
            val short = studioShortViewport(maxHeight.value)
            val actionAreaMaxHeight = maxHeight * if (short) .36f else .4f
            val listContentWidth = editorTreeContentWidth(maxWidth.value, displayRows.maxOfOrNull { it.depth } ?: 0).dp
            Column(Modifier.fillMaxWidth()) {
                EditorFocusBreadcrumb(focusPath, onFocus)
                Column(Modifier.fillMaxWidth().zIndex(1f).padding(horizontal = 8.dp, vertical = 4.dp)) {
                    FlowRow(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(3.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(selectedBranch?.let { "加入${childBlockLabel(it.slot)}" } ?: impact.label, color = EditorBlue, fontSize = 10.sp,
                            modifier = Modifier.padding(vertical = 4.dp), maxLines = 2, overflow = TextOverflow.Ellipsis,
                            style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false)))
                        EditorEditSelectionButton("全选", (onSetSelection != null || onToggleNode != null) && nodes.isNotEmpty()) {
                            rangeStart = null; rangeError = null
                            val additions = editorSelectionToggleIds(viewNodes.map { it.id }, checkedIds, selectAll = true)
                            if (onSetSelection != null) onSetSelection(checkedIds + additions)
                            else additions.forEach { onToggleNode?.invoke(it) }
                        }
                        EditorEditSelectionButton("清空", (onSetSelection != null || onToggleNode != null) && checkedIds.isNotEmpty()) {
                            rangeStart = null; rangeError = null
                            if (onSetSelection != null) onSetSelection(emptySet())
                            else editorSelectionToggleIds(nodes.map { it.id }, checkedIds, selectAll = false).forEach { onToggleNode?.invoke(it) }
                        }
                        EditorEditSelectionButton(if (rangeStart == null) "范围" else "取消范围",
                            editingEnabled && onSetSelection != null && selectedBranch == null && visibleSelectedIndex >= 0) {
                            rangeStart = if (rangeStart == null) selectedNodeId else null
                            rangeError = null
                        }
                        EditorEditSelectionButton("聚焦", focusTarget != null && focusTarget != focusNodeId) { onFocus(focusTarget) }
                        if (short) {
                            EditorEditSelectionButton("展开", nodes.any { it.container && !it.expanded }) { onSetFolded(false) }
                            EditorEditSelectionButton("折叠", nodes.any { it.container && it.expanded }) { onSetFolded(true) }
                        }
                    }
                    Text(when {
                        rangeError != null -> rangeError.orEmpty()
                        rangeStart != null -> "已定起点，点另一行选中连续范围（含端点）"
                        outsideSelection > 0 -> "范围外还选中 $outsideSelection 行，操作仍包含这些行"
                        checkedIds.size > 1 -> "多选包含所选行的子树 · 全选最多256项"
                        breadcrumb.isNotBlank() -> breadcrumb
                        else -> "点行单选，点左侧勾选框多选 · 全选最多256项"
                    }, color = AutoScriptPalette.TextSecondary, fontSize = 9.sp, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp),
                        style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false)))
                    if (!short) Row(Modifier.fillMaxWidth().padding(top = 3.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        EditorEditSelectionButton("展开全部", nodes.any { it.container && !it.expanded }) { onSetFolded(false) }
                        EditorEditSelectionButton("折叠全部", nodes.any { it.container && it.expanded }) { onSetFolded(true) }
                    }
                }
                Box(Modifier.fillMaxWidth().height(hairline()).background(AutoScriptPalette.Divider))
                Box(Modifier.weight(1f).fillMaxWidth().clipToBounds().horizontalScroll(rememberScrollState())) {
                LazyColumn(Modifier.width(listContentWidth).fillMaxHeight(), state = editListState) {
                    if (nodes.isEmpty()) item {
                        Text("当前插件还没有积木", color = AutoScriptPalette.TextSecondary, fontSize = 12.sp,
                            modifier = Modifier.fillMaxWidth().padding(20.dp), textAlign = TextAlign.Center)
                    }
                    itemsIndexed(displayRows, key = { _, row -> row.key }) { _, row ->
                        if (row is EditorTreeDisplayRow.Branch) {
                            EditorBranchHeading(row,
                                selected = selectedBranch?.let { it.ownerId == row.ownerId && it.slot == row.slot } == true,
                                enabled = editingEnabled && rangeStart == null && onSelectBranch != null,
                                onSelect = { onSelectBranch?.invoke(row.ownerId, row.slot) })
                            return@itemsIndexed
                        }
                        val node = (row as EditorTreeDisplayRow.Node).node
                        val hasChildren = editorTreeHasChildren(nodes, nodeIndexes[node.id] ?: -1)
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = EDITOR_PROGRAM_ROW_MIN_HEIGHT_DP.dp)
                                .background(if (node.id in checkedIds) Color(0xFFE7EEFF) else Color.White)
                                .clickable { selectRow(node.id) }
                                .drawBehind {
                                    repeat(node.depth.coerceIn(0, 32)) { level ->
                                        val x = editorTreeGuideOffset(level).dp.toPx()
                                        drawLine(Color(0xFFDDE3EE), GuideOffset(x, 0f), GuideOffset(x, size.height), strokeWidth = 1.dp.toPx())
                                    }
                                }
                                .padding(start = editorTreeRowIndent(node.depth).dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(Modifier.width(EDITOR_TREE_EXPAND_WIDTH_DP.dp).heightIn(min = EDITOR_PROGRAM_ROW_MIN_HEIGHT_DP.dp)
                                .clickable(enabled = hasChildren, onClickLabel = if (node.expanded) "折叠子积木" else "展开子积木") {
                                    onToggleExpansion(node.id)
                                }, contentAlignment = Alignment.Center) {
                                if (hasChildren) Icon(painterResource(R.drawable.editor_tree_expand_15),
                                    contentDescription = if (node.expanded) "折叠子积木" else "展开子积木",
                                    tint = EditorBlue, modifier = Modifier.size(12.dp).rotate(if (node.expanded) 0f else -90f))
                            }
                            // Keep the checkbox inside the compact row; the entire row also selects.
                            Box(Modifier.width(EDITOR_TREE_CHECK_WIDTH_DP.dp).heightIn(min = EDITOR_PROGRAM_ROW_MIN_HEIGHT_DP.dp)
                                .clickable {
                                    if (rangeStart != null) selectRow(node.id)
                                    else onToggleNode?.invoke(node.id) ?: onSelectNode(node.id)
                                }, contentAlignment = Alignment.Center) {
                                Box(
                                    Modifier.size(17.dp).border(1.dp, if (node.id in checkedIds) EditorBlue else Color(0xFF8A909B), RoundedCornerShape(2.dp))
                                        .background(if (node.id in checkedIds) EditorBlue else Color.White, RoundedCornerShape(2.dp)),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    if (node.id in checkedIds) Text("✓", color = Color.White, fontSize = 12.sp, lineHeight = 13.sp)
                                }
                            }
                            EditorProgramLabel(node, selected = node.id in checkedIds, collapsed = hasChildren && !node.expanded,
                                expandedSummary = expandedSummaryId == node.id,
                                descendantCount = descendantCounts[node.id] ?: 0,
                                modifier = Modifier.weight(1f).padding(start = 1.dp,
                                    end = 6.dp, top = EDITOR_PROGRAM_ROW_PADDING_DP.dp, bottom = EDITOR_PROGRAM_ROW_PADDING_DP.dp))
                        }
                    }
                }
                }
                Box(Modifier.fillMaxWidth().height(hairline()).background(AutoScriptPalette.Divider))
                EditorEditToolbar(listOf(
                    EditorEditButton(EditorEditTool.PARAMETERS, singleSelection, onClick = onEditSelected),
                    EditorEditButton(EditorEditTool.BATCH_PARAMETERS, hasSelection && checkedIds.size > 1 && onBatchParameters != null) { onBatchParameters?.invoke() },
                    EditorEditButton(EditorEditTool.ALIAS, singleSelection && onDisplaySelected != null) { onDisplaySelected?.invoke() },
                    EditorEditButton(EditorEditTool.RANDOMIZATION, singleSelection && onRandomizationSelected != null) { onRandomizationSelected?.invoke() },
                    EditorEditButton(EditorEditTool.COPY, hasSelection) { onCommand(EditorProgramCommand.COPY) },
                    EditorEditButton(EditorEditTool.PASTE, canPaste && editingEnabled) { onCommand(EditorProgramCommand.PASTE) },
                    EditorEditButton(EditorEditTool.UNDO, canUndo && editingEnabled) { onCommand(EditorProgramCommand.UNDO) },
                    EditorEditButton(EditorEditTool.REDO, canRedo && editingEnabled) { onCommand(EditorProgramCommand.REDO) },
                    EditorEditButton(EditorEditTool.CUT, hasSelection) { onCommand(EditorProgramCommand.CUT) },
                    EditorEditButton(EditorEditTool.DELETE, hasSelection, onClick = onDeleteSelected),
                    EditorEditButton(EditorEditTool.ANNOTATE, singleSelection && onAnnotateSelected != null) { onAnnotateSelected?.invoke() },
                    EditorEditButton(EditorEditTool.TOGGLE_DISABLED, hasSelection,
                        title = if (disabledSelection) "恢复执行" else "注释执行") { onCommand(EditorProgramCommand.TOGGLE_DISABLED) },
                    EditorEditButton(EditorEditTool.REUSE, singleSelection && onReuseSelected != null) { onReuseSelected?.invoke() },
                    EditorEditButton(EditorEditTool.MOVE_UP, hasSelection) { onCommand(EditorProgramCommand.MOVE_UP) },
                    EditorEditButton(EditorEditTool.MOVE_DOWN, hasSelection) { onCommand(EditorProgramCommand.MOVE_DOWN) },
                    EditorEditButton(EditorEditTool.OUTDENT, hasSelection) { onCommand(EditorProgramCommand.OUTDENT) },
                    EditorEditButton(EditorEditTool.INDENT, hasSelection) { onCommand(EditorProgramCommand.INDENT) },
                    EditorEditButton(EditorEditTool.NODE_SOURCE, singleSelection && onEditSelectedSource != null) { onEditSelectedSource?.invoke() },
                    EditorEditButton(EditorEditTool.PLUGIN_SOURCE, editingEnabled && onEditSource != null) { onEditSource?.invoke() },
                ), Modifier.heightIn(max = actionAreaMaxHeight))
            }
        }
    }
}

/** 选中节点的祖先链，如 `限次循环(3次)>循环体>点击`；按 depth 逆向回溯得到。 */
internal fun editorTreeBreadcrumb(nodes: List<EditorTreeNode>, selectedIndex: Int): String {
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

internal fun editorCollapsedAncestorIds(nodes: List<EditorTreeNode>, selectedId: String?): List<String> {
    val index = nodes.indexOfFirst { it.id == selectedId }
    if (index < 0) return emptyList()
    var depth = nodes[index].depth
    val ancestors = mutableListOf<String>()
    for (position in index - 1 downTo 0) {
        val node = nodes[position]
        if (node.depth < depth) {
            if (!node.expanded && node.container) ancestors += node.id
            depth = node.depth
        }
        if (depth == 0) break
    }
    return ancestors.reversed()
}

internal fun editorVisibleTreeNodes(nodes: List<EditorTreeNode>): List<EditorTreeNode> {
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
private fun EditorBranchHeading(branch: EditorTreeDisplayRow.Branch, selected: Boolean,
    enabled: Boolean, onSelect: () -> Unit) {
    val color = if (branch.disabled) Color(0xFF959DAA)
        else if (branch.slot == "success" || branch.slot == "then") Color(0xFF377B69)
        else if (branch.slot.startsWith("elseIf")) Color(0xFF6975AF) else Color(0xFF9D6554)
    Row(Modifier.fillMaxWidth().heightIn(min = 24.dp)
        .semantics {
            contentDescription = childBlockLabel(branch.slot)
            this.selected = selected
            stateDescription = if (selected) "已选中加入位置" else "可选择加入位置"
        }
        .background(if (selected) Color(0xFFE7EEFF) else color.copy(alpha = .055f))
        .clickable(enabled = enabled, onClickLabel = "选择${childBlockLabel(branch.slot)}作为加入位置", onClick = onSelect)
        .padding(start = (editorTreeRowIndent(branch.depth) + EDITOR_TREE_EXPAND_WIDTH_DP).dp,
            end = 5.dp, top = 3.dp, bottom = 3.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(compactProgramBranchLabel(childBlockLabel(branch.slot)) ?: childBlockLabel(branch.slot), color = color, fontSize = 11.sp,
            style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false)), fontWeight = FontWeight.Medium)
        if (branch.empty) Text(" · 空", color = AutoScriptPalette.TextSecondary, fontSize = 10.sp)
        if (selected) Text(" · 已选", color = EditorBlue, fontSize = 10.sp)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun EditorProgramTreeRow(
    node: EditorTreeNode,
    hasChildren: Boolean,
    selected: Boolean,
    current: Boolean,
    executing: Boolean,
    recent: Boolean,
    onSelect: () -> Unit,
    onToggle: () -> Unit,
    onLongClick: () -> Unit,
    expandedSummary: Boolean = false,
    descendantCount: Int = 0,
) {
    val emphasis = editorExecutionEmphasis(current, executing, recent)
    val entryFlash = remember(node.id) { Animatable(0f) }
    LaunchedEffect(emphasis) {
        entryFlash.snapTo(0f)
        if (emphasis == EditorExecutionEmphasis.EXECUTING) {
            entryFlash.snapTo(1f)
            entryFlash.animateTo(0f, tween(220))
        }
    }
    Row(
        Modifier.fillMaxWidth().heightIn(min = EDITOR_PROGRAM_ROW_MIN_HEIGHT_DP.dp)
            .semantics { if (current) stateDescription = emphasis.label else if (selected) stateDescription = "已选中" }
            .background(if (current) emphasis.background else if (selected) Color(0xFFEFF3FF) else Color.White)
            .drawBehind {
                if (current && entryFlash.value > 0f) drawRect(Brush.horizontalGradient(listOf(
                    emphasis.accent.copy(alpha = .17f * entryFlash.value), Color.Transparent)))
                if (selected || current) drawRect(if (current) emphasis.accent else EditorBlue,
                    size = androidx.compose.ui.geometry.Size(2.dp.toPx(), size.height))
                repeat(node.depth.coerceIn(0, 32)) { level ->
                    val x = editorTreeGuideOffset(level).dp.toPx()
                    drawLine(Color(0xFFE4E8F1), GuideOffset(x, 0f), GuideOffset(x, size.height), strokeWidth = 1.dp.toPx())
                }
            }
            .combinedClickable(onClick = onSelect, onLongClick = onLongClick, onLongClickLabel = "积木操作")
            .padding(start = editorTreeRowIndent(node.depth).dp, end = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.width(EDITOR_TREE_EXPAND_WIDTH_DP.dp).heightIn(min = EDITOR_PROGRAM_ROW_MIN_HEIGHT_DP.dp)
                .drawBehind {
                    // Share the existing gutter; execution must not reflow the label.
                    if (current) {
                        val marker = Path().apply {
                            moveTo(0f, center.y - 4.dp.toPx())
                            lineTo(4.dp.toPx(), center.y)
                            lineTo(0f, center.y + 4.dp.toPx())
                            close()
                        }
                        drawPath(marker, emphasis.accent)
                    }
                }
                .clickable(enabled = hasChildren, onClick = onToggle),
            contentAlignment = Alignment.Center,
        ) {
            if (hasChildren) {
                Icon(
                    painter = painterResource(R.drawable.editor_tree_expand_15),
                    contentDescription = if (node.expanded) "折叠节点" else "展开节点",
                    tint = EditorBlue,
                    modifier = Modifier.size(12.dp).rotate(if (node.expanded) 0f else -90f),
                )
            }
        }
        EditorProgramLabel(node, selected, collapsed = hasChildren && !node.expanded,
            expandedSummary = expandedSummary, descendantCount = descendantCount,
            modifier = Modifier.weight(1f).padding(start = 1.dp, end = 4.dp,
                top = EDITOR_PROGRAM_ROW_PADDING_DP.dp, bottom = EDITOR_PROGRAM_ROW_PADDING_DP.dp))
    }
}

@Composable
internal fun EditorProgramLabel(
    node: EditorTreeNode,
    selected: Boolean,
    collapsed: Boolean = false,
    modifier: Modifier = Modifier,
    expandedSummary: Boolean = false,
    descendantCount: Int = 0,
) {
    val parts = if (expandedSummary) node.fullDisplayRow ?: node.displayRow ?: compactProgramRowText(node.label)
        else node.displayRow ?: compactProgramRowText(node.label)
    val muted = if (parts.disabled) Color(0xFF959DAA) else Color(0xFF758094)
    val text = buildAnnotatedString {
        if (parts.disabled) withStyle(SpanStyle(color = muted)) { append(if (parts.inheritedDisabled) "随父级禁用  " else "已注释  ") }
        compactProgramBranchLabel(parts.branch, parts.branchHasHeading)?.let { branch ->
            withStyle(SpanStyle(color = Color(0xFF6975AF))) { append("$branch ") }
        }
        withStyle(SpanStyle(color = if (parts.disabled) muted else if (selected) EditorBlue else Color(0xFF283348),
            fontWeight = if (node.container || selected) FontWeight.SemiBold else FontWeight.Medium)) { append(parts.title) }
        if (collapsed) withStyle(SpanStyle(color = Color(0xFF6975AF))) { append("  · ${descendantCount}项已折叠") }
        if (parts.details.isNotBlank()) withStyle(SpanStyle(color = muted)) { append(" · ${parts.details}") }
    }
    Text(text, modifier.then(if (expandedSummary) Modifier.heightIn(max = 144.dp).verticalScroll(rememberScrollState()) else Modifier)
        .semantics { contentDescription = node.label; if (selected) stateDescription = if (expandedSummary) "完整摘要，再点收起" else "再点展开摘要" },
        fontSize = 12.sp, lineHeight = 16.sp, maxLines = if (expandedSummary) Int.MAX_VALUE else 1, overflow = TextOverflow.Ellipsis,
        style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false)))
}

@Composable
internal fun EditorProgramSearchDialog(
    nodes: List<EditorTreeNode>, onDismiss: () -> Unit, onSelect: (String) -> Unit,
    onReplace: ((String, String, Boolean) -> String?)?, programSource: String?,
    onOpenProjectSearch: (() -> Unit)? = null,
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
    var fullScreen by remember { mutableStateOf(false) }
    StudioPanelDialog("查找替换", onDismiss, fullScreen = fullScreen, onToggleFullScreen = { fullScreen = !fullScreen }) {
        LazyColumn(Modifier.fillMaxWidth().then(if (fullScreen) Modifier.fillMaxHeight() else Modifier.heightIn(max = 430.dp)),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (onReplace != null) item(key = "field:mode") {
                InspectorFilterRow(listOf("find" to "查找", "replace" to "替换"), mode) { mode = it }
            }
            if (onOpenProjectSearch != null) item(key = "field:project") {
                TextButton(onClick = onOpenProjectSearch, modifier = Modifier.fillMaxWidth()) { Text("搜整个项目（包含所有插件草稿）") }
            }
            item(key = "field:query") {
                InspectorSearchField(query, { query = it.take(80); replaceError = null }, "输入积木名称、类型或参数")
            }
            if (mode == "replace" && onReplace != null) {
                item(key = "field:replacement") {
                    InspectorSearchField(replacement, { replacement = it.take(2048); replaceError = null }, "替换为（可留空删除文字）")
                }
                item(key = "field:scope") {
                    InspectorFilterRow(listOf("all" to "当前插件", "selection" to "选中积木及子树"),
                        if (selectionOnly) "selection" else "all") { selectionOnly = it == "selection" }
                }
                item(key = "field:hint") {
                    Text("替换区分大小写，仅修改 message / value 字符串；变量名、表达式、路径和标记不变。", fontSize = 10.sp, color = AutoScriptPalette.TextSecondary)
                }
                item(key = "field:confirm") {
                    TextButton(enabled = query.isNotEmpty() && query != replacement, onClick = {
                        confirmSource = programSource; confirmReplace = true
                    }, modifier = Modifier.fillMaxWidth()) { Text("确认范围并替换") }
                }
            }
            replaceError?.let { message -> item(key = "field:error") { Text(message, fontSize = 11.sp, color = AutoScriptPalette.Danger) } }
            item(key = "field:summary") {
                Text(if (query.isEmpty()) "输入关键词后显示结果" else "匹配 ${allMatches.size} 项 · 显示前 ${allMatches.size.coerceAtMost(100)} 项 · 点击定位", fontSize = 10.sp, color = AutoScriptPalette.TextSecondary)
            }
            itemsIndexed(allMatches.take(100), key = { _, node -> "node:${node.id}" }) { _, node ->
                Row(Modifier.fillMaxWidth().heightIn(min = 38.dp).clickable { onSelect(node.id) }.padding(6.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(node.label, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Text("定位 ›", color = EditorBlue, fontSize = 10.sp)
                }
                Box(Modifier.fillMaxWidth().height(hairline()).background(AutoScriptPalette.Divider))
            }
            if (query.isNotEmpty() && allMatches.isEmpty()) item { Text("没有匹配的积木。", fontSize = 12.sp) }
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
    insideLabel: String = "选择行里面",
    previewNodes: List<EditorTreeNode>? = null,
    previewAnchorId: String? = null,
    previewChildSlot: String? = null,
) {
    var selected by rememberSaveable { mutableStateOf(initialPosition) }
    val options = listOf(
        EditorInsertPosition.LIST_BOTTOM to "列表最底部",
        EditorInsertPosition.ABOVE to "选择行上方",
        EditorInsertPosition.BELOW to "选择行下方",
        EditorInsertPosition.INSIDE to insideLabel,
        EditorInsertPosition.REPLACE to "替换选择行",
    ).filter { allowReplace || it.first != EditorInsertPosition.REPLACE }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BoxWithConstraints(Modifier.fillMaxSize().padding(8.dp), contentAlignment = Alignment.Center) {
        Surface(
            modifier = Modifier.widthIn(max = 340.dp).fillMaxWidth(.94f).heightIn(max = maxHeight),
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
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                previewNodes?.let { EditorInsertionPreview(it, previewAnchorId, selected, previewChildSlot) }
                options.forEach { (position, label) ->
                    val enabled = when (position) {
                        EditorInsertPosition.LIST_BOTTOM -> true
                        EditorInsertPosition.INSIDE -> hasSelection && canInsertInside
                        else -> hasSelection
                    }
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 34.dp).clickable(enabled = enabled) { selected = position }
                            .padding(horizontal = 15.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(label, color = if (enabled) Color(0xFF20242C) else Color(0xFFAAB1BD), fontSize = 14.sp, modifier = Modifier.weight(1f))
                        Text(
                            if (selected == position) "◉" else "○",
                            color = if (enabled) EditorBlue else Color(0xFFAAB1BD),
                            fontSize = 22.sp,
                        )
                    }
                }
                }
                Box(Modifier.fillMaxWidth().height(hairline()).background(AutoScriptPalette.Divider))
                Row(Modifier.fillMaxWidth().height(42.dp)) {
                    Box(Modifier.weight(1f).fillMaxHeight().clickable(onClick = onDismiss), contentAlignment = Alignment.Center) {
                        Text("取消", color = Color(0xFF20242C), fontSize = 14.sp,
                            style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false)))
                    }
                    Box(Modifier.width(hairline()).fillMaxHeight().background(AutoScriptPalette.Divider))
                    Box(Modifier.weight(1f).fillMaxHeight().clickable(enabled = selected == EditorInsertPosition.LIST_BOTTOM ||
                        (hasSelection && (selected != EditorInsertPosition.INSIDE || canInsertInside))) { onConfirm(selected) }, contentAlignment = Alignment.Center) {
                        Text("确定", color = EditorBlue, fontSize = 14.sp,
                            style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false)))
                    }
                }
            }
        }
        }
    }
}

@Composable
internal fun EditorDialogTextButton(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Text(
        label,
        color = Color(0xFF3F51B5),
        fontSize = 14.sp,
        modifier = modifier.clickable(onClick = onClick).padding(horizontal = 15.dp, vertical = 8.dp),
    )
}

@Composable
internal fun EditorDialogTextButton(label: String, onClick: () -> Unit) = EditorDialogTextButton(label, Modifier, onClick)

@Composable
internal fun EditorDockAction(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Text(
        label,
        color = if (enabled) Color(0xFF202839) else Color(0xFF9DA6B4),
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 27.dp)
            .padding(vertical = 1.dp)
            .border(1.dp, EditorBorder, RoundedCornerShape(3.dp))
            .background(Color.White, RoundedCornerShape(3.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .wrapContentSize(Alignment.Center),
    )
}

@Composable
internal fun EditorDockHeaderButton(
    description: String,
    @DrawableRes drawable: Int,
    active: Boolean = false,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .width(EDITOR_HEADER_BUTTON_WIDTH_DP.dp)
            .fillMaxHeight()
            .clickable(onClickLabel = description, onClick = onClick)
            .padding(vertical = 4.dp)
            .background(if (active) Color(0xFFE5EDFF) else Color.Transparent, RoundedCornerShape(4.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(drawable),
            contentDescription = description,
            tint = if (danger) AutoScriptPalette.Danger else if (active) EditorBlue else Color(0xFF5F6C84),
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
internal fun EditorBottomButton(action: EditorToolbarAction, enabled: Boolean, showLabel: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val tint = when (action) {
        EditorToolbarAction.RUN, EditorToolbarAction.RESUME -> if (enabled) Color(0xFF38B36D) else EditorDisabledBlue
        EditorToolbarAction.STOP -> if (enabled) Color(0xFFE55353) else EditorDisabledBlue
        else -> if (enabled) EditorBlue else EditorDisabledBlue
    }
    val drawable = when (action) {
        EditorToolbarAction.RUN, EditorToolbarAction.RESUME -> R.drawable.editor_run_24
        EditorToolbarAction.PAUSE -> R.drawable.editor_pause_24
        EditorToolbarAction.STOP -> R.drawable.editor_stop_24
        EditorToolbarAction.STEP -> R.drawable.editor_step_run_24
        EditorToolbarAction.EDIT -> R.drawable.editor_edit_24
        EditorToolbarAction.LOG -> R.drawable.editor_log_24
        EditorToolbarAction.MORE -> R.drawable.editor_more_24
    }
    EditorToolButton(action.label, drawable, enabled, tint, modifier.fillMaxHeight(), showLabel, onClick = onClick)
}

@Composable
private fun EditorToolButton(
    label: String,
    @DrawableRes drawable: Int,
    enabled: Boolean,
    tint: Color,
    modifier: Modifier,
    showLabel: Boolean = true,
    onClick: () -> Unit,
) {
    Column(
        modifier.clickable(enabled = enabled, role = androidx.compose.ui.semantics.Role.Button, onClick = onClick)
            .padding(vertical = 2.dp),
        verticalArrangement = Arrangement.spacedBy(1.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            painter = painterResource(drawable),
            contentDescription = if (showLabel) null else label,
            tint = tint,
            modifier = Modifier.size(16.dp),
        )
        if (showLabel) Text(label, color = tint, fontSize = 9.sp, lineHeight = 11.sp,
            textAlign = TextAlign.Center, maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false)))
    }
}

@Composable
internal fun ResizeHandle(modifier: Modifier, onResize: (Offset) -> Unit) {
    val currentOnResize by rememberUpdatedState(onResize)
    Icon(
        painter = painterResource(R.drawable.editor_window_resize_20),
        contentDescription = "拖动调整窗口大小",
        tint = Color(0xFF5F6C84),
        modifier = modifier.pointerInput(Unit) {
            detectDragGestures { change, delta ->
                change.consume()
                currentOnResize(delta)
            }
        },
    )
}

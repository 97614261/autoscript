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
import androidx.compose.material3.Surface
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
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
    modifier: Modifier = Modifier,
) {
    var expanded by rememberSaveable(projectName) { mutableStateOf(false) }
    var functionLibrary by rememberSaveable(projectName) { mutableStateOf(false) }
    var toolDialog by rememberSaveable(projectName) { mutableStateOf<LegacyToolDialog?>(null) }
    var sourceManagerVisible by rememberSaveable(projectName) { mutableStateOf(false) }
    var sourceFileName by rememberSaveable(projectName) { mutableStateOf<String?>(null) }
    var sourceEntries by rememberSaveable(projectName) { mutableStateOf<List<String>>(emptyList()) }
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
    val displayedSourceName = sourceName ?: sourceFileName

    LaunchedEffect(programNodes) {
        if (programNodes != null) {
            val activeIds = programNodes.asSequence().map(LegacyDockProgramNode::nodeId).toHashSet()
            collapsedProgramNodeIds = collapsedProgramNodeIds.filter(activeIds::contains)
        }
    }

    LaunchedEffect(sourceName) {
        sourceName?.let { activeSource ->
            if (activeSource !in sourceEntries) sourceEntries = sourceEntries + activeSource
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
        val minPanelHeightPx = with(density) { 324.dp.toPx() }
        val maxWidthPx = constraints.maxWidth.toFloat()
        val maxHeightPx = constraints.maxHeight.toFloat()
        val ballPeekPx = with(density) { 10.dp.toPx() }
        var ballX by rememberSaveable(projectName) { mutableFloatStateOf(Float.NaN) }
        var ballY by rememberSaveable(projectName) { mutableFloatStateOf(Float.NaN) }
        var panelX by rememberSaveable(projectName) { mutableFloatStateOf(with(density) { 4.dp.toPx() }) }
        var panelY by rememberSaveable(projectName) { mutableFloatStateOf(Float.NaN) }
        var panelWidthPx by rememberSaveable(projectName) { mutableFloatStateOf(with(density) { 232.dp.toPx() }) }
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
            Surface(
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
                                val center = resolvedBallX + ballSizePx / 2f
                                ballX = if (center < maxWidthPx / 2f) -ballPeekPx else maxWidthPx - ballSizePx + ballPeekPx
                            },
                        ) { change, dragAmount ->
                            change.consume()
                            val currentX = if (ballX.isNaN()) resolvedBallX else ballX
                            val currentY = if (ballY.isNaN()) resolvedBallY else ballY
                            ballX = (currentX + dragAmount.x).coerceIn(-ballPeekPx, maxBallX)
                            ballY = (currentY + dragAmount.y).coerceIn(0f, maxBallY)
                        }
                    }
                    .clickable { editorMenuVisible = false; expanded = true },
                color = Color(0xE6111827),
                shape = RoundedCornerShape(18.dp),
                border = androidx.compose.foundation.BorderStroke(2.dp, Color.White.copy(alpha = .65f)),
                shadowElevation = 5.dp,
            ) {
                EditorBallIcon(Modifier.fillMaxSize())
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
                onCreateSource = { if (editingEnabled) sourceManagerVisible = true },
                onRun = onRun,
                onStep = onStep,
                onFunctions = {
                    if (editingEnabled) {
                        if (!externallyManaged && displayedSourceName == null) sourceManagerVisible = true else functionLibrary = true
                    }
                },
                onTool = { entry ->
                    if (editingEnabled) {
                        if (!externallyManaged && displayedSourceName == null) sourceManagerVisible = true else toolDialog = entry
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
                        toolDialog = LegacyToolDialog.PLUGINS
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
                    onRecord = { moreMenuVisible = false; toolDialog = LegacyToolDialog.RECORDING },
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
        LegacyFunctionLibrary(
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
    if (sourceManagerVisible) {
        LegacySourceManagerDialog(
            entries = sourceEntries,
            currentSource = displayedSourceName,
            onEntriesChanged = { sourceEntries = it },
            onDismiss = { sourceManagerVisible = false },
            onConfirmSource = { path, newlyCreated ->
                if (!externallyManaged) {
                    sourceFileName = path
                    if (newlyCreated) {
                        treeNodes = emptyList()
                        selectedNodeId = null
                        editingNodeId = null
                        nextNodeId = 1L
                        onInsert("-- ${path.substringAfterLast('/')}\n")
                    }
                }
                sourceManagerVisible = false
            },
        )
    }
    toolDialog?.let { dialog ->
        LegacyToolDialogScreen(dialog, projectName, onDismiss = { toolDialog = null; editingNodeId = null }) { snippet ->
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
internal enum class LegacyToolDialog { FILES, TOOLS, IMAGE, JUDGMENT, LOOP, COMMON, FUNCTIONS, RECORDING, PLUGINS, DEBUG, AI, DATA_BACKFILL, VARIABLE_CHECK, RUNTIME_VARIABLES }

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
                                onDrag(delta)
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

@Composable
private fun LegacyConsolePanel(lines: List<String>) {
    Column(Modifier.fillMaxSize().background(Color.White)) {
        Text(
            "CPU --%  |  内存 -- / -- (--%)  |  应用 --",
            color = EditorBlue,
            fontSize = 8.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().height(22.dp).padding(horizontal = 8.dp, vertical = 5.dp),
        )
        if (lines.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "暂无控制台日志\n运行后 Log(...) 输出会显示在这里",
                    color = Color(0xFF8A909B),
                    fontSize = 11.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
                itemsIndexed(lines) { _, line ->
                    Text(line, color = Color(0xFF202839), fontSize = 10.sp, modifier = Modifier.padding(vertical = 2.dp))
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
    val selected = nodes.firstOrNull { it.id == selectedNodeId }
    val rawLine = selected?.let { node ->
        JSONObject()
            .put("nodeId", node.id)
            .put("kind", node.code)
            .put("title", node.label)
            .put("depth", node.depth)
            .toString()
    }.orEmpty()
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.fillMaxWidth(.96f).fillMaxHeight(.90f),
            color = Color.White,
            shape = RoundedCornerShape(2.dp),
            shadowElevation = 10.dp,
        ) {
            Column {
                Text(
                    rawLine.ifBlank { "请选择程序节点" },
                    color = Color(0xFF202839),
                    fontSize = 10.sp,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().height(58.dp).padding(10.dp),
                )
                Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFFE2E5EA)))
                LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                    itemsIndexed(nodes, key = { _, node -> node.id }) { index, node ->
                        Row(
                            Modifier.fillMaxWidth().height(42.dp)
                                .background(if (node.id == selectedNodeId) Color(0xFFE6EEF9) else Color.White)
                                .clickable { onSelectNode(node.id) },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                if (node.id == selectedNodeId) "☑" else "□",
                                color = EditorBlue,
                                fontSize = 17.sp,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                modifier = Modifier.width(35.dp),
                            )
                            Text((index + 1).toString(), color = Color(0xFF8A909B), fontSize = 10.sp, modifier = Modifier.width(25.dp))
                            Text(
                                node.label,
                                color = Color(0xFF202839),
                                fontSize = 13.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f).padding(start = (node.depth * 10).dp, end = 6.dp),
                            )
                        }
                    }
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFFE2E5EA)))
                val actions = listOf(
                    "撤销" to "UNDO", "恢复" to "REDO", "上移" to "MOVE_UP", "下移" to "MOVE_DOWN", "左移" to "OUTDENT", "右移" to "INDENT",
                    "关闭" to "CLOSE", "注释" to "COMMENT", "删除" to "DELETE", "复制" to "COPY", "粘贴" to "PASTE", "剪切" to "CUT",
                )
                actions.chunked(6).forEach { rowActions ->
                    Row(Modifier.fillMaxWidth().height(47.dp)) {
                        rowActions.forEach { (label, action) ->
                            Column(
                                Modifier.weight(1f).fillMaxHeight().clickable(enabled = selected != null || action == "CLOSE") {
                                    when (action) {
                                        "CLOSE" -> onDismiss()
                                        "DELETE" -> onDeleteSelected()
                                        "COMMENT" -> onEditSelected()
                                        else -> runCatching { LegacyDockProgramCommand.valueOf(action) }.getOrNull()?.let(onCommand)
                                    }
                                },
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center,
                            ) {
                                Text(
                                    when (action) {
                                        "UNDO" -> "↶"; "REDO" -> "↷"; "MOVE_UP" -> "↑"; "MOVE_DOWN" -> "↓"
                                        "OUTDENT" -> "←"; "INDENT" -> "→"; "CLOSE" -> "×"; "COMMENT" -> "//"
                                        "DELETE" -> "▰"; "COPY" -> "▣"; "PASTE" -> "▤"; else -> "✂"
                                    },
                                    color = if (selected != null || action == "CLOSE") EditorBlue else EditorDisabledBlue,
                                    fontSize = 19.sp,
                                )
                                Text(label, color = Color(0xFF303746), fontSize = 9.sp)
                            }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().height(40.dp)) {
                    Text(
                        "修改选中节点参数（保留原值回显）",
                        color = EditorBlue,
                        fontSize = 11.sp,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier = Modifier.fillMaxSize().clickable(enabled = selected != null, onClick = onEditSelected).padding(top = 12.dp),
                    )
                }
            }
        }
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
            color = Color(0xFF202839),
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
                Box(Modifier.fillMaxWidth().height(1.dp).background(EditorBorder))
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
                Box(Modifier.fillMaxWidth().height(1.dp).background(EditorBorder))
                Row(Modifier.fillMaxWidth().height(42.dp)) {
                    Text(
                        "取消",
                        color = Color(0xFF20242C),
                        fontSize = 14.sp,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier = Modifier.weight(1f).fillMaxHeight().clickable(onClick = onDismiss).padding(top = 11.dp),
                    )
                    Box(Modifier.width(1.dp).fillMaxHeight().background(EditorBorder))
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
private fun LegacyNewSourceDialog(
    onDismiss: () -> Unit,
    onCreateGroup: (String) -> Unit,
    onCreateSource: (String) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf("") }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.fillMaxWidth(.82f).widthIn(max = 420.dp),
            color = Color.White,
            shape = RoundedCornerShape(2.dp),
            shadowElevation = 8.dp,
        ) {
            Column(Modifier.padding(top = 20.dp)) {
                Text("创建", color = Color(0xFF171B25), fontSize = 18.sp, modifier = Modifier.padding(start = 25.dp))
                BasicTextField(
                    value = name,
                    onValueChange = { if (it.length <= 30) name = it },
                    singleLine = true,
                    textStyle = TextStyle(color = Color(0xFF1C2333), fontSize = 15.sp),
                    modifier = Modifier.padding(start = 30.dp, top = 10.dp, end = 30.dp, bottom = 5.dp)
                        .fillMaxWidth().height(36.dp)
                        .border(1.dp, Color(0xFFC7CBD1), RoundedCornerShape(2.dp))
                        .padding(horizontal = 8.dp, vertical = 7.dp),
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 15.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    LegacyDialogTextButton("取消", onDismiss)
                    Spacer(Modifier.weight(1f))
                    LegacyDialogTextButton("分组") { onCreateGroup(name) }
                    LegacyDialogTextButton("源文件", Modifier.padding(end = 15.dp)) { onCreateSource(name) }
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
    Icon(
        painter = painterResource(R.drawable.editor_resize_24),
        contentDescription = "调整窗口大小",
        tint = Color.Unspecified,
        modifier = modifier.pointerInput(Unit) {
            detectDragGestures { change, delta ->
                change.consume()
                onResize(delta)
            }
        },
    )
}

@Composable
private fun LegacyFunctionLibrary(
    onDismiss: () -> Unit,
    onInsert: (String) -> Unit,
) {
    var category by rememberSaveable { mutableStateOf(LegacyFunctionCategory.KEYS) }
    var groupIndex by rememberSaveable { mutableStateOf(1) }
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    val commands = remember(category, groupIndex, query) {
        val base = if (query.isBlank()) {
            legacyFunctionCommands(category, groupIndex)
        } else {
            LegacyFunctionCategory.entries.flatMap { candidate ->
                candidate.groups.indices.flatMap { index -> legacyFunctionCommands(candidate, index) }
            }.distinctBy(LegacyCommand::label)
        }
        base.filter { query.isBlank() || it.label.contains(query, ignoreCase = true) || it.snippet.contains(query, ignoreCase = true) }
    }
    var commandIndex by rememberSaveable(category, groupIndex, query) { mutableStateOf(0) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.fillMaxWidth(.96f).fillMaxHeight(.64f),
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
                Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFFE1E4E9)))
                Row(Modifier.weight(1f).padding(horizontal = 1.dp)) {
                    Column(Modifier.weight(1.2f).fillMaxHeight().background(Color(0xFFF8F9FB)).verticalScroll(rememberScrollState())) {
                        LegacyFunctionCategory.entries.forEach { candidate ->
                            LegacyLibraryTab(candidate.label, category == candidate) {
                                category = candidate
                                groupIndex = if (candidate == LegacyFunctionCategory.KEYS) 1 else 0
                            }
                        }
                    }
                    Box(Modifier.width(1.dp).fillMaxHeight().background(Color(0xFFE1E4E9)))
                    Column(Modifier.weight(1.2f).fillMaxHeight().background(Color(0xFFF8F9FB)).verticalScroll(rememberScrollState())) {
                        category.groups.forEachIndexed { index, label ->
                            LegacyLibraryTab(label, groupIndex == index) { groupIndex = index }
                        }
                    }
                    Box(Modifier.width(1.dp).fillMaxHeight().background(Color(0xFFE1E4E9)))
                    Column(Modifier.weight(.9f).fillMaxHeight().background(Color(0xFFF5F5F5)).verticalScroll(rememberScrollState())) {
                        if (commands.isEmpty()) {
                            Text(if (query.isBlank()) "暂无可用内容" else "没有匹配结果", color = Color(0xFF8A939E), fontSize = 11.sp, modifier = Modifier.fillMaxWidth().padding(12.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                        } else commands.forEachIndexed { index, command ->
                            LegacyLibraryTab(command.label, commandIndex == index) {
                                commandIndex = index
                                onInsert(command.snippet)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Dialog routes reconstructed from FloatingCreateScript and myCreateScriptClickEvent.
 * They are in-app previews until Runtime exposes recording and plugin IPC. */
@Composable
private fun LegacyToolDialogScreen(dialog: LegacyToolDialog, projectName: String, onDismiss: () -> Unit, onInsert: (String) -> Unit) {
    if (dialog in setOf(
            LegacyToolDialog.TOOLS,
            LegacyToolDialog.FILES,
            LegacyToolDialog.IMAGE,
            LegacyToolDialog.JUDGMENT,
            LegacyToolDialog.LOOP,
            LegacyToolDialog.COMMON,
            LegacyToolDialog.DEBUG,
            LegacyToolDialog.AI,
            LegacyToolDialog.DATA_BACKFILL,
            LegacyToolDialog.VARIABLE_CHECK,
            LegacyToolDialog.RUNTIME_VARIABLES,
        )
    ) {
        EditorEntryDialog(dialog, projectName, onDismiss, onInsert)
        return
    }
    Dialog(onDismissRequest = onDismiss) {
        Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(5.dp), color = Color(0xFFF6F6F6)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when (dialog) {
                    LegacyToolDialog.TOOLS,
                    LegacyToolDialog.FILES,
                    LegacyToolDialog.IMAGE,
                    LegacyToolDialog.JUDGMENT,
                    LegacyToolDialog.LOOP,
                    LegacyToolDialog.COMMON,
                    LegacyToolDialog.FUNCTIONS,
                    -> Unit
                    LegacyToolDialog.RECORDING -> {
                        Text("录制模式", color = LegacyDockGreenDark, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                        Text("原版会收起编辑面板并显示悬浮录制控制。当前先以应用内控制预览呈现，Runtime 事件流接入后会写入真实动作。", fontSize = 13.sp, color = Color(0xFF596270))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            LegacyDialogControl("● REC", Color(0xFFD84949))
                            LegacyDialogControl("暂停", LegacyDockGreen)
                            LegacyDialogControl("结束", Color(0xFF555B65))
                        }
                        Text("录制内容：点击、长按、滑动、按键、等待", fontSize = 12.sp, color = Color(0xFF6A7480))
                    }
                    LegacyToolDialog.PLUGINS -> {
                        Text("插件管理", color = LegacyDockGreenDark, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                        Text("选择项目插件", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        listOf("新建插件", "导入插件包", "插件分组管理").forEach { label ->
                            Row(Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(3.dp)).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("▦", color = LegacyDockGreen, modifier = Modifier.padding(end = 10.dp))
                                Text(label, modifier = Modifier.weight(1f))
                                Text("›", color = Color(0xFF8A939E), fontSize = 22.sp)
                            }
                        }
                        Text("当前项目暂无已安装插件。插件运行沙箱和签名校验将在 Runtime 插件模型接入后启用。", fontSize = 12.sp, color = Color(0xFF6A7480))
                    }
                    LegacyToolDialog.DEBUG,
                    LegacyToolDialog.AI,
                    LegacyToolDialog.DATA_BACKFILL,
                    LegacyToolDialog.VARIABLE_CHECK,
                    LegacyToolDialog.RUNTIME_VARIABLES,
                    -> Unit
                }
                Text("关闭", modifier = Modifier.fillMaxWidth().border(1.dp, LegacyFunctionBlue, RoundedCornerShape(3.dp)).clickable(onClick = onDismiss).padding(10.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center, color = LegacyFunctionBlue)
            }
        }
    }
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

private enum class LegacyFunctionCategory(val label: String, val groups: List<String>) {
    VARIABLES("变量管理", listOf("新建变量", "读取变量", "设置变量")),
    UI("界面函数", listOf("界面操作", "控件操作", "窗口操作")),
    SCRIPT("脚本函数", listOf("脚本调用", "脚本控制", "参数传递")),
    KEYS("按键函数", listOf("触摸操作", "设备按键", "文本输入", "无障碍节点")),
    IMAGE("图像函数", listOf("找图识别", "找色识别", "字库识字", "ONNX OCR")),
    FILE("文件函数", listOf("文件读取", "文件写入", "目录操作")),
    NETWORK("网络函数", listOf("HTTP 请求", "下载上传", "Socket")),
    SYSTEM("系统函数", listOf("应用管理", "设备信息", "系统设置")),
    DATA("数据函数", listOf("数组操作", "字符操作", "JSON 操作")),
    MATH("数学函数", listOf("基础运算", "随机数", "数学计算")),
    OTHER_LANGUAGE("其他语言", listOf("JavaScript", "Shell 命令", "Java 调用")),
    PLUGIN("插件函数", listOf("项目插件", "插件调用", "插件管理")),
}

private data class LegacyCommand(val label: String, val snippet: String)

private fun legacyFunctionCommands(category: LegacyFunctionCategory, groupIndex: Int): List<LegacyCommand> = when (category) {
    LegacyFunctionCategory.KEYS -> when (groupIndex) {
        0 -> listOf(
            LegacyCommand("屏幕单击", "Input.tap(x, y)\n"),
            LegacyCommand("屏幕长按", "Input.longPress(x, y, 800)\n"),
            LegacyCommand("屏幕滑动", "Input.swipe(x1, y1, x2, y2, 300)\n"),
        )
        1 -> listOf(
            LegacyCommand("设备单击", "Input.keyClick(keyCode)\n"),
            LegacyCommand("设备按下", "Input.keyDown(keyCode)\n"),
            LegacyCommand("设备弹起", "Input.keyUp(keyCode)\n"),
        )
        2 -> listOf(LegacyCommand("输入文本", "Input.text(\"text\")\n"), LegacyCommand("清空文本", "Input.clearText()\n"))
        else -> listOf(LegacyCommand("查找节点", "Accessibility.findNode(\"text\")\n"), LegacyCommand("点击节点", "Accessibility.clickNode(node)\n"))
    }
    LegacyFunctionCategory.VARIABLES -> listOf(LegacyCommand("设置变量", "local value = \"\"\n"), LegacyCommand("读取变量", "-- value\n"))
    LegacyFunctionCategory.UI -> listOf(LegacyCommand("显示界面", "Ui.show(\"main\")\n"), LegacyCommand("设置文本", "Ui.setText(id, text)\n"), LegacyCommand("关闭界面", "Ui.close(\"main\")\n"))
    LegacyFunctionCategory.SCRIPT -> listOf(LegacyCommand("调用脚本", "Script.call(\"scriptName\")\n"), LegacyCommand("停止脚本", "Script.stop()\n"), LegacyCommand("等待", "Task.sleep(1000)\n"))
    LegacyFunctionCategory.IMAGE -> when (groupIndex) {
        0 -> listOf(LegacyCommand("区域找图", "Vision.findImage(\"image.png\")\n"), LegacyCommand("特征找图", "Vision.findFeature(\"image.png\")\n"))
        1 -> listOf(LegacyCommand("多点找色", "Vision.findColor(\"#FFFFFF\")\n"), LegacyCommand("多点比色", "Vision.compareColors(points)\n"))
        2 -> listOf(LegacyCommand("字库识字", "Vision.findText(\"default.txt\")\n"))
        else -> listOf(LegacyCommand("ONNX OCR", "Vision.findTextOnnx(\"RapidOCR.onnx\", \"中英文\", 0.8)\n"))
    }
    LegacyFunctionCategory.FILE -> listOf(LegacyCommand("读取文本", "File.readText(path)\n"), LegacyCommand("写入文本", "File.writeText(path, text)\n"), LegacyCommand("创建目录", "File.makeDir(path)\n"))
    LegacyFunctionCategory.NETWORK -> listOf(LegacyCommand("HTTP GET", "Net.get(url)\n"), LegacyCommand("下载文件", "Net.download(url, path)\n"), LegacyCommand("上传文件", "Net.upload(url, path)\n"))
    LegacyFunctionCategory.SYSTEM -> listOf(LegacyCommand("启动应用", "System.launch(packageName)\n"), LegacyCommand("设备信息", "System.deviceInfo()\n"), LegacyCommand("设置亮度", "System.setBrightness(value)\n"))
    LegacyFunctionCategory.DATA -> listOf(LegacyCommand("创建数组", "local values = {}\n"), LegacyCommand("JSON 解析", "Json.decode(text)\n"), LegacyCommand("字符替换", "String.replace(text, old, new)\n"))
    LegacyFunctionCategory.MATH -> listOf(LegacyCommand("随机数", "Math.random(min, max)\n"), LegacyCommand("取绝对值", "Math.abs(value)\n"), LegacyCommand("四舍五入", "Math.round(value)\n"))
    LegacyFunctionCategory.OTHER_LANGUAGE -> listOf(LegacyCommand("执行 JavaScript", "Language.javascript(code)\n"), LegacyCommand("执行 Shell", "Language.shell(command)\n"))
    LegacyFunctionCategory.PLUGIN -> listOf(LegacyCommand("调用插件", "Plugin.call(\"pluginName\")\n"), LegacyCommand("插件返回值", "Plugin.result(\"pluginName\")\n"))
}

@Composable
private fun LegacyLibraryTab(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(Modifier.fillMaxWidth().height(30.dp).clickable(onClick = onClick)) {
        Box(
            Modifier.align(Alignment.CenterStart).width(1.5.dp).fillMaxHeight()
                .background(if (selected) Color(0xFF4C8DFF) else Color.Transparent),
        )
        Text(
            label,
            color = if (selected) Color(0xFF2864F0) else Color(0xFF202839),
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.fillMaxSize().wrapContentSize(Alignment.Center),
        )
        Box(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth(.82f).height(1.5.dp)
                .background(if (selected) Color(0xFF2864F0) else Color.Transparent),
        )
    }
}

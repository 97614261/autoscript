package com.autoscript.studio

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.autoscript.project.store.ProjectSnapshot
import com.autoscript.project.store.ProjectSourceMode
import com.autoscript.project.store.ProjectStore
import com.autoscript.project.store.ProjectSummary
import com.autoscript.core.model.RuntimeConnectionPhase
import com.autoscript.core.model.RuntimeConnectionState
import com.autoscript.core.model.RuntimeEngineState
import com.autoscript.runtime.client.RuntimeClient
import com.autoscript.runtime.client.ScriptValidationResult
import com.autoscript.runtime.client.VisualCompileResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun ProjectPage(
    store: ProjectStore,
    runtimeClient: RuntimeClient,
    runtimeState: RuntimeConnectionState,
    active: Boolean,
    onFullScreenChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    footer: @Composable () -> Unit,
) {
    var projects by remember { mutableStateOf<List<ProjectSummary>>(emptyList()) }
    var workspacePanel by remember { mutableStateOf(WorkspacePanel.PROJECTS) }
    var opened by remember { mutableStateOf<ProjectSnapshot?>(null) }
    var editingOpenedProject by remember { mutableStateOf(false) }
    var requestedFlowId by remember { mutableStateOf<String?>(null) }
    var designingRunnerUi by remember { mutableStateOf(false) }
    var showingImageTools by remember { mutableStateOf(false) }
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
    var backupSlotsProject by remember { mutableStateOf<ProjectSummary?>(null) }
    var floatingEditorProject by remember { mutableStateOf<ProjectSummary?>(null) }
    var floatingEditorSnapshot by remember { mutableStateOf<ProjectSnapshot?>(null) }
    var floatingVisualEditor by remember { mutableStateOf<VisualEditorState?>(null) }
    var floatingEditorRevision by remember { mutableStateOf(0) }
    var floatingInsertSnippet by remember { mutableStateOf<String?>(null) }
    var floatingClipboard by remember { mutableStateOf<VisualSubtreeClipboard?>(null) }
    var floatingPastePending by remember { mutableStateOf(false) }
    var floatingIndentSlots by remember { mutableStateOf<List<String>?>(null) }
    var floatingEditingNodeId by remember { mutableStateOf<String?>(null) }
    var busyProjectId by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val currentExportTarget by rememberUpdatedState(exportTarget)

    SideEffect {
        onFullScreenChanged(opened != null || workspacePanel != WorkspacePanel.PROJECTS || backupSlotsProject != null)
    }
    DisposableEffect(Unit) {
        onDispose { onFullScreenChanged(false) }
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

    fun runStoreAction(action: () -> ProjectSnapshot?) {
        scope.launch {
            loading = true
            error = null
            runCatching {
                withContext(Dispatchers.IO) {
                    val snapshot = action()
                    snapshot to store.listProjects()
                }
            }.onSuccess { (snapshot, refreshed) ->
                if (snapshot != null) opened = snapshot
                projects = refreshed
                expandedProjectIds = expandedProjectIds.intersect(refreshed.map { it.projectId }.toSet())
                    .ifEmpty { refreshed.firstOrNull()?.let { setOf(it.projectId) }.orEmpty() }
            }.onFailure { failure ->
                error = failure.message ?: "项目操作失败"
            }
            loading = false
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

    fun runProject(project: ProjectSummary) {
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
        busyProjectId = project.projectId
        error = null
        notice = null
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    var snapshot = store.openProject(project.projectId)
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
                            RuntimeProjectPlan.fromVisualSnapshot(snapshot, generationId)
                        }
                    }
                    require(
                        runtimeClient.startProject(
                            generatedLuaModule = plan.luaSource,
                            resources = plan.resources,
                            capabilities = plan.capabilities,
                            designWidth = plan.designWidth,
                            designHeight = plan.designHeight,
                            scaleMode = plan.scaleMode,
                        ),
                    ) { "Runner拒绝启动，请查看引擎状态" }
                    snapshot
                }
            }.onSuccess {
                notice = "“${project.name}”已提交运行"
                projects = withContext(Dispatchers.IO) { store.listProjects() }
            }.onFailure { failure ->
                error = failure.message ?: "项目启动失败"
            }
            busyProjectId = null
        }
    }

    fun saveFloatingFlow() {
        val project = floatingEditorProject ?: return
        val snapshot = floatingEditorSnapshot ?: return
        val editor = floatingVisualEditor ?: return
        val flowId = snapshot.manifest.entryFlowId ?: return
        if (busyProjectId != null || !editor.isDirty) return
        val submitted = editor.currentSource
        val expected = editor.savedSource
        busyProjectId = project.projectId
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    store.saveFlow(project.projectId, flowId, submitted, expected)
                }
            }.onSuccess { saved ->
                editor.markSaved(submitted)
                floatingEditorSnapshot = saved
                projects = withContext(Dispatchers.IO) { store.listProjects() }
                notice = "程序树已保存"
                error = null
            }.onFailure { failure ->
                // currentSource is intentionally retained; the user can retry
                // or open the full editor to resolve an external write conflict.
                error = failure.message ?: "程序树保存失败"
            }
            busyProjectId = null
        }
    }

    fun insertFloatingBlock(
        snippet: String,
        childSlot: String?,
        position: LegacyInsertPosition = LegacyInsertPosition.BELOW,
    ) {
        val snapshot = floatingEditorSnapshot ?: return
        val editor = floatingVisualEditor ?: return
        val flowId = snapshot.manifest.entryFlowId ?: return
        val matches = BlockCatalog.search(
            legacyDockBlockQuery(snippet),
            snapshot.manifest.capabilities.toSet(),
        ).filter(BlockSearchResult::isAvailable)
        val contract = matches.singleOrNull()
        if (contract == null) {
            error = if (matches.isEmpty()) "当前命令还没有可用的正式积木" else "命令匹配到多个积木，请进入完整编辑器选择"
            return
        }
        val defaults = initialBlockArguments(
            contract.contract,
            snapshot.manifest.flows,
            snapshot.manifest.resources,
            flowId,
        )
        if (defaults == null) {
            error = "缺少${contract.contract.title}所需的项目资源"
            return
        }
        val arguments = legacyDockBlockArguments(contract.contract, snippet, defaults)
        if (position == LegacyInsertPosition.REPLACE) {
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
        if (position == LegacyInsertPosition.LIST_BOTTOM) {
            editor.selectedNodeId = editor.rows.lastOrNull { it.depth == 0 }?.nodeId
        }
        val inserted = editor.insertBlock(contract.contract, arguments, intoChildBlockName = childSlot)
        if (inserted != null && position == LegacyInsertPosition.ABOVE && originalSelection != null) {
            editor.moveSelected(-1)
        }
        if (inserted == null) {
            error = "无法把${contract.contract.title}加入当前位置"
            return
        }
        floatingEditorRevision++
        notice = "已加入：${contract.contract.title}"
        error = null
        saveFloatingFlow()
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
            editor.pasteSubtree(clipboard, intoChildBlockName = childSlot) != null,
            "已粘贴节点副本",
        )
    }

    fun runFloatingCommand(command: LegacyDockProgramCommand) {
        val editor = floatingVisualEditor ?: return
        if (busyProjectId != null || editor.isReadOnly) return
        when (command) {
            LegacyDockProgramCommand.MOVE_UP -> finishFloatingMutation(editor.moveSelected(-1), "节点已上移")
            LegacyDockProgramCommand.MOVE_DOWN -> finishFloatingMutation(editor.moveSelected(1), "节点已下移")
            LegacyDockProgramCommand.OUTDENT -> finishFloatingMutation(editor.outdentSelected(), "节点已减少一级缩进")
            LegacyDockProgramCommand.INDENT -> {
                val selected = editor.rows.firstOrNull { it.nodeId == editor.selectedNodeId }
                val siblings = editor.rows.filter { it.blockId == selected?.blockId }.sortedBy { it.orderKey }
                val previous = siblings.getOrNull(siblings.indexOfFirst { it.nodeId == selected?.nodeId } - 1)
                val slots = previous?.kind?.let(BlockCatalog::find)?.childBlocks.orEmpty()
                when (slots.size) {
                    0 -> error = "上一节点不是容器"
                    1 -> finishFloatingMutation(editor.indentSelected(slots.single()), "节点已缩进")
                    else -> floatingIndentSlots = slots
                }
            }
            LegacyDockProgramCommand.COPY -> {
                floatingClipboard = editor.copySelectedSubtree()
                if (floatingClipboard == null) error = "请先选择节点" else {
                    error = null
                    notice = "已复制整棵子树"
                }
            }
            LegacyDockProgramCommand.CUT -> {
                val copied = editor.copySelectedSubtree()
                if (copied == null) error = "请先选择节点" else {
                    floatingClipboard = copied
                    finishFloatingMutation(editor.deleteSelected(), "已剪切整棵子树")
                }
            }
            LegacyDockProgramCommand.PASTE -> {
                if (floatingClipboard == null) error = "剪贴板为空" else {
                    val selected = editor.rows.firstOrNull { it.nodeId == editor.selectedNodeId }
                    val slots = selected?.kind?.let(BlockCatalog::find)?.childBlocks.orEmpty()
                    if (selected != null && slots.isNotEmpty()) floatingPastePending = true
                    else pasteFloatingClipboard(null)
                }
            }
            LegacyDockProgramCommand.UNDO -> finishFloatingMutation(editor.undo(), "已撤销")
            LegacyDockProgramCommand.REDO -> finishFloatingMutation(editor.redo(), "已重做")
            LegacyDockProgramCommand.DATA_BACKFILL -> {
                error = null
                notice = "当前没有可回填的调试结果，未修改节点参数"
            }
            LegacyDockProgramCommand.SEARCH,
            LegacyDockProgramCommand.EXPAND_ALL,
            LegacyDockProgramCommand.COLLAPSE_ALL -> Unit
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
        floatingVisualEditor = null
        floatingInsertSnippet = null
        floatingClipboard = null
        floatingPastePending = false
        floatingIndentSlots = null
        floatingEditingNodeId = null
        if (project == null || project.sourceMode != ProjectSourceMode.VISUAL) return@LaunchedEffect
        runCatching { withContext(Dispatchers.IO) { store.openProject(project.projectId) } }
            .onSuccess { snapshot ->
                val flowId = snapshot.manifest.entryFlowId
                val flow = snapshot.manifest.flows.singleOrNull { it.flowId == flowId }
                if (flow == null) {
                    error = "可视化项目缺少入口 Flow"
                } else {
                    floatingEditorSnapshot = snapshot
                    floatingVisualEditor = VisualEditorState.create(
                        snapshot.flowSources[flow.flowId].orEmpty(),
                        flow.rootBlockId,
                    )
                    floatingEditorRevision++
                }
            }
            .onFailure { failure -> error = failure.message ?: "读取程序树失败" }
    }

    when (workspacePanel) {
        WorkspacePanel.BACKUPS -> {
            BackupManagementScreen(
                projects = projects,
                busyProjectId = busyProjectId,
                onBack = { workspacePanel = WorkspacePanel.PROJECTS },
                onImport = {
                    importLauncher.launch(
                        arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream"),
                    )
                },
                onExport = { project ->
                    if (busyProjectId == null) {
                        exportTarget = project
                        exportLauncher.launch(backupFileName(project.name))
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

    backupSlotsProject?.let { project ->
        ProjectBackupSlotsScreen(
            projectName = project.name,
            onBack = { backupSlotsProject = null },
            onBackup = {
                if (busyProjectId == null) {
                    exportTarget = project
                    exportLauncher.launch(backupFileName(project.name))
                }
            },
            modifier = modifier,
        )
        return
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
            onBack = { designingRunnerUi = false },
            modifier = modifier,
        )
        return
    }

    val imageToolsProject = opened?.takeIf { showingImageTools }
    if (imageToolsProject != null) {
        ImageToolsScreen(
            snapshot = imageToolsProject,
            store = store,
            onSnapshotChanged = { opened = it },
            onBack = { showingImageTools = false },
            modifier = modifier,
        )
        return
    }

    val packageProject = opened?.takeIf { showingPackager }
    if (packageProject != null) {
        ProjectPackageScreen(
            snapshot = packageProject,
            onBack = { showingPackager = false },
            modifier = modifier,
        )
        return
    }

    val recorderProject = opened?.takeIf { showingRecorder }
    if (recorderProject != null) {
        RecordingWorkspaceScreen(
            snapshot = recorderProject,
            onBack = { showingRecorder = false },
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
            active = active,
            modifier = modifier,
            onSnapshotChanged = { opened = it },
            onExit = {
                editingOpenedProject = false
            },
        )
        return
    }

    val visualProject = opened?.takeIf {
        editingOpenedProject && it.manifest.sourceMode == ProjectSourceMode.VISUAL
    }
    if (visualProject != null) {
        VisualProjectScreen(
            snapshot = visualProject,
            store = store,
            runtimeClient = runtimeClient,
            runtimeState = runtimeState,
            active = active,
            initialFlowId = requestedFlowId,
            modifier = modifier,
            onSnapshotChanged = { opened = it },
            onExit = {
                editingOpenedProject = false
            },
        )
        return
    }

    val fileWorkspace = opened
    if (fileWorkspace != null) {
        ProjectFilesScreen(
            snapshot = fileWorkspace,
            busy = busyProjectId != null,
            onBack = {
                opened = null
                editingOpenedProject = false
                runStoreAction { null }
            },
            onRefresh = {
                runStoreAction { store.openProject(fileWorkspace.manifest.projectId) }
            },
            onOpenFile = { file ->
                when (file.kind) {
                    StudioProjectFileKind.LUA -> editingOpenedProject = true
                    StudioProjectFileKind.FLOW -> {
                        requestedFlowId = fileWorkspace.manifest.flows
                            .singleOrNull { it.path == file.path }
                            ?.flowId
                        editingOpenedProject = true
                    }
                    StudioProjectFileKind.MANIFEST,
                    StudioProjectFileKind.IMAGE,
                    StudioProjectFileKind.GLYPH_DICTIONARY,
                    -> settingsSnapshot = fileWorkspace
                }
            },
            onRun = {
                projects.singleOrNull { it.projectId == fileWorkspace.manifest.projectId }
                    ?.let(::runProject)
            },
            onOpenUiDesigner = { designingRunnerUi = true },
            onOpenImageTools = { showingImageTools = true },
            onOpenPackager = { showingPackager = true },
            onOpenRecorder = { showingRecorder = true },
            onSettings = { settingsSnapshot = fileWorkspace },
            modifier = modifier,
        )
    } else {
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
                            Modifier.padding(horizontal = 16.dp, vertical = 18.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            WorkspaceIcon(WorkspaceIconKind.FOLDER, Modifier.size(32.dp), WorkspaceAccent)
                            Text("还没有项目", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                            Text("创建一个项目，开始编排自动化流程", fontSize = 11.sp, color = WorkspaceTextSecondary)
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
                        enabled = !loading && busyProjectId == null,
                        busy = busyProjectId == project.projectId,
                        onToggle = {
                            expandedProjectIds = if (expanded) {
                                expandedProjectIds - project.projectId
                            } else {
                                expandedProjectIds + project.projectId
                            }
                        },
                        onOpen = { floatingEditorProject = project },
                        onRun = { runProject(project) },
                        onSettings = { openSettings(project) },
                        onUi = {
                            designingRunnerUi = true
                            runStoreAction { store.openProject(project.projectId) }
                        },
                        onPackage = {
                            showingPackager = true
                            runStoreAction { store.openProject(project.projectId) }
                        },
                        onExport = {
                            if (busyProjectId == null) backupSlotsProject = project
                        },
                        onRename = { renameTarget = project },
                        onDelete = { deleteTarget = project },
                    )
                }
            }
            }
            floatingEditorProject?.let { project ->
                val visualSnapshot = floatingEditorSnapshot
                val visualEditor = floatingVisualEditor
                @Suppress("UNUSED_VARIABLE")
                val revision = floatingEditorRevision
                LegacyScriptDock(
                    projectName = project.name,
                    onRun = {
                        if (visualEditor?.isDirty == true) {
                            notice = "请先完成程序树保存后再运行"
                            saveFloatingFlow()
                        } else {
                            runProject(project)
                        }
                    },
                    sourceName = if (project.sourceMode == ProjectSourceMode.VISUAL) {
                        visualSnapshot?.manifest?.entryFlowId?.let { "$it.jsonl" } ?: "加载中…"
                    } else "main.lua",
                    programNodes = if (project.sourceMode == ProjectSourceMode.VISUAL) {
                        visualEditor?.rows?.map { node ->
                            val contract = BlockCatalog.find(node.kind)
                            LegacyDockProgramNode(
                                nodeId = node.nodeId,
                                label = buildString {
                                    node.childSlot?.let { append('[').append(childBlockLabel(it)).append("] ") }
                                    append(legacyDockProgramLabel(contract, visualEditor.nodeArguments(node.nodeId), node.kind))
                                },
                                kind = node.kind,
                                depth = node.depth,
                                childSlots = contract?.childBlocks.orEmpty(),
                            )
                        }.orEmpty()
                    } else null,
                    programSelectedNodeId = visualEditor?.selectedNodeId,
                    editingEnabled = busyProjectId == null && (
                        project.sourceMode == ProjectSourceMode.LUA ||
                            visualEditor?.isReadOnly == false
                        ),
                    onProgramNodeSelected = { nodeId ->
                        visualEditor?.selectedNodeId = nodeId
                        floatingEditorRevision++
                    },
                    onInsertPositioned = { snippet, position ->
                        val selected = visualEditor?.rows?.firstOrNull { it.nodeId == visualEditor.selectedNodeId }
                        val slots = selected?.kind?.let(BlockCatalog::find)?.childBlocks.orEmpty()
                        when {
                            position == LegacyInsertPosition.INSIDE && slots.isEmpty() -> error = "当前选择行不能加入内部"
                            position == LegacyInsertPosition.INSIDE -> insertFloatingBlock(snippet, slots.first(), position)
                            else -> insertFloatingBlock(snippet, null, position)
                        }
                    },
                    onProgramNodeDeleted = {
                        if (busyProjectId == null && visualEditor?.deleteSelected() == true) {
                            floatingEditorRevision++
                            saveFloatingFlow()
                        }
                    },
                    onProgramNodeEdited = {
                        if (visualEditor?.isDirty == true) {
                            notice = "正在保存程序树，保存完成后再编辑参数"
                            saveFloatingFlow()
                        } else if (visualSnapshot != null) {
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
                    onProgramCommand = { command ->
                        if (project.sourceMode == ProjectSourceMode.VISUAL) runFloatingCommand(command)
                        else error = "结构化节点操作仅适用于可视化项目，Lua 请进入源码编辑器"
                    },
                    onStep = {
                        notice = "单步运行需要 Runtime 调试协议，当前版本未开放，未执行脚本"
                    },
                    onClose = { floatingEditorProject = null },
                    onInsert = { snippet ->
                        if (project.sourceMode == ProjectSourceMode.VISUAL) {
                            val selected = visualEditor?.rows?.firstOrNull { it.nodeId == visualEditor.selectedNodeId }
                            val childSlots = selected?.kind?.let(BlockCatalog::find)?.childBlocks.orEmpty()
                            if (selected != null && childSlots.isNotEmpty()) {
                                floatingInsertSnippet = snippet
                            } else {
                                insertFloatingBlock(snippet, null)
                            }
                        } else scope.launch {
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
                val pendingSnippet = floatingInsertSnippet
                if (pendingSnippet != null && visualEditor != null) {
                    val selected = visualEditor.rows.firstOrNull { it.nodeId == visualEditor.selectedNodeId }
                    val childSlots = selected?.kind?.let(BlockCatalog::find)?.childBlocks.orEmpty()
                    AlertDialog(
                        onDismissRequest = { floatingInsertSnippet = null },
                        title = { Text("选择插入位置") },
                        text = {
                            Column {
                                TextButton(
                                    onClick = {
                                        floatingInsertSnippet = null
                                        insertFloatingBlock(pendingSnippet, null)
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                ) { Text("当前节点之后") }
                                childSlots.forEach { childSlot ->
                                    TextButton(
                                        onClick = {
                                            floatingInsertSnippet = null
                                            insertFloatingBlock(pendingSnippet, childSlot)
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                    ) { Text("${childBlockLabel(childSlot)}内新增") }
                                }
                            }
                        },
                        confirmButton = {},
                        dismissButton = { TextButton(onClick = { floatingInsertSnippet = null }) { Text("取消") } },
                    )
                }
                floatingIndentSlots?.let { slots ->
                    AlertDialog(
                        onDismissRequest = { floatingIndentSlots = null },
                        title = { Text("选择缩进分支") },
                        text = { Column { slots.forEach { slot ->
                            TextButton(
                                onClick = {
                                    floatingIndentSlots = null
                                    finishFloatingMutation(
                                        visualEditor?.indentSelected(slot) == true,
                                        "节点已缩进到${childBlockLabel(slot)}",
                                    )
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text(childBlockLabel(slot)) }
                        } } },
                        confirmButton = {},
                        dismissButton = { TextButton(onClick = { floatingIndentSlots = null }) { Text("取消") } },
                    )
                }
                if (floatingPastePending && visualEditor != null) {
                    val selected = visualEditor.rows.firstOrNull { it.nodeId == visualEditor.selectedNodeId }
                    val slots = selected?.kind?.let(BlockCatalog::find)?.childBlocks.orEmpty()
                    AlertDialog(
                        onDismissRequest = { floatingPastePending = false },
                        title = { Text("选择粘贴位置") },
                        text = { Column {
                            TextButton(
                                onClick = { floatingPastePending = false; pasteFloatingClipboard(null) },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("当前节点之后") }
                            slots.forEach { slot ->
                                TextButton(
                                    onClick = { floatingPastePending = false; pasteFloatingClipboard(slot) },
                                    modifier = Modifier.fillMaxWidth(),
                                ) { Text("${childBlockLabel(slot)}内") }
                            }
                        } },
                        confirmButton = {},
                        dismissButton = { TextButton(onClick = { floatingPastePending = false }) { Text("取消") } },
                    )
                }
                val editingNodeId = floatingEditingNodeId
                if (editingNodeId != null && visualEditor != null && visualSnapshot != null) {
                    val row = visualEditor.rows.firstOrNull { it.nodeId == editingNodeId }
                    val contract = row?.kind?.let(BlockCatalog::find)
                    val arguments = visualEditor.nodeArguments(editingNodeId)
                    if (contract == null || arguments == null) {
                        floatingEditingNodeId = null
                    } else {
                        VisualNodeArgumentsDialog(
                            contract = contract,
                            arguments = arguments,
                            flows = visualSnapshot.manifest.flows,
                            currentFlowId = visualSnapshot.manifest.entryFlowId.orEmpty(),
                            resources = visualSnapshot.manifest.resources,
                            onDismiss = { floatingEditingNodeId = null },
                            onConfirm = { updated ->
                                floatingEditingNodeId = null
                                finishFloatingMutation(
                                    visualEditor.updateArguments(editingNodeId, updated),
                                    "节点参数已更新",
                                )
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
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除项目") },
            text = { Text("确定删除“${project.name}”？本地脚本和资源也会删除，此操作不可撤销。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleteTarget = null
                        if (opened?.manifest?.projectId == project.projectId) opened = null
                        runStoreAction {
                            store.deleteProject(project.projectId)
                            null
                        }
                    },
                ) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("取消") } },
        )
    }
    settingsSnapshot?.let { snapshot ->
        ProjectSettingsDialog(
            snapshot = snapshot,
            store = store,
            onSnapshotChanged = { updated ->
                settingsSnapshot = updated
                if (opened?.manifest?.projectId == updated.manifest.projectId) opened = updated
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

private val WorkspaceAccent = Color(0xFF3A6EFF)
private val WorkspaceTextPrimary = Color(0xFF182033)
private val WorkspaceTextSecondary = Color(0xFF7A8499)
private val WorkspaceBorder = Color(0xFFEBEFF5)
private val WorkspaceMint = Color(0xFF22A06B)
private val WorkspacePurple = Color(0xFF735BFF)
private val WorkspaceDanger = Color(0xFFE5484D)

private enum class WorkspaceIconKind {
    CLOUD, BOOK, CODE_BOX, FOLDER, CHEVRON, PLAY, EDIT, UI, BACKUP, PACKAGE, DELETE,
}

@Composable
private fun WorkspaceIcon(kind: WorkspaceIconKind, modifier: Modifier, tint: Color) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val stroke = Stroke(width = size.minDimension * 0.075f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        fun path(block: Path.() -> Unit) = Path().apply(block)
        when (kind) {
            WorkspaceIconKind.CLOUD, WorkspaceIconKind.BACKUP -> {
                val cloud = path {
                    moveTo(.20f * w, .70f * h)
                    cubicTo(.05f * w, .68f * h, .05f * w, .44f * h, .24f * w, .40f * h)
                    cubicTo(.31f * w, .12f * h, .69f * w, .13f * h, .76f * w, .40f * h)
                    cubicTo(.96f * w, .43f * h, .96f * w, .70f * h, .78f * w, .72f * h)
                    close()
                }
                drawPath(cloud, tint, style = stroke)
                if (kind == WorkspaceIconKind.BACKUP) {
                    drawLine(tint, Offset(.50f * w, .66f * h), Offset(.50f * w, .42f * h), stroke.width, StrokeCap.Round)
                    drawLine(tint, Offset(.50f * w, .42f * h), Offset(.40f * w, .52f * h), stroke.width, StrokeCap.Round)
                    drawLine(tint, Offset(.50f * w, .42f * h), Offset(.60f * w, .52f * h), stroke.width, StrokeCap.Round)
                }
            }
            WorkspaceIconKind.BOOK -> {
                val left = path { moveTo(.08f*w,.20f*h); quadraticTo(.30f*w,.12f*h,.48f*w,.28f*h); lineTo(.48f*w,.82f*h); quadraticTo(.28f*w,.68f*h,.08f*w,.76f*h); close() }
                val right = path { moveTo(.92f*w,.20f*h); quadraticTo(.70f*w,.12f*h,.52f*w,.28f*h); lineTo(.52f*w,.82f*h); quadraticTo(.72f*w,.68f*h,.92f*w,.76f*h); close() }
                drawPath(left, tint, style = stroke); drawPath(right, tint, style = stroke)
            }
            WorkspaceIconKind.CODE_BOX -> {
                drawRoundRect(tint, Offset(.08f*w,.20f*h), Size(.84f*w,.64f*h), CornerRadius(.05f*w), style = stroke)
                drawLine(tint, Offset(.08f*w,.34f*h), Offset(.92f*w,.34f*h), stroke.width)
                drawLine(tint, Offset(.39f*w,.48f*h), Offset(.30f*w,.58f*h), stroke.width, StrokeCap.Round)
                drawLine(tint, Offset(.30f*w,.58f*h), Offset(.39f*w,.68f*h), stroke.width, StrokeCap.Round)
                drawLine(tint, Offset(.61f*w,.48f*h), Offset(.70f*w,.58f*h), stroke.width, StrokeCap.Round)
                drawLine(tint, Offset(.70f*w,.58f*h), Offset(.61f*w,.68f*h), stroke.width, StrokeCap.Round)
                drawLine(tint, Offset(.55f*w,.45f*h), Offset(.46f*w,.71f*h), stroke.width, StrokeCap.Round)
            }
            WorkspaceIconKind.FOLDER -> {
                val folder = path { moveTo(.10f*w,.28f*h); lineTo(.40f*w,.28f*h); lineTo(.49f*w,.39f*h); lineTo(.90f*w,.39f*h); lineTo(.90f*w,.82f*h); lineTo(.10f*w,.82f*h); close() }
                drawPath(folder, tint, style = stroke)
            }
            WorkspaceIconKind.CHEVRON -> {
                drawLine(tint, Offset(.35f*w,.22f*h), Offset(.68f*w,.50f*h), stroke.width, StrokeCap.Square)
                drawLine(tint, Offset(.68f*w,.50f*h), Offset(.35f*w,.78f*h), stroke.width, StrokeCap.Square)
            }
            WorkspaceIconKind.PLAY -> {
                val play = path { moveTo(.26f*w,.12f*h); lineTo(.82f*w,.50f*h); lineTo(.26f*w,.88f*h); close() }
                drawPath(play, tint, style = stroke)
            }
            WorkspaceIconKind.EDIT -> {
                drawLine(tint, Offset(.34f*w,.10f*h), Offset(.10f*w,.50f*h), stroke.width, StrokeCap.Round)
                drawLine(tint, Offset(.10f*w,.50f*h), Offset(.34f*w,.90f*h), stroke.width, StrokeCap.Round)
                drawLine(tint, Offset(.66f*w,.10f*h), Offset(.90f*w,.50f*h), stroke.width, StrokeCap.Round)
                drawLine(tint, Offset(.90f*w,.50f*h), Offset(.66f*w,.90f*h), stroke.width, StrokeCap.Round)
                drawLine(tint, Offset(.58f*w,.05f*h), Offset(.42f*w,.95f*h), stroke.width, StrokeCap.Round)
            }
            WorkspaceIconKind.UI -> {
                drawRect(tint, Offset(.08f*w,.10f*h), Size(.84f*w,.80f*h), style = stroke)
                drawLine(tint, Offset(.08f*w,.35f*h), Offset(.92f*w,.35f*h), stroke.width)
                drawLine(tint, Offset(.55f*w,.35f*h), Offset(.55f*w,.90f*h), stroke.width)
                drawLine(tint, Offset(.08f*w,.65f*h), Offset(.55f*w,.65f*h), stroke.width)
            }
            WorkspaceIconKind.PACKAGE -> {
                drawArc(tint, 200f, 140f, false, Offset(.18f*w,.18f*h), Size(.64f*w,.55f*h), style = stroke)
                drawLine(tint, Offset(.25f*w,.28f*h), Offset(.13f*w,.10f*h), stroke.width, StrokeCap.Round)
                drawLine(tint, Offset(.75f*w,.28f*h), Offset(.87f*w,.10f*h), stroke.width, StrokeCap.Round)
                drawCircle(tint, .04f*w, Offset(.35f*w,.45f*h)); drawCircle(tint, .04f*w, Offset(.65f*w,.45f*h))
            }
            WorkspaceIconKind.DELETE -> {
                drawRect(tint, Offset(.25f*w,.30f*h), Size(.50f*w,.58f*h), style = stroke)
                drawLine(tint, Offset(.18f*w,.22f*h), Offset(.82f*w,.22f*h), stroke.width, StrokeCap.Round)
                drawLine(tint, Offset(.38f*w,.12f*h), Offset(.62f*w,.12f*h), stroke.width, StrokeCap.Round)
            }
        }
    }
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
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = Color.White,
    ) {
        Column {
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
                    WorkspaceIcon(WorkspaceIconKind.FOLDER, Modifier.size(20.dp), WorkspaceAccent)
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
                    Modifier.size(18.dp).graphicsLayer { rotationZ = if (expanded) 90f else 0f },
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
    val localResolution = remember(configuration.screenWidthDp, configuration.screenHeightDp) {
        val metrics = context.resources.displayMetrics
        val shortSide = minOf(metrics.widthPixels, metrics.heightPixels)
        val longSide = maxOf(metrics.widthPixels, metrics.heightPixels)
        "$shortSide×$longSide"
    }
    var selectedResolution by remember(localResolution) { mutableStateOf("720×1280") }
    val valid = name.trim().isNotEmpty() && name.trim().length <= 128
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
                    Row(
                        modifier = Modifier.fillMaxWidth().height(42.dp).padding(start = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            title,
                            color = WorkspaceAccent,
                            fontSize = 16.sp,
                            lineHeight = 20.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            "?",
                            color = WorkspaceAccent,
                            fontSize = 20.sp,
                            lineHeight = 20.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .size(42.dp)
                                .clickable { showingHelp = !showingHelp }
                                .wrapContentSize(Alignment.Center),
                        )
                    }
                    Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFFE2E2E2)))
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
                                    onValueChange = { if (it.length <= 128) name = it },
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
                                    label = "720×1280",
                                    badge = "推荐",
                                    selected = selectedResolution == "720×1280",
                                    onClick = { selectedResolution = "720×1280" },
                                )
                                Spacer(Modifier.height(6.dp))
                                ResolutionOption(
                                    label = "1080×1920",
                                    selected = selectedResolution == "1080×1920",
                                    onClick = { selectedResolution = "1080×1920" },
                                )
                                Spacer(Modifier.height(6.dp))
                                ResolutionOption(
                                    label = localResolution,
                                    badge = "本机",
                                    selected = selectedResolution == localResolution,
                                    onClick = { selectedResolution = localResolution },
                                )
                            }
                        }
                    }
                    Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFFE2E2E2)))
                    Row(Modifier.fillMaxWidth().height(40.dp)) {
                        Text(
                            "取消",
                            color = Color.Black,
                            fontSize = 13.sp,
                            modifier = Modifier.weight(1f).fillMaxSize().clickable(onClick = onDismiss).wrapContentSize(Alignment.Center),
                        )
                        Box(Modifier.width(1.dp).fillMaxSize().background(Color(0xFFE2E2E2)))
                        Text(
                            "创建",
                            color = Color.Black.copy(alpha = if (valid) 1f else .35f),
                            fontSize = 13.sp,
                            modifier = Modifier.weight(1f).fillMaxSize().clickable(enabled = valid) {
                                val parts = selectedResolution.split('×')
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
        ProjectDialogSection("创建说明") {
            Text(
                "基准分辨率决定项目截图采集目标和统一坐标系；不会修改设备的屏幕分辨率。720×1280 处理量较小，适合多数项目；1080×1920 可保留更多细节，但会增加处理耗时。",
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

private const val IMPORT_BUSY_ID = "__import_backup__"

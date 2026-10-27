package com.autoscript.studio

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.random.Random
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.wrapContentSize
import com.autoscript.studio.StudioDialog as Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.autoscript.core.designsystem.hairline
import com.autoscript.project.store.BackupProjectSummary
import com.autoscript.project.store.BackupSlot
import com.autoscript.project.store.ProjectSnapshot
import com.autoscript.project.store.ProjectStore
import com.autoscript.project.store.ProjectSummary
import com.autoscript.core.designsystem.AutoScriptPalette
import com.autoscript.core.model.RuntimeConnectionPhase
import com.autoscript.core.model.RuntimeConnectionState
import com.autoscript.core.model.RuntimeEngineState
import com.autoscript.runtime.client.RuntimeClient
import com.autoscript.runtime.client.VisualCompileResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private suspend fun <T> projectPageAttempt(action: suspend () -> T): Result<T> = try {
    val value = action()
    currentCoroutineContext().ensureActive()
    Result.success(value)
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (failure: Exception) {
    currentCoroutineContext().ensureActive()
    Result.failure(failure)
}

@Composable
internal fun ProjectPage(
    store: ProjectStore,
    runtimeClient: RuntimeClient,
    runtimeState: RuntimeConnectionState,
    onFullScreenChanged: (Boolean) -> Unit,
    captureHandoff: StudioCaptureHandoff?,
    onCaptureHandoffConsumed: () -> Unit,
    entryRequest: WorkspaceEntryRequest? = null,
    onEntryRequestConsumed: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var projects by remember { mutableStateOf<List<ProjectSummary>>(emptyList()) }
    var projectQuery by remember { mutableStateOf("") }
    var projectOrder by remember { mutableStateOf(ProjectListOrder.UPDATED) }
    val entryGate = remember { WorkspaceEntryGate() }
    val openOverlayEditor = rememberEditorOverlayLauncher()
    val overlayEditorStatus by EditorOverlaySessions.status.collectAsState()
    val openScriptInterface = rememberScriptLaunchOverlayLauncher()
    val scriptLaunchStatus by ScriptLaunchSessions.status.collectAsState()
    var workspacePanel by remember { mutableStateOf(WorkspacePanel.PROJECTS) }
    var opened by remember { mutableStateOf<ProjectSnapshot?>(null) }
    var designingRunnerUi by remember { mutableStateOf(false) }
    var showingPackager by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var expandedProjectIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var showCreate by remember { mutableStateOf(false) }
    var showHistoricalProjects by remember { mutableStateOf(false) }
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
    var busyProjectId by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val currentExportTarget by rememberUpdatedState(exportTarget)

    SideEffect {
        onFullScreenChanged(opened != null || workspacePanel != WorkspacePanel.PROJECTS || backupSlotsTarget != null)
    }
    DisposableEffect(Unit) {
        onDispose { onFullScreenChanged(false) }
    }

    LaunchedEffect(captureHandoff?.token) {
        val handoff = captureHandoff ?: return@LaunchedEffect
        when {
            !android.provider.Settings.canDrawOverlays(context) ->
                error = "截图编辑需要悬浮授权，请允许智构显示在其他应用上层后重新截屏"
            editorSessionDecision(EditorOverlaySessions.status.value?.projectId, handoff.projectId) == EditorSessionDecision.REJECT ->
                error = "请先关闭当前悬浮编辑，再打开其他项目截图"
            else -> runCatching { EditorOverlayService.openCapture(context, handoff) }.onFailure {
                error = "无法打开截图编辑，请检查悬浮权限和系统后台限制"
            }
        }
        onCaptureHandoffConsumed()
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri ->
        val project = currentExportTarget
        exportTarget = null
        if (uri == null || project == null) return@rememberLauncherForActivityResult
        if (loading || busyProjectId != null || backupProgress != null) return@rememberLauncherForActivityResult
        busyProjectId = project.projectId
        error = null
        notice = null
        scope.launch {
            try { projectPageAttempt {
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
            } finally { busyProjectId = null }
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        if (loading || busyProjectId != null || backupProgress != null) return@rememberLauncherForActivityResult
        busyProjectId = IMPORT_BUSY_ID
        error = null
        notice = null
        scope.launch {
            try { projectPageAttempt {
                withContext(Dispatchers.IO) {
                    val input = requireNotNull(context.contentResolver.openInputStream(uri)) {
                        "无法读取备份文件"
                    }
                    val imported = input.use(store::importProjectBackup)
                    imported to store.listProjects()
                }
            }.onSuccess { (imported, refreshed) ->
                projects = refreshed
                expandedProjectIds = setOf(imported.manifest.projectId)
                notice = "“${imported.manifest.name}”已作为新项目导入"
            }.onFailure { failure ->
                error = failure.message ?: "项目备份导入失败"
            }
            } finally { busyProjectId = null }
        }
    }

    /**
     * 执行一次 Store 写操作并刷新项目列表。只有工具页（界面设计/打包）需要把结果留在
     * [opened] 里；新建/改名/删除只刷新列表，参考产品也不会在创建后跳进项目。
     */
    fun runStoreAction(openResult: Boolean = false, action: () -> ProjectSnapshot?) {
        if (loading || busyProjectId != null || backupProgress != null) return
        loading = true
        error = null
        scope.launch {
            try { projectPageAttempt {
                withContext(Dispatchers.IO) {
                    val snapshot = action()
                    snapshot to store.listProjects()
                }
            }.onSuccess { (snapshot, refreshed) ->
                if (openResult && snapshot != null) {
                    if (StudioProjectPolicy.canOperate(snapshot.manifest.sourceMode)) opened = snapshot
                    else error = StudioProjectPolicy.historicalMessage
                }
                projects = refreshed
                expandedProjectIds = expandedProjectIds.intersect(refreshed.map { it.projectId }.toSet())
                    .ifEmpty { orderedStudioProjects(refreshed, "", projectOrder).firstOrNull()?.let { setOf(it.projectId) }.orEmpty() }
            }.onFailure { failure ->
                error = failure.message ?: "项目操作失败"
                (failure as? com.autoscript.project.store.ProjectStoreException)?.creationDiagnostic?.let {
                    android.util.Log.w("ProjectCreation", it.summary)
                }
            }
            } finally { loading = false }
        }
    }

    fun refreshProjects() {
        if (loading || busyProjectId != null || backupProgress != null) return
        loading = true
        error = null
        scope.launch {
            try { projectPageAttempt { withContext(Dispatchers.IO) { store.listProjects() } }
                .onSuccess { refreshed ->
                    projects = refreshed
                    val ids = refreshed.map { it.projectId }.toSet()
                    expandedProjectIds = expandedProjectIds.intersect(ids)
                        .ifEmpty { orderedStudioProjects(refreshed, "", projectOrder).firstOrNull()?.let { setOf(it.projectId) }.orEmpty() }
                }
                .onFailure { failure -> error = failure.message ?: "读取项目失败" }
            } finally { loading = false }
        }
    }

    /** 槽位备份/恢复/删除：进行中显示 58dp 进度面板，结束后刷新槽位、备份列表和项目列表。 */
    fun runBackupSlotAction(label: String, action: () -> String) {
        if (loading || backupProgress != null || busyProjectId != null) return
        backupProgress = label
        backupMessage = null
        scope.launch {
            try {
            projectPageAttempt { withContext(Dispatchers.IO) { action() } }
                .onSuccess { message -> backupMessage = message }
                .onFailure { failure -> backupMessage = failure.message ?: "备份操作失败" }
            projectPageAttempt { withContext(Dispatchers.IO) { store.listProjects() } }.onSuccess { projects = it }
            backupsRevision++
            } finally { backupProgress = null }
        }
    }

    fun openSettings(project: ProjectSummary) {
        if (!StudioProjectPolicy.canOperate(project.sourceMode)) {
            error = StudioProjectPolicy.historicalMessage
            return
        }
        if (loading || busyProjectId != null || backupProgress != null) return
        busyProjectId = project.projectId
        error = null
        scope.launch {
            try { projectPageAttempt { withContext(Dispatchers.IO) { store.openProject(project.projectId) } }
                .onSuccess { settingsSnapshot = it }
                .onFailure { failure -> error = failure.message ?: "读取项目设置失败" }
            } finally { busyProjectId = null }
        }
    }

    // Formal launch uses saved project data. Editor Run/Step belong only to the overlay session.
    fun launchProject(project: ProjectSummary) {
        if (!StudioProjectPolicy.canOperate(project.sourceMode)) {
            error = StudioProjectPolicy.historicalMessage
            return
        }
        if (loading || busyProjectId != null) return
        if (runtimeState.phase != RuntimeConnectionPhase.CONNECTED) {
            error = "Runner尚未连接"
            return
        }
        if (runtimeState.engineState in setOf(
                RuntimeEngineState.RUNNING, RuntimeEngineState.PAUSED, RuntimeEngineState.STOPPING,
            )
        ) {
            error = "已有脚本正在运行，请先停止"
            return
        }
        busyProjectId = project.projectId
        error = null
        notice = null
        scope.launch {
            try {
                val snapshot = withContext(Dispatchers.IO) { store.openProject(project.projectId) }
                require(StudioProjectPolicy.canOperate(snapshot.manifest.sourceMode)) {
                    StudioProjectPolicy.historicalMessage
                }
                if (snapshot.manifest.runnerUi != null) {
                    openScriptInterface(project.projectId)
                    return@launch
                }
                withContext(Dispatchers.IO) {
                    val generationId = when (val result = runtimeClient.compileVisualProject(project.projectId)) {
                        is VisualCompileResult.Success -> result.generationId
                        is VisualCompileResult.Invalid -> {
                            val location = listOfNotNull(
                                result.diagnostic.flowId?.let { "插件 $it" },
                                result.diagnostic.nodeId?.let { "节点 $it" },
                                result.diagnostic.line?.let { "第 $it 行" },
                            ).joinToString(" · ")
                            throw IllegalArgumentException(
                                if (location.isEmpty()) result.diagnostic.message else "$location：${result.diagnostic.message}",
                            )
                        }
                        is VisualCompileResult.Unavailable -> throw IllegalStateException(result.message)
                    }
                    val refreshed = store.openProject(project.projectId)
                    require(refreshed.manifest.runnerUi == null) { "脚本界面已变化，请重新启动" }
                    val plan = RuntimeProjectPlan.fromVisualSnapshot(refreshed, generationId)
                    require(runtimeClient.startProject(
                        projectId = project.projectId,
                        generatedLuaModule = plan.luaSource,
                        requiredVisionComponents = plan.requiredVisionComponents,
                        resources = plan.resources,
                        requiresPointerInput = plan.requiresPointerInput,
                        capabilities = plan.capabilities,
                        designWidth = plan.designWidth,
                        designHeight = plan.designHeight,
                        scaleMode = plan.scaleMode,
                        scriptUiJson = null,
                        floatingControl = true,
                    )) { "Runner拒绝启动，请查看引擎状态" }
                }
                notice = "“${project.name}”已提交运行"
                projects = withContext(Dispatchers.IO) { store.listProjects() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error = failure.message ?: "项目启动失败"
            } finally {
                busyProjectId = null
            }
        }
    }

    LaunchedEffect(store) {
        try { projectPageAttempt { withContext(Dispatchers.IO) { store.listProjects() } }
            .onSuccess {
                projects = it
                expandedProjectIds = orderedStudioProjects(it, "", projectOrder).firstOrNull()?.let { project -> setOf(project.projectId) }.orEmpty()
            }
            .onFailure { error = it.message ?: "读取项目失败" }
        } finally { loading = false }
    }

    LaunchedEffect(entryRequest?.token, loading, busyProjectId) {
        val request = entryRequest ?: return@LaunchedEffect
        if (loading || busyProjectId != null) return@LaunchedEffect
        if (!entryGate.claim(request)) { onEntryRequestConsumed(); return@LaunchedEffect }
        onEntryRequestConsumed()
        when (request.action) {
            WorkspaceEntryAction.CREATE -> showCreate = true
            WorkspaceEntryAction.IMPORT -> importLauncher.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
            WorkspaceEntryAction.BACKUPS -> workspacePanel = WorkspacePanel.BACKUPS
            WorkspaceEntryAction.EDIT -> {
                val project = StudioProjectPolicy.active(projects).firstOrNull { it.projectId == request.projectId }
                if (project == null) error = "项目已不存在或不支持编辑，请刷新项目列表"
                else openOverlayEditor(project.projectId)
            }
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
                onServerBackups = { workspacePanel = WorkspacePanel.SERVER_BACKUPS },
            )
            return
        }
        WorkspacePanel.SERVER_BACKUPS -> {
            ServerProjectBackupsScreen(
                store = store,
                projects = projects,
                blocked = {
                    EditorOverlaySessions.status.value != null || ScriptLaunchSessions.status.value != null ||
                        runtimeState.engineState in setOf(RuntimeEngineState.RUNNING, RuntimeEngineState.PAUSED, RuntimeEngineState.STOPPING)
                },
                onRestored = { projects = withContext(Dispatchers.IO) { store.listProjects() } },
                onBack = { workspacePanel = WorkspacePanel.BACKUPS },
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

    // 工具页保持独立，可视化编辑直接叠在项目列表上。
    fun leaveOpenedProject() {
        designingRunnerUi = false
        showingPackager = false
        opened = null
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

    val packageProject = opened?.takeIf { showingPackager }
    if (packageProject != null) {
        ProjectPackageScreen(
            snapshot = packageProject,
            runtimeClient = runtimeClient,
            onBack = ::leaveOpenedProject,
            modifier = modifier,
        )
        return
    }


    run {
        val listedProjects = remember(projects, projectQuery, projectOrder) { orderedStudioProjects(projects, projectQuery, projectOrder) }
        StudioPageBackdrop(modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                WorkspaceHeader(
                    enabled = !loading && busyProjectId == null,
                    query = projectQuery,
                    order = projectOrder,
                    onQueryChange = { projectQuery = it.take(80) },
                    onOrderChange = { projectOrder = it },
                    onCreate = { showCreate = true },
                    onImport = { importLauncher.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) },
                    onBackup = { workspacePanel = WorkspacePanel.BACKUPS },
                    onRefresh = ::refreshProjects,
                )
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentPadding = PaddingValues(top = 8.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(9.dp),
            ) {
            overlayEditorStatus?.let { host -> item {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("悬浮编辑已打开", fontSize = 12.sp, modifier = Modifier.weight(1f))
                    TextButton(onClick = { openOverlayEditor(host.projectId) }) { Text("恢复编辑", fontSize = 11.sp) }
                    TextButton(onClick = { context.startService(android.content.Intent(context, EditorOverlayService::class.java).setAction(EditorOverlayService.ACTION_CLOSE)) }) {
                        Text("保存关闭", fontSize = 11.sp)
                    }
                }
            } }
            scriptLaunchStatus?.let { host -> item {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (host.phase == ScriptLaunchPhase.RUNNING) "脚本运行中 · 停止后回配置" else "脚本配置窗口已打开",
                        fontSize = 12.sp, modifier = Modifier.weight(1f))
                    TextButton(onClick = { openScriptInterface(host.projectId) }) { Text("恢复界面", fontSize = 11.sp) }
                    TextButton(onClick = { context.startService(android.content.Intent(context, ScriptLaunchOverlayService::class.java)
                        .setAction(if (host.phase == ScriptLaunchPhase.STARTING) ScriptLaunchOverlayService.ACTION_CANCEL else ScriptLaunchOverlayService.ACTION_CLOSE)) }) {
                        Text(if (host.phase == ScriptLaunchPhase.STARTING) "取消启动" else "关闭窗口", fontSize = 11.sp)
                    }
                }
            } }
            if (StudioProjectPolicy.historical(projects).isNotEmpty()) {
                item {
                    TextButton(onClick = { showHistoricalProjects = true }, modifier = Modifier.padding(horizontal = 12.dp)) {
                        Text("旧源码项目 (${StudioProjectPolicy.historical(projects).size}) · 保留 / 导出", fontSize = 12.sp)
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

            if (!loading && StudioProjectPolicy.active(projects).isEmpty()) {
                item {
                    ProjectListPlaceholder(false, !loading && busyProjectId == null, { showCreate = true },
                        Modifier.padding(horizontal = 12.dp))
                }
            }

            if (!loading && StudioProjectPolicy.active(projects).isNotEmpty() && projectQuery.isNotBlank() && listedProjects.isEmpty()) item {
                ProjectListPlaceholder(true, true, { projectQuery = "" }, Modifier.padding(horizontal = 12.dp))
            }
            items(listedProjects, key = ProjectSummary::projectId) { project ->
                val expanded = project.projectId in expandedProjectIds
                Box(Modifier.padding(horizontal = 12.dp)) {
                    ProjectRow(
                        project = project,
                        expanded = expanded,
                        enabled = !loading && busyProjectId == null && overlayEditorStatus == null && scriptLaunchStatus == null,
                        editEnabled = editorEntryEnabled(!loading && busyProjectId == null,
                            overlayEditorStatus?.projectId, scriptLaunchStatus?.projectId, project.projectId),
                        busy = busyProjectId == project.projectId,
                        onToggle = {
                            expandedProjectIds = if (expanded) emptySet() else setOf(project.projectId)
                        },
                        onOpen = {
                            // 使用正式可视化编辑状态直接展开弹窗，不切换到旧全屏诊断界面。
                            openOverlayEditor(project.projectId)
                        },
                        onRun = { launchProject(project) },
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
            }
        }
    }

    if (showHistoricalProjects) {
        HistoricalProjectsDialog(
            projects = StudioProjectPolicy.historical(projects),
            enabled = !loading && busyProjectId == null && exportTarget == null,
            message = error ?: notice,
            onDismiss = { showHistoricalProjects = false },
            onExport = { project ->
                error = null
                notice = null
                exportTarget = project
                exportLauncher.launch(backupFileName(project.name))
            },
        )
    }
    if (showCreate) {
        ProjectEditorDialog(
            title = "创建项目",
            initialName = "",
            showResolution = true,
            onDismiss = { showCreate = false },
            onConfirm = { name, width, height ->
                showCreate = false
                runStoreAction { store.createProject(name, StudioProjectPolicy.creationMode, width, height,
                    initialCapabilities = defaultScriptCapabilities()) }
            },
        )
    }
    renameTarget?.let { project ->
        ProjectEditorDialog(
            title = "重命名项目",
            initialName = project.name,
            showResolution = false,
            onDismiss = { renameTarget = null },
            onConfirm = { name, _, _ ->
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
            runtimeClient = runtimeClient,
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

private enum class WorkspacePanel {
    PROJECTS,
    BACKUPS,
    SERVER_BACKUPS,
    LEARNING,
    BACKEND,
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun WorkspaceHeader(
    enabled: Boolean,
    query: String,
    order: ProjectListOrder,
    onQueryChange: (String) -> Unit,
    onOrderChange: (ProjectListOrder) -> Unit,
    onCreate: () -> Unit,
    onImport: () -> Unit,
    onBackup: () -> Unit,
    onRefresh: () -> Unit,
) {
    var sortMenu by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp)) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val columns = if (maxWidth.value / LocalDensity.current.fontScale >= 245f) 4 else 2
            val tools = listOf(
                Triple(R.drawable.studio_add_24, "新建", onCreate), Triple(R.drawable.ic_download_24, "导入", onImport),
                Triple(R.drawable.ic_backup_outline_24, "备份", onBackup), Triple(R.drawable.project_refresh_20, "刷新", onRefresh),
            )
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                tools.chunked(columns).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        row.forEach { (icon, label, action) ->
                            WorkspaceToolbarAction(icon, label, Modifier.weight(1f), enabled, action, prominent = label == "新建")
                        }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(Modifier.weight(1f), color = Color(0xFFEEF0FA), shape = RoundedCornerShape(12.dp),
                border = androidx.compose.foundation.BorderStroke(1.5.dp, Color.White)) {
                Row(Modifier.heightIn(min = 38.dp).padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(painterResource(R.drawable.editor_more_search_24), null, tint = WorkspaceTextSecondary, modifier = Modifier.size(16.dp))
                    BasicTextField(query, onQueryChange, singleLine = true, cursorBrush = SolidColor(ProjectListAccent),
                        textStyle = TextStyle(fontSize = 12.sp, color = WorkspaceTextPrimary),
                        modifier = Modifier.weight(1f).padding(horizontal = 7.dp).semantics { contentDescription = "搜索项目名称" },
                        decorationBox = { input ->
                            Box {
                                if (query.isEmpty()) Text("搜索项目名称", color = WorkspaceTextSecondary, fontSize = 12.sp)
                                input()
                            }
                        })
                    if (query.isNotEmpty()) Icon(painterResource(R.drawable.editor_close_24), "清空搜索", tint = WorkspaceTextSecondary,
                        modifier = Modifier.size(24.dp).clickable { onQueryChange("") }.padding(4.dp))
                }
            }
            Box {
                Surface(onClick = { sortMenu = true }, color = Color.White.copy(alpha = .9f), shape = RoundedCornerShape(12.dp)) {
                    Row(Modifier.heightIn(min = 38.dp).padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(order.label, color = ProjectListAccent, fontSize = 11.sp)
                        Icon(painterResource(R.drawable.editor_chevron_down_16), "项目排序", tint = ProjectListAccent,
                            modifier = Modifier.padding(start = 3.dp).size(12.dp))
                    }
                }
                DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                    ProjectListOrder.entries.forEach { option ->
                        DropdownMenuItem(text = { Text(option.label, fontSize = 12.sp,
                            color = if (order == option) ProjectListAccent else WorkspaceTextPrimary) },
                            onClick = { sortMenu = false; onOrderChange(option) })
                    }
                }
            }
        }
    }
}

@Composable
private fun WorkspaceToolbarAction(
    @DrawableRes icon: Int,
    label: String,
    modifier: Modifier,
    enabled: Boolean,
    onClick: () -> Unit,
    prominent: Boolean = false,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(11.dp),
        color = if (prominent) StudioViolet else Color.White.copy(alpha = .94f),
        shadowElevation = 1.dp,
        onClick = onClick,
        enabled = enabled,
    ) {
        Row(Modifier.then(if (prominent) Modifier.background(StudioBrandGradient) else Modifier)
            .heightIn(min = 38.dp).padding(horizontal = 5.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            val tint = (if (prominent) Color.White else StudioInk).copy(alpha = if (enabled) 1f else .45f)
            Icon(painterResource(icon), null, tint = tint, modifier = Modifier.size(17.dp))
            Text(label, color = tint, fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(start = 4.dp), textAlign = TextAlign.Center)
        }
    }
}

private val ProjectListAccent = StudioViolet
private val ProjectListBackground = StudioPaper
private val WorkspaceAccent = AutoScriptPalette.Accent
private val WorkspaceTextPrimary = AutoScriptPalette.TextPrimary
private val WorkspaceTextSecondary = AutoScriptPalette.TextSecondary
private val WorkspaceBorder = AutoScriptPalette.Border
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
    SETTINGS(R.drawable.editor_settings_24),
    RENAME(R.drawable.editor_edit_24),
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
@OptIn(ExperimentalLayoutApi::class)
internal fun ProjectRow(
    project: ProjectSummary,
    expanded: Boolean,
    enabled: Boolean,
    editEnabled: Boolean = enabled,
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
    val projectClipboard = LocalClipboardManager.current
    val locale = LocalConfiguration.current.locales[0]
    val summaryTime = remember(project.updatedAt, locale) { projectListUpdatedLabel(project.updatedAt, locale) }
    val createdTime = remember(project.createdAt, locale) { formatTime(project.createdAt, locale) }
    val updatedTime = remember(project.updatedAt, locale) { formatTime(project.updatedAt, locale) }
    var idCopied by remember(project.projectId) { mutableStateOf(false) }
    var showFullId by remember(project.projectId) { mutableStateOf(false) }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = if (expanded) Color(0xFFFCFAFF) else Color.White.copy(alpha = .95f),
        border = androidx.compose.foundation.BorderStroke(1.dp, if (expanded) Color(0xFFD9C3FF) else Color.White),
        shadowElevation = if (expanded) 1.dp else 0.dp,
    ) {
        Column(Modifier.animateContentSize(tween(120))) {
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 12.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(Modifier.weight(1f).semantics { contentDescription = if (expanded) "收起项目详情" else "展开项目详情" }
                    .clickable(onClick = onToggle), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(34.dp).background(
                        if (expanded) StudioBrandGradient else Brush.linearGradient(listOf(Color(0xFFE7DFFF), Color(0xFFEBE3FF))),
                        RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
                        WorkspaceIcon(WorkspaceIconKind.CODE_BOX, Modifier.size(21.dp), if (expanded) Color.White else ProjectListAccent)
                    }
                    Column(Modifier.weight(1f).padding(start = 11.dp)) {
                        Text(
                            project.name,
                            maxLines = if (expanded) Int.MAX_VALUE else 2,
                            overflow = TextOverflow.Ellipsis,
                            color = StudioInk,
                            fontSize = 14.sp,
                            lineHeight = 19.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            "${project.designWidth}×${project.designHeight} · $summaryTime",
                            color = StudioMuted,
                            fontSize = 10.sp,
                            lineHeight = 14.sp,
                            modifier = Modifier.padding(top = 2.dp),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Box(Modifier.size(28.dp).background(Color(0xFFF0EFF9), RoundedCornerShape(14.dp))
                    .semantics { contentDescription = if (expanded) "收起项目详情" else "展开项目详情" }
                    .clickable(onClick = onToggle), contentAlignment = Alignment.Center) {
                    Icon(painterResource(if (expanded) R.drawable.editor_more_24 else R.drawable.ic_chevron_right_20),
                        null, tint = StudioMuted, modifier = Modifier.size(17.dp))
                }
            }
            if (expanded) {
                BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp)) {
                    val columns = projectActionColumns(maxWidth.value, LocalDensity.current.fontScale)
                    val actions = listOf(
                        ProjectGridAction(if (busy) "处理中" else "启动", WorkspaceIconKind.PLAY, Color(0xFF00B982), enabled, onRun),
                        ProjectGridAction("编辑", WorkspaceIconKind.EDIT, ProjectListAccent, editEnabled, onOpen),
                        ProjectGridAction("界面", WorkspaceIconKind.UI, StudioInk, enabled, onUi),
                        ProjectGridAction("设置", WorkspaceIconKind.SETTINGS, StudioInk, enabled, onSettings),
                        ProjectGridAction("备份", WorkspaceIconKind.BACKUP, StudioInk, enabled, onExport),
                        ProjectGridAction("重命名", WorkspaceIconKind.RENAME, StudioInk, enabled, onRename),
                        ProjectGridAction("打包", WorkspaceIconKind.PACKAGE, StudioInk, enabled, onPackage),
                        ProjectGridAction("删除", WorkspaceIconKind.DELETE, WorkspaceDanger, enabled, onDelete),
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        actions.chunked(columns).forEach { row ->
                            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                                row.forEach { action ->
                                    ProjectCardAction(action.label, action.icon, action.color, action.enabled,
                                        action.onClick, Modifier.weight(1f))
                                }
                            }
                        }
                    }
                }
            }
            if (expanded) {
                Column(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 6.dp)) {
                    HorizontalDivider(color = Color(0xFFE1DBF0), modifier = Modifier.padding(bottom = 4.dp))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("项目 ID", fontSize = 10.sp, lineHeight = 13.sp, color = WorkspaceTextSecondary)
                        Text(compactProjectId(project.projectId), fontSize = 10.sp, lineHeight = 13.sp,
                            fontFamily = FontFamily.Monospace, color = WorkspaceTextPrimary,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Text(if (showFullId) "收起" else "完整", color = ProjectListAccent, fontSize = 10.sp, lineHeight = 13.sp,
                            modifier = Modifier.semantics { contentDescription = if (showFullId) "收起完整项目ID" else "查看完整项目ID" }
                                .background(StudioLilac, RoundedCornerShape(7.dp))
                                .clickable { showFullId = !showFullId }.padding(horizontal = 8.dp, vertical = 4.dp))
                        Text(if (idCopied) "已复制" else "复制", color = ProjectListAccent, fontSize = 10.sp, lineHeight = 13.sp,
                            modifier = Modifier.semantics { contentDescription = "复制项目ID" }
                                .clickable { projectClipboard.setText(AnnotatedString(project.projectId)); idCopied = true }
                                .background(StudioLilac, RoundedCornerShape(7.dp))
                                .padding(horizontal = 8.dp, vertical = 4.dp))
                    }
                    if (showFullId) {
                        SelectionContainer {
                            Text(project.projectId, fontSize = 10.sp, lineHeight = 13.sp, fontFamily = FontFamily.Monospace,
                                color = WorkspaceTextPrimary, softWrap = true, modifier = Modifier.fillMaxWidth())
                        }
                    }
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(1.dp),
                    ) {
                        Text(
                            "创建 $createdTime",
                            modifier = Modifier.semantics { contentDescription = "创建时间：$createdTime" },
                            fontSize = 10.sp,
                            lineHeight = 13.sp,
                            color = WorkspaceTextSecondary,
                        )
                        Text(
                            "更新 $updatedTime",
                            modifier = Modifier.semantics { contentDescription = "更新时间：$updatedTime" },
                            fontSize = 10.sp,
                            lineHeight = 13.sp,
                            color = WorkspaceTextSecondary,
                        )
                    }
                }
            }
        }
    }
}

private data class ProjectGridAction(val label: String, val icon: WorkspaceIconKind, val color: Color,
    val enabled: Boolean, val onClick: () -> Unit)

private fun projectListUpdatedLabel(timestamp: Long, locale: Locale): String =
    if (timestamp <= 0) "时间未知" else SimpleDateFormat("MM-dd HH:mm", locale).format(Date(timestamp))

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
    StudioConfirmDialog(onDismissRequest = onDismiss, title = { Text("删除项目") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("将删除项目“$projectName”。此操作不能撤销，请先确认已有备份。", fontSize = 13.sp)
                Text("计算验证：${challenge.first} − ${challenge.second} = ?", fontSize = 13.sp, color = WorkspaceAccent)
                BasicTextField(answer, { answer = it.filter(Char::isDigit).take(3); wrong = false }, singleLine = true,
                    textStyle = TextStyle(fontSize = 15.sp, textAlign = TextAlign.Center, color = WorkspaceTextPrimary),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth().border(1.dp, WorkspaceBorder, RoundedCornerShape(3.dp)).padding(10.dp))
                if (wrong) Text("答案不正确，请重新计算", color = WorkspaceDanger, fontSize = 11.sp)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
        confirmButton = { TextButton(onClick = {
            if (answer.toIntOrNull() == challenge.first - challenge.second) onConfirm() else wrong = true
        }) { Text("确认删除", color = WorkspaceDanger) } })
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
    Row(
        modifier = modifier.heightIn(min = 26.dp)
            .background(when (label) {
                "启动", "处理中" -> Color(0xFFE1F5EF)
                "编辑" -> StudioLilac
                "删除" -> Color(0xFFFFEAF0)
                else -> Color(0xFFEFEEF8)
            }, RoundedCornerShape(9.dp))
            .semantics { contentDescription = "项目操作：$label" }
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 5.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        WorkspaceIcon(icon, Modifier.size(13.dp), if (enabled) color else color.copy(alpha = .45f))
        Spacer(Modifier.width(5.dp))
        Text(
            label,
            fontSize = 11.sp,
            lineHeight = 15.sp,
            color = if (enabled) color else color.copy(alpha = .45f),
            softWrap = false,
        )
    }
}

@Composable
private fun ProjectEditorDialog(
    title: String,
    initialName: String,
    showResolution: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String, Int, Int) -> Unit,
) {
    var name by remember(initialName) { mutableStateOf(initialName) }
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
                        ProjectCreationHelp()
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
                            if (showResolution) {
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
                                onConfirm(name.trim(), parts[0].toInt(), parts[1].toInt())
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
private fun ProjectCreationHelp() {
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
        }
    }
}

private fun formatTime(timestamp: Long, locale: Locale = Locale.getDefault()): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", locale).format(Date(timestamp))

private fun backupFileName(projectName: String): String {
    val safeName = projectName.map { character ->
        if (character.isISOControl() || character in "\\/:*?\"<>|") '_' else character
    }.joinToString("").trim().take(80).ifEmpty { "AutoScript项目" }
    return "$safeName.asproject"
}

/** Reads and consumes a Runner-owned one-shot capture without accepting an arbitrary file path. */
internal fun decodeCaptureHandoff(context: android.content.Context, handoff: StudioCaptureHandoff): Bitmap {
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

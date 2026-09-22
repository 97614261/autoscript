package com.autoscript.studio

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
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
import com.autoscript.runtime.client.VisualCompileDiagnostic
import com.autoscript.runtime.client.VisualCompileResult
import com.autoscript.studio.generated.BlockCategory
import com.autoscript.studio.generated.BlockContract
import com.autoscript.studio.generated.BlockPropertyEditor
import com.autoscript.studio.generated.BlockResourceKind
import com.google.gson.JsonParser
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

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
    var showNewFlowDialog by remember(projectId) { mutableStateOf(false) }
    var showVariableManager by remember(projectId) { mutableStateOf(false) }
    var newFlowId by remember(projectId) { mutableStateOf("") }
    var showBlockPicker by remember(projectId) { mutableStateOf(false) }
    var blockPickerAutoSave by remember(projectId) { mutableStateOf(false) }
    var blockQuery by remember(projectId) { mutableStateOf("") }
    var blockCategory by remember(projectId) { mutableStateOf<BlockCategory?>(null) }
    var insertionChildBlockName by remember(projectId) { mutableStateOf<String?>(null) }
    var pendingDockInsertHint by remember(projectId) { mutableStateOf<String?>(null) }
    var dockClipboard by remember(projectId) { mutableStateOf<VisualSubtreeClipboard?>(null) }
    var pendingDockPaste by remember(projectId) { mutableStateOf(false) }
    var pendingDockIndentSlots by remember(projectId) { mutableStateOf<List<String>?>(null) }
    var pendingBlockConfiguration by remember(projectId) { mutableStateOf<VisualPendingBlockInsert?>(null) }
    var editingCallNodeId by remember(projectId) { mutableStateOf<String?>(null) }
    var editingCallTargetId by remember(projectId) { mutableStateOf<String?>(null) }
    var propertyInputs by remember(projectId) { mutableStateOf<Map<String, String>>(emptyMap()) }
    var callArgumentInputs by remember(projectId) { mutableStateOf<Map<String, String>>(emptyMap()) }
    var callArgumentError by remember(projectId) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val sourceFiles = remember(store) { ProjectSourceFiles(store) }
    var sourceTree by remember(projectId) { mutableStateOf(SourceFileTree()) }
    var sourceBusy by remember(projectId) { mutableStateOf(false) }
    var sourceMessage by remember(projectId) { mutableStateOf<String?>(null) }
    val editor = requireNotNull(editors[selectedFlowId])

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
            error = "Flow结构无效，当前只能只读查看"
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
            error = failure.message ?: "Flow保存失败"
            return false
        }
        state.markSaved(submitted)
        editorRevision++
        onSnapshotChanged(saved)
        notice = if (state.isDirty) "已保存提交快照，仍有后续修改" else "Flow $flowId 已安全保存"
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

    fun runProject() {
        if (!canStartAction) return
        if (runtimeState.phase != RuntimeConnectionPhase.CONNECTED) {
            error = "Runner尚未连接"
            return
        }
        action = VisualAction.STARTING
        error = null
        notice = null
        scope.launch {
            if (!saveAllIfNeeded()) {
                action = null
                return@launch
            }
            val compileResult = withContext(Dispatchers.IO) {
                runtimeClient.compileVisualProject(projectId)
            }
            val generationId = showCompileResult(compileResult)
            if (generationId == null) {
                action = null
                return@launch
            }
            val refreshed = runCatching {
                withContext(Dispatchers.IO) { store.openProject(projectId) }
            }.getOrElse { failure ->
                error = failure.message ?: "重新读取项目失败"
                action = null
                return@launch
            }
            onSnapshotChanged(refreshed)
            val plan = runCatching {
                withContext(Dispatchers.IO) {
                    RuntimeProjectPlan.fromVisualSnapshot(refreshed, generationId)
                }
            }.getOrElse { failure ->
                error = failure.message ?: "生成物校验失败"
                action = null
                return@launch
            }
            val started = withContext(Dispatchers.IO) {
                runtimeClient.startProject(
                    projectId = snapshot.manifest.projectId,
                    generatedLuaModule = plan.luaSource,
                    resources = plan.resources,
                    capabilities = plan.capabilities,
                    designWidth = plan.designWidth,
                    designHeight = plan.designHeight,
                    scaleMode = plan.scaleMode,
                )
            }
            if (started) notice = "积木脚本已提交运行"
            action = null
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

    fun pauseOrResumeProject() {
        if (!canPauseResume) return
        val resume = runtimeState.engineState == RuntimeEngineState.PAUSED
        action = VisualAction.CONTROLLING
        error = null
        notice = null
        scope.launch {
            val accepted = withContext(Dispatchers.IO) {
                if (resume) runtimeClient.requestResume() else runtimeClient.requestPause()
            }
            if (!accepted) error = if (resume) "继续请求被拒绝" else "暂停请求被拒绝"
            action = null
        }
    }

    fun createFlow() {
        val requestedId = newFlowId.trim()
        if (requestedId.isEmpty() || runtimeBusy) return
        showNewFlowDialog = false
        action = VisualAction.CREATING_FLOW
        error = null
        scope.launch {
            if (!saveAllIfNeeded()) {
                action = null
                return@launch
            }
            val created = runCatching {
                withContext(Dispatchers.IO) {
                    store.createFlow(
                        projectId,
                        requestedId,
                        snapshot.manifest.flows.map(ProjectFlow::flowId).toSet(),
                    )
                }
            }.getOrElse { failure ->
                error = failure.message ?: "新建 Flow 失败"
                action = null
                return@launch
            }
            val declaration = created.manifest.flows.first { it.flowId == requestedId }
            editors[requestedId] = VisualEditorState.create(
                created.flowSources.getValue(requestedId),
                declaration.rootBlockId,
            )
            selectedFlowId = requestedId
            newFlowId = ""
            onSnapshotChanged(created)
            notice = "Flow $requestedId 已创建"
            action = null
        }
    }

    fun deleteCurrentFlow() {
        if (runtimeBusy || selectedFlowId == snapshot.manifest.entryFlowId || editors.values.any { it.isDirty }) return
        action = VisualAction.DELETING_FLOW
        error = null
        scope.launch {
            val deletedId = selectedFlowId
            val deleted = runCatching {
                withContext(Dispatchers.IO) {
                    store.deleteFlow(
                        projectId,
                        deletedId,
                        snapshot.manifest.flows.map(ProjectFlow::flowId).toSet(),
                    )
                }
            }.getOrElse { failure ->
                error = failure.message ?: "删除 Flow 失败"
                action = null
                return@launch
            }
            editors.remove(deletedId)
            selectedFlowId = requireNotNull(deleted.manifest.entryFlowId)
            onSnapshotChanged(deleted)
            notice = "Flow $deletedId 已删除"
            action = null
        }
    }

    if (showNewFlowDialog) {
        LegacyInputDialog(
            title = "新建 Flow",
            value = newFlowId,
            onValueChange = { newFlowId = it },
            hint = "Flow ID：字母或数字开头，可含 . _ -",
            confirmLabel = "创建",
            confirmEnabled = newFlowId.isNotBlank(),
            maxLength = 64,
            onDismiss = { showNewFlowDialog = false },
            onConfirm = ::createFlow,
        )
    }

    if (showVariableManager) {
        VisualVariableManagerDialog(
            variables = snapshot.manifest.variables,
            currentFlowId = selectedFlowId,
            flows = snapshot.manifest.flows,
            onDismiss = { showVariableManager = false },
            onSave = { variables ->
                scope.launch {
                    runCatching {
                        withContext(Dispatchers.IO) { store.updateVariables(projectId, variables, snapshot.manifest.variables) }
                    }.onSuccess {
                        onSnapshotChanged(it); notice = "变量表已保存"; showVariableManager = false
                    }.onFailure { error = it.message ?: "变量表保存失败" }
                }
            },
        )
    }

    fun insertDockBlock(
        hint: String,
        childSlot: String?,
        position: LegacyInsertPosition = LegacyInsertPosition.BELOW,
    ) {
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
        if (command.startsWith(LegacyFunctionCatalog.LOOP_HINT_PREFIX + "metric:")) {
            val parts = command.removePrefix(LegacyFunctionCatalog.LOOP_HINT_PREFIX).split(':')
            val metric = parts.getOrNull(1)
            val variable = parts.getOrNull(2)
            if (metric !in setOf("count", "elapsed") || variable.isNullOrBlank()) {
                error = "循环参数无效"
                return
            }
            val loopId = editor.nearestAncestorOfKind(kinds = setOf("control.repeat", "control.while"))
            val oldArgs = loopId?.let(editor::nodeArguments)
            if (loopId == null || oldArgs == null) {
                error = "请先选中循环或循环体中的积木，再设置循环${if (metric == "count") "次数" else "时间"}变量"
                return
            }
            val args = oldArgs.deepCopy().apply {
                addProperty(
                    when (metric) {
                        "elapsed" -> "elapsedVariable"
                        "count" -> if (editor.rows.firstOrNull { it.nodeId == loopId }?.kind == "control.repeat") "indexVariable" else "iterationVariable"
                        else -> error("checked above")
                    },
                    variable,
                )
            }
            if (!editor.updateArguments(loopId, args)) {
                error = "无法更新循环参数"
                return
            }
            editorRevision++
            error = null
            notice = "循环${if (metric == "count") "次数" else "时间"}将写入变量：$variable"
            saveCurrent()
            return
        }
        val capabilities = snapshot.manifest.capabilities.toSet()
        val query = legacyDockBlockQuery(hint)
        // 函数库直接给出积木 kind 时不做模糊搜索；能力不足要明确说明而不是静默落到选择器。
        val direct = LegacyFunctionCatalog.blockKindOf(hint)?.let(BlockCatalog::find)
            ?.let { BlockSearchResult(it, it.requiredCapabilities - capabilities) }
        if (direct != null && !direct.isAvailable) {
            error = "${direct.contract.title}需要能力：${direct.missingCapabilities.joinToString()}"
            return
        }
        val matching = direct?.let(::listOf)
            ?: BlockCatalog.search(query, capabilities).filter(BlockSearchResult::isAvailable)
        if (matching.size == 1) {
            val contract = matching.single().contract
            val args = initialBlockArguments(
                contract,
                snapshot.manifest.flows,
                snapshot.manifest.resources,
                selectedFlowId,
            )
            if (args == null) {
                error = "缺少${contract.title}所需的目标 Flow 或项目资源"
                return
            }
            val migratedArgs = legacyDockBlockArguments(contract, hint, args)
            if (contract.kind in setOf("variable.set", "variable.copy", "control.if") && position != LegacyInsertPosition.REPLACE) {
                pendingBlockConfiguration = VisualPendingBlockInsert(contract, migratedArgs, childSlot, position)
                return
            }
            if (position == LegacyInsertPosition.REPLACE) {
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
            if (position == LegacyInsertPosition.LIST_BOTTOM) {
                editor.selectedNodeId = editor.rows.lastOrNull { it.depth == 0 }?.nodeId
            }
            val inserted = editor.insertBlock(contract, migratedArgs, intoChildBlockName = childSlot)
            if (inserted != null && position == LegacyInsertPosition.ABOVE && originalSelection != null) {
                editor.moveSelected(-1)
            }
            if (inserted == null) {
                error = "无法把${contract.title}加入当前位置"
            } else {
                editorRevision++
                error = null
                notice = "已加入：${contract.title}"
                saveCurrent()
            }
            return
        }
        blockQuery = query.takeIf { matching.isNotEmpty() }.orEmpty()
        insertionChildBlockName = childSlot
        blockPickerAutoSave = true
        showBlockPicker = true
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
            editor.pasteSubtree(clipboard, intoChildBlockName = childSlot) != null,
            "已粘贴节点副本",
        )
    }

    fun runDockCommand(command: LegacyDockProgramCommand) {
        if (runtimeBusy || editor.isReadOnly) return
        when (command) {
            LegacyDockProgramCommand.MOVE_UP -> finishDockMutation(editor.moveSelected(-1), "节点已上移")
            LegacyDockProgramCommand.MOVE_DOWN -> finishDockMutation(editor.moveSelected(1), "节点已下移")
            LegacyDockProgramCommand.OUTDENT -> finishDockMutation(editor.outdentSelected(), "节点已减少一级缩进")
            LegacyDockProgramCommand.INDENT -> {
                val selected = nodes.firstOrNull { it.nodeId == editor.selectedNodeId }
                val siblings = nodes.filter { it.blockId == selected?.blockId }.sortedBy { it.orderKey }
                val previous = siblings.getOrNull(siblings.indexOfFirst { it.nodeId == selected?.nodeId } - 1)
                val slots = previous?.nodeId?.let(editor::childBlockNames).orEmpty()
                when (slots.size) {
                    0 -> error = "上一节点不是容器"
                    1 -> finishDockMutation(editor.indentSelected(slots.single()), "节点已缩进")
                    else -> pendingDockIndentSlots = slots
                }
            }
            LegacyDockProgramCommand.COPY -> {
                dockClipboard = editor.copySelectedSubtree()
                if (dockClipboard == null) error = "请先选择节点" else {
                    error = null
                    notice = "已复制整棵子树"
                }
            }
            LegacyDockProgramCommand.CUT -> {
                val copied = editor.copySelectedSubtree()
                if (copied == null) error = "请先选择节点" else {
                    dockClipboard = copied
                    finishDockMutation(editor.deleteSelected(), "已剪切整棵子树")
                }
            }
            LegacyDockProgramCommand.PASTE -> {
                if (dockClipboard == null) error = "剪贴板为空" else {
                    val selected = nodes.firstOrNull { it.nodeId == editor.selectedNodeId }
                    val slots = selected?.nodeId?.let(editor::childBlockNames).orEmpty()
                    if (selected != null && slots.isNotEmpty()) pendingDockPaste = true else pasteDockClipboard(null)
                }
            }
            LegacyDockProgramCommand.UNDO -> finishDockMutation(editor.undo(), "已撤销")
            LegacyDockProgramCommand.REDO -> finishDockMutation(editor.redo(), "已重做")
            LegacyDockProgramCommand.DATA_BACKFILL -> {
                error = null
                notice = "当前没有可回填的调试结果，未修改节点参数"
            }
            LegacyDockProgramCommand.SEARCH,
            LegacyDockProgramCommand.EXPAND_ALL,
            LegacyDockProgramCommand.COLLAPSE_ALL
            -> Unit // handled as view-only state by LegacyScriptDock
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
    fun handlePluginAction(pluginAction: LegacyPluginAction) {
        when (pluginAction) {
            LegacyPluginAction.CHECK, LegacyPluginAction.CHECK_ALL -> compileOnly()
            LegacyPluginAction.UNUSED -> {
                val unused = sourceFiles.unreferencedFlows(snapshot)
                notice = if (unused.isEmpty()) "所有源文件都被调用或是入口" else "未调用源文件：" + unused.joinToString("、") { it.displayName() }
            }
            LegacyPluginAction.TEMPLATE -> notice = "存储为模版属于预留功能，尚未开放"
            LegacyPluginAction.CREATE,
            LegacyPluginAction.DELETE,
            LegacyPluginAction.SAVE_AS,
            LegacyPluginAction.GROUP,
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

    pendingDockInsertHint?.let { hint ->
        val selectedRow = nodes.firstOrNull { it.nodeId == editor.selectedNodeId }
        val childSlots = selectedRow?.nodeId?.let(editor::childBlockNames).orEmpty()
        LegacyOptionDialog(
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
        LegacyOptionDialog(
            title = "选择缩进分支",
            options = slots.map { slot -> slot to childBlockLabel(slot) },
            onDismiss = { pendingDockIndentSlots = null },
            onConfirm = { slot ->
                pendingDockIndentSlots = null
                finishDockMutation(editor.indentSelected(slot), "节点已缩进到${childBlockLabel(slot)}")
            },
        )
    }

    if (pendingDockPaste) {
        val selected = nodes.firstOrNull { it.nodeId == editor.selectedNodeId }
        val slots = selected?.nodeId?.let(editor::childBlockNames).orEmpty()
        LegacyOptionDialog(
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
        VisualVariableConditionDialog(
            contract = pending.contract,
            inputs = conditionPropertyTexts(pending.contract, pending.arguments),
            knownVariables = visualKnownVariables(editor, snapshot.manifest.variables, selectedFlowId),
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
                if (pending.position == LegacyInsertPosition.LIST_BOTTOM) {
                    editor.selectedNodeId = editor.rows.lastOrNull { it.depth == 0 }?.nodeId
                }
                val inserted = editor.insertBlock(pending.contract, configured, intoChildBlockName = pending.childSlot)
                if (inserted != null && pending.position == LegacyInsertPosition.ABOVE) editor.moveSelected(-1)
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

    // 顶栏“添加积木”与悬浮面板“函数”走同一个函数库弹窗（service_tk_functionui 版式），
    // 不再用 Material 默认的 AlertDialog；能力不足的积木在库里直接禁用并标出缺什么。
    if (showBlockPicker) {
        fun closePicker() {
            showBlockPicker = false
            blockPickerAutoSave = false
            insertionChildBlockName = null
        }
        LegacyFunctionLibrary(
            groups = LegacyFunctionCatalog.visualGroups(),
            capabilities = snapshot.manifest.capabilities.toSet(),
            onDismiss = ::closePicker,
            onInsert = { hint ->
                val contract = LegacyFunctionCatalog.blockKindOf(hint)?.let(BlockCatalog::find)
                if (contract == null) {
                    error = "未找到对应积木"
                } else {
                    val args = initialBlockArguments(contract, snapshot.manifest.flows, snapshot.manifest.resources, selectedFlowId)
                    if (args == null) {
                        error = "缺少积木所需的目标 Flow 或项目资源"
                    } else if (contract.kind in setOf("variable.set", "variable.copy", "control.if")) {
                        // The library is another insertion entry point: never silently add a
                        // placeholder `value == true` condition from here.
                        pendingBlockConfiguration = VisualPendingBlockInsert(
                            contract = contract,
                            arguments = args,
                            childSlot = insertionChildBlockName,
                            position = LegacyInsertPosition.BELOW,
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
        val targets = snapshot.manifest.flows.filter { it.flowId != selectedFlowId }
        val target = targets.firstOrNull { it.flowId == editingCallTargetId }
        val isVariableOrCondition = contract?.kind in setOf("variable.set", "variable.copy", "control.if")
        if (isVariableOrCondition && contract != null) {
            VisualVariableConditionDialog(
                contract = contract,
                inputs = propertyInputs,
                knownVariables = visualKnownVariables(editor, snapshot.manifest.variables, selectedFlowId),
                onDismiss = { editingCallNodeId = null },
                onConfirm = { inputs ->
                    val parsed = parseBlockArguments(contract, inputs, target, callArgumentInputs, snapshot.manifest.resources)
                    if (parsed.second != null) {
                        parsed.second
                    } else if (editor.updateArguments(nodeId, requireNotNull(parsed.first))) {
                        editorRevision++
                        editingCallNodeId = null
                        saveCurrent()
                        null
                    } else "无法保存积木参数"
                },
            )
        } else AlertDialog(
            onDismissRequest = { editingCallNodeId = null },
            title = { Text(contract?.let { "${it.title}参数" } ?: "积木参数") },
            text = {
                Column {
                    contract?.properties.orEmpty().forEach { property ->
                        when (property.editor) {
                            BlockPropertyEditor.FLOW_REFERENCE -> {
                                Text(property.label, style = MaterialTheme.typography.labelMedium)
                                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                                    targets.forEach { candidate ->
                                        TextButton(
                                            onClick = {
                                                editingCallTargetId = candidate.flowId
                                                propertyInputs = propertyInputs + (property.path to candidate.flowId)
                                                callArgumentInputs = defaultFlowCallArgumentTexts(candidate)
                                                callArgumentError = null
                                            },
                                            contentPadding = PaddingValues(horizontal = 7.dp, vertical = 0.dp),
                                        ) {
                                            Text(if (candidate.flowId == editingCallTargetId) "● ${candidate.flowId}" else candidate.flowId)
                                        }
                                    }
                                }
                            }
                            BlockPropertyEditor.FLOW_ARGUMENTS -> target?.params.orEmpty()
                                .forEach { parameter ->
                                    val name = parameter.get("name")?.asString ?: return@forEach
                                    val type = parameter.get("type")?.asString.orEmpty()
                                    OutlinedTextField(
                                        value = callArgumentInputs[name].orEmpty(),
                                        onValueChange = {
                                            callArgumentInputs = callArgumentInputs + (name to it)
                                        },
                                        singleLine = true,
                                        label = { Text("$name · $type") },
                                    )
                                }
                            BlockPropertyEditor.BOOLEAN -> {
                                Text(property.label, style = MaterialTheme.typography.labelMedium)
                                Row {
                                    listOf("true" to "是", "false" to "否").forEach { (value, label) ->
                                        TextButton(
                                            onClick = {
                                                propertyInputs = propertyInputs + (property.path to value)
                                            },
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                        ) {
                                            Text(if (propertyInputs[property.path] == value) "● $label" else label)
                                        }
                                    }
                                }
                            }
                            BlockPropertyEditor.ENUM,
                            BlockPropertyEditor.INTEGER_ENUM -> {
                                Text(property.label, style = MaterialTheme.typography.labelMedium)
                                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                                    property.choices.forEach { choice ->
                                        TextButton(
                                            onClick = {
                                                propertyInputs = propertyInputs + (property.path to choice)
                                            },
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                        ) {
                                            Text(if (propertyInputs[property.path] == choice) "● $choice" else choice)
                                        }
                                    }
                                }
                            }
                            BlockPropertyEditor.RESOURCE -> {
                                val paths = resourcePaths(
                                    snapshot.manifest.resources,
                                    property.resourceKind,
                                )
                                Text(property.label, style = MaterialTheme.typography.labelMedium)
                                if (paths.isEmpty()) {
                                    Text("项目中没有对应资源", color = MaterialTheme.colorScheme.error)
                                } else {
                                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                                        paths.forEach { path ->
                                            TextButton(
                                                onClick = {
                                                    propertyInputs = propertyInputs + (property.path to path)
                                                },
                                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                            ) {
                                                Text(if (propertyInputs[property.path] == path) "● $path" else path)
                                            }
                                        }
                                    }
                                }
                            }
                            else -> OutlinedTextField(
                                value = propertyInputs[property.path].orEmpty(),
                                onValueChange = {
                                    propertyInputs = propertyInputs + (property.path to it)
                                },
                                singleLine = true,
                                label = { Text(property.label) },
                                supportingText = {
                                    Text(property.editor.name.lowercase())
                                },
                            )
                        }
                    }
                    if (contract?.properties.isNullOrEmpty()) {
                        Text("这个积木没有可编辑参数")
                    }
                    callArgumentError?.let {
                        Text(it, color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val selectedContract = contract ?: return@TextButton
                        val parsed = parseBlockArguments(
                            selectedContract,
                            propertyInputs,
                            target,
                            callArgumentInputs,
                            snapshot.manifest.resources,
                        )
                        if (parsed.second != null) {
                            callArgumentError = parsed.second
                        } else {
                            if (editor.updateArguments(nodeId, requireNotNull(parsed.first))) {
                                editorRevision++
                                editingCallNodeId = null
                                saveCurrent()
                            }
                        }
                    },
                ) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { editingCallNodeId = null }) { Text("取消") }
            },
        )
    }

    BackHandler(enabled = active, onBack = onExit)

    Box(modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 4.dp)) {
        Surface(modifier = Modifier.fillMaxWidth(), color = AutoScriptPalette.VisualEditor.PanelBackground, tonalElevation = 0.dp) {
            Column {
                // 顶栏对齐 `tk_bjck`：40dp、返回图标 + 面包屑式标题；其余操作行保持原有按钮。
                Row(
                    modifier = Modifier.fillMaxWidth().height(40.dp).background(AutoScriptPalette.VisualEditor.ToolbarBackground).padding(horizontal = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                        Icon(
                            painterResource(R.drawable.visual_back_24),
                            contentDescription = "返回",
                            tint = AutoScriptPalette.VisualEditor.ToolbarIcon,
                            modifier = Modifier.size(36.dp).clickable(onClick = onExit).padding(7.dp),
                        )
                        Column(Modifier.weight(1f)) {
                            Text(
                                "${snapshot.manifest.name} > ${snapshot.manifest.flows.firstOrNull { it.flowId == selectedFlowId }?.displayName() ?: selectedFlowId}",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = AutoScriptPalette.VisualEditor.TextPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                "可视化积木 · ${snapshot.manifest.flows.size} Flow · ${nodes.size} 节点",
                                fontSize = 8.sp,
                                color = AutoScriptPalette.VisualEditor.TextSecondary,
                                maxLines = 1,
                            )
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ProjectSettingsButton(
                            snapshot = snapshot,
                            store = store,
                            enabled = canEditProjectSettings,
                            onSnapshotChanged = onSnapshotChanged,
                        )
                        TextButton(
                            onClick = ::compileOnly,
                            enabled = canStartAction,
                            contentPadding = PaddingValues(horizontal = 7.dp, vertical = 0.dp),
                        ) { Text(if (action == VisualAction.COMPILING) "编译中…" else "编译") }
                    }
                }
                HorizontalDivider()
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(
                        onClick = ::runProject,
                        enabled = canStartAction,
                        contentPadding = PaddingValues(horizontal = 7.dp, vertical = 0.dp),
                    ) { Text(if (action == VisualAction.STARTING) "启动中…" else "运行") }
                    TextButton(
                        onClick = ::pauseOrResumeProject,
                        enabled = canPauseResume,
                        contentPadding = PaddingValues(horizontal = 7.dp, vertical = 0.dp),
                    ) {
                        Text(if (runtimeState.engineState == RuntimeEngineState.PAUSED) "继续" else "暂停")
                    }
                    TextButton(
                        onClick = ::stopProject,
                        enabled = canStop,
                        contentPadding = PaddingValues(horizontal = 7.dp, vertical = 0.dp),
                    ) { Text("停止") }
                    TextButton(
                        onClick = {
                            insertionChildBlockName = null
                            showBlockPicker = true
                        },
                        enabled = !runtimeBusy && !editor.isReadOnly,
                        contentPadding = PaddingValues(horizontal = 7.dp, vertical = 0.dp),
                    ) { Text("新增积木") }
                    val callTargets = snapshot.manifest.flows.filter { it.flowId != selectedFlowId }
                    TextButton(
                        onClick = {
                            editor.deleteSelected()
                            editorRevision++
                        },
                        enabled = !runtimeBusy && !editor.isReadOnly && editor.selectedNodeId != null,
                        contentPadding = PaddingValues(horizontal = 7.dp, vertical = 0.dp),
                    ) { Text("删除") }
                    TextButton(
                        onClick = {
                            editor.moveSelected(-1)
                            editorRevision++
                        },
                        enabled = !runtimeBusy && !editor.isReadOnly && editor.selectedNodeId != null,
                        contentPadding = PaddingValues(horizontal = 7.dp, vertical = 0.dp),
                    ) { Text("上移") }
                    TextButton(
                        onClick = {
                            editor.moveSelected(1)
                            editorRevision++
                        },
                        enabled = !runtimeBusy && !editor.isReadOnly && editor.selectedNodeId != null,
                        contentPadding = PaddingValues(horizontal = 7.dp, vertical = 0.dp),
                    ) { Text("下移") }
                    val selectedRow = nodes.firstOrNull { it.nodeId == editor.selectedNodeId }
                    val selectedContract = selectedRow?.kind?.let(BlockCatalog::find)
                    val selectedChildBlocks = selectedRow?.nodeId?.let(editor::childBlockNames).orEmpty()
                    if (selectedContract?.kind == "control.if") {
                        TextButton(
                            onClick = { if (editor.addElseIfBranch()) editorRevision++ },
                            enabled = !runtimeBusy && !editor.isReadOnly,
                            contentPadding = PaddingValues(horizontal = 7.dp, vertical = 0.dp),
                        ) { Text("＋否则如果") }
                        TextButton(
                            onClick = { if (editor.removeLastElseIfBranch()) editorRevision++ },
                            enabled = !runtimeBusy && !editor.isReadOnly && editor.elseIfBranchCount(selectedRow.nodeId) > 0,
                            contentPadding = PaddingValues(horizontal = 7.dp, vertical = 0.dp),
                        ) { Text("－否则如果") }
                    }
                    selectedChildBlocks.forEach { childBlockName ->
                        TextButton(
                            onClick = {
                                insertionChildBlockName = childBlockName
                                showBlockPicker = true
                            },
                            enabled = !runtimeBusy && !editor.isReadOnly,
                            contentPadding = PaddingValues(horizontal = 7.dp, vertical = 0.dp),
                        ) { Text("${childBlockLabel(childBlockName)}内新增") }
                    }
                    TextButton(
                        onClick = {
                            val selectedId = editor.selectedNodeId ?: return@TextButton
                            val contract = selectedContract ?: return@TextButton
                            val args = editor.nodeArguments(selectedId) ?: JsonObject()
                            val currentTarget = args.get("targetFlowId")?.asString
                            val target = callTargets.firstOrNull { it.flowId == currentTarget }
                                ?: callTargets.firstOrNull()
                            editingCallNodeId = selectedId
                            editingCallTargetId = target?.flowId
                            propertyInputs = conditionPropertyTexts(contract, args)
                            val existing = args.getAsJsonObject("arguments")
                            callArgumentInputs = target?.let { flowCallArgumentTexts(it, existing) }.orEmpty()
                            callArgumentError = null
                        },
                        enabled = !runtimeBusy &&
                            selectedContract?.properties?.isNotEmpty() == true &&
                            (!selectedContract.properties.any {
                                it.editor == BlockPropertyEditor.FLOW_REFERENCE
                            } || callTargets.isNotEmpty()),
                        contentPadding = PaddingValues(horizontal = 7.dp, vertical = 0.dp),
                    ) { Text("参数") }
                    TextButton(
                        onClick = {
                            editor.undo()
                            editorRevision++
                        },
                        enabled = !runtimeBusy && editor.canUndo,
                        contentPadding = PaddingValues(horizontal = 7.dp, vertical = 0.dp),
                    ) { Text("撤销") }
                    TextButton(
                        onClick = {
                            editor.redo()
                            editorRevision++
                        },
                        enabled = !runtimeBusy && editor.canRedo,
                        contentPadding = PaddingValues(horizontal = 7.dp, vertical = 0.dp),
                    ) { Text("重做") }
                    TextButton(
                        onClick = ::saveCurrent,
                        enabled = !runtimeBusy && editor.isDirty && !editor.isReadOnly,
                        contentPadding = PaddingValues(horizontal = 7.dp, vertical = 0.dp),
                    ) { Text(if (action == VisualAction.SAVING) "保存中…" else "保存") }
                }
                HorizontalDivider()
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    snapshot.manifest.flows.forEach { flow ->
                        TextButton(
                            onClick = {
                                selectedFlowId = flow.flowId
                                error = null
                                diagnostic = null
                            },
                            contentPadding = PaddingValues(horizontal = 9.dp, vertical = 0.dp),
                        ) {
                            Text(
                                if (flow.flowId == selectedFlowId) "● ${flow.displayName()}" else flow.displayName(),
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                    }
                    TextButton(
                        onClick = { showVariableManager = true },
                        enabled = !runtimeBusy,
                        contentPadding = PaddingValues(horizontal = 9.dp, vertical = 0.dp),
                    ) { Text("变量") }
                    TextButton(
                        onClick = { showNewFlowDialog = true },
                        enabled = !runtimeBusy,
                        contentPadding = PaddingValues(horizontal = 9.dp, vertical = 0.dp),
                    ) { Text("＋Flow") }
                    TextButton(
                        onClick = ::deleteCurrentFlow,
                        enabled = !runtimeBusy &&
                            selectedFlowId != snapshot.manifest.entryFlowId &&
                            editors.values.none { it.isDirty },
                        contentPadding = PaddingValues(horizontal = 9.dp, vertical = 0.dp),
                    ) { Text("删Flow") }
                }
            }
        }

        error?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 3.dp),
            )
        }
        if (error == null) {
            runtimeState.message?.let { message ->
                Text(
                    message,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 3.dp),
                )
            }
        }
        notice?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 3.dp),
            )
        }
        Text(
            "Runner ${runtimeState.engineState} · ${if (editor.isReadOnly) "只读：结构损坏" else if (editor.isDirty) "未保存" else "已保存"} · 结构按 blockId/parentId/orderKey 投影",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 3.dp),
        )

        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(vertical = 3.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(nodes, key = VisualNodeRow::nodeId) { node ->
                val selected = diagnostic?.nodeId == node.nodeId || editor.selectedNodeId == node.nodeId
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = (node.depth.coerceAtMost(8) * 12).dp)
                        .clickable {
                            editor.selectedNodeId = node.nodeId
                            editorRevision++
                        }
                        .then(
                            if (selected) Modifier.border(1.dp, MaterialTheme.colorScheme.error)
                            else Modifier,
                        ),
                ) {
                    Column(Modifier.padding(horizontal = 9.dp, vertical = 6.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                buildString {
                                    node.childSlot?.let { append(childBlockLabel(it)).append(" · ") }
                                    append(BlockCatalog.find(node.kind)?.title ?: node.kind)
                                },
                                style = MaterialTheme.typography.titleSmall,
                            )
                            Text(
                                node.orderKey,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            "${node.kind} · node ${node.nodeId} · block ${node.blockId}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
        val projectFiles = remember(snapshot) { projectFileCatalog(snapshot) }
        LegacyScriptDock(
            projectName = snapshot.manifest.name,
            onRun = ::runProject,
            running = canStop,
            onStop = ::stopProject,
            consoleLines = consoleLines,
            sourceName = snapshot.manifest.flows.firstOrNull { it.flowId == selectedFlowId }?.displayName() ?: selectedFlowId,
            sourceTree = sourceTree,
            currentFlowId = selectedFlowId,
            sourceBusy = sourceBusy,
            sourceMessage = sourceMessage,
            onSourceAction = ::handleSourceAction,
            onManageVariables = { showVariableManager = true },
            availableVariables = visualKnownVariables(editor, snapshot.manifest.variables, selectedFlowId),
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
            programNodes = nodes.map { node ->
                val contract = BlockCatalog.find(node.kind)
                LegacyDockProgramNode(
                    nodeId = node.nodeId,
                    label = buildString {
                        node.childSlot?.let { append('[').append(childBlockLabel(it)).append("] ") }
                        append(legacyDockProgramLabel(contract, editor.nodeArguments(node.nodeId), node.kind))
                    },
                    kind = node.kind,
                    depth = node.depth,
                    childSlots = editor.childBlockNames(node.nodeId),
                )
            },
            programSelectedNodeId = editor.selectedNodeId,
            editingEnabled = !runtimeBusy && !editor.isReadOnly,
            onProgramNodeSelected = { nodeId ->
                editor.selectedNodeId = nodeId
                editorRevision++
            },
            onProgramNodeDeleted = {
                if (!runtimeBusy && !editor.isReadOnly && editor.deleteSelected()) {
                    editorRevision++
                    saveCurrent()
                }
            },
            onProgramNodeEdited = {
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
                }
            },
            onProgramCommand = ::runDockCommand,
            onStep = { notice = "单步运行需要 Runtime 调试协议，当前版本未开放，未执行脚本" },
            onInsertPositioned = { legacyHint, position ->
                val selectedRow = nodes.firstOrNull { it.nodeId == editor.selectedNodeId }
                val childSlots = selectedRow?.nodeId?.let(editor::childBlockNames).orEmpty()
                when {
                    position == LegacyInsertPosition.INSIDE && childSlots.isEmpty() -> error = "当前选择行不能加入内部"
                    position == LegacyInsertPosition.INSIDE -> insertDockBlock(legacyHint, childSlots.first(), position)
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
        line.startsWith(LegacyFunctionCatalog.LOOP_HINT_PREFIX + "repeat:") -> "重复次数"
        line.startsWith(LegacyFunctionCatalog.LOOP_HINT_PREFIX) -> "条件循环"
        line.contains("Onnx", ignoreCase = true) -> "ONNX OCR"
        line.contains("findImage", ignoreCase = true) -> "区域找图"
        line.contains("findColor", ignoreCase = true) -> "区域找色"
        line.contains("findText", ignoreCase = true) -> "字库识字"
        line.startsWith("if ") -> "如果"
        line.startsWith("while ") || line.startsWith("repeat") -> "条件循环"
        line.startsWith("for ") -> "重复次数"
        line.contains("Input.tap", ignoreCase = true) -> "点击"
        line.contains("Input.swipe", ignoreCase = true) -> "滑动"
        line.contains("sleep", ignoreCase = true) -> "等待"
        // 调试面板输出的是标准 Lua `Log.*` 调用；在可视化项目中它必须落成
        // `task.log` 积木，不能按未知 Lua 片段处理。
        line.contains("Log.", ignoreCase = true) -> "输出日志"
        line.contains("Capture.", ignoreCase = true) -> "截图"
        line.contains("Runtime.setParameter", ignoreCase = true) -> "设置变量"
        line.contains("Runtime.getParameter", ignoreCase = true) -> "读取变量"
        else -> line.substringBefore('(').substringBefore('\n').trim()
    }
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
    val callArguments = legacyLuaCallArguments(snippet)
    fun integer(index: Int): Long? = callArguments.getOrNull(index)?.trim()?.toLongOrNull()
    fun decimal(index: Int): Double? = callArguments.getOrNull(index)?.trim()?.toDoubleOrNull()
        ?.takeIf(Double::isFinite)
    fun quoted(index: Int): String? = callArguments.getOrNull(index)?.trim()?.let(::legacyLuaString)

    when (contract.kind) {
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
        "task.sleep" -> integer(0)?.takeIf { it >= 0 }?.let { addProperty("milliseconds", it) }
        "task.log" -> {
            Regex("Log\\.(info|warn|error)", RegexOption.IGNORE_CASE)
                .find(snippet)?.groupValues?.getOrNull(1)?.lowercase()?.let { addProperty("level", it) }
            quoted(0)?.let { addProperty("message", it) }
        }
        "control.repeat" -> {
            val marker = snippet.lineSequence().firstOrNull()?.trim().orEmpty()
            if (marker.startsWith(LegacyFunctionCatalog.LOOP_HINT_PREFIX + "repeat:")) {
                val value = marker.removePrefix(LegacyFunctionCatalog.LOOP_HINT_PREFIX + "repeat:")
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
            if (contract.kind == "control.while" && marker.startsWith(LegacyFunctionCatalog.LOOP_HINT_PREFIX)) {
                addProperty("always", true)
                addProperty("variable", "loopEnabled")
                addProperty("operator", "equals")
                addProperty("value", true)
                when {
                    marker.startsWith(LegacyFunctionCatalog.LOOP_HINT_PREFIX + "timed:fixed:") -> marker
                        .substringAfterLast(':').toLongOrNull()?.takeIf { it in 1..86_400_000 }
                        ?.let { addProperty("durationMs", it) }
                    marker.startsWith(LegacyFunctionCatalog.LOOP_HINT_PREFIX + "timed:variable:") -> marker
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
    CREATING_FLOW,
    DELETING_FLOW,
    COMPILING,
    STARTING,
    CONTROLLING,
    STOPPING,
}

private data class VisualPendingBlockInsert(
    val contract: BlockContract,
    val arguments: JsonObject,
    val childSlot: String?,
    val position: LegacyInsertPosition,
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
): JsonObject? {
    val target = flows.firstOrNull { it.flowId != currentFlowId }
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
                resourcePaths(resources, property.resourceKind).firstOrNull() ?: return null,
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
    return arguments
}

/** Variables that are already meaningful in this Flow, offered by the variable/condition editor. */
private fun visualKnownVariables(
    editor: VisualEditorState,
    declared: List<ProjectVariable>,
    currentFlowId: String,
): List<String> = buildSet {
    declared.filter { it.scope == ProjectVariableScope.GLOBAL || it.flowId == currentFlowId }
        .forEach { add(it.name) }
    editor.rows.forEach { row ->
        val args = editor.nodeArguments(row.nodeId) ?: return@forEach
        when (row.kind) {
            "variable.set" -> args.get("name")?.takeIf { it.isJsonPrimitive }?.asString?.let(::add)
            "variable.copy" -> listOf("sourceName", "name").forEach { key ->
                args.get(key)?.takeIf { it.isJsonPrimitive }?.asString?.let(::add)
            }
            else -> args.entrySet().forEach { (key, value) ->
                if ((key == "variable" || key.endsWith("Variable")) && value.isJsonPrimitive) add(value.asString)
            }
        }
    }
}.filter { it.matches(Regex("[A-Za-z_][A-Za-z0-9_]{0,63}")) }.sorted()

@Composable
internal fun VisualVariableManagerDialog(
    variables: List<ProjectVariable>,
    currentFlowId: String,
    flows: List<ProjectFlow>,
    onDismiss: () -> Unit,
    onSave: (List<ProjectVariable>) -> Unit,
    allowFlowScope: Boolean = true,
) {
    var draft by remember(variables) { mutableStateOf(variables) }
    var name by remember { mutableStateOf("") }
    var scope by remember { mutableStateOf(if (allowFlowScope) ProjectVariableScope.FLOW else ProjectVariableScope.GLOBAL) }
    var type by remember { mutableStateOf(ProjectVariableType.INTEGER) }
    var editing by remember { mutableStateOf<ProjectVariable?>(null) }
    var validationMessage by remember { mutableStateOf<String?>(null) }
    val visibleVariables = draft.filter { variable ->
        variable.scope == scope && (scope == ProjectVariableScope.GLOBAL || variable.flowId == currentFlowId)
    }

    fun resetEditor() {
        name = ""
        type = ProjectVariableType.INTEGER
        editing = null
        validationMessage = null
    }

    fun saveDraftVariable() {
        val normalized = name.trim()
        if (!normalized.matches(Regex("[A-Za-z_][A-Za-z0-9_]{0,63}"))) {
            validationMessage = "变量名需以字母或下划线开头，只能包含字母、数字和下划线"
            return
        }
        val flowId = if (scope == ProjectVariableScope.FLOW) currentFlowId else null
        val replacement = ProjectVariable(normalized, scope, flowId, type)
        if (draft.any { it != editing && it.scope == replacement.scope && it.flowId == replacement.flowId && it.name == replacement.name }) {
            validationMessage = "同一作用域内已存在变量：$normalized"
            return
        }
        draft = editing?.let { old -> draft.map { if (it == old) replacement else it } } ?: draft + replacement
        resetEditor()
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth(.92f).heightIn(max = 560.dp).widthIn(max = 540.dp), color = androidx.compose.ui.graphics.Color.White, shape = RoundedCornerShape(3.dp), shadowElevation = 10.dp) {
            Column {
                Text("变量管理", color = AutoScriptPalette.Accent, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.fillMaxWidth().padding(14.dp))
                HorizontalDivider(color = AutoScriptPalette.Divider)
                Row(Modifier.heightIn(min = 360.dp, max = 460.dp)) {
                    // 易编的变量选择页用左列切换作用域。Flow 在这里就是当前正在编辑的流程，
                    // 因而“局部变量”不会错误地泄漏到其它 Flow。
                    Column(Modifier.weight(.27f).fillMaxSize().border(1.dp, AutoScriptPalette.Divider)) {
                        if (allowFlowScope) {
                            VariableScopeTab("局部变量", scope == ProjectVariableScope.FLOW) {
                                scope = ProjectVariableScope.FLOW
                                resetEditor()
                            }
                        }
                        VariableScopeTab("全局变量", scope == ProjectVariableScope.GLOBAL) {
                            scope = ProjectVariableScope.GLOBAL
                            resetEditor()
                        }
                    }
                    Column(Modifier.weight(.73f).fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            if (editing == null) "添加${if (scope == ProjectVariableScope.GLOBAL) "全局" else "局部"}变量" else "编辑变量",
                            color = AutoScriptPalette.Accent,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        if (scope == ProjectVariableScope.FLOW) {
                            Text("当前 Flow：$currentFlowId", color = AutoScriptPalette.TextSecondary, fontSize = 10.sp)
                        }
                        OutlinedTextField(name, { name = it; validationMessage = null }, label = { Text("变量名") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        Row(Modifier.fillMaxWidth()) {
                            ProjectVariableType.entries.forEach { candidate ->
                                Text(
                                    projectVariableTypeLabel(candidate),
                                    color = if (type == candidate) AutoScriptPalette.Accent else AutoScriptPalette.TextSecondary,
                                    fontSize = 10.sp,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.weight(1f).border(1.dp, if (type == candidate) AutoScriptPalette.Accent else AutoScriptPalette.Divider, RoundedCornerShape(2.dp)).clickable { type = candidate }.padding(vertical = 7.dp),
                                )
                            }
                        }
                        validationMessage?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 10.sp) }
                        Text(if (editing == null) "＋ 添加变量" else "保存修改", color = AutoScriptPalette.Accent, fontSize = 12.sp, modifier = Modifier.clickable { saveDraftVariable() }.padding(vertical = 4.dp))
                        if (editing != null) Text("取消编辑", color = AutoScriptPalette.TextSecondary, fontSize = 11.sp, modifier = Modifier.clickable { resetEditor() })
                        HorizontalDivider(color = AutoScriptPalette.Divider)
                        if (visibleVariables.isEmpty()) {
                            Text("暂无${if (scope == ProjectVariableScope.GLOBAL) "全局" else "局部"}变量", color = AutoScriptPalette.TextSecondary, fontSize = 11.sp, modifier = Modifier.padding(vertical = 10.dp))
                        }
                        visibleVariables.forEach { variable ->
                            Row(Modifier.fillMaxWidth().border(1.dp, AutoScriptPalette.Divider, RoundedCornerShape(3.dp)).clickable {
                                editing = variable
                                name = variable.name
                                type = variable.type
                                validationMessage = null
                            }.padding(horizontal = 9.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("${projectVariableTypeLabel(variable.type)} · ${variable.name}", fontSize = 11.sp, modifier = Modifier.weight(1f))
                                Text("删除", color = MaterialTheme.colorScheme.error, fontSize = 11.sp, modifier = Modifier.clickable {
                                    draft = draft - variable
                                    if (editing == variable) resetEditor()
                                }.padding(4.dp))
                            }
                        }
                    }
                }
                HorizontalDivider(color = AutoScriptPalette.Divider)
                Row(Modifier.fillMaxWidth().height(42.dp)) { Text("取消", textAlign = TextAlign.Center, modifier = Modifier.weight(1f).fillMaxSize().clickable(onClick = onDismiss).wrapContentSize(Alignment.Center)); Text("确定", color = AutoScriptPalette.Accent, textAlign = TextAlign.Center, modifier = Modifier.weight(1f).fillMaxSize().clickable { onSave(draft) }.wrapContentSize(Alignment.Center)) }
            }
        }
    }
}

@Composable
private fun VariableScopeTab(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        label,
        color = if (selected) AutoScriptPalette.Accent else AutoScriptPalette.TextPrimary,
        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        fontSize = 13.sp,
        modifier = Modifier.fillMaxWidth().background(if (selected) AutoScriptPalette.Accent.copy(alpha = .08f) else androidx.compose.ui.graphics.Color.Transparent).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 13.dp),
    )
}

private fun projectVariableTypeLabel(type: ProjectVariableType): String = when (type) {
    ProjectVariableType.INTEGER -> "整数"
    ProjectVariableType.NUMBER -> "浮点"
    ProjectVariableType.STRING -> "字符"
    ProjectVariableType.IMAGE -> "图像"
}

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
    onDismiss: () -> Unit,
    onConfirm: (Map<String, String>) -> String?,
) {
    var values by remember(contract.kind, inputs) { mutableStateOf(inputs) }
    var elseIfBranches by remember(contract.kind, inputs) {
        mutableStateOf(parseElseIfDrafts(inputs["elseIf"]))
    }
    var validationError by remember(contract.kind, inputs) { mutableStateOf<String?>(null) }
    val title = when (contract.kind) {
        "control.if" -> "如果"
        "variable.set" -> "设置变量"
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
                    Text("确定", textAlign = TextAlign.Center, color = AutoScriptPalette.Accent, fontSize = 13.sp, modifier = Modifier.weight(1f).fillMaxSize().clickable {
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
    onDismiss: () -> Unit,
    onConfirm: (JsonObject) -> Unit,
) {
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
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${contract.title}参数") },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 430.dp).verticalScroll(rememberScrollState())) {
                contract.properties.forEach { property ->
                    when (property.editor) {
                        BlockPropertyEditor.FLOW_REFERENCE -> {
                            Text(property.label, style = MaterialTheme.typography.labelMedium)
                            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                                targets.forEach { candidate ->
                                    TextButton(onClick = {
                                        targetFlowId = candidate.flowId
                                        inputs = inputs + (property.path to candidate.flowId)
                                        flowInputs = defaultFlowCallArgumentTexts(candidate)
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
                        else -> OutlinedTextField(
                            value = inputs[property.path].orEmpty(),
                            onValueChange = { inputs = inputs + (property.path to it) },
                            label = { Text(property.label) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                validationError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val parsed = parseBlockArguments(contract, inputs, target, flowInputs, resources)
                if (parsed.first == null) validationError = parsed.second ?: "参数无效"
                else onConfirm(requireNotNull(parsed.first))
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
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

private fun blockPropertyTexts(
    contract: BlockContract,
    arguments: JsonObject,
): Map<String, String> = contract.properties
    .filter { it.editor != BlockPropertyEditor.FLOW_ARGUMENTS }
    .associate { property ->
        val value = arguments.get(property.path)
        property.path to when {
            value == null || value.isJsonNull -> ""
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
                val target = targetFlow ?: return null to "请选择目标 Flow"
                arguments.addProperty(property.path, target.flowId)
            }
            BlockPropertyEditor.FLOW_ARGUMENTS -> {
                val target = targetFlow ?: return null to "请选择目标 Flow"
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

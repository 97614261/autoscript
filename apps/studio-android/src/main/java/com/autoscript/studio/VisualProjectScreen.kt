package com.autoscript.studio

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Card
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.autoscript.core.model.RuntimeConnectionPhase
import com.autoscript.core.model.RuntimeConnectionState
import com.autoscript.core.model.RuntimeEngineState
import com.autoscript.project.store.ProjectSnapshot
import com.autoscript.project.store.ProjectFlow
import com.autoscript.project.store.ProjectStore
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
    active: Boolean,
    modifier: Modifier = Modifier,
    onSnapshotChanged: (ProjectSnapshot) -> Unit,
    onExit: () -> Unit,
) {
    val projectId = snapshot.manifest.projectId
    var selectedFlowId by remember(projectId) {
        mutableStateOf(snapshot.manifest.entryFlowId ?: snapshot.manifest.flows.first().flowId)
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
    var newFlowId by remember(projectId) { mutableStateOf("") }
    var showBlockPicker by remember(projectId) { mutableStateOf(false) }
    var blockQuery by remember(projectId) { mutableStateOf("") }
    var blockCategory by remember(projectId) { mutableStateOf<BlockCategory?>(null) }
    var insertionChildBlockName by remember(projectId) { mutableStateOf<String?>(null) }
    var editingCallNodeId by remember(projectId) { mutableStateOf<String?>(null) }
    var editingCallTargetId by remember(projectId) { mutableStateOf<String?>(null) }
    var propertyInputs by remember(projectId) { mutableStateOf<Map<String, String>>(emptyMap()) }
    var callArgumentInputs by remember(projectId) { mutableStateOf<Map<String, String>>(emptyMap()) }
    var callArgumentError by remember(projectId) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val editor = requireNotNull(editors[selectedFlowId])
    val nodes = remember(editor, editorRevision) { editor.rows }
    val runtimeBusy = action != null
    val running = runtimeState.engineState == RuntimeEngineState.RUNNING
    val stopping = runtimeState.engineState == RuntimeEngineState.STOPPING
    val canStartAction = !runtimeBusy && !running && !stopping
    val canStop = runtimeState.phase == RuntimeConnectionPhase.CONNECTED && running && !runtimeBusy
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
                    generatedLuaModule = plan.luaSource,
                    resources = plan.resources,
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
        AlertDialog(
            onDismissRequest = { showNewFlowDialog = false },
            title = { Text("新建 Flow") },
            text = {
                OutlinedTextField(
                    value = newFlowId,
                    onValueChange = { newFlowId = it },
                    singleLine = true,
                    label = { Text("Flow ID") },
                    supportingText = { Text("字母或数字开头，可含 . _ -") },
                )
            },
            confirmButton = { TextButton(onClick = ::createFlow) { Text("创建") } },
            dismissButton = {
                TextButton(onClick = { showNewFlowDialog = false }) { Text("取消") }
            },
        )
    }

    if (showBlockPicker) {
        val results = BlockCatalog.search(
            blockQuery,
            snapshot.manifest.capabilities.toSet(),
            blockCategory,
        )
        AlertDialog(
            onDismissRequest = { showBlockPicker = false },
            title = {
                Text(insertionChildBlockName?.let { "添加积木到 $it" } ?: "添加积木")
            },
            text = {
                Column {
                    OutlinedTextField(
                        value = blockQuery,
                        onValueChange = { blockQuery = it },
                        singleLine = true,
                        label = { Text("搜索名称、类型或关键词") },
                    )
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                        listOf(
                            null to "全部",
                            BlockCategory.FLOW to "流程",
                            BlockCategory.TASK to "任务",
                            BlockCategory.CONTROL to "控制",
                            BlockCategory.VARIABLE to "变量",
                            BlockCategory.SCREEN to "截图",
                            BlockCategory.VISION to "视觉",
                            BlockCategory.OCR to "OCR",
                        ).forEach { (category, label) ->
                            TextButton(onClick = { blockCategory = category }) {
                                Text(if (blockCategory == category) "● $label" else label)
                            }
                        }
                    }
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
                        items(results, key = { it.contract.kind }) { result ->
                            val contract = result.contract
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable(enabled = result.isAvailable) {
                                        val args = initialBlockArguments(
                                            contract,
                                            snapshot.manifest.flows,
                                            snapshot.manifest.resources,
                                            selectedFlowId,
                                        )
                                        if (args == null) {
                                            error = "缺少积木所需的目标 Flow 或项目资源"
                                        } else {
                                            editor.insertBlock(
                                                contract,
                                                args,
                                                intoChildBlockName = insertionChildBlockName,
                                            )
                                            editorRevision++
                                            showBlockPicker = false
                                            insertionChildBlockName = null
                                        }
                                    }
                                    .padding(horizontal = 5.dp, vertical = 6.dp),
                            ) {
                                Text(contract.title, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    "${contract.kind} · ${contract.summary}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                if (!result.isAvailable) {
                                    Text(
                                        "缺少能力：${result.missingCapabilities.joinToString()}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                            HorizontalDivider()
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showBlockPicker = false }) { Text("关闭") }
            },
        )
    }

    editingCallNodeId?.let { nodeId ->
        val nodeKind = nodes.firstOrNull { it.nodeId == nodeId }?.kind
        val contract = nodeKind?.let(BlockCatalog::find)
        val targets = snapshot.manifest.flows.filter { it.flowId != selectedFlowId }
        val target = targets.firstOrNull { it.flowId == editingCallTargetId }
        AlertDialog(
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
                            BlockPropertyEditor.ENUM -> {
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
                            editor.updateArguments(nodeId, requireNotNull(parsed.first))
                            editorRevision++
                            editingCallNodeId = null
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

    Column(modifier.fillMaxSize().padding(8.dp)) {
        Surface(modifier = Modifier.fillMaxWidth(), tonalElevation = 1.dp) {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(
                            onClick = onExit,
                            contentPadding = PaddingValues(horizontal = 6.dp),
                        ) { Text("返回") }
                        Column {
                            Text(snapshot.manifest.name, style = MaterialTheme.typography.titleSmall)
                            Text(
                                "可视化积木 · ${snapshot.manifest.flows.size} Flow · ${nodes.size} 节点",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                        TextButton(
                            onClick = ::runProject,
                            enabled = canStartAction,
                            contentPadding = PaddingValues(horizontal = 7.dp, vertical = 0.dp),
                        ) { Text(if (action == VisualAction.STARTING) "启动中…" else "运行") }
                        TextButton(
                            onClick = ::stopProject,
                            enabled = canStop,
                            contentPadding = PaddingValues(horizontal = 7.dp, vertical = 0.dp),
                        ) { Text("停止") }
                    }
                }
                HorizontalDivider()
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
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
                    selectedContract?.childBlocks.orEmpty().forEach { childBlockName ->
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
                            propertyInputs = blockPropertyTexts(contract, args)
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
                                if (flow.flowId == selectedFlowId) "● ${flow.flowId}" else flow.flowId,
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                    }
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

private fun childBlockLabel(name: String): String = when (name) {
    "then" -> "满足"
    "else" -> "否则"
    "body" -> "循环体"
    else -> name
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
    STOPPING,
}

private fun defaultFlowCallArguments(flow: ProjectFlow): JsonObject = JsonObject().apply {
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

private fun initialBlockArguments(
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
            property.editor != BlockPropertyEditor.SCALAR &&
                value.isJsonPrimitive && value.asJsonPrimitive.isString -> value.asString
            else -> value.toString()
        }
    }

private fun parseBlockArguments(
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

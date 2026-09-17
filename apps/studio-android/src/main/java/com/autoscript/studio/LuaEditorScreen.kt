package com.autoscript.studio

import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autoscript.core.designsystem.AutoScriptPalette
import com.autoscript.project.store.MAX_LUA_SOURCE_BYTES
import com.autoscript.project.store.ProjectSnapshot
import com.autoscript.project.store.ProjectStore
import com.autoscript.core.model.RuntimeConnectionPhase
import com.autoscript.core.model.RuntimeConnectionState
import com.autoscript.core.model.RuntimeEngineState
import com.autoscript.runtime.client.RuntimeClient
import com.autoscript.runtime.client.ScriptValidationResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun LuaEditorScreen(
    snapshot: ProjectSnapshot,
    store: ProjectStore,
    runtimeClient: RuntimeClient,
    runtimeState: RuntimeConnectionState,
    consoleLines: List<String>,
    active: Boolean,
    modifier: Modifier = Modifier,
    onSnapshotChanged: (ProjectSnapshot) -> Unit,
    onExit: () -> Unit,
) {
    val projectId = snapshot.manifest.projectId
    val initialSource = snapshot.luaSource.orEmpty()
    var buffer by remember(projectId) { mutableStateOf(LuaEditorBuffer.open(projectId, initialSource)) }
    var fieldValue by remember(projectId) { mutableStateOf(TextFieldValue(initialSource)) }
    var saving by remember(projectId) { mutableStateOf(false) }
    var runtimeAction by remember(projectId) { mutableStateOf<RuntimeAction?>(null) }
    var error by remember(projectId) { mutableStateOf<String?>(null) }
    var notice by remember(projectId) { mutableStateOf<String?>(null) }
    var confirmExit by remember(projectId) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val sourceBytes = remember(buffer.text) { buffer.text.toByteArray(Charsets.UTF_8).size }
    val lineCount = remember(buffer.text) { buffer.text.count { it == '\n' } + 1 }
    val runtimeBusy = runtimeAction != null
    val canRun = runtimeState.phase == RuntimeConnectionPhase.CONNECTED &&
        runtimeState.engineState !in setOf(
            RuntimeEngineState.RUNNING,
            RuntimeEngineState.PAUSED,
            RuntimeEngineState.STOPPING,
        ) &&
        !runtimeBusy && !saving && sourceBytes <= MAX_LUA_SOURCE_BYTES
    val runButtonEnabled = runtimeState.engineState !in
        setOf(RuntimeEngineState.RUNNING, RuntimeEngineState.PAUSED, RuntimeEngineState.STOPPING) &&
        !runtimeBusy && !saving && sourceBytes <= MAX_LUA_SOURCE_BYTES
    val canStop = runtimeState.phase == RuntimeConnectionPhase.CONNECTED &&
        runtimeState.engineState in setOf(RuntimeEngineState.RUNNING, RuntimeEngineState.PAUSED) &&
        !runtimeBusy
    val canPauseResume = canStop
    val canEditProjectSettings = !buffer.isDirty && !saving && !runtimeBusy &&
        runtimeState.engineState !in setOf(
            RuntimeEngineState.RUNNING,
            RuntimeEngineState.PAUSED,
            RuntimeEngineState.STOPPING,
        )

    LaunchedEffect(active) {
        if (!active) {
            focusManager.clearFocus(force = true)
            confirmExit = false
        }
    }

    fun requestExit() {
        if (buffer.isDirty) confirmExit = true else onExit()
    }

    fun save() {
        if (saving || !buffer.isDirty || sourceBytes > MAX_LUA_SOURCE_BYTES) return
        val submitted = buffer.text
        val expected = buffer.savedText
        saving = true
        error = null
        notice = null
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    store.saveLua(projectId, submitted, expectedSource = expected)
                }
            }.onSuccess { persisted ->
                buffer = buffer.markSaved(submitted)
                onSnapshotChanged(persisted)
            }.onFailure { failure ->
                error = failure.message ?: "保存失败"
            }
            saving = false
        }
    }

    fun validate() {
        if (runtimeBusy || sourceBytes > MAX_LUA_SOURCE_BYTES) return
        val submitted = buffer.text.toByteArray(Charsets.UTF_8)
        runtimeAction = RuntimeAction.VALIDATING
        error = null
        notice = null
        scope.launch {
            when (val result = withContext(Dispatchers.IO) {
                runtimeClient.validateScript(submitted)
            }) {
                ScriptValidationResult.Valid -> notice = "Lua 5.4 语法校验通过"
                is ScriptValidationResult.Invalid -> error = result.diagnostic
                is ScriptValidationResult.Unavailable -> error = result.message
            }
            runtimeAction = null
        }
    }

    fun runProject() {
        if (!canRun) {
            if (runtimeState.phase != RuntimeConnectionPhase.CONNECTED) error = "Runner尚未连接"
            return
        }
        val submitted = buffer.text
        val expected = buffer.savedText
        runtimeAction = RuntimeAction.RUNNING
        error = null
        notice = null
        scope.launch {
            val persistedResult = runCatching {
                if (submitted == expected) snapshot else withContext(Dispatchers.IO) {
                    store.saveLua(projectId, submitted, expectedSource = expected)
                }
            }
            val persisted = persistedResult.getOrElse { failure ->
                error = failure.message ?: "保存失败，未启动脚本"
                runtimeAction = null
                return@launch
            }
            if (submitted != expected) {
                buffer = buffer.markSaved(submitted)
                onSnapshotChanged(persisted)
            }
            val source = submitted.toByteArray(Charsets.UTF_8)
            when (val validation = withContext(Dispatchers.IO) {
                runtimeClient.validateScript(source)
            }) {
                is ScriptValidationResult.Invalid -> {
                    error = validation.diagnostic
                    runtimeAction = null
                    return@launch
                }
                is ScriptValidationResult.Unavailable -> {
                    error = validation.message
                    runtimeAction = null
                    return@launch
                }
                ScriptValidationResult.Valid -> Unit
            }
            val plan = runCatching {
                withContext(Dispatchers.IO) {
                    RuntimeProjectPlan.fromSnapshot(persisted, submitted)
                }
            }.getOrElse { failure ->
                error = failure.message ?: "项目资源装载计划无效"
                runtimeAction = null
                return@launch
            }
            val started = withContext(Dispatchers.IO) {
                runtimeClient.startProject(
                    generatedLuaModule = plan.luaSource,
                    resources = plan.resources,
                    capabilities = plan.capabilities,
                    designWidth = plan.designWidth,
                    designHeight = plan.designHeight,
                    scaleMode = plan.scaleMode,
                )
            }
            if (started) notice = "脚本已提交运行"
            runtimeAction = null
        }
    }

    /** “文件”弹窗只允许删除图片和字库资源，走 Store 的引用检查与事务删除。 */
    fun deleteProjectFiles(files: List<StudioProjectFile>) {
        if (runtimeBusy || saving) return
        val targets = files.filter {
            it.kind == StudioProjectFileKind.IMAGE || it.kind == StudioProjectFileKind.GLYPH_DICTIONARY
        }
        if (targets.isEmpty()) {
            error = "这里只能删除图片和字库资源"
            return
        }
        saving = true
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
            saving = false
        }
    }

    fun stopProject() {
        if (!canStop) return
        runtimeAction = RuntimeAction.STOPPING
        error = null
        notice = null
        scope.launch {
            withContext(Dispatchers.IO) { runtimeClient.requestStop() }
            runtimeAction = null
        }
    }

    fun pauseOrResumeProject() {
        if (!canPauseResume) return
        val resume = runtimeState.engineState == RuntimeEngineState.PAUSED
        runtimeAction = RuntimeAction.CONTROLLING
        error = null
        notice = null
        scope.launch {
            val accepted = withContext(Dispatchers.IO) {
                if (resume) runtimeClient.requestResume() else runtimeClient.requestPause()
            }
            if (!accepted) error = if (resume) "继续请求被拒绝" else "暂停请求被拒绝"
            runtimeAction = null
        }
    }

    BackHandler(enabled = active, onBack = ::requestExit)

    // 撤销/恢复：按停顿合并的文本快照栈，只在编辑器内有效。
    var undoStack by remember(projectId) { mutableStateOf(emptyList<TextFieldValue>()) }
    var redoStack by remember(projectId) { mutableStateOf(emptyList<TextFieldValue>()) }
    var lastEditAt by remember(projectId) { mutableStateOf(0L) }
    var settingsMenuVisible by remember(projectId) { mutableStateOf(false) }
    var projectSettingsVisible by remember(projectId) { mutableStateOf(false) }

    fun applyEdit(updated: TextFieldValue, coalesce: Boolean = true) {
        if (updated.text != fieldValue.text) {
            val now = System.currentTimeMillis()
            if (!coalesce || now - lastEditAt > EDIT_COALESCE_MILLIS || undoStack.isEmpty()) {
                undoStack = (undoStack + fieldValue).takeLast(MAX_UNDO_STEPS)
            }
            redoStack = emptyList()
            lastEditAt = now
        }
        fieldValue = updated
        buffer = buffer.edit(updated.text)
        error = null
        notice = null
    }

    fun undoEdit() {
        val previous = undoStack.lastOrNull() ?: return
        redoStack = redoStack + fieldValue
        undoStack = undoStack.dropLast(1)
        fieldValue = previous
        buffer = buffer.edit(previous.text)
        lastEditAt = 0L
    }

    fun redoEdit() {
        val next = redoStack.lastOrNull() ?: return
        undoStack = undoStack + fieldValue
        redoStack = redoStack.dropLast(1)
        fieldValue = next
        buffer = buffer.edit(next.text)
        lastEditAt = 0L
    }

    Box(modifier = modifier.fillMaxSize()) {
    Column(modifier = Modifier.fillMaxSize()) {
        // `service_tk_text_editing.xml`：深色顶栏（主题 | 设置 / 保存 / 撤销 / 恢复 / 关闭）+ 1dp 分割 + 24dp 文件栏。
        Column(Modifier.fillMaxWidth().background(LuaChromeBackground)) {
            Row(Modifier.fillMaxWidth().padding(start = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                LuaChromeIcon(R.drawable.editor_moon_24, "主题") { notice = "编辑器主题切换未开放" }
                Spacer(Modifier.weight(1f))
                Box {
                    LuaChromeIcon(R.drawable.editor_popup_settings_20, "设置") { settingsMenuVisible = true }
                    DropdownMenu(expanded = settingsMenuVisible, onDismissRequest = { settingsMenuVisible = false }) {
                        LuaChromeMenuItem(
                            if (runtimeAction == RuntimeAction.VALIDATING) "校验中…" else "校验语法",
                            enabled = !runtimeBusy && sourceBytes <= MAX_LUA_SOURCE_BYTES,
                        ) { settingsMenuVisible = false; validate() }
                        LuaChromeMenuItem("插入缩进") { settingsMenuVisible = false; applyEdit(insertEditorIndent(fieldValue), coalesce = false) }
                        LuaChromeMenuItem(
                            if (runtimeAction == RuntimeAction.RUNNING) "启动中…" else "运行脚本",
                            enabled = runButtonEnabled,
                        ) { settingsMenuVisible = false; runProject() }
                        LuaChromeMenuItem(
                            if (runtimeState.engineState == RuntimeEngineState.PAUSED) "继续运行" else "暂停运行",
                            enabled = canPauseResume,
                        ) { settingsMenuVisible = false; pauseOrResumeProject() }
                        LuaChromeMenuItem("停止运行", enabled = canStop) { settingsMenuVisible = false; stopProject() }
                        LuaChromeMenuItem("项目设置", enabled = canEditProjectSettings) { settingsMenuVisible = false; projectSettingsVisible = true }
                    }
                }
                LuaChromeIcon(
                    R.drawable.visual_save_24,
                    if (saving) "保存中" else "保存",
                    enabled = buffer.isDirty && !saving && !runtimeBusy && sourceBytes <= MAX_LUA_SOURCE_BYTES,
                ) { save() }
                LuaChromeIcon(R.drawable.visual_undo_24, "撤销", enabled = undoStack.isNotEmpty()) { undoEdit() }
                LuaChromeIcon(R.drawable.visual_redo_24, "恢复", enabled = redoStack.isNotEmpty()) { redoEdit() }
                LuaChromeIcon(R.drawable.editor_close_24, "关闭", endPadding = 0.dp) { requestExit() }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(LuaChromeDivider))
            Row(Modifier.fillMaxWidth().height(24.dp).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${snapshot.manifest.entryPoint ?: "main.lua"} · ${snapshot.manifest.name} · $lineCount 行 · $sourceBytes 字节 · ${if (buffer.isDirty) "未保存" else "已保存"}",
                    color = if (buffer.isDirty) LuaChromeUnsaved else LuaChromeText,
                    fontSize = 8.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "Runner ${runtimeState.engineState}",
                    color = LuaChromeMuted,
                    fontSize = 8.sp,
                    maxLines = 1,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
                Text("UTF-8", color = LuaChromeMuted, fontSize = 8.sp, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
            }
        }

        (error ?: runtimeState.message)?.let { message ->
            Text(
                text = message,
                color = AutoScriptPalette.Danger,
                fontSize = 11.sp,
                modifier = Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }
        notice?.let { message ->
            Text(
                text = message,
                color = AutoScriptPalette.Accent,
                fontSize = 11.sp,
                modifier = Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }
        if (sourceBytes > MAX_LUA_SOURCE_BYTES) {
            Text(
                text = "源码超过 16 MiB，无法保存或运行",
                color = AutoScriptPalette.Danger,
                fontSize = 11.sp,
                modifier = Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }

        LuaSourceField(
            value = fieldValue,
            onValueChange = { updated -> applyEdit(updated) },
            lineCount = lineCount,
            modifier = Modifier.fillMaxWidth().weight(1f),
        )
    }
        if (projectSettingsVisible) {
            ProjectSettingsDialog(
                snapshot = snapshot,
                store = store,
                onSnapshotChanged = onSnapshotChanged,
                onDismiss = { projectSettingsVisible = false },
            )
        }
        val projectFiles = remember(snapshot) { projectFileCatalog(snapshot) }
        LegacyScriptDock(
            projectName = snapshot.manifest.name,
            onRun = ::runProject,
            running = canStop,
            onStop = ::stopProject,
            consoleLines = consoleLines,
            sourceName = snapshot.manifest.entryPoint ?: "main.lua",
            projectFiles = projectFiles,
            onOpenProjectFile = { file ->
                notice = when (file.kind) {
                    StudioProjectFileKind.LUA -> "main.lua 已在当前编辑器中打开"
                    StudioProjectFileKind.FLOW -> "Lua 项目没有积木流程"
                    StudioProjectFileKind.MANIFEST,
                    StudioProjectFileKind.IMAGE,
                    StudioProjectFileKind.GLYPH_DICTIONARY,
                    -> "图片、字库和 project.json 请通过顶部“项目设置”管理"
                }
            },
            onDeleteProjectFiles = ::deleteProjectFiles,
            capabilities = snapshot.manifest.capabilities.toSet(),
            onStep = { notice = "单步运行需要 Runtime 调试协议，当前版本未开放，未执行脚本" },
            onProgramCommand = { notice = "结构化节点操作仅适用于可视化项目，Lua 请直接编辑源码" },
            onInsert = insert@{ snippet ->
                LuaSnippetGate.reject(snippet)?.let { reason ->
                    notice = reason
                    return@insert
                }
                val selection = fieldValue.selection
                applyEdit(
                    fieldValue.copy(
                        text = fieldValue.text.replaceRange(selection.start, selection.end, snippet),
                        selection = androidx.compose.ui.text.TextRange(selection.start + snippet.length),
                    ),
                    coalesce = false,
                )
                notice = "已插入脚本片段"
            },
        )
    }

    if (confirmExit) {
        LegacyPromptDialog(
            title = "放弃未保存修改？",
            text = "main.lua 中的修改尚未保存。返回后这些修改将丢失。",
            cancelLabel = "继续编辑",
            confirmLabel = "放弃修改",
            confirmDanger = true,
            onDismiss = { confirmExit = false },
            onConfirm = {
                confirmExit = false
                onExit()
            },
        )
    }
}

@Composable
private fun LuaSourceField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    lineCount: Int,
    modifier: Modifier = Modifier,
) {
    val verticalScroll = rememberScrollState()
    val horizontalScroll = rememberScrollState()
    val lineNumbers = remember(lineCount) {
        if (lineCount <= MAX_RENDERED_LINE_NUMBERS) {
            (1..lineCount).joinToString("\n")
        } else {
            "1\n…\n$lineCount"
        }
    }
    Box(modifier = modifier.background(Color.White)) {
        Row(Modifier.fillMaxSize().verticalScroll(verticalScroll)) {
            Text(
                text = lineNumbers,
                modifier = Modifier
                    .widthIn(min = 34.dp)
                    .background(LuaGutterBackground)
                    .padding(horizontal = 6.dp, vertical = 8.dp),
                color = AutoScriptPalette.TextSecondary,
                textAlign = TextAlign.End,
                style = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                ),
            )
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 480.dp)
                    .horizontalScroll(horizontalScroll)
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                textStyle = TextStyle(
                    color = AutoScriptPalette.TextPrimary,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                ),
                cursorBrush = SolidColor(AutoScriptPalette.Accent),
            )
        }
    }
}

@Composable
private fun LuaChromeIcon(
    @DrawableRes icon: Int,
    label: String,
    enabled: Boolean = true,
    endPadding: Dp = 8.dp,
    onClick: () -> Unit,
) {
    Box(
        Modifier.padding(end = endPadding).clickable(enabled = enabled, onClick = onClick).padding(5.dp),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painterResource(icon),
            contentDescription = label,
            tint = Color.White.copy(alpha = if (enabled) 1f else 0.35f),
            modifier = Modifier.size(22.dp),
        )
    }
}

@Composable
private fun LuaChromeMenuItem(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label, fontSize = 13.sp, color = AutoScriptPalette.TextPrimary) },
        enabled = enabled,
        onClick = onClick,
    )
}

private val LuaChromeBackground = Color(0xFF242A33)
private val LuaChromeDivider = Color(0xFF344052)
private val LuaChromeText = Color(0xFFE8EAED)
private val LuaChromeMuted = Color(0xFFC8CCD2)
private val LuaChromeUnsaved = Color(0xFFFFB4B4)
private val LuaGutterBackground = Color(0xFFF3F4F6)
private const val EDIT_COALESCE_MILLIS = 700L
private const val MAX_UNDO_STEPS = 200
private const val MAX_RENDERED_LINE_NUMBERS = 20_000

private enum class RuntimeAction {
    VALIDATING,
    RUNNING,
    CONTROLLING,
    STOPPING,
}

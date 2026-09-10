package com.autoscript.studio

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
        runtimeState.engineState !in setOf(RuntimeEngineState.RUNNING, RuntimeEngineState.STOPPING) &&
        !runtimeBusy && !saving && sourceBytes <= MAX_LUA_SOURCE_BYTES
    val runButtonEnabled = runtimeState.engineState !in
        setOf(RuntimeEngineState.RUNNING, RuntimeEngineState.STOPPING) &&
        !runtimeBusy && !saving && sourceBytes <= MAX_LUA_SOURCE_BYTES
    val canStop = runtimeState.phase == RuntimeConnectionPhase.CONNECTED &&
        runtimeState.engineState == RuntimeEngineState.RUNNING && !runtimeBusy
    val canEditProjectSettings = !buffer.isDirty && !saving && !runtimeBusy &&
        runtimeState.engineState !in setOf(RuntimeEngineState.RUNNING, RuntimeEngineState.STOPPING)

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
                    designWidth = plan.designWidth,
                    designHeight = plan.designHeight,
                    scaleMode = plan.scaleMode,
                )
            }
            if (started) notice = "脚本已提交运行"
            runtimeAction = null
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

    BackHandler(enabled = active, onBack = ::requestExit)

    Column(modifier = modifier.fillMaxSize().padding(8.dp)) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 1.dp,
        ) {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(
                            onClick = ::requestExit,
                            contentPadding = PaddingValues(horizontal = 6.dp),
                        ) { Text("返回") }
                        Column {
                            Text(snapshot.manifest.name, style = MaterialTheme.typography.titleSmall)
                            Text(
                                "main.lua · ${if (buffer.isDirty) "未保存" else "已保存"}",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (buffer.isDirty) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
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
                            onClick = ::validate,
                            enabled = !runtimeBusy && sourceBytes <= MAX_LUA_SOURCE_BYTES,
                            contentPadding = PaddingValues(horizontal = 7.dp, vertical = 0.dp),
                        ) { Text(if (runtimeAction == RuntimeAction.VALIDATING) "校验中…" else "校验") }
                        TextButton(
                            onClick = ::runProject,
                            enabled = runButtonEnabled,
                            contentPadding = PaddingValues(horizontal = 7.dp, vertical = 0.dp),
                        ) { Text(if (runtimeAction == RuntimeAction.RUNNING) "启动中…" else "运行") }
                        TextButton(
                            onClick = ::stopProject,
                            enabled = canStop,
                            contentPadding = PaddingValues(horizontal = 7.dp, vertical = 0.dp),
                        ) { Text("停止") }
                        Button(
                            onClick = ::save,
                            enabled = buffer.isDirty && !saving && !runtimeBusy &&
                                sourceBytes <= MAX_LUA_SOURCE_BYTES,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        ) { Text(if (saving) "保存中…" else "保存") }
                    }
                }
                HorizontalDivider()
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 3.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "$lineCount 行 · $sourceBytes 字节 · Runner ${runtimeState.engineState}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(
                        onClick = {
                            fieldValue = insertEditorIndent(fieldValue)
                            buffer = buffer.edit(fieldValue.text)
                        },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                    ) { Text("缩进", style = MaterialTheme.typography.labelMedium) }
                }
            }
        }

        error?.let { message ->
            Text(
                text = message,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 3.dp),
            )
        }
        if (error == null) {
            runtimeState.message?.let { message ->
                Text(
                    text = message,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 3.dp),
                )
            }
        }
        notice?.let { message ->
            Text(
                text = message,
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 3.dp),
            )
        }
        if (sourceBytes > MAX_LUA_SOURCE_BYTES) {
            Text(
                text = "源码超过 16 MiB，无法保存或运行",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 3.dp),
            )
        }

        LuaSourceField(
            value = fieldValue,
            onValueChange = { updated ->
                fieldValue = updated
                buffer = buffer.edit(updated.text)
                error = null
                notice = null
            },
            lineCount = lineCount,
            modifier = Modifier.fillMaxWidth().weight(1f),
        )
    }

    if (confirmExit) {
        AlertDialog(
            onDismissRequest = { confirmExit = false },
            title = { Text("放弃未保存修改？") },
            text = { Text("main.lua 中的修改尚未保存。返回后这些修改将丢失。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmExit = false
                        onExit()
                    },
                ) { Text("放弃修改", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmExit = false }) { Text("继续编辑") } },
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
    Box(
        modifier = modifier
            .padding(top = 6.dp)
            .border(1.dp, MaterialTheme.colorScheme.outline)
            .background(MaterialTheme.colorScheme.surface),
    ) {
        Row(Modifier.fillMaxSize().verticalScroll(verticalScroll)) {
            Text(
                text = lineNumbers,
                modifier = Modifier
                    .widthIn(min = 38.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 6.dp, vertical = 8.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                    color = MaterialTheme.colorScheme.onSurface,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            )
        }
    }
}

private const val MAX_RENDERED_LINE_NUMBERS = 20_000

private enum class RuntimeAction {
    VALIDATING,
    RUNNING,
    STOPPING,
}

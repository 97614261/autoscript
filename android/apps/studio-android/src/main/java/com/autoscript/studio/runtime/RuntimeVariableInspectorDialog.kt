package com.autoscript.studio

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.autoscript.core.designsystem.AutoScriptPalette
import com.autoscript.project.store.ProjectSnapshot
import com.autoscript.runtime.api.RuntimeDebugReply
import com.autoscript.runtime.api.RuntimeDebugValue
import com.autoscript.runtime.api.RuntimeProtocol
import com.autoscript.runtime.client.RuntimeClient
import com.autoscript.runtime.client.VisualCompileResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Invalidating an event also invalidates in-flight reads; late replies cannot restore old values. */
internal class RuntimeVariableInspectionState {
    var reply by mutableStateOf(RuntimeDebugReply.unavailable())
        private set
    var loading by mutableStateOf(false)
        private set
    private var revision = 0L

    fun invalidate() { revision++; loading = false; reply = RuntimeDebugReply.unavailable() }
    fun discard(request: Long) { if (request == revision) invalidate() }
    fun owns(request: Long): Boolean = request == revision
    fun finish(request: Long) { if (request == revision && loading) invalidate() }
    fun begin(keepReply: Boolean = false): Long? {
        if (loading) return null
        loading = true
        if (!keepReply) reply = RuntimeDebugReply.unavailable()
        return ++revision
    }
    fun complete(request: Long, generation: Long?, result: RuntimeDebugReply): Boolean {
        if (request != revision) return false
        loading = false
        reply = if (result.status == RuntimeDebugReply.SUCCESS && result.sessionGeneration != generation)
            RuntimeDebugReply.unavailable(RuntimeDebugReply.SESSION_MISMATCH) else result
        return true
    }
}

/** Read failures and cancellation release the busy state without resurrecting a superseded read. */
internal suspend fun readRuntimeVariables(state: RuntimeVariableInspectionState, generation: Long?, revision: Long,
    read: suspend () -> RuntimeDebugReply, current: () -> com.autoscript.core.model.RuntimeConnectionState,
    keepReply: Boolean = false, onFailure: () -> Unit) {
    val request = state.begin(keepReply) ?: return
    editorOperation({ if (state.owns(request)) onFailure() }, { state.finish(request) }) {
        val result = read()
        currentCoroutineContext().ensureActive()
        val now = current()
        if (now.sessionGeneration != generation || now.stateRevision != revision) state.discard(request)
        else state.complete(request, generation, result)
    }
}

internal fun runtimeDebugValueText(value: RuntimeDebugValue): String = when (value.type) {
    "nil" -> "nil（当前未赋值）"
    "unsupported" -> "不可显示的类型：${value.value}"
    "string" -> "\"${value.value}\"${if (value.truncated) "…（内容已截断或包含非 UTF-8 字节）" else ""}"
    else -> value.value
}

internal fun runtimeDebugSummary(reply: RuntimeDebugReply): String = when (reply.status) {
    RuntimeDebugReply.SESSION_MISMATCH -> "当前项目没有匹配的运行会话，请运行本项目后重试。"
    RuntimeDebugReply.BUSY -> "另一个变量读取尚未结束，请稍后刷新。"
    RuntimeDebugReply.SUCCESS -> when (reply.runtimeState) {
        RuntimeProtocol.STATE_PAUSED -> "已暂停 · 以下为当前检查点执行前的真实值"
        RuntimeProtocol.STATE_RUNNING -> "运行中 · 以下为读取时刻最近检查点上下文中的值"
        RuntimeProtocol.STATE_STOPPED, RuntimeProtocol.STATE_FAILED -> "运行已结束 · 以下为最后检查点上下文中的值"
        else -> "当前运行状态无法检查，请刷新。"
    }
    else -> "尚无可视化检查点。运行或单步执行本项目后刷新；手写 Lua 暂不提供变量快照。"
}

@Composable
internal fun RuntimeVariableInspectorDialog(snapshot: ProjectSnapshot, flowId: String, runtimeClient: RuntimeClient, onDismiss: () -> Unit, restoreRequest: Int = 0) {
    RuntimeVariableObserver(snapshot, flowId, runtimeClient, onDismiss, restoreRequest)
}

@Composable
internal fun VariableCheckDialog(
    snapshot: ProjectSnapshot,
    flowId: String,
    editor: VisualEditorState,
    runtimeClient: RuntimeClient,
    onDismiss: () -> Unit,
) {
    val input = editor.variableCheckInput()
    val analysisKey = Triple(input, snapshot, flowId)
    val analysis by produceState<Pair<Triple<VisualVariableCheckInput, ProjectSnapshot, String>, Pair<List<VisualVariableCheckNode>, List<String>>>?>(null, analysisKey) {
        value = null
        val result = withContext(Dispatchers.Default) {
            val context = currentCoroutineContext()
            val nodes = variableCheckNodes(input)
            nodes to visualVariableWarnings(nodes, snapshot, flowId) { context.ensureActive() }
        }
        value = analysisKey to result
    }
    val currentAnalysis = analysis?.takeIf { it.first == analysisKey }?.second
    val warnings = currentAnalysis?.second.orEmpty()
    val rowsById = remember(currentAnalysis) { currentAnalysis?.first.orEmpty().associate { it.row.nodeId to it.row } }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var warningFilter by remember { mutableStateOf("all") }
    val scope = rememberCoroutineScope()
    InspectorDialog("变量检查", onDismiss) {
        Text("当前插件的声明、引用和赋值检查；分支及跨插件赋值属于保守提示。", fontSize = 11.sp, color = AutoScriptPalette.TextSecondary)
        InspectorSearchField(query, { query = it.take(128) }, "搜索变量或积木")
        InspectorFilterRow(listOf("all" to "全部", "declaration" to "未声明", "assignment" to "可能未赋值"), warningFilter) { warningFilter = it }
        val filtered = warnings.filter { it.contains(query, ignoreCase = true) && when (warningFilter) {
            "declaration" -> "未声明" in it || "未维护" in it
            "assignment" -> "未赋值" in it
            else -> true
        } }
        Text("维护检查：${filtered.size} / ${warnings.size} 项", color = AutoScriptPalette.Accent, fontSize = 12.sp)
        if (currentAnalysis == null) Text("检查中…", fontSize = 11.sp)
        else if (warnings.isEmpty()) Text("未发现变量维护警告。")
        if (warnings.isNotEmpty() && filtered.isEmpty()) Text("没有符合筛选的结果。", fontSize = 11.sp)
        filtered.forEach { warning ->
            val node = rowsById[warning.substringBefore(" · ")]
            Surface(color = AutoScriptPalette.PageBackground, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(node?.let { "第 ${it.sourceLine} 行 · ${BlockCatalog.find(it.kind)?.title ?: it.kind}" } ?: "变量维护提示", fontSize = 10.sp, color = AutoScriptPalette.Accent)
                    SelectionContainer { Text(warning.substringAfter(" · "), fontSize = 12.sp) }
                }
            }
        }
        message?.let { Text(it) }
        TextButton(enabled = !busy, onClick = {
            val submitted = editor.currentSource
            val source = submitted.toByteArray(Charsets.UTF_8)
            busy = true
            scope.launch {
                editorOperation({ message = "参数与类型校验失败，请重试；草稿未改变。" }, { busy = false }) {
                    val result = withContext(Dispatchers.IO) {
                        runtimeClient.validateVisualDraft(snapshot.manifest.projectId, flowId, source)
                    }
                    message = if (editor.currentSource != submitted) "草稿已变化，请重新校验。" else when (result) {
                        is VisualCompileResult.Success -> "编译器参数与类型校验通过。此检查不能证明所有执行路径都已赋值。"
                        is VisualCompileResult.Invalid -> result.diagnostic.message
                        is VisualCompileResult.Unavailable -> result.message
                    }
                }
            }
        }, modifier = Modifier.fillMaxWidth()) { Text(if (busy) "校验中…" else "编译器参数 / 类型校验") }
    }
}

@Composable
private fun InspectorDialog(title: String, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    var fullScreen by remember { mutableStateOf(false) }
    StudioPanelDialog(title, onDismiss, fullScreen = fullScreen, onToggleFullScreen = { fullScreen = !fullScreen }) {
        ProvideTextStyle(TextStyle(fontSize = 12.sp, lineHeight = 17.sp)) {
            Column(Modifier.fillMaxWidth().then(if (fullScreen) Modifier.fillMaxHeight() else Modifier.heightIn(max = 430.dp))
                .verticalScroll(rememberScrollState()).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp), content = content)
        }
    }
}

@Composable
internal fun InspectorSearchField(value: String, onChange: (String) -> Unit, placeholder: String) {
    BasicTextField(value, onChange, singleLine = true, textStyle = TextStyle(fontSize = 12.sp),
        modifier = Modifier.fillMaxWidth().height(36.dp).border(1.dp, AutoScriptPalette.Border, RoundedCornerShape(4.dp)).padding(horizontal = 8.dp),
        decorationBox = { field -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) {
            if (value.isEmpty()) Text(placeholder, fontSize = 12.sp, color = AutoScriptPalette.TextSecondary)
            field()
        } })
}

@Composable
internal fun InspectorFilterRow(options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().background(AutoScriptPalette.PageBackground, RoundedCornerShape(4.dp))) {
        options.forEach { (key, label) ->
            Box(Modifier.weight(1f).height(34.dp).clickable { onSelect(key) }
                .background(if (key == selected) Color(0xFFE7EEFF) else Color.Transparent, RoundedCornerShape(4.dp)), contentAlignment = Alignment.Center) {
                Text(label, fontSize = 11.sp, color = if (key == selected) AutoScriptPalette.Accent else AutoScriptPalette.TextSecondary)
            }
        }
    }
}

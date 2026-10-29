package com.autoscript.studio

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autoscript.core.designsystem.AutoScriptPalette
import com.autoscript.studio.compiler.VisualLuaSource
import kotlinx.coroutines.*

@Composable
internal fun SelectedNodeSourceEditorDialog(
    source: String,
    initialNodeId: String,
    variables: Set<String>,
    nodeOrder: List<String> = emptyList(),
    onSelectNode: (String) -> Unit,
    onApply: suspend (String, String) -> String?,
    onDismiss: () -> Unit,
) {
    val original = remember { source }
    val originalNodeOrder = remember { nodeOrder }
    var selectedId by remember { mutableStateOf(initialNodeId) }
    var rows by remember { mutableStateOf<List<PluginSourceRow>>(emptyList()) }
    var scopeDraft by remember { mutableStateOf<SelectedNodeSource?>(null) }
    var draft by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var discard by remember { mutableStateOf(false) }
    var nextRow by remember { mutableStateOf<String?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()
    val latestApply by rememberUpdatedState(onApply)
    val latestVariables by rememberUpdatedState(variables)
    LaunchedEffect(selectedId) {
        try {
            val result = withContext(Dispatchers.Default) {
                val context = currentCoroutineContext()
                pluginSourceRows(original, originalNodeOrder) to SelectedNodeSource.create(original, selectedId) { context.ensureActive() }
            }
            rows = result.first
            scopeDraft = result.second
            draft = result.second.text
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (problem: Exception) { error = problem.message ?: "无法打开本行源码，原积木未改变" }
    }
    fun switchRow(id: String) {
        picking = false
        scopeDraft = null
        error = null
        selectedId = id
        onSelectNode(id)
    }
    fun requestRow(id: String) {
        if (id == selectedId || busy || scopeDraft == null) return
        if (draft != scopeDraft?.text) { nextRow = id; discard = true }
        else switchRow(id)
    }
    fun close() {
        if (busy) job?.cancel()
        else if (scopeDraft != null && draft != scopeDraft?.text) { nextRow = null; discard = true }
        else onDismiss()
    }
    val index = rows.indexOfFirst { it.id == selectedId }
    val selected = rows.getOrNull(index)
    var fullScreen by remember { mutableStateOf(false) }
    StudioPanelDialog("本行源码 · ${selected?.title ?: "加载中"}", ::close,
        if (busy) "校验中…" else "校验并保存", {
            val descriptor = scopeDraft
            if (!busy && descriptor != null) {
                busy = true
                error = null
                job = scope.launch {
                    try {
                        withTimeout(30_000) {
                            val replacement = withContext(Dispatchers.Default) {
                                val context = currentCoroutineContext()
                                descriptor.parse(draft, latestVariables) { context.ensureActive() }
                            }
                            error = latestApply(original, replacement.text)?.let {
                                pluginSourceProblemMessage(replacement, it)
                            }
                            if (error == null) onDismiss()
                        }
                    } catch (cancelled: CancellationException) {
                        error = "校验已取消或超时，文本保留，原积木未改变"
                        throw cancelled
                    } catch (problem: Exception) { error = problem.message ?: "校验失败，原积木未改变" }
                    finally { busy = false }
                }
            }
        }, confirmEnabled = !busy && scopeDraft != null, fullScreen = fullScreen, onToggleFullScreen = { fullScreen = !fullScreen }) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val sourceHeight = studioPanelViewportHeight(maxHeight.value * .65f, 220, fullScreen).dp
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(Modifier.fillMaxWidth()) {
                    SourceEditorAction("上一行", !busy && index > 0) { requestRow(rows[index - 1].id) }
                    SourceEditorAction("选择行 · ${index + 1} / ${rows.size}", !busy && scopeDraft != null, Modifier.weight(1f)) { picking = true }
                    SourceEditorAction("下一行", !busy && index in 0 until rows.lastIndex) { requestRow(rows[index + 1].id) }
                }
                Text("只修改本行参数，其他积木与子树不变；空子区是结构骨架，请勿加入新积木。", fontSize = 11.sp, color = AutoScriptPalette.TextSecondary)
                error?.let { Text(it, color = AutoScriptPalette.Danger, fontSize = 11.sp) }
                Surface(color = AutoScriptPalette.PageBackground) {
                    key(selectedId) {
                        BasicTextField(draft, {
                            if (it.toByteArray(Charsets.UTF_8).size <= VisualLuaSource.MAX_BYTES) { draft = it; error = null }
                            else error = "本行源码超过 1 MiB"
                        }, enabled = !busy && scopeDraft != null,
                            textStyle = TextStyle(fontSize = 12.sp, fontFamily = FontFamily.Monospace),
                            modifier = Modifier.fillMaxWidth().then(if (fullScreen) Modifier.height(sourceHeight) else
                                Modifier.heightIn(min = minOf(90.dp, sourceHeight), max = sourceHeight))
                                .verticalScroll(rememberScrollState()).padding(8.dp))
                    }
                }
                if (busy) TextButton(onClick = { job?.cancel() }) { Text("取消校验") }
            }
        }
    }
    if (picking) StudioPanelDialog("快速选择行", { picking = false }) {
        val visibleRows = remember(rows, query) {
            rows.filter { query.isBlank() || it.label.contains(query) || it.kind.contains(query, true) || (it.index + 1).toString() == query }
        }
        val rowListState = rememberLazyListState()
        LaunchedEffect(query, selectedId) {
            val selectedIndex = visibleRows.indexOfFirst { it.id == selectedId }
            if (selectedIndex >= 0) rowListState.scrollToItem(selectedIndex)
        }
        Column(Modifier.fillMaxWidth().heightIn(max = 320.dp).padding(8.dp)) {
            InspectorSearchField(query, { query = it }, "搜索行号 / 积木名称")
            LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false), state = rowListState) {
                items(visibleRows, key = { it.id }) { row ->
                    Text("${if (row.id == selectedId) "● " else ""}${row.index + 1}. ${row.label}", fontSize = 12.sp,
                        maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        color = if (row.id == selectedId) AutoScriptPalette.Accent else AutoScriptPalette.TextPrimary,
                        modifier = Modifier.fillMaxWidth().clickable { requestRow(row.id); picking = false }.padding(vertical = 8.dp))
                }
            }
        }
    }
    if (discard) StudioConfirmDialog(onDismissRequest = { discard = false; nextRow = null },
        title = { Text(if (nextRow == null) "放弃本行修改？" else "放弃修改并切换行？") },
        text = { Text("尚未校验保存的文本将丢弃，原积木保持不变。") },
        dismissButton = { TextButton(onClick = { discard = false; nextRow = null }) { Text("继续编辑") } },
        confirmButton = { TextButton(onClick = {
            val target = nextRow
            nextRow = null
            discard = false
            if (target == null) onDismiss() else switchRow(target)
        }) { Text("放弃修改") } })
}

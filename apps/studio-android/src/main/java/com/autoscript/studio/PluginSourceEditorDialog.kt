package com.autoscript.studio

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autoscript.core.designsystem.AutoScriptPalette
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout
import com.autoscript.studio.compiler.VisualSourceDocument
import com.autoscript.studio.compiler.VisualSourceException
import com.autoscript.studio.compiler.VisualLuaSource
import com.google.gson.JsonParser
import com.autoscript.runtime.client.RuntimeClient
import com.autoscript.runtime.client.VisualCompileResult

internal suspend fun applyValidatedPluginSource(
    client: RuntimeClient, projectId: String, flowId: String, editor: VisualEditorState,
    expected: String, replacement: String, canApply: () -> Boolean,
): String? {
    if (!canApply() || editor.currentSource != expected) return "编辑上下文已变化，请重新打开源码编辑"
    if (replacement.toByteArray(Charsets.UTF_8).size > 1_048_576) return "源码超过 1 MiB"
    when (val result = withContext(Dispatchers.IO) {
        client.validateVisualDraft(projectId, flowId, replacement.toByteArray(Charsets.UTF_8))
    }) {
        is VisualCompileResult.Invalid -> return buildString {
            result.diagnostic.nodeId?.let { append("节点 ").append(it).append("：") }
            if (result.diagnostic.nodeId == null) result.diagnostic.line?.let { append("JSONL 第 ").append(it).append(" 行：") }
            append(result.diagnostic.message)
        }
        is VisualCompileResult.Unavailable -> return result.message
        is VisualCompileResult.Success -> Unit
    }
    if (!canApply()) return "运行或插件状态已变化，本次修改未应用"
    return editor.replaceRawSource(expected, replacement)
}

/** Both tabs describe the same draft. Only validated IR is ever submitted to the host. */
@Composable
internal fun PluginSourceEditorDialog(
    source: String,
    rootBlockId: String,
    variables: Set<String>,
    onApply: suspend (String, String) -> String?,
    onDismiss: () -> Unit,
) {
    val original = remember { source }
    var originalLua by remember { mutableStateOf<String?>(null) }
    var draft by remember { mutableStateOf(TextFieldValue()) }
    var parsed by remember { mutableStateOf<VisualSourceDocument?>(null) }
    var tab by remember { mutableStateOf("源码") }
    var query by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var discard by remember { mutableStateOf(false) }
    var validationJob by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()
    val applyLatest by rememberUpdatedState(onApply)
    val variablesLatest by rememberUpdatedState(variables)
    LaunchedEffect(original, rootBlockId) {
        try {
            val exported = withContext(Dispatchers.Default) {
                val context = currentCoroutineContext()
                pluginSourceToolchain.export(original, rootBlockId) { context.ensureActive() }
            }
            originalLua = exported.text
            draft = TextFieldValue(exported.text)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (problem: VisualSourceException) { error = problem.message }
        catch (_: Exception) { error = "当前插件数据无法无损导出；原积木未改变。" }
    }
    suspend fun parseDraft(): VisualSourceDocument = withContext(Dispatchers.Default) {
        val context = currentCoroutineContext()
        pluginSourceToolchain.parse(draft.text, original, rootBlockId, variablesLatest) { context.ensureActive() }
    }
    fun startValidation(apply: Boolean) {
        if (busy || originalLua == null) return
        busy = true; error = null
        validationJob = scope.launch {
            try {
                withTimeout(30_000) {
                    val converted = parseDraft()
                    parsed = converted
                    if (apply) {
                        val problem = applyLatest(original, converted.text)
                        error = problem?.let { message ->
                            val location = converted.nodes.entries.firstOrNull { message.contains(it.key) }?.value
                                ?: Regex("JSONL 第 (\\d+) 行").find(message)?.groupValues?.get(1)?.toIntOrNull()
                                    ?.let { converted.nodes.values.elementAtOrNull(it - 1) }
                            if (location == null) message else "第 ${location.line} 行，第 ${location.column} 列：$message"
                        }
                        if (problem == null) onDismiss()
                    } else tab = "积木预览"
                }
            } catch (cancelled: CancellationException) {
                error = "校验已取消或超时，源码草稿保留，原积木未改变。"
                throw cancelled
            } catch (problem: VisualSourceException) { error = problem.message; tab = "源码" }
            catch (_: Exception) { error = "校验失败，原积木未改变；请检查插件数据或运行服务后重试。" }
            finally { busy = false }
        }
    }
    fun close() {
        if (busy) validationJob?.cancel()
        else if (originalLua != null && draft.text != originalLua) discard = true else onDismiss()
    }
    StudioPanelDialog("插件 · 积木 / 源码", ::close, if (busy) "校验中…" else "校验并应用", { startValidation(true) }) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(enabled = !busy, onClick = { tab = "源码" }) { Text("源码") }
                TextButton(enabled = !busy && originalLua != null, onClick = { startValidation(false) }) { Text("积木预览") }
                TextButton(enabled = !busy, onClick = { tab = "函数参考" }) { Text("函数参考") }
            }
            Text("受限 Lua 视图；不支持任意 Lua、模块或自定义函数。校验后同步积木并自动保存，可撤销。变量仍在变量页面维护。", fontSize = 11.sp, color = AutoScriptPalette.TextSecondary)
            Text("${draft.text.lineSequence().count()} 行 · ${draft.text.toByteArray(Charsets.UTF_8).size} 字节 / 1 MiB", fontSize = 10.sp)
            error?.let { Text(it, color = AutoScriptPalette.Danger, fontSize = 11.sp) }
            if (busy) TextButton(onClick = { validationJob?.cancel() }) { Text("取消校验") }
            when (tab) {
                "源码" -> Surface(color = AutoScriptPalette.PageBackground) {
                    BasicTextField(draft, { next ->
                        if (next.text.toByteArray(Charsets.UTF_8).size <= VisualLuaSource.MAX_BYTES) {
                            draft = next; error = null; parsed = null
                        } else error = "源码超过 1 MiB，未接受本次输入。"
                    }, enabled = !busy && originalLua != null,
                        textStyle = TextStyle(fontSize = 12.sp, fontFamily = FontFamily.Monospace),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 260.dp).verticalScroll(rememberScrollState()).padding(8.dp))
                }
                "积木预览" -> {
                    Text("预览尚未写入；点击条目定位源码。保存时还会进行完整编译与能力校验。", fontSize = 10.sp)
                    val rows = remember(parsed) { parsed?.text?.lineSequence()?.filter(String::isNotBlank)?.map { JsonParser.parseString(it).asJsonObject }?.toList().orEmpty() }
                    if (rows.isEmpty()) Text("空插件", fontSize = 12.sp)
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 230.dp)) {
                        items(rows) { node ->
                            val location = parsed?.nodes?.get(node["nodeId"].asString)
                            Row(Modifier.fillMaxWidth().clickable {
                                tab = "源码"
                                location?.let { at ->
                                    val offset = draft.text.lineSequence().take(at.line - 1).sumOf { it.length + 1 }.coerceAtMost(draft.text.length)
                                    draft = draft.copy(selection = TextRange(offset))
                                }
                            }.padding(start = (node["depth"].asInt * 8).coerceAtMost(80).dp, top = 5.dp, bottom = 5.dp)) {
                                Text("${if (node["disabled"]?.asBoolean == true) "[禁用] " else ""}${BlockCatalog.find(node["kind"].asString)?.title ?: node["kind"].asString}", fontSize = 12.sp, modifier = Modifier.weight(1f))
                                Text("${location?.line ?: ""} 行", fontSize = 10.sp, color = AutoScriptPalette.TextSecondary)
                            }
                        }
                    }
                }
                else -> {
                    OutlinedTextField(query, { query = it }, label = { Text("查找函数 / 积木") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Text("例：count = 3；count = count + 1；if count > 0 then … end。复杂参数使用函数具名表；数组 Json.Array({…})，空值 Json.Null。", fontSize = 10.sp)
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 190.dp)) {
                        items(BlockCatalog.all.filter { query.isBlank() || it.title.contains(query) || it.kind.contains(query, true) }) { contract ->
                            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                                Text("${contract.title} · ${VisualLuaSource.name(contract.kind)}", fontSize = 12.sp, color = AutoScriptPalette.Accent)
                                Text(contract.properties.joinToString(" · ") { it.path }, fontSize = 10.sp, color = AutoScriptPalette.TextSecondary)
                            }
                        }
                    }
                }
            }
        }
    }
    if (discard) StudioConfirmDialog(onDismissRequest = { discard = false }, title = { Text("放弃源码修改？") },
        text = { Text("尚未应用的源码修改将被丢弃，原来的积木不受影响。") },
        dismissButton = { TextButton(onClick = { discard = false }) { Text("继续编辑") } },
        confirmButton = { TextButton(onClick = onDismiss) { Text("放弃修改") } })
}

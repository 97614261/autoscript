package com.autoscript.studio

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autoscript.core.designsystem.AutoScriptPalette
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

@Composable
internal fun EditorBatchParametersDialog(request: EditorBatchParameters, onDismiss: () -> Unit,
    onApply: suspend (EditorBatchCandidate) -> String?) {
    var inputs by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var fields by remember { mutableStateOf<Set<String>>(emptySet()) }
    var error by remember { mutableStateOf<String?>(null) }
    var candidate by remember { mutableStateOf<EditorBatchCandidate?>(null) }
    var fullScreen by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var dirty by remember { mutableStateOf(false) }
    var discard by remember { mutableStateOf(false) }
    val owner = rememberCoroutineScope()
    var job by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    fun close() { if (dirty || busy) discard = true else onDismiss() }
    StudioPanelDialog("批量参数 · ${request.context.selection.size}行", onDismiss = ::close,
        compactHeader = true, showFooter = false,
        fullScreen = fullScreen, onToggleFullScreen = { fullScreen = !fullScreen }) {
        Column(Modifier.fillMaxWidth().then(if (fullScreen) Modifier.fillMaxHeight() else Modifier.heightIn(max = 440.dp))) {
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("${request.title} · 仅选中行，不自动改子积木", fontSize = 11.sp, color = EditorBlue)
                Text("位置：${request.rows.take(16).joinToString("、")}行${if (request.rows.size > 16) "等" else ""}", fontSize = 10.sp)
                request.fields.forEach { field ->
                    Row(Modifier.fillMaxWidth().clickable(enabled = !busy) {
                        dirty = true; error = null
                        fields = if (field.path in fields) fields - field.path else fields + field.path
                    }, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Checkbox(field.path in fields, onCheckedChange = null, modifier = Modifier.size(22.dp))
                        Column(Modifier.weight(1f)) {
                            Text(field.label, fontSize = 12.sp)
                            Text("原值：${field.values.distinct().take(6).joinToString("、")}${if (field.values.distinct().size > 6) "…" else ""}",
                                fontSize = 10.sp, color = AutoScriptPalette.TextSecondary)
                        }
                    }
                    if (field.path in fields) {
                        if (field.choices.isNotEmpty()) EditorCompactActions {
                            field.choices.forEach { value -> EditorCompactButton(enabled = !busy, onClick = {
                                inputs = inputs + (field.path to value); dirty = true; error = null
                            }) { Text("${if (inputs[field.path] == value) "✓ " else ""}${editorBatchChoiceLabel(field.path, value)}", fontSize = 11.sp) } }
                        } else {
                            InspectorSearchField(inputs[field.path].orEmpty(), { if (!busy) {
                                inputs = inputs + (field.path to it.take(32)); dirty = true; error = null
                            } }, "统一改为（整数）")
                            Text(when {
                                field.path == "similarityPermille" -> "0–1000，900表示90%"
                                field.path == "tolerance" -> "颜色容差0–255"
                                field.path.endsWith("Ms") || field.path == "milliseconds" -> "单位：毫秒；1000毫秒=1秒"
                                else -> "范围：0–2147483647；具体限制由项目校验检查"
                            }, fontSize = 10.sp, color = AutoScriptPalette.TextSecondary)
                        }
                    }
                }
                error?.let { Text(it, fontSize = 11.sp, color = AutoScriptPalette.Danger) }
            }
            EditorCompactActions {
                EditorCompactButton(onClick = ::close) { Text("取消", fontSize = 11.sp) }
                EditorCompactButton(enabled = !busy && fields.isNotEmpty(), onClick = {
                    val submitted = fields.associateWith { inputs[it].orEmpty() }
                    busy = true; error = null
                    job = owner.launch {
                        try {
                            candidate = withContext(Dispatchers.Default) {
                                val task = currentCoroutineContext()
                                editorBatchCandidate(request, submitted) { task.ensureActive() }
                            }
                        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                        catch (failure: Exception) { error = failure.message ?: "输入不合法" }
                        finally { busy = false }
                    }
                }) { Text(if (busy) "处理中…" else "预览并确认", fontSize = 11.sp) }
            }
        }
    }
    candidate?.let { prepared ->
        StudioConfirmDialog(onDismissRequest = { candidate = null }, title = { Text("确认修改${prepared.changed}行") },
            text = {
                Column(Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState())) {
                    request.fields.filter { it.path in fields }.forEach {
                        Text("${it.label} → ${editorBatchChoiceLabel(it.path, inputs[it.path].orEmpty())}", fontSize = 12.sp)
                    }
                    Text("其他参数和未选中子行不变，可一次撤销。只应用草稿，不自动保存或运行。", fontSize = 11.sp)
                }
            },
            actions = listOf(StudioConfirmAction("返回修改", { candidate = null }), StudioConfirmAction("校验并应用", {
                candidate = null; busy = true; error = null
                job = owner.launch {
                    try { error = onApply(prepared); if (error == null) onDismiss() }
                    catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                    catch (_: Exception) { error = "校验或应用失败，未完成本次修改" }
                    finally { busy = false }
                }
            })))
    }
    if (discard) StudioConfirmDialog(onDismissRequest = { discard = false }, title = { Text("放弃批量参数输入？") },
        text = { Text("未应用的内容将丢弃，正在进行的校验会取消。") },
        actions = listOf(StudioConfirmAction("继续编辑", { discard = false }), StudioConfirmAction("放弃", { job?.cancel(); onDismiss() })))
}

internal fun editorBatchChoiceLabel(path: String, value: String): String = when {
    value == "true" -> "开启"
    value == "false" -> "关闭"
    path == "level" -> when (value) { "info" -> "普通"; "warn" -> "警告"; "error" -> "错误"; else -> value }
    path == "direction" -> value.toIntOrNull()?.let { recognitionSearchDirections.getOrNull(it) } ?: value
    else -> value
}

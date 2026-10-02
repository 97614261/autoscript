package com.autoscript.studio

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.autoscript.core.designsystem.AutoScriptPalette
import com.autoscript.project.store.ProjectSnapshot
import com.autoscript.runtime.api.RuntimeDebugReply
import com.autoscript.runtime.api.RuntimeDebugValue
import com.autoscript.runtime.client.RuntimeClient
import com.autoscript.runtime.client.VisualCompileResult
import com.autoscript.studio.generated.BlockContract
import com.autoscript.studio.generated.BlockPropertyContract
import com.autoscript.studio.generated.BlockPropertyEditor
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal fun backfillFields(contract: BlockContract?) = contract?.properties.orEmpty().filter {
    it.editor in setOf(BlockPropertyEditor.INTEGER, BlockPropertyEditor.NUMBER, BlockPropertyEditor.BOOLEAN, BlockPropertyEditor.SCALAR) ||
        (it.editor == BlockPropertyEditor.STRING && it.path in setOf("message", "text", "content"))
}

internal fun backfillArguments(arguments: JsonObject, field: BlockPropertyContract, value: RuntimeDebugValue): JsonObject {
    require(!value.truncated && value.type != "nil") { "空值或截断值不能回填" }
    val scalar = when (value.type) {
        "integer" -> JsonPrimitive(value.value.toLong())
        "number" -> JsonPrimitive(value.value.toDouble().also { require(it.isFinite()) })
        "boolean" -> JsonPrimitive(value.value.toBooleanStrict())
        "string" -> JsonPrimitive(value.value)
        else -> error("不支持的变量类型")
    }
    require(when (field.editor) {
        BlockPropertyEditor.INTEGER -> value.type == "integer"
        BlockPropertyEditor.NUMBER -> value.type in setOf("integer", "number")
        BlockPropertyEditor.BOOLEAN -> value.type == "boolean"
        BlockPropertyEditor.STRING -> value.type == "string"
        BlockPropertyEditor.SCALAR -> true
        else -> false
    }) { "变量类型与参数不匹配" }
    return arguments.deepCopy().apply {
        add(field.path, scalar)
        remove("${field.path}Variable")
        if (field.path == "message") remove("valueVariable")
    }
}

/** Conservative maintenance warnings; compiler validation remains authoritative. */
internal fun visualVariableWarnings(editor: VisualEditorState, snapshot: ProjectSnapshot, flowId: String): List<String> {
    val declared = snapshot.manifest.variables.filter { it.flowId == null || it.flowId == flowId }.map { it.name }.toSet() +
        snapshot.manifest.flows.firstOrNull { it.flowId == flowId }?.params.orEmpty().mapNotNull { it.get("name")?.asString }
    val warnings = linkedSetOf<String>()
    val writes = setOf("resultVariable", "foundVariable", "xVariable", "yVariable", "indexVariable", "elapsedVariable", "countVariable")
    val assignments = mutableMapOf<String, MutableSet<String>>()
    val initial = snapshot.manifest.variables.filter { (it.flowId == null || it.flowId == flowId) && it.type != com.autoscript.project.store.ProjectVariableType.IMAGE }.map { it.name }.toSet() +
        snapshot.manifest.flows.firstOrNull { it.flowId == flowId }?.params.orEmpty().filter { it.get("required")?.asBoolean == true }.mapNotNull { it.get("name")?.asString }
    val rows = editor.rows
    val rowsById = rows.associateBy { it.nodeId }
    val nodes = editor.currentSource.lineSequence().filter(String::isNotBlank).mapNotNull { line ->
        runCatching { com.google.gson.JsonParser.parseString(line).asJsonObject }.getOrNull()
    }.associateBy { it.get("nodeId")?.asString.orEmpty() }
    editor.rows.forEach { row ->
        if (editor.isNodeDisabled(row.nodeId)) return@forEach
        val json = nodes[row.nodeId] ?: return@forEach
        val args = json.getAsJsonObject("args") ?: return@forEach
        val assigned = assignments.getOrPut(row.blockId) {
            val parent = json.get("parentId")?.takeUnless { it.isJsonNull }?.asString
            val parentBlock = rowsById[parent]?.blockId
            (assignments[parentBlock] ?: initial).toMutableSet()
        }
        val nodeWrites = mutableSetOf<String>()
        if (row.kind == "variable.calculate") {
            calculationReferencedNames(args["expression"]?.asString.orEmpty()).forEach { name ->
                if (name !in declared && warnings.size < 128) warnings += "${row.nodeId} · $name：计算来源未声明"
                else if (name !in assigned && warnings.size < 128) warnings += "${row.nodeId} · $name：计算前可能未赋值（保守检查）"
            }
        }
        fun visit(json: com.google.gson.JsonElement, depth: Int = 0) {
            if (depth > 32 || warnings.size >= 128) return
            if (json.isJsonObject) json.asJsonObject.entrySet().forEach { (key, value) ->
                if ((key == "variable" || key.endsWith("Variable") || (key == "name" && row.kind in setOf("variable.set", "variable.calculate", "control.loopmetric")) ||
                        (key in setOf("sourceName", "name") && row.kind == "variable.copy")) && value.isJsonPrimitive && value.asJsonPrimitive.isString
                ) {
                    val name = value.asString
                    val output = key in writes || (key == "name" && row.kind in setOf("variable.set", "variable.copy", "variable.calculate", "control.loopmetric"))
                    if (name.isNotBlank() && name !in declared) warnings += "${row.nodeId} · $name：未维护到变量表（${if (output) "输出" else "引用/赋值"}）"
                    if (output) {
                        if (key !in setOf("xVariable", "yVariable")) nodeWrites += name
                    } else if (name.isNotBlank() && name !in assigned && warnings.size < 128) warnings += "${row.nodeId} · $name：使用前可能未赋值（保守检查，分支/调用需人工确认）"
                } else if (value.isJsonObject || value.isJsonArray) visit(value, depth + 1)
            } else if (json.isJsonArray) json.asJsonArray.forEach { visit(it, depth + 1) }
        }
        visit(args)
        assigned += nodeWrites
    }
    return warnings.toList()
}

@Composable
internal fun VisualDebugInspector(
    mode: EditorToolPanel,
    snapshot: ProjectSnapshot,
    flowId: String,
    editor: VisualEditorState,
    runtimeClient: RuntimeClient,
    onDismiss: () -> Unit,
    onBackfill: suspend (String, JsonObject) -> String?,
) {
    var result by remember { mutableStateOf(RuntimeDebugReply.unavailable()) }
    var message by remember { mutableStateOf("点击刷新读取当前运行会话的变量。数据不会写入普通日志。") }
    var busy by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<RuntimeDebugValue?>(null) }
    var field by remember { mutableStateOf<BlockPropertyContract?>(null) }
    val scope = rememberCoroutineScope()
    val targetId = remember { editor.selectedNodeId.takeIf { editor.selectedNodeIds.size <= 1 } }
    val target = editor.rows.firstOrNull { it.nodeId == targetId }
    val fields = backfillFields(target?.kind?.let(BlockCatalog::find))
    val check = mode == EditorToolPanel.VARIABLE_CHECK
    Dialog(onDismissRequest = { if (!busy) onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth(.94f).widthIn(max = 580.dp).fillMaxHeight(.72f), color = androidx.compose.ui.graphics.Color.White) {
            Column {
                Text(when(mode) { EditorToolPanel.DATA_BACKFILL -> "数据回填"; EditorToolPanel.VARIABLE_CHECK -> "变量检查"; else -> "变量信息" }, color = AutoScriptPalette.Accent, modifier = Modifier.padding(14.dp))
                HorizontalDivider()
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (check) {
                        Text("维护检查：未维护到变量表不等于运行错误。参数类型和流程合法性以编译器校验为准。")
                        val warnings = visualVariableWarnings(editor, snapshot, flowId)
                        if (warnings.isEmpty()) Text("本插件未发现变量维护警告。此结果不代表所有执行分支都已赋值。")
                        warnings.forEach { Text(it) }
                        Button(enabled = !busy, onClick = {
                            busy = true
                            scope.launch {
                                message = when (val validation = withContext(Dispatchers.IO) {
                                    runtimeClient.validateVisualDraft(snapshot.manifest.projectId, flowId, editor.currentSource.toByteArray(Charsets.UTF_8))
                                }) {
                                    is VisualCompileResult.Success -> "本插件编译器校验通过（非全路径赋值证明）"
                                    is VisualCompileResult.Invalid -> validation.diagnostic.message
                                    is VisualCompileResult.Unavailable -> validation.message
                                }
                                busy = false
                            }
                        }) { Text("编译器参数 / 类型校验") }
                        Text(message)
                    } else {
                        Button(enabled = !busy, onClick = {
                            busy = true
                            selected = null
                            scope.launch {
                                result = withContext(Dispatchers.IO) { runtimeClient.debugSnapshot(snapshot.manifest.projectId) }
                                message = if (result.status == RuntimeDebugReply.SUCCESS) "插件 ${result.flowId} · 节点 ${result.nodeId}（最近检查点，最多128项）" else "没有可用快照，请运行或单步执行本项目。"
                                busy = false
                            }
                        }) { Text(if (busy) "读取中…" else "刷新变量") }
                        Text(message)
                        result.variables.forEach { value ->
                            Text("${if (value.scope == "local") "局部" else "全局"} · ${value.name} [${value.type}] = ${if (value.type == "nil") "nil" else value.value}${if (value.truncated) "…（已截断）" else ""}",
                                Modifier.fillMaxWidth().clickable(enabled = !busy) { selected = value }.padding(vertical = 8.dp),
                                color = if (selected == value) AutoScriptPalette.Accent else AutoScriptPalette.TextPrimary)
                        }
                        if (mode == EditorToolPanel.DATA_BACKFILL) {
                            Text("目标：${targetId ?: "请先单选节点"}；回填会覆盖所选参数。")
                            fields.forEach { candidate ->
                                Text("${if (field == candidate) "● " else "○ "}${candidate.label}", Modifier.clickable(enabled = !busy) { field = candidate }.padding(8.dp))
                            }
                            if (fields.isEmpty()) Text("当前节点没有可回填的标量参数。")
                            Button(enabled = !busy && selected != null && field != null && targetId != null && result.flowId == flowId, onClick = {
                                val nodeId = targetId ?: return@Button
                                val updated = runCatching { backfillArguments(requireNotNull(editor.nodeArguments(nodeId)), requireNotNull(field), requireNotNull(selected)) }
                                if (updated.isFailure) message = updated.exceptionOrNull()?.message ?: "无法回填"
                                else {
                                    busy = true
                                    scope.launch {
                                        message = onBackfill(nodeId, updated.getOrThrow()) ?: "已回填；可以撤销"
                                        busy = false
                                    }
                                }
                            }) { Text("确认回填") }
                        }
                    }
                }
                HorizontalDivider()
                TextButton(onClick = onDismiss, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("关闭") }
            }
        }
    }
}

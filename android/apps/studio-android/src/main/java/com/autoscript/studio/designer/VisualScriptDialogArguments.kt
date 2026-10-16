package com.autoscript.studio

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autoscript.project.store.ProjectSnapshot
import com.autoscript.project.store.ProjectVariableType
import com.autoscript.core.designsystem.AutoScriptPalette
import com.autoscript.script.ui.ScriptUiDefinition
import com.autoscript.studio.generated.BlockContract
import com.google.gson.JsonObject

internal fun scriptDialogArgumentError(kind: String, inputs: Map<String, String>, pages: Set<String>, variables: Set<String>): String? {
    val time = inputs["timeoutMs"]?.toLongOrNull()
    if (time == null || time !in 1000..1800000) return "等待期限为1000～1800000毫秒"
    val result = inputs["resultVariable"].orEmpty()
    if ((kind == "ui.confirm" || result.isNotEmpty()) && result !in variables) return "请选择已声明的字符结果变量"
    if (kind == "ui.dialog") {
        if (inputs["pageId"] !in pages) return "请选择已保存的设计页面"
        if (inputs["blockOutside"]?.toBooleanStrictOrNull() == null) return "请选择窗外关闭策略"
    } else for ((key, maximum) in listOf("title" to 192, "message" to 4096, "confirmText" to 96, "cancelText" to 96)) {
        val text = inputs[key].orEmpty()
        if (text.isEmpty() || '\u0000' in text || text.toByteArray(Charsets.UTF_8).size > maximum) return "文字为空或超长：$key（最多$maximum UTF-8字节）"
    }
    return null
}

@Composable
internal fun VisualScriptDialogArguments(contract: BlockContract, arguments: JsonObject, project: ProjectSnapshot, flowId: String,
    onManageVariables: (() -> Unit)?, onDismiss: () -> Unit, onConfirm: (JsonObject) -> Unit, confirmLabel: String) {
    val model = remember(project.manifest.runnerUi) { project.manifest.runnerUi?.let { ScriptUiDefinition.parse(it.toString()) } }
    var inputs by remember(contract.kind, arguments) { mutableStateOf(blockPropertyTexts(contract, arguments)) }
    var error by remember { mutableStateOf<String?>(null) }
    val variables = calculationVariables(project.manifest.variables, flowId, project.manifest.flows)
        .filter { it.type == ProjectVariableType.STRING }.map { it.name }
    val layout = contract.kind == "ui.dialog"
    fun update(key: String, text: String) { inputs = inputs + (key to text); error = null }
    StudioPanelDialog(contract.title, onDismiss, confirmLabel, onConfirm = {
        error = scriptDialogArgumentError(contract.kind, inputs, model?.pages?.map { it.id }?.toSet().orEmpty(), variables.toSet())
        if (error == null) {
            val parsed = parseBlockArguments(contract, inputs, null, emptyMap(), project.manifest.resources)
            error = parsed.second
            parsed.first?.let(onConfirm)
        }
    }) {
        Column(Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (layout) {
                val pages = model?.pages.orEmpty()
                DesignerChoice("设计页面", pages.find { it.id == inputs["pageId"] }?.title ?: "请选择", pages.map { it.id to it.title }, true) { update("pageId", it) }
                DesignerToggle("禁止点击窗外关闭", inputs["blockOutside"] == "true", true) { update("blockOutside", it.toString()) }
                Text("返回键仍可关闭。页面按钮绑定「关闭」时只关闭布局框，填写值保留。", fontSize = 11.sp)
                if (model == null) Text("请先在界面设计器创建并保存页面。")
            } else {
                for ((key, label) in listOf("title" to "标题", "message" to "内容", "confirmText" to "确定按钮", "cancelText" to "取消按钮")) {
                    OutlinedTextField(inputs[key].orEmpty(), { update(key, it) }, label = { Text(label) }, singleLine = key != "message", maxLines = if (key == "message") 4 else 1, modifier = Modifier.fillMaxWidth())
                }
            }
            OutlinedTextField(inputs["timeoutMs"].orEmpty(), { update("timeoutMs", it) }, label = { Text("等待期限 · 毫秒") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            val result = inputs["resultVariable"].orEmpty()
            DesignerChoice("结果变量 · 字符", result.ifEmpty { if (layout) "不接收" else "请选择" },
                (if (layout) listOf("" to "不接收") else emptyList()) + variables.map { it to it }, true) { update("resultVariable", it) }
            if (onManageVariables != null) TextButton(onClick = onManageVariables) { Text("维护变量", fontSize = 12.sp) }
            Text(if (layout) "结果：close关闭 / dismiss返回或窗外 / timeout到期。控件值用读取页面参数获取。" else "结果：confirm确定 / cancel取消 / dismiss返回或窗外 / timeout到期。接判断积木比较 confirm。", fontSize = 11.sp)
            error?.let { Text(it, color = AutoScriptPalette.Accent, fontSize = 12.sp) }
        }
    }
}

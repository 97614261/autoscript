package com.autoscript.studio

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autoscript.project.store.ProjectSnapshot
import com.autoscript.project.store.ProjectVariableType
import com.autoscript.studio.generated.BlockContract
import com.google.gson.JsonObject

@Composable
internal fun RecognitionResultReaderDialog(project: ProjectSnapshot, flowId: String, visible: Boolean,
    onManageVariables: () -> Unit, onDismiss: () -> Unit, onConfirm: (List<Pair<BlockContract, JsonObject>>) -> Unit,
    initial: JsonObject? = null, confirmLabel: String = "选择位置并加入") {
    val initialShape = runCatching { RecognitionResultShape.valueOf(initial?.get("shape")?.asString.orEmpty()) }.getOrDefault(RecognitionResultShape.IMAGES)
    var shape by remember(initial) { mutableStateOf(initialShape) }
    var field by remember(initial) { mutableStateOf(initial?.get("field")?.asString ?: "count") }
    var index by remember(initial) { mutableStateOf(initial?.get("index")?.asString ?: "0") }
    var source by remember(initial) { mutableStateOf(initial?.get("sourceVariable")?.asString.orEmpty()) }
    var target by remember(initial) { mutableStateOf(initial?.get("resultVariable")?.asString.orEmpty()) }
    val baseline = remember(initial) { listOf(shape.name, field, index, source, target) }
    var error by remember { mutableStateOf<String?>(null) }
    var discard by remember { mutableStateOf(false) }
    var fullScreen by remember { mutableStateOf(false) }
    val variables = calculationVariables(project.manifest.variables, flowId, project.manifest.flows)
    fun choices(type: ProjectVariableType) = variables.filter { it.type == type }.map { it.name to it.name }
    fun close() { if (listOf(shape.name, field, index, source, target) != baseline) discard = true else onDismiss() }
    if (visible) StudioPanelDialog("读取识别结果", ::close, confirmLabel, fullScreen = fullScreen, onToggleFullScreen = { fullScreen = !fullScreen }, onConfirm = {
        runCatching { recognitionResultBlocks(shape, field, requireNotNull(index.toIntOrNull()) { "序号须为整数" }, source, "", target, variables) }
            .onSuccess(onConfirm).onFailure { error = it.message ?: "结果配置无效" }
    }) {
        Column(Modifier.fillMaxWidth().heightIn(max = if (fullScreen) 900.dp else 420.dp).verticalScroll(rememberScrollState()).padding(10.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            ImageToolChoice("结果类型", shape.name, RecognitionResultShape.entries.map { it.name to it.label }, true) {
                val previousType=recognitionResultType(field)
                shape = RecognitionResultShape.valueOf(it); field = shape.fields.first(); index = if (field == "count" || shape.isSummary()) "0" else "1"
                if(previousType!=recognitionResultType(field)) target=""
                error = null
            }
            CompactVariableChoice("结果来源", source, choices(ProjectVariableType.STRING), true, variables) { source = it; if(target==source) target=""; error = null }
            Text(recognitionResultProducerHint(shape),fontSize=10.sp)
            ImageToolChoice("读取属性", field, shape.fields.map { it to recognitionResultFieldLabel(it) }, true) {
                val previousType=recognitionResultType(field)
                field = it; index = if (it in setOf("count", "truncated") || shape.isSummary()) "0" else index.takeIf { value -> value.toIntOrNull()?.let { n -> n>0 }==true } ?: "1"
                if(previousType!=recognitionResultType(field)) target=""
                error = null
            }
            if (!shape.isSummary() && field !in setOf("count", "truncated"))
                ImageToolCompactInput("结果序号 · 从1开始", index, true, Modifier.fillMaxWidth(), numeric = true) { index = it; error = null }
            CompactVariableChoice("接收 · ${calculationTypeLabel(recognitionResultType(field))}", target, choices(recognitionResultType(field)).filter { it.first!=source }, true, variables) { target = it; error = null }
            DesignerAction("维护变量", onClick = onManageVariables)
            Text("只读取已有结果；坐标属于来源图像，序号从1开始。", fontSize = 10.sp)
            if(shape in setOf(RecognitionResultShape.GLYPH_ITEM,RecognitionResultShape.GLYPH_SUMMARY))
                Text("来源必须是运行时“字库逐字结果”的字符输出，不是普通取字文本或本次预览；所选序号只代表下一次结果中的位置。",fontSize=10.sp)
            ImageToolHelp("运行时仍校验结果格式；已识别/截断为整数1/0。最多64KiB，字库/文字/颜色2048项、连通块256项、找图64项。空集合可读数量0，缺失字段、形状不符或序号越界明确失败。")
            error?.let { Text(it, fontSize = 11.sp, color = com.autoscript.core.designsystem.AutoScriptPalette.Danger) }
        }
    }
    if (discard && visible) EditorPromptDialog("放弃结果读取配置？", "尚未加入的变量选择将丢弃。", { discard = false }, { discard = false; onDismiss() }, "继续编辑", "放弃")
}

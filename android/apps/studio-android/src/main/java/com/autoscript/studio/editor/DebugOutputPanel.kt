package com.autoscript.studio

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autoscript.core.designsystem.AutoScriptPalette

@Composable
internal fun DebugOutputPanel(
    outputMode: Int,
    onOutputModeChange: (Int) -> Unit,
    outputField: TextFieldValue,
    expressionMode: Boolean,
    expressionAvailable: Boolean,
    onExpressionModeChange: (Boolean) -> Unit,
    onOutputTextChange: (TextFieldValue) -> Unit,
    onExpressionEdit: ((TextFieldValue) -> TextFieldValue?) -> Unit,
    selectedVariable: String?,
    logLevel: String,
    onLogLevelChange: (String) -> Unit,
    error: String?,
    onClearVariable: () -> Unit,
    onPickVariable: (Boolean) -> Unit,
    onPickDirectVariable: () -> Unit,
) {
    val channels = listOf("运行提示", "弹出提示", "开发日志", "添加说明")
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val wide = studioWideShortLayout(maxWidth.value, maxHeight.value, LocalDensity.current.fontScale, minimumWidthDp = 500)
        val short = studioShortViewport(maxHeight.value)
        val columns = debugOutputChoiceColumns(maxWidth.value, LocalDensity.current.fontScale)
        val form: @Composable () -> Unit = {
            DebugOutputForm(outputMode, outputField, expressionMode, expressionAvailable, onExpressionModeChange,
                onOutputTextChange, onExpressionEdit, selectedVariable, logLevel, onLogLevelChange, error,
                onClearVariable, onPickVariable, onPickDirectVariable, short)
        }
        if (wide) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.width(112.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                channels.forEachIndexed { index, title ->
                    StudioToolRailItem(title, index == outputMode) { onOutputModeChange(index) }
                }
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) { form() }
        } else Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            channels.withIndex().toList().chunked(columns).forEach { row ->
                StudioToolSegments(outputMode.toString(), row.map { it.index.toString() to it.value }) { onOutputModeChange(it.toInt()) }
            }
            form()
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun DebugOutputForm(
    mode: Int, field: TextFieldValue, expression: Boolean, expressionAvailable: Boolean,
    onExpressionModeChange: (Boolean) -> Unit, onTextChange: (TextFieldValue) -> Unit,
    onEdit: ((TextFieldValue) -> TextFieldValue?) -> Unit,
    variable: String?, level: String, onLevelChange: (String) -> Unit, error: String?,
    onClearVariable: () -> Unit, onPickVariable: (Boolean) -> Unit, onPickDirectVariable: () -> Unit,
    short: Boolean,
) {
    var showMore by remember { mutableStateOf(false) }
    var showDescription by remember(variable) { mutableStateOf(field.text.isNotBlank()) }
    val variableSource = !expression && variable != null && mode != DebugOutputCode.COMMENT
    Text(when (mode) {
        DebugOutputCode.RUN_PROMPT -> "运行时持续显示；脚本结束即关闭。最终结果可用「弹出提示」。"
        DebugOutputCode.TOAST -> "短时显示；脚本正常结束后仍保留至到期，适合输出识别结果。"
        DebugOutputCode.LOG -> "写入脚本日志，可在运行日志中查看。"
        else -> "仅在积木区显示说明，不参与执行。"
    }, fontSize = 10.sp, lineHeight = 15.sp, color = AutoScriptPalette.TextSecondary)
    if (mode != DebugOutputCode.COMMENT) {
        val sources = if (expression && !expressionAvailable) listOf("expression" to "表达式")
            else listOf("text" to "文字", "variable" to "变量") + if (expressionAvailable) listOf("expression" to "表达式") else emptyList()
        StudioToolSegments(if (expression) "expression" else if (variableSource) "variable" else "text", sources) { source ->
            when (source) {
                "variable" -> onPickDirectVariable()
                "expression" -> onExpressionModeChange(true)
                else -> { onExpressionModeChange(false); onClearVariable() }
            }
        }
    }
    if (variableSource) {
        StudioFormGroup("运行时读取变量的值") {
            Row(Modifier.fillMaxWidth().background(AutoScriptPalette.VisualEditor.ControlBackground, RoundedCornerShape(6.dp))
                .border(1.dp, AutoScriptPalette.VisualEditor.Border, RoundedCornerShape(6.dp))
                .clickable(onClick = onPickDirectVariable).padding(start = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(variable.orEmpty(), Modifier.weight(1f).padding(vertical = 8.dp), fontSize = 13.sp,
                    lineHeight = 17.sp, color = AutoScriptPalette.TextPrimary, fontWeight = FontWeight.Medium)
                ImageToolCompactAction("更换", true, onClick = onPickDirectVariable)
            }
        }
        Row(Modifier.fillMaxWidth().clickable { showDescription = !showDescription }.padding(vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text("编辑器说明（可选，不输出）", Modifier.weight(1f), fontSize = 10.sp, color = AutoScriptPalette.TextSecondary)
            Text(if (showDescription) "收起 ∧" else "展开 ∨", fontSize = 10.sp, color = AutoScriptPalette.Accent)
        }
    }
    if (!variableSource || showDescription) StudioFormGroup(when {
        variableSource -> ""
        expression -> "表达式"
        mode == DebugOutputCode.COMMENT -> "说明内容"
        else -> "文字内容"
    }) {
        val shape = RoundedCornerShape(6.dp)
        BasicTextField(field, onTextChange,
            textStyle = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, color = AutoScriptPalette.TextPrimary),
            minLines = if (short || variableSource) 2 else 3, maxLines = 8,
            modifier = Modifier.fillMaxWidth().background(AutoScriptPalette.VisualEditor.ControlBackground, shape)
                .border(1.dp, AutoScriptPalette.VisualEditor.Border, shape).padding(9.dp),
            decorationBox = { input ->
                Box(Modifier.fillMaxWidth()) {
                    if (field.text.isEmpty()) Text(when {
                        expression -> "例如：\"次数=\" .. text(次数)"
                        variableSource -> "只用于辨认这条积木，可留空"
                        mode == DebugOutputCode.COMMENT -> "输入这条积木的说明"
                        else -> "输入要显示的文字"
                    }, fontSize = 13.sp, lineHeight = 18.sp, color = AutoScriptPalette.TextSecondary.copy(alpha = .65f))
                    input()
                }
            })
    }
    if (expression) {
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            StudioToolButton("变量值") { onPickVariable(false) }
            StudioToolButton("名称=值") { onPickVariable(true) }
            StudioToolButton("插文字") { onEdit { DebugOutputExpression.literal(it, "", editInside = true) } }
            StudioToolButton("换行") { onEdit { DebugOutputExpression.literal(it, "\n") } }
            StudioToolButton("更多", selected = showMore) { showMore = !showMore }
        }
        if (showMore) {
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                StudioToolButton("空格") { onEdit { DebugOutputExpression.literal(it, " ") } }
                StudioToolButton("转文字") { onEdit(DebugOutputExpression::asText) }
                listOf("+" to " + ", "−" to " - ", "×" to " * ", "÷" to " / ", "(" to "(", ")" to ")", "拼接 .." to " .. ").forEach { (label, token) ->
                    StudioToolButton(label) { onEdit { DebugOutputExpression.token(it, token) } }
                }
            }
            Text("按光标插入、替换选区；字符串直接拼接，数值自动转为文字。", fontSize = 10.sp, color = AutoScriptPalette.TextSecondary)
        }
    }
    if (mode == DebugOutputCode.LOG && !expression) StudioCompactChoice("日志级别", level,
        listOf("info" to "信息", "warn" to "警告", "error" to "错误"), true, onLevelChange)
    error?.let { Text(it, color = AutoScriptPalette.Danger, fontSize = 11.sp, lineHeight = 16.sp) }
}

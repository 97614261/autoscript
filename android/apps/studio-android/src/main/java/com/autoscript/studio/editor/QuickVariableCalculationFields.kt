package com.autoscript.studio

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autoscript.core.designsystem.AutoScriptPalette
import com.autoscript.project.store.ProjectVariableType

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun QuickVariableCalculationFields(state: QuickCalculationState, variables: List<CalculationVariable>,
    onChange: (QuickCalculationState) -> Unit, onManageVariables: (() -> Unit)?) {
    val operation = quickCalculationOperations.single { it.id == state.operation }
    var chooseOperation by remember { mutableStateOf(false) }
    StudioToolButton("${operation.group}  ·  ${operation.label}  ▾", Modifier.fillMaxWidth()) { chooseOperation = true }
    if (chooseOperation) QuickCalculationOperationPicker(state.operation, { chooseOperation = false }) { id ->
        onChange(newQuickCalculationState(id, state, variables)); chooseOperation = false
    }
    operation.inputs.forEachIndexed { index, input ->
        key(state.operation, index) {
            CalculationOperandField(input.label, state.operands[index], input.kind, variables, onManageVariables) { operand ->
                onChange(state.copy(operands = state.operands.toMutableList().apply { set(index, operand) }))
            }
        }
    }
    if (state.operation in setOf("split", "splitAny")) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf("," to "逗号", "，" to "中文逗号", ";" to "分号", " " to "空格", "\n" to "换行", "\t" to "Tab").forEach { (value, label) ->
                StudioToolButton(label, selected = state.operands[1].variable.isEmpty() &&
                    if (state.operation == "splitAny") value in state.operands[1].literal else value == state.operands[1].literal) {
                    val old = state.operands[1]
                    val separator = if (state.operation == "splitAny" && old.variable.isEmpty())
                        if (value in old.literal) old.literal.replace(value, "") else old.literal + value else value
                    onChange(state.copy(operands = state.operands.toMutableList().apply { set(1, CalculationOperand(separator)) }))
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            StudioToolButton("去首尾空白", Modifier.weight(1f), selected = state.trim) { onChange(state.copy(trim = !state.trim)) }
            StudioToolButton("忽略空项", Modifier.weight(1f), selected = state.skipEmpty) { onChange(state.copy(skipEmpty = !state.skipEmpty)) }
        }
    }
    if (state.operation in setOf("int", "float", "itemText", "itemInt", "itemFloat")) {
        StudioToolButton("失败时使用默认值", Modifier.fillMaxWidth(), selected = state.fallback != null) {
            val output = requireNotNull(operation.output)
            onChange(state.copy(fallback = if (state.fallback != null) null else CalculationOperand(
                if (output == ProjectVariableType.STRING) "" else "0", type = output)))
        }
        state.fallback?.let { fallback ->
            CalculationOperandField("默认值", fallback, when (operation.output) {
                ProjectVariableType.INTEGER -> CalculationInputKind.INTEGER
                ProjectVariableType.NUMBER -> CalculationInputKind.NUMERIC
                else -> CalculationInputKind.TEXT
            }, variables, onManageVariables) { onChange(state.copy(fallback = it)) }
            Text(if (state.operation.startsWith("item")) "缺失、空值或数字转换失败时使用；JSON格式错误仍会提示。" else "无法转成数字时使用；不填写则明确提示错误。",
                fontSize = 10.sp, lineHeight = 14.sp, color = AutoScriptPalette.TextSecondary)
        }
    }
    if (operation.hint.isNotEmpty()) Text(operation.hint, fontSize = 10.sp, lineHeight = 14.sp, color = AutoScriptPalette.TextSecondary)
}

@Composable
private fun CalculationOperandField(label: String, operand: CalculationOperand, kind: CalculationInputKind,
    variables: List<CalculationVariable>, onManageVariables: (() -> Unit)?, onChange: (CalculationOperand) -> Unit) {
    var chooseVariable by remember { mutableStateOf(false) }
    var chooseType by remember { mutableStateOf(false) }
    val choices = variables.filter { calculationInputAccepts(kind, it.type) }
    val type = if (operand.variable.isEmpty() || operand.variable.startsWith("=")) operand.type else variables.firstOrNull { it.name == operand.variable }?.type
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, Modifier.width(62.dp), fontSize = 11.sp, lineHeight = 14.sp)
        val shape = RoundedCornerShape(5.dp)
        if (operand.variable.isNotEmpty()) {
            StudioToolButton("${calculationTypeLabel(type ?: operand.type)} · ${operand.variable.removePrefix("=")}", Modifier.weight(1f)) { chooseVariable = true }
        } else {
            BasicTextField(operand.literal, { if (it.toByteArray(Charsets.UTF_8).size <= 4096) onChange(operand.copy(literal = it)) },
                textStyle = TextStyle(color = AutoScriptPalette.TextPrimary, fontSize = 12.sp, lineHeight = 16.sp),
                cursorBrush = SolidColor(AutoScriptPalette.Accent), singleLine = type != ProjectVariableType.STRING,
                maxLines = if (type == ProjectVariableType.STRING) 3 else 1,
                keyboardOptions = KeyboardOptions(keyboardType = if (type == ProjectVariableType.STRING) KeyboardType.Text else KeyboardType.Decimal),
                modifier = Modifier.weight(1f).heightIn(min = 32.dp, max = 78.dp)
                    .background(AutoScriptPalette.VisualEditor.ControlBackground, shape)
                    .border(1.dp, AutoScriptPalette.Border, shape).padding(horizontal = 7.dp, vertical = 7.dp))
            if (kind in setOf(CalculationInputKind.SCALAR, CalculationInputKind.NUMERIC))
                StudioToolButton("${calculationTypeLabel(operand.type)} ▾") { chooseType = true }
        }
        StudioToolButton(if (operand.variable.isEmpty()) "取值" else "固定") {
            if (operand.variable.isEmpty()) chooseVariable = true else onChange(operand.copy(variable = ""))
        }
    }
    if (chooseType) EditorOptionDialog("固定值类型", listOf(ProjectVariableType.INTEGER to "整数", ProjectVariableType.NUMBER to "小数", ProjectVariableType.STRING to "文本")
        .filter { calculationInputAccepts(kind, it.first) }.map { it.first.name to it.second }, { chooseType = false }) { name ->
        onChange(operand.copy(type = ProjectVariableType.valueOf(name))); chooseType = false
    }
    if (chooseVariable) ScalarValueSourcePicker("选择${label}取值", operand.variable, variables,
        scalarValueTypes.filter { calculationInputAccepts(kind, it) }.toSet(),
        onDismiss = { chooseVariable = false }, onSelect = {}, onManage = onManageVariables,
        initialType = operand.type,
        onSelectTyped = { binding, resultType ->
            onChange(operand.copy(variable = binding, type = resultType ?: operand.type)); chooseVariable = false
        })
}

internal val commonCalculationOperations = setOf("assign", "int", "float", "text", "+", "-", "*", "/", "..", "split", "itemInt", "itemText")
internal fun filteredCalculationOperations(group: String, query: String): List<QuickCalculationOperation> =
    quickCalculationOperations.filter { (query.isNotBlank() || if (group == "常用") it.id in commonCalculationOperations else it.group == group) &&
        (query.isBlank() || "${it.label} ${it.id} ${it.group}".contains(query.trim(), true)) }

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QuickCalculationOperationPicker(selected: String, onDismiss: () -> Unit, onSelect: (String) -> Unit) {
    var group by remember { mutableStateOf(quickCalculationOperations.first { it.id == selected }.group.takeUnless { selected in commonCalculationOperations } ?: "常用") }
    var query by remember { mutableStateOf("") }
    val groups = listOf("常用") + quickCalculationOperations.map { it.group }.distinct()
    StudioPanelDialog("选择计算操作", onDismiss, showFooter = false, landscapeWidthDp = 360) {
        Column(Modifier.fillMaxWidth().padding(8.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            ImageToolCompactInput("搜索操作", query, true, Modifier.fillMaxWidth(), dense = true) { query = it.take(64) }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                groups.forEach { label -> StudioToolButton(label, selected = group == label && query.isBlank()) { group = label; query = "" } }
            }
            val visible = filteredCalculationOperations(group, query)
            Column(Modifier.weight(1f, fill = false).heightIn(max = 220.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                StudioCompactGrid(visible, cellDp = 108, maximum = 3) { item, cell ->
                    StudioToolButton(item.label, cell, selected = item.id == selected) { onSelect(item.id) }
                }
                if (visible.isEmpty()) Text("无匹配操作", fontSize = 11.sp, color = AutoScriptPalette.TextSecondary)
            }
        }
    }
}

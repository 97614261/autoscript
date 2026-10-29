package com.autoscript.studio

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autoscript.core.designsystem.AutoScriptPalette

internal val comparisonChoices = listOf("equals" to "= 等于", "notEquals" to "≠ 不等于", "lessThan" to "< 小于",
    "lessOrEqual" to "≤ 小于等于", "greaterThan" to "> 大于", "greaterOrEqual" to "≥ 大于等于")

@Composable
internal fun CompactComparisonEditor(
    variable: String, operator: String, value: String, valueVariable: String, variables: List<String>,
    onVariable: (String) -> Unit, onOperator: (String) -> Unit,
    onValue: (String) -> Unit, onValueVariable: (String) -> Unit,
) {
    var useVariable by remember { mutableStateOf(valueVariable.isNotBlank()) }
    val variableRight = useVariable || valueVariable.isNotBlank()
    var rightPicker by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        ConditionFormRow("左侧变量") { ConditionVariableInput(variable, variables, onVariable) }
        ConditionFormRow("比较方式") { ConditionSelect(operator, comparisonChoices, "比较方式", onOperator) }
        ConditionFormRow(if (variableRight) "右侧变量" else "比较值") {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Box(Modifier.weight(1f)) {
                    if (variableRight) ConditionVariableInput(valueVariable, variables, onValueVariable, showButton = false,
                        onPick = { rightPicker = true })
                    else ImageToolCompactInput("", value, true, Modifier.fillMaxWidth(), dense = true, onChange = onValue)
                }
                ConditionSmallButton(if (variableRight) "固定" else "变量") {
                    focus.clearFocus()
                    if (variableRight) { useVariable = false; onValueVariable("") }
                    else if (variables.isEmpty()) useVariable = true
                    else rightPicker = true
                }
            }
        }
    }
    if (rightPicker) ConditionChoiceDialog("选择右侧变量", valueVariable, variables.map { it to it }, true,
        onDismiss = { rightPicker = false }) { selected ->
        useVariable = true
        onValueVariable(selected)
        rightPicker = false
    }
}

@Composable
internal fun ConditionFormRow(label: String, content: @Composable () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth.value < 240 * LocalDensity.current.fontScale.coerceAtLeast(1f)) {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(label, fontSize = 10.sp, lineHeight = 14.sp, color = AutoScriptPalette.TextSecondary)
                content()
            }
        } else Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(label, Modifier.width(54.dp), fontSize = 10.sp, lineHeight = 14.sp, color = AutoScriptPalette.TextSecondary)
            Box(Modifier.weight(1f)) { content() }
        }
    }
}

@Composable
internal fun ConditionSelect(value: String, options: List<Pair<String, String>>, title: String, onChange: (String) -> Unit) {
    var picker by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val shape = RoundedCornerShape(6.dp)
    Row(Modifier.fillMaxWidth().heightIn(min = 30.dp).clip(shape)
        .background(AutoScriptPalette.VisualEditor.ControlBackground, shape)
        .border(1.dp, AutoScriptPalette.VisualEditor.Border, shape)
        .clickable { focus.clearFocus(); picker = true }.padding(horizontal = 7.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(options.firstOrNull { it.first == value }?.second ?: value.ifBlank { "请选择" }, Modifier.weight(1f),
            fontSize = 11.sp, lineHeight = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text("▾", fontSize = 11.sp, color = AutoScriptPalette.TextSecondary)
    }
    if (picker) ConditionChoiceDialog(title, value, options, false, { picker = false }) {
        onChange(it); picker = false
    }
}

@Composable
internal fun ConditionVariableInput(value: String, variables: List<String>, onChange: (String) -> Unit,
    showButton: Boolean = true, onPick: (() -> Unit)? = null) {
    var picker by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        ImageToolCompactInput("", value, true, Modifier.weight(1f), dense = true, trailing = {
            if (!showButton) Box(Modifier.size(24.dp).clickable {
                focus.clearFocus(); if (onPick != null) onPick() else picker = true
            }, contentAlignment = Alignment.Center) {
                Text("▾", fontSize = 11.sp, color = AutoScriptPalette.Accent)
            }
        }, onChange = onChange)
        if (showButton) ConditionSmallButton("变量") { focus.clearFocus(); picker = true }
    }
    if (picker) ConditionChoiceDialog("选择变量", value, listOf("" to "清空") + variables.map { it to it }, true,
        { picker = false }) { onChange(it); picker = false }
}

@Composable
private fun ConditionSmallButton(label: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(6.dp)
    Box(Modifier.width(36.dp).heightIn(min = 30.dp).clip(shape)
        .background(AutoScriptPalette.AccentSoft, shape).border(1.dp, AutoScriptPalette.VisualEditor.Border, shape)
        .clickable(onClick = onClick).padding(horizontal = 4.dp, vertical = 5.dp), contentAlignment = Alignment.Center) {
        Text(label, fontSize = 10.sp, lineHeight = 14.sp, color = AutoScriptPalette.Accent)
    }
}

@Composable
private fun ConditionChoiceDialog(title: String, selected: String, options: List<Pair<String, String>>, searchable: Boolean,
    onDismiss: () -> Unit, onSelect: (String) -> Unit) {
    if (searchable) {
        val metadata = variablePickerOptions(options.map { it.first }, LocalVariablePickerMetadata.current).associateBy { it.value }
        CompactVariablePickerDialog(title, options.map { (key, label) -> metadata.getValue(key).copy(label = label) }, selected, onDismiss, onSelect)
        return
    }
    StudioPanelDialog(title, onDismiss, showFooter = false, landscapeWidthDp = 280) {
        Column(Modifier.fillMaxWidth().heightIn(max = 240.dp)) {
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 6.dp, vertical = 2.dp)) {
                if (options.isEmpty()) Text("没有可选项", fontSize = 10.sp, lineHeight = 14.sp,
                    color = AutoScriptPalette.TextSecondary, modifier = Modifier.padding(6.dp))
                options.forEach { (key, label) ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 30.dp)
                        .background(if (key == selected) AutoScriptPalette.AccentSoft else androidx.compose.ui.graphics.Color.Transparent,
                            RoundedCornerShape(5.dp))
                        .clickable { onSelect(key) }.padding(horizontal = 6.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(label, Modifier.weight(1f), fontSize = 11.sp, lineHeight = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (key == selected) Text("✓", fontSize = 11.sp, color = AutoScriptPalette.Accent)
                    }
                }
            }
            HorizontalDivider(color = AutoScriptPalette.Divider)
            Text("取消", Modifier.fillMaxWidth().heightIn(min = 30.dp).clickable(onClick = onDismiss)
                .padding(6.dp), textAlign = TextAlign.Center, fontSize = 12.sp, color = AutoScriptPalette.TextSecondary)
        }
    }
}

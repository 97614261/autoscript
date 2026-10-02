package com.autoscript.studio

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.autoscript.core.designsystem.AutoScriptPalette
import com.autoscript.project.store.ProjectFlow
import com.autoscript.project.store.ProjectVariable
import com.autoscript.project.store.ProjectVariableType
import com.autoscript.script.ui.ScriptUiDefinition
import com.google.gson.JsonObject

internal fun uiParameterChoices(model: ScriptUiDefinition): List<Pair<String, String>> = model.fields.map { field ->
    val page = model.pages.firstOrNull { it.id == field.ui?.pageId } ?: model.pages.first()
    field.id to "${page.title} · ${field.label} (${field.id})"
}

internal fun uiParameterReadError(model: ScriptUiDefinition?, id: String, target: String, variables: List<CalculationVariable>): String? = when {
    model == null || model.fields.isEmpty() -> "请先在界面设计器添加控件并保存"
    model.fields.none { it.id == id } -> "请选择已保存界面里的控件，原控件可能已删除"
    !target.matches(Regex("[A-Za-z_][A-Za-z0-9_]{0,63}")) -> "请选择或输入结果变量名"
    variables.none { it.name == target && it.type == ProjectVariableType.STRING } -> "结果需写入已声明的字符串变量，请先维护变量"
    else -> null
}

/** UI.getValue already exists in Rust. This editor selects stable IDs, not labels or view handles. */
@Composable
internal fun VisualUiParameterDialog(
    arguments: JsonObject, definition: JsonObject?, variables: List<ProjectVariable>, currentFlowId: String,
    flows: List<ProjectFlow>, onManageVariables: (() -> Unit)?, onDismiss: () -> Unit,
    onConfirm: (JsonObject) -> String?, confirmLabel: String,
) {
    val model = remember(definition) { definition?.let { ScriptUiDefinition.parse(it.toString()) } }
    val controls = model?.let(::uiParameterChoices).orEmpty()
    val targets = calculationVariables(variables, currentFlowId, flows)
    var id by remember(arguments) { mutableStateOf(arguments["controlId"]?.asString.orEmpty()) }
    var target by remember(arguments) { mutableStateOf(arguments["resultVariable"]?.asString.orEmpty()) }
    var error by remember(arguments) { mutableStateOf<String?>(null) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BoxWithConstraints(Modifier.fillMaxSize().imePadding().padding(12.dp), contentAlignment = Alignment.Center) {
            Surface(Modifier.widthIn(max = 480.dp).fillMaxWidth().heightIn(max = maxHeight), color = Color.White, shape = RoundedCornerShape(4.dp)) {
                Column {
                    Row(Modifier.fillMaxWidth().height(40.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("读取页面参数", Modifier.weight(1f), color = AutoScriptPalette.Accent, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        DesignerAction("×", onClick = onDismiss)
                    }
                    HorizontalDivider(color = AutoScriptPalette.Divider)
                    Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("来源控件 · 任意页面", color = AutoScriptPalette.TextSecondary, fontSize = 11.sp)
                        ParameterChoice(controls.firstOrNull { it.first == id }?.second ?: "选择控件（原 ID：${id.ifBlank { "未选择" }}）", controls) { id = it; error = null }
                        Text("结果变量 · 字符串", color = AutoScriptPalette.TextSecondary, fontSize = 11.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.weight(1f).height(34.dp).border(1.dp, AutoScriptPalette.Divider, RoundedCornerShape(3.dp)).padding(horizontal = 8.dp), contentAlignment = Alignment.CenterStart) {
                                BasicTextField(target, { target = it.take(64); error = null }, Modifier.fillMaxWidth(), singleLine = true,
                                    textStyle = TextStyle(fontSize = 12.sp, color = AutoScriptPalette.TextPrimary, platformStyle = PlatformTextStyle(includeFontPadding = false)))
                            }
                            ParameterChoice("选择变量", targets.filter { it.type == ProjectVariableType.STRING }.map { it.name to "${it.scope} · ${it.name}" }, Modifier.width(88.dp)) { target = it; error = null }
                        }
                        if (onManageVariables != null) DesignerAction("＋维护变量", onClick = onManageVariables)
                        Text("读取当前值，不是画布尺寸。复选框返回 true/false；数字返回数字文本，可用计算积木转换。启动时的配置仍可通过绑定变量读取。", fontSize = 11.sp, color = AutoScriptPalette.TextSecondary)
                        if (controls.isEmpty()) Text("尚无已保存的界面控件", fontSize = 11.sp, color = Color(0xFFD14949))
                        error?.let { Text(it, fontSize = 11.sp, color = Color(0xFFD14949)) }
                    }
                    HorizontalDivider(color = AutoScriptPalette.Divider)
                    Row(Modifier.fillMaxWidth().height(40.dp)) {
                        DesignerAction("取消", Modifier.weight(1f).fillMaxHeight(), onClick = onDismiss)
                        DesignerAction(confirmLabel, Modifier.weight(1f).fillMaxHeight(), selected = true) {
                            error = uiParameterReadError(model, id, target, targets)
                            if (error == null) error = onConfirm(arguments.deepCopy().apply { addProperty("controlId", id); addProperty("resultVariable", target) })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ParameterChoice(label: String, choices: List<Pair<String, String>>, modifier: Modifier = Modifier.fillMaxWidth(), onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        Row(Modifier.fillMaxWidth().height(34.dp).background(Color(0xFFF5F7FB), RoundedCornerShape(3.dp))
            .border(1.dp, AutoScriptPalette.Divider, RoundedCornerShape(3.dp)).clickable(enabled = choices.isNotEmpty()) { expanded = true }
            .padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = AutoScriptPalette.TextPrimary)
            Text("⌄", fontSize = 12.sp, color = AutoScriptPalette.Accent)
        }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            choices.forEach { (id, text) -> DropdownMenuItem(text = { Text(text, fontSize = 12.sp) }, onClick = { expanded = false; onSelect(id) }) }
        }
    }
}

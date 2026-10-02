package com.autoscript.studio

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.autoscript.core.designsystem.AutoScriptPalette
import com.autoscript.core.designsystem.hairline
import com.autoscript.project.store.ProjectFlow
import com.autoscript.project.store.ProjectVariable
import com.autoscript.project.store.ProjectVariableScope
import com.autoscript.project.store.ProjectVariableType
import com.google.gson.JsonObject
import com.google.gson.JsonParser

internal const val VARIABLE_CALCULATION_HINT = "--@autoscript-variable:"

internal fun variableCalculationHint(arguments: JsonObject) = "$VARIABLE_CALCULATION_HINT$arguments\n"

internal fun variableCalculationArguments(hint: String): JsonObject? = runCatching {
    val line = hint.lineSequence().firstOrNull().orEmpty()
    require(line.startsWith(VARIABLE_CALCULATION_HINT))
    require(line.length <= 8192)
    val args = JsonParser.parseString(line.removePrefix(VARIABLE_CALCULATION_HINT)).asJsonObject
    require(args.keySet() == setOf("name", "expression"))
    require(args["name"].isJsonPrimitive && args["name"].asJsonPrimitive.isString)
    require(args["expression"].isJsonPrimitive && args["expression"].asJsonPrimitive.isString)
    require(args["name"].asString.matches(Regex("[A-Za-z_][A-Za-z0-9_]{0,63}")))
    require(args["expression"].asString.toByteArray(Charsets.UTF_8).size in 1..1024)
    args
}.getOrNull()

internal data class CalculationVariable(val name: String, val type: ProjectVariableType, val scope: String)

/** Lua lookup uses parameter/local before global. Do not offer an inaccessible shadowed global. */
internal fun calculationVariables(variables: List<ProjectVariable>, flowId: String, flows: List<ProjectFlow>): List<CalculationVariable> {
    val result = linkedMapOf<String, CalculationVariable>()
    variables.filter { it.scope == ProjectVariableScope.GLOBAL }.forEach {
        result[it.name] = CalculationVariable(it.name, it.type, "全局")
    }
    variables.filter { it.scope == ProjectVariableScope.FLOW && it.flowId == flowId }.forEach {
        result[it.name] = CalculationVariable(it.name, it.type, "局部")
    }
    flows.firstOrNull { it.flowId == flowId }?.params.orEmpty().forEach { param ->
        val name = param["name"]?.asString ?: return@forEach
        val type = when (param["type"]?.asString) {
            "integer" -> ProjectVariableType.INTEGER
            "number" -> ProjectVariableType.NUMBER
            "string" -> ProjectVariableType.STRING
            else -> null
        }
        if (type == null) result.remove(name) else result[name] = CalculationVariable(name, type, "参数")
    }
    return result.values.sortedWith(compareBy({ it.scope == "全局" }, { it.name }))
}

internal fun insertCalculationToken(field: TextFieldValue, token: String): TextFieldValue {
    val start = field.selection.min.coerceIn(0, field.text.length)
    val end = field.selection.max.coerceIn(start, field.text.length)
    val text = field.text.replaceRange(start, end, token)
    return if (text.toByteArray(Charsets.UTF_8).size > 1024) field else TextFieldValue(text, TextRange(start + token.length))
}

internal fun wrapCalculationConversion(field: TextFieldValue, function: String, fallback: String): TextFieldValue {
    require(function in setOf("int", "float", "text"))
    if (!field.selection.collapsed) {
        val selected = field.text.substring(field.selection.min, field.selection.max)
        return insertCalculationToken(field, "$function($selected)")
    }
    val content = field.text.ifBlank { fallback }
    val result = "$function($content)"
    if (result.toByteArray(Charsets.UTF_8).size > 1024) return field
    return TextFieldValue(result, TextRange(if (content.isBlank()) result.length - 1 else result.length))
}

internal fun calculationTypeLabel(type: ProjectVariableType) = when (type) {
    ProjectVariableType.INTEGER -> "整"
    ProjectVariableType.NUMBER -> "浮"
    ProjectVariableType.STRING -> "字"
    ProjectVariableType.IMAGE -> "图"
}

/** Maintenance-only reference extraction; this is not the expression validator. */
internal fun calculationReferencedNames(source: String): Set<String> {
    val withoutStrings = Regex("\"(?:[^\"\\\\]|\\\\.)*\"").replace(source.take(1024), " ")
    return Regex("(?<![A-Za-z0-9_.])[A-Za-z_][A-Za-z0-9_]*").findAll(withoutStrings).map { it.value }
        .filter { it !in setOf("int", "float", "text") }.toSet()
}

/** Shared compact assignment/calculation UI; Rust, not this editor, owns expression semantics. */
@Composable
internal fun VisualVariableCalculationDialog(
    arguments: JsonObject,
    variables: List<ProjectVariable>,
    currentFlowId: String,
    flows: List<ProjectFlow>,
    onDismiss: () -> Unit,
    onConfirm: (JsonObject) -> String?,
    onManageVariables: (() -> Unit)? = null,
    confirmLabel: String = "加入",
) {
    var target by remember(arguments) { mutableStateOf(arguments["name"]?.asString.orEmpty()) }
    var expression by remember(arguments) { mutableStateOf(TextFieldValue(arguments["expression"]?.asString.orEmpty())) }
    var expressionEdited by remember(arguments) { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var picker by remember { mutableStateOf<String?>(null) }
    var scope by remember { mutableStateOf("局部") }
    var selected by remember { mutableStateOf<String?>(null) }
    val choices = calculationVariables(variables, currentFlowId, flows).filter { it.type != ProjectVariableType.IMAGE }
    fun insert(token: String, replaceDefault: Boolean = false) {
        expression = insertCalculationToken(if (replaceDefault && !expressionEdited && expression.text in setOf("", "0", "\"\"")) expression.copy(selection = TextRange(0, expression.text.length)) else expression, token)
        expressionEdited = true; error = null
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BoxWithConstraints(Modifier.fillMaxSize().imePadding().padding(horizontal = 10.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
            val limit = maxHeight
            Surface(Modifier.widthIn(max = 560.dp).fillMaxWidth().heightIn(max = limit), color = Color.White, shape = RoundedCornerShape(3.dp), shadowElevation = 10.dp) {
                Column {
                    Row(Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("变量赋值 / 计算", color = AutoScriptPalette.Accent, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        if (onManageVariables != null) CalculationButton("＋变量", onClick = onManageVariables)
                    }
                    HorizontalDivider(thickness = hairline(), color = AutoScriptPalette.Divider)
                    Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("目标", fontSize = 12.sp, modifier = Modifier.width(38.dp))
                            val variable = choices.firstOrNull { it.name == target }
                            CalculationButton(variable?.let { "${calculationTypeLabel(it.type)} · ${it.name}（${it.scope}）" } ?: "选择目标变量", Modifier.weight(1f)) {
                                picker = "目标"; selected = target.takeIf(String::isNotBlank)
                                scope = variable?.scope ?: choices.firstOrNull()?.scope ?: "局部"
                            }
                        }
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            CalculationButton("选择变量") { picker = "来源"; selected = null }
                            CalculationButton("字符常量") { insert("\"\"", replaceDefault = true); expression = expression.copy(selection = TextRange((expression.selection.start - 1).coerceAtLeast(0))) }
                            CalculationButton("类型转换") { picker = "转换" }
                        }
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            listOf("+", "-", "*", "/", "//", "%", "..", "(", ")").forEach { token ->
                                CalculationButton(token, Modifier.widthIn(min = 34.dp)) { insert(if (token in listOf("(", ")")) token else " $token ") }
                            }
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            val numeric = choices.firstOrNull { it.name == target }?.type in setOf(ProjectVariableType.INTEGER, ProjectVariableType.NUMBER)
                            CalculationButton("自增 +1", Modifier.weight(1f), enabled = numeric) { expression = TextFieldValue("$target + 1", TextRange(target.length + 4)); expressionEdited = true; error = null }
                            CalculationButton("自减 −1", Modifier.weight(1f), enabled = numeric) { expression = TextFieldValue("$target - 1", TextRange(target.length + 4)); expressionEdited = true; error = null }
                        }
                        Text("$target =", color = AutoScriptPalette.Accent, fontSize = 13.sp)
                        BasicTextField(
                            value = expression,
                            onValueChange = { if (it.text.toByteArray(Charsets.UTF_8).size <= 1024) { expression = it; expressionEdited = true; error = null } },
                            textStyle = TextStyle(fontSize = 14.sp, color = AutoScriptPalette.TextPrimary, fontFamily = FontFamily.Monospace),
                            cursorBrush = SolidColor(AutoScriptPalette.Accent),
                            modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp).background(AutoScriptPalette.PageBackground, RoundedCornerShape(3.dp))
                                .border(1.dp, AutoScriptPalette.Divider, RoundedCornerShape(3.dp)).padding(9.dp),
                            decorationBox = { field -> Box { if (expression.text.isBlank()) Text("例如：count + 1 或 int(\"12\")", color = AutoScriptPalette.TextSecondary, fontSize = 12.sp); field() } },
                        )
                        Text("/ 浮点除法 · // 向下整除 · % 取余 · .. 字符连接\nint 转整数（向零截断） · float 转浮点 · text 转字符", fontSize = 10.sp, color = AutoScriptPalette.TextSecondary)
                        Text("局部变量仅属于当前插件；图像不参与计算。声明不赋初值：整数/浮点默认为0，字符为空。", fontSize = 10.sp, color = AutoScriptPalette.TextSecondary)
                        error?.let { Text(it, color = AutoScriptPalette.Danger, fontSize = 11.sp) }
                    }
                    HorizontalDivider(thickness = hairline(), color = AutoScriptPalette.Divider)
                    Row(Modifier.fillMaxWidth().height(42.dp)) {
                        CalculationFooter("取消", Modifier.weight(1f), onClick = onDismiss)
                        CalculationFooter("清空", Modifier.weight(1f)) { expression = TextFieldValue(); expressionEdited = true; error = null }
                        CalculationFooter(confirmLabel, Modifier.weight(1f), primary = true) {
                            error = when {
                                choices.none { it.name == target } -> "请选择已维护的整数、浮点或字符变量"
                                expression.text.isBlank() -> "请输入赋值或计算表达式"
                                else -> onConfirm(JsonObject().apply { addProperty("name", target); addProperty("expression", expression.text.trim()) })
                            }
                        }
                    }
                }
            }
        }
    }
    if (picker == "转换") {
        EditorOptionDialog("类型转换", listOf("int" to "转整数 · int", "float" to "转浮点 · float", "text" to "转字符 · text"), onDismiss = { picker = null }) { function ->
            expression = wrapCalculationConversion(expression, function, target)
            expressionEdited = true; error = null; picker = null
        }
    } else if (picker != null) {
        Dialog(onDismissRequest = { picker = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            BoxWithConstraints(Modifier.fillMaxSize().padding(10.dp), contentAlignment = Alignment.Center) {
                Surface(Modifier.widthIn(max = 540.dp).fillMaxWidth().heightIn(max = maxHeight * .8f), color = Color.White, shape = RoundedCornerShape(3.dp), shadowElevation = 10.dp) {
                    Column {
                        Row(Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("变量选择", fontSize = 16.sp, color = AutoScriptPalette.Accent, modifier = Modifier.weight(1f))
                            if (onManageVariables != null) CalculationButton("＋维护变量") { picker = null; onManageVariables() }
                        }
                        HorizontalDivider(thickness = hairline(), color = AutoScriptPalette.Divider)
                        Row(Modifier.weight(1f, fill = false).heightIn(max = 340.dp)) {
                            Column(Modifier.width(85.dp).fillMaxHeight()) {
                                listOf("局部", "全局", "参数").forEach { label -> CalculationFooter("${label}变量", Modifier.fillMaxWidth().height(42.dp), primary = scope == label) { scope = label; selected = null } }
                            }
                            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                                val visible = choices.filter { it.scope == scope }
                                if (visible.isEmpty()) Text("暂无可用变量", fontSize = 12.sp, color = AutoScriptPalette.TextSecondary, modifier = Modifier.padding(12.dp))
                                visible.forEach { variable -> CalculationFooter("${if (selected == variable.name) "● " else ""}${calculationTypeLabel(variable.type)} · ${variable.name}", Modifier.fillMaxWidth().height(42.dp), primary = selected == variable.name) { selected = variable.name } }
                            }
                        }
                        HorizontalDivider(thickness = hairline(), color = AutoScriptPalette.Divider)
                        Row(Modifier.fillMaxWidth().height(42.dp)) {
                            CalculationFooter("取消", Modifier.weight(1f)) { picker = null }
                            CalculationFooter("确定", Modifier.weight(1f), primary = true) {
                                selected?.takeIf { name -> choices.any { it.name == name } }?.let { name -> if (picker == "目标") target = name else insert(name, replaceDefault = true); error = null; picker = null }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CalculationButton(label: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Box(modifier.height(34.dp).border(1.dp, AutoScriptPalette.Divider, RoundedCornerShape(3.dp)).clickable(enabled = enabled, onClick = onClick).padding(horizontal = 8.dp), contentAlignment = Alignment.Center) {
        Text(label, color = if (enabled) AutoScriptPalette.Accent else AutoScriptPalette.TextSecondary, fontSize = 11.sp, textAlign = TextAlign.Center)
    }
}

@Composable
private fun CalculationFooter(label: String, modifier: Modifier, primary: Boolean = false, onClick: () -> Unit) {
    Box(modifier.fillMaxHeight().clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Text(label, fontSize = 13.sp, color = if (primary) AutoScriptPalette.Accent else AutoScriptPalette.TextPrimary, textAlign = TextAlign.Center)
    }
}

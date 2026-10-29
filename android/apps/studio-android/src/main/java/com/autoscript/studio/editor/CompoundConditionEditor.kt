package com.autoscript.studio

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autoscript.core.designsystem.AutoScriptPalette
import com.google.gson.*

private val conditionOperators = linkedMapOf("equals" to "=", "notEquals" to "≠", "lessThan" to "<", "lessOrEqual" to "≤", "greaterThan" to ">", "greaterOrEqual" to "≥")

internal fun conditionSeed(variable: String = "value", operator: String = "equals", value: String = "true", right: String = ""): JsonObject = JsonObject().apply {
    addProperty("type", "compare"); addProperty("variable", variable); addProperty("operator", operator)
    add("value", runCatching { JsonParser.parseString(value) }.getOrDefault(JsonPrimitive(value)))
    if (right.isNotBlank()) addProperty("valueVariable", right)
}

/** Matches the compiler's shape/budget. It never interprets expressions or repairs unknown data. */
internal fun parseCompoundCondition(text: String): JsonObject? = runCatching {
    require(text.toByteArray(Charsets.UTF_8).size <= 8192)
    var count = 0
    fun check(v: JsonElement, depth: Int) {
        require(depth <= 8 && ++count <= 64 && v.isJsonObject)
        val o = v.asJsonObject
        fun name(key: String) = o.get(key)?.let { it.isJsonPrimitive && it.asJsonPrimitive.isString && it.asString.matches(com.autoscript.project.store.ProjectNames.pattern) } == true
        when (o.get("type")?.asString) {
            "compare" -> {
                require(o.keySet().all { it in setOf("type", "variable", "operator", "value", "valueVariable") } && name("variable"))
                require(o.get("operator")?.asString in conditionOperators && o.has("value") && !o["value"].isJsonArray && !o["value"].isJsonObject)
                require(!o.has("valueVariable") || name("valueVariable"))
                require(o.has("valueVariable") || o["operator"].asString in setOf("equals", "notEquals") || (o["value"].isJsonPrimitive && (o["value"].asJsonPrimitive.isNumber || o["value"].asJsonPrimitive.isString)))
            }
            "all", "any" -> {
                require(o.keySet() == setOf("type", "conditions"))
                val a = o.getAsJsonArray("conditions"); require(a.size() in 1..64); a.forEach { check(it, depth + 1) }
            }
            "not" -> { require(o.keySet() == setOf("type", "condition")); check(o["condition"], depth + 1) }
            else -> error("Unknown condition")
        }
    }
    val root = JsonParser.parseString(text); check(root, 1); root.asJsonObject
}.getOrNull()

@Composable
internal fun CompoundConditionEditor(text: String, seed: JsonObject, variables: List<String>, onValidityChange: (Boolean) -> Unit = {}, onChange: (String) -> Unit) {
    var invalid by remember { mutableStateOf(emptySet<String>()) }
    LaunchedEffect(invalid, text) { onValidityChange(invalid.isEmpty() && (text.isBlank() || parseCompoundCondition(text) != null)) }
    ConditionFormRow("判断模式") {
        ConditionSelect(if (text.isBlank()) "single" else "compound",
            listOf("single" to "单项比较", "compound" to "复合条件（与 / 或 / 非）"), "判断模式") { mode ->
            if (mode == "single") onChange("") else if (text.isBlank()) onChange(seed.toString())
        }
    }
    if (text.isNotBlank()) {
        val tree = parseCompoundCondition(text)
        if (tree == null) Text("复合条件格式或上限无效；未丢弃原配置。", color = AutoScriptPalette.Danger, fontSize = 11.sp)
        else ConditionNode(tree, 1, variables, "root", { key, ok -> invalid = if (ok) invalid - key else invalid + key }) { next -> if (parseCompoundCondition(next.toString()) != null) onChange(next.toString()) }
    }
}

@Composable
private fun ConditionNode(value: JsonObject, depth: Int, variables: List<String>, path: String, onValidity: (String, Boolean) -> Unit, onChange: (JsonObject) -> Unit) {
    val type = value["type"].asString
    fun edit(key: String, v: JsonElement) = onChange(value.deepCopy().apply { add(key, v) })
    Column(Modifier.fillMaxWidth().border(1.dp, AutoScriptPalette.Divider).padding(4.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        ConditionFormRow("条件关系") {
            ConditionSelect(type, listOf("compare" to "比较", "all" to "全部满足（与）", "any" to "任一满足（或）", "not" to "结果取反（非）"), "条件关系") { kind ->
                if (kind != type && (kind == "compare" || depth < 8)) onChange(when {
                    kind == "compare" -> conditionSeed()
                    kind == "not" -> JsonObject().apply { addProperty("type", kind); add("condition", value.deepCopy()) }
                    type in setOf("all", "any") -> value.deepCopy().apply { addProperty("type", kind) }
                    else -> JsonObject().apply { addProperty("type", kind); add("conditions", JsonArray().apply { add(value.deepCopy()) }) }
                })
            }
        }
        if (type == "compare") {
            ConditionText("左变量", value["variable"].asString, variables, { it.matches(com.autoscript.project.store.ProjectNames.pattern) }, { onValidity("$path.left", it) }) { edit("variable", JsonPrimitive(it)) }
            ConditionFormRow("比较方式") {
                ConditionSelect(value["operator"].asString, comparisonChoices, "比较方式") { edit("operator", JsonPrimitive(it)) }
            }
            ConditionText("比较值", value["value"].toString(), validator = { raw -> runCatching { JsonParser.parseString(raw) }.getOrNull()?.let { !it.isJsonArray && !it.isJsonObject && (value.has("valueVariable") || value["operator"].asString in setOf("equals", "notEquals") || (it.isJsonPrimitive && (it.asJsonPrimitive.isNumber || it.asJsonPrimitive.isString))) } == true }, onValidity = { onValidity("$path.value", it) }) { raw ->
                runCatching { JsonParser.parseString(raw) }.getOrNull()?.takeIf { !it.isJsonArray && !it.isJsonObject }?.let { edit("value", it) }
            }
            ConditionText("右变量", value.get("valueVariable")?.asString.orEmpty(), variables, { it.isBlank() || it.matches(com.autoscript.project.store.ProjectNames.pattern) }, { onValidity("$path.right", it) }) {
                onChange(value.deepCopy().apply { if (it.isBlank()) remove("valueVariable") else addProperty("valueVariable", it) })
            }
        } else if (type == "not") ConditionNode(value["condition"].asJsonObject, depth + 1, variables, "$path.not", onValidity) { edit("condition", it) }
        else {
            val children = value.getAsJsonArray("conditions")
            children.forEachIndexed { index, child ->
                Row(verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f)) { ConditionNode(child.asJsonObject, depth + 1, variables, "$path.$index", onValidity) {
                        edit("conditions", children.deepCopy().apply { set(index, it) })
                    } }
                    if (children.size() > 1) ConditionChoice("－") { edit("conditions", children.deepCopy().apply { remove(index) }) }
                }
            }
            if (depth < 8) ConditionChoice("＋比较") { edit("conditions", children.deepCopy().apply { add(conditionSeed()) }) }
        }
    }
}

@Composable
private fun ConditionChoice(label: String, modifier: Modifier = Modifier, action: () -> Unit) {
    StudioToolButton(label.removePrefix("●"), modifier, selected = label.startsWith("●"), onClick = action)
}

@Composable
private fun ConditionText(label: String, text: String, choices: List<String> = emptyList(), validator: (String) -> Boolean, onValidity: (Boolean) -> Unit, onChange: (String) -> Unit) {
    var draft by remember(text) { mutableStateOf(text) }
    LaunchedEffect(draft, text) { onValidity(validator(draft)) }
    DisposableEffect(Unit) { onDispose { onValidity(true) } }
    Column {
        ConditionFormRow(label) {
            val update: (String) -> Unit = { draft = it.take(4096); onChange(draft) }
            if (label in setOf("左变量", "右变量")) ConditionVariableInput(draft, choices, update)
            else ImageToolCompactInput("", draft, true, Modifier.fillMaxWidth(), dense = true, onChange = update)
        }
        if (!validator(draft)) Text("输入无效，不能保存", color = AutoScriptPalette.Danger, fontSize = 10.sp)
    }
}

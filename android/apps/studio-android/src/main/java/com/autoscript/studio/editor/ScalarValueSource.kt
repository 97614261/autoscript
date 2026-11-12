package com.autoscript.studio

import androidx.compose.runtime.*
import com.autoscript.project.store.ProjectNames
import com.autoscript.project.store.ProjectVariableType
import com.google.gson.JsonObject
import com.autoscript.studio.generated.BlockPropertyEditor

internal fun scalarInputTypes(kind: String, path: String): Set<ProjectVariableType>? {
    if (kind == "variable.copy" && path == "sourceName" ||
        kind in setOf("control.if", "control.while") && path in setOf("variable", "valueVariable") ||
        kind in setOf("task.log", "task.prompt", "task.runprompt", "ui.set", "ui.command") && path == "valueVariable") return scalarValueTypes
    if (kind == "task.sleep" && path == "millisecondsVariable" || kind == "control.repeat" && path == "timesVariable") return setOf(ProjectVariableType.INTEGER)
    if (kind == "vision.findimage" && path in setOf("leftVariable", "topVariable", "rightVariable", "bottomVariable") ||
        kind == "screen.mappoint" && path in setOf("xVariable", "yVariable")) return setOf(ProjectVariableType.INTEGER)
    if (kind in setOf("control.cycle", "control.while") && path == "durationVariable" ||
        kind == "control.loopcheck" && path == "limitVariable") return setOf(ProjectVariableType.INTEGER, ProjectVariableType.NUMBER)
    if (path == "textVariable" && kind in setOf("clipboard.settext", "input.pastetext", "floating.show")) return setOf(ProjectVariableType.STRING)
    if (path.endsWith("Variable") && path != "resultVariable" &&
        !(kind == "system.screensize" && path in setOf("widthVariable", "heightVariable")) &&
        (kind in utilityApiKinds.values || kind in setOf("data.length", "data.get", "data.set", "data.remove", "data.append", "data.keys", "data.text", "files.read", "files.write", "files.list", "files.remove"))) {
        val field = BlockCatalog.find(kind)?.properties?.firstOrNull { it.path == path.removeSuffix("Variable") } ?: return null
        return when (field.editor) {
            BlockPropertyEditor.INTEGER -> setOf(ProjectVariableType.INTEGER)
            BlockPropertyEditor.NUMBER -> setOf(ProjectVariableType.INTEGER, ProjectVariableType.NUMBER)
            BlockPropertyEditor.STRING -> setOf(ProjectVariableType.STRING)
            else -> null
        }
    }
    return null
}

internal val scalarValueTypes = setOf(ProjectVariableType.INTEGER, ProjectVariableType.NUMBER, ProjectVariableType.STRING)
internal data class ScalarSourceRequest(val title: String, val variables: List<CalculationVariable>,
    val types: Set<ProjectVariableType> = scalarValueTypes, val initialType: ProjectVariableType? = null)

internal fun isScalarSource(value: String): Boolean = value.matches(ProjectNames.pattern) ||
    value.startsWith("=") && value.drop(1).isNotBlank() && value.drop(1).toByteArray(Charsets.UTF_8).size <= 1024

internal fun scalarSourceError(value: String, variables: List<CalculationVariable>, types: Set<ProjectVariableType>): String? {
    if (!isScalarSource(value)) return "请选择变量，或填写不超过1024字节的表达式"
    if (value.startsWith("=")) {
        if (calculationReferencedNames(value.drop(1)).any { name -> variables.none { it.name == name && it.type != ProjectVariableType.IMAGE } })
            return "表达式含未声明或不可用的变量"
        return null // Full grammar and result-type validation remain in the compiler.
    }
    return if (variables.any { it.name == value && it.type in types }) null else "请选择当前插件可用且类型正确的变量"
}

internal fun scalarSourceBinding(expression: String, variables: List<CalculationVariable>): String =
    expression.trim().let { if (variables.any { variable -> variable.name == it }) it else "=$it" }

@Composable
internal fun ScalarValueSourcePicker(title: String, value: String, variables: List<CalculationVariable>,
    types: Set<ProjectVariableType> = scalarValueTypes, allowFixed: Boolean = false,
    onDismiss: () -> Unit, onSelect: (String) -> Unit, onManage: (() -> Unit)? = null,
    initialType: ProjectVariableType? = null,
    onSelectTyped: ((String, ProjectVariableType?) -> Unit)? = null) {
    var calculate by remember { mutableStateOf(value.startsWith("=")) }
    if (!calculate) CompactVariablePickerDialog(title,
        (if (allowFixed) listOf(VariablePickerOption("", "固定值")) else emptyList()) +
            variablePickerOptions(variables.filter { it.type in types }.map { it.name }, variables), value, onDismiss, { binding ->
            if (onSelectTyped != null) onSelectTyped(binding, variables.firstOrNull { it.name == binding }?.type) else onSelect(binding)
        },
        onManage = onManage, onCalculate = { calculate = true })
    else {
        val arguments = remember(value) { JsonObject().apply {
            addProperty("expression", value.removePrefix("="))
        } }
        VisualVariableCalculationDialog(arguments, emptyList(), "", emptyList(), onDismiss,
            onConfirm = { configured ->
                val binding = scalarSourceBinding(configured["expression"].asString, variables)
                scalarSourceError(binding, variables, types)?.let { return@VisualVariableCalculationDialog it }
                if (onSelectTyped != null) onSelectTyped(binding, configured["resultType"]?.asString?.let(ProjectVariableType::valueOf)) else onSelect(binding)
                null
            }, onManageVariables = onManage, confirmLabel = "使用",
            sourceRequest = ScalarSourceRequest(title, variables, types, initialType))
    }
}

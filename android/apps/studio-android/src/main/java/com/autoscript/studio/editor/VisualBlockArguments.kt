package com.autoscript.studio

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autoscript.core.designsystem.AutoScriptPalette
import com.autoscript.project.store.ProjectSnapshot
import com.autoscript.project.store.ProjectFlow
import com.autoscript.project.store.ProjectVariable
import com.autoscript.project.store.ProjectVariableScope
import com.autoscript.studio.generated.BlockContract
import com.autoscript.studio.generated.BlockPropertyEditor
import com.autoscript.studio.generated.BlockResourceKind
import com.google.gson.JsonParser
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.util.Locale

internal fun defaultFlowCallArguments(flow: ProjectFlow): JsonObject = JsonObject().apply {
    flow.params.forEach { parameter ->
        if (parameter.get("required")?.asBoolean != true) return@forEach
        val name = parameter.get("name")?.asString ?: return@forEach
        when (parameter.get("type")?.asString) {
            "boolean" -> addProperty(name, false)
            "integer" -> addProperty(name, 0)
            "number" -> addProperty(name, 0.0)
            "string" -> addProperty(name, "")
        }
    }
}

internal fun initialBlockArguments(
    contract: BlockContract,
    flows: List<ProjectFlow>,
    resources: List<JsonObject>,
    currentFlowId: String,
    allowMissingResources: Boolean = false,
    preferredFlowId: String? = null,
): JsonObject? {
    val target = if (preferredFlowId == null) flows.firstOrNull { it.flowId != currentFlowId }
        else flows.firstOrNull { it.flowId == preferredFlowId && it.flowId != currentFlowId }
    val arguments = JsonObject()
    contract.properties.forEach { property ->
        if (!property.required) return@forEach
        when (property.editor) {
            BlockPropertyEditor.BOOLEAN -> arguments.addProperty(
                property.path,
                property.defaultValue?.toBooleanStrictOrNull() ?: false,
            )
            BlockPropertyEditor.INTEGER -> arguments.addProperty(
                property.path,
                property.defaultValue?.toLongOrNull() ?: 0,
            )
            BlockPropertyEditor.INTEGER_ENUM -> arguments.addProperty(
                property.path,
                property.defaultValue
                    ?.takeIf { it in property.choices }
                    ?.toLongOrNull() ?: property.choices.firstOrNull()?.toLongOrNull() ?: return null,
            )
            BlockPropertyEditor.NUMBER -> arguments.addProperty(
                property.path,
                property.defaultValue?.toDoubleOrNull()?.takeIf(Double::isFinite) ?: 0.0,
            )
            BlockPropertyEditor.STRING -> {
                arguments.addProperty(property.path, property.defaultValue.orEmpty())
            }
            BlockPropertyEditor.RESOURCE -> arguments.addProperty(
                property.path,
                resourcePaths(resources, property.resourceKind).firstOrNull() ?: if (allowMissingResources) "" else return null,
            )
            BlockPropertyEditor.RESOURCE_LIST -> arguments.add(property.path, JsonArray().apply {
                resourcePaths(resources, property.resourceKind).firstOrNull()?.let(::add)
            })
            BlockPropertyEditor.SCALAR -> arguments.add(
                property.path,
                parseScalar(property.defaultValue ?: "null") ?: return null,
            )
            BlockPropertyEditor.POINT -> arguments.add(
                property.path,
                parsePoint(property.defaultValue ?: "0,0") ?: return null,
            )
            BlockPropertyEditor.RECT -> arguments.add(
                property.path,
                parseRect(property.defaultValue ?: "0,0,1,1") ?: return null,
            )
            BlockPropertyEditor.COLOR -> arguments.addProperty(
                property.path,
                parseColor(property.defaultValue ?: "#FFFFFF") ?: return null,
            )
            BlockPropertyEditor.MULTI_COLOR_SAMPLES -> arguments.add(
                property.path,
                parseMultiColorSamples(property.defaultValue ?: "1,0,#FFFFFF,0") ?: return null,
            )
            BlockPropertyEditor.LEGACY_PATTERN -> arguments.add(
                property.path,
                parseLegacyPattern(property.defaultValue ?: "0,0,#FFFFFF,0,0,0") ?: return null,
            )
            BlockPropertyEditor.LEGACY_FIXED_PATTERN -> arguments.add(
                property.path,
                parseLegacyFixedPattern(property.defaultValue ?: "0,0,#FFFFFF,0,0,0") ?: return null,
            )
            BlockPropertyEditor.LEGACY_COLOR_GROUP -> arguments.add(
                property.path,
                parseLegacyColorGroup(property.defaultValue ?: "#FFFFFF,0,0,0") ?: return null,
            )
            BlockPropertyEditor.LEGACY_REGION -> arguments.add(
                property.path,
                parseLegacyRegion(property.defaultValue ?: "0,0,1,1") ?: return null,
            )
            BlockPropertyEditor.ENUM -> arguments.addProperty(
                property.path,
                property.defaultValue?.takeIf { it in property.choices }
                    ?: property.choices.firstOrNull() ?: return null,
            )
            BlockPropertyEditor.FLOW_REFERENCE -> {
                arguments.addProperty(property.path, target?.flowId ?: return null)
            }
            BlockPropertyEditor.FLOW_ARGUMENTS -> {
                arguments.add(property.path, defaultFlowCallArguments(target ?: return null))
            }
        }
    }
    if (contract.kind in setOf("flow.argument.set", "flow.return.set") &&
        !arguments.has("value") && !arguments.has("valueVariable")) {
        arguments.addProperty("value", 0)
    }
    return arguments
}

/** Variables that are already meaningful in this Flow, offered by the variable/condition editor. */
internal fun visualKnownVariables(
    editor: VisualEditorState,
    declared: List<ProjectVariable>,
    currentFlowId: String,
): List<String> = buildSet {
    declared.filter { it.scope == ProjectVariableScope.GLOBAL || it.flowId == currentFlowId }
        .forEach { add(it.name) }
    variableCheckNodes(editor.variableCheckInput()).forEach { node ->
        val args = node.args ?: return@forEach
        when (node.row.kind) {
            "variable.set", "variable.calculate" -> args.get("name")?.takeIf { it.isJsonPrimitive }?.asString?.let(::add)
            "variable.copy" -> listOf("sourceName", "name").forEach { key ->
                args.get(key)?.takeIf { it.isJsonPrimitive }?.asString?.let(::add)
            }
            else -> args.entrySet().forEach { (key, value) ->
                if ((key == "variable" || key.endsWith("Variable")) && value.isJsonPrimitive) add(value.asString)
            }
        }
    }
}.filter { it.matches(com.autoscript.project.store.ProjectNames.pattern) }.sorted()


/**
 * A real editor for the first two visual language building blocks.  It deliberately writes the
 * existing `variable.*` / `control.if` contract, so saved flows compile through the same Rust path
 * as blocks inserted from the library.
 */
@Composable
internal fun VisualVariableConditionDialog(
    contract: BlockContract,
    inputs: Map<String, String>,
    knownVariables: List<String>,
    projectVariables: List<ProjectVariable> = emptyList(),
    currentFlowId: String = "",
    flows: List<ProjectFlow> = emptyList(),
    onManageVariables: (() -> Unit)? = null,
    onDismiss: () -> Unit,
    onConfirm: (Map<String, String>) -> String?,
    confirmLabel: String = "确定",
    titleOverride: String? = null,
) {
    if (contract.kind == "variable.calculate") {
        VisualVariableCalculationDialog(JsonObject().apply {
            addProperty("name", inputs["name"].orEmpty())
            addProperty("expression", inputs["expression"].orEmpty())
        }, projectVariables, currentFlowId, flows, onDismiss,
            onConfirm = { configured -> onConfirm(configured.entrySet().associate { it.key to it.value.asString }) },
            onManageVariables = onManageVariables, confirmLabel = confirmLabel)
        return
    }
    var values by remember(contract.kind, inputs) { mutableStateOf(inputs) }
    var elseIfBranches by remember(contract.kind, inputs) {
        mutableStateOf(parseElseIfDrafts(inputs["elseIf"]))
    }
    var invalidConditions by remember(contract.kind, inputs) { mutableStateOf(emptySet<String>()) }
    var validationError by remember(contract.kind, inputs) { mutableStateOf<String?>(null) }
    var fullScreen by remember { mutableStateOf(false) }
    val title = when (contract.kind) {
        "control.if" -> "条件"
        "variable.set" -> "设置变量"
        "task.log" -> "输出日志"
        "task.prompt" -> "弹出提示"
        "task.runprompt" -> "运行提示"
        "task.comment" -> "添加说明"
        else -> "读取变量"
    }
    StudioPanelDialog(titleOverride ?: title, onDismiss, showFooter = false, fullScreen = fullScreen,
        landscapeWidthDp = if (contract.kind == "control.if") 320 else 600,
        onToggleFullScreen = { fullScreen = !fullScreen }) {
        CompositionLocalProvider(LocalVariablePickerMetadata provides calculationVariables(projectVariables, currentFlowId, flows)) {
            Column(if (fullScreen) Modifier.fillMaxSize() else Modifier.fillMaxWidth().heightIn(max = 480.dp)) {
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(8.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    when (contract.kind) {
                        "control.if" -> {
                            CompoundConditionEditor(values["condition"].orEmpty(), conditionSeed(values["variable"].orEmpty(), values["operator"].orEmpty(), values["value"].orEmpty(), values["valueVariable"].orEmpty()), knownVariables, { valid -> invalidConditions = if (valid) invalidConditions - "main" else invalidConditions + "main" }) {
                                values = values + ("condition" to it)
                            }
                            if (values["condition"].isNullOrBlank()) {
                                CompactComparisonEditor(values["variable"].orEmpty(), values["operator"].orEmpty(),
                                    values["value"].orEmpty(), values["valueVariable"].orEmpty(), knownVariables,
                                    { values = values + ("variable" to it) }, { values = values + ("operator" to it) },
                                    { values = values + ("value" to it) }, { values = values + ("valueVariable" to it) })
                            }
                            if (elseIfBranches.isNotEmpty()) {
                                Text("否则如果分支", color = AutoScriptPalette.Accent, fontSize = 11.sp)
                                elseIfBranches.forEachIndexed { index, branch ->
                                    Column(
                                        Modifier.fillMaxWidth().border(1.dp, AutoScriptPalette.Divider, RoundedCornerShape(5.dp)).padding(5.dp),
                                        verticalArrangement = Arrangement.spacedBy(4.dp),
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text("否则如果 ${index + 1}", fontSize = 11.sp, modifier = Modifier.weight(1f))
                                        }
                                        CompoundConditionEditor(branch.condition, conditionSeed(branch.variable, branch.operator, branch.value, branch.valueVariable), knownVariables, { valid -> invalidConditions = if (valid) invalidConditions - "branch$index" else invalidConditions + "branch$index" }) { next ->
                                            elseIfBranches = elseIfBranches.toMutableList().apply { set(index, branch.copy(condition = next)) }
                                        }
                                        if (branch.condition.isBlank()) {
                                            CompactComparisonEditor(branch.variable, branch.operator, branch.value,
                                                branch.valueVariable, knownVariables,
                                                { next -> elseIfBranches = elseIfBranches.toMutableList().apply { set(index, branch.copy(variable = next)) } },
                                                { next -> elseIfBranches = elseIfBranches.toMutableList().apply { set(index, branch.copy(operator = next)) } },
                                                { next -> elseIfBranches = elseIfBranches.toMutableList().apply { set(index, branch.copy(value = next)) } },
                                                { next -> elseIfBranches = elseIfBranches.toMutableList().apply { set(index, branch.copy(valueVariable = next)) } })
                                        }
                                    }
                                }
                            }
                            ImageToolHelp("比较值按 JSON 标量填写：数字直接输入，字符串加双引号；选择右变量时优先读取变量。复合条件支持与/或/非，与/或短路，最多64项、8层、8192字节。新增或删除分支用顶栏“＋/－否则如果”。")
                        }
                        "variable.set" -> {
                            VisualVariableField("变量名", values["name"].orEmpty(), knownVariables) { values = values + ("name" to it) }
                            OutlinedTextField(value = values["value"].orEmpty(), onValueChange = { values = values + ("value" to it) }, label = { Text("值（字符串请用双引号）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        }
                        "task.log" -> {
                            Text("日志级别", color = AutoScriptPalette.Accent, fontSize = 11.sp)
                            Row(Modifier.fillMaxWidth()) {
                                listOf("info" to "信息", "warn" to "警告", "error" to "错误").forEach { (level, label) ->
                                    val selected = values["level"] == level
                                    Text(
                                        label,
                                        color = if (selected) androidx.compose.ui.graphics.Color.White else AutoScriptPalette.TextPrimary,
                                        fontSize = 10.sp,
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier.weight(1f).padding(horizontal = 2.dp)
                                            .background(if (selected) AutoScriptPalette.Accent else androidx.compose.ui.graphics.Color(0xFFF1F4F8), RoundedCornerShape(3.dp))
                                            .clickable { values = values + ("level" to level) }
                                            .padding(vertical = 7.dp),
                                    )
                                }
                            }
                            OutlinedTextField(
                                value = values["message"].orEmpty(),
                                onValueChange = { values = values + ("message" to it) },
                                label = { Text("日志内容") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            VisualVariableField("变量值（可选，优先输出）", values["valueVariable"].orEmpty(), knownVariables) {
                                values = values + ("valueVariable" to it)
                            }
                        }
                        "task.prompt", "task.runprompt" -> {
                            OutlinedTextField(
                                value = values["message"].orEmpty(),
                                onValueChange = { values = values + ("message" to it) },
                                label = { Text("提示内容") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            VisualVariableField("变量值（可选，优先提示）", values["valueVariable"].orEmpty(), knownVariables) {
                                values = values + ("valueVariable" to it)
                            }
                        }
                        "task.comment" -> {
                            OutlinedTextField(
                                value = values["message"].orEmpty(),
                                onValueChange = { values = values + ("message" to it) },
                                label = { Text("注释内容") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        else -> {
                            VisualVariableField("来源变量", values["sourceName"].orEmpty(), knownVariables) { values = values + ("sourceName" to it) }
                            VisualVariableField("目标变量", values["name"].orEmpty(), knownVariables) { values = values + ("name" to it) }
                        }
                    }
                    if (contract.kind != "control.if") validationError?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 11.sp) }
                }
                if (contract.kind == "control.if") validationError?.let {
                    Text(it, Modifier.padding(horizontal = 8.dp, vertical = 4.dp), color = AutoScriptPalette.Danger, fontSize = 11.sp)
                }
                HorizontalDivider(color = AutoScriptPalette.Divider)
                Row(Modifier.fillMaxWidth().height(studioCompactControlHeight(if (contract.kind == "control.if") 36 else 32,
                    LocalDensity.current.fontScale).dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("取消", textAlign = TextAlign.Center, color = AutoScriptPalette.TextPrimary, fontSize = 13.sp, modifier = Modifier.weight(1f).fillMaxSize().clickable(onClick = onDismiss).wrapContentSize(Alignment.Center))
                    if (contract.kind == "control.if") Box(Modifier.width(1.dp).height(24.dp).background(AutoScriptPalette.Divider))
                    Text(confirmLabel, textAlign = TextAlign.Center, color = AutoScriptPalette.Accent, fontSize = 13.sp, modifier = Modifier.weight(1f).fillMaxSize().clickable {
                        val complete = if (contract.kind == "control.if") values + ("elseIf" to serializeElseIfDrafts(elseIfBranches)) else values
                        validationError = if (invalidConditions.isNotEmpty()) "请修正复合条件输入后再保存" else onConfirm(complete)
                    }.wrapContentSize(Alignment.Center))
                }
            }
        }
    }
}

private data class VisualElseIfDraft(
    val variable: String = "value",
    val operator: String = "equals",
    val value: String = "true",
    val valueVariable: String = "",
    val condition: String = "",
)

private fun parseElseIfDrafts(value: String?): List<VisualElseIfDraft> = runCatching {
    JsonParser.parseString(value ?: "[]").asJsonArray.mapNotNull { item ->
        item.takeIf { it.isJsonObject }?.asJsonObject?.let { branch ->
            VisualElseIfDraft(
                variable = branch.get("variable")?.asString ?: return@let null,
                operator = branch.get("operator")?.asString ?: "equals",
                value = branch.get("value")?.toString() ?: "true",
                valueVariable = branch.get("valueVariable")?.asString.orEmpty(),
                condition = branch.get("condition")?.toString().orEmpty(),
            )
        }
    }
}.getOrDefault(emptyList())

private fun serializeElseIfDrafts(branches: List<VisualElseIfDraft>): String = JsonArray().apply {
    branches.forEach { branch -> add(JsonObject().apply {
        addProperty("variable", branch.variable)
        addProperty("operator", branch.operator)
        add("value", parseScalar(branch.value) ?: com.google.gson.JsonPrimitive(branch.value))
        if (branch.valueVariable.isNotBlank()) addProperty("valueVariable", branch.valueVariable)
        if (branch.condition.isNotBlank()) add("condition", JsonParser.parseString(branch.condition))
    }) }
}.toString()

@Composable
internal fun VisualVariableField(label: String, value: String, choices: List<String>,
    variables: List<CalculationVariable> = LocalVariablePickerMetadata.current, onValueChange: (String) -> Unit) {
    if (choices.isEmpty()) {
        ImageToolCompactInput(label, value, true, Modifier.fillMaxWidth(), onChange = onValueChange)
        return
    }
    VariableChoiceAnchor("选择变量", value, listOf(VariablePickerOption("", "清空")) + variablePickerOptions(choices, variables), onValueChange) { open ->
        ImageToolCompactInput(label, value, true, Modifier.fillMaxWidth(), trailing = {
            Box(Modifier.size(24.dp).clickable(onClick = open), contentAlignment = Alignment.Center) {
                androidx.compose.material3.Icon(androidx.compose.ui.res.painterResource(R.drawable.ic_list_24),
                    "选择变量", Modifier.size(16.dp), tint = AutoScriptPalette.Accent)
            }
        }, onChange = onValueChange)
    }
}

@Composable
internal fun VisualNodeArgumentsDialog(
    contract: BlockContract,
    arguments: JsonObject,
    flows: List<ProjectFlow>,
    currentFlowId: String,
    resources: List<JsonObject>,
    knownVariables: List<String> = emptyList(),
    imageProject: ProjectSnapshot? = null,
    imageDialogVisible: Boolean = true,
    imageCaptureSelection: ImageToolCodeGen.VisualSelection? = null,
    imageCaptureTemplatePath: String? = null,
    onPickImageFromScreen: ((ImageToolMode) -> Unit)? = null,
    onManageVariables: (() -> Unit)? = null,
    onDismiss: () -> Unit,
    onConfirm: (JsonObject) -> Unit,
    confirmLabel: String = "保存",
    knownLabels: List<String> = emptyList(),
    onResourcesUpdated: ((ProjectSnapshot) -> Unit)? = null,
    runtimeClient: com.autoscript.runtime.client.RuntimeClient? = null,
    projectStore: com.autoscript.project.store.ProjectStore? = null,
    applyInitialTemplateRegion: Boolean = false,
) {
    if (contract.kind == "vision.readresult" && imageProject != null) {
        RecognitionResultReaderDialog(imageProject, currentFlowId, imageDialogVisible, { onManageVariables?.invoke() },
            onDismiss, { onConfirm(it.single().second) }, initial = arguments, confirmLabel = confirmLabel)
        return
    }
    if(contract.kind == "vision.resultaction" && imageProject != null) {
        RecognitionResultActionDialog(imageProject,currentFlowId,imageDialogVisible,{onManageVariables?.invoke()},onDismiss,{onConfirm(it.single().second)},arguments)
        return
    }
    if (contract.kind == "ui.get" && imageProject != null) {
        VisualUiParameterDialog(arguments, imageProject.manifest.runnerUi, imageProject.manifest.variables, currentFlowId,
            flows, onManageVariables, onDismiss, { onConfirm(it); null }, confirmLabel)
        return
    }
    if (contract.kind in visualUtilityKinds && imageProject != null) {
        VisualUtilityArguments(contract,arguments,imageProject,currentFlowId,onManageVariables,onDismiss,onConfirm,confirmLabel)
        return
    }
    if (contract.kind in visionExtensionKinds && imageProject != null) {
        VisualVisionExtensionDialog(contract, arguments, imageProject, currentFlowId, onManageVariables, onDismiss, onConfirm, confirmLabel,
            visible = imageDialogVisible, captureSelection = imageCaptureSelection, onPickFromScreen = onPickImageFromScreen,projectStore=projectStore)
        return
    }
    if (contract.kind in floatingKinds && imageProject!=null) {
        VisualFloatingDialog(contract,arguments,imageProject,currentFlowId,onManageVariables,onDismiss,onConfirm,confirmLabel)
        return
    }
    if (contract.kind in runtimeToolKinds && imageProject != null) {
        VisualRuntimeToolsDialog(contract, arguments, imageProject, currentFlowId, onManageVariables, onDismiss, onConfirm, confirmLabel, onResourcesUpdated)
        return
    }
    if(contract.kind in frameResourceKinds && imageProject!=null) {
        VisualFrameResourceDialog(contract,arguments,imageProject,currentFlowId,onManageVariables,onDismiss,onConfirm,confirmLabel)
        return
    }
    if (contract.kind in setOf("ui.confirm", "ui.dialog") && imageProject != null) {
        VisualScriptDialogArguments(contract, arguments, imageProject, currentFlowId, onManageVariables, onDismiss, onConfirm, confirmLabel)
        return
    }
    if (contract.kind in setOf("ui.set", "ui.command") && imageProject != null) {
        VisualUiCommandDialog(contract, arguments, imageProject, currentFlowId, knownVariables, onManageVariables, onDismiss, onConfirm, confirmLabel)
        return
    }
    if (contract.kind.startsWith("input.")) {
        CompactInputArgumentsDialog(contract, arguments, onDismiss, onConfirm, confirmLabel)
        return
    }
    if (contract.kind in setOf("variable.set", "variable.copy", "control.if")) {
        VisualVariableConditionDialog(contract, conditionPropertyTexts(contract, arguments), knownVariables,
            imageProject?.manifest?.variables.orEmpty(), currentFlowId, flows, onManageVariables, onDismiss,
            onConfirm = { configured ->
                val parsed = parseBlockArguments(contract, configured, null, emptyMap(), resources)
                if (parsed.first == null) parsed.second else { onConfirm(requireNotNull(parsed.first)); null }
            }, confirmLabel = confirmLabel)
        return
    }
    if (ConfiguredEntryArgumentsDialog(contract, arguments, imageProject?.manifest?.variables.orEmpty(), flows,
            currentFlowId, knownVariables, imageProject?.manifest?.debugSettings ?: com.autoscript.project.store.ProjectDebugSettings(),
            onManageVariables, onDismiss, onConfirm, confirmLabel, knownLabels)) return
    if (contract.kind == "variable.calculate") {
        VisualVariableCalculationDialog(arguments, imageProject?.manifest?.variables.orEmpty(), currentFlowId, flows,
            onDismiss, onConfirm = { onConfirm(it); null }, onManageVariables = onManageVariables, confirmLabel = confirmLabel)
        return
    }
    if (contract.kind in visualRecognitionKinds || contract.kind == "vision.result") {
        VisualImageRecognitionDialog(
            contract, arguments, resources, knownVariables, imageProject,
            visible = imageDialogVisible, captureSelection = imageCaptureSelection,
            captureTemplatePath = imageCaptureTemplatePath, onPickFromScreen = onPickImageFromScreen,
            confirmLabel = confirmLabel,
            onDismiss = onDismiss, onConfirm = { _, configured -> onConfirm(configured) },
            currentFlowId = currentFlowId, onManageVariables = onManageVariables,
            runtimeClient = runtimeClient,
            applyInitialTemplateRegion = applyInitialTemplateRegion,
        )
        return
    }
    val targets = flows.filter { it.flowId != currentFlowId }
    var targetFlowId by remember(contract.kind, arguments) {
        mutableStateOf(arguments.get("targetFlowId")?.takeIf { it.isJsonPrimitive }?.asString ?: targets.firstOrNull()?.flowId)
    }
    var inputs by remember(contract.kind, arguments) { mutableStateOf(blockPropertyTexts(contract, arguments)) }
    var flowInputs by remember(contract.kind, arguments, targetFlowId) {
        val target = targets.firstOrNull { it.flowId == targetFlowId }
        mutableStateOf(target?.let { flowCallArgumentTexts(it, arguments.getAsJsonObject("arguments")) }.orEmpty())
    }
    var validationError by remember(contract.kind, arguments) { mutableStateOf<String?>(null) }
    var fullScreen by remember { mutableStateOf(false) }
    val target = targets.firstOrNull { it.flowId == targetFlowId }
    val footerHeight = studioCompactControlHeight(40, LocalDensity.current.fontScale).dp
    StudioPanelDialog(contract.title, onDismiss, showFooter = false, fullScreen = fullScreen,
        onToggleFullScreen = { fullScreen = !fullScreen }) {
            Column(if (fullScreen) Modifier.fillMaxSize() else Modifier.fillMaxWidth().heightIn(max = 480.dp)) {
                Column(
                    Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    contract.properties.forEach { property ->
                        if (contract.kind == "ui.command") {
                            val reading = inputs["operation"].orEmpty().startsWith("get:")
                            if (reading && property.path in setOf("value", "valueVariable")) return@forEach
                            if (!reading && property.path == "resultVariable") return@forEach
                        }
                        when (property.editor) {
                            BlockPropertyEditor.FLOW_REFERENCE -> {
                                Text(property.label, color = AutoScriptPalette.Accent, fontSize = 11.sp)
                                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                                    targets.forEach { candidate ->
                                        TextButton(onClick = {
                                            targetFlowId = candidate.flowId
                                            inputs = inputs + (property.path to candidate.flowId)
                                            flowInputs = defaultFlowCallArgumentTexts(candidate)
                                            validationError = null
                                        }) { Text(if (candidate.flowId == targetFlowId) "● ${candidate.flowId}" else candidate.flowId) }
                                    }
                                }
                            }
                            BlockPropertyEditor.FLOW_ARGUMENTS -> {
                                target?.params.orEmpty().forEach { parameter ->
                                    val name = parameter.get("name")?.asString.orEmpty()
                                    OutlinedTextField(
                                        value = flowInputs[name].orEmpty(),
                                        onValueChange = { flowInputs = flowInputs + (name to it) },
                                        label = { Text("调用参数 $name") },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                }
                            }
                            BlockPropertyEditor.RESOURCE_LIST -> {
                                Text(property.label, color = AutoScriptPalette.Accent, fontSize = 11.sp)
                                val selected = runCatching { JsonParser.parseString(inputs[property.path].orEmpty()).asJsonArray.map { it.asString } }.getOrDefault(emptyList())
                                var search by remember(property.path) { mutableStateOf("") }
                                OutlinedTextField(value = search, onValueChange = { search = it.take(128) },
                                    placeholder = { Text("搜索模板 · 已选 ${selected.size}/64") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                                Column(Modifier.fillMaxWidth().heightIn(max = 120.dp).verticalScroll(rememberScrollState())) {
                                    resourcePaths(resources, property.resourceKind).filter { it.contains(search, true) }.forEach { path ->
                                        Text(if (path in selected) "✓ ${path.substringAfterLast('/')}" else path.substringAfterLast('/'),
                                            color = if (path in selected) AutoScriptPalette.Accent else AutoScriptPalette.TextPrimary,
                                            fontSize = 12.sp, modifier = Modifier.fillMaxWidth().clickable {
                                                val next = if (path in selected) selected - path else if (selected.size < 64) selected + path else selected
                                                inputs = inputs + (property.path to JsonArray().apply { next.forEach(::add) }.toString())
                                            }.padding(vertical = 6.dp))
                                    }
                                }
                                Text("按选择顺序匹配；再次点击取消。", fontSize = 10.sp, color = AutoScriptPalette.TextSecondary)
                            }
                            BlockPropertyEditor.RESOURCE -> {
                                Text(property.label, color = AutoScriptPalette.Accent, fontSize = 11.sp)
                                val choices = resourcePaths(resources, property.resourceKind)
                                if (choices.isEmpty()) Text("项目中没有可选资源", color = MaterialTheme.colorScheme.error)
                                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                                    choices.forEach { path ->
                                        TextButton(onClick = { inputs = inputs + (property.path to path) }) {
                                            Text(if (inputs[property.path] == path) "● $path" else path)
                                        }
                                    }
                                }
                            }
                            BlockPropertyEditor.BOOLEAN,
                            BlockPropertyEditor.ENUM,
                            BlockPropertyEditor.INTEGER_ENUM -> {
                                Text(property.label, color = AutoScriptPalette.Accent, fontSize = 11.sp)
                                val choices = if (property.editor == BlockPropertyEditor.BOOLEAN) listOf("true", "false") else property.choices
                                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                                    choices.forEach { choice ->
                                        TextButton(onClick = { inputs = inputs + (property.path to choice) }) {
                                            Text(if (inputs[property.path] == choice) "● $choice" else choice)
                                        }
                                    }
                                }
                            }
                            BlockPropertyEditor.STRING -> if (property.path.endsWith("Variable")) {
                                VisualVariableField(property.label, inputs[property.path].orEmpty(), knownVariables,
                                    calculationVariables(imageProject?.manifest?.variables.orEmpty(), currentFlowId, flows)) {
                                    inputs = inputs + (property.path to it)
                                }
                            } else OutlinedTextField(
                                value = inputs[property.path].orEmpty(),
                                onValueChange = { inputs = inputs + (property.path to it) },
                                label = { Text(property.label) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            else -> OutlinedTextField(
                                value = inputs[property.path].orEmpty(),
                                onValueChange = { inputs = inputs + (property.path to it) },
                                label = { Text(property.label) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                    if (contract.properties.isEmpty()) {
                        Text("这个积木没有可编辑参数", color = AutoScriptPalette.TextSecondary, fontSize = 12.sp)
                    }
                    validationError?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 11.sp) }
                }
                HorizontalDivider(color = AutoScriptPalette.Divider)
                Row(Modifier.fillMaxWidth().height(footerHeight)) {
                    Text("取消", textAlign = TextAlign.Center, color = AutoScriptPalette.TextPrimary,
                        fontSize = 13.sp, modifier = Modifier.weight(1f).fillMaxSize()
                            .clickable(onClick = onDismiss).wrapContentSize(Alignment.Center))
                    Box(Modifier.size(width = 1.dp, height = footerHeight).background(AutoScriptPalette.Divider))
                    Text(confirmLabel, textAlign = TextAlign.Center, color = AutoScriptPalette.Accent,
                        fontSize = 13.sp, modifier = Modifier.weight(1f).fillMaxSize()
                            .clickable {
                                val parsed = parseBlockArguments(contract, inputs, target, flowInputs, resources)
                                if (parsed.first == null) validationError = parsed.second ?: "参数无效"
                                else onConfirm(requireNotNull(parsed.first))
                            }.wrapContentSize(Alignment.Center))
                }
            }
    }
}

private fun defaultFlowCallArgumentTexts(flow: ProjectFlow): Map<String, String> =
    flowCallArgumentTexts(flow, defaultFlowCallArguments(flow))

internal fun flowCallArgumentTexts(flow: ProjectFlow, arguments: JsonObject?): Map<String, String> =
    flow.params.associate { parameter ->
        val name = parameter.get("name")?.asString.orEmpty()
        name to arguments?.get(name)?.let { value ->
            if (value.isJsonPrimitive && value.asJsonPrimitive.isString) value.asString else value.toString()
        }.orEmpty()
    }

internal fun blockPropertyTexts(
    contract: BlockContract,
    arguments: JsonObject,
): Map<String, String> = contract.properties
    .filter { it.editor != BlockPropertyEditor.FLOW_ARGUMENTS }
    .associate { property ->
        val value = arguments.get(property.path)
        property.path to when {
            value == null || value.isJsonNull -> property.defaultValue.orEmpty()
            property.editor == BlockPropertyEditor.POINT -> formatPoint(value.asJsonObject)
            property.editor == BlockPropertyEditor.RECT -> formatRect(value.asJsonObject)
            property.editor == BlockPropertyEditor.COLOR -> formatColor(value.asInt)
            property.editor == BlockPropertyEditor.MULTI_COLOR_SAMPLES -> {
                formatMultiColorSamples(value.asJsonArray)
            }
            property.editor == BlockPropertyEditor.LEGACY_PATTERN -> {
                formatLegacyPattern(value.asJsonArray)
            }
            property.editor == BlockPropertyEditor.LEGACY_FIXED_PATTERN -> {
                formatLegacyFixedPattern(value.asJsonArray)
            }
            property.editor == BlockPropertyEditor.LEGACY_COLOR_GROUP -> {
                formatLegacyColorGroup(value.asJsonArray)
            }
            property.editor == BlockPropertyEditor.LEGACY_REGION -> {
                formatLegacyRegion(value.asJsonObject)
            }
            property.editor != BlockPropertyEditor.SCALAR &&
                value.isJsonPrimitive && value.asJsonPrimitive.isString -> value.asString
            else -> value.toString()
    }
}

internal fun conditionPropertyTexts(contract: BlockContract, arguments: JsonObject): Map<String, String> =
    blockPropertyTexts(contract, arguments).let { values ->
        if (contract.kind != "control.if") values else values + (
            "elseIf" to (arguments.getAsJsonArray("elseIf")?.toString() ?: "[]")
        ) + ("condition" to arguments.get("condition")?.toString().orEmpty())
    }

internal fun parseBlockArguments(
    contract: BlockContract,
    inputs: Map<String, String>,
    targetFlow: ProjectFlow?,
    flowInputs: Map<String, String>,
    resources: List<JsonObject>,
): Pair<JsonObject?, String?> {
    val arguments = JsonObject()
    val uiReading = contract.kind == "ui.command" && inputs["operation"].orEmpty().startsWith("get:")
    contract.properties.forEach { property ->
        if (contract.kind == "ui.command") {
            if (uiReading && property.path == "value") { arguments.addProperty("value", ""); return@forEach }
            if (uiReading && property.path == "valueVariable") return@forEach
            if (!uiReading && property.path == "resultVariable") return@forEach
        }
        val text = inputs[property.path].orEmpty()
        if (!property.required && text.isBlank() && property.editor != BlockPropertyEditor.FLOW_ARGUMENTS) {
            return@forEach
        }
        when (property.editor) {
            BlockPropertyEditor.BOOLEAN -> arguments.addProperty(
                property.path,
                text.toBooleanStrictOrNull()
                    ?: return null to "${property.label}必须是 true 或 false",
            )
            BlockPropertyEditor.INTEGER -> arguments.addProperty(
                property.path,
                text.toLongOrNull() ?: return null to "${property.label}必须是整数",
            )
            BlockPropertyEditor.INTEGER_ENUM -> {
                if (text !in property.choices) return null to "${property.label}不在允许值中"
                val value = text.toLongOrNull() ?: return null to "${property.label}必须是整数"
                arguments.addProperty(property.path, value)
            }
            BlockPropertyEditor.NUMBER -> arguments.addProperty(
                property.path,
                text.toDoubleOrNull()?.takeIf { it.isFinite() }
                    ?: return null to "${property.label}必须是有限数字",
            )
            BlockPropertyEditor.STRING -> {
                arguments.addProperty(property.path, text)
            }
            BlockPropertyEditor.RESOURCE -> {
                if (text !in resourcePaths(resources, property.resourceKind)) {
                    return null to "${property.label}不是已登记的项目资源"
                }
                arguments.addProperty(property.path, text)
            }
            BlockPropertyEditor.RESOURCE_LIST -> {
                val selected = runCatching { JsonParser.parseString(text).asJsonArray }.getOrNull()
                    ?: return null to "${property.label}需要资源列表"
                val choices = resourcePaths(resources, property.resourceKind)
                if (selected.size() !in 1..64 || selected.any { !it.isJsonPrimitive || !it.asJsonPrimitive.isString || it.asString !in choices } ||
                    selected.map { it.asString }.distinct().size != selected.size()) return null to "请选择1–64个不同的项目模板"
                arguments.add(property.path, selected.deepCopy())
            }
            BlockPropertyEditor.SCALAR -> arguments.add(
                property.path,
                parseScalar(text) ?: return null to "${property.label}必须是JSON标量",
            )
            BlockPropertyEditor.POINT -> arguments.add(
                property.path,
                parsePoint(text) ?: return null to "${property.label}必须是 x,y",
            )
            BlockPropertyEditor.RECT -> arguments.add(
                property.path,
                parseRect(text) ?: return null to "${property.label}必须是 left,top,right,bottom",
            )
            BlockPropertyEditor.COLOR -> arguments.addProperty(
                property.path,
                parseColor(text) ?: return null to "${property.label}必须是 #RRGGBB 或 0-16777215",
            )
            BlockPropertyEditor.MULTI_COLOR_SAMPLES -> arguments.add(
                property.path,
                parseMultiColorSamples(text)
                    ?: return null to "${property.label}格式必须是 x,y,#RRGGBB,容差；最多64组并用分号分隔",
            )
            BlockPropertyEditor.LEGACY_PATTERN -> arguments.add(
                property.path,
                parseLegacyPattern(text)
                    ?: return null to
                        "${property.label}格式必须是 dx,dy,#RRGGBB,红容差,绿容差,蓝容差；首项偏移必须为 0,0，最多65组并用分号分隔",
            )
            BlockPropertyEditor.LEGACY_FIXED_PATTERN -> arguments.add(
                property.path,
                parseLegacyFixedPattern(text)
                    ?: return null to
                        "${property.label}格式必须是 x,y,#RRGGBB,红容差,绿容差,蓝容差；坐标非负，最多256组并用分号分隔",
            )
            BlockPropertyEditor.LEGACY_COLOR_GROUP -> arguments.add(
                property.path,
                parseLegacyColorGroup(text)
                    ?: return null to
                        "${property.label}格式必须是 #RRGGBB,红容差,绿容差,蓝容差；最多64组并用分号分隔",
            )
            BlockPropertyEditor.LEGACY_REGION -> arguments.add(
                property.path,
                parseLegacyRegion(text)
                    ?: return null to "${property.label}必须是 左,上,宽,高，且宽高大于0",
            )
            BlockPropertyEditor.ENUM -> {
                if (text !in property.choices) return null to "${property.label}不在允许值中"
                arguments.addProperty(property.path, text)
            }
            BlockPropertyEditor.FLOW_REFERENCE -> {
                val target = targetFlow ?: return null to "请选择目标插件"
                arguments.addProperty(property.path, target.flowId)
            }
            BlockPropertyEditor.FLOW_ARGUMENTS -> {
                val target = targetFlow ?: return null to "请选择目标插件"
                val parsed = parseFlowCallArguments(target, flowInputs)
                if (parsed.second != null) return null to parsed.second
                arguments.add(property.path, requireNotNull(parsed.first))
            }
        }
    }
    if (uiReading && !inputs["resultVariable"].orEmpty().matches(com.autoscript.project.store.ProjectNames.pattern)) {
        return null to "请选择接收属性值的字符变量"
    }
    if (contract.kind == "control.if") {
        inputs["condition"]?.takeIf(String::isNotBlank)?.let {
            arguments.add("condition", parseCompoundCondition(it) ?: return null to "复合条件需为64项/8层内的比较与/或/非树")
        }
        val branches = runCatching { JsonParser.parseString(inputs["elseIf"].orEmpty()).asJsonArray }
            .getOrNull() ?: return null to "否则如果分支格式无效"
        if (branches.size() > 32 || branches.any { branch ->
                !branch.isJsonObject || branch.asJsonObject.get("variable")?.isJsonPrimitive != true ||
                    branch.asJsonObject.get("operator")?.isJsonPrimitive != true ||
                    branch.asJsonObject.get("value") == null
            }) return null to "否则如果分支参数无效"
        if (branches.size() > 0) arguments.add("elseIf", branches)
        if (branches.any { it.asJsonObject.get("condition")?.let { tree -> parseCompoundCondition(tree.toString()) == null } == true }) return null to "否则如果复合条件无效"
    }
    if (contract.kind == "task.comment" && arguments.get("message")?.asString.isNullOrBlank()) {
        return null to "注释内容不能为空"
    }
    if (contract.kind.startsWith("vision.") || contract.kind.startsWith("ocr.")) {
        if (contract.kind in recognitionColorGroupKinds) {
            runCatching {
                com.autoscript.project.store.ProjectGlyphColor.decodeAdditional(
                    arguments["additionalColors"]?.asString.orEmpty(), arguments["rgb"].asInt)
            }.getOrElse { return null to "色组需为不同的RRGGBB:容差（0–255），含主色最多16色" }
        }
        if(contract.kind in setOf("ocr.glyph","ocr.glyphdetails")) {
            val extra = runCatching {com.autoscript.project.store.ProjectGlyphColor.decodeAdditional(arguments["additionalColors"]?.asString.orEmpty(),arguments["foregroundRgb"].asInt)}
                .getOrElse {return null to "前景色组需为不同的RRGGBB:容差（0–255），总共最多16色"}
            val area=arguments["region"]?.asJsonObject
            val left=area?.get("left")?.asLong ?: arguments["left"]?.asLong
            val top=area?.get("top")?.asLong ?: arguments["top"]?.asLong
            val right=area?.get("right")?.asLong ?: arguments["right"]?.asLong
            val bottom=area?.get("bottom")?.asLong ?: arguments["bottom"]?.asLong
            if(left!=null && top!=null && right!=null && bottom!=null && (right-left)*(bottom-top)*(extra.size+1)>64_000_000L)
                return null to "前景色比较超过6400万次，请缩小范围或减少颜色"
        }
        val bounds = mapOf(
            "tolerance" to (0..255),
            "anchorTolerance" to (0..255),
            "similarityPermille" to (0..1000),
            "limit" to recognitionLimitRange(contract.kind),
            "spaceGapColumns" to (0..65535),
        )
        bounds.forEach { (name, allowed) ->
            arguments.get(name)?.let { value ->
                if (value.asInt !in allowed) return null to "${if (name == "limit") "数量上限" else name} 必须在 ${allowed.first}..${allowed.last} 之间"
            }
        }
        arguments.getAsJsonObject("region")?.let { region ->
            if (region.get("right").asInt <= region.get("left").asInt ||
                region.get("bottom").asInt <= region.get("top").asInt) {
                return null to "识别区域的右/下坐标必须大于左/上坐标"
            }
        }
    }
    return arguments to null
}

internal fun parseScalar(text: String) = runCatching { JsonParser.parseString(text) }
    .getOrNull()
    ?.takeUnless { it.isJsonArray || it.isJsonObject }

internal fun parsePoint(text: String): JsonObject? {
    val values = text.split(',').map(String::trim)
    if (values.size != 2) return null
    val x = values[0].toIntOrNull() ?: return null
    val y = values[1].toIntOrNull() ?: return null
    return JsonObject().apply {
        addProperty("x", x)
        addProperty("y", y)
    }
}

internal fun parseRect(text: String): JsonObject? {
    val values = text.split(',').map(String::trim)
    if (values.size != 4) return null
    val edges = values.map { it.toIntOrNull() ?: return null }
    if (edges[2] <= edges[0] || edges[3] <= edges[1]) return null
    return JsonObject().apply {
        addProperty("left", edges[0])
        addProperty("top", edges[1])
        addProperty("right", edges[2])
        addProperty("bottom", edges[3])
    }
}

internal fun parseColor(text: String): Int? {
    val value = text.trim()
    val parsed = when {
        value.startsWith("#") -> value.drop(1).takeIf { it.length == 6 }?.toIntOrNull(16)
        value.startsWith("0x", ignoreCase = true) -> value.drop(2).toIntOrNull(16)
        else -> value.toIntOrNull()
    }
    return parsed?.takeIf { it in 0..0xFFFFFF }
}

internal fun parseMultiColorSamples(text: String): JsonArray? {
    val entries = text.split(';').map(String::trim)
    if (entries.isEmpty() || entries.size > 64 || entries.any(String::isEmpty)) return null
    val samples = JsonArray()
    entries.forEach { entry ->
        val values = entry.split(',').map(String::trim)
        if (values.size != 4) return null
        val x = values[0].toIntOrNull() ?: return null
        val y = values[1].toIntOrNull() ?: return null
        val rgb = parseColor(values[2]) ?: return null
        val tolerance = values[3].toIntOrNull()?.takeIf { it in 0..255 } ?: return null
        samples.add(JsonObject().apply {
            addProperty("x", x)
            addProperty("y", y)
            addProperty("rgb", rgb)
            addProperty("tolerance", tolerance)
        })
    }
    return samples
}

private fun formatMultiColorSamples(samples: JsonArray): String = samples.joinToString(";") { value ->
    val sample = value.asJsonObject
    "${sample.get("x").asInt},${sample.get("y").asInt},${formatColor(sample.get("rgb").asInt)},${sample.get("tolerance").asInt}"
}

private fun JsonObject.channelTolerances(): String =
    "${get("toleranceRed").asInt},${get("toleranceGreen").asInt},${get("toleranceBlue").asInt}"

private fun JsonObject.addChannelTolerances(red: Int, green: Int, blue: Int) {
    addProperty("toleranceRed", red)
    addProperty("toleranceGreen", green)
    addProperty("toleranceBlue", blue)
}

/**
 * Parses per-channel legacy tolerances. The legacy grammar keeps red, green and blue independent,
 * so a single collapsed tolerance would silently change matching behaviour.
 */
private fun parseChannelTolerances(values: List<String>): Triple<Int, Int, Int>? {
    val channels = values.map { it.toIntOrNull()?.takeIf { channel -> channel in 0..255 } ?: return null }
    return Triple(channels[0], channels[1], channels[2])
}

internal fun parseLegacyPattern(text: String): JsonArray? {
    val entries = text.split(';').map(String::trim)
    if (entries.isEmpty() || entries.size > 65 || entries.any(String::isEmpty)) return null
    val samples = JsonArray()
    entries.forEachIndexed { index, entry ->
        val values = entry.split(',').map(String::trim)
        if (values.size != 6) return null
        val dx = values[0].toIntOrNull() ?: return null
        val dy = values[1].toIntOrNull() ?: return null
        if (index == 0 && (dx != 0 || dy != 0)) return null
        val rgb = parseColor(values[2]) ?: return null
        val (red, green, blue) = parseChannelTolerances(values.subList(3, 6)) ?: return null
        samples.add(JsonObject().apply {
            addProperty("dx", dx)
            addProperty("dy", dy)
            addProperty("rgb", rgb)
            addChannelTolerances(red, green, blue)
        })
    }
    return samples
}

private fun formatLegacyPattern(samples: JsonArray): String = samples.joinToString(";") { value ->
    val sample = value.asJsonObject
    "${sample.get("dx").asInt},${sample.get("dy").asInt}," +
        "${formatColor(sample.get("rgb").asInt)},${sample.channelTolerances()}"
}

internal fun parseLegacyFixedPattern(text: String): JsonArray? {
    val entries = text.split(';').map(String::trim)
    if (entries.isEmpty() || entries.size > 256 || entries.any(String::isEmpty)) return null
    val samples = JsonArray()
    entries.forEach { entry ->
        val values = entry.split(',').map(String::trim)
        if (values.size != 6) return null
        val x = values[0].toIntOrNull()?.takeIf { it >= 0 } ?: return null
        val y = values[1].toIntOrNull()?.takeIf { it >= 0 } ?: return null
        val rgb = parseColor(values[2]) ?: return null
        val (red, green, blue) = parseChannelTolerances(values.subList(3, 6)) ?: return null
        samples.add(JsonObject().apply {
            addProperty("x", x)
            addProperty("y", y)
            addProperty("rgb", rgb)
            addChannelTolerances(red, green, blue)
        })
    }
    return samples
}

private fun formatLegacyFixedPattern(samples: JsonArray): String = samples.joinToString(";") { value ->
    val sample = value.asJsonObject
    "${sample.get("x").asInt},${sample.get("y").asInt}," +
        "${formatColor(sample.get("rgb").asInt)},${sample.channelTolerances()}"
}

internal fun parseLegacyColorGroup(text: String): JsonArray? {
    val entries = text.split(';').map(String::trim)
    if (entries.isEmpty() || entries.size > 64 || entries.any(String::isEmpty)) return null
    val colors = JsonArray()
    entries.forEach { entry ->
        val values = entry.split(',').map(String::trim)
        if (values.size != 4) return null
        val rgb = parseColor(values[0]) ?: return null
        val (red, green, blue) = parseChannelTolerances(values.subList(1, 4)) ?: return null
        colors.add(JsonObject().apply {
            addProperty("rgb", rgb)
            addChannelTolerances(red, green, blue)
        })
    }
    return colors
}

private fun formatLegacyColorGroup(colors: JsonArray): String = colors.joinToString(";") { value ->
    val color = value.asJsonObject
    "${formatColor(color.get("rgb").asInt)},${color.channelTolerances()}"
}

/** Legacy regions are origin plus extent, not a half-open rectangle. */
internal fun parseLegacyRegion(text: String): JsonObject? {
    val values = text.split(',').map(String::trim)
    if (values.size != 4) return null
    val left = values[0].toIntOrNull()?.takeIf { it >= 0 } ?: return null
    val top = values[1].toIntOrNull()?.takeIf { it >= 0 } ?: return null
    val width = values[2].toIntOrNull()?.takeIf { it > 0 } ?: return null
    val height = values[3].toIntOrNull()?.takeIf { it > 0 } ?: return null
    return JsonObject().apply {
        addProperty("left", left)
        addProperty("top", top)
        addProperty("width", width)
        addProperty("height", height)
    }
}

private fun formatLegacyRegion(value: JsonObject): String =
    "${value.get("left").asInt},${value.get("top").asInt}," +
        "${value.get("width").asInt},${value.get("height").asInt}"

private fun formatPoint(value: JsonObject): String =
    "${value.get("x").asInt},${value.get("y").asInt}"

private fun formatRect(value: JsonObject): String =
    "${value.get("left").asInt},${value.get("top").asInt}," +
        "${value.get("right").asInt},${value.get("bottom").asInt}"

internal fun formatColor(value: Int): String = String.format(Locale.ROOT, "#%06X", value)

internal fun resourcePaths(
    resources: List<JsonObject>,
    kind: BlockResourceKind?,
): List<String> {
    val manifestKind = when (kind) {
        BlockResourceKind.IMAGE -> "image"
        BlockResourceKind.GLYPH_DICTIONARY -> "glyphDictionary"
        null -> return emptyList()
    }
    return resources.mapNotNull { resource ->
        resource.get("path")?.asString?.takeIf { resource.get("kind")?.asString == manifestKind }
    }.sorted()
}

private fun parseFlowCallArguments(
    flow: ProjectFlow,
    inputs: Map<String, String>,
): Pair<JsonObject?, String?> {
    val arguments = JsonObject()
    flow.params.forEach { parameter ->
        val name = parameter.get("name")?.asString ?: return null to "参数定义缺少 name"
        val type = parameter.get("type")?.asString ?: return null to "参数 $name 缺少类型"
        val required = parameter.get("required")?.asBoolean == true
        val text = inputs[name].orEmpty()
        if (!required && text.isBlank()) return@forEach
        when (type) {
            "boolean" -> arguments.addProperty(
                name,
                text.toBooleanStrictOrNull() ?: return null to "$name 必须是 true 或 false",
            )
            "integer" -> arguments.addProperty(
                name,
                text.toLongOrNull() ?: return null to "$name 必须是整数",
            )
            "number" -> arguments.addProperty(
                name,
                text.toDoubleOrNull()?.takeIf(Double::isFinite)
                    ?: return null to "$name 必须是有限数字",
            )
            "string" -> arguments.addProperty(name, text)
            else -> return null to "$name 使用了不支持的类型"
        }
    }
    return arguments to null
}

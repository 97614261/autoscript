package com.autoscript.studio

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import com.autoscript.core.designsystem.AutoScriptPalette
import com.autoscript.project.store.ProjectDebugSettings
import com.autoscript.project.store.ProjectVariable
import com.autoscript.project.store.ProjectFlow
import com.autoscript.project.store.ProjectVariableType
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autoscript.studio.StudioDialog as Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.isActive

private val EntryBlue = Color(0xFF3D5AFE)
private val EntryAccent = Color(0xFF3A6EFF)
private val EntryBorder = Color(0xFFC7CBD1)
private val EntryMuted = Color(0xFF727B8B)
private val EntryPanel = Color(0xFFF8F9FB)

private data class PickerRequest(
    val title: String,
    val options: List<String>,
    val selected: String? = null,
    val isVariable: Boolean = false,
    val onSelected: (String) -> Unit,
)

/** Editing and insertion reuse the same loop/jump/debug panels, with typed initial values. */
@Composable
internal fun ConfiguredEntryArgumentsDialog(
    contract: com.autoscript.studio.generated.BlockContract,
    arguments: com.google.gson.JsonObject,
    variables: List<ProjectVariable>, flows: List<ProjectFlow>, currentFlowId: String,
    knownVariables: List<String>, debugSettings: ProjectDebugSettings,
    onManageVariables: (() -> Unit)?, onDismiss: () -> Unit, onConfirm: (com.google.gson.JsonObject) -> Unit,
    confirmLabel: String,
    knownLabels: List<String> = emptyList(),
): Boolean {
    val kind = contract.kind
    if (kind in setOf("control.repeat", "control.cycle", "control.loopmetric", "control.loopcheck", "task.sleep") ||
        (kind == "control.while" && arguments.get("always")?.asBoolean == true)) {
        val initial = remember(kind, arguments) { loopDraftFromArguments(kind, arguments) }
        VisualLoopDialog(variables, currentFlowId, flows, onDismiss, { hint ->
            loopInsertionFromHint(hint)?.takeIf { it.kind == kind }?.let { onConfirm(it.arguments) }
        }, onManageVariables, initial, kind, confirmLabel)
        return true
    }
    // Calls always use the real typed argument editor, even when their current argument map is empty.
    // A newly selected target may require parameters; the compact target-only form cannot supply them.
    val jump = kind in setOf("control.break", "control.continue", "control.label", "control.goto", "flow.return", "flow.status.return",
        "flow.argument.set", "flow.argument.get", "flow.return.set", "flow.return.get") &&
        (kind != "flow.return" || arguments.size() == 0)
    val debug = kind in setOf("task.prompt", "task.runprompt", "task.log", "task.message", "task.comment")
    if (!jump && !debug) return false
    val accept: (String) -> Unit = { hint ->
        val defaults = arguments.deepCopy()
        if (debug) defaults.remove("valueVariable")
        val updated = legacyDockBlockArguments(contract, hint, defaults)
        onConfirm(updated)
    }
    if (debug) {
        // The owner of the draft must own every close path, including system Back and outside taps.
        DebugPage(onDismiss, accept, knownVariables, debugSettings, { _, complete ->
            complete(Result.failure(IllegalStateException("当前入口不保存项目配置")))
        }, kind, arguments, confirmLabel,
            expressionVariables = calculationVariables(variables, currentFlowId, flows)
                .filter { it.type != ProjectVariableType.IMAGE })
        return true
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BoxWithConstraints(Modifier.fillMaxWidth().imePadding(), contentAlignment = Alignment.Center) {
        Surface(Modifier.width(studioDialogWidth(maxWidth.value).dp).height(minOf(390.dp, maxHeight)),
            color = Color.White, shape = RoundedCornerShape(3.dp), shadowElevation = 9.dp) {
            CommonPage(onDismiss, accept, knownVariables, knownLabels, currentFlowId, flows,
                variables, onManageVariables, kind, arguments, confirmLabel)
        }
        }
    }
    return true
}

/** Only live visual-editor entry points. File resources and screenshots have their own owners. */
@Composable
internal fun EditorEntryDialog(
    entry: EditorToolPanel,
    onDismiss: () -> Unit,
    onOpenImageTools: ((Long) -> Unit)? = null,
    onCreateImageBlock: ((String) -> Unit)? = null,
    onOpenImageLibrary: (() -> Unit)? = null,
    onTestRecognition: (() -> Unit)? = null,
    availableVariables: List<String> = emptyList(),
    availableLabels: List<String> = emptyList(),
    projectVariables: List<ProjectVariable> = emptyList(),
    currentFlowId: String = "",
    projectFlows: List<ProjectFlow> = emptyList(),
    onManageVariables: (() -> Unit)? = null,
    debugSettings: ProjectDebugSettings = ProjectDebugSettings(),
    onSaveDebugSettings: (ProjectDebugSettings, (Result<ProjectDebugSettings>) -> Unit) -> Unit = { _, complete ->
        complete(Result.failure(IllegalStateException("当前没有可保存的项目")))
    },
    onInsert: (String) -> Unit,
) {
    when (entry) {
        EditorToolPanel.LOOP -> VisualLoopDialog(projectVariables, currentFlowId, projectFlows, onDismiss, onInsert, onManageVariables)
        EditorToolPanel.JUDGMENT -> VisualConditionEntryDialog(onDismiss, onInsert, availableVariables,
            projectVariables, currentFlowId, projectFlows, onManageVariables)
        EditorToolPanel.VARIABLE_CHECK, EditorToolPanel.RUNTIME_VARIABLES -> {
            StudioPanelDialog(title = "需要项目上下文", onDismiss = onDismiss, showFooter = false) {
                Text("请从项目的可视化编辑器打开此功能。", fontSize = 12.sp, modifier = Modifier.padding(12.dp))
            }
        }
        EditorToolPanel.IMAGE -> if (onCreateImageBlock != null) {
            VisualImageHomeDialog(onDismiss, onCreateImageBlock,
                onCapture = onOpenImageTools?.let { open -> { open(0L) } },
                onLibrary = onOpenImageLibrary, onTest = onTestRecognition)
        } else {
            StudioPanelDialog("需要项目上下文", onDismiss, showFooter = false) {
                Text("请从项目的可视化编辑器打开图像工具。", fontSize = 12.sp, modifier = Modifier.padding(12.dp))
            }
        }
        EditorToolPanel.DEBUG -> DebugPage(onDismiss, onInsert, availableVariables,
            debugSettings, onSaveDebugSettings,
            expressionVariables = calculationVariables(projectVariables, currentFlowId, projectFlows)
                .filter { it.type != ProjectVariableType.IMAGE })
        EditorToolPanel.AI -> StudioPanelDialog("AI 助手", onDismiss, compactHeader = true) {
            Text("请从项目的可视化编辑器打开 AI 助手。",
                fontSize = 12.sp, color = EntryMuted, modifier = Modifier.padding(12.dp))
        }
        else -> Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            BoxWithConstraints(Modifier.fillMaxWidth().imePadding(), contentAlignment = Alignment.Center) {
            Surface(Modifier.width(studioDialogWidth(maxWidth.value).dp).height(minOf(390.dp, maxHeight)),
                color = Color.White, shape = RoundedCornerShape(2.dp), shadowElevation = 8.dp) {
                when (entry) {
                    EditorToolPanel.COMMON -> CommonPage(onDismiss, onInsert, availableVariables,
                        availableLabels, currentFlowId, projectFlows, projectVariables, onManageVariables)
                    else -> Unit
                }
            }
            }
        }
    }
}

@Composable
private fun DenseLabeledRow(label: String, value: String, onClick: () -> Unit, arrow: Boolean = false) {
    Row(Modifier.fillMaxWidth().height(32.dp), verticalAlignment = Alignment.CenterVertically) {
        DenseLabel(label)
        DenseSpinner(value, Modifier.weight(1f), arrow, onClick)
    }
}

@Composable
private fun DenseLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, color = Color(0xFF20242C), fontSize = 10.sp, modifier = modifier.padding(end = 5.dp))
}

@Composable
private fun DenseSpinner(
    value: String,
    modifier: Modifier = Modifier,
    arrow: Boolean = true,
    onClick: () -> Unit,
    centered: Boolean = false,
) {
    Box(
        modifier = modifier.height(30.dp)
            .background(Color(0xFFFAFBFC), RoundedCornerShape(3.dp))
            .border(1.dp, EntryBorder, RoundedCornerShape(3.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp),
        contentAlignment = if (centered) Alignment.Center else Alignment.CenterStart,
    ) {
        Text(
            text = if (arrow) "$value  ▾" else value,
            color = Color(0xFF20242C),
            fontSize = 10.sp,
            lineHeight = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = if (centered) TextAlign.Center else TextAlign.Start,
        )
    }
}

private fun variablePicker(
    variables: List<String>,
    selected: String?,
    onSelected: (String) -> Unit,
): PickerRequest = PickerRequest("选择变量", variables, selected, isVariable = true, onSelected = onSelected)

@Composable
private fun CommonPage(
    onDismiss: () -> Unit,
    onInsert: (String) -> Unit,
    availableVariables: List<String>,
    availableLabels: List<String>,
    currentFlowId: String,
    projectFlows: List<ProjectFlow>,
    projectVariables: List<ProjectVariable>,
    onManageVariables: (() -> Unit)?,
    initialKind: String? = null,
    initialArguments: com.google.gson.JsonObject? = null,
    confirmLabel: String = "加入",
) {
    val tabs = listOf("流程操作", "调用插件", "设置调用参数", "读取调用参数", "设置返回参数", "读取返回参数")
    val targets = projectFlows.filter { it.flowId != currentFlowId }
    fun text(key: String, fallback: String = "") = initialArguments?.get(key)?.takeIf { it.isJsonPrimitive }?.asString ?: fallback
    val initialTab = when (initialKind) { "flow.call", "flow.call.branch" -> 1; "flow.argument.set" -> 2; "flow.argument.get" -> 3; "flow.return.set" -> 4; "flow.return.get" -> 5; else -> 0 }
    val initialAction = when (initialKind) { "control.label" -> 1; "flow.return" -> 2; "control.goto" -> 3; "task.sleep" -> 4; "flow.status.return" -> if (text("success") == "false") 6 else 5; "control.continue" -> 7; else -> 0 }
    var selected by remember { mutableStateOf(initialTab) }
    var action by remember { mutableStateOf(initialAction) }
    var callBranches by remember { mutableStateOf(initialKind == "flow.call.branch") }
    var waitValue by remember { mutableStateOf(text("millisecondsVariable", text("milliseconds", "500"))) }
    var waitIsVariable by remember { mutableStateOf(initialArguments?.has("millisecondsVariable") == true) }
    val autoLabel = remember(availableLabels) { JumpPanelCode.nextLabel(availableLabels) }
    var labelName by remember(autoLabel) { mutableStateOf(text("name", autoLabel)) }
    var customLabel by remember { mutableStateOf(initialKind == "control.label") }
    var jumpError by remember { mutableStateOf<String?>(null) }
    var parameterIndex by remember { mutableStateOf(text("index", "1")) }
    var parameterValue by remember { mutableStateOf(initialArguments?.get("value")?.toString() ?: "0") }
    var parameterVariable by remember { mutableStateOf(text("valueVariable", text("targetVariable"))) }
    var useParameterVariable by remember { mutableStateOf(initialArguments?.has("valueVariable") == true) }
    var targetFlowId by remember(currentFlowId, projectFlows) { mutableStateOf(text("targetFlowId").ifBlank { targets.firstOrNull()?.flowId }) }
    var picker by remember { mutableStateOf<PickerRequest?>(null) }
    val resolvedVariables = calculationVariables(projectVariables, currentFlowId, projectFlows).associateBy { it.name }
    fun variableType(name: String): ProjectVariableType? = resolvedVariables[name]?.type
    val scalarVariables = availableVariables.filter { variableType(it) != ProjectVariableType.IMAGE }
    val numericVariables = availableVariables.filter {
        variableType(it) == ProjectVariableType.INTEGER
    }
    fun pickVariable(options: List<String>, update: (String) -> Unit) {
        if (options.isEmpty()) jumpError = "没有符合类型的变量，请先维护变量"
        else picker = variablePicker(options, parameterVariable) { update(it); jumpError = null }
    }
    SplitEntryPage("流程 · 插件调用", tabs, selected, { if (initialKind == null || it == initialTab) { selected = it; jumpError = null } else jumpError = "修改时请保留原积木类型" }, onDismiss, {
        val index = parameterIndex.toIntOrNull()
        val snippet = when (selected) {
            0 -> when (action) {
                0 -> "break\n"
                1 -> {
                    val name = if (customLabel) labelName else autoLabel
                    when {
                        !JumpPanelCode.validName(name) -> jumpError = "标记名支持中文、英文字母及下划线开头，后续可含数字，最多64字符"
                        name in availableLabels && name != text("name") -> jumpError = "当前插件已有同名标记"
                    }
                    "::$name::\n"
                }
                2 -> "return\n"
                5 -> JumpPanelCode.statusSnippet(true)
                6 -> JumpPanelCode.statusSnippet(false)
                7 -> "${FunctionCatalog.BLOCK_HINT_PREFIX}control.continue\n{}\n"
                3 -> {
                    if (!JumpPanelCode.validName(labelName)) jumpError = "请输入有效的标记名"
                    else if (labelName !in availableLabels) jumpError = "请先在当前插件放置该标记"
                    "goto $labelName\n"
                }
                else -> {
                    if (waitIsVariable) {
                        if (!JumpPanelCode.validName(waitValue)) jumpError = "请输入有效的毫秒变量名"
                        else if (variableType(waitValue) != ProjectVariableType.INTEGER)
                            jumpError = "等待毫秒请选择当前插件可用的整数变量"
                        "${FunctionCatalog.BLOCK_HINT_PREFIX}task.sleep\nvariable:$waitValue"
                    } else {
                        if (waitValue.toLongOrNull()?.takeIf { it >= 0 } == null) jumpError = "等待时间须为非负整数毫秒"
                        waitValue.toLongOrNull()?.let(JumpPanelCode::waitSnippet)
                    }
                }
            }
            1 -> JumpPanelCode.callSnippet(targetFlowId?.takeIf { id -> targets.any { it.flowId == id } }.orEmpty(), callBranches).also {
                if (it == null) jumpError = "请先创建并选择目标插件"
            }
            2, 4 -> {
                if (index == null || index !in 1..99) jumpError = "参数序号须为 1～99"
                else if (useParameterVariable && !JumpPanelCode.validName(parameterVariable)) jumpError = "请输入有效的来源变量名"
                else if (useParameterVariable && variableType(parameterVariable) == ProjectVariableType.IMAGE)
                    jumpError = "调用和返回参数不能使用图像变量"
                else if (!useParameterVariable && JumpPanelCode.fixedValue(parameterValue) == null) {
                    jumpError = "固定值须为非空标量；数字样式的文字请加引号"
                }
                if (jumpError == null) JumpPanelCode.setSnippet(
                    if (selected == 2) "flow.argument.set" else "flow.return.set",
                    requireNotNull(index), parameterValue, parameterVariable.takeIf { useParameterVariable },
                ) else null
            }
            else -> {
                if (index == null || index !in 1..99) jumpError = "参数序号须为 1～99"
                else if (!JumpPanelCode.validName(parameterVariable)) jumpError = "请输入有效的接收变量名"
                else if (variableType(parameterVariable) == ProjectVariableType.IMAGE)
                    jumpError = "调用和返回参数不能写入图像变量"
                if (jumpError == null) JumpPanelCode.getSnippet(
                    if (selected == 3) "flow.argument.get" else "flow.return.get",
                    requireNotNull(index), parameterVariable,
                ) else null
            }
        }
        if (jumpError == null && snippet != null) onInsert(snippet)
    }, headerHint = "当前插件内操作", confirmLabel = confirmLabel) {
        when (selected) {
            0 -> {
                DenseFlowActions(action) {
                    if (!JumpPanelCode.canSelectAction(initialKind, initialAction, it)) {
                        jumpError = "修改时请保留原积木类型"; return@DenseFlowActions
                    }
                    action = it
                    jumpError = null
                    if (it == 3) labelName = availableLabels.firstOrNull().orEmpty()
                    else if (it == 1 && !customLabel) labelName = autoLabel
                }
                when (action) {
                    0 -> DenseNotice("仅能加入循环体；执行后跳出最近一层循环。", "跳出循环")
                    1 -> {
                        SegmentedChoice("自动命名", "自定义", customLabel) {
                            customLabel = it; jumpError = null
                            if (!it) labelName = autoLabel
                        }
                        if (customLabel) DenseTypedValue("标记", labelName, { labelName = it; jumpError = null }, "")
                        else DenseNotice("将创建 $autoLabel；只在当前插件内有效。")
                    }
                    2 -> DenseNotice("结束当前插件，向调用者返回；此前设置的返回参数会一起传回。", "返回上层")
                    5, 6 -> DenseNotice("结束当前插件，独立返回${if (action == 5) "成功" else "失败"}状态；已设置的返回参数不变。异常、取消不当作失败。", "返回运行状态")
                    7 -> DenseNotice("跳过最近一层循环的剩余积木，开始下一轮；停止和让步仍然生效。", "结束本次循环")
                    3 -> {
                        DenseTypedValue("标记", labelName, { labelName = it; jumpError = null },
                            "选择标记") {
                            if (availableLabels.isEmpty()) jumpError = "当前插件没有标记，请先放置标记"
                            else picker = PickerRequest("选择当前插件标记", availableLabels, labelName) {
                                labelName = it; jumpError = null
                            }
                        }
                        DenseNotice("跳到当前插件根层级的标记；不可跨插件或跳入循环体。")
                    }
                    else -> {
                        SegmentedChoice("固定毫秒", "毫秒变量", waitIsVariable) {
                            waitIsVariable = it; waitValue = if (it) "" else "500"; jumpError = null
                        }
                        DenseTypedValue("毫秒", waitValue, { waitValue = it; jumpError = null },
                            if (waitIsVariable) "选择变量" else "") {
                            if (numericVariables.isEmpty()) jumpError = "没有数值变量，请先维护变量"
                            else picker = variablePicker(numericVariables, waitValue) { waitValue = it; jumpError = null }
                        }
                        if (waitIsVariable && onManageVariables != null) {
                            Text("维护变量", color = EntryAccent, fontSize = 11.sp,
                                modifier = Modifier.clickable { onDismiss(); onManageVariables() }.padding(vertical = 6.dp))
                        }
                    }
                }
            }
            1 -> {
                SegmentedChoice("仅调用", "成功 / 失败分支", callBranches) {
                    if (initialKind == null) callBranches = it
                    else if (it != callBranches) jumpError = "修改时保留原子区；请新建分支调用积木"
                }
                DenseNotice(if (callBranches) "子插件返回成功走成功分支，返回失败走失败分支；正常结束默认成功，异常或取消中止。"
                    else "先设置调用参数，再加入调用；调用后可读取返回参数。", if (callBranches) "分支规则" else "调用顺序")
                val selectedTarget = targets.firstOrNull { it.flowId == targetFlowId }
                DenseLabeledRow("目标插件", selectedTarget?.displayName() ?: "未创建", {
                    if (targets.isEmpty()) jumpError = "请先创建另一个插件"
                    else {
                        val labels = targets.mapIndexed { index, flow -> "${index + 1}. ${flow.displayName()} (${flow.flowId.take(8)})" }
                        picker = PickerRequest("选择目标插件", labels,
                            labels.getOrNull(targets.indexOfFirst { it.flowId == targetFlowId })) { choice ->
                            targetFlowId = targets[labels.indexOf(choice)].flowId; jumpError = null
                        }
                    }
                }, arrow = true)
                if (targets.isEmpty()) DenseNotice("当前只有一个插件；请先在标题的插件管理中创建目标插件。")
            }
            2, 4 -> {
                Text(if (selected == 2) "设置下一次调用的参数" else "设置当前插件的返回参数",
                    color = EntryAccent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                DenseTypedValue("序号", parameterIndex, { parameterIndex = it; jumpError = null }, "")
                SegmentedChoice("固定值", "变量", useParameterVariable) { useParameterVariable = it; jumpError = null }
                if (useParameterVariable) DenseTypedValue("来源", parameterVariable, { parameterVariable = it; jumpError = null }, "选择变量") {
                    pickVariable(scalarVariables) { parameterVariable = it }
                } else {
                    DenseTypedValue("数值", parameterValue, { parameterValue = it; jumpError = null }, "")
                    DenseNotice("数字、true/false 自动识别；普通文字直接输入。数字样式的文字请用双引号包住。")
                }
                if (useParameterVariable && onManageVariables != null) {
                    Text("维护变量", color = EntryAccent, fontSize = 11.sp,
                        modifier = Modifier.clickable { onDismiss(); onManageVariables() }.padding(vertical = 6.dp))
                }
            }
            else -> {
                Text(if (selected == 3) "读取当前插件收到的调用参数" else "读取上一次插件调用的返回参数",
                    color = EntryAccent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                DenseTypedValue("序号", parameterIndex, { parameterIndex = it; jumpError = null }, "")
                DenseTypedValue("接收", parameterVariable, { parameterVariable = it; jumpError = null }, "选择变量") {
                    pickVariable(scalarVariables) { parameterVariable = it }
                }
                if (onManageVariables != null) Text("维护变量", color = EntryAccent, fontSize = 11.sp,
                    modifier = Modifier.clickable { onDismiss(); onManageVariables() }.padding(vertical = 8.dp))
            }
        }
        jumpError?.let { Text(it, color = Color(0xFFD84949), fontSize = 11.sp) }
    }
    picker?.let { request -> PickerDialog(request, resolvedVariables.values.toList()) { picker = null } }
}

@Composable
private fun DenseFlowActions(selected: Int, onSelected: (Int) -> Unit) {
    val actions = listOf("跳出循环", "放置标记", "返回上层", "跳转标记", "等待时间", "返回成功", "返回失败", "结束本次循环")
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        actions.chunked(2).forEachIndexed { rowIndex, rowItems ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                rowItems.forEachIndexed { columnIndex, label ->
                    val index = rowIndex * 2 + columnIndex
                    Box(Modifier.weight(1f).height(34.dp)
                        .background(if (index == selected) Color(0xFFEAF1FF) else Color.White, RoundedCornerShape(4.dp))
                        .border(1.dp, if (index == selected) EntryAccent else EntryBorder, RoundedCornerShape(4.dp))
                        .clickable { onSelected(index) }, contentAlignment = Alignment.Center) {
                        Text(label, fontSize = 11.sp,
                            color = if (index == selected) EntryAccent else Color(0xFF202839),
                            fontWeight = if (index == selected) FontWeight.Bold else FontWeight.Normal,
                            style = CenteredDialogButtonTextStyle)
                    }
                }
                if (rowItems.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun DenseTypedValue(
    type: String,
    value: String,
    onValue: (String) -> Unit,
    action: String,
    onAction: () -> Unit = {},
) {
    Row(
        Modifier.fillMaxWidth().height(36.dp).padding(top = 6.dp)
            .background(Color(0xFFFAFBFC), RoundedCornerShape(3.dp)).border(1.dp, EntryBorder, RoundedCornerShape(3.dp)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(34.dp).fillMaxHeight(), contentAlignment = Alignment.Center) {
            Text(
                type,
                color = EntryAccent,
                fontSize = 10.sp,
                lineHeight = 10.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                style = CenteredDialogButtonTextStyle,
            )
        }
        DividerVertical()
        BasicTextField(value, onValue, singleLine = true, textStyle = TextStyle(fontSize = 11.sp, color = Color(0xFF20242C)), modifier = Modifier.weight(1f).padding(horizontal = 8.dp))
        if (action.isNotEmpty()) {
            DividerVertical()
            Box(
                Modifier.width(76.dp).fillMaxHeight().clickable(onClick = onAction),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    action,
                    fontSize = 9.sp,
                    lineHeight = 9.sp,
                    textAlign = TextAlign.Center,
                    style = CenteredDialogButtonTextStyle,
                )
            }
        }
    }
}

@Composable
private fun DenseNotice(text: String, title: String? = null) {
    Column(Modifier.fillMaxWidth().padding(top = 6.dp).background(Color(0xFFFAFBFC), RoundedCornerShape(3.dp)).border(1.dp, EntryBorder, RoundedCornerShape(3.dp)).padding(horizontal = 8.dp, vertical = 7.dp)) {
        title?.let { Text(it, fontSize = 10.sp, fontWeight = FontWeight.Bold) }
        Text(text, color = EntryMuted, fontSize = 10.sp, modifier = Modifier.padding(top = if (title == null) 0.dp else 4.dp))
    }
}

/** Shared debug panel: insert a typed output block or edit settings that really affect execution. */
@Composable
private fun DebugPage(
    onDismiss: () -> Unit,
    onInsert: (String) -> Unit,
    availableVariables: List<String>,
    initialSettings: ProjectDebugSettings,
    onSaveSettings: (ProjectDebugSettings, (Result<ProjectDebugSettings>) -> Unit) -> Unit,
    initialKind: String? = null,
    initialArguments: com.google.gson.JsonObject? = null,
    confirmLabel: String = "加入",
    expressionVariables: List<CalculationVariable>,
) {
    val currentExpressionVariables by rememberUpdatedState(expressionVariables)
    val currentAvailableVariables by rememberUpdatedState(availableVariables)
    val pages = if (initialKind != null) listOf(0) else listOf(0, 1, 2)
    val tabs = pages.map { when (it) { 1 -> "对话框"; 2 -> "弹窗样式"; else -> "输出内容" } }
    var selected by remember { mutableStateOf(0) }
    val selectedPage = pages[selected]
    var fullScreen by remember { mutableStateOf(false) }
    val initialOutput = remember { DebugOutputDraft.from(initialKind, initialArguments) }
    val initialOutputMode = initialOutput.mode
    var outputMode by remember { mutableStateOf(initialOutputMode) }
    var outputField by remember { mutableStateOf(TextFieldValue(initialOutput.text, TextRange(initialOutput.text.length))) }
    val outputText = outputField.text
    var expressionMode by remember { mutableStateOf(initialOutput.expression) }
    var outputVariable by remember { mutableStateOf(initialOutput.variable) }
    var logLevel by remember { mutableStateOf(initialOutput.logLevel) }
    var outputError by remember { mutableStateOf<String?>(null) }
    val formScope = rememberCoroutineScope()
    var popupSave by remember { mutableStateOf(PopupStyleSaveState(PopupStyleDraft.from(initialSettings.popupStyle))) }
    LaunchedEffect(initialSettings.popupStyle) { popupSave = popupSave.observe(initialSettings.popupStyle) }
    val popupDraft = popupSave.draft
    var variablePicker by remember { mutableStateOf<PickerRequest?>(null) }
    var confirmDiscard by remember { mutableStateOf(false) }
    var pendingDialogKind by remember { mutableStateOf<String?>(null) }
    val settingsDirty = popupSave.dirty
    val outputDirty = DebugOutputDraft(outputMode, outputText, outputVariable, expressionMode, logLevel)
        .needsDiscard(initialOutput, editingExisting = initialKind != null)
    fun editExpression(edit: (TextFieldValue) -> TextFieldValue?) {
        val updated = edit(outputField)
        if (updated == null) outputError = "无法插入：请勿截断变量名或转义字符；转文字和运算符需放在引号外，表达式最多1024字节。原内容已保留。"
        else { outputField = updated; outputError = null }
    }
    fun pickVariable(labeled: Boolean, direct: Boolean = false) {
        val choices = if (expressionMode && !direct) expressionVariables.map { it.name } else availableVariables
        if (choices.isEmpty()) {
            outputError = if (expressionMode && !direct) "没有可用的数值或文字变量，请先在“变量”中创建。图像变量不能直接打印。"
                else "当前没有可用变量，请先创建变量"
            return
        }
        val field = outputField
        val expression = expressionMode
        variablePicker = PickerRequest(
            title = if (expression && !direct && labeled) "插入名称=值" else "选择变量",
            options = choices,
            selected = outputVariable,
            isVariable = true,
        ) { name ->
            if (formScope.isActive && expressionMode == expression && outputField.text == field.text && outputField.selection == field.selection) {
                if (expression && !direct) {
                    val chosen = expressionVariables.singleOrNull { it.name == name }
                    if (chosen == null || currentExpressionVariables.singleOrNull { it.name == name } != chosen)
                        outputError = "变量范围或类型已变化，请重新选择。原内容已保留。"
                    else editExpression { DebugOutputExpression.variable(it, chosen, labeled) }
                }
                else if (name !in currentAvailableVariables) outputError = "变量范围已变化，请重新选择。原内容已保留。"
                else { outputVariable = name; if (direct) expressionMode = false; outputError = null }
            }
        }
    }
    fun requestClose() {
        if (popupSave.pending != null) return
        pendingDialogKind = null
        if (settingsDirty || outputDirty) confirmDiscard = true else onDismiss()
    }
    fun chooseDialog(kind: String) {
        if (popupSave.pending != null) return
        val hint = scriptDialogInsertionHint(kind) ?: return
        if (settingsDirty || outputDirty) {
            pendingDialogKind = kind
            confirmDiscard = true
        } else onInsert(hint)
    }
    val page: @Composable () -> Unit = {
    Column(Modifier.fillMaxWidth().then(if (fullScreen) Modifier.fillMaxHeight() else Modifier.heightIn(max = 460.dp))) {
        if (tabs.size > 1) DebugNavigation(tabs, selected) { selected = it; outputError = null }
        if (selectedPage == 1) Text(when (selectedPage) {
            0 -> "加入输出积木，不修改项目配置"
            1 -> "选择对话框功能 · 配置后加入，不在这里执行"
            else -> "项目配置 · 保存后用于后续运行"
        },
            color = EntryMuted, fontSize = 10.sp, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
        Box(Modifier.weight(1f, fill = fullScreen || selectedPage != 0).fillMaxWidth()
            .then(if (fullScreen || selectedPage != 0) Modifier.fillMaxHeight() else Modifier).padding(8.dp)) {
                when (selectedPage) {
                    0 -> DebugOutputPanel(
                        outputMode = outputMode,
                        onOutputModeChange = {
                            if (initialKind == null || it == initialOutputMode || initialKind == "task.message" && it != DebugOutputCode.COMMENT) {
                                outputMode = it; outputError = null
                                if (it == DebugOutputCode.COMMENT) expressionMode = false
                            }
                            else outputError = "修改时请保留输出类型；其他类型请从输出入口新增"
                        },
                        outputField = outputField,
                        expressionMode = expressionMode,
                        expressionAvailable = initialKind == null,
                        onExpressionModeChange = { enabled ->
                            if (initialKind == null) { expressionMode = enabled; outputVariable = null; outputError = null }
                        },
                        onOutputTextChange = { outputField = it; outputError = null },
                        onExpressionEdit = ::editExpression,
                        selectedVariable = outputVariable,
                        logLevel = logLevel,
                        onLogLevelChange = { logLevel = it; outputError = null },
                        error = outputError,
                        onClearVariable = { outputVariable = null; outputError = null },
                        onPickVariable = { pickVariable(it) },
                        onPickDirectVariable = { pickVariable(false, direct = true) },
                    )
                    1 -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        SCRIPT_DIALOG_KINDS.forEach { kind ->
                            BlockCatalog.find(kind)?.let { contract ->
                                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                    .border(1.dp, EntryBorder, RoundedCornerShape(4.dp))
                                    .clickable(onClickLabel = "选择${contract.title}") { chooseDialog(kind) }
                                    .padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(contract.title, fontSize = 12.sp, color = EntryAccent, fontWeight = FontWeight.Bold)
                                        Text(contract.summary, fontSize = 10.sp, color = EntryMuted,
                                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    }
                                    Text(if (contract.properties.isEmpty()) "加入" else "配置", fontSize = 11.sp,
                                        color = EntryAccent, modifier = Modifier.padding(start = 8.dp))
                                }
                            }
                        }
                        Text("确认框返回用户选择；布局框使用已保存页面。关闭对话框不会停止脚本。",
                            fontSize = 10.sp, color = EntryMuted)
                    }
                    2 -> DebugPopupStyleEditor(popupDraft, initialSettings.popupStyle) {
                        popupSave = popupSave.edit(it)
                    }
                    else -> Unit
                }
        }
        if (selectedPage == 2) {
            popupSave.error?.let {
                Text(it, color = AutoScriptPalette.Danger, fontSize = 10.sp, modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp))
            }
            popupSave.message?.let {
                Text(it, color = AutoScriptPalette.Accent, fontSize = 10.sp, modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp))
            }
        }
        if (selectedPage == 1) {
            FooterButtons(listOf("关闭" to ::requestClose))
        } else {
            FooterButtons(
            listOf(
                "关闭" to ::requestClose,
                (if (selectedPage == 0) confirmLabel else if (popupSave.pending != null) "保存中…" else "保存配置") to {
                    if (selectedPage != 0) {
                        if (popupSave.pending == null) {
                            popupSave = popupSave.submit(initialSettings)
                            popupSave.pending?.let { request ->
                                onSaveSettings(request.settings) { result ->
                                    if (formScope.isActive) popupSave = popupSave.complete(request, result)
                                }
                            }
                        }
                    } else {
                        outputError = if (expressionMode) DebugOutputCode.expressionError(outputMode, outputText, true)
                            else DebugOutputCode.error(outputMode, outputText, outputVariable, logLevel)
                        if (outputError == null) {
                            if (expressionMode) DebugOutputCode.expressionSnippet(outputMode, outputText, true)?.let(onInsert)
                            else DebugOutputCode.snippet(outputMode, outputText, outputVariable, true, logLevel)?.let(onInsert)
                        }
                    }
                },
            ),
            )
        }
    }
    }
    StudioPanelDialog("输出",
        ::requestClose, showFooter = false, fullScreen = fullScreen, landscapeWidthDp = 600,
        onToggleFullScreen = { fullScreen = !fullScreen }, content = page)
    if (confirmDiscard) StudioConfirmDialog(
        onDismissRequest = { confirmDiscard = false; pendingDialogKind = null }, title = { Text(when {
            settingsDirty && outputDirty -> "放弃未保存内容？"
            settingsDirty -> "放弃未保存的样式？"
            initialKind != null -> "放弃输出修改？"
            else -> "放弃待加入的输出？"
        }) },
        text = { Text(when {
            settingsDirty && outputDirty -> "尚未加入的输出及未保存的弹窗样式将被丢弃，现有积木和项目配置不会改变。"
            settingsDirty -> "本次弹窗样式修改尚未保存，关闭将丢弃修改。"
            initialKind != null -> "输出修改尚未确认，关闭会丢弃本次修改，原积木不变。"
            else -> "输出内容尚未加入积木；关闭会丢弃该内容，已保存的弹窗样式不受影响。"
        }) },
        confirmButton = { androidx.compose.material3.TextButton(onClick = {
            val hint = pendingDialogKind?.let(::scriptDialogInsertionHint)
            confirmDiscard = false; pendingDialogKind = null
            if (hint == null) onDismiss() else onInsert(hint)
        }) { Text(if (pendingDialogKind == null) "放弃并关闭" else "放弃并继续") } },
        dismissButton = { androidx.compose.material3.TextButton(onClick = {
            if (!settingsDirty && outputDirty) selected = 0
            confirmDiscard = false; pendingDialogKind = null
        }) { Text(if (!settingsDirty && outputDirty) "查看输出内容" else "继续编辑") } },
    )
    variablePicker?.let { request -> PickerDialog(request, expressionVariables) { variablePicker = null } }
}

@Composable
private fun DebugNavigation(tabs: List<String>, selected: Int, onSelected: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().height(studioCompactControlHeight(32, LocalDensity.current.fontScale).dp).background(EntryPanel)) {
        tabs.forEachIndexed { index, label ->
            Box(
                Modifier.weight(1f).fillMaxHeight().clickable { onSelected(index) },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier.align(Alignment.BottomCenter).height(2.dp).fillMaxWidth()
                        .background(if (selected == index) EntryAccent else Color.Transparent),
                )
                Text(
                    label,
                    color = if (selected == index) EntryAccent else Color(0xFF282E38),
                    fontSize = 12.sp,
                    fontWeight = if (selected == index) FontWeight.Bold else FontWeight.Normal,
                    style = CenteredDialogButtonTextStyle,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun SplitEntryPage(title: String, tabs: List<String>, selected: Int, onSelected: (Int) -> Unit, onDismiss: () -> Unit, onAdd: () -> Unit, headerHint: String? = null, showAdd: Boolean = true, confirmLabel: String = "加入", content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().height(40.dp).padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = EntryBlue, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            headerHint?.let { Text(it, color = EntryMuted, fontSize = 10.sp) }
        }
        DividerLine()
        Row(Modifier.weight(1f)) {
            Column(Modifier.width(96.dp).fillMaxHeight().background(EntryPanel).verticalScroll(rememberScrollState())) {
                tabs.forEachIndexed { index, tab ->
                    Box(Modifier.fillMaxWidth().height(38.dp).clickable { onSelected(index) }) {
                        Box(Modifier.align(Alignment.CenterStart).width(1.5.dp).fillMaxHeight()
                            .background(if (selected == index) EntryAccent else Color.Transparent))
                        Text(
                            tab,
                            fontSize = 11.sp,
                            fontWeight = if (selected == index) FontWeight.Bold else FontWeight.Normal,
                            color = if (selected == index) EntryAccent else Color(0xFF283140),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxSize().wrapContentSize(Alignment.Center),
                        )
                    }
                }
            }
            DividerVertical()
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)) { content() }
        }
        FooterButtons(if (showAdd) listOf("取消" to onDismiss, confirmLabel to onAdd) else listOf("关闭" to onDismiss))
    }
}


@Composable
private fun PickerDialog(request: PickerRequest, variables: List<CalculationVariable>, onDismiss: () -> Unit) {
    CompactVariablePickerDialog(request.title,
        if (request.isVariable) variablePickerOptions(request.options, variables) else request.options.map { VariablePickerOption(it) }, request.selected,
        onDismiss, { value -> request.onSelected(value); onDismiss() })
}


@Composable private fun EntryField(label: String, value: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(38.dp).border(1.dp, EntryBorder, RoundedCornerShape(2.dp)).clickable(onClick = onClick).padding(horizontal = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 11.sp, color = EntryMuted, modifier = Modifier.weight(1f)); Text(value, fontSize = 11.sp, color = EntryAccent)
    }
}



@Composable
private fun SegmentedChoice(
    left: String,
    right: String,
    selectedRight: Boolean,
    onSelect: (Boolean) -> Unit,
) {
    Row(Modifier.fillMaxWidth().height(30.dp).padding(horizontal = 12.dp).background(Color(0xFFEEF1F7), RoundedCornerShape(15.dp))) {
        listOf(false to left, true to right).forEach { (rightSide, label) ->
            Box(
                Modifier.weight(1f).fillMaxHeight()
                    .background(if (selectedRight == rightSide) EntryAccent else Color.Transparent, RoundedCornerShape(15.dp))
                    .clickable { onSelect(rightSide) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    color = if (selectedRight == rightSide) Color.White else EntryMuted,
                    fontSize = 12.sp,
                    lineHeight = 12.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    style = CenteredDialogButtonTextStyle,
                )
            }
        }
    }
}


@Composable private fun HeaderAction(label: String, onClick: () -> Unit) {
    Box(Modifier.width(46.dp).height(30.dp).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Text(label, fontSize = 10.sp, textAlign = TextAlign.Center,
            style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false)))
    }
}
@Composable private fun FooterButtons(buttons: List<Pair<String, () -> Unit>>, fontSize: Int = 12) {
    DividerLine()
    Row(Modifier.fillMaxWidth().height(40.dp)) {
        buttons.forEachIndexed { index, (label, action) ->
            if (index > 0) DividerVertical()
            CenteredFooterAction(
                label = label,
                color = if (index == buttons.lastIndex) EntryAccent else Color(0xFF202839),
                onClick = action,
                modifier = Modifier.weight(1f),
                fontSize = fontSize,
                bold = index == buttons.lastIndex,
            )
        }
    }
}

/** Shared dialog-action base: center by layout, never by a font-specific top padding. */
@Composable
private fun CenteredFooterAction(
    label: String,
    color: Color,
    onClick: () -> Unit,
    modifier: Modifier,
    fontSize: Int = 12,
    bold: Boolean = false,
) {
    Box(
        modifier = modifier.fillMaxHeight().clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = color,
            fontSize = fontSize.sp,
            lineHeight = fontSize.sp,
            style = CenteredDialogButtonTextStyle,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
            textAlign = TextAlign.Center,
        )
    }
}

private val CenteredDialogButtonTextStyle = TextStyle(
    platformStyle = PlatformTextStyle(includeFontPadding = false),
)

// 文件弹窗（service_tk_xt_wj_gl_layout）的分割线是 1.0dp 的 hs，不是 px 发线。
@Composable private fun DividerLine() = Box(Modifier.fillMaxWidth().height(1.dp).background(AutoScriptPalette.Divider))
@Composable private fun DividerVertical() = Box(Modifier.width(1.dp).fillMaxHeight().background(AutoScriptPalette.Divider))

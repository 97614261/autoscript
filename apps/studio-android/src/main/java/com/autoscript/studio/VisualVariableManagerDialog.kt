package com.autoscript.studio

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.autoscript.core.designsystem.AutoScriptPalette
import com.autoscript.project.store.ProjectFlow
import com.autoscript.project.store.ProjectVariable
import com.autoscript.project.store.ProjectVariableScope
import com.autoscript.project.store.ProjectVariableType
import com.google.gson.JsonObject

/** Filtering declarations must not leak locals from another plugin or hide shadowed declarations. */
internal fun variableManagerEntries(
    variables: List<ProjectVariable>,
    scope: ProjectVariableScope,
    currentFlowId: String,
    query: String = "",
): List<ProjectVariable> = variables.filter {
    it.scope == scope && (scope == ProjectVariableScope.GLOBAL || it.flowId == currentFlowId) &&
        it.name.contains(query.trim(), ignoreCase = true)
}.sortedWith(compareBy<ProjectVariable> { it.name.lowercase(java.util.Locale.ROOT) }.thenBy { it.name })

/** Sized for actual content, capped at six rows rather than filling the screen. */
internal fun variableManagerPanelHeightDp(entryCount: Int, availableHeightDp: Float, hasNotice: Boolean = false): Float {
    val listHeight = if (entryCount <= 0) 96f else minOf(entryCount, 6) * 41f
    val desiredHeight = 182f + listHeight + if (hasNotice) 40f else 0f
    return minOf(availableHeightDp.coerceAtLeast(0f), desiredHeight)
}

internal fun variableDeclarationError(
    replacement: ProjectVariable,
    variables: List<ProjectVariable>,
    original: ProjectVariable?,
): String? = when {
    !replacement.name.matches(Regex("[A-Za-z_][A-Za-z0-9_]{0,63}")) ->
        "以字母或下划线开头，仅支持字母、数字和下划线，最长64字符"
    replacement.scope == ProjectVariableScope.FLOW && replacement.flowId.isNullOrBlank() -> "请先选择当前插件"
    variables.any { it != original && it.scope == replacement.scope && it.flowId == replacement.flowId && it.name == replacement.name } ->
        "同一作用域已存在变量：${replacement.name}"
    else -> null
}

internal fun variableCalculationUnavailableReason(
    variable: ProjectVariable,
    variables: List<ProjectVariable>,
    currentFlowId: String,
    flows: List<ProjectFlow>,
): String? = when {
    variable.type == ProjectVariableType.IMAGE -> "图像由截图/识别积木赋值，不参与标量计算"
    flows.firstOrNull { it.flowId == currentFlowId }?.params.orEmpty().any { it["name"]?.asString == variable.name } ->
        "同名插件参数优先；请从函数库的计算面板选择参数"
    variable.scope == ProjectVariableScope.GLOBAL && variables.any {
        it.scope == ProjectVariableScope.FLOW && it.flowId == currentFlowId && it.name == variable.name
    } -> "同名局部变量优先；当前插件不能直接计算这个全局变量"
    else -> null
}

private val VariablePanel = Color(0xFFF5F7FB)
private val VariableInk = AutoScriptPalette.TextPrimary
private val VariableMuted = AutoScriptPalette.TextSecondary
private val VariableBlue = AutoScriptPalette.Accent
private val VariableDanger = Color(0xFFD14949)
private data class VariableEditRequest(val scope: ProjectVariableScope, val original: ProjectVariable? = null)

/** Shared by the page and floating visual editor. Maintenance does not execute an assignment. */
@Composable
internal fun VisualVariableManagerDialog(
    variables: List<ProjectVariable>,
    currentFlowId: String,
    flows: List<ProjectFlow>,
    onDismiss: () -> Unit,
    onSave: (List<ProjectVariable>) -> Unit,
    allowFlowScope: Boolean = true,
    onSaveAndInsert: ((List<ProjectVariable>, JsonObject) -> Unit)? = null,
) {
    var draft by remember(variables) { mutableStateOf(variables) }
    var scope by remember(currentFlowId, allowFlowScope) {
        mutableStateOf(if (allowFlowScope) ProjectVariableScope.FLOW else ProjectVariableScope.GLOBAL)
    }
    var query by remember { mutableStateOf("") }
    var editRequest by remember { mutableStateOf<VariableEditRequest?>(null) }
    var calculationArguments by remember { mutableStateOf<JsonObject?>(null) }
    var deleteVariable by remember { mutableStateOf<ProjectVariable?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var confirmDiscard by remember { mutableStateOf(false) }
    val dirty = draft != variables
    val flowName = flows.firstOrNull { it.flowId == currentFlowId }?.displayName() ?: currentFlowId
    val scoped = variableManagerEntries(draft, scope, currentFlowId)
    val entries = variableManagerEntries(draft, scope, currentFlowId, query)
    val listState = rememberLazyListState()
    LaunchedEffect(scope, query) { listState.scrollToItem(0) }
    fun dismiss() { if (dirty) confirmDiscard = true else onDismiss() }

    Dialog(onDismissRequest = ::dismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BoxWithConstraints(Modifier.fillMaxWidth().padding(12.dp).imePadding()) {
            // Keep a bounded list without stretching a small declaration set into a tall window.
            val panelHeight = variableManagerPanelHeightDp(entries.size, maxHeight.value, notice != null).dp
            Surface(
                Modifier.widthIn(max = 520.dp).fillMaxWidth().height(panelHeight).align(Alignment.Center),
                color = Color.White, shape = RoundedCornerShape(6.dp), shadowElevation = 10.dp,
            ) {
                Column {
                    Row(Modifier.fillMaxWidth().height(40.dp).padding(start = 12.dp, end = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("变量管理", color = VariableBlue, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        Text(if (dirty) "未保存" else "声明", color = if (dirty) Color(0xFF976919) else VariableMuted,
                            fontSize = 10.sp, modifier = Modifier.weight(1f).padding(start = 8.dp))
                        VariableAction("＋新增", { editRequest = VariableEditRequest(scope) }, Modifier.width(62.dp).fillMaxHeight())
                        VariableAction("×", ::dismiss, Modifier.width(36.dp).fillMaxHeight(), color = VariableMuted)
                    }
                    HorizontalDivider(color = AutoScriptPalette.Divider)
                    Row(Modifier.fillMaxWidth().height(36.dp).padding(horizontal = 8.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        val scopes = if (allowFlowScope) listOf(ProjectVariableScope.FLOW, ProjectVariableScope.GLOBAL) else listOf(ProjectVariableScope.GLOBAL)
                        scopes.forEach { candidate ->
                            val count = variableManagerEntries(draft, candidate, currentFlowId).size
                            VariableAction(variableScopeLabel(candidate) + " " + count, { scope = candidate; notice = null },
                                Modifier.width(94.dp).fillMaxHeight(), selected = scope == candidate)
                        }
                        Text(if (scope == ProjectVariableScope.FLOW) flowName else "项目共享", color = VariableMuted, fontSize = 10.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(start = 4.dp))
                    }
                    Row(Modifier.fillMaxWidth().height(40.dp).padding(horizontal = 8.dp, vertical = 3.dp)) {
                        VariableInput(query, { query = it.take(64) }, "搜索变量名", Modifier.fillMaxWidth(), height = 34.dp, trailing = {
                            if (query.isNotEmpty()) VariableAction("×", { query = "" }, Modifier.size(30.dp), color = VariableMuted)
                        })
                    }
                    Row(Modifier.fillMaxWidth().height(24.dp).background(VariablePanel).padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text("类型", color = VariableMuted, fontSize = 10.sp, modifier = Modifier.width(32.dp))
                        Text("变量名", color = VariableMuted, fontSize = 10.sp, modifier = Modifier.weight(1f))
                        Text(if (query.isBlank()) entries.size.toString() + "个" else entries.size.toString() + "/" + scoped.size + "个",
                            color = VariableMuted, fontSize = 10.sp)
                        Text("编辑 / 计算 / 删除", color = VariableMuted, fontSize = 10.sp,
                            modifier = Modifier.width(120.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    }
                    notice?.let {
                        Text(it, color = VariableDanger, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp))
                    }
                    if (entries.isEmpty()) {
                        Column(Modifier.weight(1f).fillMaxWidth().padding(16.dp),
                            verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(if (scoped.isEmpty()) "还没有" + variableScopeLabel(scope) else "没有匹配的变量",
                                color = VariableMuted, fontSize = 13.sp)
                            VariableAction(if (scoped.isEmpty()) "＋新增变量" else "清除搜索", {
                                if (scoped.isEmpty()) editRequest = VariableEditRequest(scope) else query = ""
                            }, Modifier.height(36.dp).padding(top = 4.dp))
                        }
                    } else {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                        ) {
                            items(entries, key = { it.scope.toString() + ":" + it.flowId + ":" + it.name }) { variable ->
                                VariableDeclarationRow(variable,
                                    onEdit = { editRequest = VariableEditRequest(scope, variable) },
                                    onDelete = { deleteVariable = variable },
                                    onCalculate = onSaveAndInsert?.let {
                                        {
                                            val reason = variableCalculationUnavailableReason(variable, draft, currentFlowId, flows)
                                            if (reason != null) notice = reason
                                            else {
                                                notice = null
                                                calculationArguments = JsonObject().apply {
                                                    addProperty("name", variable.name)
                                                    addProperty("expression", if (variable.type == ProjectVariableType.STRING) "\"\"" else "0")
                                                }
                                            }
                                        }
                                    },
                                )
                                HorizontalDivider(color = AutoScriptPalette.Divider.copy(alpha = .6f))
                            }
                        }
                    }
                    HorizontalDivider(color = AutoScriptPalette.Divider)
                    Row(Modifier.fillMaxWidth().height(40.dp)) {
                        VariableAction("取消", ::dismiss, Modifier.weight(1f).fillMaxHeight(), color = VariableInk)
                        Box(Modifier.width(1.dp).fillMaxHeight().background(AutoScriptPalette.Divider))
                        VariableAction("保存", { onSave(draft) }, Modifier.weight(1f).fillMaxHeight())
                    }
                }
            }
        }
    }
    editRequest?.let { request ->
        VariableDeclarationEditor(request, flowName, onDismiss = { editRequest = null }) { name, type ->
            val replacement = ProjectVariable(name.trim(), request.scope, if (request.scope == ProjectVariableScope.FLOW) currentFlowId else null, type)
            variableDeclarationError(replacement, draft, request.original)?.let { return@VariableDeclarationEditor it }
            draft = request.original?.let { original -> draft.map { if (it == original) replacement else it } } ?: draft + replacement
            query = ""
            notice = null
            editRequest = null
            null
        }
    }
    calculationArguments?.let { arguments ->
        VisualVariableCalculationDialog(arguments, draft, currentFlowId, flows,
            onDismiss = { calculationArguments = null },
            onConfirm = { configured -> calculationArguments = null; onSaveAndInsert?.invoke(draft, configured); null })
    }
    deleteVariable?.let { variable ->
        EditorPromptDialog("删除变量 ${variable.name}", "仅删除声明，不删除已有积木。仍有引用时，需修正并通过编译检查。",
            onDismiss = { deleteVariable = null }, onConfirm = { draft = draft - variable; deleteVariable = null },
            confirmLabel = "删除", confirmDanger = true)
    }
    if (confirmDiscard) EditorPromptDialog("放弃变量更改？", "新增、编辑和删除尚未保存；返回继续编辑，或放弃本次草稿。",
        onDismiss = { confirmDiscard = false }, onConfirm = { confirmDiscard = false; onDismiss() }, confirmLabel = "放弃更改", confirmDanger = true)
}

@Composable
private fun VariableDeclarationRow(
    variable: ProjectVariable,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onCalculate: (() -> Unit)?,
) {
    val color = variableTypeColor(variable.type)
    Row(Modifier.fillMaxWidth().heightIn(min = 40.dp).clickable(onClick = onEdit).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(32.dp), contentAlignment = Alignment.CenterStart) {
            Box(Modifier.size(22.dp).background(color.copy(alpha = .09f), RoundedCornerShape(3.dp)), contentAlignment = Alignment.Center) {
                Text(calculationTypeLabel(variable.type), color = color, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                    style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false)))
            }
        }
        Text(variable.name, color = VariableInk, fontFamily = FontFamily.Monospace, fontSize = 13.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false)))
        VariableIconAction(R.drawable.editor_edit_24, "编辑变量 " + variable.name, onEdit, VariableBlue)
        if (onCalculate != null && variable.type != ProjectVariableType.IMAGE) {
            VariableAction("计算", onCalculate, Modifier.width(48.dp).height(40.dp))
        } else {
            Box(Modifier.width(48.dp).height(40.dp), contentAlignment = Alignment.Center) {
                Text("—", color = VariableMuted.copy(alpha = .5f), fontSize = 11.sp)
            }
        }
        VariableIconAction(R.drawable.ic_delete_outline_24, "删除变量 " + variable.name, onDelete, VariableDanger)
    }
}

@Composable
private fun VariableIconAction(icon: Int, description: String, onClick: () -> Unit, color: Color) {
    Box(Modifier.width(36.dp).height(40.dp).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(painterResource(icon), description, tint = color, modifier = Modifier.size(17.dp))
    }
}

@Composable
private fun VariableDeclarationEditor(
    request: VariableEditRequest,
    flowName: String,
    onDismiss: () -> Unit,
    onConfirm: (String, ProjectVariableType) -> String?,
) {
    var name by remember(request) { mutableStateOf(request.original?.name.orEmpty()) }
    var type by remember(request) { mutableStateOf(request.original?.type ?: ProjectVariableType.INTEGER) }
    var error by remember { mutableStateOf<String?>(null) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BoxWithConstraints(Modifier.fillMaxWidth().padding(16.dp).imePadding()) {
            Surface(Modifier.widthIn(max = 460.dp).fillMaxWidth().heightIn(max = maxHeight).align(Alignment.Center), color = Color.White, shape = RoundedCornerShape(6.dp), shadowElevation = 10.dp) {
                Column {
                    Text(if (request.original == null) "新增${variableScopeLabel(request.scope)}" else "编辑变量", color = VariableBlue, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(14.dp))
                    HorizontalDivider(color = AutoScriptPalette.Divider)
                    Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                        Text(if (request.scope == ProjectVariableScope.FLOW) "作用域 · $flowName" else "作用域 · 当前项目全部插件", color = VariableMuted, fontSize = 11.sp)
                        Text("变量名称", color = VariableInk, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                        VariableInput(name, { name = it.take(64); error = null }, "例如 count、total、frame", Modifier.fillMaxWidth(), monospace = true)
                        Text("变量类型", color = VariableInk, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                        ProjectVariableType.entries.chunked(2).forEach { row ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                                row.forEach { candidate ->
                                    val selected = type == candidate
                                    Row(Modifier.weight(1f).height(40.dp).background(if (selected) VariableBlue.copy(alpha = .07f) else VariablePanel, RoundedCornerShape(5.dp))
                                        .border(1.dp, if (selected) VariableBlue else AutoScriptPalette.Divider, RoundedCornerShape(5.dp))
                                        .clickable { type = candidate; error = null }.padding(horizontal = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Text(calculationTypeLabel(candidate), color = variableTypeColor(candidate), fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                        Text(variableTypeLabel(candidate), color = if (selected) VariableBlue else VariableInk, fontSize = 12.sp, modifier = Modifier.weight(1f).padding(start = 8.dp))
                                        if (selected) Text("✓", color = VariableBlue, fontSize = 13.sp)
                                    }
                                }
                            }
                        }
                        Text(when (type) {
                            ProjectVariableType.INTEGER -> "默认0 · 用于次数、坐标和整数计算"
                            ProjectVariableType.NUMBER -> "默认0 · 用于小数和数值计算"
                            ProjectVariableType.STRING -> "默认空字符串 · 用于文本、提示和拼接"
                            ProjectVariableType.IMAGE -> "默认空图像 · 由截图/识别积木赋值，不参与算术"
                        }, color = VariableMuted, fontSize = 11.sp, modifier = Modifier.fillMaxWidth().background(VariablePanel, RoundedCornerShape(4.dp)).padding(9.dp))
                        if (request.original != null) Text("改名或修改类型不会自动改写已有积木引用，保存后需编译检查。", color = Color(0xFF976919), fontSize = 11.sp)
                        error?.let { Text(it, color = VariableDanger, fontSize = 11.sp) }
                    }
                    HorizontalDivider(color = AutoScriptPalette.Divider)
                    Row(Modifier.fillMaxWidth().height(44.dp)) {
                        VariableAction("取消", onDismiss, Modifier.weight(1f).fillMaxHeight(), color = VariableInk)
                        Box(Modifier.width(1.dp).fillMaxHeight().background(AutoScriptPalette.Divider))
                        VariableAction(if (request.original == null) "添加" else "保存修改", { error = onConfirm(name, type) }, Modifier.weight(1f).fillMaxHeight())
                    }
                }
            }
        }
    }
}

@Composable
private fun VariableInput(
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier,
    monospace: Boolean = false,
    height: androidx.compose.ui.unit.Dp = 36.dp,
    trailing: @Composable () -> Unit = {},
) {
    Row(modifier.height(height).background(VariablePanel, RoundedCornerShape(5.dp)).border(1.dp, AutoScriptPalette.Divider, RoundedCornerShape(5.dp)).padding(start = 10.dp, end = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        BasicTextField(value, onChange, singleLine = true, cursorBrush = SolidColor(VariableBlue),
            textStyle = TextStyle(color = VariableInk, fontSize = 13.sp, fontFamily = if (monospace) FontFamily.Monospace else FontFamily.Default, platformStyle = PlatformTextStyle(includeFontPadding = false)),
            modifier = Modifier.weight(1f), decorationBox = { field ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) Text(placeholder, color = VariableMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    field()
                }
            })
        trailing()
    }
}

@Composable
private fun VariableAction(label: String, onClick: () -> Unit, modifier: Modifier, color: Color = VariableBlue, selected: Boolean = false, outlined: Boolean = false) {
    val shape = RoundedCornerShape(4.dp)
    Box(modifier.background(if (selected) Color.White else Color.Transparent, shape)
        .then(if (outlined || selected) Modifier.border(1.dp, if (selected) VariableBlue.copy(alpha = .30f) else AutoScriptPalette.Divider, shape) else Modifier)
        .clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Text(label, color = color, fontSize = 12.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis,
            style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false)))
    }
}

private fun variableScopeLabel(scope: ProjectVariableScope) = if (scope == ProjectVariableScope.FLOW) "局部变量" else "全局变量"
private fun variableTypeLabel(type: ProjectVariableType) = when (type) {
    ProjectVariableType.INTEGER -> "整数"
    ProjectVariableType.NUMBER -> "浮点"
    ProjectVariableType.STRING -> "字符"
    ProjectVariableType.IMAGE -> "图像"
}
private fun variableTypeColor(type: ProjectVariableType) = when (type) {
    ProjectVariableType.INTEGER -> VariableBlue
    ProjectVariableType.NUMBER -> Color(0xFF8654B8)
    ProjectVariableType.STRING -> Color(0xFF218578)
    ProjectVariableType.IMAGE -> Color(0xFFAE7825)
}

package com.autoscript.studio

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autoscript.core.designsystem.AutoScriptPalette
import com.autoscript.project.store.ProjectVariableType

internal data class VariablePickerOption(val value: String, val label: String = value,
    val scope: String? = null, val type: ProjectVariableType? = null, val enabled: Boolean = true)

internal val LocalVariablePickerMetadata = staticCompositionLocalOf<List<CalculationVariable>> { emptyList() }

/** Metadata only annotates the caller's eligible names; it never widens type or scope access. */
internal fun variablePickerOptions(names: List<String>, variables: List<CalculationVariable> = emptyList()): List<VariablePickerOption> {
    val metadata = variables.associateBy { it.name }
    return names.distinct().map { name -> VariablePickerOption(name, scope = metadata[name]?.scope, type = metadata[name]?.type) }
}

internal fun filteredVariablePickerOptions(options: List<VariablePickerOption>, scope: String, query: String): List<VariablePickerOption> =
    options.filter { (it.scope == null || scope.isEmpty() || it.scope == scope) &&
        (query.isBlank() || it.label.contains(query.trim(), true) || it.value.contains(query.trim(), true)) }

@Composable
internal fun CompactVariableFilters(scope: String, scopes: List<Pair<String, String>>, query: String,
    onScope: (String) -> Unit, onQuery: (String) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp)) {
        val search: @Composable (Modifier) -> Unit = { modifier ->
            ImageToolCompactInput("搜索", query, true, modifier, dense = true) { onQuery(it.take(128)) }
        }
        if (scopes.isEmpty()) search(Modifier.fillMaxWidth())
        else if (maxWidth.value >= 250f * LocalDensity.current.fontScale) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                StudioToolSegments(scope, scopes, Modifier.weight(1f), onScope)
                search(Modifier.weight(1.2f))
            }
        } else Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            StudioToolSegments(scope, scopes, onChange = onScope)
            search(Modifier.fillMaxWidth())
        }
    }
}

@Composable
internal fun CompactVariablePickerDialog(title: String = "选择变量", options: List<VariablePickerOption>, selected: String?,
    onDismiss: () -> Unit, onSelect: (String) -> Unit, onManage: (() -> Unit)? = null,
    initialScope: String? = null, allowNewValue: Boolean = false) {
    val scopes = listOf("局部", "全局", "参数").filter { group -> options.any { it.scope == group } }
    var scope by remember { mutableStateOf(initialScope?.takeIf { it in scopes }
        ?: options.firstOrNull { it.value == selected }?.scope?.takeIf { it in scopes }
        ?: listOf("局部", "参数", "全局").firstOrNull { it in scopes }.orEmpty()) }
    var query by remember { mutableStateOf("") }
    val visible = filteredVariablePickerOptions(options, scope, query)
    val list = rememberLazyListState()
    LaunchedEffect(scope, query) { list.scrollToItem(0) }
    LaunchedEffect(scopes) { if (scope !in scopes) scope = scopes.firstOrNull().orEmpty() }
    fun select(option: VariablePickerOption) {
        if (options.any { it == option && it.enabled }) onSelect(option.value)
    }
    StudioPanelDialog(title, onDismiss, showFooter = false, landscapeWidthDp = 280) {
        Column(Modifier.fillMaxWidth().heightIn(max = 220.dp)) {
            CompactVariableFilters(scope, scopes.map { it to it }, query, { scope = it }, { query = it })
            LazyColumn(Modifier.weight(1f, fill = false).fillMaxWidth().padding(horizontal = 6.dp), state = list) {
                if (allowNewValue && query.matches(com.autoscript.project.store.ProjectNames.pattern) && options.none { it.value == query }) item {
                    CompactVariablePickerRow(VariablePickerOption(query, "使用变量：$query"), false) { onSelect(query) }
                }
                if (visible.isEmpty()) item {
                    Text(if (onManage == null) "无匹配变量" else "无匹配变量，可点管理添加。", fontSize = 10.sp,
                        lineHeight = 14.sp, color = AutoScriptPalette.TextSecondary, modifier = Modifier.padding(6.dp))
                }
                itemsIndexed(visible) { _, option ->
                    CompactVariablePickerRow(option, option.value == selected) { select(option) }
                    HorizontalDivider(color = AutoScriptPalette.Divider)
                }
            }
            HorizontalDivider(color = AutoScriptPalette.Divider)
            Row(Modifier.fillMaxWidth()) {
                onManage?.let { CompactVariableAction("管理变量", it, Modifier.weight(1f)) }
                CompactVariableAction("取消", onDismiss, Modifier.weight(1f), muted = true)
            }
        }
    }
}

@Composable
private fun CompactVariablePickerRow(option: VariablePickerOption, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 30.dp)
        .background(if (selected) AutoScriptPalette.AccentSoft else Color.Transparent, RoundedCornerShape(4.dp))
        .clickable(enabled = option.enabled, onClick = onClick).padding(horizontal = 4.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(option.label, Modifier.weight(1f), fontSize = 11.sp, lineHeight = 14.sp,
            maxLines = 2, overflow = TextOverflow.Ellipsis,
            color = if (option.enabled) AutoScriptPalette.TextPrimary else AutoScriptPalette.TextSecondary)
        option.type?.let { Text(calculationTypeLabel(it), fontSize = 10.sp, lineHeight = 14.sp, color = AutoScriptPalette.TextSecondary) }
        if (selected) Text("✓", color = AutoScriptPalette.Accent, fontSize = 11.sp)
    }
}

@Composable
internal fun CompactVariableAction(label: String, onClick: () -> Unit, modifier: Modifier = Modifier,
    muted: Boolean = false, enabled: Boolean = true) {
    Box(modifier.heightIn(min = 30.dp).clickable(enabled = enabled, onClick = onClick).padding(horizontal = 6.dp, vertical = 5.dp),
        contentAlignment = Alignment.Center) {
        Text(label, fontSize = 11.sp, lineHeight = 14.sp,
            color = if (muted || !enabled) AutoScriptPalette.TextSecondary else AutoScriptPalette.Accent)
    }
}

@Composable
internal fun VariableChoiceAnchor(title: String, value: String, choices: List<VariablePickerOption>, onChange: (String) -> Unit,
    anchor: @Composable (() -> Unit) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    anchor { focus.clearFocus(); open = true }
    if (open) CompactVariablePickerDialog(title, choices, value, { open = false }, { onChange(it); open = false })
}

@Composable
internal fun CompactVariableChoice(label: String, value: String, options: List<Pair<String, String>>, enabled: Boolean,
    variables: List<CalculationVariable> = emptyList(), onChange: (String) -> Unit) {
    val metadata = variables.associateBy { it.name }
    VariableChoiceAnchor(label, value, options.map { (key, text) ->
        VariablePickerOption(key, text, metadata[key]?.scope, metadata[key]?.type, enabled)
    }, onChange) { open ->
        Row(Modifier.fillMaxWidth().heightIn(min = 30.dp)
            .background(AutoScriptPalette.VisualEditor.ControlBackground, RoundedCornerShape(5.dp))
            .clickable(enabled = enabled, onClick = open).padding(horizontal = 8.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(label, Modifier.widthIn(max = 90.dp), fontSize = 10.sp, lineHeight = 14.sp, color = AutoScriptPalette.TextSecondary,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(options.firstOrNull { it.first == value }?.second ?: value.ifEmpty { "请选择" }, Modifier.weight(1f),
                fontSize = 11.sp, lineHeight = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, color = AutoScriptPalette.TextPrimary)
            Text("▾", fontSize = 11.sp, color = AutoScriptPalette.Accent)
        }
    }
}

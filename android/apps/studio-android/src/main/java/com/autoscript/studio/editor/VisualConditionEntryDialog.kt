package com.autoscript.studio

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autoscript.core.designsystem.AutoScriptPalette
import com.autoscript.project.store.ProjectFlow
import com.autoscript.project.store.ProjectVariable

private enum class ConditionEntry(val title: String, val detail: String) {
    IF("如果", "填写条件后选择加入位置"),
    ELSE_IF("否则如果", "填写条件后加入选中的判断"),
    ELSE("否则", "进入选中条件的否则分支"),
}

@Composable
internal fun VisualConditionEntryDialog(
    onDismiss: () -> Unit,
    onInsert: (String) -> Unit,
    knownVariables: List<String> = emptyList(),
    projectVariables: List<ProjectVariable> = emptyList(),
    currentFlowId: String = "",
    flows: List<ProjectFlow> = emptyList(),
    onManageVariables: (() -> Unit)? = null,
) {
    var selected by remember { mutableStateOf(ConditionEntry.IF) }
    var configuring by remember { mutableStateOf<ConditionEntry?>(null) }
    configuring?.let { entry ->
        val contract = remember { requireNotNull(BlockCatalog.find("control.if")) }
        val initial = remember(entry) {
            conditionPropertyTexts(contract, requireNotNull(initialBlockArguments(contract, flows, emptyList(), currentFlowId)))
        }
        VisualVariableConditionDialog(contract, initial, knownVariables, projectVariables, currentFlowId,
            flows, onManageVariables, onDismiss = { configuring = null }, onConfirm = { values ->
                val parsed = parseBlockArguments(contract, values, null, emptyMap(), emptyList())
                val arguments = parsed.first
                when {
                    arguments == null -> parsed.second ?: "请填写完整条件"
                    !validConfiguredConditionArguments(arguments) -> "请填写有效的比较变量和条件"
                    else -> {
                        onInsert(configuredConditionHint(arguments, entry == ConditionEntry.ELSE_IF))
                        null
                    }
                }
            }, confirmLabel = if (entry == ConditionEntry.IF) "选择位置" else "加入分支",
            titleOverride = if (entry == ConditionEntry.IF) "如果" else "否则如果")
        return
    }
    StudioPanelDialog("条件", onDismiss, showFooter = false, landscapeWidthDp = 320) {
        Column(Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())
                .padding(8.dp).selectableGroup(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                ConditionEntry.entries.forEach { entry ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 34.dp)
                        .selectable(entry == selected, role = Role.RadioButton) { selected = entry }
                        .padding(horizontal = 3.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box(Modifier.size(12.dp).border(1.dp,
                            if (entry == selected) AutoScriptPalette.Accent else AutoScriptPalette.TextSecondary.copy(alpha = .6f),
                            RoundedCornerShape(50)), contentAlignment = Alignment.Center) {
                            if (entry == selected) Box(Modifier.size(6.dp).background(AutoScriptPalette.Accent, RoundedCornerShape(50)))
                        }
                        Text(entry.title, Modifier.width(66.dp), fontSize = 11.sp, lineHeight = 14.sp)
                        Text(entry.detail, Modifier.weight(1f), fontSize = 10.sp, lineHeight = 14.sp, color = AutoScriptPalette.TextSecondary)
                    }
                }
            }
            HorizontalDivider(color = AutoScriptPalette.Divider)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("取消", Modifier.weight(1f).heightIn(min = 36.dp).clickable(onClick = onDismiss)
                    .padding(8.dp), textAlign = TextAlign.Center, fontSize = 12.sp, color = AutoScriptPalette.TextSecondary)
                Box(Modifier.width(1.dp).height(24.dp).background(AutoScriptPalette.Divider))
                Text(if (selected == ConditionEntry.ELSE) "选择分支积木" else "下一步",
                    Modifier.weight(1f).heightIn(min = 36.dp).clickable {
                        if (selected == ConditionEntry.ELSE) {
                            onInsert("--@autoscript-control:else\n")
                            onDismiss()
                        } else configuring = selected
                    }.padding(8.dp), textAlign = TextAlign.Center, fontSize = 12.sp, color = AutoScriptPalette.Accent)
            }
        }
    }
}

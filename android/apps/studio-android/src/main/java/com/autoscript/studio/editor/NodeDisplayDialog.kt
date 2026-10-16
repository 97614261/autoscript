package com.autoscript.studio

import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autoscript.core.designsystem.AutoScriptPalette
import com.autoscript.studio.compiler.NodeDisplaySettings

internal data class NodeDisplayEditTarget(val nodeId: String, val source: String,
    val initial: NodeDisplaySettings, val defaultLabel: String)

@Composable
internal fun NodeDisplayDialog(target: NodeDisplayEditTarget, enabled: Boolean,
    onDismiss: () -> Unit, onApply: (NodeDisplaySettings) -> String?) {
    var alias by remember(target) { mutableStateOf(target.initial.alias) }
    var showParameters by remember(target) { mutableStateOf(target.initial.showParameters) }
    var message by remember(target) { mutableStateOf<String?>(null) }
    var discard by remember { mutableStateOf(false) }
    val settings = NodeDisplaySettings(alias, showParameters)
    fun dismiss() { if (settings != target.initial) discard = true else onDismiss() }
    StudioPanelDialog("积木显示", ::dismiss, showFooter = false) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("显示别名", fontSize = 12.sp)
            Surface(shape = RoundedCornerShape(8.dp), color = AutoScriptPalette.AccentSoft) {
                BasicTextField(alias, { alias = it; message = null }, singleLine = true,
                    textStyle = TextStyle(fontSize = 13.sp), modifier = Modifier.fillMaxWidth().heightIn(min = 40.dp),
                    decorationBox = { inner -> Box(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), contentAlignment = Alignment.CenterStart) {
                        if (alias.isEmpty()) Text("留空恢复默认名称", color = AutoScriptPalette.TextSecondary, fontSize = 12.sp)
                        inner()
                    } })
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(true to "别名＋参数", false to "只显示别名").forEach { (value, title) ->
                    Surface(onClick = { showParameters = value }, modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(8.dp), color = if (showParameters == value) AutoScriptPalette.AccentSoft else AutoScriptPalette.PageBackground) {
                        Box(Modifier.heightIn(min = 40.dp), contentAlignment = Alignment.Center) {
                            Text(title, fontSize = 12.sp, color = if (showParameters == value) AutoScriptPalette.Accent else AutoScriptPalette.TextSecondary)
                        }
                    }
                }
            }
            Text("预览", fontSize = 11.sp, color = AutoScriptPalette.TextSecondary)
            Text(applyProgramDisplayLabel(target.defaultLabel, settings), fontSize = 12.sp, maxLines = 3,
                modifier = Modifier.fillMaxWidth().background(AutoScriptPalette.PageBackground, RoundedCornerShape(8.dp)).padding(10.dp))
            Text("仅改变列表显示，不改变量名、执行参数或提示窗内容。最多80字；清空别名恢复默认摘要。", fontSize = 10.sp, color = AutoScriptPalette.TextSecondary)
            if (!settings.isValid()) Text("别名最多80字，不允许换行或控制字符", fontSize = 11.sp, color = AutoScriptPalette.Danger)
            message?.let { Text(it, fontSize = 11.sp, color = AutoScriptPalette.Danger) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { alias = ""; showParameters = true }) { Text("恢复默认", fontSize = 12.sp) }
                TextButton(onClick = ::dismiss) { Text("取消", fontSize = 12.sp) }
                TextButton(enabled = enabled && settings.isValid(), onClick = {
                    val problem = onApply(settings)
                    if (problem == null) onDismiss() else message = problem
                }) { Text("应用", fontSize = 12.sp) }
            }
        }
    }
    if (discard) EditorPromptDialog("放弃显示修改？", "尚未应用的别名和显示方式将丢弃。",
        onDismiss = { discard = false }, onConfirm = { discard = false; onDismiss() }, confirmLabel = "放弃")
}

package com.autoscript.studio

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autoscript.core.designsystem.AutoScriptPalette
import com.autoscript.project.store.ProjectDebugSettings

/** Only log options are editable here; merge into the latest project settings on explicit save. */
internal data class RuntimeLogSettingsDraft(val traceExecution: Boolean, val recordVisionMetrics: Boolean) {
    fun applyTo(settings: ProjectDebugSettings): ProjectDebugSettings =
        settings.copy(traceExecution = traceExecution, recordVisionMetrics = recordVisionMetrics)

    companion object {
        fun from(settings: ProjectDebugSettings): RuntimeLogSettingsDraft =
            RuntimeLogSettingsDraft(settings.traceExecution, settings.recordVisionMetrics)
    }
}

@Composable
internal fun RuntimeLogSettingsDialog(settings: ProjectDebugSettings, status: String? = null,
    onSave: (ProjectDebugSettings) -> Unit, onDismiss: () -> Unit, enabled: Boolean = true) {
    val initial = remember { RuntimeLogSettingsDraft.from(settings) }
    var draft by remember { mutableStateOf(initial) }
    var confirmDiscard by remember { mutableStateOf(false) }
    var submitted by remember { mutableStateOf<RuntimeLogSettingsDraft?>(null) }
    var saveMessage by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(settings, submitted) {
        if (submitted != null && submitted == RuntimeLogSettingsDraft.from(settings)) {
            saveMessage = if (draft == submitted) "已保存记录设置" else "上次提交已保存，当前修改尚未保存"
            submitted = null
        }
    }
    fun requestClose() {
        if (draft != RuntimeLogSettingsDraft.from(settings)) confirmDiscard = true else onDismiss()
    }
    StudioPanelDialog("日志记录设置", ::requestClose, showFooter = false, compactHeader = true) {
        Column(Modifier.fillMaxWidth().heightIn(max = 340.dp)) {
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())
                .padding(horizontal = 10.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                LogSettingsText("只影响后续运行 · 不改变已有日志", muted = true)
                status?.let { LogSettingsText(it) }
                saveMessage?.let { LogSettingsText(it, muted = true) }
                if (!enabled) LogSettingsText("运行或操作进行中，暂不可修改。草稿保留。", muted = true)
                Column(Modifier.fillMaxWidth().border(1.dp, AutoScriptPalette.Divider, RoundedCornerShape(5.dp))) {
                    LogSettingsToggle("逐积木执行记录", "在运行日志显示进入的插件和积木；循环会产生较多记录，不记录源码或变量值。",
                        draft.traceExecution, enabled) { draft = draft.copy(traceExecution = it); saveMessage = null }
                    HorizontalDivider(color = AutoScriptPalette.Divider)
                    LogSettingsToggle("识图耗时统计", "记录截图往返、匹配耗时及取消、超时等结果，用于排查性能。",
                        draft.recordVisionMetrics, enabled) { draft = draft.copy(recordVisionMetrics = it); saveMessage = null }
                }
                Column(Modifier.fillMaxWidth().background(AutoScriptPalette.PageBackground, RoundedCornerShape(5.dp))
                    .padding(horizontal = 8.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        LogSettingsText("开始、结束 / 脚本输出 / 错误", Modifier.weight(1f))
                        LogSettingsText("自动记录", muted = true)
                    }
                    LogSettingsText("保存在本机私有目录；历史可查看、导出和清理。导出可能包含脚本主动输出的私密内容。",
                        muted = true)
                }
                LogSettingsText("运行提示和弹出提示独立配置，不受这两个开关影响。", muted = true)
            }
            HorizontalDivider(color = AutoScriptPalette.Divider)
            Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                LogSettingsAction("取消", ::requestClose, Modifier.weight(1f))
                LogSettingsAction("保存设置", {
                    submitted = draft; saveMessage = "已提交保存请求，未生效可重试；失败时草稿保留。"
                    onSave(draft.applyTo(settings))
                }, Modifier.weight(1f), primary = true,
                    enabled = enabled && draft != RuntimeLogSettingsDraft.from(settings))
            }
        }
    }
    if (confirmDiscard) StudioConfirmDialog(
        onDismissRequest = { confirmDiscard = false },
        title = { Text("放弃记录设置？") },
        text = { Text("未保存的开关修改将被丢弃，不清理已有日志；已经提交的保存请求不会被撤销。") },
        dismissButton = { LogSettingsAction("继续编辑", { confirmDiscard = false }) },
        confirmButton = { LogSettingsAction("放弃并关闭", onDismiss, primary = true) },
    )
}

@Composable
private fun LogSettingsToggle(title: String, description: String, checked: Boolean, enabled: Boolean, onChecked: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().toggleable(value = checked, role = Role.Switch, enabled = enabled, onValueChange = onChecked)
        .padding(horizontal = 8.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            LogSettingsText(title, bold = true)
            LogSettingsText(description, muted = true)
        }
        Box(Modifier.size(30.dp, 18.dp)
            .background(if (checked) AutoScriptPalette.Accent else Color(0xFFD1D5DB), RoundedCornerShape(9.dp)).padding(2.dp),
            contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart) {
            Box(Modifier.size(14.dp).background(Color.White, RoundedCornerShape(7.dp)))
        }
    }
}

@Composable
private fun LogSettingsText(text: String, modifier: Modifier = Modifier, muted: Boolean = false, bold: Boolean = false) {
    Text(text, modifier, color = if (muted) AutoScriptPalette.TextSecondary else AutoScriptPalette.TextPrimary,
        fontSize = if (muted) 10.sp else 11.sp, fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal,
        style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false)))
}

@Composable
private fun LogSettingsAction(title: String, onClick: () -> Unit, modifier: Modifier = Modifier,
    primary: Boolean = false, enabled: Boolean = true) {
    val shape = RoundedCornerShape(4.dp)
    Box(modifier.background(if (primary && enabled) AutoScriptPalette.Accent else AutoScriptPalette.AccentSoft, shape)
        .clickable(enabled = enabled, onClick = onClick).heightIn(min = 30.dp).padding(horizontal = 8.dp, vertical = 5.dp),
        contentAlignment = Alignment.Center) {
        Text(title, fontSize = 11.sp, textAlign = TextAlign.Center,
            color = if (!enabled) AutoScriptPalette.TextSecondary else if (primary) Color.White else AutoScriptPalette.Accent,
            style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false)))
    }
}

package com.autoscript.studio

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autoscript.project.store.ProjectSummary

@Composable
internal fun HistoricalProjectsDialog(
    projects: List<ProjectSummary>,
    enabled: Boolean,
    message: String?,
    onDismiss: () -> Unit,
    onExport: (ProjectSummary) -> Unit,
) {
    StudioPanelDialog("旧源码项目 · 备份导出", onDismiss) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("原文件和资源仍在本地保留。现在统一使用可视化项目，插件内可编辑受限 Lua 源码。任意历史 Lua 不能自动无损转积木，请先导出完整备份。", fontSize = 12.sp)
            message?.let { Text(it, fontSize = 12.sp) }
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 280.dp)) {
                items(projects, key = ProjectSummary::projectId) { project ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(project.name, fontSize = 13.sp, modifier = Modifier.weight(1f))
                        TextButton(enabled = enabled, onClick = { onExport(project) }) { Text("导出备份") }
                    }
                }
            }
        }
    }
}

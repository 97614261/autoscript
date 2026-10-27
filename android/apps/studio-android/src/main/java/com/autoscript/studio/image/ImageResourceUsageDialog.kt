package com.autoscript.studio

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autoscript.core.designsystem.AutoScriptPalette
import com.autoscript.project.store.ProjectResourceUsage
import com.autoscript.project.store.ProjectResourceUsageReport
import com.autoscript.project.store.ResourceUsageSource

@Composable
internal fun ImageResourceUsageDialog(report: ProjectResourceUsageReport, deletionRefused: Boolean, onDismiss: () -> Unit) {
    var fullScreen by remember(report) { mutableStateOf(false) }
    // Deletion feedback shares the prompt layer, above the library and other ordinary editor windows.
    CompositionLocalProvider(LocalStudioPrompt provides true) {
        StudioPanelDialog("图片使用位置", onDismiss, fullScreen = fullScreen, compactHeader = true,
            onToggleFullScreen = { fullScreen = !fullScreen }) {
            Column(Modifier.fillMaxWidth()) {
                Text(when {
                    deletionRefused -> "无法删除：仍有 ${report.totalCount} 处引用，图片已保留。"
                    report.totalCount == 0 -> "未发现使用位置，可以返回图片库删除。"
                    else -> "共 ${report.totalCount} 处引用"
                }, color = if (deletionRefused) AutoScriptPalette.Danger else AutoScriptPalette.TextPrimary,
                    fontSize = 12.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
                LazyColumn(Modifier.weight(1f, fill = false).fillMaxWidth(), contentPadding = PaddingValues(12.dp, 4.dp)) {
                    item {
                        SelectionContainer { Text(report.path, fontSize = 11.sp, color = AutoScriptPalette.TextSecondary) }
                        Text("已保存引用和修改过的草稿分别列出。移除引用后需保存插件、界面或方案，再尝试删除。",
                            fontSize = 10.sp, color = AutoScriptPalette.TextSecondary, modifier = Modifier.padding(vertical = 6.dp))
                    }
                    itemsIndexed(report.usages) { index, usage ->
                        HorizontalDivider(color = AutoScriptPalette.Divider)
                        SelectionContainer {
                            Column(Modifier.fillMaxWidth().padding(vertical = 7.dp)) {
                                Text("${index + 1}. ${usageTitle(usage)}", fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium, color = AutoScriptPalette.TextPrimary)
                                usage.line?.let { line ->
                                    val block = usage.blockKind?.let { BlockCatalog.find(it)?.title ?: it }
                                    Text("源码第 $line 行${block?.let { " · $it" }.orEmpty()}", fontSize = 11.sp,
                                        color = AutoScriptPalette.Accent)
                                    usage.nodeId?.let { Text("积木：$it", fontSize = 10.sp, color = AutoScriptPalette.TextSecondary) }
                                }
                                usage.parameter?.let {
                                    Text("${if (usage.viaDirectory) "文件夹引用" else "参数"}：$it", fontSize = 10.sp,
                                        color = AutoScriptPalette.TextSecondary)
                                }
                                if (usage.source == ResourceUsageSource.LUA) Text("历史源码文本匹配（可能包含注释）",
                                    fontSize = 10.sp, color = AutoScriptPalette.TextSecondary)
                            }
                        }
                    }
                    if (report.truncated) item {
                        Text("共 ${report.totalCount} 处，仅展示前 ${report.usages.size} 处；未展示的引用也会阻止删除。",
                            fontSize = 11.sp, color = AutoScriptPalette.Danger)
                    }
                }
            }
        }
    }
}

private fun usageTitle(usage: ProjectResourceUsage): String = when (usage.source) {
    ResourceUsageSource.SAVED_FLOW -> "已保存插件 · ${usage.location.removePrefix("visual/flows/").removeSuffix(".jsonl")}"
    ResourceUsageSource.DRAFT_FLOW -> "未保存草稿 · ${usage.location.removePrefix("visual/flows/").removeSuffix(".jsonl")}"
    ResourceUsageSource.INTERFACE -> "脚本界面 · ${usage.location}"
    ResourceUsageSource.IMAGE_RECIPE -> "图像方案 · ${usage.location}"
    ResourceUsageSource.LUA -> "历史源码 · ${usage.location}"
}

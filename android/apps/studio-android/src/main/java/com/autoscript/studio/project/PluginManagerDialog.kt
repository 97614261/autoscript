package com.autoscript.studio

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autoscript.core.designsystem.AutoScriptPalette

/** UI commands only; these identities are not persisted in project data. */
internal enum class PluginManagerAction(
    val label: String,
    val description: String,
    @DrawableRes val icon: Int,
) {
    CREATE("插件创建", "创建独立流程", R.drawable.studio_add_24),
    DELETE("插件删除", "选择后确认删除", R.drawable.editor_delete_24),
    SAVE_AS("插件另存", "选择插件后另存", R.drawable.editor_file_copy_24),
    CHECK("插件检错", "使用项目编译校验", R.drawable.editor_more_variable_check_24),
    CHECK_ALL("全部插件检错", "全项目与引用检查", R.drawable.editor_more_variable_check_24),
    GROUP("插件分组", "管理分组与归属", R.drawable.editor_folder_24),
    TEMPLATE("存储为模板", "后续开放", R.drawable.editor_code_file_24),
    UNUSED("未调用插件", "查看未引用的插件", R.drawable.editor_more_search_24),
}

internal fun pluginManagerActionEnabled(action: PluginManagerAction, enabled: Boolean): Boolean =
    enabled && action != PluginManagerAction.TEMPLATE

/** Compact action launcher. Destructive writes remain in the file manager's confirmation flow. */
@Composable
internal fun PluginManagerDialog(
    enabled: Boolean,
    currentPluginName: String,
    pluginCount: Int,
    onDismiss: () -> Unit,
    onAction: (PluginManagerAction) -> Unit,
) {
    var showHelp by rememberSaveable { mutableStateOf(false) }
    var submitted by remember { mutableStateOf(false) }
    StudioPanelDialog(
        title = "插件管理",
        onDismiss = onDismiss,
        showFooter = false,
        compactHeader = true,
        headerActions = {
            Box(
                Modifier.size(28.dp).clickable(role = Role.Button) { showHelp = !showHelp },
                contentAlignment = Alignment.Center,
            ) {
                Icon(painterResource(R.drawable.ic_help_outline_24), "插件管理说明",
                    tint = AutoScriptPalette.Accent, modifier = Modifier.size(16.dp))
            }
        },
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth().background(Color(0xFFF5F7FB))) {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
            ) {
                Row(Modifier.fillMaxWidth().heightIn(min = 26.dp).padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("当前 · ${currentPluginName.ifBlank { "未选择插件" }}", fontSize = 11.sp,
                        color = AutoScriptPalette.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f))
                    Text("共 $pluginCount 个", fontSize = 10.sp, color = AutoScriptPalette.TextSecondary)
                }
                if (showHelp) {
                    Text("插件是独立的流程文件。创建、另存、分组及删除进入插件列表继续操作；两个检错入口沿用项目级 Rust 编译校验，不执行脚本。未调用统计包含任务、定时器和界面引用，仅供参考，不会自动删除。模板库后续开放。",
                        fontSize = 11.sp, lineHeight = 16.sp, color = AutoScriptPalette.TextSecondary,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
                }
                if (!enabled) {
                    Text("当前不可管理插件，请结束运行或等待操作完成。", fontSize = 11.sp,
                        color = AutoScriptPalette.TextSecondary, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
                }
                StudioCompactGrid(PluginManagerAction.entries, cellDp = 170, maximum = 2, modifier = Modifier.padding(6.dp)) { action, cell ->
                    BoxWithConstraints(cell) {
                        val showDescriptions = maxWidth >= 300.dp && LocalDensity.current.fontScale <= 1.3f
                        PluginManagerRow(action, pluginManagerActionEnabled(action, enabled && !submitted), showDescriptions) {
                            if (!submitted && pluginManagerActionEnabled(action, enabled)) {
                                submitted = true
                                onAction(action)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PluginManagerRow(action: PluginManagerAction, enabled: Boolean, showDescription: Boolean, onClick: () -> Unit) {
    val tint = when {
        !enabled -> AutoScriptPalette.TextSecondary
        action == PluginManagerAction.DELETE -> Color(0xFFC95763)
        else -> AutoScriptPalette.Accent
    }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 32.dp).background(Color.White)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(painterResource(action.icon), contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
        Text(action.label, fontSize = 12.sp, lineHeight = 16.sp, modifier = Modifier.weight(1f),
            color = if (enabled) AutoScriptPalette.TextPrimary else AutoScriptPalette.TextSecondary)
        if (showDescription || action == PluginManagerAction.TEMPLATE) {
            Text(action.description, fontSize = 10.sp, lineHeight = 13.sp, color = AutoScriptPalette.TextSecondary)
        }
    }
}

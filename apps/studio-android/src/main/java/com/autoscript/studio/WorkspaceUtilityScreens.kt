package com.autoscript.studio

import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autoscript.core.designsystem.AutoScriptPalette
import com.autoscript.project.store.BackupProjectSummary
import com.autoscript.project.store.ProjectSummary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * `activity_backup_management.xml` + `item_backup_project.xml`：58dp 标题栏、白色提示条、12dp 列表；
 * 卡片 82dp（34dp 图标框、14sp 名称、两行 9sp 元信息、右侧 30dp 危险描边“删除”）。
 * 参考列的是云端备份，本地版列有槽位备份的项目；“导入”是本地版必要的额外入口。
 */
@Composable
internal fun BackupManagementScreen(
    backups: List<BackupProjectSummary>,
    busy: Boolean,
    message: String?,
    onBack: () -> Unit,
    onImport: () -> Unit,
    onOpen: (BackupProjectSummary) -> Unit,
    onDelete: (BackupProjectSummary) -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onBack)
    var deleteTarget by remember { mutableStateOf<BackupProjectSummary?>(null) }
    val timestamp = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }
    Column(modifier.fillMaxSize().background(AutoScriptPalette.PageBackground)) {
        UtilityLightHeader("备份管理", "可用 3 槽位/项目", onBack) {
            Text(
                "导入",
                color = AutoScriptPalette.Accent,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 10.dp).clickable(enabled = !busy, onClick = onImport).padding(horizontal = 6.dp, vertical = 6.dp),
            )
        }
        Text(
            "点击已有备份项目进入槽位管理；新备份请从工作台项目卡片进入",
            modifier = Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 16.dp, vertical = 10.dp),
            fontSize = 11.sp,
            color = AutoScriptPalette.TextSecondary,
        )
        message?.let {
            Text(
                it,
                color = if (it.startsWith("已")) AutoScriptPalette.Accent else AutoScriptPalette.Danger,
                fontSize = 11.sp,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            )
        }
        if (backups.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(36.dp), contentAlignment = Alignment.Center) {
                Text(
                    "暂无本地备份\n请在工作台项目卡片中点击备份",
                    fontSize = 12.sp,
                    color = AutoScriptPalette.TextSecondary,
                    textAlign = TextAlign.Center,
                    lineHeight = 18.sp,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(backups, key = BackupProjectSummary::projectId) { backup ->
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        color = Color.White,
                        onClick = { onOpen(backup) },
                        enabled = !busy,
                    ) {
                        Row(
                            Modifier.fillMaxWidth().height(82.dp).padding(horizontal = 13.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                Modifier.size(34.dp).background(AutoScriptPalette.AccentSoft, RoundedCornerShape(17.dp)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(painterResource(R.drawable.ic_cloud_outline_24), contentDescription = null, tint = AutoScriptPalette.Accent, modifier = Modifier.size(20.dp))
                            }
                            Column(Modifier.weight(1f).padding(start = 10.dp, end = 6.dp)) {
                                Text(
                                    backup.projectName,
                                    color = AutoScriptPalette.TextPrimary,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    "备份时间 ${backup.latestBackupAt?.let { timestamp.format(Date(it)) } ?: "未记录"} · ${formatBackupSize(backup.totalBytes)}",
                                    color = AutoScriptPalette.TextSecondary,
                                    fontSize = 9.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(top = 5.dp),
                                )
                                Text(
                                    if (backup.localProjectExists) {
                                        "本机项目存在 · 已用 ${backup.slots.count { it.occupied }}/3 槽位"
                                    } else {
                                        "本机暂无对应项目，点击可选择槽位恢复"
                                    },
                                    color = AutoScriptPalette.TextSecondary,
                                    fontSize = 9.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(top = 4.dp),
                                )
                            }
                            Text(
                                "删除",
                                color = AutoScriptPalette.Danger,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center,
                                modifier = Modifier
                                    .widthIn(min = 48.dp)
                                    .height(30.dp)
                                    .background(Color(0xFFFFF1F2), RoundedCornerShape(8.dp))
                                    .clickable(enabled = !busy) { deleteTarget = backup }
                                    .padding(horizontal = 8.dp, vertical = 8.dp),
                            )
                        }
                    }
                }
            }
        }
    }
    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            containerColor = Color.White,
            title = { Text("删除备份") },
            text = { Text("确定删除“${target.projectName}”的全部本地备份槽位？本机项目不受影响。") },
            confirmButton = {
                TextButton(onClick = { deleteTarget = null; onDelete(target) }) { Text("删除", color = AutoScriptPalette.Danger) }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("取消") } },
        )
    }
}

/** `activity_learning_projects.xml`：56dp 蓝色标题栏、2dp 进度线、居中空态（52dp 图标框 + 标题）。 */
@Composable
internal fun LearningProjectsScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    BackHandler(onBack = onBack)
    Column(modifier.fillMaxSize().background(AutoScriptPalette.PageBackground)) {
        UtilityBlueHeader("学习项目", "刷新", onBack, {})
        Box(Modifier.fillMaxWidth().height(2.dp).background(Color(0xFF72A6FF)))
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            UtilityEmpty(R.drawable.ic_book_outline_24, "暂无学习项目", "")
        }
    }
}

/** 参考“开发者后台”是登录后的 WebView；本地版保留路由与层级，不接参考站点。 */
@Composable
internal fun DeveloperBackendScreen(projects: List<ProjectSummary>, onBack: () -> Unit, modifier: Modifier = Modifier) {
    BackHandler(onBack = onBack)
    Column(modifier.fillMaxSize().background(AutoScriptPalette.PageBackground)) {
        Row(Modifier.fillMaxWidth().height(64.dp).background(Color.White).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                painterResource(R.drawable.package_back_20),
                contentDescription = "返回",
                tint = Color.Unspecified,
                modifier = Modifier.size(40.dp).clickable(onClick = onBack).padding(10.dp),
            )
            Text("开发者后台", modifier = Modifier.weight(1f).padding(start = 8.dp), fontSize = 20.sp, color = AutoScriptPalette.TextPrimary)
            Icon(painterResource(R.drawable.ic_code_window_24), contentDescription = null, tint = Color(0xFF6D91F8), modifier = Modifier.size(24.dp))
        }
        Row(Modifier.fillMaxWidth().height(68.dp).background(Color.White).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(modifier = Modifier.size(44.dp), shape = RoundedCornerShape(12.dp), color = Color.White, shadowElevation = 4.dp) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(painterResource(R.drawable.ic_list_24), contentDescription = null, tint = AutoScriptPalette.Accent, modifier = Modifier.size(24.dp))
                }
            }
            Spacer(Modifier.weight(1f))
            Surface(shape = RoundedCornerShape(12.dp), color = Color.White, shadowElevation = 3.dp) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("−", modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp), color = AutoScriptPalette.Accent, fontSize = 20.sp)
                    Text("100%", modifier = Modifier.padding(horizontal = 12.dp), color = Color(0xFF315BCB), fontWeight = FontWeight.Bold)
                    Text("＋", modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp), color = AutoScriptPalette.Accent, fontSize = 20.sp)
                }
            }
            Spacer(Modifier.weight(1f))
            Surface(modifier = Modifier.size(44.dp), shape = RoundedCornerShape(22.dp), color = AutoScriptPalette.AccentSoft) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(painterResource(R.drawable.nav_profile_24), contentDescription = null, tint = Color(0xFF315BCB), modifier = Modifier.size(22.dp))
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(AutoScriptPalette.Border))
        LazyColumn(modifier = Modifier.weight(1f), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = Color.White), border = BorderStroke(1.dp, Color(0xFFCFE0FF))) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Text("开发者项目", fontSize = 23.sp, color = AutoScriptPalette.TextPrimary)
                        Text("管理项目发布规则、更新策略与授权。本地版只读，在线后台未开放。", fontSize = 13.sp, color = AutoScriptPalette.TextSecondary)
                        Surface(shape = RoundedCornerShape(20.dp), color = AutoScriptPalette.AccentSoft) {
                            Text("${projects.size} / 100 个项目", modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp), color = Color(0xFF315BCB), fontSize = 12.sp)
                        }
                        Text("项目由手机端打包或备份时自动创建", color = AutoScriptPalette.TextSecondary, fontSize = 12.sp)
                        Box(Modifier.fillMaxWidth().height(1.dp).background(AutoScriptPalette.Border))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("全部项目", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                            Surface(modifier = Modifier.padding(start = 8.dp), shape = RoundedCornerShape(20.dp), color = AutoScriptPalette.AccentSoft) {
                                Text(projects.size.toString(), modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp), color = Color(0xFF315BCB))
                            }
                        }
                        Text("项目由手机端接入，网页集中维护运行规则和授权状态。", fontSize = 12.sp, color = AutoScriptPalette.TextSecondary)
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Surface(modifier = Modifier.weight(1f).height(42.dp), shape = RoundedCornerShape(12.dp), color = AutoScriptPalette.AccentSoft) {
                                Box(contentAlignment = Alignment.Center) { Text("正常项目     ${projects.size}", color = Color(0xFF315BCB)) }
                            }
                            Surface(modifier = Modifier.weight(1f).height(42.dp), shape = RoundedCornerShape(12.dp), color = Color.White, shadowElevation = 3.dp) {
                                Box(contentAlignment = Alignment.Center) { Text("⟳  刷新", color = Color(0xFF315BCB)) }
                            }
                        }
                    }
                }
            }
            if (projects.isEmpty()) item { UtilityEmpty(R.drawable.nav_workspace_24, "暂无项目", "工作台项目会显示在这里") }
            items(projects, key = ProjectSummary::projectId) { project ->
                Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = Color.White), border = BorderStroke(1.dp, Color(0xFFCFE0FF))) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Surface(modifier = Modifier.size(38.dp), shape = RoundedCornerShape(11.dp), color = AutoScriptPalette.AccentSoft) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(painterResource(R.drawable.nav_workspace_24), contentDescription = null, tint = AutoScriptPalette.Accent, modifier = Modifier.size(20.dp))
                            }
                        }
                        Column(Modifier.weight(1f).padding(start = 10.dp)) {
                            Text(project.name, style = MaterialTheme.typography.titleSmall)
                            Text("本地正常 · 安装包尚未打包", style = MaterialTheme.typography.labelSmall, color = AutoScriptPalette.TextSecondary)
                        }
                        Icon(painterResource(R.drawable.ic_chevron_right_20), contentDescription = null, tint = AutoScriptPalette.TextSecondary, modifier = Modifier.size(20.dp))
                    }
                }
            }
        }
    }
}

/** `activity_backup_management.xml` 标题栏：58dp，40dp 返回图标，18sp 标题，10sp 右侧文字。 */
@Composable
internal fun UtilityLightHeader(
    title: String,
    trailing: String,
    onBack: () -> Unit,
    extra: @Composable () -> Unit = {},
) {
    Row(
        modifier = Modifier.fillMaxWidth().height(58.dp).padding(start = 12.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painterResource(R.drawable.package_back_20),
            contentDescription = "返回",
            tint = Color.Unspecified,
            modifier = Modifier.size(40.dp).clickable(onClick = onBack).padding(10.dp),
        )
        Text(
            title,
            modifier = Modifier.weight(1f).padding(start = 8.dp),
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = AutoScriptPalette.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(trailing, fontSize = 10.sp, color = AutoScriptPalette.TextSecondary)
        extra()
    }
}

@Composable
private fun UtilityBlueHeader(title: String, action: String, onBack: () -> Unit, onAction: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().height(56.dp).background(AutoScriptPalette.Accent).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(
            painterResource(R.drawable.visual_back_24),
            contentDescription = "返回",
            tint = Color.White,
            modifier = Modifier.size(48.dp).clickable(onClick = onBack).padding(12.dp),
        )
        Text(title, modifier = Modifier.weight(1f), color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        if (action.isNotBlank()) Text(action, modifier = Modifier.padding(horizontal = 12.dp).clickable(onClick = onAction), color = Color.White, fontSize = 13.sp)
    }
}

@Composable
private fun UtilityEmpty(@DrawableRes icon: Int, title: String, description: String) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 38.dp).heightIn(min = 52.dp),
    ) {
        Surface(modifier = Modifier.size(52.dp), shape = RoundedCornerShape(17.dp), color = AutoScriptPalette.AccentSoft) {
            Box(contentAlignment = Alignment.Center) {
                Icon(painterResource(icon), contentDescription = null, tint = AutoScriptPalette.Accent, modifier = Modifier.size(26.dp))
            }
        }
        Text(title, style = MaterialTheme.typography.titleMedium, color = AutoScriptPalette.TextSecondary)
        if (description.isNotBlank()) Text(description, style = MaterialTheme.typography.bodySmall, color = AutoScriptPalette.TextSecondary, textAlign = TextAlign.Center)
    }
}

internal fun formatBackupSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KB".format(Locale.ROOT, bytes / 1024.0)
    else -> "%.1f MB".format(Locale.ROOT, bytes / 1024.0 / 1024.0)
}

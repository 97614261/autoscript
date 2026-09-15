package com.autoscript.studio

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autoscript.project.store.ProjectSummary

/** Rebuilt from activity_backup_management.xml: 58dp header, hint strip, project list and empty state. */
@Composable
internal fun BackupManagementScreen(projects: List<ProjectSummary>, busyProjectId: String?, onBack: () -> Unit, onImport: () -> Unit, onExport: (ProjectSummary) -> Unit, modifier: Modifier = Modifier) {
    BackHandler(onBack = onBack)
    Column(modifier.fillMaxSize().background(Color(0xFFF7F8FB))) {
        UtilityLightHeader("备份管理", "可用 3 槽位/项目", onBack)
        Text("点击已有备份项目进入槽位管理；新备份请从工作台项目卡片进入", modifier = Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 16.dp, vertical = 10.dp), fontSize = 11.sp, color = Color(0xFF7A8499))
        LazyColumn(modifier = Modifier.weight(1f), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            if (projects.isEmpty()) item { UtilityEmpty("云", "暂无备份", "请在工作台项目卡片中点击备份") }
            items(projects, key = ProjectSummary::projectId) { project ->
                Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = Color.White)) {
                    Row(Modifier.padding(horizontal = 14.dp, vertical = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                        Surface(modifier = Modifier.size(40.dp), shape = RoundedCornerShape(12.dp), color = Color(0xFFE9F0FF)) { Box(contentAlignment = Alignment.Center) { Text("云", color = Color(0xFF4D7DF4), fontWeight = FontWeight.Bold) } }
                        Column(Modifier.weight(1f).padding(start = 10.dp)) {
                            Text(project.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("备份时间 未记录 · 0 B", style = MaterialTheme.typography.labelSmall, color = Color(0xFF7A8499), modifier = Modifier.padding(top = 3.dp))
                            Text("本机暂无备份，点击可选择槽位导出", style = MaterialTheme.typography.labelSmall, color = Color(0xFF7A8499), modifier = Modifier.padding(top = 3.dp))
                        }
                        OutlinedButton(onClick = { onExport(project) }, enabled = busyProjectId == null) { Text(if (busyProjectId == project.projectId) "处理中" else "备份") }
                    }
                }
            }
        }
    }
}

/** Rebuilt from activity_learning_projects.xml: blue header, thin loading divider and center empty state. */
@Composable
internal fun LearningProjectsScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    BackHandler(onBack = onBack)
    Column(modifier.fillMaxSize().background(Color(0xFFF7F8FB))) {
        UtilityBlueHeader("学习项目", "刷新", onBack, {})
        Box(Modifier.fillMaxWidth().height(2.dp).background(Color(0xFF72A6FF)))
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { UtilityEmpty("◇", "暂无学习项目", "") }
    }
}

/** Reference backend is a logged-in WebView. This local equivalent preserves route and hierarchy without embedding its service. */
@Composable
internal fun DeveloperBackendScreen(projects: List<ProjectSummary>, onBack: () -> Unit, modifier: Modifier = Modifier) {
    BackHandler(onBack = onBack)
    Column(modifier.fillMaxSize().background(Color(0xFFF7F8FB))) {
        Row(Modifier.fillMaxWidth().height(64.dp).background(Color.White).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("‹", modifier = Modifier.width(40.dp).clickable(onClick = onBack), fontSize = 31.sp, color = Color(0xFF516582), textAlign = TextAlign.Center)
            Text("开发者后台", modifier = Modifier.weight(1f).padding(start = 8.dp), fontSize = 20.sp, color = Color(0xFF182033))
            Text("▣", color = Color(0xFF6D91F8), fontSize = 25.sp)
        }
        Row(Modifier.fillMaxWidth().height(68.dp).background(Color.White).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(modifier = Modifier.size(44.dp), shape = RoundedCornerShape(12.dp), color = Color.White, shadowElevation = 4.dp) { Box(contentAlignment = Alignment.Center) { Text("☷", fontSize = 25.sp, color = Color(0xFF3A6EFF)) } }
            Spacer(Modifier.weight(1f))
            Surface(shape = RoundedCornerShape(12.dp), color = Color.White, shadowElevation = 3.dp) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("−", modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp), color = Color(0xFF3A6EFF), fontSize = 20.sp)
                    Text("100%", modifier = Modifier.padding(horizontal = 12.dp), color = Color(0xFF315BCB), fontWeight = FontWeight.Bold)
                    Text("＋", modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp), color = Color(0xFF3A6EFF), fontSize = 20.sp)
                }
            }
            Spacer(Modifier.weight(1f))
            Surface(modifier = Modifier.size(44.dp), shape = RoundedCornerShape(22.dp), color = Color(0xFFEAF0FF)) { Box(contentAlignment = Alignment.Center) { Text("♙", color = Color(0xFF315BCB), fontSize = 22.sp) } }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFFEBEFF5)))
        LazyColumn(modifier = Modifier.weight(1f), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = Color.White), border = BorderStroke(1.dp, Color(0xFFCFE0FF))) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Text("开发者项目", fontSize = 23.sp, color = Color(0xFF182033))
                        Text("管理项目发布规则、更新策略与授权。", fontSize = 13.sp, color = Color(0xFF7A8499))
                        Surface(shape = RoundedCornerShape(20.dp), color = Color(0xFFEAF0FF)) { Text("${projects.size} / 100 个项目", modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp), color = Color(0xFF315BCB), fontSize = 12.sp) }
                        Text("项目由手机端打包或备份时自动创建", color = Color(0xFF7A8499), fontSize = 12.sp)
                        Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFFEBEFF5)))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("全部项目", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                            Surface(modifier = Modifier.padding(start = 8.dp), shape = RoundedCornerShape(20.dp), color = Color(0xFFEAF0FF)) { Text(projects.size.toString(), modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp), color = Color(0xFF315BCB)) }
                        }
                        Text("项目由手机端接入，网页集中维护运行规则和授权状态。", fontSize = 12.sp, color = Color(0xFF7A8499))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Surface(modifier = Modifier.weight(1f).height(42.dp), shape = RoundedCornerShape(12.dp), color = Color(0xFFEAF0FF)) { Box(contentAlignment = Alignment.Center) { Text("正常项目     ${projects.size}", color = Color(0xFF315BCB)) } }
                            Surface(modifier = Modifier.weight(1f).height(42.dp), shape = RoundedCornerShape(12.dp), color = Color.White, shadowElevation = 3.dp) { Box(contentAlignment = Alignment.Center) { Text("⟳  刷新", color = Color(0xFF315BCB)) } }
                        }
                    }
                }
            }
            if (projects.isEmpty()) item { UtilityEmpty("▦", "暂无项目", "工作台项目会显示在这里") }
            items(projects, key = ProjectSummary::projectId) { project ->
                Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = Color.White), border = BorderStroke(1.dp, Color(0xFFCFE0FF))) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Surface(modifier = Modifier.size(38.dp), shape = RoundedCornerShape(11.dp), color = Color(0xFFE9F0FF)) { Box(contentAlignment = Alignment.Center) { Text("▦", color = Color(0xFF4D7DF4)) } }
                        Column(Modifier.weight(1f).padding(start = 10.dp)) {
                            Text(project.name, style = MaterialTheme.typography.titleSmall)
                            Text("本地正常 · 安装包尚未打包", style = MaterialTheme.typography.labelSmall, color = Color(0xFF7D8799))
                        }
                        Text("›", fontSize = 25.sp, color = Color(0xFF9AA4B5))
                    }
                }
            }
        }
    }
}

@Composable
private fun UtilityLightHeader(title: String, trailing: String, onBack: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().height(58.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("‹", modifier = Modifier.width(40.dp).clickable(onClick = onBack), fontSize = 30.sp, color = Color(0xFF28354A), textAlign = TextAlign.Center)
        Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
        Text(trailing, fontSize = 11.sp, color = Color(0xFF7D8799))
    }
}

@Composable
private fun UtilityBlueHeader(title: String, action: String, onBack: () -> Unit, onAction: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().height(56.dp).background(Color(0xFF3A6EFF)).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("‹", modifier = Modifier.width(48.dp).clickable(onClick = onBack), color = Color.White, fontSize = 31.sp, textAlign = TextAlign.Center)
        Text(title, modifier = Modifier.weight(1f), color = Color.White, style = MaterialTheme.typography.titleLarge)
        if (action.isNotBlank()) Text(action, modifier = Modifier.padding(horizontal = 12.dp).clickable(onClick = onAction), color = Color.White, fontSize = 13.sp)
    }
}

@Composable
private fun UtilityEmpty(symbol: String, title: String, description: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 38.dp)) {
        Surface(modifier = Modifier.size(52.dp), shape = RoundedCornerShape(17.dp), color = Color(0xFFE9F0FF)) { Box(contentAlignment = Alignment.Center) { Text(symbol, color = Color(0xFF4D7DF4), fontSize = 23.sp) } }
        Text(title, style = MaterialTheme.typography.titleMedium)
        if (description.isNotBlank()) Text(description, style = MaterialTheme.typography.bodySmall, color = Color(0xFF7D8799), textAlign = TextAlign.Center)
    }
}

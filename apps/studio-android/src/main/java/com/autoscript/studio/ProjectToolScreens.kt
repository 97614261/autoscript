package com.autoscript.studio

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autoscript.core.designsystem.AutoScriptDimens
import com.autoscript.project.store.ProjectSnapshot
import com.autoscript.project.store.ProjectStore

@Composable
internal fun ProjectPackageScreen(
    snapshot: ProjectSnapshot,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onBack)
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("打包", "更新", "日志输出")
    Column(modifier = modifier.fillMaxSize().background(Color(0xFFF7F8FB))) {
        Row(
            modifier = Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack, contentPadding = PaddingValues(11.dp), modifier = Modifier.size(42.dp)) {
                Icon(painterResource(R.drawable.package_back_20), contentDescription = "返回", tint = Color.Unspecified)
            }
            Text("打包应用", modifier = Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("APK", color = Color(0xFF4875EF), fontWeight = FontWeight.Bold, modifier = Modifier.background(Color(0xFFE6EEFF), RoundedCornerShape(9.dp)).padding(horizontal = 12.dp, vertical = 6.dp))
        }
        Row(Modifier.fillMaxWidth().height(36.dp).background(Color(0xFFF5F7FB))) {
            tabs.forEachIndexed { index, label ->
                Column(Modifier.weight(1f).fillMaxSize()) {
                    TextButton(onClick = { selectedTab = index }, modifier = Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(0.dp)) {
                        Text(label, color = if (selectedTab == index) Color(0xFF3A6EFF) else Color(0xFF7A8499), fontSize = 12.sp)
                    }
                    Box(Modifier.fillMaxWidth().height(2.dp).background(if (selectedTab == index) Color(0xFF3A6EFF) else Color.Transparent))
                }
            }
        }
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (selectedTab) {
                0 -> {
                    item {
                        Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
                            Column(Modifier.padding(14.dp)) {
                                Surface(modifier = Modifier.fillMaxWidth().height(42.dp), shape = RoundedCornerShape(10.dp), color = Color(0xFFEAF0FF)) {
                                    Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                                        Icon(painterResource(R.drawable.package_folder_open_20), contentDescription = null, tint = Color.Unspecified, modifier = Modifier.size(18.dp))
                                        Text("从文件管理器选择图片", color = Color(0xFF3A6EFF), fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 7.dp))
                                    }
                                }
                            }
                        }
                    }
                    item { Text("安装信息", style = MaterialTheme.typography.titleMedium) }
                    item {
                        Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
                            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                PackageInput("应用名称", snapshot.manifest.name)
                                PackageInput("应用包名", suggestedApplicationId(snapshot.manifest.name, snapshot.manifest.projectId), trailingLabel = "历史包名")
                                PackageInput("输出路径", "/sdcard/${snapshot.manifest.name}.apk")
                            }
                        }
                    }
                    item { Text("代码与资源保护", style = MaterialTheme.typography.titleMedium) }
                    item {
                        Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
                            Column(Modifier.padding(horizontal = 14.dp)) {
                                PackageSettingRow("项目保护", "已开启")
                                Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFFEBEFF5)))
                                PackageSwitchRow(".sue 文件转 Lua 片段", true)
                            }
                        }
                    }
                    item { Text("随包插件", style = MaterialTheme.typography.titleMedium) }
                    item {
                        Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
                            Row(Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(painterResource(R.drawable.package_java_plugin_24), contentDescription = null, tint = Color.Unspecified, modifier = Modifier.padding(end = 9.dp).size(24.dp))
                                Text("选择插件", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                                Text("未选择  ›", color = Color(0xFF3A6EFF), fontSize = 11.sp)
                            }
                        }
                    }
                }
                1 -> item { PackageInfoCard("更新策略", listOf("当前模式" to "本地离线", "远程更新" to "预留，未连接后台", "在线授权" to "预留，未启用")) }
                else -> item { PackageInfoCard("日志输出", listOf("状态" to "暂无本机构建日志", "说明" to "主机门禁的报告保存在仓库 build 目录。")) }
            }
        }
        Surface(color = Color.White, shadowElevation = 8.dp) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
                Surface(modifier = Modifier.fillMaxWidth().height(44.dp), shape = RoundedCornerShape(12.dp), color = Color(0xFFDCE7FF)) {
                    Box(contentAlignment = Alignment.Center) { Text("开始打包 APK", color = Color(0xFF2446C7), fontSize = 13.sp, fontWeight = FontWeight.Bold) }
                }
            }
        }
    }
}

@Composable
private fun PackageInput(label: String, value: String, trailingLabel: String? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, fontSize = 10.sp, color = Color(0xFF7A8499), modifier = Modifier.weight(1f))
            if (trailingLabel != null) Text(trailingLabel, fontSize = 10.sp, color = Color(0xFF3A6EFF))
        }
        Surface(modifier = Modifier.fillMaxWidth().height(46.dp), color = Color(0xFFF5F7FB), shape = RoundedCornerShape(11.dp)) {
            Text(value, modifier = Modifier.padding(horizontal = 12.dp, vertical = 13.dp), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun PackageSettingRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f), fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Text(value, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = if (value == "已开启") Color(0xFF4169C1) else Color(0xFF7A8499))
    }
}

@Composable
private fun PackageSwitchRow(label: String, checked: Boolean) {
    Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f), fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Switch(checked = checked, onCheckedChange = null, modifier = Modifier.size(48.dp, 30.dp))
    }
}

@Composable
internal fun ImageToolsScreen(
    snapshot: ProjectSnapshot,
    store: ProjectStore,
    onSnapshotChanged: (ProjectSnapshot) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onBack)
    var settingsVisible by remember(snapshot.manifest.projectId) { mutableStateOf(false) }
    val images = snapshot.manifest.resources.mapNotNull { resource ->
        resource.takeIf { it.get("kind")?.asString == "image" }?.get("path")?.asString
    }
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            horizontal = AutoScriptDimens.PageHorizontal,
            vertical = AutoScriptDimens.PageVertical,
        ),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("‹ 返回") }
                Column(Modifier.weight(1f)) {
                    Text("图片与标注", style = MaterialTheme.typography.headlineSmall)
                    Text("项目图片 ${images.size} 张", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = { settingsVisible = true }) { Text("导入") }
            }
        }
        item {
            Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(AutoScriptDimens.CardRadius)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text("截图工作区", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "运行时截图、区域标注、取色和模板裁剪共用此入口。M27 完成页面与资源流，新增视觉算法在 M28 接入。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth()) {
                        Text("需要运行时截图会话")
                    }
                }
            }
        }
        if (images.isEmpty()) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Text("暂无图片，先从项目资源导入。", modifier = Modifier.padding(18.dp))
                }
            }
        }
        items(images, key = { it }) { path ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(path.substringAfterLast('/'), style = MaterialTheme.typography.titleSmall)
                    Text(
                        path,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
    if (settingsVisible) {
        ProjectSettingsDialog(
            snapshot = snapshot,
            store = store,
            onSnapshotChanged = onSnapshotChanged,
            onDismiss = { settingsVisible = false },
        )
    }
}

@Composable
private fun PackageInfoCard(title: String, values: List<Pair<String, String>>) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(AutoScriptDimens.CardRadius), colors = CardDefaults.cardColors(containerColor = Color.White)) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                values.forEach { (label, value) ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(value, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

private fun suggestedApplicationId(projectName: String, projectId: String): String {
    val nameSuffix = projectName.lowercase().filter(Char::isLetterOrDigit).take(30)
    val suffix = nameSuffix.ifEmpty { projectId.lowercase().filter(Char::isLetterOrDigit).takeLast(20).ifEmpty { "project" } }
    return "com.yibian.app.$suffix"
}

@Composable
internal fun RecordingWorkspaceScreen(
    snapshot: ProjectSnapshot,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onBack)
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            horizontal = AutoScriptDimens.PageHorizontal,
            vertical = AutoScriptDimens.PageVertical,
        ),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("‹ 返回") }
                Column(Modifier.weight(1f)) {
                    Text("动作录制", style = MaterialTheme.typography.headlineSmall)
                    Text(snapshot.manifest.name, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text("未录制", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item {
            Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(AutoScriptDimens.CardRadius)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text("悬浮控制预览", style = MaterialTheme.typography.titleMedium)
                    Card {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Text("● REC", color = MaterialTheme.colorScheme.error)
                            Text("拖动")
                            Text("停止")
                        }
                    }
                    Text(
                        "录制会把用户确认的按键、点击、滑动和等待动作转换为积木草稿。当前 Runtime 尚未提供录制事件流，因此这里只展示最终交互边界。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        item { Text("动作类型", style = MaterialTheme.typography.titleLarge) }
        items(listOf("点击与长按", "滑动手势", "设备按键", "等待与循环", "寻图后操作")) { label ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Text(label, modifier = Modifier.padding(12.dp))
            }
        }
        item {
            OutlinedButton(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth()) {
                Text("录制后端尚未开放")
            }
        }
    }
}

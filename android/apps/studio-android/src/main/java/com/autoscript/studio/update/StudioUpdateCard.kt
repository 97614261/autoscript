package com.autoscript.studio

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal val LocalStudioUpdater = staticCompositionLocalOf<StudioUpdateController> { error("Missing Studio updater") }

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun StudioUpdateCard() {
    val updater = LocalStudioUpdater.current
    val value by updater.state.collectAsState()
    val context = LocalContext.current
    var configuring by remember { mutableStateOf(value.source.isEmpty()) }
    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("应用更新", fontSize = 14.sp)
            TextButton(enabled = !value.busy, onClick = { configuring = !configuring }) { Text("更新地址", fontSize = 11.sp) }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(value.automatic, updater::setAutomatic)
            Text("打开 App 自动检查（每 6 小时一次）", fontSize = 11.sp)
        }
        Text("发现新版先提醒，确认后才下载，安装由系统确认。", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (configuring) OutlinedTextField(value.source, updater::setSource, enabled = !value.busy,
            singleLine = true, label = { Text("文档站根地址", fontSize = 11.sp) }, modifier = Modifier.fillMaxWidth())
        if (runCatching { StudioUpdateSite.parse(value.source.trim()).encrypted }.getOrNull() == false)
            Text("HTTP 连接未加密；安装包另行校验当前 App 签名。建议站点启用 HTTPS。", fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
        value.message?.let { Text(it, fontSize = 12.sp) }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { if (value.busy) updater.cancel() else updater.check() }) {
                Text(if (value.busy) "取消操作" else "检查更新", fontSize = 12.sp)
            }
            if (value.release?.let { studioUpdateRelation(it, BuildConfig.VERSION_CODE) } == StudioUpdateRelation.NEWER)
                TextButton(enabled = !value.busy, onClick = updater::showPrompt) { Text(if (value.ready) "安装更新" else "更新详情", fontSize = 12.sp) }
            value.site?.let { site -> TextButton(enabled = !value.busy, onClick = {
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(site.downloadPage.toASCIIString())).addCategory(Intent.CATEGORY_BROWSABLE)) }
            }) { Text("下载页", fontSize = 12.sp) } }
        }
    }
}

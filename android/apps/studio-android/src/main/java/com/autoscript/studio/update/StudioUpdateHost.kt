package com.autoscript.studio

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

internal fun studioUpdateActionLabel(value: StudioUpdateState): String = when {
    value.verifying -> "校验中…"
    value.downloading -> "下载中…"
    value.ready && value.needsPermission -> "去授权"
    value.ready -> "确认安装"
    else -> "下载更新"
}

@Composable
internal fun StudioUpdateHost(updater: StudioUpdateController, installAllowed: Boolean) {
    val owner = LocalLifecycleOwner.current
    val context = LocalContext.current
    val value by updater.state.collectAsState()
    val allowed by rememberUpdatedState(installAllowed)
    DisposableEffect(owner, updater) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) updater.foreground()
            else if (event == Lifecycle.Event.ON_PAUSE) updater.background()
        }
        owner.lifecycle.addObserver(observer)
        if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) updater.foreground()
        onDispose { owner.lifecycle.removeObserver(observer); updater.close() }
    }
    LaunchedEffect(value.installRequested, value.busy, value.foreground) {
        if (value.installRequested && !value.busy && value.foreground) updater.install(context) { allowed }
    }
    val release = value.release
    if (value.prompt && value.foreground && release != null) StudioConfirmDialog(
        onDismissRequest = updater::dismiss,
        compact = true,
        title = { Text("发现新版本 ${release.versionName}", fontSize = 15.sp) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("当前 ${BuildConfig.VERSION_NAME} → ${release.versionName}", fontSize = 12.sp)
                Text("%.1f MiB · ${release.abis.joinToString(" / ")}".format(java.util.Locale.ROOT, release.bytes / 1048576.0),
                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (release.description.isNotEmpty()) {
                    Text("本次更新", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                    Text(release.description, fontSize = 12.sp)
                }
                if (release.unverifiedCandidate) Text("开发签名候选：此安装包尚未完成设备验收。", fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
                if (value.site?.encrypted == false) Text("HTTP 未加密；安装前会校验安装包摘要和当前 App 签名。建议使用 HTTPS。", fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
                Text("安装会重启智构，请先保存、停止脚本并备份重要项目。不卸载旧版，不清除数据。", fontSize = 11.sp)
                if (value.downloading) {
                    LinearProgressIndicator(progress = { (value.downloaded.toFloat() / release.bytes).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                    Text("%d%% · %.1f / %.1f MiB".format(java.util.Locale.ROOT,
                        (value.downloaded * 100 / release.bytes).coerceIn(0, 100), value.downloaded / 1048576.0, release.bytes / 1048576.0), fontSize = 11.sp)
                }
                if (value.verifying) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (value.busy) Text("离开 App 会取消本次操作，返回后可重试。", fontSize = 11.sp)
                value.message?.let { Text(it, fontSize = 11.sp) }
            }
        },
        actions = listOf(
            StudioConfirmAction(if (value.busy) "取消更新" else "稍后再说", updater::dismiss),
            StudioConfirmAction(studioUpdateActionLabel(value), enabled = !value.busy, onClick = {
                when {
                    value.ready && value.needsPermission -> updater.openInstallPermission(context)
                    value.ready -> updater.install(context) { allowed }
                    else -> updater.download()
                }
            }),
        ),
    )
}

package com.autoscript.studio

import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.net.toUri

@Composable
internal fun rememberScriptLaunchOverlayLauncher(): (String) -> Unit {
    val context = LocalContext.current
    var requested by remember { mutableStateOf<String?>(null) }
    fun message(text: String) { Toast.makeText(context, text, Toast.LENGTH_LONG).show() }
    fun start(id: String) {
        if (!validEditorProjectId(id)) { message("项目标识无效"); return }
        if (!canOpenScriptLaunch(EditorOverlaySessions.status.value?.projectId, ScriptLaunchSessions.status.value?.projectId, id)) {
            message("请先关闭已有的编辑或配置窗口"); return
        }
        requested = null
        runCatching {
            ContextCompat.startForegroundService(context, Intent(context, ScriptLaunchOverlayService::class.java)
                .setAction(ScriptLaunchOverlayService.ACTION_OPEN).putExtra(ScriptLaunchOverlayService.EXTRA_PROJECT, id))
        }.onFailure { message("脚本界面启动失败，请检查悬浮授权和后台限制") }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val id = requested; requested = null
        if (id != null && Settings.canDrawOverlays(context)) start(id)
        else if (id != null) message("未允许悬浮窗，未启动项目")
    }
    return { id ->
        if (!Settings.canDrawOverlays(context)) {
            requested = id
            runCatching { permission.launch(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, "package:${context.packageName}".toUri())) }
                .onFailure { requested = null; message("请在系统设置中允许智构悬浮窗") }
        } else start(id)
    }
}

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

/** User-triggered production entry; never create an App-local editor as a silent fallback. */
@Composable
internal fun rememberEditorOverlayLauncher(): (String) -> Unit {
    val context = LocalContext.current
    var requested by remember { mutableStateOf<String?>(null) }
    fun message(text: String) { Toast.makeText(context, text, Toast.LENGTH_LONG).show() }
    fun start(id: String) {
        if (!validEditorProjectId(id)) { message("项目标识无效"); return }
        if (ScriptLaunchSessions.status.value != null) { message("请先关闭脚本配置窗口，再编辑"); return }
        if (editorSessionDecision(EditorOverlaySessions.status.value?.projectId, id) == EditorSessionDecision.REJECT) {
            message("请先关闭当前悬浮编辑，再切换项目"); return
        }
        requested = null
        runCatching {
            ContextCompat.startForegroundService(context, Intent(context, EditorOverlayService::class.java)
                .setAction(EditorOverlayService.ACTION_OPEN).putExtra(EditorOverlayService.EXTRA_PROJECT, id))
        }.onFailure { requested = null; message("悬浮编辑启动失败，请检查系统权限和后台限制") }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val id = requested
        requested = null
        if (id != null && Settings.canDrawOverlays(context)) start(id)
        else if (id != null) message("未允许悬浮编辑；请允许智构显示在其他应用上层")
    }
    return { id ->
        if (!Settings.canDrawOverlays(context)) {
            requested = id
            runCatching { permission.launch(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, "package:${context.packageName}".toUri())) }
                .onFailure { requested = null; message("请在系统设置中允许智构悬浮窗") }
        } else start(id)
    }
}

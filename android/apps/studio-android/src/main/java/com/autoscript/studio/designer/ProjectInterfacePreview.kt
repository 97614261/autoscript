package com.autoscript.studio

import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.window.Popup
import com.autoscript.script.ui.ScriptUiDefinition
import com.autoscript.script.ui.ScriptUiDialog
import com.autoscript.script.ui.ScriptUiWindowView
import com.google.gson.JsonObject
import java.io.File

internal fun scriptUiImage(root: File?, path: String): File? {
    if (root == null || !path.startsWith("assets/images/") || path.split('/').any { it == ".." || it.isEmpty() } || '\\' in path) return null
    val target = File(root, path).canonicalFile
    return target.takeIf { it.path.startsWith(root.canonicalPath + File.separator) }
}

@Composable
internal fun ProjectInterfacePreview(definition: JsonObject?, directory: File? = null, onDismiss: () -> Unit) {
    if (definition == null) StudioConfirmDialog(onDismissRequest = onDismiss, text = { Text("尚未设计脚本界面") }, confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } })
    else ScriptInterfaceDialog(definition, directory, "preview", preview = true, onDismiss = onDismiss, onConfirm = {})
}

@Composable
internal fun ScriptInterfaceDialog(definition: JsonObject, directory: File?, projectId: String, preview: Boolean = false, onDismiss: () -> Unit, onConfirm: (Map<String, String>) -> Unit) {
    val context = LocalContext.current
    val overlayHost = LocalEditorOverlay.current
    val configuration = LocalConfiguration.current
    val model = remember(definition) { ScriptUiDefinition.parse(definition.toString()) }
    val dismiss by rememberUpdatedState(onDismiss)
    val confirm by rememberUpdatedState(onConfirm)
    var minimized by remember(model, projectId) { mutableStateOf(false) }
    var confirming by remember(model, projectId) { mutableStateOf(false) }
    val window = remember(model, projectId, context, preview, directory) {
        val prefs = context.getSharedPreferences("script-ui-config", android.content.Context.MODE_PRIVATE)
        val initial = model.fields.associate { it.id to if (preview) it.initialValue else prefs.getString("$projectId:${it.id}", it.initialValue).orEmpty() }
            .let { runCatching { model.validateValues(it) }.getOrDefault(model.initialValues()) }
        lateinit var dialog: ScriptUiDialog
        lateinit var view: ScriptUiWindowView
        view = ScriptUiWindowView(context, model, initial, { scriptUiImage(directory, it) }, if (preview) "界面预览 · 不执行脚本" else "脚本界面") { _, _, _, action ->
            when (action) {
                "run" -> if (preview) view.error("预览不会执行脚本") else if (!confirming) {
                    runCatching { model.validateValues(view.values()) }.onSuccess { validated ->
                        confirming = true
                        dialog.setBusy(true)
                        // Hide the native window now, not when Compose later disposes it.
                        dialog.hide()
                        val edit = prefs.edit()
                        validated.forEach { (id, value) -> edit.putString("$projectId:$id", value) }
                        edit.apply()
                        runCatching { confirm(validated) }.onFailure {
                            confirming = false; dialog.setBusy(false); view.error(it.message ?: "启动失败"); dialog.show()
                        }
                    }.onFailure { view.error(it.message ?: "参数无效") }
                }
                "close" -> if (!confirming) dismiss()
                "minimize" -> if (!confirming) minimized = true
            }
        }
        dialog = ScriptUiDialog(context, view, systemOverlay = overlayHost != null) { if (!confirming) dismiss() }
        dialog
    }
    var unavailable by remember(window) { mutableStateOf(false) }
    val managedWindow = remember(window) {
        object : EditorSessionWindow {
            override fun visibility(visible: Boolean) {
                runCatching {
                    if (!visible || minimized || confirming || unavailable) window.hide() else window.show()
                }.onFailure { unavailable = true }
            }
            override fun close() { window.close() }
        }
    }
    DisposableEffect(managedWindow, overlayHost) {
        if (overlayHost != null && !overlayHost.registerChild(managedWindow)) unavailable = true
        onDispose {
            if (overlayHost == null) managedWindow.close() else overlayHost.unregisterChild(managedWindow)
        }
    }
    LaunchedEffect(managedWindow, minimized, confirming, overlayHost?.hidden, configuration.screenWidthDp, configuration.screenHeightDp, configuration.orientation) {
        managedWindow.visibility(overlayHost?.hidden != true && overlayHost?.isClosed != true)
    }
    if (minimized && !unavailable && overlayHost?.hidden != true) Popup(alignment = Alignment.BottomEnd, onDismissRequest = { minimized = false }) {
        Button(onClick = { minimized = false }) { Text("恢复脚本界面") }
    }
    if (unavailable) StudioConfirmDialog(onDismissRequest = { dismiss() },
        text = { Text("界面窗口无法显示，请检查悬浮授权后重新预览；已保存的设计保持不变。") },
        confirmButton = { TextButton(onClick = { dismiss() }) { Text("关闭") } })
}

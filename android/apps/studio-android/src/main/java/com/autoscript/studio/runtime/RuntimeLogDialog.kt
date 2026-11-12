package com.autoscript.studio

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autoscript.runtime.api.RuntimeLogFiles
import com.autoscript.runtime.api.RuntimeScriptLog
import com.autoscript.runtime.api.RuntimeSystemLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private data class ReadableRunLog(val run: RuntimeLogFiles.Run, val lines: List<String>)

@Composable
internal fun RuntimeLogDialog(currentLines: List<String>, projectId: String?,
    onLocate: ((RuntimeLogLocation) -> String?)? = null, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val overlay = LocalEditorOverlay.current
    DisposableEffect(overlay) {
        overlay?.realtimeLogRequested = true
        onDispose { overlay?.realtimeLogRequested = false }
    }
    var category by rememberSaveable { mutableStateOf("script") }
    var fullScreen by rememberSaveable { mutableStateOf(false) }
    var revision by remember { mutableStateOf(0) }
    var selected by remember { mutableStateOf<String?>(null) }
    var runs by remember { mutableStateOf<List<RuntimeLogFiles.Run>>(emptyList()) }
    var loaded by remember { mutableStateOf<ReadableRunLog?>(null) }
    var system by remember { mutableStateOf<List<String>>(emptyList()) }
    var message by remember { mutableStateOf<String?>(null) }
    StudioSystemLogEffect("日志管理", message)
    var menu by remember { mutableStateOf(false) }
    var cleanup by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var exportLines by remember { mutableStateOf<List<String>>(emptyList()) }
    val history = selected != null
    val activeRun = loaded?.takeIf { it.run.id == selected }
    val channel = if (category == "system") RuntimeConsoleChannel.SYSTEM else RuntimeConsoleChannel.ACTIVITY
    val offset = activeRun?.run?.metadata?.let { meta ->
        val wall = meta["startedAtMs"]?.toLongOrNull()
        val elapsed = meta["startedElapsedMs"]?.toLongOrNull()
        if (wall != null && elapsed != null && elapsed in 0..wall) wall - elapsed else null
    }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        val frozen = exportLines
        if (uri != null) scope.launch {
            busy = true
            try {
                val result = runtimeLogAttempt { withContext(Dispatchers.IO) {
                    requireNotNull(context.contentResolver.openOutputStream(uri, "w")).use {
                        it.write(frozen.joinToString("\n").toByteArray(Charsets.UTF_8))
                    }
                } }
                message = if (result.isSuccess) "日志已导出" else "导出失败，请检查保存位置"
            } finally { busy = false }
        }
        if (uri == null) busy = false
        exportLines = emptyList()
    }
    LaunchedEffect(projectId, revision) {
        runtimeLogAttempt { withContext(Dispatchers.IO) {
            RuntimeLogFiles.list(context.filesDir).filter { projectId == null || it.metadata["projectId"] == projectId }
        } }.onSuccess { values ->
            runs = values
            if (selected != null && values.none { it.id == selected }) selected = null
        }.onFailure { message = "读取运行记录失败，请刷新" }
    }
    LaunchedEffect(selected, revision) {
        loaded = null
        val id = selected ?: return@LaunchedEffect
        runtimeLogAttempt { withContext(Dispatchers.IO) {
            val snapshot = RuntimeLogFiles.snapshot(context.filesDir, id, "script.log")
            val script = RuntimeScriptLog.history(snapshot.text, snapshot.events)
            val diagnostic = runtimeLogDiagnosticRecords(RuntimeLogFiles.read(context.filesDir, id, "diagnostic.log"))
            ReadableRunLog(snapshot.run, (script + diagnostic).sortedBy {
                runtimeConsoleTimestamp.find(it)?.groupValues?.get(2)?.toLongOrNull() ?: 0L
            })
        } }.onSuccess { loaded = it }.onFailure { message = "读取此运行记录失败，请刷新" }
    }
    LaunchedEffect(category, selected, revision) {
        if (category != "system" || history) return@LaunchedEffect
        while (isActive) {
            system = withContext(Dispatchers.IO) { RuntimeSystemLog.read(context.filesDir) }
            delay(1000)
        }
    }
    val lines = if (history) activeRun?.lines.orEmpty() else if (category == "system")
        runtimeLogCurrentSystemLines(system, currentLines) else currentLines
    val locate: ((RuntimeLogLocation) -> String?)? = onLocate?.let { action -> { target ->
        val result = if (history && activeRun?.run?.metadata?.get("projectId") != projectId) "记录不属于当前项目，不能定位" else action(target)
        if (result == null) onDismiss()
        result
    } }
    if (cleanup) StudioConfirmDialog(onDismissRequest = { if (!busy) cleanup = false },
        title = { Text("删除这次运行记录？") }, text = { Text("删除本次运行和脚本日志，保留项目、图片和应用闪退记录。") },
        dismissButton = { LogAction("取消", { cleanup = false }, enabled = !busy) },
        confirmButton = { LogAction(if (busy) "删除中…" else "删除", {
            val owner = projectId
            val id = selected
            if (owner != null && id != null) scope.launch {
                busy = true
                try {
                    runtimeLogAttempt { withContext(Dispatchers.IO) { RuntimeLogFiles.cleanup(context.filesDir, owner, setOf(id)) } }
                        .onSuccess { value ->
                            message = if (id in value.deleted) "记录已删除" else "当前仍在运行或写入，记录已保留"
                            if (id in value.deleted) { selected = null; loaded = null }
                            revision++
                        }.onFailure { message = "删除失败，请刷新后重试" }
                } finally { busy = false; cleanup = false }
            }
        }, enabled = !busy) })
    StudioPanelDialog("日志", onDismiss, fullScreen = fullScreen, showFooter = false,
        compactHeader = true, onToggleFullScreen = { fullScreen = !fullScreen }) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val height = studioPanelViewportHeight(maxHeight.value, runtimeLogBodyHeightDp(LocalConfiguration.current.screenHeightDp), fullScreen).dp
            Column(Modifier.fillMaxWidth().height(height).padding(horizontal = 10.dp)) {
                Column(Modifier.heightIn(max = studioToolbarBudget(height.value, 2).dp).verticalScroll(rememberScrollState())) {
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        StudioToolSegments(category, listOf("system" to "系统日志", "script" to "脚本运行日志"), Modifier.weight(1f)) { category = it }
                        Box {
                            LogAction("⋯", { menu = true })
                            DropdownMenu(menu, { menu = false }, containerColor = Color.White) {
                                DropdownMenuItem(text = { LogText("刷新") }, onClick = { menu = false; revision++ })
                                DropdownMenuItem(text = { LogText("导出当前日志") }, enabled = !busy && lines.isNotEmpty(), onClick = {
                                    menu = false
                                    val clock = if (history) offset else System.currentTimeMillis() - android.os.SystemClock.elapsedRealtime()
                                    exportLines = filterRuntimeConsole(lines, "", false, channel).map { raw ->
                                        runtimeLogLinePresentation(raw, clock).let { "${it.time}  ${it.message}" }
                                    }
                                    busy = true
                                    runCatching { export.launch("智构-${if (category == "system") "系统日志" else "脚本日志"}.txt") }
                                        .onFailure { busy = false; exportLines = emptyList(); message = "无法打开保存窗口" }
                                })
                                if (history && projectId != null) DropdownMenuItem(text = { LogText("删除这次记录") }, enabled = !busy,
                                    onClick = { menu = false; cleanup = true })
                            }
                        }
                    }
                    StudioCompactChoice("记录", selected ?: "current", listOf("current" to "当前") + runs.map { run ->
                        val date = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date(run.metadata["startedAtMs"]?.toLongOrNull() ?: 0))
                        val status = when (run.metadata["status"]) { "running" -> "运行中"; "ended" -> "结束"; "stopped" -> "停止"; "failed", "start_failed" -> "失败"; else -> "中断" }
                        run.id to "$date · $status"
                    }, !busy) { selected = it.takeUnless { value -> value == "current" } }
                    message?.let { LogText(it, color = Color.Gray, maxLines = 2) }
                    activeRun?.run?.metadata?.let { metadata ->
                        if (metadata["writerError"] == "true" || (metadata["droppedRecords"]?.toLongOrNull() ?: 0) > 0 ||
                            (metadata["visionEngineDropped"]?.toLongOrNull() ?: 0) > 0 ||
                            (metadata["evictedEventRecords"]?.toLongOrNull() ?: 0) > 0 ||
                            (metadata["truncatedRecords"]?.toLongOrNull() ?: 0) > 0) LogText("部分日志未能保存或已轮换，当前记录可能不完整", color = Color.Red, size = 10)
                    }
                }
                key(channel, selected) {
                    Box(Modifier.weight(1f)) { LogConsolePanel(lines, history, channel, if (history) offset else null, onLocate = locate) }
                }
            }
        }
    }
}

internal val LogBlue = Color(0xFF3867FF)
internal val LogBorder = Color(0xFFE6EAF2)

@Composable
internal fun LogText(text: String, modifier: Modifier = Modifier, color: Color = Color(0xFF30384B), size: Int = 11,
    maxLines: Int = Int.MAX_VALUE, align: TextAlign = TextAlign.Start, bold: Boolean = false) {
    Text(text, modifier, color = color, maxLines = maxLines, overflow = TextOverflow.Ellipsis, textAlign = align,
        style = TextStyle(fontSize = size.sp, lineHeight = (size + 3).sp, fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal,
            platformStyle = PlatformTextStyle(includeFontPadding = false)))
}

@Composable
internal fun LogAction(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, selected: Boolean = false,
    enabled: Boolean = true, align: TextAlign = TextAlign.Center) {
    Box(modifier.heightIn(min = 28.dp).background(if (selected) Color(0xFFF0F4FF) else Color.Transparent, RoundedCornerShape(5.dp))
        .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 6.dp, vertical = 4.dp),
        contentAlignment = if (align == TextAlign.Start) Alignment.CenterStart else Alignment.Center) {
        LogText(label, color = if (!enabled) Color.LightGray else if (selected) LogBlue else Color.Gray,
            maxLines = 1, align = align, bold = selected)
    }
}

package com.autoscript.studio

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autoscript.runtime.api.RuntimeLogFiles
import com.autoscript.runtime.api.RuntimeLogEventCodec
import com.autoscript.runtime.api.RuntimeScriptLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private data class LogLoadedSnapshot(val events: String, val statistics: VisionRunStatistics.Report,
    val run: RuntimeLogFiles.Run, val locations: List<RuntimeLogLocation>)

@Composable
internal fun RuntimeLogDialog(currentLines: List<String>, projectId: String?,
    onLocate: ((RuntimeLogLocation) -> String?)? = null,
    debugSettings: com.autoscript.project.store.ProjectDebugSettings? = null,
    onSaveDebugSettings: ((com.autoscript.project.store.ProjectDebugSettings) -> Unit)? = null,
    settingsEditable: Boolean = true,
    onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var tab by remember { mutableStateOf(0) }
    var diagnostics by remember { mutableStateOf(false) }
    val overlay = LocalEditorOverlay.current
    DisposableEffect(overlay, tab) {
        overlay?.realtimeLogRequested = tab == 0
        onDispose { overlay?.realtimeLogRequested = false }
    }
    var fullScreen by rememberSaveable { mutableStateOf(false) }
    var recordingSettings by remember { mutableStateOf(false) }
    if (recordingSettings && debugSettings != null && onSaveDebugSettings != null) {
        RuntimeLogSettingsDialog(debugSettings, onSave = onSaveDebugSettings,
            enabled = settingsEditable, onDismiss = { recordingSettings = false })
    }
    var screenshotOverview by remember { mutableStateOf(false) }
    var comparing by remember { mutableStateOf(false) }
    var revision by remember { mutableStateOf(0) }
    var runs by remember { mutableStateOf<List<RuntimeLogFiles.Run>>(emptyList()) }
    var selected by remember { mutableStateOf<String?>(null) }
    var channel by remember { mutableStateOf("script.log") }
    var historyLines by remember { mutableStateOf<List<String>>(emptyList()) }
    var loadedEvents by remember { mutableStateOf<LogLoadedSnapshot?>(null) }
    var locations by remember { mutableStateOf<List<RuntimeLogLocation>>(emptyList()) }
    var locationMessage by remember { mutableStateOf<String?>(null) }
    var locatingFailure by remember { mutableStateOf(false) }
    var report by remember { mutableStateOf(VisionRunStatistics.Report(emptyList(), 0, 0, 0)) }
    var errors by remember { mutableStateOf(RuntimeLogLoadErrors()) }
    var exporting by remember { mutableStateOf(false) }
    var exportRun by remember { mutableStateOf<RuntimeLogFiles.Run?>(null) }
    var actionsExpanded by remember { mutableStateOf(false) }
    var showRunDetails by remember(selected) { mutableStateOf(false) }
    var cleanupIds by remember(projectId) { mutableStateOf<Set<String>?>(null) }
    var cleaning by remember { mutableStateOf(false) }
    var cleanupMessage by remember { mutableStateOf<String?>(null) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        val run = exportRun; exportRun = null
        if (uri != null && run != null) scope.launch {
            exporting = true
            try {
                val result = runtimeLogAttempt {
                    withContext(Dispatchers.IO) {
                        val content = RuntimeLogFiles.export(context.filesDir, run)
                        requireNotNull(context.contentResolver.openOutputStream(uri, "w")).use { it.write(content.toByteArray(Charsets.UTF_8)) }
                    }
                }
                errors = errors.completed(RuntimeLogOperation.EXPORT, result)
                revision++
            } finally {
                exporting = false
            }
        }
    }
    val historyVisible = tab != 0
    LaunchedEffect(revision, projectId, historyVisible) {
        if (!historyVisible) return@LaunchedEffect
        val result = runtimeLogAttempt {
            withContext(Dispatchers.IO) { RuntimeLogFiles.list(context.filesDir).filter { projectId == null || it.metadata["projectId"] == projectId } }
        }
        errors = errors.completed(RuntimeLogOperation.HISTORY, result)
        result.onSuccess { loaded -> runs = loaded; if (selected !in loaded.map { it.id }) selected = loaded.firstOrNull()?.id }
    }
    LaunchedEffect(selected, revision, historyVisible) {
        if (!historyVisible) return@LaunchedEffect
        loadedEvents = null; historyLines = emptyList(); locations = emptyList(); locationMessage = null
        report = VisionRunStatistics.Report(emptyList(), 0, 0, 0)
        errors = errors.copy(snapshot = null)
        selected?.let { id ->
            val result = runtimeLogAttempt { withContext(Dispatchers.IO) {
                val snapshot = RuntimeLogFiles.snapshot(context.filesDir, id, "events.jsonl")
                LogLoadedSnapshot(snapshot.events, VisionRunStatistics.parse(snapshot.events), snapshot.run, RuntimeLogLocation.failures(snapshot.events))
            } }
            errors = errors.completed(RuntimeLogOperation.SNAPSHOT, result)
            result.onSuccess { loaded ->
                loadedEvents = loaded; report = loaded.statistics
                val run = loaded.run
                locations = loaded.locations
                runs = runs.map { if (it.id == run.id) run else it }
            }
        }
    }
    val historyChannel = channel.takeIf { tab == 1 }
    LaunchedEffect(selected, loadedEvents, historyChannel, revision, historyVisible) {
        historyLines = emptyList()
        val loaded = loadedEvents?.takeIf { historyVisible && it.run.id == selected } ?: return@LaunchedEffect
        val requested = historyChannel ?: return@LaunchedEffect
        val result = runtimeLogAttempt { withContext(Dispatchers.IO) {
            if (requested == "script.log") {
                val work = currentCoroutineContext()
                RuntimeScriptLog.history(RuntimeLogFiles.read(context.filesDir, loaded.run.id, requested), loaded.events) { work.ensureActive() }
            } else {
                val content = if (requested == "events.jsonl") RuntimeLogEventCodec.preview(loaded.events)
                    else RuntimeLogFiles.read(context.filesDir, loaded.run.id, requested)
                content.lineSequence().filter(String::isNotBlank).toList()
            }
        } }
        errors = errors.completed(RuntimeLogOperation.SNAPSHOT, result)
        result.onSuccess { historyLines = it }
    }
    val active = runs.firstOrNull { it.id == selected }
    val locate: ((RuntimeLogLocation) -> Unit)? = if (onLocate == null) null else { target ->
        if (active?.metadata?.get("projectId") != projectId || projectId == null) {
            locationMessage = "记录不属于当前项目，不能定位"
        } else {
            locationMessage = onLocate(target)
            if (locationMessage == null) onDismiss()
        }
    }
    val startExport: () -> Unit = {
        active?.let { run ->
            exportRun = run
            export.launch("智构运行-${run.id.take(8)}.txt")
        }
    }
    fun locateLatestFailure() {
        if (onLocate == null || projectId == null || locatingFailure) return
        scope.launch {
            locatingFailure = true
            try {
                val result = runtimeLogAttempt { withContext(Dispatchers.IO) {
                    RuntimeLogFiles.list(context.filesDir).firstOrNull { it.metadata["projectId"] == projectId }
                        ?.let { RuntimeLogFiles.snapshot(context.filesDir, it.id, "events.jsonl") }
                        ?.takeIf { it.run.metadata["projectId"] == projectId }
                        ?.let { RuntimeLogLocation.failures(it.events).firstOrNull() }
                } }
                result.onSuccess { target ->
                    locationMessage = if (target == null) "最近一次运行没有可定位的积木错误" else onLocate(target)
                    if (target != null && locationMessage == null) onDismiss()
                }.onFailure { locationMessage = "读取错误位置失败，请刷新后重试" }
            } finally { locatingFailure = false }
        }
    }
    cleanupIds?.let { ids ->
        StudioConfirmDialog(
            onDismissRequest = { if (!cleaning) cleanupIds = null },
            title = { Text("清理运行日志") },
            text = { Text("删除已选择的 ${ids.size} 次运行记录及其脚本输出、状态和识图指标，无法恢复。\n运行中或尚未写完的记录会保留；不删除项目、图片及已导出的文件。") },
            dismissButton = { LogAction("取消", { cleanupIds = null }, enabled = !cleaning) },
            confirmButton = { LogAction(if (cleaning) "清理中…" else "确认删除", {
                val owner = projectId ?: return@LogAction
                scope.launch {
                    cleaning = true
                    try {
                        val result = runtimeLogAttempt { withContext(Dispatchers.IO) { RuntimeLogFiles.cleanup(context.filesDir, owner, ids) } }
                        result.onSuccess { removed ->
                            cleanupMessage = "已删除 ${removed.deleted.size} 条 · 保留 ${removed.protected.size} 条 · 失败 ${removed.failed.size} 条"
                            runs = runs.filter { it.id !in removed.deleted }
                            if (selected in removed.deleted) {
                                selected = null; loadedEvents = null; historyLines = emptyList()
                                report = VisionRunStatistics.Report(emptyList(), 0, 0, 0)
                            }
                            comparing = false
                        }.onFailure { cleanupMessage = "清理失败，请刷新后重试；未操作项目或导出文件。" }
                        cleanupIds = null
                        revision++
                    } finally { cleaning = false }
                }
            }, enabled = !cleaning && !exporting && exportRun == null) },
        )
    }
    StudioPanelDialog(title = "运行日志", onDismiss = onDismiss, fullScreen = fullScreen, showFooter = false,
        compactHeader = true, onToggleFullScreen = { fullScreen = !fullScreen }) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val bodyHeight = studioPanelViewportHeight(maxHeight.value, runtimeLogBodyHeightDp(LocalConfiguration.current.screenHeightDp), fullScreen).dp
            val toolbarBudget = studioToolbarBudget(bodyHeight.value, if (tab == 0) 1 else 2).dp
            Column(Modifier.fillMaxWidth().height(bodyHeight).padding(horizontal = if (fullScreen) 8.dp else 10.dp)) {
            Column(Modifier.fillMaxWidth().heightIn(max = toolbarBudget).verticalScroll(rememberScrollState())) {
            Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                StudioToolSegments((if (tab == 2) 1 else tab).toString(), listOf("0" to "实时", "1" to "历史"), Modifier.weight(1f)) {
                    tab = it.toInt(); diagnostics = false; channel = "script.log"
                }
                Box {
                    LogAction("更多 ▾", { actionsExpanded = true })
                    DropdownMenu(actionsExpanded, { actionsExpanded = false }, modifier = Modifier.heightIn(max = 280.dp), containerColor = Color.White) {
                        DropdownMenuItem(text = { LogText(if (diagnostics) "返回脚本日志" else "查看内部诊断") }, onClick = {
                            actionsExpanded = false; diagnostics = !diagnostics
                            if (tab != 0) { tab = 1; channel = if (diagnostics) "diagnostic.log" else "script.log" }
                        })
                        DropdownMenuItem(text = { LogText("识图性能统计") }, onClick = {
                            actionsExpanded = false; tab = 2; diagnostics = false
                        })
                        if (tab == 1) DropdownMenuItem(text = { LogText("执行观测原文") }, onClick = {
                            actionsExpanded = false; channel = "events.jsonl"; diagnostics = false
                        })
                        DropdownMenuItem(text = { LogText("刷新记录") }, enabled = !cleaning, onClick = {
                            actionsExpanded = false; errors = RuntimeLogLoadErrors(); revision++
                        })
                        if (tab == 0 && projectId != null && onLocate != null) DropdownMenuItem(
                            text = { LogText(if (locatingFailure) "定位中…" else "定位最近错误") }, enabled = !locatingFailure,
                            onClick = { actionsExpanded = false; locateLatestFailure() })
                        if (tab == 1 && locate != null) locations.forEachIndexed { index, target ->
                            DropdownMenuItem(text = { LogText(if (index == 0) "定位错误积木" else "定位其他错误 ${index + 1}") },
                                onClick = { actionsExpanded = false; locate(target) })
                        }
                        if (debugSettings != null && onSaveDebugSettings != null) DropdownMenuItem(text = { LogText("记录设置") },
                            enabled = settingsEditable, onClick = { actionsExpanded = false; recordingSettings = true })
                        if (tab != 0 && active != null) {
                            DropdownMenuItem(text = { LogText(if (showRunDetails) "收起记录详情" else "记录详情") },
                                onClick = { actionsExpanded = false; showRunDetails = !showRunDetails })
                            DropdownMenuItem(text = { LogText(if (exporting) "导出中…" else "导出完整日志") },
                                enabled = !exporting && exportRun == null, onClick = { actionsExpanded = false; startExport() })
                        }
                        if (tab == 2) {
                            DropdownMenuItem(text = { LogText("对比两次运行") }, enabled = active != null && runs.size > 1,
                                onClick = { actionsExpanded = false; comparing = true })
                            DropdownMenuItem(text = { LogText(if (screenshotOverview) "查看分组统计" else "截图统计总览") }, onClick = {
                                actionsExpanded = false; screenshotOverview = !screenshotOverview
                                if (screenshotOverview) fullScreen = true
                            })
                        }
                        if (tab != 0) {
                            HorizontalDivider(color = LogBorder)
                            val canClean = projectId != null && runs.isNotEmpty() && !cleaning && !exporting && exportRun == null
                            DropdownMenuItem(text = { LogText("删除所选记录") }, enabled = canClean && active != null,
                                onClick = { actionsExpanded = false; cleanupIds = active?.let { setOf(it.id) } })
                            DropdownMenuItem(text = { LogText("清理项目历史") }, enabled = canClean,
                                onClick = { actionsExpanded = false; cleanupIds = runs.map { it.id }.toSet() })
                        }
                    }
                }
            }
            cleanupMessage?.let { LogText(it, color = Color.Gray, size = 10, maxLines = 2) }
            locationMessage?.let { LogText(it, color = Color.Red, size = 10, maxLines = 2) }
            errors.visible(historyVisible = tab != 0)?.let { LogText(it, color = Color.Red, maxLines = 2) }
            }
            if (tab == 0) key(diagnostics) {
                Column(Modifier.weight(1f)) {
                    if (diagnostics) LogText("内部诊断 · Runner / Root / App", color = Color.Gray, size = 10)
                    Box(Modifier.weight(1f)) { LogConsolePanel(currentLines,
                        initialChannel = if (diagnostics) RuntimeConsoleChannel.DIAGNOSTIC else RuntimeConsoleChannel.ESSENTIAL) }
                }
            }
            else {
                if (runs.isEmpty()) Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    LogText("暂无文件日志，运行一次后刷新", color = Color.Gray)
                } else {
                    Column(Modifier.fillMaxWidth().heightIn(max = toolbarBudget).verticalScroll(rememberScrollState())) {
                    LogRunSelector(runs, selected, onSelect = { selected = it })
                    active?.let { run ->
                        if (showRunDetails) LogText(RuntimeLogHealth.runSummary(run.metadata), size = 9, color = Color.Gray,
                            maxLines = 1, modifier = Modifier.padding(vertical = 1.dp))
                        val dropped = run.metadata["droppedRecords"]?.toLongOrNull() ?: 0
                        val truncated = run.metadata["truncatedRecords"]?.toLongOrNull() ?: 0
                        val evicted = run.metadata["evictedEventRecords"]?.toLongOrNull() ?: 0
                        val engineDropped = run.metadata["visionEngineDropped"]?.toLongOrNull() ?: report.dropped
                        if (run.metadata["writerError"] == "true") LogText("写盘错误，观测可能不完整", color = Color.Red, size = 10)
                        if (dropped + truncated + engineDropped + report.malformed > 0) LogText("非完整：丢弃 $dropped / 限额 $truncated / 引擎丢弃 $engineDropped / 无效 ${report.malformed}", color = Color.Red, size = 10)
                        if (showRunDetails && evicted > 0) LogText("已轮转 $evicted 条明细 · 次数为累计，百分位仅保留窗口", color = Color.Gray, size = 9)
                    }
                    if (tab == 1 && channel != "script.log") Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        LogText(if (channel == "diagnostic.log") "内部诊断" else "执行观测原文", Modifier.weight(1f), color = Color.Gray, size = 10)
                        LogAction("返回脚本", { channel = "script.log"; diagnostics = false })
                    }
                    }
                    if (tab == 1) {
                        key(channel) {
                            Box(Modifier.weight(1f)) { LogConsolePanel(historyLines, history = true,
                                initialChannel = if (channel == "script.log") RuntimeConsoleChannel.ESSENTIAL else RuntimeConsoleChannel.ALL,
                                clockOffsetMs = active?.metadata?.let { meta ->
                                    val wall = meta["startedAtMs"]?.toLongOrNull()
                                    val elapsed = meta["startedElapsedMs"]?.toLongOrNull()
                                    if (wall != null && elapsed != null && elapsed in 0..wall) wall - elapsed else null
                                }) }
                        }
                    } else {
                        LogText(if (screenshotOverview) RuntimeLogHealth.visionSummary(report, active?.metadata.orEmpty()) else
                            "耗时 ms · 工作量按行单位 · 同条件/结果分组", size = 10, color = Color.Gray, modifier = Modifier.padding(vertical = 1.dp))
                        val sections = remember(report) { VisionLogPresentation.sections(report) }
                        key(selected) {
                            if (screenshotOverview && sections.isNotEmpty()) {
                                Box(Modifier.weight(1f)) { LogScreenshotOverview(sections) }
                            } else {
                                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp), contentPadding = PaddingValues(vertical = 2.dp)) {
                                    if (sections.isEmpty()) item { LogText("暂无识图指标，请开启识图耗时统计后运行。\n追踪 ${report.traceCount} 次积木进入。", modifier = Modifier.padding(8.dp)) }
                                    itemsIndexed(sections, key = { _, section -> section.configuration }) { index, section ->
                                        LogStatisticsCard(section, index, locate)
                                    }
                                }
                            }
                        }
                    }

                }
            }
            }
        }
    }
    if (comparing && selected != null) LogComparisonDialog(runs, selected!!, revision, onDismiss = { comparing = false })
}

@Composable
private fun LogComparisonDialog(runs: List<RuntimeLogFiles.Run>, current: String, revision: Int, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val candidates = runs.filter { it.id != current }
    var baseline by remember(current) { mutableStateOf(candidates.firstOrNull()?.id) }
    var comparison by remember { mutableStateOf<VisionRunComparison.Report?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var refresh by remember { mutableStateOf(0) }
    var copying by remember { mutableStateOf(false) }
    var copyFailure by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(current, baseline, revision, refresh) {
        comparison = null; failure = null; copyFailure = null
        val previous = baseline ?: return@LaunchedEffect
        loading = true
        try {
            runtimeLogAttempt {
                withContext(Dispatchers.IO) {
                    fun sample(id: String): VisionRunComparison.Sample {
                        val snapshot = RuntimeLogFiles.snapshot(context.filesDir, id, "events.jsonl")
                        return VisionRunComparison.Sample(snapshot.run.metadata, VisionRunStatistics.parse(snapshot.events))
                    }
                    VisionRunComparison.compare(sample(previous), sample(current))
                }
            }.onSuccess { comparison = it }.onFailure { failure = "读取对照日志失败，请重新选择或刷新。" }
        } finally { loading = false }
    }
    var fullScreen by remember { mutableStateOf(false) }
    StudioPanelDialog(title = "同记录条件对比", onDismiss = onDismiss, fullScreen = fullScreen, onToggleFullScreen = { fullScreen = !fullScreen }) {
        Column(Modifier.fillMaxWidth().then(if (fullScreen) Modifier.fillMaxHeight() else Modifier.heightIn(max = 520.dp)).padding(horizontal = 10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                LogText("基线运行", Modifier.weight(1f), color = Color.Gray)
                LogAction("刷新", { refresh++ }, enabled = !loading)
            }
            LogRunSelector(candidates, baseline, onSelect = { baseline = it })
            LogText("当前：${runs.firstOrNull { it.id == current }?.let(::logRunLabel).orEmpty()}", size = 10, maxLines = 1)
            if (loading) LogText("正在读取对照快照…", color = Color.Gray)
            failure?.let { LogText(it, color = Color.Red) }
            copyFailure?.let { LogText(it, color = if (it.startsWith("已")) LogBlue else Color.Red, size = 10) }
            comparison?.let { report ->
                LogAction(if (copying) "复制中…" else "复制对比报告", {
                    scope.launch {
                        copying = true; copyFailure = null
                        try {
                            val result = runtimeLogAttempt { withContext(Dispatchers.IO) { report.clipboardText() } }
                            if (comparison === report) {
                                result.onSuccess { content ->
                                    copyFailure = runtimeLogClipboardStatus(content,
                                        { clipboard.setText(androidx.compose.ui.text.AnnotatedString(it)) },
                                        { clipboard.getText()?.text })
                                }.onFailure { copyFailure = "报告超过复制容量或生成失败，请导出原日志查看。" }
                            }
                        } finally { copying = false }
                    }
                }, enabled = !copying)
                LazyColumn(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    report.blocked?.let { item { LogText(it, color = Color.Red) } }
                    itemsIndexed(report.warnings) { _, warning -> LogText(warning, color = Color.Gray, size = 10) }
                    itemsIndexed(report.rows) { index, row ->
                        Column(Modifier.fillMaxWidth().border(1.dp, LogBorder, RoundedCornerShape(5.dp)).padding(6.dp)) {
                            LogText("${index + 1}. ${VisionLogPresentation.algorithm(row.after.algorithm)} · ${VisionLogPresentation.status(row.after.status)}",
                                color = LogBlue, bold = true, maxLines = 2)
                            LogText("样本 ${row.before.count} → ${row.after.count} · ${row.after.unit}", size = 10, color = Color.Gray)
                            LogText(VisionLogPresentation.summary(row.after.parameters), size = 9, color = Color.Gray, maxLines = 2)
                            listOf("P50" to (row.before.p50Ms to row.after.p50Ms), "P95" to (row.before.p95Ms to row.after.p95Ms),
                                "P99" to (row.before.p99Ms to row.after.p99Ms)).forEach { (label, values) ->
                                Row(Modifier.fillMaxWidth()) {
                                    LogText(label, Modifier.width(32.dp), size = 10)
                                    LogText(String.format(Locale.ROOT, "%.2f → %.2f", values.first, values.second), Modifier.weight(1f), size = 10)
                                    LogText(row.change(values.first, values.second), size = 10, align = TextAlign.End)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Compact paged sheet rather than two independently expanded scrolling cards. */
@Composable
private fun LogScreenshotOverview(sections: List<VisionLogPresentation.Section>) {
    var selectedCondition by remember(sections) { mutableStateOf(sections.indexOfFirst { !VisionLogPresentation.isCapture(it) }.coerceAtLeast(0)) }
    var dropdown by remember { mutableStateOf(false) }
    var page by remember(sections, selectedCondition) { mutableStateOf(0) }
    val section = sections[selectedCondition]
    val displayed = remember(sections, section) { VisionLogPresentation.screenshotSections(sections, section) }
    val hasTransfer = displayed.any { it.rows.any(VisionLogPresentation::isTransferDetail) }
    var transferOnly by remember(displayed) { mutableStateOf(false) }
    val lines = remember(displayed, transferOnly) { VisionLogPresentation.screenshotLines(displayed, transferOnly) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().height(28.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                LogAction("${section.parameters["flowId"] ?: "识图"} · 条件 ${selectedCondition + 1} ▾", { dropdown = true },
                    Modifier.fillMaxWidth(), selected = true, align = TextAlign.Start)
                DropdownMenu(dropdown, { dropdown = false }, modifier = Modifier.heightIn(max = 240.dp), containerColor = Color.White) {
                    sections.forEachIndexed { index, condition ->
                        DropdownMenuItem(text = { LogText("条件 ${index + 1} · ${if (VisionLogPresentation.isCapture(condition)) "截图" else "识图"}") },
                            onClick = { selectedCondition = index; dropdown = false })
                    }
                }
            }
            if (hasTransfer) LogAction(if (transferOnly) "主指标" else "传输明细", { transferOnly = !transferOnly; page = 0 }, Modifier.width(62.dp), selected = transferOnly)
            else LogText(if (displayed.size > 1) "附同积木截图统计" else "单独条件统计", size = 9, color = Color.Gray)
        }
        LogText(VisionLogPresentation.summary(section.parameters), size = 9, maxLines = 2, color = Color.Gray)
        LogText(listOfNotNull(section.parameters["templateSizes"]?.let { "尺寸 $it" },
            section.parameters["tolerance"]?.let { "容差 $it" }, section.parameters["direction"]?.let { "方向 $it" },
            displayed.firstOrNull { VisionLogPresentation.isCapture(it) }?.let { capture ->
                VisionLogPresentation.summary(capture.parameters).substringAfter(" · ", "")
            }?.takeIf(String::isNotBlank)).joinToString(" · "), size = 9, maxLines = 2, color = Color.Gray)
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val capacity = VisionLogPresentation.screenshotPageCapacity(maxHeight.value.toInt() - 44, LocalConfiguration.current.fontScale)
            val pages = VisionLogPresentation.screenshotPages(lines, capacity)
            val current = page.coerceIn(0, pages.lastIndex)
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().height(18.dp), verticalAlignment = Alignment.CenterVertically) {
                    LogText("指标 / ms / 行单位", Modifier.weight(1.65f), size = 9, color = Color.Gray)
                    LogText("次数", Modifier.weight(.45f), size = 9, color = Color.Gray, align = TextAlign.Center)
                    listOf("P50", "P95", "P99").forEach { LogText(it, Modifier.weight(.8f), size = 9, color = Color.Gray, align = TextAlign.End) }
                }
                LazyColumn(Modifier.weight(1f)) {
                    itemsIndexed(pages[current]) { _, line ->
                        if (line.heading != null) {
                            Box(Modifier.fillMaxWidth().heightIn(min = 14.dp).background(Color(0xFFF0F4FF))) {
                                LogText(line.heading, size = 9, color = LogBlue, bold = true)
                            }
                        } else line.group?.let { group ->
                            Row(Modifier.fillMaxWidth().heightIn(min = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                                LogText(VisionLogPresentation.algorithm(group.algorithm), Modifier.weight(1.65f), size = 9, maxLines = 1)
                                LogText(group.count.toString(), Modifier.weight(.45f), size = 9, align = TextAlign.Center)
                                listOf(group.p50Ms, group.p95Ms, group.p99Ms).forEachIndexed { column, value ->
                                    LogText(String.format(Locale.ROOT, if (group.unit == "count") "%.0f" else "%.2f", value),
                                        Modifier.weight(.8f), size = 9, align = TextAlign.End, bold = column == 0)
                                }
                            }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().height(26.dp), verticalAlignment = Alignment.CenterVertically) {
                    LogText(if (pages.size == 1) "${if (transferOnly) "传输明细" else if (hasTransfer) "主指标" else "全部指标"} · 1 页" else "指标 ${current + 1}/${pages.size} 页 · 分页保留全部数据", Modifier.weight(1f), size = 9, color = Color.Gray)
                    if (pages.size > 1) {
                        LogAction("‹", { page = current - 1 }, Modifier.width(28.dp), enabled = current > 0)
                        LogAction("›", { page = current + 1 }, Modifier.width(28.dp), enabled = current < pages.lastIndex)
                    }
                }
            }
        }
    }
}

private fun logRunLabel(run: RuntimeLogFiles.Run): String {
    val date = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date(run.metadata["startedAtMs"]?.toLongOrNull() ?: 0))
    val status = when (run.metadata["status"]) { "running" -> "运行中"; "ended" -> "已结束"; "stopped" -> "主动停止"; "failed" -> "失败"; "start_failed" -> "启动失败"; else -> "中断" }
    return "$date · $status"
}

@Composable
private fun LogRunSelector(runs: List<RuntimeLogFiles.Run>, selected: String?, onSelect: (String) -> Unit) {
    val options = remember(runs) { runs.map { it.id to logRunLabel(it) } }
    StudioCompactChoice("记录", selected.orEmpty(), options, runs.isNotEmpty(), onSelect)
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

@Composable
private fun LogStatisticsCard(section: VisionLogPresentation.Section, index: Int, onLocate: ((RuntimeLogLocation) -> Unit)? = null) {
    var expanded by remember(section.configuration) { mutableStateOf(false) }
    var parametersExpanded by remember(section.configuration) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().border(1.dp, LogBorder, RoundedCornerShape(6.dp)).padding(horizontal = 7.dp, vertical = 3.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            LogText("${section.parameters["flowId"] ?: "识图"} · 条件 ${index + 1}", Modifier.weight(1f), color = LogBlue, maxLines = 1, bold = true)
            RuntimeLogLocation.from(section.parameters)?.let { target ->
                if (onLocate != null) LogAction("定位", { onLocate(target) }, Modifier.width(40.dp))
            }
            LogAction(if (expanded) "收起 ▴" else "详情 ▾", { expanded = !expanded }, Modifier.width(52.dp))
        }
        val summary = VisionLogPresentation.summary(section.parameters)
        if (summary.isNotBlank()) LogText(summary, color = Color.Gray, size = 10, maxLines = 1)
        VisionLogPresentation.outcomes(section, expanded).forEach { outcome ->
            val failure = outcome.status in listOf("error", "timeout", "budget_exceeded")
            Row(Modifier.fillMaxWidth().heightIn(min = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                LogText(VisionLogPresentation.status(outcome.status), Modifier.weight(1.65f), color = if (failure) Color(0xFFC33F46) else LogBlue, size = 10, bold = true)
                LogText("次数", Modifier.weight(.45f), color = Color.Gray, size = 10, align = TextAlign.Center)
                listOf("P50", "P95", "P99").forEach { LogText(it, Modifier.weight(.8f), color = Color.Gray, size = 10, align = TextAlign.End) }
            }
            outcome.rows.forEach { group ->
                HorizontalDivider(color = LogBorder)
                Row(Modifier.fillMaxWidth().heightIn(min = 18.dp).padding(vertical = 1.dp), verticalAlignment = Alignment.CenterVertically) {
                    LogText(VisionLogPresentation.algorithm(group.algorithm), Modifier.weight(1.65f), color = if (failure) Color(0xFFC33F46) else Color(0xFF30384B), size = 10, maxLines = 2)
                    LogText(group.count.toString(), Modifier.weight(.45f), size = 10, align = TextAlign.Center)
                    listOf(group.p50Ms, group.p95Ms, group.p99Ms).forEachIndexed { column, value ->
                        LogText(String.format(Locale.ROOT, if (group.unit == "count") "%.0f" else "%.2f", value), Modifier.weight(.8f), size = 10, align = TextAlign.End, bold = column == 0)
                    }
                }
            }
        }
        if (expanded) {
            Row(Modifier.fillMaxWidth().heightIn(min = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                LogText("分段百分位不可相加", size = 10, color = Color.Gray, modifier = Modifier.weight(1f))
                LogText(if (parametersExpanded) "收起参数" else "参数/口径", color = LogBlue, size = 10,
                    modifier = Modifier.clickable { parametersExpanded = !parametersExpanded }.padding(horizontal = 4.dp, vertical = 2.dp))
            }
            if (parametersExpanded) {
                LogText("最近秩百分位。遍历/比较为合计，准备含锁等待与收尾；工作量不是毫秒。桥接收与桥处理重叠。", size = 10, color = Color.Gray)
                LogText(VisionLogPresentation.details(section), size = 10, color = Color.Gray, modifier = Modifier.padding(bottom = 3.dp))
            }
        }
    }
}

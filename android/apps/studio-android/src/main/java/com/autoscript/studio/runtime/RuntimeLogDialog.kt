package com.autoscript.studio

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private data class LogLoadedSnapshot(val content: String, val statistics: VisionRunStatistics.Report,
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
    var text by remember { mutableStateOf("") }
    var locations by remember { mutableStateOf<List<RuntimeLogLocation>>(emptyList()) }
    var locationMessage by remember { mutableStateOf<String?>(null) }
    var report by remember { mutableStateOf(VisionRunStatistics.Report(emptyList(), 0, 0, 0)) }
    var errors by remember { mutableStateOf(RuntimeLogLoadErrors()) }
    var exporting by remember { mutableStateOf(false) }
    var exportRun by remember { mutableStateOf<RuntimeLogFiles.Run?>(null) }
    var cleanupMenu by remember { mutableStateOf(false) }
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
    LaunchedEffect(revision, projectId) {
        val result = runtimeLogAttempt {
            withContext(Dispatchers.IO) { RuntimeLogFiles.list(context.filesDir).filter { projectId == null || it.metadata["projectId"] == projectId } }
        }
        errors = errors.completed(RuntimeLogOperation.HISTORY, result)
        result.onSuccess { loaded -> runs = loaded; if (selected !in loaded.map { it.id }) selected = loaded.firstOrNull()?.id }
    }
    LaunchedEffect(selected, channel, revision) {
        text = ""; locations = emptyList(); locationMessage = null
        report = VisionRunStatistics.Report(emptyList(), 0, 0, 0)
        errors = errors.copy(snapshot = null)
        selected?.let { id ->
            val result = runtimeLogAttempt { withContext(Dispatchers.IO) {
                val snapshot = RuntimeLogFiles.snapshot(context.filesDir, id, channel)
                val content = if (channel == "events.jsonl") RuntimeLogEventCodec.preview(snapshot.events) else snapshot.text
                LogLoadedSnapshot(content, VisionRunStatistics.parse(snapshot.events), snapshot.run, RuntimeLogLocation.failures(snapshot.events))
            } }
            errors = errors.completed(RuntimeLogOperation.SNAPSHOT, result)
            result.onSuccess { loaded ->
                text = loaded.content; report = loaded.statistics
                val run = loaded.run
                locations = loaded.locations
                runs = runs.map { if (it.id == run.id) run else it }
            }
        }
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
                                selected = null; text = ""; report = VisionRunStatistics.Report(emptyList(), 0, 0, 0)
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
        Column(Modifier.fillMaxWidth().then(if (fullScreen) Modifier.fillMaxHeight() else
            Modifier.height(runtimeLogBodyHeightDp(LocalConfiguration.current.screenHeightDp).dp)).padding(horizontal = if (fullScreen) 8.dp else 10.dp)) {
            Row(Modifier.fillMaxWidth().height(30.dp), verticalAlignment = Alignment.CenterVertically) {
                listOf("实时", "历史", "识图统计").forEachIndexed { index, label ->
                    LogAction(label, { tab = index }, Modifier.weight(1f), selected = tab == index)
                }
                LogAction("刷新", { errors = RuntimeLogLoadErrors(); revision++ }, Modifier.width(42.dp))
                if (tab != 0) Box {
                    LogAction(if (cleaning) "清理中" else "清理", { cleanupMenu = true }, Modifier.width(42.dp),
                        enabled = projectId != null && runs.isNotEmpty() && !cleaning && !exporting && exportRun == null)
                    DropdownMenu(cleanupMenu, { cleanupMenu = false }, containerColor = Color.White) {
                        DropdownMenuItem(text = { LogText("删除所选记录") }, enabled = active != null,
                            onClick = { cleanupIds = active?.let { setOf(it.id) }; cleanupMenu = false })
                        DropdownMenuItem(text = { LogText("清理当前项目历史") },
                            onClick = { cleanupIds = runs.map { it.id }.toSet(); cleanupMenu = false })
                    }
                }
            }
            if (tab == 2 || debugSettings != null && onSaveDebugSettings != null)
                Row(Modifier.fillMaxWidth().height(28.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (debugSettings != null && onSaveDebugSettings != null)
                        LogAction("记录设置", { recordingSettings = true }, Modifier.width(62.dp), enabled = settingsEditable)
                    Spacer(Modifier.weight(1f))
                    if (tab == 2) {
                        LogAction("对比", { comparing = true }, Modifier.width(42.dp), enabled = active != null && runs.size > 1)
                        LogAction(if (screenshotOverview) "详情" else "截图总览", {
                            screenshotOverview = !screenshotOverview
                            if (screenshotOverview) fullScreen = true
                        }, Modifier.width(62.dp), selected = screenshotOverview)
                        if (screenshotOverview) LogAction(if (exporting) "导出中" else "导出", startExport, Modifier.width(40.dp),
                            enabled = active != null && !exporting && exportRun == null)
                    }
                }
            cleanupMessage?.let { LogText(it, color = Color.Gray, size = 10, maxLines = 2) }
            locationMessage?.let { LogText(it, color = Color.Red, size = 10, maxLines = 2) }
            errors.visible(historyVisible = tab != 0)?.let { LogText(it, color = Color.Red, maxLines = 2) }
            if (tab == 0) Box(Modifier.weight(1f)) { LogConsolePanel(currentLines) }
            else {
                if (runs.isEmpty()) Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    LogText("暂无文件日志，运行一次后刷新", color = Color.Gray)
                } else {
                    LogRunSelector(runs, selected, onSelect = { selected = it })
                    active?.let { run ->
                        LogText(RuntimeLogHealth.runSummary(run.metadata), size = 9, color = Color.Gray,
                            maxLines = 1, modifier = Modifier.padding(vertical = 1.dp))
                        val dropped = run.metadata["droppedRecords"]?.toLongOrNull() ?: 0
                        val truncated = run.metadata["truncatedRecords"]?.toLongOrNull() ?: 0
                        val evicted = run.metadata["evictedEventRecords"]?.toLongOrNull() ?: 0
                        val engineDropped = run.metadata["visionEngineDropped"]?.toLongOrNull() ?: report.dropped
                        if (run.metadata["writerError"] == "true") LogText("写盘错误，观测可能不完整", color = Color.Red, size = 10)
                        if (dropped + truncated + engineDropped + report.malformed > 0) LogText("非完整：丢弃 $dropped / 限额 $truncated / 引擎丢弃 $engineDropped / 无效 ${report.malformed}", color = Color.Red, size = 10)
                        if (evicted > 0) LogText("已轮转 $evicted 条明细 · 次数为累计，百分位仅保留窗口", color = Color.Gray, size = 9)
                    }
                    if (tab == 1) {
                        if (locate != null && locations.isNotEmpty()) {
                            var expanded by remember(selected) { mutableStateOf(false) }
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                LogText("失败位置 · 仅定位当前草稿", Modifier.weight(1f), size = 10, color = Color.Gray)
                                LogAction("定位", { locate(locations.first()) })
                                if (locations.size > 1) Box {
                                    LogAction("其他 ${locations.size - 1}", { expanded = true })
                                    DropdownMenu(expanded, { expanded = false }, modifier = Modifier.heightIn(max = 200.dp)) {
                                        locations.drop(1).forEach { target ->
                                            DropdownMenuItem(text = { LogText("${target.flowId} · ${target.nodeId}", maxLines = 2) },
                                                onClick = { expanded = false; locate(target) })
                                        }
                                    }
                                }
                            }
                        }
                        Row(Modifier.fillMaxWidth().height(30.dp)) {
                            listOf("script.log" to "脚本", "diagnostic.log" to "状态", "events.jsonl" to "追踪/指标").forEach { (file, label) ->
                                LogAction(label, { channel = file }, Modifier.weight(1f), selected = channel == file)
                            }
                        }
                        Box(Modifier.weight(1f)) { LogConsolePanel(text.lineSequence().filter(String::isNotBlank).toList(), history = true) }
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
                    if (!(tab == 2 && screenshotOverview)) Row(Modifier.fillMaxWidth().height(28.dp), verticalAlignment = Alignment.CenterVertically) {
                        LogText("含脚本输出，请注意隐私", color = Color.Gray, size = 10, modifier = Modifier.weight(1f), maxLines = 1)
                        LogAction(if (exporting) "导出中…" else "导出日志", enabled = active != null && !exporting && exportRun == null, onClick = startExport)
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

/** Script output and diagnostics stay separate from the user-facing run prompt window. */
@Composable
private fun LogConsolePanel(lines: List<String>, history: Boolean = false) {
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var failuresOnly by remember { mutableStateOf(false) }
    var copying by remember { mutableStateOf(false) }
    var copyMessage by remember { mutableStateOf<String?>(null) }
    var follow by remember { mutableStateOf(true) }
    var channel by remember { mutableStateOf(RuntimeConsoleChannel.ALL) }
    var detail by remember { mutableStateOf<String?>(null) }
    val clipboard = LocalClipboardManager.current
    // SnapshotStateList is mutable: using its identity as a remember key freezes the first result.
    val snapshot = lines.toList()
    val visible = remember(snapshot, query, failuresOnly, channel, history) {
        filterRuntimeConsole(snapshot, query, failuresOnly, if (history) RuntimeConsoleChannel.ALL else channel)
    }
    val listState = rememberLazyListState()
    LaunchedEffect(visible.size, visible.lastOrNull(), follow) {
        if (follow && visible.isNotEmpty()) listState.animateScrollToItem(visible.lastIndex)
    }
    Column(Modifier.fillMaxSize()) {
        if (!history) Row(Modifier.fillMaxWidth().height(28.dp), verticalAlignment = Alignment.CenterVertically) {
            RuntimeConsoleChannel.entries.forEach { option ->
                LogAction(option.label, { channel = option }, Modifier.weight(1f), selected = channel == option)
            }
        }
        Row(Modifier.fillMaxWidth().height(30.dp), verticalAlignment = Alignment.CenterVertically) {
            LogText("${visible.size} 项", Modifier.weight(1f), size = 10, color = Color.Gray)
            LogAction("仅错误", { failuresOnly = !failuresOnly }, selected = failuresOnly)
            LogAction(if (follow) "跟随 ✓" else "跟随", { follow = !follow }, selected = follow)
            LogAction(if (copying) "复制中" else "复制", enabled = visible.isNotEmpty() && !copying, onClick = {
                val selection = visible.toList()
                scope.launch {
                    copying = true; copyMessage = null
                    try {
                        val result = runtimeLogAttempt { withContext(Dispatchers.IO) { runtimeLogCopyText(selection) } }
                        copyMessage = result.fold({ content -> runtimeLogClipboardStatus(content,
                            { clipboard.setText(androidx.compose.ui.text.AnnotatedString(it)) }, { clipboard.getText()?.text })
                            .replace("完整报告", "完整日志") }, { "日志超过复制容量或生成失败，请筛选后复制或导出。" })
                    } finally { copying = false }
                }
            })
        }
        copyMessage?.let { LogText(it, size = 10, maxLines = 2, color = if (it.startsWith("已")) LogBlue else Color.Red) }
        BasicTextField(query, { query = it.take(128) }, singleLine = true,
            textStyle = TextStyle(fontSize = 11.sp, lineHeight = 14.sp, platformStyle = PlatformTextStyle(includeFontPadding = false)),
            modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp).height(30.dp)
                .border(1.dp, LogBorder, RoundedCornerShape(5.dp)).padding(horizontal = 7.dp),
            decorationBox = { field -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) {
                if (query.isEmpty()) LogText(if (history) "筛选本次记录" else "筛选实时日志（最近 200 项）", color = Color.Gray, maxLines = 1)
                field()
            } })
        if (visible.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 10.dp), contentAlignment = Alignment.Center) {
                LogText(if (lines.isEmpty()) "暂无运行记录\n运行提示在独立提示窗显示" else "没有符合筛选的日志", color = Color.Gray, align = TextAlign.Center)
            }
        } else {
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState) {
                itemsIndexed(visible) { _, line ->
                    Row(Modifier.fillMaxWidth().clickable { detail = line }.padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        LogText(line, Modifier.weight(1f), size = 10, maxLines = 2,
                            color = if (editorConsoleLineIsFailure(line)) Color(0xFFC33F46) else Color(0xFF30384B))
                        LogText("›", Modifier.padding(horizontal = 5.dp), size = 12, color = LogBlue)
                    }
                }
            }
        }
    }
    detail?.let { selectedLine -> LogLineDetailDialog(selectedLine, onDismiss = { detail = null }) }
}

@Composable
private fun LogLineDetailDialog(line: String, onDismiss: () -> Unit) {
    var fullScreen by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    val clipboard = LocalClipboardManager.current
    StudioPanelDialog("日志详情", onDismiss, fullScreen = fullScreen, compactHeader = true,
        onToggleFullScreen = { fullScreen = !fullScreen }, showFooter = false) {
        Column(Modifier.fillMaxWidth().then(if (fullScreen) Modifier.fillMaxHeight() else Modifier.heightIn(max = 420.dp)).padding(10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                LogText("选中记录 · 原文", Modifier.weight(1f), size = 10, color = Color.Gray)
                LogAction("复制", {
                    status = runCatching { runtimeLogCopyText(listOf(line)) }.fold({ content ->
                        runtimeLogClipboardStatus(content, { clipboard.setText(androidx.compose.ui.text.AnnotatedString(it)) },
                            { clipboard.getText()?.text }).replace("完整报告", "日志原文")
                    }, { "此条日志超过复制容量，请导出原日志。" })
                })
            }
            status?.let { LogText(it, size = 10, maxLines = 2) }
            SelectionContainer(Modifier.weight(1f, fill = false).fillMaxWidth()) {
                androidx.compose.foundation.lazy.LazyColumn {
                    item { LogText(line, Modifier.fillMaxWidth().padding(vertical = 4.dp), size = 11) }
                }
            }
        }
    }
}

private fun logRunLabel(run: RuntimeLogFiles.Run): String {
    val date = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date(run.metadata["startedAtMs"]?.toLongOrNull() ?: 0))
    val status = when (run.metadata["status"]) { "running" -> "运行中 / 未完整结束"; "ended" -> "已结束"; "stopped" -> "主动停止"; "failed" -> "失败"; "start_failed" -> "启动失败"; else -> "中断" }
    return "$date · $status"
}

@Composable
private fun LogRunSelector(runs: List<RuntimeLogFiles.Run>, selected: String?, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val index = runs.indexOfFirst { it.id == selected }
    Row(Modifier.fillMaxWidth().height(30.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) {
            LogAction(runs.getOrNull(index)?.let(::logRunLabel) ?: "选择运行", { expanded = true }, Modifier.fillMaxWidth(), selected = true, align = TextAlign.Start)
            DropdownMenu(expanded, { expanded = false }, modifier = Modifier.heightIn(max = 240.dp), containerColor = Color.White) {
                runs.forEach { run ->
                    DropdownMenuItem(text = { LogText(logRunLabel(run), color = if (run.id == selected) LogBlue else Color.DarkGray) },
                        onClick = { onSelect(run.id); expanded = false })
                }
            }
        }
        LogAction("‹", { onSelect(runs[index - 1].id) }, Modifier.width(30.dp), enabled = index > 0)
        LogAction("›", { onSelect(runs[index + 1].id) }, Modifier.width(30.dp), enabled = index >= 0 && index < runs.lastIndex)
    }
}

private val LogBlue = Color(0xFF3867FF)
private val LogBorder = Color(0xFFE6EAF2)

@Composable
private fun LogText(text: String, modifier: Modifier = Modifier, color: Color = Color(0xFF30384B), size: Int = 11,
    maxLines: Int = Int.MAX_VALUE, align: TextAlign = TextAlign.Start, bold: Boolean = false) {
    Text(text, modifier, color = color, maxLines = maxLines, overflow = TextOverflow.Ellipsis, textAlign = align,
        style = TextStyle(fontSize = size.sp, lineHeight = (size + 3).sp, fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal,
            platformStyle = PlatformTextStyle(includeFontPadding = false)))
}

@Composable
private fun LogAction(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, selected: Boolean = false,
    enabled: Boolean = true, align: TextAlign = TextAlign.Center) {
    Box(modifier.heightIn(min = 28.dp).background(if (selected) Color(0xFFF0F4FF) else Color.Transparent, RoundedCornerShape(5.dp))
        .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 6.dp),
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

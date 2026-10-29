package com.autoscript.studio

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class LogConsoleFilter(val lines: List<String>, val query: String, val failuresOnly: Boolean,
    val channel: RuntimeConsoleChannel)

@Composable
internal fun LogConsolePanel(lines: List<String>, history: Boolean = false,
    initialChannel: RuntimeConsoleChannel = RuntimeConsoleChannel.ESSENTIAL, clockOffsetMs: Long? = null) {
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var filterKey by remember(initialChannel) { mutableStateOf(initialChannel.name) }
    val failuresOnly = filterKey == "errors"
    val channel = RuntimeConsoleChannel.entries.firstOrNull { it.name == filterKey } ?: initialChannel
    var copying by remember { mutableStateOf(false) }
    var copyMessage by remember { mutableStateOf<String?>(null) }
    var follow by remember { mutableStateOf(true) }
    var detail by remember { mutableStateOf<String?>(null) }
    val clipboard = LocalClipboardManager.current
    val displayClockOffset = if (history) clockOffsetMs else remember { System.currentTimeMillis() - android.os.SystemClock.elapsedRealtime() }
    // SnapshotStateList mutates in place; filtering must use its current immutable contents.
    val snapshot = if (history) lines else lines.toList()
    val filter = remember(snapshot, query, failuresOnly, channel, history) { LogConsoleFilter(snapshot, query, failuresOnly, channel) }
    val filtered = if (!history) remember(filter) {
        filterRuntimeConsole(filter.lines, filter.query, filter.failuresOnly, filter.channel)
    } else {
        val result by produceState<Pair<LogConsoleFilter, List<String>>?>(null, filter) {
            val visible = withContext(Dispatchers.Default) {
                val work = currentCoroutineContext()
                filterRuntimeConsole(filter.lines, filter.query, filter.failuresOnly, filter.channel) { work.ensureActive() }
            }
            value = filter to visible
        }
        result?.takeIf { it.first === filter }?.second
    }
    val visible = filtered.orEmpty()
    val listState = rememberLazyListState()
    val dragging by listState.interactionSource.collectIsDraggedAsState()
    LaunchedEffect(dragging) { if (dragging) follow = false }
    LaunchedEffect(visible.size, visible.lastOrNull(), follow) {
        if (follow && visible.isNotEmpty()) listState.scrollToItem(visible.lastIndex)
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val toolbarBudget = studioToolbarBudget(maxHeight.value).dp
        Column(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxWidth().heightIn(max = toolbarBudget).verticalScroll(rememberScrollState())) {
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        val options = listOf(initialChannel.name to "全部") +
                            (if (initialChannel == RuntimeConsoleChannel.ESSENTIAL) listOf(RuntimeConsoleChannel.SCRIPT.name to "输出") else emptyList()) +
                            listOf("errors" to "错误")
                        options.forEach { (key, title) ->
                            LogAction(title, { filterKey = key }, selected = filterKey == key)
                        }
                    }
                    LogText("${visible.size}条", size = 10, color = Color.Gray, modifier = Modifier.padding(end = 4.dp))
                    LogConsoleIcon(R.drawable.editor_more_search_24, "搜索日志", searching) {
                        searching = !searching
                        if (!searching) query = ""
                    }
                    LogAction(if (follow) "跟随" else "继续", { follow = !follow }, selected = follow)
                    LogConsoleIcon(R.drawable.editor_file_copy_24, if (copying) "复制中" else "复制当前日志", enabled = visible.isNotEmpty() && !copying) {
                        val selection = visible.toList()
                        scope.launch {
                            copying = true; copyMessage = null
                            try {
                                val result = runtimeLogAttempt { withContext(Dispatchers.IO) { runtimeLogCopyText(selection) } }
                                copyMessage = result.fold({ content -> runtimeLogClipboardStatus(content,
                                    { clipboard.setText(androidx.compose.ui.text.AnnotatedString(it)) }, { clipboard.getText()?.text })
                                    .replace("完整报告", "完整日志") }, { "日志超过复制容量，请筛选后复制或导出。" })
                            } finally { copying = false }
                        }
                    }
                }
                if (searching) InspectorSearchField(query, { query = it.take(128) }, "搜索日志原文")
                copyMessage?.let { LogText(it, size = 10, maxLines = 2, color = if (it.startsWith("已")) LogBlue else Color.Red) }
            }
            HorizontalDivider(color = LogBorder)
            if (visible.isEmpty()) Box(Modifier.weight(1f).fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
                LogText(when {
                    filtered == null -> "正在读取…"
                    lines.isEmpty() || channel == RuntimeConsoleChannel.ESSENTIAL && query.isEmpty() ->
                        "运行后显示执行记录、脚本输出和错误"
                    else -> "没有符合条件的日志"
                }, color = Color.Gray, align = androidx.compose.ui.text.style.TextAlign.Center)
            } else LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState, contentPadding = PaddingValues(vertical = 3.dp)) {
                itemsIndexed(visible) { _, line ->
                    val item = remember(line, displayClockOffset) { runtimeLogLinePresentation(line, displayClockOffset) }
                    val tint = when { item.failure -> Color(0xFFC33F46); item.warning -> Color(0xFFA66A15); else -> Color(0xFF30384B) }
                    Column {
                        Row(Modifier.fillMaxWidth().background(if (item.failure) Color(0xFFFFF6F5) else Color.Transparent)
                            .clickable { detail = line }.padding(horizontal = 3.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            if (item.time.isNotEmpty()) LogText(item.time, Modifier.width(48.dp), color = Color.Gray, size = 10, maxLines = 1)
                            LogText(item.label, Modifier.width(25.dp), color = if (item.failure || item.warning) tint else LogBlue,
                                size = 10, maxLines = 1)
                            LogText(item.message, Modifier.weight(1f), size = 12, maxLines = if (item.failure) 3 else 2, color = tint)
                        }
                    }
                }
            }
            if (!history && visible.isNotEmpty()) LogText("显示最近 ${MAX_CONSOLE_LINES} 条 · 点记录查看详情", size = 9,
                color = Color.Gray, modifier = Modifier.padding(vertical = 3.dp))
        }
    }
    detail?.let { selectedLine -> LogLineDetailDialog(selectedLine, onDismiss = { detail = null }) }
}

@Composable
private fun LogConsoleIcon(icon: Int, description: String, selected: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    Box(Modifier.size(30.dp).background(if (selected) Color(0xFFF0F4FF) else Color.Transparent, RoundedCornerShape(5.dp))
        .clickable(enabled = enabled, onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(painterResource(icon), description, Modifier.size(17.dp), tint = if (!enabled) Color.LightGray else if (selected) LogBlue else Color.Gray)
    }
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
            val advice = remember(line) { editorProblemAdvice(line) }
            SelectionContainer(Modifier.weight(1f, fill = false).fillMaxWidth()) {
                androidx.compose.foundation.lazy.LazyColumn {
                    if (advice != null) item {
                        LogText(advice.summary, size = 12, color = Color(0xFFC33F46), modifier = Modifier.padding(vertical = 4.dp))
                        LogText(advice.suggestion, size = 11, color = Color.Gray, modifier = Modifier.padding(bottom = 6.dp))
                    }
                    item { LogText(line, Modifier.fillMaxWidth().padding(vertical = 4.dp), size = 11) }
                }
            }
        }
    }
}


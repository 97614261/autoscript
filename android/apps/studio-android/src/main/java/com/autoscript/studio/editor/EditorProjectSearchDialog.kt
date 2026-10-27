package com.autoscript.studio

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Text
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autoscript.core.designsystem.AutoScriptPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal class EditorSearchSession {
    var query by mutableStateOf("")
    var scope by mutableStateOf("all")
    var range by mutableStateOf("all")
    var enabled by mutableStateOf("all")
    var fullScreen by mutableStateOf(false)
    var filtersExpanded by mutableStateOf(false)
    var active by mutableStateOf<String?>(null)
    var collapsed by mutableStateOf<Set<String>>(emptySet())
    val scroll = LazyListState()
}

private fun EditorProjectSearchHit.identity() = "$flowId:$nodeId"
private data class SearchListRow(val key: String, val flowId: String, val header: String? = null,
    val hit: EditorProjectSearchHit? = null)

@Composable
internal fun EditorProjectSearchDialog(flows: List<EditorSearchFlow>, currentFlowId: String,
    session: EditorSearchSession, onRefresh: () -> Unit, onReplace: () -> Unit,
    onDismiss: () -> Unit, onSelect: (EditorProjectSearchHit, Boolean) -> String?) {
    var error by remember { mutableStateOf<String?>(null) }
    var taskError by remember { mutableStateOf<String?>(null) }
    var navigationRequest by remember { mutableIntStateOf(0) }
    val index by produceState<EditorProjectSearchIndex?>(null, flows) {
        value = null; taskError = null
        try {
            value = withContext(Dispatchers.Default) {
                val job = currentCoroutineContext()
                editorProjectSearchIndex(flows) { job.ensureActive() }
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { taskError = "无法读取草稿，请刷新后重试" }
    }
    val query = session.query
    val scope = session.scope
    val range = session.range
    val enabled = session.enabled
    val matches by produceState<List<EditorProjectSearchHit>?>(null, index, query, scope, range, enabled, currentFlowId) {
        value = null
        value = index?.let { frozen -> withContext(Dispatchers.Default) {
            val job = currentCoroutineContext()
            editorProjectSearch(frozen, query, scope) { job.ensureActive() }.filter {
                (range == "all" || it.flowId == currentFlowId) &&
                    (enabled == "all" || it.disabled == (enabled == "disabled"))
            }
        } }
    }
    val rows = remember(matches, session.collapsed) { buildList {
        matches.orEmpty().groupBy { it.flowId }.forEach { (id, hits) ->
            add(SearchListRow("header:$id", id, "${hits.first().flowName} · ${hits.size}行"))
            if (id !in session.collapsed) hits.forEach { add(SearchListRow(it.identity(), id, hit = it)) }
        }
    } }
    fun locate(hit: EditorProjectSearchHit, close: Boolean) {
        error = onSelect(hit, close)
        if (error == null) session.active = hit.identity()
    }
    fun move(delta: Int) {
        val hits = matches.orEmpty()
        if (hits.isEmpty()) return
        val at = hits.indexOfFirst { it.identity() == session.active }
        val hit = hits[if (at < 0) { if (delta > 0) 0 else hits.lastIndex } else Math.floorMod(at + delta, hits.size)]
        session.collapsed = session.collapsed - hit.flowId
        locate(hit, false)
        if (error == null) navigationRequest++
    }
    LaunchedEffect(navigationRequest) {
        if (navigationRequest == 0) return@LaunchedEffect
        val at = rows.indexOfFirst { it.key == session.active }
        // The status item precedes the plugin/result rows.
        if (at >= 0) session.scroll.animateScrollToItem(at + 1)
    }
    StudioPanelDialog("全项目查找", onDismiss, compactHeader = true, showFooter = false,
        fullScreen = session.fullScreen, onToggleFullScreen = { session.fullScreen = !session.fullScreen }) {
        BoxWithConstraints(Modifier.fillMaxWidth().then(if (session.fullScreen) Modifier.fillMaxHeight() else Modifier.heightIn(max = 500.dp))) {
        val filterHeight = (maxHeight - 124.dp).coerceIn(0.dp, 100.dp)
        Column(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                InspectorSearchField(query, { session.query = it.take(80); error = null }, "函数、变量、图片或参数")
                Text("${if (session.filtersExpanded) "收起筛选" else "展开筛选"} · ${when (scope) { "kind" -> "函数"; "variable" -> "变量"; "image" -> "图片"; else -> "全部参数" }} · ${if (range == "all") "全部插件" else "当前插件"} · ${when (enabled) { "disabled" -> "已注释"; "enabled" -> "启用"; else -> "全部行" }}",
                    fontSize = 10.sp, color = EditorBlue, modifier = Modifier.fillMaxWidth().clickable { session.filtersExpanded = !session.filtersExpanded }.padding(vertical = 5.dp))
                if (session.filtersExpanded && filterHeight > 0.dp) Column(Modifier.heightIn(max = filterHeight).verticalScroll(rememberScrollState())) {
                    EditorCompactFilter(listOf("all" to "全部", "kind" to "函数", "variable" to "变量", "image" to "图片"), scope) { session.scope = it }
                    EditorCompactFilter(listOf("all" to "全部插件", "current" to "当前插件"), range) { session.range = it }
                    EditorCompactFilter(listOf("all" to "全部行", "enabled" to "启用", "disabled" to "已注释/父级注释"), enabled) { session.enabled = it }
                }
            }
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = session.scroll,
                contentPadding = PaddingValues(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                item(key = "status") {
                    Text(taskError ?: when {
                        index == null -> "正在读取当前草稿…"
                        query.isBlank() -> "输入关键词；包含未保存修改，不会运行脚本"
                        matches == null -> "正在查找…"
                        else -> "找到${matches.orEmpty().size}行 · 点行定位并关闭；前后切换保留面板"
                    }, fontSize = 10.sp, color = AutoScriptPalette.TextSecondary)
                    index?.takeIf { it.skipped > 0 || it.limited }?.let {
                        Text("${it.skipped}个插件未完整检索（只读/结构/预算）；上限16MiB、20000行", fontSize = 10.sp, color = AutoScriptPalette.Danger)
                    }
                    error?.let { Text(it, fontSize = 11.sp, color = AutoScriptPalette.Danger) }
                }
                itemsIndexed(rows, key = { _, row -> row.key }) { _, row ->
                    if (row.header != null) Text("${if (row.flowId in session.collapsed) "+" else "−"} ${row.header}",
                        fontSize = 11.sp, color = EditorBlue, modifier = Modifier.fillMaxWidth().clickable {
                            session.collapsed = if (row.flowId in session.collapsed) session.collapsed - row.flowId else session.collapsed + row.flowId
                        }.padding(vertical = 6.dp))
                    else row.hit?.let { hit ->
                        Column(Modifier.fillMaxWidth().background(if (session.active == hit.identity()) Color(0xFFEAF2FF) else Color.Transparent)
                            .clickable { locate(hit, true) }.padding(vertical = 4.dp, horizontal = 4.dp)) {
                            Text("${hit.line} · ${hit.title}${if (hit.disabled) " [已注释]" else ""}", fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            val terms = query.trim().split(Regex("\\s+")).filter(String::isNotBlank)
                            val parameters = hit.parameters.filter { (label, value) -> terms.any { label.contains(it, true) || value.contains(it, true) } }
                            Text("${BlockCatalog.find(hit.kind)?.title.orEmpty()} · " + parameters.ifEmpty { hit.parameters.take(2) }.take(4).joinToString(" · ") {
                                "${it.first}=${editorSearchSnippet(it.second, terms)}"
                            },
                                fontSize = 10.sp, color = AutoScriptPalette.TextSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            EditorCompactActions {
                EditorCompactButton(onClick = { move(-1) }, enabled = !matches.isNullOrEmpty()) { Text("上一条", fontSize = 11.sp) }
                EditorCompactButton(onClick = { move(1) }, enabled = !matches.isNullOrEmpty()) { Text("下一条", fontSize = 11.sp) }
                EditorCompactButton(onClick = { error = null; onRefresh() }) { Text("刷新", fontSize = 11.sp) }
                EditorCompactButton(onClick = onReplace) { Text("替换", fontSize = 11.sp) }
                EditorCompactButton(onClick = onDismiss) { Text("关闭", fontSize = 11.sp) }
            }
        }
        }
    }
}

private fun editorSearchSnippet(value: String, terms: List<String>): String {
    val at = terms.map { value.indexOf(it, ignoreCase = true) }.filter { it >= 0 }.minOrNull() ?: 0
    val start = (at - 20).coerceAtLeast(0)
    val end = (start + 120).coerceAtMost(value.length)
    return (if (start > 0) "…" else "") + value.substring(start, end).replace('\n', ' ') + (if (end < value.length) "…" else "")
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun EditorCompactActions(content: @Composable () -> Unit) {
    FlowRow(Modifier.fillMaxWidth().heightIn(max = 90.dp).verticalScroll(rememberScrollState()).padding(horizontal = 4.dp), horizontalArrangement = Arrangement.End,
        verticalArrangement = Arrangement.spacedBy(0.dp)) { content() }
}

@Composable
internal fun EditorCompactButton(onClick: () -> Unit, enabled: Boolean = true, content: @Composable () -> Unit) {
    Box(Modifier.widthIn(min = 32.dp).heightIn(min = 28.dp).clickable(enabled = enabled, onClick = onClick)
        .padding(horizontal = 7.dp, vertical = 5.dp), contentAlignment = Alignment.Center) {
        CompositionLocalProvider(LocalContentColor provides if (enabled) EditorBlue else AutoScriptPalette.TextSecondary.copy(alpha = .5f), content = content)
    }
}

@Composable
internal fun EditorCompactFilter(options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        options.forEach { (value, label) ->
            Box(Modifier.background(if (value == selected) Color(0xFFE7EEFF) else Color.Transparent)) {
                EditorCompactButton(onClick = { onSelect(value) }) { Text(label, fontSize = 11.sp, maxLines = 1) }
            }
        }
    }
}

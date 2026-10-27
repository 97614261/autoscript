package com.autoscript.studio

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autoscript.core.designsystem.AutoScriptPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun EditorProjectReplacementDialog(flows: List<EditorSearchFlow>, images: List<String>, currentFlow: String,
    initialQuery: String, initialRange: String, initialEnabled: String, onDismiss: () -> Unit,
    onApply: suspend (EditorReplacementCandidate) -> String?) {
    var mode by remember { mutableStateOf("text") }
    var find by remember { mutableStateOf(initialQuery.take(256)) }
    var replacement by remember { mutableStateOf("") }
    var originalImage by remember { mutableStateOf<String?>(null) }
    var newImage by remember { mutableStateOf<String?>(null) }
    var range by remember { mutableStateOf(initialRange) }
    var enabled by remember { mutableStateOf(initialEnabled) }
    var imageQuery by remember { mutableStateOf("") }
    var choosing by remember { mutableStateOf<String?>(null) }
    var fullScreen by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<EditorReplacementPreview?>(null) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var error by remember { mutableStateOf<String?>(null) }
    var dirty by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var discard by remember { mutableStateOf(false) }
    var job by remember { mutableStateOf<Job?>(null) }
    val owner = rememberCoroutineScope()
    fun close() { if (dirty || busy) discard = true else onDismiss() }
    fun changed() { dirty = true; preview = null; selected = emptySet(); error = null }
    fun prepare() {
        val old = if (mode == "image") originalImage.orEmpty() else find
        val new = if (mode == "image") newImage.orEmpty() else replacement
        val selectedMode = mode
        val selectedRange = range
        val selectedEnabled = enabled
        busy = true; error = null; dirty = true
        job = owner.launch {
            try {
                val result = withContext(Dispatchers.Default) {
                    val task = currentCoroutineContext()
                    editorReplacementPreview(flows, selectedMode, old, new, images.toSet(),
                        currentFlow.takeIf { selectedRange == "current" }, selectedEnabled) { task.ensureActive() }
                }
                preview = result; selected = result.changes.map { it.key }.toSet()
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (failure: Exception) { error = failure.message ?: "无法生成替换预览" }
            finally { busy = false }
        }
    }
    StudioPanelDialog("全项目替换", ::close, compactHeader = true, showFooter = false,
        fullScreen = fullScreen, onToggleFullScreen = { fullScreen = !fullScreen }) {
        Column(Modifier.fillMaxWidth().then(if (fullScreen) Modifier.fillMaxHeight() else Modifier.heightIn(max = 500.dp))) {
            val prepared = preview
            if (prepared == null) Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp)) {
                EditorCompactFilter(listOf("text" to "文字内容", "image" to "图片引用"), mode) { if (!busy) { changed(); mode = it } }
                if (mode == "text") {
                    InspectorSearchField(find, { if (!busy) { changed(); find = it.take(256) } }, "原文字（区分大小写）")
                    InspectorSearchField(replacement, { if (!busy) { changed(); replacement = it.take(256) } }, "替换为（可留空删除这段文字）")
                    Text("只改文字参数及文字常量，不改变量名、表达式、ID、目录或源码。", fontSize = 10.sp, color = AutoScriptPalette.TextSecondary)
                } else {
                    Text("只改引用，不删除或重命名图片文件。", fontSize = 10.sp, color = AutoScriptPalette.TextSecondary)
                    Text("原图：${originalImage ?: "点击选择"}", fontSize = 12.sp,
                        modifier = Modifier.fillMaxWidth().clickable(enabled = !busy) { choosing = "old" }.padding(vertical = 8.dp))
                    Text("新图：${newImage ?: "点击选择"}", fontSize = 12.sp,
                        modifier = Modifier.fillMaxWidth().clickable(enabled = !busy) { choosing = "new" }.padding(vertical = 8.dp))
                }
                EditorCompactFilter(listOf("all" to "全部插件", "current" to "当前插件"), range) { if (!busy) { changed(); range = it } }
                EditorCompactFilter(listOf("all" to "全部行", "enabled" to "启用", "disabled" to "已注释"), enabled) { if (!busy) { changed(); enabled = it } }
                Text("先预览并勾选，再整项目校验。只应用到草稿，跨插件修改可整批撤销。", fontSize = 10.sp)
                error?.let { Text(it, fontSize = 11.sp, color = AutoScriptPalette.Danger) }
            } else {
                Text("${prepared.changes.size}项匹配 · 已选${selected.size}项${if (prepared.skipped > 0) " · ${prepared.skipped}个插件未检索（只读/结构/预算）" else ""}",
                    fontSize = 10.sp, modifier = Modifier.padding(8.dp))
                LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(horizontal = 8.dp)) {
                    if (prepared.changes.isEmpty()) item { Text("没有匹配的可替换参数，未修改任何内容。", fontSize = 12.sp) }
                    items(prepared.changes, key = { it.key }) { change ->
                        Row(Modifier.fillMaxWidth().clickable(enabled = !busy) {
                            selected = if (change.key in selected) selected - change.key else selected + change.key
                        }.padding(vertical = 5.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            Checkbox(change.key in selected, onCheckedChange = null, modifier = Modifier.size(22.dp))
                            Column(Modifier.weight(1f)) {
                                Text("${change.flowName} · ${change.line}行 · ${change.title} / ${change.label}", fontSize = 11.sp, color = EditorBlue)
                                Text("原：${change.before}", fontSize = 10.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text("新：${change.after}", fontSize = 10.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                    error?.let { item { Text(it, fontSize = 11.sp, color = AutoScriptPalette.Danger) } }
                }
            }
            EditorCompactActions {
                EditorCompactButton(onClick = ::close) { Text("取消", fontSize = 11.sp) }
                if (prepared != null) {
                    EditorCompactButton(enabled = !busy, onClick = { preview = null; error = null }) { Text("改条件", fontSize = 11.sp) }
                    EditorCompactButton(enabled = !busy, onClick = {
                        selected = if (selected.size == prepared.changes.size) emptySet() else prepared.changes.map { it.key }.toSet()
                    }) { Text(if (selected.size == prepared.changes.size) "全不选" else "全选", fontSize = 11.sp) }
                    EditorCompactButton(enabled = !busy && selected.isNotEmpty(), onClick = {
                        val selection = selected.toSet()
                        busy = true; error = null
                        job = owner.launch {
                            try {
                                val candidate = withContext(Dispatchers.Default) {
                                    val task = currentCoroutineContext()
                                    editorReplacementCandidate(flows, prepared, selection) { task.ensureActive() }
                                }
                                currentCoroutineContext().ensureActive()
                                error = onApply(candidate)
                                if (error == null) onDismiss()
                            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                            catch (failure: Exception) { error = failure.message ?: "替换失败，未应用本次修改" }
                            finally { busy = false }
                        }
                    }) { Text(if (busy) "校验中…" else "校验并替换", fontSize = 11.sp) }
                } else EditorCompactButton(enabled = !busy && (if (mode == "text") find.isNotEmpty() else originalImage != null && newImage != null), onClick = ::prepare) {
                    Text(if (busy) "准备中…" else "预览改动", fontSize = 11.sp)
                }
            }
        }
    }
    choosing?.let { target ->
        StudioPanelDialog(if (target == "old") "选择原图片" else "选择新图片", { choosing = null }, compactHeader = true) {
            Column(Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
                InspectorSearchField(imageQuery, { imageQuery = it.take(80) }, "筛选本项目图片")
                LazyColumn(Modifier.weight(1f)) {
                    val choices = images.filter { it.contains(imageQuery, true) }
                    if (choices.isEmpty()) item { Text("没有符合的已声明图片", fontSize = 11.sp, modifier = Modifier.padding(8.dp)) }
                    items(choices, key = { it }) { path ->
                        Text(path, fontSize = 12.sp, modifier = Modifier.fillMaxWidth().clickable {
                            changed(); if (target == "old") originalImage = path else newImage = path
                            choosing = null; imageQuery = ""
                        }.padding(8.dp))
                    }
                }
            }
        }
    }
    if (discard) StudioConfirmDialog(onDismissRequest = { discard = false }, title = { Text("放弃本次替换？") },
        text = { Text("未应用的输入和勾选将丢弃；正在进行的校验会取消，不修改草稿。") },
        actions = listOf(StudioConfirmAction("继续编辑", { discard = false }),
            StudioConfirmAction("放弃", { job?.cancel(); onDismiss() })))
}

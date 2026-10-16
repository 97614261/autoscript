package com.autoscript.studio

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autoscript.core.designsystem.AutoScriptPalette
import com.autoscript.core.model.RuntimeConnectionPhase
import com.autoscript.core.model.RuntimeEngineState
import com.autoscript.project.store.ProjectSnapshot
import com.autoscript.runtime.api.RuntimeDebugReply
import com.autoscript.runtime.api.RuntimeDebugValue
import com.autoscript.runtime.client.RuntimeClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

@Composable
internal fun RuntimeVariableObserver(snapshot: ProjectSnapshot, flowId: String, client: RuntimeClient, onDismiss: () -> Unit, restoreRequest: Int = 0) {
    val projectId = snapshot.manifest.projectId
    val runtime by client.runtimeStates.collectAsState()
    val inspection = remember(projectId) { RuntimeVariableInspectionState() }
    val watch = remember(projectId) { RuntimeVariableWatchState() }
    var collapsed by remember(projectId) { mutableStateOf(false) }
    var maximized by remember(projectId) { mutableStateOf(false) }
    var visible by remember(projectId) { mutableStateOf(false) }
    var filter by remember(projectId) { mutableStateOf("local") }
    var query by remember(projectId) { mutableStateOf("") }
    var lockedFlow by remember(projectId) { mutableStateOf<String?>(null) }
    var details by remember(projectId) { mutableStateOf<RuntimeDebugValue?>(null) }
    var searching by remember(projectId) { mutableStateOf(false) }
    var searchDraft by remember(projectId) { mutableStateOf("") }
    var help by remember(projectId) { mutableStateOf(false) }
    var refreshRequest by remember(projectId) { mutableIntStateOf(0) }
    var readFailed by remember(projectId) { mutableStateOf(false) }
    var knownGeneration by remember(projectId) { mutableStateOf<Long?>(null) }
    var lastEngine by remember(projectId) { mutableStateOf(RuntimeEngineState.UNKNOWN) }
    val context = LocalContext.current
    val fontScale = LocalConfiguration.current.fontScale
    LaunchedEffect(restoreRequest) { collapsed = false }

    LaunchedEffect(projectId, runtime.phase, runtime.sessionGeneration, runtime.stateRevision, runtime.engineState, visible, collapsed, refreshRequest) {
        inspection.invalidate()
        details = null
        readFailed = false
        if (knownGeneration != runtime.sessionGeneration || runtime.phase != RuntimeConnectionPhase.CONNECTED ||
            runtime.engineState in setOf(RuntimeEngineState.IDLE, RuntimeEngineState.STOPPING, RuntimeEngineState.UNKNOWN) ||
            runtime.engineState == RuntimeEngineState.RUNNING && lastEngine in setOf(RuntimeEngineState.STOPPED, RuntimeEngineState.FAILED)) {
            watch.clear()
        }
        knownGeneration = runtime.sessionGeneration; lastEngine = runtime.engineState
        if (!visible || collapsed || runtime.phase != RuntimeConnectionPhase.CONNECTED || runtime.engineState !in setOf(
                RuntimeEngineState.RUNNING, RuntimeEngineState.PAUSED, RuntimeEngineState.STOPPED, RuntimeEngineState.FAILED)) return@LaunchedEffect
        do {
            val position = client.executionPosition.value?.takeIf { it.projectId == projectId && it.sessionGeneration == runtime.sessionGeneration }
            readRuntimeVariables(inspection, runtime.sessionGeneration, runtime.stateRevision,
                read = { withContext(Dispatchers.IO) { client.debugSnapshot(projectId, runtime.sessionGeneration) } },
                current = { client.runtimeStates.value }, keepReply = true, onFailure = { readFailed = true })
            currentCoroutineContext().ensureActive()
            val currentPosition = client.executionPosition.value?.takeIf { it.projectId == projectId && it.sessionGeneration == runtime.sessionGeneration }
            val minimumRun = maxOf(position?.runRevision ?: 0L, currentPosition?.runRevision ?: 0L)
            val result = inspection.reply
            if (watch.reply.status == RuntimeDebugReply.SUCCESS && watch.reply.runRevision < minimumRun) watch.clear()
            if (result.status != RuntimeDebugReply.SUCCESS || result.runRevision >= minimumRun) {
                if (watch.sample(result)) readFailed = false
            } else watch.clear()
            if (runtime.engineState != RuntimeEngineState.RUNNING) break
            // Delay follows completion: cancellation or a slow Binder cannot accumulate read requests.
            delay(500)
        } while (isActive)
    }
    DisposableEffect(inspection) { onDispose { inspection.invalidate(); watch.clear() } }
    val reply = watch.reply
    val values = watch.values(filter, query, lockedFlow)
    val activeFlow = if (reply.flowId.isNotEmpty()) reply.flowId else flowId
    fun flowName(id: String) = snapshot.manifest.flows.firstOrNull { it.flowId == id }?.path?.substringAfterLast('/')?.removeSuffix(".jsonl") ?: "插件"

    RuntimeVariableFloatingWindow(collapsed, maximized, onRestore = { maximized = false }, onDismiss = onDismiss,
        onVisibility = { visible = it }) { move ->
        Surface(Modifier.fillMaxSize(), color = Color.White, shadowElevation = 4.dp) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().height(studioCompactControlHeight(32, fontScale).dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (collapsed) "运行变量 · 已收起" else "运行变量 · ${flowName(lockedFlow ?: activeFlow)}", fontSize = 11.sp,
                        color = AutoScriptPalette.Accent, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).fillMaxHeight().pointerInput(Unit) {
                            detectDragGestures { change, amount -> change.consume(); move(amount.x, amount.y) }
                        }.wrapContentHeight(Alignment.CenterVertically).padding(horizontal = 6.dp))
                    WatchButton(if (collapsed) "展开" else "收起") { collapsed = !collapsed }
                    if (!collapsed) StudioPanelFullScreenButton(maximized, { maximized = !maximized }, compact = true)
                    Box(Modifier.size(28.dp).clickable(onClick = onDismiss), contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.Close, contentDescription = "关闭运行变量", modifier = Modifier.size(16.dp), tint = AutoScriptPalette.TextSecondary)
                    }
                }
                if (!collapsed) {
                    HorizontalDivider(color = AutoScriptPalette.Divider)
                    Row(Modifier.fillMaxWidth().background(AutoScriptPalette.PageBackground)) {
                        listOf("global" to "全局", "local" to "局部", "changed" to "变动").forEach { (key, label) ->
                            Box(Modifier.weight(1f).heightIn(min = 26.dp).background(if (filter == key) Color(0xFFE7EEFF) else Color.Transparent)
                                .clickable { filter = key }.padding(vertical = 4.dp), contentAlignment = Alignment.Center) {
                                Text(label, fontSize = 11.sp, color = if (filter == key) AutoScriptPalette.Accent else AutoScriptPalette.TextSecondary)
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        WatchButton(if (query.isEmpty()) "查找" else "筛选中") { searchDraft = query; searching = true }
                        WatchButton(if (lockedFlow == null) "锁插件" else "解锁") { lockedFlow = if (lockedFlow == null) activeFlow else null }
                        WatchButton("刷新", !inspection.loading && runtime.phase == RuntimeConnectionPhase.CONNECTED) { refreshRequest++ }
                        WatchButton("说明") { help = true }
                    }
                    Row(Modifier.fillMaxWidth().background(AutoScriptPalette.PageBackground).padding(horizontal = 6.dp, vertical = 3.dp)) {
                        Text("类型", fontSize = 10.sp, modifier = Modifier.width(30.dp))
                        Text("变量", fontSize = 10.sp, modifier = Modifier.weight(1f))
                        Text("当前值", fontSize = 10.sp, modifier = Modifier.weight(1f))
                    }
                    LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                        if (values.isEmpty()) item {
                            Text(when {
                                lockedFlow != null && reply.flowId != lockedFlow && filter != "global" -> "锁定插件未在当前快照中，暂无可读局部值"
                                reply.status != RuntimeDebugReply.SUCCESS -> "运行或单步本项目后可观察变量"
                                filter == "changed" -> "相邻采样暂未发现变化"
                                else -> "没有符合筛选的变量"
                            }, fontSize = 11.sp, color = AutoScriptPalette.TextSecondary, modifier = Modifier.padding(8.dp))
                        }
                        items(values, key = { it.watchKey(reply.flowId) }) { value ->
                            val changed = value.watchKey(reply.flowId) in watch.latestChanges
                            Row(Modifier.fillMaxWidth().heightIn(min = 28.dp)
                                .background(if (changed) Color(0xFFEFF5FF) else Color.Transparent).clickable { details = value }
                                .padding(horizontal = 6.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(watchTypeLabel(value.type), fontSize = 10.sp, color = AutoScriptPalette.TextSecondary, modifier = Modifier.width(30.dp))
                                Text(value.name, fontSize = 11.sp, color = AutoScriptPalette.Accent, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f).padding(end = 4.dp))
                                Text(runtimeDebugValueText(value), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f), color = if (changed) AutoScriptPalette.Accent else AutoScriptPalette.TextPrimary)
                            }
                            HorizontalDivider(color = AutoScriptPalette.Divider, thickness = .5.dp)
                        }
                    }
                    HorizontalDivider(color = AutoScriptPalette.Divider)
                    Text(when {
                        readFailed -> "读取失败，可手动重试"
                        runtime.phase != RuntimeConnectionPhase.CONNECTED -> "未连接 · 旧值已清除"
                        watch.status == RuntimeDebugReply.SESSION_MISMATCH -> "无匹配运行会话"
                        inspection.loading -> if (reply.status == RuntimeDebugReply.SUCCESS) "读取中 · 显示上一份采样" else "正在读取运行变量"
                        watch.status == RuntimeDebugReply.BUSY -> if (reply.status == RuntimeDebugReply.SUCCESS) "读取忙 · 显示上一份采样" else "读取忙 · 等待样本"
                        reply.status != RuntimeDebugReply.SUCCESS -> "等待本项目运行检查点"
                        else -> "${values.size}/${reply.variables.size} · ${when (runtime.engineState) {
                            RuntimeEngineState.RUNNING -> "500ms采样"; RuntimeEngineState.PAUSED -> "暂停·执行前"; else -> "结束·最后快照"
                        }}${if (reply.variablesTruncated) " · 已达上限" else ""}"
                    }, fontSize = 10.sp, maxLines = 2, color = AutoScriptPalette.TextSecondary, modifier = Modifier.padding(5.dp))
                }
            }
        }
    }
    if (searching) StudioPanelDialog("搜索变量", onDismiss = { searching = false }, confirmLabel = "应用",
        onConfirm = { query = searchDraft; searching = false }, compactHeader = true) {
        Column(Modifier.padding(8.dp)) {
            InspectorSearchField(searchDraft, { searchDraft = it.take(128) }, "变量名，留空显示全部")
            TextButton(onClick = { searchDraft = "" }) { Text("清除筛选") }
        }
    }
    if (help) StudioPanelDialog("观察说明", onDismiss = { help = false }, compactHeader = true) {
        Text("运行时最多每500ms读取一次，可能错过短暂变化；首次采样建立基线，变动栏显示当前变量观察到的变化。局部跟随运行插件，锁定后不显示其他插件局部值。收起暂停刷新，展开重新读取。值超过上限会截断；截断前缀不变不能证明完整值未变化。不会修改变量或写入日志。",
            fontSize = 11.sp, modifier = Modifier.heightIn(max = 160.dp).verticalScroll(rememberScrollState()).padding(10.dp))
    }
    details?.let { value ->
        var copied by remember(value) { mutableStateOf(false) }
        StudioPanelDialog("${value.name} · ${watchTypeLabel(value.type)}", onDismiss = { details = null }, confirmLabel = "关闭", compactHeader = true,
            headerActions = { WatchButton(if (copied) "已复制" else "复制") {
                runCatching { context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("运行变量", value.value)) }
                    .onSuccess { copied = true }
            } }) {
            Column(Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState()).padding(10.dp)) {
                Text("所选采样 · ${if (value.scope == "local") "局部" else "全局"}${if (value.truncated) " · 已截断" else ""}", fontSize = 10.sp)
                if (value.declaredType.isNotEmpty()) Text("声明类型：${watchTypeLabel(value.declaredType)}", fontSize = 10.sp)
                SelectionContainer { Text(runtimeDebugValueText(value), fontSize = 12.sp) }
            }
        }
    }
}

internal fun watchTypeLabel(type: String): String = when (type) {
    "integer" -> "整"; "number" -> "浮"; "string" -> "字"; "boolean" -> "布"; "nil" -> "空"; else -> "其他"
}

@Composable
private fun WatchButton(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Box(Modifier.heightIn(min = 28.dp).clickable(enabled = enabled, onClick = onClick).padding(horizontal = 5.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center) {
        Text(label, fontSize = 10.sp, color = if (enabled) AutoScriptPalette.Accent else AutoScriptPalette.TextSecondary,
            style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false)))
    }
}

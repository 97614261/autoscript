package com.autoscript.studio

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.foundation.clickable
import com.autoscript.core.designsystem.AutoScriptPalette
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.autoscript.project.store.ProjectDesign
import com.autoscript.runtime.api.RecordingReply
import com.autoscript.runtime.client.RuntimeClient
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Real global Root recording. This composable remains mounted while its own Dialog is hidden. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RecordingWorkspaceScreen(
    projectId: String, flowId: String, design: ProjectDesign, capabilities: Set<String>,
    runtimeClient: RuntimeClient, onApply: (ImageToolCodeGen.Snippet) -> Boolean, onBack: () -> Unit,
    onRecordingActive: (Boolean) -> Unit = {}, choosingPosition: Boolean = false,
) {
    val context = LocalContext.current
    val overlayHost = LocalEditorOverlay.current
    val lifecycleOwner = context.inputPickerActivity() as? LifecycleOwner
    val scope = rememberCoroutineScope()
    var recordingId by remember(projectId,flowId) { mutableStateOf<Long?>(null) }
    var visible by remember { mutableStateOf(true) }
    LaunchedEffect(overlayHost?.restoreRequest) {
        if ((overlayHost?.restoreRequest ?: 0) > 0) visible = true
    }
    var working by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var messageIsError by remember { mutableStateOf(false) }
    var reply by remember { mutableStateOf<RecordingReply?>(null) }
    var actions by remember { mutableStateOf<List<RecordedAction>>(emptyList()) }
    var selected by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var keepIntervals by remember { mutableStateOf(true) }
    var operation by remember { mutableStateOf<Job?>(null) }
    var showHelp by remember { mutableStateOf(false) }
    var fullScreen by remember { mutableStateOf(false) }
    var discardRequested by remember { mutableStateOf(false) }
    var restartRequested by remember { mutableStateOf(false) }
    var cancelRequested by remember { mutableStateOf(false) }
    val latestActive by rememberUpdatedState(onRecordingActive)
    DisposableEffect(runtimeClient,projectId,flowId) {
        onDispose { runtimeClient.cancelRecordingOnClose(); latestActive(false) }
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) visible = true }
        lifecycleOwner?.lifecycle?.addObserver(observer)
        onDispose { lifecycleOwner?.lifecycle?.removeObserver(observer) }
    }
    fun close() { operation?.cancel(); runtimeClient.cancelRecordingOnClose(); latestActive(false); onBack() }
    fun requestClose() {
        if (recordingReviewNeedsConfirmation(recordingId != null, working, reply != null)) discardRequested = true
        else close()
    }
    fun acceptResult(result: RecordingReply) {
        if (result.status == RecordingReply.BUSY) {
            message = "录制正在收尾，请稍后再获取"
            return
        }
        recordingId = null
        latestActive(false)
        visible = true
        messageIsError = false
        if (result.status != RecordingReply.SUCCESS) {
            messageIsError = true; message = recordingFailureLabel(result.status)
        } else runCatching { decodeRecordedActions(result.actions,result.width,result.height,result.elapsedMs) }
            .onSuccess { decoded ->
                reply = result; actions = decoded; selected = decoded.indices.toSet()
                message = when {
                    result.skippedGestures > 0 -> "已跳过${result.skippedGestures}个曲线/超长/过短手势；以下${decoded.size}个动作保留，请逐项确认。"
                    decoded.isEmpty() -> "没有可加入的录制动作"
                    else -> "录制完成：请检查点位与顺序，再确认加入"
                }
            }.onFailure { messageIsError = true; message = it.message ?: "录制结果格式无效" }
    }
    val completion by runtimeClient.recordingCompletion.collectAsState()
    LaunchedEffect(recordingId, completion) {
        val id = recordingId ?: return@LaunchedEffect
        if (completion?.recordingId != id || overlayHost?.isClosed == true) return@LaunchedEffect
        runtimeClient.consumeRecordingCompletion(id)?.let { result ->
            acceptResult(result)
            overlayHost?.restore()
        }
    }
    fun start() {
        working=true; messageIsError=false; message="等待Root就绪…"
        operation=scope.launch {
            try {
                runtimeClient.startRecording(projectId,flowId).onSuccess { id ->
                    // Do not discard the last result until a new recording has actually started.
                    reply=null; actions=emptyList(); selected=emptySet()
                    recordingId=id; latestActive(true); message="正在录制：点 REC 停止后自动返回结果"
                    visible=false
                    context.inputPickerActivity()?.moveTaskToBack(true)
                }.onFailure { messageIsError=true; message=it.message ?: "录制无法启动" }
            } finally { working=false }
        }
    }
    fun cancel() {
        val id=recordingId ?: return
        working=true; messageIsError=false
        operation=scope.launch {
            try {
                runtimeClient.cancelRecording(id)
                    .onSuccess { recordingId=null; latestActive(false); message="已取消，未提交草稿" }
                    .onFailure { messageIsError=true; message=it.message ?: "未能确认取消，请重试或获取结果" }
            } finally { working=false }
        }
    }
    val draft = remember(actions,selected,reply,keepIntervals,design) {
        runCatching {
            val data = requireNotNull(reply)
            recordedSnippet(actions.filterIndexed { index, _ -> index in selected },data.width,data.height,design,keepIntervals)
        }
    }
    if (!visible || choosingPosition) return
    StudioPanelDialog(title = "动作录制", onDismiss = ::requestClose, showFooter = false, compactHeader = true,
        fullScreen = fullScreen, onToggleFullScreen = { fullScreen = !fullScreen }) {
            Column((if (fullScreen) Modifier.fillMaxSize() else Modifier.fillMaxWidth().heightIn(max = 440.dp))
                .padding(8.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                // Status/help and results share one scroll budget; joining stays outside it.
                LazyColumn(Modifier.weight(1f, fill = false).fillMaxWidth()) {
                item(key = "recording-controls") {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment=Alignment.CenterVertically) {
                    Text(when { working -> "处理中"; recordingId != null -> "录制中"; reply != null -> "结果预览"; else -> "准备录制" },
                        fontSize = 11.sp, color = AutoScriptPalette.Accent, modifier=Modifier.weight(1f))
                    RecordingSmallButton("说明") { showHelp = !showHelp }
                    if (recordingId != null) RecordingSmallButton("继续录制", enabled = !working) {
                        visible = false; context.inputPickerActivity()?.moveTaskToBack(true)
                    }
                }
                if (showHelp) Text("开始后窗口隐藏，可在其他应用操作。点 REC 停止/取消后，悬浮编辑自动返回结果；App内录制回到智构查看。单指、点击/长按、直线滑动和白名单设备按键；最长60秒，最多512个动作。结果只成为草稿，不会自动执行。",
                    fontSize = 11.sp, modifier = Modifier.heightIn(max = 70.dp).verticalScroll(rememberScrollState()))
                else if (reply == null) Text("开始后隐藏窗口 · 结束后预览保存 · 不自动执行", fontSize = 11.sp)
                if ("input.basic" !in capabilities) Text("项目未声明 input.basic，请到项目能力设置开启后再录制。",color=MaterialTheme.colorScheme.error)
                message?.let { Text(it,color=if (messageIsError) MaterialTheme.colorScheme.error else AutoScriptPalette.TextSecondary,fontSize = 11.sp,
                    modifier = Modifier.heightIn(max = 56.dp).verticalScroll(rememberScrollState())) }
                FlowRow(horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                    if (recordingId == null) RecordingSmallButton(if (reply == null) "开始录制" else "重新录制", enabled = !working && "input.basic" in capabilities, onClick={
                        if (reply != null) restartRequested = true else start()
                    })
                    if (recordingId != null) RecordingSmallButton("停止并预览", enabled = !working, onClick={
                        val id=recordingId ?: return@RecordingSmallButton
                        working=true; messageIsError=false
                        operation=scope.launch {
                            try {
                                runtimeClient.finishRecording(id).onSuccess { result ->
                                    if (recordingId == id) acceptResult(result)
                                }.onFailure { messageIsError=true; message=it.message ?: "获取结果失败" }
                            } finally { working=false }
                        }
                    })
                    if (recordingId != null) RecordingSmallButton("取消录制", enabled = !working, onClick={
                        cancelRequested = true
                    })
                }
                reply?.let { data -> Text("${data.width}×${data.height} → ${design.width}×${design.height} · ${data.elapsedMs/1000.0}s · ${design.scaleMode}",fontSize = 10.sp) }
                if (reply != null) {
                FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                    Text("已选 ${selected.size}/${actions.size}", fontSize = 11.sp, modifier = Modifier.padding(vertical = 6.dp))
                    RecordingSmallButton("全选", enabled = !working) { selected=actions.indices.toSet() }
                    RecordingSmallButton("清空", enabled = !working) { selected=emptySet() }
                    RecordingCheck("保留间隔", keepIntervals, !working) { keepIntervals=it }
                }
                }
                }
                }
                if (reply != null) {
                    itemsIndexed(actions) { index,action ->
                        RecordingCheck("${index+1}. ${action.label}", index in selected, !working, Modifier.fillMaxWidth()) { chosen ->
                            selected=if (chosen) selected+index else selected-index
                        }
                    }
                }
                }
                if (reply != null) {
                if (selected.isNotEmpty()) {
                    draft.exceptionOrNull()?.message?.let { Text(it,color=MaterialTheme.colorScheme.error,fontSize = 11.sp,
                        modifier = Modifier.heightIn(max = 48.dp).verticalScroll(rememberScrollState())) }
                    draft.getOrNull()?.let { Text(it.summary,fontSize = 10.sp) }
                }
                RecordingSmallButton("下一步 · 选择加入位置", modifier=Modifier.fillMaxWidth(),
                    enabled = !working && recordingId==null && draft.getOrNull()?.rejection==null && draft.isSuccess) {
                    draft.getOrNull()?.let { snippet ->
                        if (!onApply(snippet)) { messageIsError=true; message = "插件或能力已变化，未加入；请返回编辑器检查" }
                    }
                }
                }
            }
    }
    if (discardRequested || restartRequested || cancelRequested) StudioConfirmDialog(
        onDismissRequest = { discardRequested=false; restartRequested=false; cancelRequested=false },
        title = { Text(when { restartRequested -> "重新录制？"; cancelRequested -> "取消录制？"; else -> "关闭录制？" }) },
        text = { Text(if (recordingId != null || working) "将停止录制并丢弃未加入的结果。" else "当前结果尚未加入积木，丢弃后不能恢复。") },
        confirmButton = { TextButton(onClick = {
            if (restartRequested) { restartRequested=false; start() }
            else if (cancelRequested) { cancelRequested=false; cancel() }
            else { discardRequested=false; close() }
        }) { Text(if (restartRequested) "确认重新录制" else "确认丢弃") } },
        dismissButton = { TextButton(onClick = { discardRequested=false; restartRequested=false; cancelRequested=false }) { Text("保留") } },
    )
}

internal fun recordingReviewNeedsConfirmation(active: Boolean, working: Boolean, hasResult: Boolean) = active || working || hasResult

@Composable
private fun RecordingSmallButton(label: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Box(modifier.heightIn(min = 28.dp).clickable(enabled = enabled, onClick = onClick).padding(horizontal = 6.dp, vertical = 5.dp),
        contentAlignment = Alignment.Center) {
        Text(label, fontSize = 11.sp, color = if (enabled) AutoScriptPalette.Accent else AutoScriptPalette.TextSecondary)
    }
}

@Composable
private fun RecordingCheck(label: String, checked: Boolean, enabled: Boolean, modifier: Modifier = Modifier, onChange: (Boolean) -> Unit) {
    Row(modifier.heightIn(min = 28.dp).clickable(enabled = enabled) { onChange(!checked) }.padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically) {
        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
            Checkbox(checked = checked, onCheckedChange = null, enabled = enabled, modifier = Modifier.size(24.dp))
        }
        Text(label, fontSize = 11.sp, modifier = Modifier.padding(start = 3.dp))
    }
}

package com.autoscript.studio

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.autoscript.project.store.ProjectDesign
import com.autoscript.runtime.api.RecordingReply
import com.autoscript.runtime.client.RuntimeClient
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Real global Root recording. This composable remains mounted while its own Dialog is hidden. */
@Composable
internal fun RecordingWorkspaceScreen(
    projectId: String, flowId: String, design: ProjectDesign, capabilities: Set<String>,
    runtimeClient: RuntimeClient, onApply: (ImageToolCodeGen.Snippet) -> Unit, onBack: () -> Unit,
    onRecordingActive: (Boolean) -> Unit = {},
) {
    val context = LocalContext.current
    val lifecycleOwner = context.inputPickerActivity() as? LifecycleOwner
    val scope = rememberCoroutineScope()
    var recordingId by remember(projectId,flowId) { mutableStateOf<Long?>(null) }
    var visible by remember { mutableStateOf(true) }
    var working by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var reply by remember { mutableStateOf<RecordingReply?>(null) }
    var actions by remember { mutableStateOf<List<RecordedAction>>(emptyList()) }
    var selected by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var keepIntervals by remember { mutableStateOf(true) }
    var operation by remember { mutableStateOf<Job?>(null) }
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
    val draft = remember(actions,selected,reply,keepIntervals,design) {
        runCatching {
            val data = requireNotNull(reply)
            recordedSnippet(actions.filterIndexed { index, _ -> index in selected },data.width,data.height,design,keepIntervals)
        }
    }
    if (!visible) return
    Dialog(onDismissRequest = ::close, properties = DialogProperties(usePlatformDefaultWidth=false)) {
        Surface(modifier=Modifier.fillMaxWidth().fillMaxHeight(0.9f).padding(12.dp),shape=MaterialTheme.shapes.large) {
            Column(Modifier.fillMaxSize().padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment=Alignment.CenterVertically) {
                    Text("Root 动作录制",style=MaterialTheme.typography.titleLarge,modifier=Modifier.weight(1f))
                    TextButton(onClick=::close) { Text("关闭") }
                }
                Text("开始后窗口隐藏，可切换到其他应用操作。点 REC 停止/取消；回到智构后获取结果。单指、点击/长按、直线滑动和白名单设备按键；最长60秒，最多512个动作。结果只成为草稿，不会自动执行。",style=MaterialTheme.typography.bodySmall)
                if ("input.basic" !in capabilities) Text("项目未声明 input.basic，请到项目能力设置开启后再录制。",color=MaterialTheme.colorScheme.error)
                message?.let { Text(it,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall) }
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    TextButton(enabled=!working && recordingId==null && "input.basic" in capabilities,onClick={
                        working=true; message="等待Root就绪…"; reply=null; actions=emptyList(); selected=emptySet()
                        operation=scope.launch {
                            try {
                                runtimeClient.startRecording(projectId,flowId).onSuccess { id ->
                                    recordingId=id; latestActive(true); message="正在录制：可切换到目标应用，结束后回到这里获取结果"
                                    visible=false
                                    context.inputPickerActivity()?.moveTaskToBack(true)
                                }.onFailure { message=it.message ?: "录制无法启动" }
                            } finally { working=false }
                        }
                    }) { Text(if (working && recordingId==null) "启动中…" else "开始真实录制") }
                    TextButton(enabled=!working && recordingId!=null,onClick={
                        val id=recordingId ?: return@TextButton
                        working=true
                        operation=scope.launch {
                            try {
                                runtimeClient.finishRecording(id).onSuccess { result ->
                                    if (result.status==RecordingReply.BUSY) message="录制正在收尾，请稍后再获取"
                                    else {
                                        recordingId=null; latestActive(false)
                                        if (result.status!=RecordingReply.SUCCESS) message=recordingFailureLabel(result.status)
                                        else runCatching { decodeRecordedActions(result.actions,result.width,result.height,result.elapsedMs) }
                                            .onSuccess { decoded ->
                                                reply=result; actions=decoded; selected=decoded.indices.toSet()
                                                message=when { result.skippedGestures>0 -> "已跳过${result.skippedGestures}个曲线/超长/过短手势；以下${decoded.size}个动作保留，请逐项确认。"
                                                    decoded.isEmpty() -> "没有可加入的录制动作"
                                                    else -> "录制完成：请检查点位与顺序，再确认加入" }
                                            }.onFailure { message=it.message ?: "录制结果格式无效" }
                                    }
                                }.onFailure { message=it.message ?: "获取结果失败" }
                            } finally { working=false }
                        }
                    }) { Text("停止 / 获取结果") }
                    TextButton(enabled=!working && recordingId!=null,onClick={
                        val id=recordingId ?: return@TextButton
                        working=true
                        operation=scope.launch {
                            try {
                                runtimeClient.cancelRecording(id)
                                    .onSuccess { recordingId=null; latestActive(false); message="已取消，未提交草稿" }
                                    .onFailure { message=it.message ?: "未能确认取消，请重试或获取结果" }
                            } finally { working=false }
                        }
                    }) { Text("取消录制") }
                }
                reply?.let { data -> Text("原屏幕 ${data.width}×${data.height} · ${(data.elapsedMs/1000.0)}秒 → 项目 ${design.width}×${design.height} (${design.scaleMode})",style=MaterialTheme.typography.bodySmall) }
                Row(verticalAlignment=Alignment.CenterVertically) {
                    Checkbox(checked=keepIntervals,onCheckedChange={ keepIntervals=it }); Text("保留动作之间的等待")
                }
                LazyColumn(Modifier.weight(1f)) {
                    itemsIndexed(actions) { index,action -> Row(verticalAlignment=Alignment.CenterVertically) {
                        Checkbox(checked=index in selected,onCheckedChange={ chosen -> selected=if (chosen) selected+index else selected-index })
                        Text("${index+1}. ${action.label}",style=MaterialTheme.typography.bodyMedium)
                    } }
                }
                if (reply!=null && selected.isNotEmpty()) {
                    draft.exceptionOrNull()?.message?.let { Text(it,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall) }
                    draft.getOrNull()?.let { Text(it.summary,style=MaterialTheme.typography.bodySmall) }
                }
                TextButton(enabled=!working && recordingId==null && draft.getOrNull()?.rejection==null && draft.isSuccess,onClick={
                    draft.getOrNull()?.let { snippet -> onApply(snippet); onBack() }
                },modifier=Modifier.fillMaxWidth()) { Text("确认一次加入（可撤销）") }
            }
        }
    }
}

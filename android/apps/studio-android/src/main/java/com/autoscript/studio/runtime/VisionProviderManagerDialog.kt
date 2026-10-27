package com.autoscript.studio

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autoscript.runtime.client.RuntimeClient
import com.autoscript.runtime.vision.components.StoredVisionComponent
import com.autoscript.runtime.vision.components.StoredVisionComponentState
import com.autoscript.runtime.vision.components.VisionComponentKind
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean

/** Real private import/selection management; never installs APKs or changes project capabilities. */
@Composable
internal fun VisionProviderManagerDialog(client: RuntimeClient, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val runtime by client.runtimeStates.collectAsState()
    val idle = canManageVisionProviders(runtime)
    val repository = remember(context, client) { VisionProviderRepository(context, { client.runtimeStates.value }, client::canChangeVisionProviders) }
    val scope = rememberCoroutineScope()
    val live = remember { AtomicBoolean(true) }
    var inventory by remember { mutableStateOf<VisionProviderInventory?>(null) }
    var status by remember { mutableStateOf<Map<String, VisionProviderStatus?>>(emptyMap()) }
    var message by remember { mutableStateOf("本地导入固定发行签名组件，不是 Runner 打包模板；不提供下载。") }
    var job by remember { mutableStateOf<Job?>(null) }
    var busy by remember { mutableStateOf(false) }
    var fullScreen by remember { mutableStateOf(false) }
    var remove by remember { mutableStateOf<StoredVisionComponent?>(null) }
    var clear by remember { mutableStateOf<VisionComponentKind?>(null) }
    DisposableEffect(Unit) { onDispose { live.set(false); job?.cancel() } }
    fun close() { live.set(false); job?.cancel(); onDismiss() }
    fun perform(success: String, action: suspend () -> Unit = {}) {
        if (busy || !live.get() || !canManageVisionProviders(client.runtimeStates.value)) return
        val expected = VisionProviderMutationContext(client.runtimeStates.value)
        busy = true
        job = scope.launch {
            try {
                action()
                val updated = repository.inventory()
                val queried = withContext(Dispatchers.IO) { client.visionAvailability().mapValues { VisionProviderStatus.parse(it.value) } }
                ensureActive()
                check(expected.matches(client.runtimeStates.value)) { "VISION_COMPONENT_RUNTIME_CHANGED" }
                if (live.get()) { inventory = updated; status = queried; message = success }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Throwable) {
                if (live.get()) message = "操作未完成或复查失败，请刷新确认；检查签名、版本、剩余空间和运行状态。原项目不变。"
            } finally { busy = false; job = null }
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && live.get()) perform("已导入并校验；尚未选择或执行。") { repository.import(uri) }
    }
    LaunchedEffect(runtime.sessionGeneration, idle) {
        status = emptyMap()
        if (!idle) { job?.cancel(); message = "Runner 未连接或脚本正在执行，组件操作暂不可用。" }
        else if (inventory == null) perform("库存已复查。运行状态来自缓存；待复查时请稍后点击刷新。")
    }
    StudioPanelDialog("视觉组件", ::close, confirmLabel = if (busy) "取消并关闭" else "关闭",
        fullScreen = fullScreen, compactHeader = true, onToggleFullScreen = { fullScreen = !fullScreen }, headerActions = {
            TextButton(enabled = idle && !busy, onClick = { picker.launch(arrayOf("application/vnd.android.package-archive", "application/octet-stream")) }) { Text("导入", fontSize = 11.sp) }
            TextButton(enabled = idle && !busy, onClick = { perform("已刷新。组件库存与后端会话是不同状态；不代表识别实测成功。") }) { Text("刷新", fontSize = 11.sp) }
        }) {
        Column(Modifier.fillMaxWidth().heightIn(max = if (fullScreen) 1600.dp else 520.dp)) {
            Text(message, Modifier.padding(8.dp), fontSize = 11.sp)
            if (busy) { LinearProgressIndicator(Modifier.fillMaxWidth()); TextButton(onClick = { job?.cancel(); message = "已请求取消；已提交的导入可能保留在库存，请刷新确认。" }) { Text("取消操作", fontSize = 11.sp) } }
            LazyColumn(Modifier.weight(1f, fill = false), contentPadding = PaddingValues(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(VisionComponentKind.entries, key = { it.id }) { kind ->
                    val actual = status[kind.id]
                    val choice = inventory?.choices?.get(kind)
                    OutlinedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(8.dp)) {
                            Text(if (kind == VisionComponentKind.OPENCV) "OpenCV · 灰度找图" else "ONNX · 字母数字取字", fontSize = 12.sp)
                            Text(actual?.label ?: "运行状态未复查", fontSize = 11.sp)
                            Text(when (actual?.source) { "bundled" -> "正在使用内置组件，外部库存不会替换它"; "provider" -> "当前配置使用所选外部组件"; else -> "后端来源未确认" }, fontSize = 10.sp)
                            Text(if (actual?.loaded == true) "后端会话已创建；识别结果需另行测试" else "尚无已创建的后端会话", fontSize = 10.sp)
                            Text(if (choice?.corrupt == true) "选择记录损坏，未自动选择其他组件" else "已选：${choice?.id?.take(8) ?: "无"}", fontSize = 10.sp)
                            if (choice?.id != null || choice?.corrupt == true) TextButton(enabled = idle && !busy, onClick = { clear = kind }) { Text("取消选择", fontSize = 11.sp) }
                        }
                    }
                }
                item { Text("本地库存 · 最多8个 / 1GiB；加载后更换需重启进程，不自动重启或运行。", fontSize = 10.sp) }
                items(inventory?.entries.orEmpty(), key = { it.id }) { entry ->
                    val descriptor = entry.verified?.descriptor
                    val kind = descriptor?.kind
                    val actual = kind?.let { status[it.id] }
                    OutlinedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(8.dp)) {
                            Text(if (kind == null) "${if (entry.state == StoredVisionComponentState.INCOMPATIBLE) "不兼容" else "校验失败"} · ${entry.id.take(8)}"
                                else "${kind.id} ${kind.sdkVersion} · V${descriptor.componentVersion} · ${entry.id.take(8)}", fontSize = 12.sp)
                            if (descriptor != null) Text(if (descriptor.componentVersion >= 2)
                                (if (kind == VisionComponentKind.OPENCV) "灰度找图 / ORB特征" else "V4默认 / V5显式可选")
                                else "原版组件 · 不支持ORB / V5扩展操作", fontSize = 10.sp)
                            if (descriptor != null) Text("${descriptor.abis.sorted().joinToString(" / ")} · ${(entry.verified?.bytes ?: 0) / (1024 * 1024)}MiB", fontSize = 10.sp)
                            Row {
                                TextButton(enabled = idle && !busy && kind != null && entry.state == StoredVisionComponentState.READY &&
                                    actual?.source == "provider" && actual.state !in setOf("CHECKING", "BUSY"), onClick = {
                                    val selectedKind = kind ?: return@TextButton
                                    perform("选择已保存；若进程已加载其他版本，需自行重启后再运行。") { repository.select(selectedKind, entry.id) }
                                }) { Text(if (kind != null && inventory?.choices?.get(kind)?.id == entry.id) "已选择" else "选择", fontSize = 11.sp) }
                                TextButton(enabled = idle && !busy, onClick = { remove = entry }) { Text("删除", fontSize = 11.sp) }
                            }
                        }
                    }
                }
                if (inventory?.entries?.isEmpty() == true) item { Text("没有导入的组件；内置组件不计入此库存。", fontSize = 11.sp) }
            }
        }
    }
    remove?.let { entry -> StudioConfirmDialog(onDismissRequest = { remove = null }, title = { Text("删除所选组件？") },
        text = { Text("只删除本地导入的 ${entry.id.take(8)}，不动项目、图片或内置 SDK。若它已被选择，选择记录会保留为缺失，不回退其他组件。", fontSize = 11.sp) },
        confirmButton = { TextButton(enabled = idle && !busy, onClick = { remove = null; perform("组件已删除；项目与声明未改。") { repository.remove(entry.id) } }) { Text("删除") } },
        dismissButton = { TextButton(onClick = { remove = null }) { Text("取消") } }) }
    clear?.let { kind -> StudioConfirmDialog(onDismissRequest = { clear = null }, title = { Text("取消 ${kind.id} 选择？") },
        text = { Text("保留导入的 APK，不自动选择另一个组件；缺内置后端时相应操作将不可用。", fontSize = 11.sp) },
        confirmButton = { TextButton(enabled = idle && !busy, onClick = { clear = null; perform("选择已清除，库存保留。") { repository.clear(kind) } }) { Text("取消选择") } },
        dismissButton = { TextButton(onClick = { clear = null }) { Text("返回") } }) }
}

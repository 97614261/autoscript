package com.autoscript.studio

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.autoscript.core.designsystem.AutoScriptPalette
import com.autoscript.project.store.ProjectSnapshot
import com.autoscript.runtime.api.RecognitionPreviewReply as Reply
import com.autoscript.runtime.client.RuntimeClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/** Shared OCR configuration/results for screenshots and saved-image recognition tests. */
@Composable
internal fun ImageToolOcrDialog(bitmap: Bitmap, initialRoi: ImageToolCodeGen.Roi, snapshot: ProjectSnapshot,
    runtimeClient: RuntimeClient, onDismiss: () -> Unit,
    onConfirm: (ImageToolOcrConfig, ImageToolCodeGen.Roi) -> Unit) {
    val dictionaries = remember(snapshot.manifest.resources) { snapshot.manifest.resources.filter {
        it.get("kind")?.asString == "glyph_dictionary"
    }.mapNotNull { it.get("path")?.asString }.sorted() }
    var operation by remember { mutableIntStateOf(if (dictionaries.isEmpty()) Reply.ALPHANUMERIC else Reply.GLYPH) }
    var dictionary by remember { mutableStateOf(dictionaries.firstOrNull().orEmpty()) }
    var foreground by remember { mutableStateOf("FFFFFF") }
    var tolerance by remember { mutableStateOf("16") }
    var threshold by remember { mutableStateOf(if (dictionaries.isEmpty()) "500" else "900") }
    var gap by remember { mutableStateOf("2") }
    var bounds by remember { mutableStateOf(listOf(initialRoi.left, initialRoi.top, initialRoi.width, initialRoi.height).map(Int::toString)) }
    var chooseDictionary by remember { mutableStateOf(false) }
    var requestId by remember { mutableLongStateOf(0L) }
    var job by remember { mutableStateOf<Job?>(null) }
    var reply by remember { mutableStateOf<Reply?>(null) }
    var message by remember { mutableStateOf("框选原图区域后测试。字母数字仅支持单行 A–Z / a–z / 0–9；字库需选择项目已保存的 ASGLYPH 字库。") }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val busy = requestId != 0L
    val roi = imageRecognitionBounds(bounds)?.let { region -> ImageToolCodeGen.Roi(
        region.get("left").asInt, region.get("top").asInt, region.get("right").asInt, region.get("bottom").asInt) }
    val config = runCatching {
        ImageToolOcrConfig(operation, dictionary, foreground.removePrefix("#").toInt(16), tolerance.toInt(), threshold.toInt(), gap.toInt()).also {
            val region = requireNotNull(roi)
            require(region.right <= bitmap.width && region.bottom <= bitmap.height)
            it.arguments(region)
            if (operation == Reply.GLYPH) imageRecognitionDictionary(snapshot, dictionary)
            else require(region.width.toLong() <= region.height.toLong() * 20)
        }
    }.getOrNull()
    fun invalidate() { reply = null }
    fun cancel() {
        val id = requestId
        requestId = 0L
        if (id != 0L) runtimeClient.cancelRecognitionPreview(id)
        job?.cancel(); job = null
    }
    DisposableEffect(runtimeClient) { onDispose { cancel() } }
    Dialog(onDismissRequest = { cancel(); onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth(.94f).widthIn(max = 580.dp).heightIn(max = 640.dp), color = Color.White) {
            Column {
                Text("取字 · 配置与测试", color = AutoScriptPalette.Accent, modifier = Modifier.padding(14.dp))
                HorizontalDivider()
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(Reply.GLYPH to "字库 OCR", Reply.ALPHANUMERIC to "字母数字 ONNX").forEach { (value, label) ->
                            FilterChip(selected = operation == value, enabled = !busy, onClick = {
                                operation = value; threshold = if (value == Reply.GLYPH) "900" else "500"; invalidate()
                            }, label = { Text(label) })
                        }
                    }
                    Image(bitmap.asImageBitmap(), "取字原图", Modifier.fillMaxWidth().heightIn(max = 160.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf("X", "Y", "宽", "高").forEachIndexed { i, label ->
                            OutlinedTextField(bounds[i], { bounds = bounds.toMutableList().apply { set(i, it.take(10)) }; invalidate() },
                                label = { Text(label) }, singleLine = true, enabled = !busy, modifier = Modifier.weight(1f))
                        }
                    }
                    if (operation == Reply.GLYPH) {
                        OutlinedButton(onClick = { chooseDictionary = true }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                            Text("字库：${dictionary.substringAfterLast('/').ifEmpty { "未选择" }}")
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(foreground, { foreground = it.take(7); invalidate() }, label = { Text("文字颜色 RGB") }, singleLine = true, enabled = !busy, modifier = Modifier.weight(1f))
                            OutlinedTextField(tolerance, { tolerance = it.take(3); invalidate() }, label = { Text("颜色容差 0–255") }, singleLine = true, enabled = !busy, modifier = Modifier.weight(1f))
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(threshold, { threshold = it.take(4); invalidate() }, label = { Text(if (operation == Reply.GLYPH) "相似度 ‰" else "逐字最低置信度 ‰") }, singleLine = true, enabled = !busy, modifier = Modifier.weight(1f))
                        if (operation == Reply.GLYPH) OutlinedTextField(gap, { gap = it.take(5); invalidate() }, label = { Text("空格列数") }, singleLine = true, enabled = !busy, modifier = Modifier.weight(1f))
                    }
                    Text(message, color = AutoScriptPalette.TextSecondary)
                    if (config == null) Text("请检查范围、阈值和已声明字库；单行范围宽高比须不超过20。", color = AutoScriptPalette.Danger)
                    Button(enabled = !busy && config != null, onClick = {
                        val selected = config ?: return@Button
                        val region = roi ?: return@Button
                        // The worker owns this immutable copy, even when the screenshot window closes.
                        val copy = runCatching { bitmap.copy(Bitmap.Config.ARGB_8888, false) }.getOrNull()
                        if (copy == null) { message = "无法准备截图区域"; return@Button }
                        val id = System.nanoTime().coerceAtLeast(1L)
                        requestId = id; reply = null; message = "正在识别…"
                        job = scope.launch {
                            val result = withContext(Dispatchers.IO) {
                                var temporary: File? = null
                                try {
                                    val file = File.createTempFile("ocr-preview-", ".png", context.cacheDir).also { temporary = it }
                                    FileOutputStream(file).use { output -> require(copy.compress(Bitmap.CompressFormat.PNG, 100, output)) }
                                    runtimeClient.testRecognitionPreview(id, selected.operation, file,
                                        if (selected.operation == Reply.GLYPH) imageRecognitionDictionary(snapshot, selected.dictionaryPath) else null,
                                        selected.options(region))
                                } catch (_: Exception) { Reply(id, 0L, Reply.INVALID_INPUT, selected.operation) }
                                finally { temporary?.delete(); copy.recycle() }
                            }
                            if (requestId == id) { reply = result; message = recognitionPreviewMessage(result); requestId = 0L; job = null }
                        }.also { task -> task.invokeOnCompletion { if (!copy.isRecycled) copy.recycle() } }
                    }, modifier = Modifier.fillMaxWidth()) { Text(if (busy) "正在识别…" else "测试识别") }
                }
                HorizontalDivider()
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    TextButton(onClick = { if (busy) { cancel(); reply = null; message = "已取消识别" } else onDismiss() }) { Text(if (busy) "取消识别" else "返回") }
                    TextButton(enabled = !busy && config != null && reply?.status in setOf(Reply.SUCCESS, Reply.NOT_FOUND), onClick = {
                        val result = reply ?: return@TextButton
                        if (!runtimeClient.isRecognitionPreviewCurrent(result)) { reply = null; message = "运行服务会话已变化，请重新测试" }
                        else onConfirm(requireNotNull(config), requireNotNull(roi))
                    }) { Text("回填配置") }
                }
            }
        }
    }
    if (chooseDictionary) StudioConfirmDialog(onDismissRequest = { chooseDictionary = false }, title = { Text("选择项目字库") }, text = {
        Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
            if (dictionaries.isEmpty()) Text("项目还没有字库，请先导入或制作 ASGLYPH 字库")
            dictionaries.forEach { path -> TextButton(onClick = { dictionary = path; chooseDictionary = false; invalidate() }) { Text(path) } }
        }
    }, confirmButton = { TextButton(onClick = { chooseDictionary = false }) { Text("返回") } })
}

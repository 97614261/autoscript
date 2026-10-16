package com.autoscript.studio

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.text.selection.SelectionContainer
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
import com.autoscript.studio.StudioDialog as Dialog
import androidx.compose.ui.window.DialogProperties
import com.autoscript.core.designsystem.AutoScriptPalette
import com.autoscript.project.store.ProjectSnapshot
import com.autoscript.runtime.api.RecognitionPreviewReply as Reply
import com.autoscript.runtime.client.RuntimeClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/** Shared OCR configuration/results for screenshots and saved-image recognition tests. */
@Composable
internal fun ImageToolOcrDialog(bitmap: Bitmap, initialRoi: ImageToolCodeGen.Roi, snapshot: ProjectSnapshot,
    runtimeClient: RuntimeClient, onDismiss: () -> Unit,
    onConfirm: (ImageToolOcrConfig, ImageToolCodeGen.Roi) -> Unit, allowBackfill: Boolean = true,
    confirmLabel: String = "回填配置", onProofConfirmed: ((() -> Boolean) -> Unit)? = null,
    onConfigureResultReader: ((com.google.gson.JsonObject)->Unit)? = null,
    resultReadTarget: ImageWorkbenchInsertionTarget? = null) {
    val dictionaries = remember(snapshot.manifest.resources) { snapshot.manifest.resources.filter {
        isGlyphDictionaryResource(it)
    }.mapNotNull { it.get("path")?.asString }.sorted() }
    var operation by remember { mutableIntStateOf(if (dictionaries.isEmpty()) Reply.ALPHANUMERIC else Reply.GLYPH) }
    var detailedPreview by remember { mutableStateOf(true) }
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
    var proof by remember { mutableStateOf<TestedImageRecognition<Bitmap>?>(null) }
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
    fun invalidate() { reply = null; proof = null }
    val initialSettings = remember { listOf(operation.toString(), dictionary, foreground, tolerance, threshold, gap) + bounds }
    var discard by remember { mutableStateOf(false) }
    fun cancel() {
        val id = requestId
        requestId = 0L
        if (id != 0L) runtimeClient.cancelRecognitionPreview(id)
        job?.cancel(); job = null
        invalidate()
    }
    DisposableEffect(runtimeClient) { onDispose { cancel() } }
    fun close() {
        cancel()
        if (listOf(operation.toString(), dictionary, foreground, tolerance, threshold, gap) + bounds != initialSettings) discard = true
        else onDismiss()
    }
    var fullScreen by remember { mutableStateOf(false) }
    var readerArguments by remember { mutableStateOf<com.google.gson.JsonObject?>(null) }
    Dialog(onDismissRequest = ::close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(if (fullScreen) Modifier.fillMaxSize() else Modifier.fillMaxWidth(.94f).widthIn(max = 580.dp).heightIn(max = 640.dp), color = Color.White) {
            Column {
                RecognitionHeader("取字 · 配置与测试", ::close, fullScreen) { fullScreen = !fullScreen }
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    RecognitionAvailabilityNotice(if (operation == Reply.GLYPH) "ocr.glyph" else "ocr.alphanumeric", runtimeClient)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(Reply.GLYPH to "字库 OCR", Reply.ALPHANUMERIC to "字母数字 ONNX").forEach { (value, label) ->
                            FilterChip(selected = operation == value, enabled = !busy, onClick = {
                                operation = value; threshold = if (value == Reply.GLYPH) "900" else "500"; invalidate()
                            }, label = { Text(label) })
                        }
                    }
                    val details=reply?.takeIf { it.operation==Reply.GLYPH_DETAILS && it.status in setOf(Reply.SUCCESS,Reply.NOT_FOUND) }?.let { decodeOcrPreviewDetails(it.text,bitmap.width,bitmap.height) }
                    if(details!=null) OcrDetailPreview(bitmap,details,onReadResult=if(onConfigureResultReader==null && resultReadTarget==null) null else { index ->
                        val args=ocrPreviewReaderArguments(index)
                        if(onConfigureResultReader!=null) onConfigureResultReader(args) else readerArguments=args
                    })
                    else Image(bitmap.asImageBitmap(), "取字原图", Modifier.fillMaxWidth().heightIn(max = if (fullScreen) 280.dp else 160.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf("X", "Y", "宽", "高").forEachIndexed { i, label ->
                            OutlinedTextField(bounds[i], { bounds = bounds.toMutableList().apply { set(i, it.take(10)) }; invalidate() },
                                label = { Text(label) }, singleLine = true, enabled = !busy, modifier = Modifier.weight(1f))
                        }
                    }
                    if (operation == Reply.GLYPH) {
                        DesignerToggle("逐字预览（关闭则仅文本）",detailedPreview,!busy) { detailedPreview=it;invalidate() }
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
                    SelectionContainer { Text(message, color = AutoScriptPalette.TextSecondary) }
                    Text("坐标属于当前原图；置信度/字库相似度不是正确率保证。逐字位置请加入“逐字结果”，再读取结果字段。", style = MaterialTheme.typography.labelSmall)
                    if (config == null) Text("请检查范围、阈值和已声明字库；单行范围宽高比须不超过20。", color = AutoScriptPalette.Danger)
                    Button(enabled = !busy && config != null, onClick = {
                        val selected = config ?: return@Button
                        val selectedPreviewOperation=if(selected.operation==Reply.GLYPH && detailedPreview) Reply.GLYPH_DETAILS else selected.operation
                        val region = roi ?: return@Button
                        // The worker owns this immutable copy, even when the screenshot window closes.
                        val copy = runCatching { bitmap.copy(Bitmap.Config.ARGB_8888, false) }.getOrNull()
                        if (copy == null) { message = "无法准备截图区域"; return@Button }
                        val id = System.nanoTime().coerceAtLeast(1L)
                        requestId = id; invalidate(); message = "正在识别…"
                        job = scope.launch {
                            try {
                                prepareRecognitionPreview(runtimeClient, selected.operation)
                                val result = withContext(Dispatchers.IO) {
                                    var temporary: File? = null
                                    try {
                                        ensureActive()
                                        val file = File.createTempFile("ocr-preview-", ".png", context.cacheDir).also { temporary = it }
                                        FileOutputStream(file).use { output -> require(copy.compress(Bitmap.CompressFormat.PNG, 100, output)) }
                                        ensureActive()
                                        runtimeClient.testRecognitionPreview(id, selectedPreviewOperation, file,
                                            if (selected.operation == Reply.GLYPH) imageRecognitionDictionary(snapshot, selected.dictionaryPath) else null,
                                            selected.options(region))
                                    } finally { temporary?.delete() }
                                }
                                if (requestId == id) {
                                    val malformed = result.operation==Reply.GLYPH_DETAILS && result.status in setOf(Reply.SUCCESS,Reply.NOT_FOUND) && decodeOcrPreviewDetails(result.text,bitmap.width,bitmap.height)==null
                                    reply = if(malformed) null else result
                                    message = if (runtimeClient.isRecognitionPreviewCurrent(result)) recognitionPreviewMessage(result)
                                        else "运行服务会话已变化，请重新测试"
                                    proof = TestedImageRecognition(bitmap, selected.operation, selected.options(region), selected.dictionaryPath, result)
                                        .takeIf { !malformed && it.matches(bitmap, selected.operation, selected.options(region), selected.dictionaryPath, runtimeClient::isRecognitionPreviewCurrent) }
                                    if(malformed) message="逐字结果结构或坐标无效，未采纳，请重新测试"
                                }
                            } catch (cancelled: CancellationException) { throw cancelled }
                            catch (unavailable: RecognitionUnavailable) { if (requestId == id) message = unavailable.message.orEmpty() }
                            catch (_: Exception) { if (requestId == id) message = "识别测试失败，请检查图片和运行环境后重试" }
                            finally { copy.recycle(); if (requestId == id) { requestId = 0L; job = null } }
                        }.also { task -> task.invokeOnCompletion { if (!copy.isRecycled) copy.recycle() } }
                    }, modifier = Modifier.fillMaxWidth()) { Text(if (busy) "正在识别…" else "测试识别") }
                }
                HorizontalDivider()
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    TextButton(onClick = { if (busy) { cancel(); reply = null; message = "已取消识别" } else close() }) { Text(if (busy) "取消识别" else "返回") }
                    if (allowBackfill) TextButton(enabled = !busy && config != null && proof != null, onClick = {
                        val selected = config ?: return@TextButton
                        val region = roi ?: return@TextButton
                        val tested = proof
                        val options = selected.options(region)
                        val validity = { !bitmap.isRecycled && tested?.matches(bitmap, selected.operation, options,
                            selected.dictionaryPath, runtimeClient::isRecognitionPreviewCurrent) == true }
                        if (!validity()) {
                            invalidate(); message = "测试设置或运行服务会话已变化，请重新测试"
                        } else { onProofConfirmed?.invoke(validity); onConfirm(selected, region) }
                    }) { Text(confirmLabel) }
                }
            }
        }
    }
    readerArguments?.let { initial -> resultReadTarget?.let { target ->
        OcrResultReadInsertDialog(initial,snapshot,target,onDismiss={readerArguments=null},onMessage={message=it})
    } }
    if (discard) EditorPromptDialog("放弃取字配置？", "未回填的识别参数将丢弃。", { discard = false },
        { discard = false; onDismiss() }, "继续编辑", "放弃")
    if (chooseDictionary) StudioConfirmDialog(onDismissRequest = { chooseDictionary = false }, title = { Text("选择项目字库") }, text = {
        Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
            if (dictionaries.isEmpty()) Text("项目还没有字库，请先导入或制作 ASGLYPH 字库")
            dictionaries.forEach { path -> TextButton(onClick = { dictionary = path; chooseDictionary = false; invalidate() }) { Text(path) } }
        }
    }, confirmButton = { TextButton(onClick = { chooseDictionary = false }) { Text("返回") } })
}

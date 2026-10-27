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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autoscript.core.designsystem.AutoScriptPalette
import com.autoscript.project.store.ProjectSnapshot
import com.autoscript.project.store.ProjectGlyphColor
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
@OptIn(ExperimentalLayoutApi::class)
internal fun ImageToolOcrDialog(bitmap: Bitmap, initialRoi: ImageToolCodeGen.Roi, snapshot: ProjectSnapshot,
    runtimeClient: RuntimeClient, onDismiss: () -> Unit,
    onConfirm: (ImageToolOcrConfig, ImageToolCodeGen.Roi) -> Unit, allowBackfill: Boolean = true,
    confirmLabel: String = "回填配置", onProofConfirmed: ((() -> Boolean) -> Unit)? = null,
    onConfigureResultReader: ((com.google.gson.JsonObject)->Unit)? = null,
    resultReadTarget: ImageWorkbenchInsertionTarget? = null, initialConfig: ImageToolOcrConfig? = null,
    onSaveRecipe:((ImageToolOcrConfig,ImageToolCodeGen.Roi)->Unit)?=null) {
    val dictionaries = remember(snapshot.manifest.resources) { snapshot.manifest.resources.filter {
        isGlyphDictionaryResource(it)
    }.mapNotNull { it.get("path")?.asString }.sorted() }
    var operation by remember { mutableIntStateOf(initialConfig?.operation ?: if (dictionaries.isEmpty()) Reply.LATIN_TEXT else Reply.GLYPH) }
    var detailedPreview by remember { mutableStateOf(true) }
    var dictionary by remember { mutableStateOf(initialConfig?.dictionaryPath ?: dictionaries.firstOrNull().orEmpty()) }
    var palette by remember { mutableStateOf(GlyphPaletteDraft.from(initialConfig?.foregroundRgb ?: 0xFFFFFF,
        initialConfig?.tolerance ?: 16, initialConfig?.additionalColors.orEmpty())) }
    var threshold by remember { mutableStateOf(initialConfig?.threshold?.toString() ?: if (dictionaries.isEmpty()) "500" else "900") }
    var gap by remember { mutableStateOf(initialConfig?.spaceGapColumns?.toString() ?: "2") }
    var bounds by remember { mutableStateOf(listOf(initialRoi.left, initialRoi.top, initialRoi.width, initialRoi.height).map(Int::toString)) }
    var chooseDictionary by remember { mutableStateOf(false) }
    var requestId by remember { mutableLongStateOf(0L) }
    var job by remember { mutableStateOf<Job?>(null) }
    var reply by remember { mutableStateOf<Reply?>(null) }
    var proof by remember { mutableStateOf<TestedImageRecognition<Bitmap>?>(null) }
    val initialMessage = "框选单行英文、数字，可保留括号、加减号、小数点等ASCII符号及空格；不需要制作字库。识别结果写入文本变量，再用通用文本工具处理。自制字库需选择已保存的ASGLYPH资源。"
    var message by remember { mutableStateOf(initialMessage) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val resourceIdentity = snapshot.manifest.resources.toString() + ":" + snapshot.resourceRevision
    val latestResources by rememberUpdatedState(resourceIdentity)
    val busy = requestId != 0L
    val roi = imageRecognitionBounds(bounds)?.let { region -> ImageToolCodeGen.Roi(
        region.get("left").asInt, region.get("top").asInt, region.get("right").asInt, region.get("bottom").asInt) }
    val config = runCatching {
        val colors = if (operation == Reply.GLYPH) requireNotNull(palette.parsed()) else listOf(ProjectGlyphColor(0xFFFFFF,16))
        ImageToolOcrConfig(operation, dictionary, colors.first().rgb, colors.first().tolerance, threshold.toInt(), gap.toInt(),
            ProjectGlyphColor.encodeAdditional(colors.drop(1))).also {
            val region = requireNotNull(roi)
            require(region.right <= bitmap.width && region.bottom <= bitmap.height)
            it.arguments(region)
            if (operation == Reply.GLYPH) imageRecognitionDictionary(snapshot, dictionary)
            else require(region.width.toLong() <= region.height.toLong() * 20)
        }
    }.getOrNull()
    fun invalidate() { reply = null; proof = null }
    val initialSettings = remember { listOf(operation.toString(), dictionary, palette.colors.toString(), threshold, gap) + bounds }
    var discard by remember { mutableStateOf(false) }
    fun cancel() {
        val id = requestId
        requestId = 0L
        if (id != 0L) runtimeClient.cancelRecognitionPreview(id)
        job?.cancel(); job = null
        invalidate()
    }
    DisposableEffect(runtimeClient) { onDispose { cancel() } }
    LaunchedEffect(resourceIdentity) { cancel() }
    fun close() {
        cancel()
        if (listOf(operation.toString(), dictionary, palette.colors.toString(), threshold, gap) + bounds != initialSettings) discard = true
        else onDismiss()
    }
    var fullScreen by remember { mutableStateOf(false) }
    var showHelp by remember { mutableStateOf(false) }
    var readerArguments by remember { mutableStateOf<com.google.gson.JsonObject?>(null) }
    StudioPanelDialog("取字 · 配置与测试", ::close, fullScreen = fullScreen, showFooter = false,
        compactHeader = true, onToggleFullScreen = { fullScreen = !fullScreen }) {
            Column {
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    RecognitionAvailabilityNotice(when(operation) {Reply.GLYPH->"ocr.glyph";Reply.ALPHANUMERIC_V5->"ocr.alphanumericv5";else->"ocr.alphanumeric"}, runtimeClient)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(Reply.GLYPH to "字库", Reply.LATIN_TEXT to "英文数字V4",Reply.ALPHANUMERIC_V5 to "V5候选").forEach { (value, label) ->
                            ImageToolCompactAction((if (operation == value || value == Reply.LATIN_TEXT && operation == Reply.ALPHANUMERIC) "✓ " else "") + label, !busy) {
                                operation = value; threshold = if (value == Reply.GLYPH) "900" else "500"; invalidate()
                            }
                        }
                    }
                    if (operation in setOf(Reply.ALPHANUMERIC, Reply.LATIN_TEXT)) {
                        ImageToolCompactAction(if (operation == Reply.LATIN_TEXT) "✓ 保留符号 / 空格" else "仅字母数字", !busy) {
                            operation = if (operation == Reply.LATIN_TEXT) Reply.ALPHANUMERIC else Reply.LATIN_TEXT
                            invalidate()
                        }
                        if (operation == Reply.LATIN_TEXT) Text("支持 () + - . % / [] 等模型已识别的符号；需内置ONNX或V3组件。", fontSize = 10.sp, color = AutoScriptPalette.TextSecondary)
                    }
                    if(operation==Reply.ALPHANUMERIC_V5) Text("固定英文候选，默认V4不变；更快但准确率更低，不保证l/1改善。输出JSON；支持内置ONNX/full或已选定的V2离线组件，V1不支持。",fontSize=10.sp,color=AutoScriptPalette.TextSecondary)
                    val details=reply?.takeIf { it.operation==Reply.GLYPH_DETAILS && it.status in setOf(Reply.SUCCESS,Reply.NOT_FOUND) }?.let { decodeOcrPreviewDetails(it.text,bitmap.width,bitmap.height) }
                    if(details!=null) OcrDetailPreview(bitmap,details,onReadResult=if(onConfigureResultReader==null && resultReadTarget==null) null else { index ->
                        val args=ocrPreviewReaderArguments(index)
                        if(onConfigureResultReader!=null) onConfigureResultReader(args) else readerArguments=args
                    }, compact = true)
                    else Image(bitmap.asImageBitmap(), "取字原图", Modifier.fillMaxWidth().height(if (fullScreen) 160.dp else 96.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf("X", "Y", "宽", "高").forEachIndexed { i, label ->
                            ImageToolCompactInput(label, bounds[i], !busy, Modifier.weight(1f), numeric = true) {
                                bounds = bounds.toMutableList().apply { set(i, it.take(10)) }; invalidate()
                            }
                        }
                    }
                    if (operation == Reply.GLYPH) {
                        ImageToolCompactAction("字库：${dictionary.substringAfterLast('/').ifEmpty { "未选择" }}", !busy, Modifier.fillMaxWidth()) {
                            chooseDictionary = true
                        }
                        GlyphColorPaletteEditor(palette, !busy) { palette = it; invalidate() }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ImageToolCompactInput(if (operation == Reply.GLYPH) "相似度 ‰" else "置信度 ‰", threshold, !busy,
                            Modifier.weight(1f), numeric = true) { threshold = it.take(4); invalidate() }
                        if (operation == Reply.GLYPH) ImageToolCompactInput("空格列数", gap, !busy, Modifier.weight(1f), numeric = true) { gap = it.take(5); invalidate() }
                    }
                    Row {
                        if (operation == Reply.GLYPH) ImageToolCompactAction(if (detailedPreview) "✓ 逐字预览" else "仅文本预览", !busy) { detailedPreview = !detailedPreview; invalidate() }
                        ImageToolCompactAction(if (showHelp) "收起说明" else "说明", true) { showHelp = !showHelp }
                    }
                    if (reply != null || busy || message != initialMessage) SelectionContainer { Text(message, fontSize = 11.sp, color = AutoScriptPalette.TextSecondary) }
                    if (showHelp) Text(initialMessage + "\n坐标属于当前原图；相似度不是正确率保证。空格列数0关闭插空格。输出位置请用逐字结果。", fontSize = 10.sp)
                    if (config == null) Text("请检查范围、阈值和已声明字库；ONNX单行宽高比不超过20。", fontSize = 10.sp, color = AutoScriptPalette.Danger)
                    if(onSaveRecipe!=null) ImageToolCompactAction("保存为方案",!busy && config!=null && roi!=null) {config?.let {onSaveRecipe(it,requireNotNull(roi))}}
                    ImageToolCompactAction(if (busy) "正在识别…" else "测试识别", !busy && config != null, Modifier.fillMaxWidth()) test@{
                        val selected = config ?: return@test
                        val selectedPreviewOperation=if(selected.operation==Reply.GLYPH && detailedPreview) Reply.GLYPH_DETAILS else selected.operation
                        val region = roi ?: return@test
                        // The worker owns this immutable copy, even when the screenshot window closes.
                        val copy = runCatching { bitmap.copy(Bitmap.Config.ARGB_8888, false) }.getOrNull()
                        if (copy == null) { message = "无法准备截图区域"; return@test }
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
                    }
                }
                HorizontalDivider()
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    ImageToolCompactAction(if (busy) "取消识别" else "返回", true) { if (busy) { cancel(); reply = null; message = "已取消识别" } else close() }
                    if (allowBackfill) ImageToolCompactAction(confirmLabel, !busy && config != null && proof != null) confirm@{
                        val selected = config ?: return@confirm
                        val region = roi ?: return@confirm
                        val tested = proof
                        val options = selected.options(region)
                        val validity = { latestResources == resourceIdentity && !bitmap.isRecycled && tested?.matches(bitmap, selected.operation, options,
                            selected.dictionaryPath, runtimeClient::isRecognitionPreviewCurrent) == true }
                        if (!validity()) {
                            invalidate(); message = "测试设置或运行服务会话已变化，请重新测试"
                        } else { onProofConfirmed?.invoke(validity); onConfirm(selected, region) }
                    }
                }
            }
    }
    readerArguments?.let { initial -> resultReadTarget?.let { target ->
        OcrResultReadInsertDialog(initial,snapshot,target,onDismiss={readerArguments=null},onMessage={message=it})
    } }
    if (discard) EditorPromptDialog("放弃取字配置？", "未回填的识别参数将丢弃。", { discard = false },
        { discard = false; onDismiss() }, "继续编辑", "放弃")
    if (chooseDictionary) StudioConfirmDialog(onDismissRequest = { chooseDictionary = false }, prompt = false, title = { Text("选择项目字库") }, text = {
        Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
            if (dictionaries.isEmpty()) Text("项目还没有字库，请先导入或制作 ASGLYPH 字库")
            dictionaries.forEach { path -> TextButton(onClick = { dictionary = path; chooseDictionary = false; invalidate() }) { Text(path) } }
        }
    }, confirmButton = { TextButton(onClick = { chooseDictionary = false }) { Text("返回") } })
}

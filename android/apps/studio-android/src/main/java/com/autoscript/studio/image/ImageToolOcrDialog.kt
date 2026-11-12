package com.autoscript.studio

import android.graphics.Bitmap
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    onSaveRecipe:((ImageToolOcrConfig,ImageToolCodeGen.Roi)->Unit)?=null,
    initialDraft: ImageOcrDraft? = null, sourceIdentity: Any = bitmap,
    onRememberDraft: ((ImageOcrDraft) -> Unit)? = null) {
    val dictionaries = remember(snapshot.manifest.resources) { snapshot.manifest.resources.filter {
        isGlyphDictionaryResource(it)
    }.mapNotNull { it.get("path")?.asString }.sorted() }
    var operation by remember { mutableIntStateOf(initialDraft?.operation ?: initialConfig?.operation ?: if (dictionaries.isEmpty()) Reply.LATIN_TEXT else Reply.GLYPH) }
    var detailedPreview by remember { mutableStateOf(initialDraft?.detailed ?: true) }
    var dictionary by remember { mutableStateOf(initialDraft?.dictionary ?: initialConfig?.dictionaryPath ?: dictionaries.firstOrNull().orEmpty()) }
    var palette by remember { mutableStateOf(initialDraft?.palette ?: GlyphPaletteDraft.from(initialConfig?.foregroundRgb ?: 0xFFFFFF,
        initialConfig?.tolerance ?: 16, initialConfig?.additionalColors.orEmpty())) }
    var threshold by remember { mutableStateOf(initialDraft?.threshold ?: initialConfig?.threshold?.toString() ?: if (dictionaries.isEmpty()) "500" else "900") }
    var gap by remember { mutableStateOf(initialDraft?.gap ?: initialConfig?.spaceGapColumns?.toString() ?: "2") }
    var bounds by remember { mutableStateOf(initialDraft?.boundsFor(sourceIdentity,initialRoi) ?: listOf(initialRoi.left, initialRoi.top, initialRoi.width, initialRoi.height).map(Int::toString)) }
    SideEffect { onRememberDraft?.invoke(ImageOcrDraft(operation,dictionary,palette,threshold,gap,detailedPreview,bounds.toList(),sourceIdentity,initialRoi)) }
    var chooseDictionary by remember { mutableStateOf(false) }
    var requestId by remember { mutableLongStateOf(0L) }
    var job by remember { mutableStateOf<Job?>(null) }
    var reply by remember { mutableStateOf<Reply?>(null) }
    var proof by remember { mutableStateOf<TestedImageRecognition<Bitmap>?>(null) }
    val initialMessage = "框选单行英文、数字，可保留括号、加减号、小数点等ASCII符号及空格；不需要制作字库。识别结果写入文本变量，再用通用文本工具处理。自制字库需选择已保存的ASGLYPH资源。"
    var message by remember { mutableStateOf(initialMessage) }
    StudioSystemLogEffect("识字工具", message, skipInitial = true)
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
    fun invalidate() { reply = null; proof = null; message = "配置已修改，请重新测试；上次结果已清除" }
    val initialSettings = remember { listOf(operation.toString(), dictionary, palette.colors.toString(), threshold, gap) + bounds }
    var discard by remember { mutableStateOf(false) }
    fun cancel() {
        val id = requestId
        requestId = 0L
        if (id != 0L) runtimeClient.cancelRecognitionPreview(id)
        job?.cancel(); job = null
        reply = null; proof = null
    }
    DisposableEffect(runtimeClient) { onDispose { cancel() } }
    LaunchedEffect(resourceIdentity) { cancel() }
    val workspace = LocalImageWorkspace.current
    ImageWorkspaceDraftClear(if (roi != null && !busy) ({ bounds = listOf("", "", "", ""); invalidate() }) else null)
    fun close() {
        cancel()
        if (onRememberDraft == null && listOf(operation.toString(), dictionary, palette.colors.toString(), threshold, gap) + bounds != initialSettings) discard = true
        else onDismiss()
    }
    var fullScreen by remember { mutableStateOf(false) }
    var readerArguments by remember { mutableStateOf<com.google.gson.JsonObject?>(null) }
    ImageWorkspacePanel("文字识别", { close() }, fullScreen = fullScreen, showFooter = false,
        compactHeader = true, onToggleFullScreen = { fullScreen = !fullScreen }) {
            Column(Modifier.fillMaxSize()) {
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ImageWorkspaceSection("识别方式") {
                    ImageToolChoice("模型", (if(operation == Reply.ALPHANUMERIC) Reply.LATIN_TEXT else operation).toString(),
                        listOf(Reply.LATIN_TEXT.toString() to "英文数字 V4", Reply.GLYPH.toString() to "自制字库", Reply.ALPHANUMERIC_V5.toString() to "V5 候选"), !busy) {
                        operation = it.toInt(); threshold = if(operation == Reply.GLYPH) "900" else "500"; invalidate()
                    }
                    if (operation in setOf(Reply.ALPHANUMERIC, Reply.LATIN_TEXT)) {
                        ImageToolToggle("保留符号和空格", operation == Reply.LATIN_TEXT, !busy) {
                            operation = if (it) Reply.LATIN_TEXT else Reply.ALPHANUMERIC
                            invalidate()
                        }
                    }
                    if(operation==Reply.ALPHANUMERIC_V5) ImageWorkspaceHint("候选模型：速度优先，准确率可能低于 V4；输出 JSON，不能保证改善 l/1。")
                    }
                    ImageWorkspaceSection("识别区域") {
                    val details=reply?.takeIf { it.operation==Reply.GLYPH_DETAILS && it.status in setOf(Reply.SUCCESS,Reply.NOT_FOUND) }?.let { decodeOcrPreviewDetails(it.text,bitmap.width,bitmap.height) }
                    if(details!=null) OcrDetailPreview(bitmap,details,onReadResult=if(onConfigureResultReader==null && resultReadTarget==null) null else { index ->
                        val args=ocrPreviewReaderArguments(index)
                        if(onConfigureResultReader!=null) onConfigureResultReader(args) else readerArguments=args
                    }, compact = true)
                    else ImageWorkspaceRegionPreview(bitmap, roi, !busy, if(fullScreen) 160 else 96) { region ->
                        bounds = listOf(region.left, region.top, region.width, region.height).map(Int::toString)
                        invalidate()
                    }
                    StudioCompactGrid(listOf("X", "Y", "宽", "高").withIndex().toList(), cellDp = 82, maximum = 4) { (i, label), cell ->
                            ImageToolCompactInput(label, bounds[i], !busy, cell, numeric = true) {
                                bounds = bounds.toMutableList().apply { set(i, it.take(10)) }; invalidate()
                            }
                    }
                    }
                    ImageWorkspaceSection(if (operation == Reply.GLYPH) "字库与参数" else "识别参数") {
                    if (operation == Reply.GLYPH) {
                        ImageToolCompactAction("字库：${dictionary.substringAfterLast('/').ifEmpty { "未选择" }}", !busy, Modifier.fillMaxWidth()) {
                            chooseDictionary = true
                        }
                        GlyphColorPaletteEditor(palette, !busy) { palette = it; invalidate() }
                    }
                    StudioCompactGrid(if (operation == Reply.GLYPH) listOf("相似度 ‰", "空格列数") else listOf("置信度 ‰"), cellDp = 148, maximum = 2) { label, cell ->
                        ImageToolCompactInput(label, if (label == "空格列数") gap else threshold, !busy, cell, numeric = true) {
                            if (label == "空格列数") gap = it.take(5) else threshold = it.take(4)
                            invalidate()
                        }
                    }
                    if (operation == Reply.GLYPH) ImageToolToggle("逐字预览与定位", detailedPreview, !busy) { detailedPreview = it; invalidate() }
                    if (config == null) ImageWorkspaceHint(when {
                        roi == null || roi.right > bitmap.width || roi.bottom > bitmap.height -> "识别范围超出图片或为空，请在主图重新框选"
                        threshold.toIntOrNull() !in 0..1000 -> "置信度／相似度需填0–1000"
                        operation != Reply.GLYPH && roi.width.toLong() > roi.height.toLong()*20 -> "当前区域过长：请分段框选单行文字，宽度最多为高度的20倍"
                        operation == Reply.GLYPH && dictionary !in dictionaries -> "请选择项目中已保存的字库"
                        else -> "字库或颜色参数无效，请检查颜色、容差及空格列数"
                    }, error = true)
                    }
                    if (reply != null || busy || message != initialMessage) ImageWorkspaceSection("识别结果") {
                        SelectionContainer { Text(message, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface) }
                    }
                    ImageToolHelp(initialMessage + "\n坐标属于当前原图；相似度不是正确率保证。空格列数0关闭插空格。输出位置请用逐字结果。" +
                        (if(onRememberDraft!=null) "\n返回保留本次配置；换图片或重新选区后采用新范围，测试结果需重新生成。" else "")) {
                        RecognitionAvailabilityNotice(when(operation) {Reply.GLYPH->"ocr.glyph";Reply.ALPHANUMERIC_V5->"ocr.alphanumericv5";else->"ocr.alphanumeric"}, runtimeClient)
                    }
                    if(onSaveRecipe!=null) ImageToolCompactAction("保存为方案",!busy && config!=null && roi!=null) {config?.let {onSaveRecipe(it,requireNotNull(roi))}}
                }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp))
                HorizontalDivider()
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    ImageToolCompactAction(if (busy) "正在识别…" else "测试识别", !busy && config != null, primary = true) test@{
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
                                    val current = runtimeClient.isRecognitionPreviewCurrent(result)
                                    reply = if(malformed || !current) null else result
                                    message = if (current) recognitionPreviewMessage(result)
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
    if (discard) EditorPromptDialog("放弃取字配置？", "未回填的识别参数将丢弃。", { discard = false; workspace?.cancelNavigation?.invoke() },
        { discard = false; onDismiss() }, "继续编辑", "放弃")
    if (chooseDictionary) CompositionLocalProvider(LocalImageWorkspace provides null) {
        ImageWorkspaceConfirmPanel(onDismissRequest = { chooseDictionary = false }, prompt = false, title = { Text("选择项目字库") }, text = {
        Column {
            if (dictionaries.isEmpty()) Text("项目还没有字库，请先导入或制作 ASGLYPH 字库")
            dictionaries.forEach { path -> TextButton(onClick = { dictionary = path; chooseDictionary = false; invalidate() }) { Text(path) } }
        }
    }, confirmButton = { TextButton(onClick = { chooseDictionary = false }) { Text("返回") } })
    }
}

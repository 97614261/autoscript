package com.autoscript.studio

import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import com.autoscript.studio.StudioDialog as Dialog
import androidx.compose.ui.window.DialogProperties
import com.autoscript.core.designsystem.AutoScriptPalette
import com.autoscript.project.store.ProjectSnapshot
import com.autoscript.runtime.api.RecognitionPreviewReply as Reply
import com.autoscript.runtime.client.RuntimeClient
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import java.io.File

internal fun imageRecognitionFile(snapshot: ProjectSnapshot, path: String): File {
    require(snapshot.manifest.resources.any { it.get("kind")?.asString == "image" && it.get("path")?.asString == path }) { "图片未在项目中声明" }
    val file = projectFileWithinRoot(snapshot.directory, path)
    require(file.isFile && file.length() in 1..24L * 1024 * 1024) { "图片无效或超过预览预算" }
    return file
}

internal fun recognitionBlockSequence(arguments: JsonObject) = listOf(
    requireNotNull(BlockCatalog.find("screen.capture")) to JsonObject().apply { addProperty("resultVariable", arguments.get("frameVariable").asString) },
    requireNotNull(BlockCatalog.find("vision.findimage")) to arguments.deepCopy(),
    requireNotNull(BlockCatalog.find("screen.release")) to JsonObject().apply { addProperty("frameVariable", arguments.get("frameVariable").asString) },
)

private data class DecodedRecognitionImage(val path: String, val directory: File, val resources: String, val bitmap: android.graphics.Bitmap)

/** Test saved screenshot/template pairs with exactly the runtime's pixel matcher. */
@Composable
internal fun ImageRecognitionTestDialog(
    snapshot: ProjectSnapshot,
    runtimeClient: RuntimeClient,
    onDismiss: () -> Unit,
    onInsert: (JsonObject) -> Unit,
    onCapture: () -> Unit,
    onInsertRecognition: ((String, JsonObject) -> Unit)? = null,
    visible: Boolean = true,
    onConfigureResultReader: ((JsonObject)->Unit)? = null,
) {
    val resourceIdentity = snapshot.manifest.resources.toString() + ":" + snapshot.resourceRevision
    val paths = remember(resourceIdentity) {
        snapshot.manifest.resources.filter { it.get("kind")?.asString == "image" }.mapNotNull { it.get("path")?.asString }.sorted()
    }
    var source by remember { mutableStateOf(paths.firstOrNull().orEmpty()) }
    var template by remember { mutableStateOf(paths.lastOrNull().orEmpty()) }
    var tolerance by remember { mutableStateOf("0") }
    var similarity by remember { mutableStateOf("900") }
    var direction by remember { mutableIntStateOf(0) }
    var bounds by remember(source) { mutableStateOf<List<String>?>(null) }
    var pickSource by remember { mutableStateOf<Boolean?>(null) }
    var reply by remember { mutableStateOf<Reply?>(null) }
    var proof by remember { mutableStateOf<TestedImageRecognition<android.graphics.Bitmap>?>(null) }
    var operation by remember { mutableIntStateOf(Reply.TEMPLATE) }
    var showOcr by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("选取已保存的截屏和模板。坐标是原图像素，从左上角计算。") }
    var requestId by remember { mutableLongStateOf(0L) }
    var recognitionJob by remember { mutableStateOf<Job?>(null) }
    val busy = requestId != 0L
    val scope = rememberCoroutineScope()
    fun invalidate() { reply = null; proof = null }
    fun cancelRecognition() {
        val id = requestId
        requestId = 0L
        if (id != 0L) runtimeClient.cancelRecognitionPreview(id)
        recognitionJob?.cancel(); recognitionJob = null
        invalidate()
    }
    DisposableEffect(runtimeClient) { onDispose { cancelRecognition() } }
    LaunchedEffect(snapshot.manifest.projectId, resourceIdentity) { cancelRecognition() }
    // Decode only bounded, declared files; no bitmap is recycled while Compose/worker owns it.
    val decoded by produceState<DecodedRecognitionImage?>(null, source, snapshot.directory, resourceIdentity) {
        val selectedSource = source
        val selectedSnapshot = snapshot
        value = null
        var prepared: android.graphics.Bitmap? = null
        try { value = withContext(Dispatchers.IO) {
            runCatching {
                val file = imageRecognitionFile(selectedSnapshot, selectedSource)
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.path, bounds)
                require(bounds.outWidth in 1..4096 && bounds.outHeight in 1..4096 && bounds.outWidth.toLong() * bounds.outHeight <= 4_194_304)
                BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inScaled = false })?.let {
                    prepared = it
                    DecodedRecognitionImage(selectedSource, selectedSnapshot.directory, resourceIdentity, it)
                }
            }.getOrNull()
        }; prepared = null } finally { prepared?.recycle() }
    }
    // produceState is launched after composition; never borrow the previous file's dimensions meanwhile.
    val bitmap = decoded?.takeIf { it.path == source && it.directory == snapshot.directory && it.resources == resourceIdentity }?.bitmap
    DisposableEffect(bitmap) { onDispose { bitmap?.recycle() } }
    val boundTexts = bounds ?: listOf("0", "0", bitmap?.width?.toString().orEmpty(), bitmap?.height?.toString().orEmpty())
    val region = bitmap?.let { recognitionPreviewRegion(boundTexts, it.width, it.height) }
    val configuration = bitmap?.let { frame -> runCatching {
        RecognitionPreviewConfiguration(operation, template, if (operation == Reply.GRAY_TEMPLATE) 0 else tolerance.toInt(),
            similarity.toInt(), frame.width, frame.height, requireNotNull(region), if (operation == Reply.TEMPLATE) direction else 0)
    }.getOrNull() }
    val parametersValid = configuration != null
    var fullScreen by remember { mutableStateOf(false) }
    if (visible) Dialog(onDismissRequest = { cancelRecognition(); onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(if (fullScreen) Modifier.fillMaxSize() else Modifier.widthIn(max = 550.dp).fillMaxWidth(.94f)
            .heightIn(max = minOf(520, (LocalConfiguration.current.screenHeightDp - 32).coerceAtLeast(120)).dp),
            color = Color.White, shape = RoundedCornerShape(if (fullScreen) 0.dp else 6.dp)) {
            Column {
                RecognitionHeader("识别测试 · 已保存图片", { cancelRecognition(); onDismiss() }, fullScreen) { fullScreen = !fullScreen }
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    RecognitionChoice("截屏：${source.substringAfterLast('/').ifEmpty { "未选择" }}", Modifier.fillMaxWidth(), enabled = !busy) { pickSource = true }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(Reply.TEMPLATE to "像素找图", Reply.GRAY_TEMPLATE to "灰度找图").forEach { (value, label) ->
                            RecognitionChoice((if (operation == value) "● " else "") + label, Modifier.weight(1f), enabled = !busy &&
                                (value == Reply.TEMPLATE || onInsertRecognition != null)) { operation = value; invalidate() }
                        }
                        RecognitionChoice("取字测试", Modifier.weight(1f), enabled = !busy && bitmap != null && region != null && onInsertRecognition != null) { showOcr = true }
                    }
                    RecognitionChoice("模板：${template.substringAfterLast('/').ifEmpty { "未选择" }}", Modifier.fillMaxWidth(), enabled = !busy) { pickSource = false }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text("容差 0–255", fontSize = 10.sp, color = AutoScriptPalette.TextSecondary)
                            RecognitionInput(tolerance, { if (it.length <= 16) { tolerance = it; invalidate() } }, Modifier.fillMaxWidth(), numeric = true, enabled = !busy && operation == Reply.TEMPLATE)
                        }
                        Column(Modifier.weight(1f)) {
                            Text("相似度 ‰", fontSize = 10.sp, color = AutoScriptPalette.TextSecondary)
                            RecognitionInput(similarity, { if (it.length <= 16) { similarity = it; invalidate() } }, Modifier.fillMaxWidth(), numeric = true, enabled = !busy)
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf("X", "Y", "宽", "高").forEachIndexed { index, label ->
                            DesignerInput("$label px", boundTexts[index], !busy && bitmap != null, Modifier.weight(1f), numeric = true) { value ->
                                if (value.length <= 16) bounds = boundTexts.toMutableList().apply { this[index] = value }
                                invalidate()
                            }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(enabled = !busy && bitmap != null, onClick = { bounds = null; invalidate() }) { Text("使用原图全范围", fontSize = 11.sp) }
                        Text(if (region == null) "范围无效，须在原图内" else "右/下边界不包含", fontSize = 10.sp,
                            color = if (region == null) AutoScriptPalette.Danger else AutoScriptPalette.TextSecondary)
                    }
                    if (operation == Reply.TEMPLATE) DesignerChoice("搜索方向", recognitionSearchDirections[direction],
                        recognitionSearchDirections.mapIndexed { index, label -> index.toString() to label }, !busy) {
                        direction = it.toInt(); invalidate()
                    }
                    Text(if (operation == Reply.TEMPLATE) "单张模板测试；范围、方向和阈值原样回填。多模板/目录及频次、动作需在正式配置中设置，未在此测试。"
                        else "灰度仅测试单模板最高分，不使用方向/颜色容差；不缩放旋转。", fontSize = 10.sp)
                    if (bitmap == null) Text("正在读取图片，或图片缺失、解码失败、超过预览预算；请选择有效原图。", fontSize = 10.sp)
                    bitmap?.let { frame ->
                        Box(Modifier.fillMaxWidth().height(if (fullScreen) 280.dp else 160.dp), contentAlignment = Alignment.Center) {
                            Image(frame.asImageBitmap(), "识别原图", Modifier.fillMaxSize())
                            Canvas(Modifier.fillMaxSize()) {
                                val scale = minOf(size.width / frame.width, size.height / frame.height)
                                val origin = Offset((size.width - frame.width * scale) / 2, (size.height - frame.height * scale) / 2)
                                region?.let { roi -> drawRect(AutoScriptPalette.Accent, origin + Offset(roi.left * scale, roi.top * scale),
                                    Size(roi.width * scale, roi.height * scale), style = Stroke(1.dp.toPx())) }
                                reply?.takeIf { it.status == Reply.SUCCESS }?.let { match ->
                                    drawCircle(Color.Red, 7.dp.toPx(), origin + Offset(match.x * scale, match.y * scale), style = Stroke(2.dp.toPx()))
                                }
                            }
                        }
                    }
                    RecognitionAvailabilityNotice(if (operation == Reply.GRAY_TEMPLATE) "vision.findgray" else "vision.findimage", runtimeClient)
                    Text(message, color = AutoScriptPalette.TextSecondary, fontSize = 11.sp)
                    RecognitionChoice(if (busy) "正在识别…" else "开始测试", Modifier.fillMaxWidth(), enabled = !busy && parametersValid && template.isNotEmpty()) {
                        val frame = bitmap ?: return@RecognitionChoice
                        val selectedConfiguration = configuration ?: return@RecognitionChoice
                        val id = System.nanoTime().coerceAtLeast(1L)
                        requestId = id
                        invalidate()
                        val selectedOperation = operation
                        val selectedSource = source
                        val selectedTemplate = template
                        val options = selectedConfiguration.options()
                        recognitionJob = scope.launch {
                            editorOperation({
                                if (requestId == id) message = if (it is RecognitionUnavailable) it.message.orEmpty() else "识别测试失败，请检查图片和运行环境后重试"
                            }, {
                                if (requestId == id) { requestId = 0L; recognitionJob = null }
                            }) {
                                prepareRecognitionPreview(runtimeClient, selectedOperation)
                                val result = withContext(Dispatchers.IO) {
                                    runtimeClient.testRecognitionPreview(id, selectedOperation, imageRecognitionFile(snapshot, selectedSource), imageRecognitionFile(snapshot, selectedTemplate), options)
                                }
                                if (requestId == id) {
                                    reply = result
                                    message = recognitionPreviewMessage(result)
                                    proof = TestedImageRecognition(frame, selectedOperation, options, selectedTemplate, result)
                                        .takeIf { it.matches(frame, selectedOperation, options, selectedTemplate, runtimeClient::isRecognitionPreviewCurrent) }
                                }
                            }
                        }
                    }
                    RecognitionChoice("截图取材 / 标注模板", Modifier.fillMaxWidth(), enabled = !busy) { invalidate(); onCapture() }
                }
                HorizontalDivider()
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { if (busy) { cancelRecognition(); reply = null; message = "已取消识别" } else onDismiss() }) { Text(if (busy) "取消识别" else "返回") }
                    TextButton(enabled = !busy && parametersValid && template.isNotEmpty() && reply?.status in setOf(Reply.SUCCESS, Reply.NOT_FOUND), onClick = {
                        val frame = bitmap ?: return@TextButton
                        val selectedConfiguration = configuration ?: return@TextButton
                        if (proof?.matches(frame, operation, selectedConfiguration.options(), template, runtimeClient::isRecognitionPreviewCurrent) != true) {
                            invalidate(); message = "测试图片、参数或运行服务会话已变化，请重新测试"
                        }
                        else {
                            val args = selectedConfiguration.arguments()
                            if (operation == Reply.TEMPLATE) onInsert(args) else onInsertRecognition?.invoke("vision.findgray", args)
                        }
                    }) { Text("回填找图配置") }
                }
            }
        }
    }
    val ocrBitmap = bitmap
    if (visible && showOcr && ocrBitmap != null) ImageToolOcrDialog(ocrBitmap,
        region ?: ImageToolCodeGen.Roi(0, 0, ocrBitmap.width, ocrBitmap.height), snapshot, runtimeClient,
        onDismiss = { showOcr = false }, onConfirm = { config, roi ->
            showOcr = false; onInsertRecognition?.invoke(config.kind, config.arguments(roi))
        },onConfigureResultReader=onConfigureResultReader)
    pickSource?.takeIf { visible }?.let { choosingSource ->
        var query by remember(choosingSource) { mutableStateOf("") }
        StudioPanelDialog(if (choosingSource) "选择截屏图像" else "选择模板", { pickSource = null }) {
            Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                RecognitionInput(query, { query = it }, Modifier.fillMaxWidth())
                if (paths.isEmpty()) Text("暂无图片，请先截图并保存", fontSize = 11.sp)
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 230.dp)) {
                    items(paths.filter { it.contains(query, true) }, key = { it }) { path ->
                        Text(path, Modifier.fillMaxWidth().clickable {
                            if (choosingSource) source = path else template = path
                            invalidate()
                            pickSource = null
                        }.padding(vertical = 8.dp), fontSize = 11.sp)
                    }
                }
            }
        }
    }
}

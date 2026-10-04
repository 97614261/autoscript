package com.autoscript.studio

import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
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

internal fun recognitionBlockArguments(path: String, tolerance: Int, similarity: Int, width: Int, height: Int) = JsonObject().apply {
    require(path.startsWith("assets/images/") && tolerance in 0..255 && similarity in 0..1000 && width > 0 && height > 0)
    addProperty("imagePath", path)
    addProperty("tolerance", tolerance)
    addProperty("similarityPermille", similarity)
    addProperty("frameVariable", "frame")
    addProperty("foundVariable", "found")
    addProperty("xVariable", "foundX")
    addProperty("yVariable", "foundY")
    add("region", JsonObject().apply {
        addProperty("left", 0); addProperty("top", 0); addProperty("right", width); addProperty("bottom", height)
    })
}

internal fun recognitionBlockSequence(arguments: JsonObject) = listOf(
    requireNotNull(BlockCatalog.find("screen.capture")) to JsonObject().apply { addProperty("resultVariable", arguments.get("frameVariable").asString) },
    requireNotNull(BlockCatalog.find("vision.findimage")) to arguments.deepCopy(),
    requireNotNull(BlockCatalog.find("screen.release")) to JsonObject().apply { addProperty("frameVariable", arguments.get("frameVariable").asString) },
)

private data class DecodedRecognitionImage(val path: String, val directory: File, val bitmap: android.graphics.Bitmap)

/** Test saved screenshot/template pairs with exactly the runtime's pixel matcher. */
@Composable
internal fun ImageRecognitionTestDialog(
    snapshot: ProjectSnapshot,
    runtimeClient: RuntimeClient,
    onDismiss: () -> Unit,
    onInsert: (JsonObject) -> Unit,
    onCapture: () -> Unit,
    onInsertRecognition: ((String, JsonObject) -> Unit)? = null,
) {
    val paths = remember(snapshot.manifest.resources) {
        snapshot.manifest.resources.filter { it.get("kind")?.asString == "image" }.mapNotNull { it.get("path")?.asString }.sorted()
    }
    var source by remember { mutableStateOf(paths.firstOrNull().orEmpty()) }
    var template by remember { mutableStateOf(paths.lastOrNull().orEmpty()) }
    var tolerance by remember { mutableStateOf("0") }
    var similarity by remember { mutableStateOf("900") }
    var pickSource by remember { mutableStateOf<Boolean?>(null) }
    var reply by remember { mutableStateOf<Reply?>(null) }
    var operation by remember { mutableIntStateOf(Reply.TEMPLATE) }
    var showOcr by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("选取已保存的截屏和模板。坐标是原图像素，从左上角计算。") }
    var requestId by remember { mutableLongStateOf(0L) }
    var recognitionJob by remember { mutableStateOf<Job?>(null) }
    val busy = requestId != 0L
    val scope = rememberCoroutineScope()
    fun cancelRecognition() {
        val id = requestId
        requestId = 0L
        if (id != 0L) runtimeClient.cancelRecognitionPreview(id)
        recognitionJob?.cancel(); recognitionJob = null
    }
    DisposableEffect(runtimeClient) { onDispose { cancelRecognition() } }
    // Decode only bounded, declared files; no bitmap is recycled while Compose/worker owns it.
    val decoded by produceState<DecodedRecognitionImage?>(null, source, snapshot.directory) {
        val selectedSource = source
        val selectedSnapshot = snapshot
        value = null
        value = withContext(Dispatchers.IO) {
            runCatching {
                val file = imageRecognitionFile(selectedSnapshot, selectedSource)
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.path, bounds)
                require(bounds.outWidth in 1..4096 && bounds.outHeight in 1..4096 && bounds.outWidth.toLong() * bounds.outHeight <= 4_194_304)
                BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inScaled = false })?.let {
                    DecodedRecognitionImage(selectedSource, selectedSnapshot.directory, it)
                }
            }.getOrNull()
        }
    }
    // produceState is launched after composition; never borrow the previous file's dimensions meanwhile.
    val bitmap = decoded?.takeIf { it.path == source && it.directory == snapshot.directory }?.bitmap
    val parametersValid = tolerance.toIntOrNull() in 0..255 && similarity.toIntOrNull() in 0..1000
    Dialog(onDismissRequest = { cancelRecognition(); onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth(.94f).widthIn(max = 580.dp).fillMaxHeight(.82f), color = Color.White) {
            Column {
                Text("测试识别", color = AutoScriptPalette.Accent, modifier = Modifier.padding(14.dp))
                HorizontalDivider()
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { pickSource = true }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("截屏图像：${source.substringAfterLast('/').ifEmpty { "未选择" }}") }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(Reply.TEMPLATE to "像素找图", Reply.GRAY_TEMPLATE to "灰度找图").forEach { (value, label) ->
                            FilterChip(operation == value, onClick = { operation = value; reply = null }, enabled = !busy &&
                                (value == Reply.TEMPLATE || onInsertRecognition != null), label = { Text(label) })
                        }
                        TextButton(onClick = { showOcr = true }, enabled = !busy && bitmap != null && onInsertRecognition != null) { Text("取字测试") }
                    }
                    OutlinedButton(onClick = { pickSource = false }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("模板：${template.substringAfterLast('/').ifEmpty { "未选择" }}") }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(tolerance, { tolerance = it.take(3); reply = null }, label = { Text("容差 0–255") }, singleLine = true, enabled = !busy && operation == Reply.TEMPLATE, modifier = Modifier.weight(1f))
                        OutlinedTextField(similarity, { similarity = it.take(4); reply = null }, label = { Text("相似度 ‰") }, singleLine = true, enabled = !busy, modifier = Modifier.weight(1f))
                    }
                    bitmap?.let { frame ->
                        Box(Modifier.fillMaxWidth().aspectRatio(frame.width.toFloat() / frame.height)) {
                            Image(frame.asImageBitmap(), "识别原图", Modifier.fillMaxSize())
                            reply?.takeIf { it.status == Reply.SUCCESS }?.let { match ->
                                Canvas(Modifier.fillMaxSize()) {
                                    val point = Offset(match.x.toFloat() / frame.width * size.width, match.y.toFloat() / frame.height * size.height)
                                    drawCircle(Color.Red, 7.dp.toPx(), point, style = Stroke(2.dp.toPx()))
                                }
                            }
                        }
                    }
                    Text(message, color = AutoScriptPalette.TextSecondary)
                    Button(enabled = !busy && parametersValid && bitmap != null && template.isNotEmpty(), onClick = {
                        val frame = bitmap ?: return@Button
                        val id = System.nanoTime().coerceAtLeast(1L)
                        requestId = id
                        reply = null
                        val selectedOperation = operation
                        val selectedSource = source
                        val selectedTemplate = template
                        val options = intArrayOf(0, 0, frame.width, frame.height, similarity.toInt(), 0xFFFFFF, tolerance.toInt(), 0)
                        recognitionJob = scope.launch {
                            val result = withContext(Dispatchers.IO) {
                                runCatching { runtimeClient.testRecognitionPreview(id, selectedOperation, imageRecognitionFile(snapshot, selectedSource), imageRecognitionFile(snapshot, selectedTemplate), options) }
                            }
                            if (requestId == id) {
                                reply = result.getOrNull()
                                message = reply?.let(::recognitionPreviewMessage) ?: "图片格式、尺寸或参数无效"
                                requestId = 0L; recognitionJob = null
                            }
                        }
                    }, modifier = Modifier.fillMaxWidth()) { Text(if (busy) "正在识别…" else "测试识别") }
                    TextButton(onClick = onCapture, enabled = !busy) { Text("截屏 / 框选取字 / 标注模板") }
                }
                HorizontalDivider()
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { if (busy) { cancelRecognition(); reply = null; message = "已取消识别" } else onDismiss() }) { Text(if (busy) "取消识别" else "返回") }
                    TextButton(enabled = !busy && parametersValid && bitmap != null && template.isNotEmpty() && reply?.status in setOf(Reply.SUCCESS, Reply.NOT_FOUND), onClick = {
                        val frame = bitmap ?: return@TextButton
                        if (!runtimeClient.isRecognitionPreviewCurrent(requireNotNull(reply))) { reply = null; message = "运行服务会话已变化，请重新测试" }
                        else {
                            val args = recognitionBlockArguments(template, tolerance.toInt(), similarity.toInt(), frame.width, frame.height)
                            if (operation == Reply.TEMPLATE) onInsert(args) else onInsertRecognition?.invoke("vision.findgray", args.apply {
                                remove("tolerance"); addProperty("scoreVariable", "grayScore"); addProperty("autoCapture", true)
                            })
                        }
                    }) { Text("回填找图配置") }
                }
            }
        }
    }
    val ocrBitmap = bitmap
    if (showOcr && ocrBitmap != null) ImageToolOcrDialog(ocrBitmap,
        ImageToolCodeGen.Roi(0, 0, ocrBitmap.width, ocrBitmap.height), snapshot, runtimeClient,
        onDismiss = { showOcr = false }, onConfirm = { config, roi ->
            showOcr = false; onInsertRecognition?.invoke(config.kind, config.arguments(roi))
        })
    pickSource?.let { choosingSource ->
        Dialog(onDismissRequest = { pickSource = null }) {
            Surface(color = Color.White) {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()).padding(12.dp)) {
                    Text(if (choosingSource) "选择截屏图像" else "选择模板", color = AutoScriptPalette.Accent)
                    if (paths.isEmpty()) Text("暂无图片，请先截图并保存")
                    paths.forEach { path ->
                        Text(path, Modifier.fillMaxWidth().clickable {
                            if (choosingSource) source = path else template = path
                            reply = null
                            pickSource = null
                        }.padding(vertical = 12.dp))
                    }
                }
            }
        }
    }
}

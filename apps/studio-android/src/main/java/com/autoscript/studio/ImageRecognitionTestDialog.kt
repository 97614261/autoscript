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
import com.autoscript.runtime.api.TemplateMatchReply
import com.autoscript.runtime.client.RuntimeClient
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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

/** Test saved screenshot/template pairs with exactly the runtime's pixel matcher. */
@Composable
internal fun ImageRecognitionTestDialog(
    snapshot: ProjectSnapshot,
    runtimeClient: RuntimeClient,
    onDismiss: () -> Unit,
    onInsert: (JsonObject) -> Unit,
    onCapture: () -> Unit,
) {
    val paths = remember(snapshot.manifest.resources) {
        snapshot.manifest.resources.filter { it.get("kind")?.asString == "image" }.mapNotNull { it.get("path")?.asString }.sorted()
    }
    var source by remember { mutableStateOf(paths.firstOrNull().orEmpty()) }
    var template by remember { mutableStateOf(paths.lastOrNull().orEmpty()) }
    var tolerance by remember { mutableStateOf("0") }
    var similarity by remember { mutableStateOf("900") }
    var pickSource by remember { mutableStateOf<Boolean?>(null) }
    var reply by remember { mutableStateOf<TemplateMatchReply?>(null) }
    var message by remember { mutableStateOf("选取已保存的截屏和模板。坐标是原图像素，从左上角计算。") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    // Decode only bounded, declared files; no bitmap is recycled while Compose/worker owns it.
    val bitmap by produceState<android.graphics.Bitmap?>(null, source, snapshot.directory) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val file = imageRecognitionFile(snapshot, source)
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.path, bounds)
                require(bounds.outWidth in 1..4096 && bounds.outHeight in 1..4096 && bounds.outWidth.toLong() * bounds.outHeight <= 4_194_304)
                BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inScaled = false })
            }.getOrNull()
        }
    }
    val parametersValid = tolerance.toIntOrNull() in 0..255 && similarity.toIntOrNull() in 0..1000
    Dialog(onDismissRequest = { if (!busy) onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth(.94f).widthIn(max = 580.dp).fillMaxHeight(.82f), color = Color.White) {
            Column {
                Text("测试识别 · 模板找图", color = AutoScriptPalette.Accent, modifier = Modifier.padding(14.dp))
                HorizontalDivider()
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { pickSource = true }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("截屏图像：${source.substringAfterLast('/').ifEmpty { "未选择" }}") }
                    OutlinedButton(onClick = { pickSource = false }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("模板：${template.substringAfterLast('/').ifEmpty { "未选择" }}") }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(tolerance, { tolerance = it.take(3); reply = null }, label = { Text("容差 0–255") }, singleLine = true, enabled = !busy, modifier = Modifier.weight(1f))
                        OutlinedTextField(similarity, { similarity = it.take(4); reply = null }, label = { Text("相似度 ‰") }, singleLine = true, enabled = !busy, modifier = Modifier.weight(1f))
                    }
                    bitmap?.let { frame ->
                        Box(Modifier.fillMaxWidth().aspectRatio(frame.width.toFloat() / frame.height)) {
                            Image(frame.asImageBitmap(), "识别原图", Modifier.fillMaxSize())
                            reply?.takeIf { it.status == TemplateMatchReply.MATCH }?.let { match ->
                                Canvas(Modifier.fillMaxSize()) {
                                    val point = Offset(match.x.toFloat() / frame.width * size.width, match.y.toFloat() / frame.height * size.height)
                                    drawCircle(Color.Red, 7.dp.toPx(), point, style = Stroke(2.dp.toPx()))
                                }
                            }
                        }
                    }
                    Text(message, color = AutoScriptPalette.TextSecondary)
                    Button(enabled = !busy && parametersValid && bitmap != null && template.isNotEmpty(), onClick = {
                        busy = true
                        reply = null
                        scope.launch {
                            val result = withContext(Dispatchers.IO) {
                                runCatching { runtimeClient.testTemplate(imageRecognitionFile(snapshot, source), imageRecognitionFile(snapshot, template), tolerance.toInt(), similarity.toInt()) }
                            }
                            reply = result.getOrNull()
                            message = when (reply?.status) {
                                TemplateMatchReply.MATCH -> "已找到：(${reply!!.x}, ${reply!!.y}) · ${reply!!.scorePermille}‰"
                                TemplateMatchReply.NOT_FOUND -> "未找到符合条件的模板"
                                TemplateMatchReply.BUSY -> "脚本运行或其他测试进行中，请先停止"
                                TemplateMatchReply.BUDGET_EXCEEDED -> "超过匹配预算，请裁剪截屏/模板后重试"
                                TemplateMatchReply.SESSION_MISMATCH -> "运行服务会话未建立或已变化，请重试"
                                else -> result.exceptionOrNull()?.message ?: "图片格式、尺寸或参数无效"
                            }
                            busy = false
                        }
                    }, modifier = Modifier.fillMaxWidth()) { Text(if (busy) "正在识别…" else "测试识别") }
                    TextButton(onClick = onCapture, enabled = !busy) { Text("截屏 / 标注新模板") }
                }
                HorizontalDivider()
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onDismiss, enabled = !busy) { Text("取消") }
                    TextButton(enabled = !busy && parametersValid && bitmap != null && template.isNotEmpty(), onClick = {
                        val frame = bitmap ?: return@TextButton
                        onInsert(recognitionBlockArguments(template, tolerance.toInt(), similarity.toInt(), frame.width, frame.height))
                    }) { Text("加入找图积木") }
                }
            }
        }
    }
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

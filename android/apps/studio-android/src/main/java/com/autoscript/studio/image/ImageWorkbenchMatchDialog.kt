package com.autoscript.studio

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autoscript.project.store.ProjectSnapshot
import com.autoscript.runtime.api.RecognitionPreviewReply as Reply
import com.autoscript.runtime.client.RuntimeClient
import kotlinx.coroutines.*
import androidx.compose.ui.platform.LocalContext
import java.io.File
import java.io.FileOutputStream

/** Tests the displayed processed bitmap, not a fresh screen or a silently substituted algorithm. */
@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun ImageWorkbenchMatchDialog(bitmap: Bitmap, selection: ImageToolCodeGen.Roi?, snapshot: ProjectSnapshot,
    runtimeClient: RuntimeClient, onResult: (Reply?) -> Unit, onDismiss: () -> Unit,
    onConfirm: ((RecognitionPreviewConfiguration, ImageToolCodeGen.Roi, () -> Boolean) -> Unit)? = null,
    initialConfig:RecognitionPreviewConfiguration?=null,
    onSaveRecipe:((RecognitionPreviewConfiguration,ImageToolCodeGen.Roi)->Unit)?=null) {
    val paths = snapshot.manifest.resources.filter { it.get("kind")?.asString == "image" }.mapNotNull { it.get("path")?.asString }
    var template by remember { mutableStateOf(initialConfig?.template ?: paths.firstOrNull().orEmpty()) }
    var similarity by remember { mutableStateOf(initialConfig?.similarityPermille?.toString() ?: "900") }
    var tolerance by remember { mutableStateOf(initialConfig?.tolerance?.toString() ?: "0") }
    var gray by remember { mutableStateOf(initialConfig?.operation==Reply.GRAY_TEMPLATE) }
    var scaled by remember {mutableStateOf(initialConfig?.operation==Reply.SCALED_TEMPLATE)}
    var feature by remember {mutableStateOf(initialConfig?.operation==Reply.FEATURE_TEMPLATE)}
    var scaleMin by remember {mutableStateOf(initialConfig?.minScalePermille?.toString() ?: "750")}
    var scaleMax by remember {mutableStateOf(initialConfig?.maxScalePermille?.toString() ?: "1250")}
    var scaleStep by remember {mutableStateOf(initialConfig?.stepPermille?.toString() ?: "125")}
    var direction by remember { mutableIntStateOf(initialConfig?.direction ?: 0) }
    var message by remember { mutableStateOf("坐标属于当前处理图，不是设备屏幕；测试后可明确选择加入，不自动点击。") }
    var requestId by remember { mutableLongStateOf(0) }
    var job by remember { mutableStateOf<Job?>(null) }
    var proof by remember { mutableStateOf<TestedImageRecognition<Bitmap>?>(null) }
    var pickTemplate by remember { mutableStateOf(false) }
    var diagnostics by remember {mutableStateOf(false)}
    var lastReply by remember {mutableStateOf<Reply?>(null)}
    val busy = requestId != 0L
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val resourceIdentity = snapshot.manifest.resources.toString() + ":" + snapshot.resourceRevision
    val latestResources by rememberUpdatedState(resourceIdentity)
    val region = selection ?: ImageToolCodeGen.Roi(0, 0, bitmap.width, bitmap.height)
    val config = runCatching {
        templateCropDimensions(bitmap.width, bitmap.height, region)
        RecognitionPreviewConfiguration(if(feature) Reply.FEATURE_TEMPLATE else if (gray) Reply.GRAY_TEMPLATE else if(scaled) Reply.SCALED_TEMPLATE else Reply.TEMPLATE, template,
            if (gray || feature) 0 else tolerance.toInt(), similarity.toInt(), bitmap.width, bitmap.height, region, if (gray || feature) 0 else direction,
            if(scaled) scaleMin.toInt() else 1000,if(scaled) scaleMax.toInt() else 1000,if(scaled) scaleStep.toInt() else 100)
    }.getOrNull()
    fun options(configuration: RecognitionPreviewConfiguration) = configuration.options()
    fun invalidate() { proof = null;lastReply=null; onResult(null) }
    fun cancel() {
        val id = requestId
        requestId = 0
        if (id != 0L) runtimeClient.cancelRecognitionPreview(id)
        job?.cancel(); job = null
        proof = null
    }
    DisposableEffect(runtimeClient) { onDispose { cancel() } }
    LaunchedEffect(bitmap, region, resourceIdentity) { cancel(); onResult(null) }
    StudioConfirmDialog(onDismissRequest = { cancel(); onDismiss() }, prompt = false, title = { Text("处理图 · 找图测试") }, text = {
        Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            RecognitionAvailabilityNotice(if(feature) "vision.findfeatures" else if (gray) "vision.findgray" else "vision.findimage", runtimeClient)
            Text("范围 X${region.left} Y${region.top} ${region.width}×${region.height}px", fontSize = 11.sp)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                ImageToolCompactAction(if (!gray && !scaled && !feature) "● 像素" else "像素", !busy) { feature=false;gray = false;scaled=false; invalidate(); message = "像素找图，不自动改变算法" }
                ImageToolCompactAction(if (gray) "● 灰度" else "灰度", !busy) { feature=false;gray = true;scaled=false; invalidate(); message = "灰度找图，不使用颜色容差" }
                ImageToolCompactAction(if (scaled) "● 多尺度" else "多尺度", !busy) { feature=false;gray=false;scaled=true;invalidate();message="有界多尺度像素匹配，不是灰度最高分；近1000‰优先" }
                ImageToolCompactAction(if (feature) "● 特征" else "特征", !busy) { feature=true;gray=false;scaled=false;invalidate();message="ORB/RANSAC，适合有纹理且不透明的图片；ROI≤1M像素，得分是内点比例" }
            }
            if (!gray && !feature) ImageToolChoice("方向", direction.toString(),
                recognitionSearchDirections.mapIndexed { index, label -> index.toString() to label }, !busy) {
                direction = it.toInt(); invalidate(); message = "方向已修改，请重新测试"
            }
            if (paths.isEmpty()) Text("请先保存或导入项目图片作为模板", fontSize = 11.sp)
            if(scaled) {
                StudioCompactGrid(listOf("最小 ‰", "最大 ‰", "步长 ‰"), cellDp = 104, maximum = 3) { label, cell ->
                    val value = when (label) { "最小 ‰" -> scaleMin; "最大 ‰" -> scaleMax; else -> scaleStep }
                    ImageToolCompactInput(label, value, !busy, cell, numeric = true) {
                        when (label) { "最小 ‰" -> scaleMin = it.take(4); "最大 ‰" -> scaleMax = it.take(4); else -> scaleStep = it.take(4) }
                        invalidate()
                    }
                }
                Text("250–4000‰，最多16档；单档模板≤256K像素、合计1亿比较。最近邻缩放，只返回首个达标位置和实际比例。",fontSize=10.sp)
            }
            TextButton(enabled = !busy && paths.isNotEmpty(), onClick = { pickTemplate = true }) {
                Text("模板：${template.removePrefix("assets/images/").ifEmpty { "请选择" }}", fontSize = 11.sp)
            }
            StudioCompactGrid(if (gray || feature) listOf("相似度 ‰") else listOf("相似度 ‰", "容差"), cellDp = 148, maximum = 2) { label, cell ->
                ImageToolCompactInput(label, if (label == "容差") tolerance else similarity, !busy, cell, numeric = true) {
                    if (it.length <= 16) { if (label == "容差") tolerance = it else similarity = it }
                    invalidate(); message = "参数已修改，请重新测试"
                }
            }
            Text(message, fontSize = 11.sp)
            if(!feature) ImageToolCompactAction("分析候选位置差异",!busy && config!=null) {diagnostics=true}
            if(onSaveRecipe!=null) ImageToolCompactAction("保存为方案",!busy && config!=null) {config?.let {onSaveRecipe(it,region)}}
            if (onConfirm != null) TextButton(enabled = !busy && config != null && proof != null, onClick = {
                val selected = config ?: return@TextButton
                val tested = proof
                val testedOptions = options(selected)
                val validity = { latestResources == resourceIdentity && !bitmap.isRecycled && tested?.matches(bitmap, selected.operation, testedOptions,
                    selected.template, runtimeClient::isRecognitionPreviewCurrent) == true }
                if (validity()) onConfirm(selected, region, validity)
                else { invalidate(); message = "测试设置或运行会话已变化，请重新测试" }
            }) { Text("加入处理 + 找图", fontSize = 11.sp) }
        }
    }, confirmButton = { TextButton(enabled = !busy && config != null, onClick = {
        val selected = config ?: return@TextButton
        val frozen = runCatching { bitmap.copy(Bitmap.Config.ARGB_8888, false) }.getOrNull()
        if (frozen == null) { message = "无法准备测试图片"; return@TextButton }
        val id = System.nanoTime().coerceAtLeast(1)
        requestId = id; invalidate(); message = "正在测试…"
        job = scope.launch {
            try {
                prepareRecognitionPreview(runtimeClient, selected.operation)
                val reply = withContext(Dispatchers.IO) {
                    var temporary: File? = null
                    try {
                        ensureActive()
                        val file = File.createTempFile("workbench-test-", ".png", context.cacheDir).also { temporary = it }
                        FileOutputStream(file).use { require(frozen.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                        ensureActive()
                        runtimeClient.testRecognitionPreview(id, selected.operation, file, imageRecognitionFile(snapshot, selected.template), options(selected))
                    } finally { temporary?.delete() }
                }
                if (requestId == id) {
                    if (runtimeClient.isRecognitionPreviewCurrent(reply)) {
                        message = recognitionPreviewMessage(reply);lastReply=reply; onResult(reply)
                        proof = TestedImageRecognition(bitmap, selected.operation, options(selected), selected.template, reply)
                            .takeIf { it.matches(bitmap, selected.operation, options(selected), selected.template, runtimeClient::isRecognitionPreviewCurrent) }
                    }
                    else message = "运行会话已变化，请重新测试"
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (unavailable: RecognitionUnavailable) { if (requestId == id) message = unavailable.message.orEmpty() }
            catch (_: Exception) { if (requestId == id) message = "测试失败，请检查图片和运行服务" }
            finally { frozen.recycle(); if (requestId == id) { requestId = 0; job = null } }
        }.also { it.invokeOnCompletion { if (!frozen.isRecycled) frozen.recycle() } }
    }) { Text(if (busy) "识别中…" else "开始测试") } }, dismissButton = { TextButton(onClick = {
        if (busy) { cancel(); message = "已取消测试" } else onDismiss()
    }) { Text(if (busy) "取消识别" else "返回") } })
    if (pickTemplate) StudioPanelDialog("选择模板", { pickTemplate = false }) {
        var query by remember { mutableStateOf("") }
        Column {
            RecognitionInput(query, { query = it }, Modifier.fillMaxWidth())
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 260.dp)) {
                items(paths.filter { it.contains(query, true) }, key = { it }) { path ->
                    TextButton(onClick = { template = path; pickTemplate = false; invalidate(); message = "模板已修改，请重新测试" }) {
                        Text(path.removePrefix("assets/images/"), fontSize = 11.sp)
                    }
                }
            }
        }
    }
    if(diagnostics) config?.let {selected ->
        val hit=lastReply?.takeIf {it.status==Reply.SUCCESS}
        val actualScale=if(selected.operation==Reply.SCALED_TEMPLATE) runCatching {com.google.gson.JsonParser.parseString(hit?.text.orEmpty()).asJsonArray[0].asJsonObject["scalePermille"].asInt}.getOrDefault(1000) else 1000
        ImageDifferenceDialog(bitmap,snapshot,selected,hit?.x ?: region.left,hit?.y ?: region.top,actualScale) {diagnostics=false}
    }
}

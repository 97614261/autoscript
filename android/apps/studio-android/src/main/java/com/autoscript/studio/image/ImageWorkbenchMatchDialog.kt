package com.autoscript.studio

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
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
    onSaveRecipe:((RecognitionPreviewConfiguration,ImageToolCodeGen.Roi)->Unit)?=null,
    initialDraft: ImageMatchDraft? = null, onRememberDraft: ((ImageMatchDraft) -> Unit)? = null) {
    val paths = snapshot.manifest.resources.filter { it.get("kind")?.asString == "image" }.mapNotNull { it.get("path")?.asString }
    var template by remember { mutableStateOf(initialDraft?.template ?: initialConfig?.template ?: paths.firstOrNull().orEmpty()) }
    var similarity by remember { mutableStateOf(initialDraft?.similarity ?: initialConfig?.similarityPermille?.toString() ?: "900") }
    var tolerance by remember { mutableStateOf(initialDraft?.tolerance ?: initialConfig?.tolerance?.toString() ?: "3") }
    var gray by remember { mutableStateOf(initialDraft?.gray ?: (initialConfig?.operation==Reply.GRAY_TEMPLATE)) }
    var scaled by remember {mutableStateOf(initialDraft?.scaled ?: (initialConfig?.operation==Reply.SCALED_TEMPLATE))}
    var feature by remember {mutableStateOf(initialDraft?.feature ?: (initialConfig?.operation==Reply.FEATURE_TEMPLATE))}
    var scaleMin by remember {mutableStateOf(initialDraft?.scaleMin ?: initialConfig?.minScalePermille?.toString() ?: "750")}
    var scaleMax by remember {mutableStateOf(initialDraft?.scaleMax ?: initialConfig?.maxScalePermille?.toString() ?: "1250")}
    var scaleStep by remember {mutableStateOf(initialDraft?.scaleStep ?: initialConfig?.stepPermille?.toString() ?: "125")}
    var direction by remember { mutableIntStateOf(initialDraft?.direction ?: initialConfig?.direction ?: 0) }
    SideEffect { onRememberDraft?.invoke(ImageMatchDraft(template,similarity,tolerance,gray,scaled,feature,scaleMin,scaleMax,scaleStep,direction)) }
    var message by remember { mutableStateOf("请选择模板后开始测试") }
    StudioSystemLogEffect("找图测试", message, skipInitial = true)
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
        require(template in paths) { "请选择仍在项目中的模板图片" }
        templateCropDimensions(bitmap.width, bitmap.height, region)
        RecognitionPreviewConfiguration(if(feature) Reply.FEATURE_TEMPLATE else if (gray) Reply.GRAY_TEMPLATE else if(scaled) Reply.SCALED_TEMPLATE else Reply.TEMPLATE, template,
            if (gray || feature) 0 else tolerance.toInt(), similarity.toInt(), bitmap.width, bitmap.height, region, if (gray || feature) 0 else direction,
            if(scaled) scaleMin.toInt() else 1000,if(scaled) scaleMax.toInt() else 1000,if(scaled) scaleStep.toInt() else 100)
    }.getOrNull()
    fun options(configuration: RecognitionPreviewConfiguration) = configuration.options()
    fun invalidate() { proof = null;lastReply=null; onResult(null); message = "配置已修改，请重新测试；上次结果已清除" }
    fun cancel() {
        val id = requestId
        requestId = 0
        if (id != 0L) runtimeClient.cancelRecognitionPreview(id)
        job?.cancel(); job = null
        proof = null
        lastReply = null
    }
    DisposableEffect(runtimeClient) { onDispose { cancel() } }
    LaunchedEffect(bitmap, region, resourceIdentity) { cancel(); onResult(null) }
    val insertAction: @Composable () -> Unit = {
        if (onConfirm != null) TextButton(enabled = !busy && config != null && proof != null, onClick = {
            val selected = config ?: return@TextButton
            val tested = proof
            val testedOptions = options(selected)
            val validity = { latestResources == resourceIdentity && !bitmap.isRecycled && tested?.matches(bitmap, selected.operation, testedOptions,
                selected.template, runtimeClient::isRecognitionPreviewCurrent) == true }
            if (validity()) onConfirm(selected, region, validity)
            else { invalidate(); message = "测试设置或运行会话已变化，请重新测试" }
        }) { Text("加入积木", fontSize = 11.sp) }
    }
    ImageWorkspaceConfirmPanel(onDismissRequest = { cancel(); onDismiss() }, prompt = false, title = { Text("找图测试") }, text = {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ImageWorkspaceCanvasSlot(Modifier.fillMaxWidth().height(96.dp)) { bounds ->
                ImageWorkspaceImageCanvas(bitmap,modifier=bounds,label="找图测试结果",overlay={v ->
                    val a=v.toView(region.left,region.top)
                    drawRect(androidx.compose.ui.graphics.Color(0xFF56CFFF),androidx.compose.ui.geometry.Offset(a.first,a.second),
                        androidx.compose.ui.geometry.Size(region.width*v.scale,region.height*v.scale),style=androidx.compose.ui.graphics.drawscope.Stroke(2f))
                    lastReply?.takeIf {it.status==Reply.SUCCESS}?.let {hit ->
                        val point=v.toView(hit.x,hit.y)
                        drawCircle(androidx.compose.ui.graphics.Color(0xFF36DC96),8.dp.toPx(),androidx.compose.ui.geometry.Offset(point.first,point.second),style=androidx.compose.ui.graphics.drawscope.Stroke(3f))
                    }
                })
            }
            ImageWorkspaceSection("模板与方式") {
            ImageToolCompactAction("模板：${template.removePrefix("assets/images/").ifEmpty { "请选择" }}", !busy && paths.isNotEmpty(), Modifier.fillMaxWidth()) { pickTemplate = true }
            if (paths.isEmpty()) ImageWorkspaceHint("先保存或导入一张项目图片作为模板。")
            ImageToolChoice("算法", when {feature->"feature";gray->"gray";scaled->"scaled";else->"pixel"},
                listOf("pixel" to "像素", "gray" to "灰度", "scaled" to "多尺度", "feature" to "特征"), !busy) {
                feature=it=="feature"; gray=it=="gray"; scaled=it=="scaled"; invalidate()
                message=when(it) {
                    "gray" -> "灰度找图，不使用颜色容差"
                    "scaled" -> "有界多尺度像素匹配，接近1000‰优先"
                    "feature" -> "ORB/RANSAC，适合有纹理且不透明的图片；得分为内点比例"
                    else -> "像素找图，不自动改变算法"
                }
            }
            ImageWorkspaceHint("范围 X${region.left} Y${region.top} · ${region.width}×${region.height}px")
            }
            ImageWorkspaceSection("匹配参数") {
            if (!gray && !feature) ImageToolChoice("方向", direction.toString(),
                recognitionSearchDirections.mapIndexed { index, label -> index.toString() to label }, !busy) {
                direction = it.toInt(); invalidate(); message = "方向已修改，请重新测试"
            }
            if(scaled) {
                StudioCompactGrid(listOf("最小 ‰", "最大 ‰", "步长 ‰"), cellDp = 104, maximum = 3) { label, cell ->
                    val value = when (label) { "最小 ‰" -> scaleMin; "最大 ‰" -> scaleMax; else -> scaleStep }
                    ImageToolCompactInput(label, value, !busy, cell, numeric = true) {
                        when (label) { "最小 ‰" -> scaleMin = it.take(4); "最大 ‰" -> scaleMax = it.take(4); else -> scaleStep = it.take(4) }
                        invalidate()
                    }
                }
            }
            StudioCompactGrid(if (gray || feature) listOf("相似度 ‰") else listOf("相似度 ‰", "容差"), cellDp = 148, maximum = 2) { label, cell ->
                ImageToolCompactInput(label, if (label == "容差") tolerance else similarity, !busy, cell, numeric = true) {
                    if (it.length <= 16) { if (label == "容差") tolerance = it else similarity = it }
                    invalidate(); message = "参数已修改，请重新测试"
                }
            }
            }
            if(config==null) ImageWorkspaceHint("检查模板、范围和参数：相似度0–1000，容差0–255；多尺度250–4000‰且含端点最多16档。",error=true)
            ImageWorkspaceSection("测试结果") { Text(message, fontSize = 11.sp) }
            ImageToolHelp("坐标属于当前处理图，不是设备屏幕；测试后可选择加入，不自动点击。" +
                (if(scaled) "\n250–4000‰，最多16档；单档模板≤256K像素、合计1亿比较。最近邻缩放，返回首个达标位置和实际比例。" else "") +
                (if(onRememberDraft!=null) "\n返回保留本次工作区的模板和参数；再次进入需重新测试。关闭整个图像工作区后清除。" else "")) {
                RecognitionAvailabilityNotice(if(feature) "vision.findfeatures" else if (gray) "vision.findgray" else "vision.findimage", runtimeClient)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if(!feature) ImageToolCompactAction("差异分析",!busy && config!=null) {diagnostics=true}
                if(onSaveRecipe!=null) ImageToolCompactAction("保存为方案",!busy && config!=null) {config?.let {onSaveRecipe(it,region)}}
            }
        }
    }, confirmButton = {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
    ImageToolCompactAction(if (busy) "识别中…" else "开始测试", !busy && config != null, primary = true) test@{
        val selected = config ?: return@test
        val frozen = runCatching { bitmap.copy(Bitmap.Config.ARGB_8888, false) }.getOrNull()
        if (frozen == null) { message = "无法准备测试图片"; return@test }
        val id = System.nanoTime().coerceAtLeast(1)
        requestId = id; invalidate(); message = "正在测试…"
        job = scope.launch {
            try {
                prepareRecognitionPreview(runtimeClient, selected.operation)
                val reply = withContext(Dispatchers.IO) {
                    var temporary: File? = null
                    try {
                        ensureActive()
                        val templateFile = imageRecognitionFile(snapshot, selected.template)
                        val size = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        android.graphics.BitmapFactory.decodeFile(templateFile.path, size)
                        fixedTemplateSizeProblem(selected.operation,size.outWidth,size.outHeight,region)?.let { throw ImageMatchInputException(it) }
                        val file = File.createTempFile("workbench-test-", ".png", context.cacheDir).also { temporary = it }
                        FileOutputStream(file).use { require(frozen.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                        ensureActive()
                        runtimeClient.testRecognitionPreview(id, selected.operation, file, templateFile, options(selected))
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
            catch (invalid: ImageMatchInputException) { if (requestId == id) message = invalid.message.orEmpty() }
            catch (unavailable: RecognitionUnavailable) { if (requestId == id) message = unavailable.message.orEmpty() }
            catch (_: Exception) { if (requestId == id) message = "测试失败，请检查图片和运行服务" }
            finally { frozen.recycle(); if (requestId == id) { requestId = 0; job = null } }
        }.also { it.invokeOnCompletion { if (!frozen.isRecycled) frozen.recycle() } }
    }
    insertAction()
    }
    }, dismissButton = { TextButton(onClick = {
        if (busy) { cancel(); message = "已取消测试" } else onDismiss()
    }) { Text(if (busy) "取消识别" else "返回") } })
    if (pickTemplate) ProjectImagePickerDialog(snapshot, paths, template, { pickTemplate = false }, { path ->
        template = path; pickTemplate = false; invalidate(); message = "模板已修改，请重新测试"
    })
    if(diagnostics) config?.let {selected ->
        CompositionLocalProvider(LocalImageWorkspace provides null) {
        val hit=lastReply?.takeIf {it.status==Reply.SUCCESS}
        val actualScale=if(selected.operation==Reply.SCALED_TEMPLATE) runCatching {com.google.gson.JsonParser.parseString(hit?.text.orEmpty()).asJsonArray[0].asJsonObject["scalePermille"].asInt}.getOrDefault(1000) else 1000
        ImageDifferenceDialog(bitmap,snapshot,selected,hit?.x ?: region.left,hit?.y ?: region.top,actualScale) {diagnostics=false}
        }
    }
}

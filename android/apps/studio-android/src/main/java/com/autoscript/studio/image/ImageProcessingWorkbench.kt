package com.autoscript.studio

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import com.autoscript.project.store.ProjectSnapshot
import com.autoscript.project.store.ProjectStore
import com.autoscript.runtime.client.RuntimeClient
import kotlinx.coroutines.*

/** One editor workspace for the library and screenshot tools; never owns the borrowed source. */
@Composable
internal fun ImageProcessingWorkbench(bitmap: Bitmap, snapshot: ProjectSnapshot, store: ProjectStore,
    runtimeClient: RuntimeClient, onSnapshotChanged: (ProjectSnapshot) -> Unit, onDismiss: () -> Unit,
    originalImagePath: String? = null, insertionTarget: ImageWorkbenchInsertionTarget? = null) {
    val history = remember(bitmap, snapshot.manifest.projectId) {
        runCatching { ImageProcessingHistory(ImageProcessingVersion(bitmap, ImageProcessingPipeline(bitmap.width, bitmap.height)),
            { it.image.allocationByteCount.toLong() }, { it.image.recycle() }) }.getOrNull()
    }
    var revision by remember(history) { mutableLongStateOf(0) }
    var originalVisible by remember(history) { mutableStateOf(false) }
    var processing by remember(history) { mutableStateOf(false) }
    var saving by remember(history) { mutableStateOf(false) }
    var task by remember(history) { mutableStateOf<Job?>(null) }
    var message by remember(history) { mutableStateOf("处理只生成预览；保存当前显示的图片，不覆盖原图。") }
    var saveDialog by remember(history) { mutableStateOf(false) }
    var resizeDialog by remember(history) { mutableStateOf(false) }
    var thresholdDialog by remember(history) { mutableStateOf(false) }
    var ocrDialog by remember(history) { mutableStateOf(false) }
    var matchDialog by remember(history) { mutableStateOf(false) }
    var closeConfirm by remember(history) { mutableStateOf(false) }
    var lastSavedImage by remember(history) { mutableStateOf(bitmap) }
    var insertionConfiguration by remember(history) { mutableStateOf(false) }
    var recognitionTail by remember(history) { mutableStateOf<ImagePipelineRecognitionTail?>(null) }
    var recognitionValidity by remember(history) { mutableStateOf<(() -> Boolean)?>(null) }
    var insertion by remember(history) { mutableStateOf<Pair<ImageBlockInsertionRequest, ImageWorkbenchInsertionTarget>?>(null) }
    var inserting by remember(history) { mutableStateOf(false) }
    var zoomMode by remember(history) { mutableStateOf(false) }
    var showRulers by remember(history) { mutableStateOf(false) }
    var selection by remember(history) { mutableStateOf(ImageToolSelection()) }
    var viewport by remember(history) { mutableStateOf<ImageToolViewport?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val latestHistory by rememberUpdatedState(history)
    val latestSnapshotChanged by rememberUpdatedState(onSnapshotChanged)
    // revision makes otherwise non-Compose history changes observable.
    val version = remember(history, revision, originalVisible) { if (originalVisible) history?.original else history?.current }
    val displayed = version?.image ?: bitmap
    val busy = processing || saving || inserting
    DisposableEffect(history) { onDispose { task?.cancel(); history?.close() } }
    LaunchedEffect(displayed) { selection = ImageToolSelection(); recognitionTail = null; recognitionValidity = null }

    fun requestDismiss() {
        if (busy || history?.current?.image?.let { it !== bitmap && it !== lastSavedImage } == true) closeConfirm = true
        else onDismiss()
    }

    fun apply(operation: ImagePixelOperation) {
        val owner = history ?: return
        if (busy || originalVisible) return
        val source = owner.current.image
        val ticket = owner.revision
        val pipeline = try { owner.current.pipeline.append(operation) }
            catch (_: Exception) { message = "处理最多64步，请保存图片后重新打开作为新来源"; return }
        val frozen = try {
            val size = operation.dimensions(source.width, source.height)
            owner.validateResultBytes(size.first.toLong() * size.second * 4)
            requireNotNull(source.copy(Bitmap.Config.ARGB_8888, false))
        } catch (_: Throwable) { message = "尺寸或预览预算无效，原图和历史未改变"; return }
        processing = true
        task = scope.launch {
            var result: Bitmap? = null
            try {
                result = withContext(Dispatchers.Default) {
                    val work = currentCoroutineContext()
                    val deadline = System.nanoTime() + 5_000_000_000L
                    val checkpoint = { work.ensureActive(); check(System.nanoTime() < deadline) { "处理超过5秒" } }
                    val pixels = IntArray(frozen.width * frozen.height)
                    frozen.getPixels(pixels, 0, frozen.width, 0, 0, frozen.width, frozen.height)
                    val output = processImagePixels(pixels, frozen.width, frozen.height, operation, checkpoint)
                    checkpoint()
                    Bitmap.createBitmap(output.argb, output.width, output.height, Bitmap.Config.ARGB_8888)
                        .also { result = it }
                }
                val published = requireNotNull(result)
                result = null // publish consumes ownership, including stale failure.
                if (owner.publish(ImageProcessingVersion(published, pipeline), ticket)) {
                    selection = ImageToolSelection()
                    revision = owner.revision
                    message = "处理完成 · ${published.width}×${published.height}px · 保留${owner.editCount}步"
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Throwable) { if (owner === latestHistory) message = "处理失败或超过预算，当前图片保留" }
            finally { result?.recycle(); frozen.recycle(); if (owner === latestHistory) { processing = false; task = null } }
        }.also { it.invokeOnCompletion { if (!frozen.isRecycled) frozen.recycle() } }
    }

    StudioDialog(onDismissRequest = ::requestDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)) {
        Column(Modifier.fillMaxSize().background(ToolChrome)) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(6.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("图片工作台", color = androidx.compose.ui.graphics.Color.White, fontSize = 12.sp)
                ImageToolButton(if (originalVisible) "原图对照" else "处理图", ButtonBlue, !busy) { selection = ImageToolSelection(); originalVisible = !originalVisible }
                ImageToolButton("撤销", ButtonGrey, !busy && history?.canUndo == true) { selection = ImageToolSelection(); history?.undo(); revision = history?.revision ?: 0; originalVisible = false }
                ImageToolButton("重做", ButtonGrey, !busy && history?.canRedo == true) { selection = ImageToolSelection(); history?.redo(); revision = history?.revision ?: 0; originalVisible = false }
                ImageToolButton("适应", ButtonGrey, !busy) { viewport = viewport?.reset() }
                ImageToolButton("100%", ButtonGrey, !busy) { viewport = viewport?.actualSize() }
                ImageToolButton(if (zoomMode) "框选" else "缩放/拖动", ButtonGrey, !busy) { zoomMode = !zoomMode }
                ImageToolButton(if (showRulers) "隐藏标尺" else "标尺/网格", ButtonGrey, true) { showRulers = !showRulers }
                ImageToolButton("返回", ButtonGrey, true, ::requestDismiss)
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp, vertical = 3.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                listOf("gray" to "灰度", "rotate90" to "右转90°", "rotate270" to "左转90°", "rotate180" to "转180°", "flipX" to "水平镜像", "flipY" to "垂直镜像").forEach { (mode, title) ->
                    ImageToolButton(title, ButtonGrey, !busy && !originalVisible && history != null) { apply(ImagePixelOperation(mode)) }
                }
                ImageToolButton("二值化", ButtonGrey, !busy && !originalVisible && history != null) { thresholdDialog = true }
                ImageToolButton("裁剪选框", ButtonGrey, !busy && !originalVisible && history != null && selection.box != null) { apply(ImagePixelOperation("crop", roi = selection.box)) }
                ImageToolButton("尺寸缩放", ButtonGrey, !busy && !originalVisible && history != null) { resizeDialog = true }
                ImageToolButton("清除框选", ButtonGrey, !busy && selection.box != null) { selection = ImageToolSelection() }
            }
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().background(androidx.compose.ui.graphics.Color.Black)) {
                val width = constraints.maxWidth.toFloat(); val height = constraints.maxHeight.toFloat()
                LaunchedEffect(displayed.width, displayed.height, width, height) {
                    if (width > 0 && height > 0) {
                        val previous = viewport
                        viewport = if (previous?.imageWidth == displayed.width && previous.imageHeight == displayed.height)
                            previous.resized(width, height) else ImageToolViewport.fit(displayed.width, displayed.height, width, height)
                    }
                }
                ImageToolCanvas(displayed, viewport, ImageToolMode.CROP, zoomMode || busy, false, selection, Offset.Zero, null, null,
                    onViewport = { viewport = it }, onSelection = { if (!busy) selection = it }, onCrosshair = {}, onSlide = { _, _ -> })
                if (showRulers) viewport?.let { ImageWorkbenchRulers(it) }
            }
            Text("${if (originalVisible) "原图" else "处理图"} ${displayed.width}×${displayed.height}px · ${(viewport?.scale?.times(100) ?: 100f).toInt()}%" +
                (selection.box?.let { " · X${it.left} Y${it.top} ${it.width}×${it.height}px" } ?: ""),
                color = androidx.compose.ui.graphics.Color.White, fontSize = 10.sp, modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
            Text(if (history == null) "原图超过预览内存预算，请先在截图工具裁剪后保存" else message,
                color = androidx.compose.ui.graphics.Color.LightGray, fontSize = 10.sp, modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ImageToolButton(if (saving) "保存中…" else "保存当前图", ButtonGreen, !busy && history != null) { saveDialog = true }
                ImageToolButton("找图测试", ButtonBlue, !busy && history != null) { selection = selection.copy(points = emptyList()); matchDialog = true }
                ImageToolButton("取字测试", ButtonBlue, !busy && history != null) { ocrDialog = true }
                if (insertionTarget != null) ImageToolButton("加入处理积木", ButtonGreen, !busy && version != null) { recognitionTail = null; recognitionValidity = null; insertionConfiguration = true }
                if (processing) ImageToolButton("取消处理", ButtonGrey, true) { task?.cancel() }
            }
        }
    }
    if (resizeDialog) ImageWorkbenchResizeDialog(displayed.width, displayed.height,
        onDismiss = { resizeDialog = false }, onApply = { w, h -> resizeDialog = false; apply(ImagePixelOperation("resize", w, h)) })
    if (thresholdDialog) ImageWorkbenchThresholdDialog(onDismiss = { thresholdDialog = false }, onApply = {
        thresholdDialog = false; apply(ImagePixelOperation("threshold", threshold = it))
    })
    if (matchDialog) ImageWorkbenchMatchDialog(displayed, selection.box, snapshot, runtimeClient,
        onResult = { result ->
            selection = selection.copy(points = if (result != null && result.status == com.autoscript.runtime.api.RecognitionPreviewReply.SUCCESS &&
                result.x in 0 until displayed.width && result.y in 0 until displayed.height)
                listOf(ImageToolCodeGen.PickedPoint(result.x, result.y, displayed.getPixel(result.x, result.y) and 0xFFFFFF)) else emptyList())
        }, onDismiss = { matchDialog = false }, onConfirm = if (insertionTarget == null) null else { settings, roi, validity ->
            matchDialog = false; recognitionTail = ImagePipelineRecognitionTail.Match(settings, roi); recognitionValidity = validity; insertionConfiguration = true
        })
    if (ocrDialog) ImageToolOcrDialog(displayed, selection.box ?: ImageToolCodeGen.Roi(0, 0, displayed.width, displayed.height), snapshot, runtimeClient,
        onDismiss = { ocrDialog = false }, onConfirm = { settings, roi ->
            ocrDialog = false; recognitionTail = ImagePipelineRecognitionTail.Ocr(settings, roi); insertionConfiguration = true
        }, allowBackfill = insertionTarget != null, confirmLabel = "加入处理 + 取字", onProofConfirmed = { recognitionValidity = it },resultReadTarget=insertionTarget)
    if (insertionConfiguration && insertionTarget != null && version != null) ImagePipelineInsertDialog(version.pipeline, originalImagePath,
        snapshot, insertionTarget, onDismiss = { insertionConfiguration = false }, onReady = {
            insertion = it to insertionTarget
            insertionConfiguration = false
        }, recognitionTail = recognitionTail, recognitionIsCurrent = recognitionValidity ?: { false })
    insertion?.let { (request, target) ->
        if (!inserting) ImageInsertionPositionDialog(request, target.editor,
            onDismiss = { insertion = null; insertionConfiguration = true }, onConfirm = { position, slot ->
                inserting = true
                task = scope.launch {
                    try {
                        val problem = target.apply(request, position, slot)
                        if (problem == null) { insertion = null; message = "处理/识别积木已整批加入，可一次撤销；帧释放按所选配置执行" }
                        else { insertion = null; insertionConfiguration = true; message = problem }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { message = "校验或加入失败，原草稿与处理预览保留" }
                    finally { inserting = false; task = null }
                }
            })
    }
    if (saveDialog) ImageTemplateSaveDialog(store = store, projectId = snapshot.manifest.projectId,
        pixelSize = displayed.width to displayed.height,
        imagePaths = snapshot.manifest.resources.mapNotNull { it.get("path")?.asString }, defaultName = "processed-${System.currentTimeMillis()}.png",
        onDismiss = { saveDialog = false }, onSave = { folder, name ->
            saveDialog = false
            val owner = history
            val savedImage = displayed
            val frozen = runCatching { displayed.copy(Bitmap.Config.ARGB_8888, false) }.getOrNull()
            if (frozen == null) message = "无法准备保存图片；原图保留"
            else {
                saving = true
                task = scope.launch {
                    try {
                        val (updated, path) = withContext(Dispatchers.IO) {
                            ensureActive()
                            cropAndImportTemplate(store, snapshot, frozen, ImageToolCodeGen.Roi(0, 0, frozen.width, frozen.height), context, folder, name)
                        }
                        ensureActive()
                        if (owner === latestHistory) { latestSnapshotChanged(updated); lastSavedImage = savedImage; message = "已保存：${path.removePrefix("assets/images/")} · 原资源未覆盖" }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { if (owner === latestHistory) message = "保存失败；当前预览保留" }
                    finally { frozen.recycle(); if (owner === latestHistory) { saving = false; task = null } }
                }.also { it.invokeOnCompletion { if (!frozen.isRecycled) frozen.recycle() } }
            }
        })
    if (closeConfirm) StudioConfirmDialog(onDismissRequest = { closeConfirm = false }, title = { Text("关闭图片工作台？") },
        text = { Text("未保存的处理预览将丢弃。进行中的任务会取消；已提交的资源保存可能已完成，但不会覆盖原图。", fontSize = 12.sp) },
        confirmButton = { TextButton(onClick = { task?.cancel(); closeConfirm = false; onDismiss() }) { Text("关闭") } },
        dismissButton = { TextButton(onClick = { closeConfirm = false }) { Text("继续编辑") } })
}

@Composable
private fun ImageWorkbenchResizeDialog(width: Int, height: Int, onDismiss: () -> Unit, onApply: (Int, Int) -> Unit) {
    var w by remember { mutableStateOf(width.toString()) }; var h by remember { mutableStateOf(height.toString()) }
    val valid = runCatching { ImagePixelOperation("resize", w.toInt(), h.toInt()).dimensions(width, height) }.getOrNull()
    StudioConfirmDialog(onDismissRequest = onDismiss, title = { Text("明确输出尺寸") }, text = {
        Column {
            Text("像素单位 · 最近邻缩放，不平滑、不自动压缩", fontSize = 11.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(w, { w = it.take(5) }, singleLine = true, label = { Text("宽 px") }, modifier = Modifier.weight(1f))
                OutlinedTextField(h, { h = it.take(5) }, singleLine = true, label = { Text("高 px") }, modifier = Modifier.weight(1f))
            }
            if (valid == null) Text("每边1–4096，总计不超过400万像素", color = androidx.compose.ui.graphics.Color.Red, fontSize = 10.sp)
        }
    }, confirmButton = { TextButton(enabled = valid != null, onClick = { valid?.let { onApply(it.first, it.second) } }) { Text("应用") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

@Composable
private fun ImageWorkbenchThresholdDialog(onDismiss: () -> Unit, onApply: (Int) -> Unit) {
    var text by remember { mutableStateOf("128") }
    val threshold = text.toIntOrNull()?.takeIf { it in 0..255 }
    StudioConfirmDialog(onDismissRequest = onDismiss, title = { Text("灰度二值化") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("先转灰度：大于或等于阈值为白，否则为黑；保留透明度和原图。", fontSize = 11.sp)
            OutlinedTextField(text, { text = it.take(3) }, singleLine = true, label = { Text("阈值 0–255") }, modifier = Modifier.fillMaxWidth())
            if (threshold == null) Text("请输入0–255之间的整数，不会自动截断", fontSize = 11.sp, color = androidx.compose.ui.graphics.Color.Red)
        }
    }, confirmButton = { TextButton(enabled = threshold != null, onClick = { threshold?.let(onApply) }) { Text("应用") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

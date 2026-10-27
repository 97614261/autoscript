package com.autoscript.studio

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import com.autoscript.core.designsystem.AutoScriptPalette
import com.autoscript.project.store.ProjectResourceKind
import com.autoscript.project.store.ProjectSnapshot
import com.autoscript.project.store.ProjectStore
import com.autoscript.project.store.ProjectGlyphColor
import com.autoscript.project.store.importedResourcePath
import com.autoscript.runtime.api.RecognitionPreviewReply as Reply
import com.autoscript.runtime.client.RuntimeClient
import java.io.File
import kotlinx.coroutines.*
import kotlin.math.min
import kotlin.math.max
import kotlin.math.roundToInt

/** Project-owned fixed glyph authoring; no model, guessed labels, script execution or runtime API. */
@Composable
internal fun GlyphMakerDialog(snapshot: ProjectSnapshot, store: ProjectStore, runtimeClient: RuntimeClient,
    onSnapshotChanged: (ProjectSnapshot) -> Unit, onDismiss: () -> Unit,
    initialBitmap: Bitmap? = null, initialRoi: ImageToolCodeGen.Roi? = null,
    initialForeground: Int = 0xFFFFFF, initialTolerance: Int = 16,
    onUseSaved: ((ProjectSnapshot, ImageToolOcrConfig, ImageToolCodeGen.Roi?) -> Unit)? = null) {
    key(snapshot.manifest.projectId) {
        GlyphMakerContent(snapshot, store, runtimeClient, onSnapshotChanged, onDismiss, initialBitmap, initialRoi,
            initialForeground, initialTolerance, onUseSaved)
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
private fun GlyphMakerContent(snapshot: ProjectSnapshot, store: ProjectStore, client: RuntimeClient,
    onSnapshotChanged: (ProjectSnapshot) -> Unit, onDismiss: () -> Unit, initialBitmap: Bitmap?, initialRoi: ImageToolCodeGen.Roi?,
    initialForeground: Int, initialTolerance: Int,
    onUseSaved: ((ProjectSnapshot, ImageToolOcrConfig, ImageToolCodeGen.Roi?) -> Unit)?) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val savePrompt = rememberResourceSavePrompt()
    val pendingIntoView = remember { BringIntoViewRequester() }
    val latestSnapshot by rememberUpdatedState(snapshot)
    var bitmap by remember { mutableStateOf(initialBitmap) }
    var ownsBitmap by remember { mutableStateOf(false) }
    var samples by remember { mutableStateOf(emptyList<MakerGlyph>()) }
    val history = remember { GlyphMakerHistory() }
    var revision by remember { mutableLongStateOf(0) }
    var savedRevision by remember { mutableLongStateOf(0) }
    var pendingBatch by remember { mutableStateOf<GlyphMakerPendingBatch?>(null) }
    var pendingBatchId by remember { mutableLongStateOf(0) }
    val pendingEditing = remember(pendingBatchId) { GlyphMakerPendingEditing(pendingBatch ?: GlyphMakerPendingBatch()) }
    val pending = pendingBatch?.samples.orEmpty()
    var characterSet by remember { mutableStateOf(GlyphMakerCharacterSet.DIGITS) }
    var customCharacters by remember { mutableStateOf("0123456789()+-./%") }
    var nextSampleLabel by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableIntStateOf(0) }
    var label by remember { mutableStateOf("") }
    var page by remember { mutableIntStateOf(0) }
    val bodyScroll = remember(page) { ScrollState(0) }
    val sampleGrid = rememberLazyGridState()
    var replacePending by remember { mutableStateOf<Boolean?>(null) }
    var fullScreen by remember { mutableStateOf(false) }
    var selection by remember { mutableStateOf(ImageToolSelection(box = initialRoi)) }
    var viewport by remember { mutableStateOf<ImageToolViewport?>(null) }
    var mode by remember { mutableStateOf(ImageToolMode.CROP) }
    var zoom by remember { mutableStateOf(false) }
    var blackWhite by remember { mutableStateOf(false) }
    var palette by remember { mutableStateOf(GlyphPaletteDraft.from(initialForeground and 0xFFFFFF, initialTolerance)) }
    val initialColors = remember { palette.colors }
    var threshold by remember { mutableStateOf("900") }
    var gap by remember { mutableStateOf("0") }
    var name by remember { mutableStateOf("digits") }
    var chooser by remember { mutableStateOf<String?>(null) }
    var discard by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var job by remember { mutableStateOf<Job?>(null) }
    var requestId by remember { mutableLongStateOf(0) }
    var operationTicket by remember { mutableLongStateOf(0) }
    var message by remember { mutableStateOf("框选 → 取色 → 切分标注；可连续补样，或追加后直接测试。") }
    var help by remember { mutableStateOf(false) }
    var lastSaved by remember { mutableStateOf<Triple<Long, String, String>?>(null) }
    var lastSavedResourceRevision by remember { mutableStateOf<String?>(null) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var testedInputs by remember { mutableStateOf<List<Any?>?>(null) }
    var recognizedText by remember { mutableStateOf<String?>(null) }
    var testDetails by remember { mutableStateOf<OcrPreviewDetails?>(null) }
    var expectedText by remember { mutableStateOf("") }
    val labelDirty = samples.getOrNull(selected)?.label?.let { it != label } == true
    val dirty = revision != savedRevision || pending.isNotEmpty() || labelDirty || palette.colors != initialColors
    val colors = palette.parsed()
    val rgb = colors?.first()?.rgb
    val tol = colors?.first()?.tolerance
    val additionalColors = colors?.let { ProjectGlyphColor.encodeAdditional(it.drop(1)) }
    val image = bitmap
    val roi = selection.box ?: image?.let { ImageToolCodeGen.Roi(0, 0, it.width, it.height) }
    val foregroundPreview = rememberGlyphForegroundPreview(image, roi, colors, blackWhite && page == 0)
    val targetCharacters = remember(characterSet, customCharacters) {
        runCatching { glyphMakerTargetCharacters(if (characterSet == GlyphMakerCharacterSet.CUSTOM) customCharacters else characterSet.characters) }.getOrNull()
    }
    val targetMissing = remember(samples, targetCharacters) { targetCharacters?.let { glyphMakerMissing(samples, it) } }
    val visibleIndices = remember(samples, query) { glyphMakerSampleIndices(samples, query) }
    LaunchedEffect(page, selected, visibleIndices) {
        if (page == 1) visibleIndices.indexOf(selected).takeIf { it >= 0 }?.let { sampleGrid.animateScrollToItem(it) }
    }
    // Compare inputs at display time, not in a deferred effect that could erase a fast fresh reply.
    fun previewInputs(): List<Any?> = listOf(revision, bitmap, selection.box, palette.colors, threshold, gap, pendingBatch?.samples.orEmpty(), label)

    DisposableEffect(bitmap, ownsBitmap) {
        val owned = bitmap.takeIf { ownsBitmap }
        onDispose { owned?.recycle() }
    }
    fun cancel() {
        operationTicket++
        val id = requestId; requestId = 0L
        if (id != 0L) client.cancelRecognitionPreview(id)
        job?.cancel(); job = null; busy = false
    }
    DisposableEffect(client) { onDispose { cancel() } }
    fun close() {
        if (saving) { message = "正在提交项目资源，请等待完成"; return }
        cancel()
        if (dirty) discard = true else onDismiss()
    }
    fun showVersion(version: GlyphMakerVersion) {
        samples = version.samples; revision = version.id
        selected = version.selected.coerceIn(0, (samples.size - 1).coerceAtLeast(0))
        label = samples.getOrNull(selected)?.label.orEmpty()
    }
    fun change(next: List<MakerGlyph>) = showVersion(history.push(next, selected))
    fun setImage(next: Bitmap) {
        bitmap = next; ownsBitmap = true; viewport = null; selection = ImageToolSelection()
        pendingBatch = null; pendingBatchId++
        message = "已读取原图 ${next.width}×${next.height}px；框选文字，再取色和切分。"
        page = 0
    }
    fun loadImage(load: suspend () -> Bitmap) {
        if (busy) return
        val ticket = ++operationTicket
        busy = true
        job = scope.launch {
            var decoded: Bitmap? = null
            try {
                withTimeout(10_000) { withContext(Dispatchers.IO) { decoded = load() } }
                ensureActive()
                if (ticket == operationTicket) { setImage(requireNotNull(decoded)); decoded = null }
            } catch (_: TimeoutCancellationException) { if (ticket == operationTicket) message = "读取图片超时，原图和样本保留" }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: OutOfMemoryError) { if (ticket == operationTicket) message = "图片预览内存不足，请缩小取材范围后重试" }
            catch (_: Exception) { if (ticket == operationTicket) message = "图片不可读或超过24MiB、4096边长/400万像素预算；未缩放原图" }
            finally { decoded?.recycle(); if (ticket == operationTicket) { busy = false; job = null } }
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && !busy && pending.isEmpty()) {
            loadImage {
            val file = File.createTempFile("glyph-source-", ".image", context.cacheDir)
            try {
                requireNotNull(context.contentResolver.openInputStream(uri)).use { input ->
                    file.outputStream().use { output ->
                        val buffer = ByteArray(16 * 1024); var total = 0
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = input.read(buffer); if (count < 0) break
                            total += count; require(total <= 24 * 1024 * 1024)
                            output.write(buffer, 0, count)
                        }
                    }
                }
                decodeGlyphMakerImage(file)
            } finally { file.delete() }
            }
        } else if (uri != null && pending.isNotEmpty()) message = "已有待标注样本，先追加或放弃本批后再换图"
    }

    fun extract(batch: Boolean, confirmed: Boolean = false) {
        val source = image ?: return
        val region = roi ?: return
        val sampledColors = colors ?: return
        if (busy) return
        if (!confirmed && pending.isNotEmpty()) { replacePending = batch; return }
        if (source.width.toLong() * source.height > GlyphDictionaryMaker.MAX_IMAGE_PIXELS) { message = "原图超过400万像素，请先裁剪保存后制作"; return }
        val ticket = ++operationTicket
        val labelHint = nextSampleLabel
        val frozen = runCatching { source.copy(Bitmap.Config.ARGB_8888, false) }.getOrNull()
            ?: run { message = "无法准备独立图片副本，请缩小取材范围"; return }
        busy = true
        job = scope.launch {
            try {
                val result = withContext(Dispatchers.Default) {
                    val work = currentCoroutineContext(); val deadline = System.nanoTime() + 5_000_000_000L
                    val pixels = IntArray(frozen.width * frozen.height)
                    frozen.getPixels(pixels, 0, frozen.width, 0, 0, frozen.width, frozen.height)
                    GlyphDictionaryMaker.sample(pixels, frozen.width, frozen.height, region.left, region.top, region.right, region.bottom,
                        sampledColors, batch) { work.ensureActive(); require(System.nanoTime() < deadline) { "切分超过5秒，请缩小选区" } }
                }
                if (ticket == operationTicket) {
                    pendingBatch = GlyphMakerPendingBatch(result).let { draft ->
                        if (result.size == 1 && glyphMakerLabelsReady(1, labelHint)) draft.label(0, labelHint) else draft
                    }
                    pendingBatchId++
                    if (result.size == 1 && labelHint.isNotEmpty()) {
                        nextSampleLabel = ""
                        message = "已按你的选择预填「$labelHint」，核对字形后追加；可点字形修改。"
                    } else message = "切分出${result.size}个样本，可逐字标注；误切字形可单独移除。"
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: OutOfMemoryError) { if (ticket == operationTicket) message = "取样内存不足，请缩小选区或原图" }
            catch (invalid: IllegalArgumentException) { if (ticket == operationTicket) message = invalid.message ?: "切分参数无效" }
            catch (_: Exception) { if (ticket == operationTicket) message = "取样失败，原样本保留" }
            finally { frozen.recycle(); if (ticket == operationTicket) { busy = false; job = null } }
        }.also { it.invokeOnCompletion { if (!frozen.isRecycled) frozen.recycle() } }
    }

    fun save(useAfter: Boolean = false) {
        if (busy) return
        if (colors == null) { message = "前景色组尚未完成，请补全或删除无效颜色"; page = 0; return }
        if (labelDirty) { message = "样本字符修改尚未应用，请先应用或恢复标注"; page = 1; return }
        if (pending.isNotEmpty()) { message = "请先标注并追加待加入的样本，或明确放弃它们"; page = 0; return }
        val frozen = samples
        val ticket = ++operationTicket
        val fileName = name.trim().removeSuffix(".asglyph")
        if (fileName.isEmpty() || fileName.length > 64 || fileName.any { it in "\\/:*?\"<>|" || it.isISOControl() } ||
            fileName.endsWith('.') || fileName.contains("..")) { message = "名称须为1–64字，不能含路径、连续句点或文件名非法字符"; return }
        val settings = if (useAfter) runCatching {
            ImageToolOcrConfig(Reply.GLYPH, "dictionaries/check.asglyph", requireNotNull(rgb), requireNotNull(tol),
                requireNotNull(threshold.toIntOrNull()), requireNotNull(gap.toIntOrNull()), requireNotNull(additionalColors)).also {
                it.arguments(ImageToolCodeGen.Roi(0, 0, 1, 1))
            }
        }.getOrElse { message = "请检查颜色、容差、相似度与空格列数后再配置识字"; page = 2; return } else null
        fun use(updated: ProjectSnapshot, path: String) {
            // Only a borrowed, identical bitmap may hand its ROI back to its owning image window.
            onUseSaved?.invoke(updated, requireNotNull(settings).copy(dictionaryPath = path), roi.takeIf { image === initialBitmap })
        }
        val previous = lastSaved
        if (useAfter && previous != null && previous.first == revision && previous.second == fileName &&
            lastSavedResourceRevision == latestSnapshot.resourceRevision &&
            latestSnapshot.manifest.resources.any { isGlyphDictionaryResource(it) && it.get("path")?.asString == previous.third }) {
            use(latestSnapshot, previous.third)
            return
        }
        val expected = latestSnapshot.resourcePaths()
        val projectId = snapshot.manifest.projectId
        val savedVersion = revision
        busy = true; saving = true
        job = scope.launch {
            try {
                val (updated, path) = savePrompt.run { mode -> withContext(Dispatchers.IO) {
                    ensureActive()
                    val encoded = GlyphDictionaryMaker.encode(frozen)
                    val imported = encoded.inputStream().use { store.importResource(projectId, ProjectResourceKind.GLYPH_DICTIONARY,
                        "$fileName.asglyph", it, expected, mode = mode) }
                    imported to importedResourcePath(imported, expected, mode)
                } }
                ensureActive()
                onSnapshotChanged(updated); savedRevision = savedVersion
                lastSaved = Triple(savedVersion, fileName, path)
                lastSavedResourceRevision = updated.resourceRevision
                message = "已保存字库 ${path.substringAfterLast('/')}。"
                if (useAfter) use(updated, path)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (invalid: IllegalArgumentException) { message = invalid.message ?: "资源已变化，请重新读取项目后保存" }
            catch (_: Exception) { message = "字库保存失败，样本保留" }
            finally { if (ticket == operationTicket) { busy = false; saving = false; job = null } }
        }
    }

    fun test() {
        if (labelDirty) { message = "样本字符修改尚未应用，请先应用或恢复标注"; page = 1; return }
        // Append-and-test runs before recomposition; inspect current state, not the rendered batch.
        if (pendingBatch?.samples?.isNotEmpty() == true) { message = "待标注样本尚未加入，请先追加或放弃后测试"; page = 0; return }
        val source = image ?: return
        val region = roi ?: return
        val color = rgb ?: return
        val colorTolerance = tol ?: return
        val similarity = threshold.toIntOrNull()?.takeIf { it in 0..1000 } ?: run { message = "相似度须为0–1000‰"; return }
        val spaceGap = gap.toIntOrNull()?.takeIf { it in 0..65535 } ?: run { message = "空格列数须为0–65535；0不插入空格"; return }
        val encoded = try { GlyphDictionaryMaker.encode(samples) } catch (invalid: IllegalArgumentException) { message = invalid.message.orEmpty(); return }
        if (busy) return
        if (source.width.toLong() * source.height > GlyphDictionaryMaker.MAX_IMAGE_PIXELS) { message = "原图超过400万像素，请先裁剪"; return }
        val ticket = ++operationTicket
        val tested = previewInputs()
        val frozen = runCatching { source.copy(Bitmap.Config.ARGB_8888, false) }.getOrNull()
            ?: run { message = "无法准备测试副本，请缩小取材范围"; return }
        val id = System.nanoTime().coerceAtLeast(1L)
        requestId = id; busy = true; testResult = null; testDetails = null; recognizedText = null; message = "正在使用Rust字库引擎测试…"
        job = scope.launch {
            try {
                prepareRecognitionPreview(client, Reply.GLYPH_DETAILS)
                val started = System.nanoTime()
                val result = withContext(Dispatchers.IO) {
                    var frame: File? = null; var dictionary: File? = null
                    try {
                        ensureActive()
                        frame = File.createTempFile("glyph-test-", ".png", context.cacheDir)
                        dictionary = File.createTempFile("glyph-test-", ".asglyph", context.cacheDir)
                        frame.outputStream().use { require(frozen.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                        dictionary.writeBytes(encoded)
                        ensureActive()
                        client.testRecognitionPreview(id, Reply.GLYPH_DETAILS, frame, dictionary,
                            ImageToolOcrConfig(Reply.GLYPH, foregroundRgb = color, tolerance = colorTolerance,
                                threshold = similarity, spaceGapColumns = spaceGap, additionalColors = requireNotNull(additionalColors)).options(region))
                    } finally { frame?.delete(); dictionary?.delete() }
                }
                if (requestId == id) {
                    testedInputs = tested
                    val current = client.isRecognitionPreviewCurrent(result)
                    testDetails = if (current && result.operation == Reply.GLYPH_DETAILS &&
                        result.status in listOf(Reply.SUCCESS, Reply.NOT_FOUND)) decodeOcrPreviewDetails(result.text, source.width, source.height) else null
                    recognizedText = testDetails?.text
                    testResult = if (current)
                        recognitionPreviewMessage(result) + "\n测试往返 ${(System.nanoTime() - started) / 1_000_000}ms（含编码/传输）"
                    else "运行服务会话已变化，请重新测试"
                    message = "本次测试已返回；修改样本、颜色或范围后需重新测试。"
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (unavailable: RecognitionUnavailable) { if (requestId == id) message = unavailable.message.orEmpty() }
            catch (_: Exception) { if (requestId == id) message = "字库测试失败，请复查运行环境；样本保留" }
            finally { frozen.recycle(); if (ticket == operationTicket) { requestId = 0L; busy = false; job = null } }
        }.also { it.invokeOnCompletion { if (!frozen.isRecycled) frozen.recycle() } }
    }

    fun append(andTest: Boolean) {
        if (labelDirty) { message = "请先应用或恢复样本页的字符标注"; page = 1; return }
        try {
            val batch = requireNotNull(pendingBatch)
            require(batch.ready) { "请先完成本批每个字形的标注" }
            val before = samples.size
            val merged = GlyphDictionaryMaker.append(samples, batch.samples, batch.text)
            if (merged !== samples) change(merged)
            val skipped = pending.size - (merged.size - before)
            pendingBatch = null; pendingBatchId++
            message = "新增${merged.size - before}个样本${if (skipped > 0) "，忽略${skipped}个重复" else ""}；可继续框选补样。"
            if (andTest) { page = 2; test() }
        } catch (invalid: IllegalArgumentException) { message = invalid.message.orEmpty() }
    }

    ProvideTextStyle(LocalTextStyle.current.copy(lineHeight = 14.sp)) {
    StudioPanelDialog("字库制作 · 固定字形", ::close, fullScreen = fullScreen, showFooter = false,
        compactHeader = true, onToggleFullScreen = { fullScreen = !fullScreen }) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
        val compactFooter = studioShortViewport(maxHeight.value)
        Column(Modifier.fillMaxWidth().heightIn(max = if (fullScreen) 1200.dp else 620.dp)) {
            Row(Modifier.fillMaxWidth().background(AutoScriptPalette.PageBackground).padding(6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf("取样", "样本 ${samples.size}", "测试设置").forEachIndexed { index, text ->
                    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
                        TextButton(onClick = { page = index }, modifier = Modifier.weight(1f).heightIn(min = 28.dp),
                            colors = ButtonDefaults.textButtonColors(containerColor = if (page == index) Color.White else Color.Transparent),
                            shape = RoundedCornerShape(5.dp),
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp)) {
                            Text(text, fontSize = 12.sp, fontWeight = if (page == index) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (page == index) AutoScriptPalette.Accent else AutoScriptPalette.TextSecondary)
                        }
                    }
                }
            }
            Column(Modifier.weight(1f).background(AutoScriptPalette.PageBackground).verticalScroll(bodyScroll).padding(horizontal = 8.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp)) {
                if (compactFooter) {
                    ImageToolCompactInput("字库名称", name, !busy, Modifier.fillMaxWidth()) { name = it.take(72) }
                    SelectionContainer { Text(message, fontSize = 10.sp, color = AutoScriptPalette.TextSecondary) }
                }
                when (page) {
                    0 -> {
                        GlyphMakerSection("取材与选区", if (image == null) "原像素取样" else "${image.width}×${image.height}px") {
                        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            GlyphAction("项目图片", !busy && pending.isEmpty()) { chooser = "image" }
                            GlyphAction("选择图片", !busy && pending.isEmpty()) { picker.launch(arrayOf("image/png", "image/jpeg", "image/webp", "image/bmp")) }
                            GlyphAction("打开字库", !busy && !dirty) { chooser = "dictionary" }
                            GlyphAction(if (help) "收起说明" else "取样说明", true) { help = !help }
                        }
                        if (image != null) {
                            BoxWithConstraints(Modifier.fillMaxWidth().height(144.dp).background(Color(0xff171923))
                                .semantics { contentDescription = "字库取样预览" }) {
                                val w = constraints.maxWidth.toFloat(); val h = constraints.maxHeight.toFloat()
                                LaunchedEffect(image, w, h) {
                                    if (w > 0 && h > 0) viewport = viewport?.takeIf { it.imageWidth == image.width && it.imageHeight == image.height }
                                        ?.resized(w, h) ?: glyphMakerFocus(ImageToolViewport.fit(image.width, image.height, w, h), selection.box)
                                }
                                ImageToolCanvas(image, viewport, mode, zoom || busy, false, selection, Offset.Zero, null, null,
                                    onViewport = { viewport = it }, onSelection = { next ->
                                        if (!busy) {
                                            selection = next
                                            if (mode == ImageToolMode.COLOR) next.points.lastOrNull()?.let {
                                                val updated = palette.pick(it.rgb)
                                                if (updated == palette && palette.colors.size == ProjectGlyphColor.MAX_COLORS && palette.colors.none { c -> c.parsed()?.rgb == it.rgb })
                                                    message = "最多16种颜色，请先删除一种；未替换已有颜色"
                                                palette = updated
                                            }
                                        }
                                    }, onCrosshair = {}, onSlide = { _, _ -> }, displayBitmap = foregroundPreview.bitmap)
                            }
                            GlyphMakerPreviewToolbar(mode, zoom, !busy,
                                onMode = { mode = it; zoom = false }, onMove = { zoom = true },
                                onReset = { selection = selection.copy(box = null); mode = ImageToolMode.CROP; zoom = false },
                                onFocus = { viewport = viewport?.let { glyphMakerFocus(it, selection.box) } },
                                onFit = { viewport = viewport?.let { glyphMakerFocus(it) } },
                                onZoom = { factor -> viewport = viewport?.let { glyphMakerZoom(it, factor) } },
                                onActualSize = { viewport = viewport?.actualSize() })
                            roi?.let { Text("原图${image.width}×${image.height} · X${it.left} Y${it.top} · ${it.width}×${it.height}px · 显示${((viewport?.scale ?: 1f) * 100).roundToInt()}%", fontSize = 10.sp) }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                GlyphAction(if (blackWhite) "查看原图" else "查看黑白字形", !busy) { blackWhite = !blackWhite }
                                if (blackWhite) Text(foregroundPreview.message, fontSize = 10.sp, modifier = Modifier.weight(1f))
                            }
                            roi?.let { region -> GlyphMakerSelectionEditor(region, image.width, image.height, !busy) {
                                selection = selection.copy(box = it); mode = ImageToolMode.CROP; zoom = false
                            } }
                        } else Text("选择含固定数字的图片；截图画布也可直接点「制字」。", fontSize = 11.sp)
                        }
                        GlyphMakerSection("提取字形", "${palette.colors.size}/16色 · 命中任意色即保留") {
                        GlyphColorPaletteEditor(palette, !busy) { palette = it }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            GlyphAction("提取单个", !busy && image != null && rgb != null && tol != null) { extract(false) }
                            GlyphMakerPrimaryAction("按空隙批量切分", !busy && image != null && rgb != null && tol != null) { extract(true) }
                        }
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                            Text("采样目标", fontSize = 10.sp)
                            GlyphMakerCharacterSet.entries.forEach { group ->
                                GlyphAction(if (characterSet == group) "✓ ${group.title}" else group.title, !busy) {
                                    characterSet = group; nextSampleLabel = ""
                                }
                            }
                        }
                        if (characterSet == GlyphMakerCharacterSet.CUSTOM)
                            ImageToolCompactInput("目标字符", customCharacters, !busy, Modifier.fillMaxWidth()) {
                                customCharacters = it.take(256); nextSampleLabel = ""
                            }
                        if (targetCharacters == null) Text("目标需为1–128个字符，不能含空白；不影响已有样本。", fontSize = 10.sp, color = AutoScriptPalette.Danger)
                        else Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                            val total = glyphMakerLabelUnits(targetCharacters).size
                            val missing = glyphMakerLabelUnits(targetMissing.orEmpty())
                            Text("已采${total - missing.size}/$total" + if (missing.isEmpty()) " · 目标已齐" else " · 缺采", fontSize = 10.sp)
                            missing.forEach { value -> GlyphAction(value, !busy && pending.isEmpty()) {
                                nextSampleLabel = value; mode = ImageToolMode.CROP; zoom = false
                                message = "下一次单字取样预填「$value」，请框选实际字形后提取。"
                            } }
                        }
                        if (nextSampleLabel.isNotEmpty()) Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("待补：$nextSampleLabel · 仅单字取样预填", fontSize = 10.sp)
                            GlyphAction("取消预填", !busy) { nextSampleLabel = "" }
                        }
                        if (help) Text("取色首次替换当前色，随后连续追加；点色块后可修改该色，再取一次会替换它。每色容差独立；多色取并集。批量按行列空隙分字，粘连需重框。单独保存字库只存字形；保存并识字携带色组。显示放大不改变保存像素。", fontSize = 10.sp, color = AutoScriptPalette.TextSecondary)
                        }
                        pendingBatch?.let { batch ->
                            Column(Modifier.fillMaxWidth().bringIntoViewRequester(pendingIntoView), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                                LaunchedEffect(pendingBatchId) {
                                    withFrameNanos { }
                                    pendingIntoView.bringIntoView()
                                }
                                key(pendingBatchId) {
                                    GlyphMakerPendingEditor(batch, targetCharacters.orEmpty(), samples, pendingEditing, !busy,
                                        onBatch = { pendingBatch = it }, onAppend = ::append,
                                        onDiscard = { pendingBatch = null; pendingBatchId++; message = "待加入样本已放弃，样本库保留" },
                                        onMessage = { message = it })
                                }
                            }
                        }
                    }
                    1 -> {
                        GlyphMakerSection("样本库", "${samples.map { it.label }.distinct().size}种字符 · ${samples.size}/256样本") {
                        targetMissing?.let { missing ->
                            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                                Text(if (missing.isEmpty()) "当前采样目标已齐，可继续补同字变体" else "当前目标缺：$missing",
                                    fontSize = 10.sp, color = AutoScriptPalette.TextSecondary)
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            ImageToolCompactInput("查找字符", query, !busy, Modifier.weight(1f)) { query = it.take(32) }
                            GlyphAction(if (query.isEmpty()) "继续取样" else "清除筛选", !busy) { if (query.isEmpty()) page = 0 else query = "" }
                        }
                        if (samples.isEmpty()) Text("暂无样本，请先取样或打开项目字库。", fontSize = 11.sp)
                        else if (visibleIndices.isEmpty()) Text("没有匹配的字符；筛选不删除或改变已选样本。", fontSize = 10.sp)
                        else BoxWithConstraints(Modifier.fillMaxWidth()) {
                            val columns = max(1, ((maxWidth.value + 4) / 50).toInt())
                            val rows = (visibleIndices.size + columns - 1) / columns
                            LazyVerticalGrid(GridCells.Adaptive(46.dp),
                            Modifier.fillMaxWidth().height((rows * 60).coerceIn(60, 124).dp), state = sampleGrid,
                            horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            items(visibleIndices.size, key = { visibleIndices[it] }) { item ->
                                val index = visibleIndices[item]; val glyph = samples[index]
                                Column(Modifier.background(if (selected == index) AutoScriptPalette.AccentSoft else Color.White, RoundedCornerShape(4.dp))
                                    .border(1.dp, if (selected == index) AutoScriptPalette.Accent else AutoScriptPalette.Border, RoundedCornerShape(4.dp))
                                    .semantics { contentDescription = "样本${index + 1}：${glyph.label}" }.clickable(enabled = !busy) {
                                        if (labelDirty) message = "请先应用或恢复当前标注，再切换样本"
                                        else { selected = index; label = glyph.label; message = "已选择样本${selected + 1}" }
                                    }.padding(3.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                    GlyphMask(glyph, Modifier.size(30.dp)); Text(glyph.label, fontSize = 10.sp, maxLines = 1)
                                }
                            }
                        }
                        }
                        }
                        samples.getOrNull(selected)?.let { glyph ->
                            GlyphMakerSection("样本${selected + 1} · ${glyph.width}×${glyph.height}px", "同字${samples.count { it.label == glyph.label }}个样本") {
                                    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        ImageToolCompactInput("字符标注", label, !busy, Modifier.widthIn(min = 90.dp).weight(1f)) { label = it.take(32) }
                                        GlyphAction("上一个", !busy && selected > 0) {
                                            if (labelDirty) message = "请先应用或恢复当前标注"
                                            else { selected--; label = samples[selected].label; message = "已选择样本${selected + 1}" }
                                        }
                                        GlyphAction("下一个", !busy && selected < samples.lastIndex) {
                                            if (labelDirty) message = "请先应用或恢复当前标注"
                                            else { selected++; label = samples[selected].label; message = "已选择样本${selected + 1}" }
                                        }
                                        GlyphAction("应用", !busy) {
                                            try {
                                                val changed = samples.toMutableList().apply { set(selected, glyph.copy(label = label)) }
                                                GlyphDictionaryMaker.validateSamples(changed); change(changed); message = "样本标注已应用"
                                            } catch (invalid: IllegalArgumentException) { message = invalid.message.orEmpty() }
                                        }
                                        GlyphAction(if (labelDirty) "恢复" else "删除", !busy) {
                                            if (labelDirty) { label = glyph.label; message = "已恢复样本标注" }
                                            else { change(samples.filterIndexed { index, _ -> index != selected }); message = "样本已删除，可撤销" }
                                        }
                                    }
                                    if (query.isNotEmpty()) Text("已筛选${visibleIndices.size}/${samples.size} · 当前仍为样本${selected + 1}", fontSize = 10.sp)
                                    key(selected) { GlyphMakerPixelEditor(glyph, !busy && !labelDirty) { edited ->
                                        val changed = samples.toMutableList().apply { set(selected, edited) }
                                        try {
                                            val previous = revision
                                            change(changed)
                                            message = if (revision != previous) "一笔修点已应用，可撤销" else "字形未变化"
                                        } catch (invalid: IllegalArgumentException) { message = invalid.message.orEmpty() }
                                    } }
                                    Text("最多12步撤销/重做 · 历史位图8MiB · 不改变字形尺寸", fontSize = 10.sp, color = AutoScriptPalette.TextSecondary)
                                }
                        }
                    }
                    else -> {
                        GlyphMakerSection("识别参数", "Rust固定字库 · 不启动脚本") {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            ImageToolCompactInput("相似度 ‰", threshold, !busy, Modifier.weight(1f), numeric = true) { threshold = it.take(4) }
                            ImageToolCompactInput("空格列数", gap, !busy, Modifier.weight(1f), numeric = true) { gap = it.take(5) }
                        }
                        Text("前景${palette.colors.size}色 · ${roi?.let { "X${it.left} Y${it.top} / ${it.width}×${it.height}px" } ?: "尚未选择取材图片"}", fontSize = 10.sp, color = AutoScriptPalette.TextSecondary)
                        GlyphColorPaletteEditor(palette, !busy) { palette = it }
                        ImageToolCompactInput("预期文字", expectedText, !busy) { expectedText = it.take(512) }
                        Text("选填，用于核对本次结果；不参与识别或保存到字库。", fontSize = 10.sp, color = AutoScriptPalette.TextSecondary)
                        GlyphMakerPrimaryAction("测试当前原图选区", !busy && image != null && samples.isNotEmpty() && rgb != null && tol != null) { test() }
                        }
                    }
                }
                testResult?.takeIf { testedInputs == previewInputs() }?.let { result ->
                    GlyphMakerSection("测试结果", "当前参数") {
                    SelectionContainer { Text(result, fontSize = 12.sp, color = AutoScriptPalette.Accent) }
                    if (expectedText.isNotEmpty()) Text(glyphMakerExpectedFeedback(expectedText, recognizedText, samples),
                        fontSize = 11.sp, color = if (recognizedText == expectedText) AutoScriptPalette.Mint else AutoScriptPalette.Danger)
                    testDetails?.let { details -> if (image != null) {
                        Text("未识别${details.glyphs.count { !it.recognized }}项 · 低分${details.glyphs.count { it.recognized && it.score < 980 }}项（低于980‰，仅提示）", fontSize = 10.sp)
                        OcrDetailPreview(image, details, compact = compactFooter, lowScoreBelow = 980, onResample = { glyph ->
                            selection = selection.copy(box = ImageToolCodeGen.Roi(glyph.left, glyph.top, glyph.right, glyph.bottom))
                            viewport = viewport?.let { glyphMakerFocus(it, selection.box) }
                            nextSampleLabel = ""; mode = ImageToolMode.CROP; zoom = false; page = 0
                            message = "已定位第${details.glyphs.indexOf(glyph) + 1}项原图字框，请核对后提取单个并填写实际字符。"
                        })
                    } }
                    }
                }
                if (testResult != null && testedInputs != previewInputs()) {
                    Text("样本或识别参数已变更，测试结果已失效，请重新测试。", fontSize = 10.sp, color = AutoScriptPalette.Warning)
                }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp))
            HorizontalDivider()
            if (!compactFooter) {
            SelectionContainer(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 3.dp)) {
                Text(message, fontSize = 10.sp, color = AutoScriptPalette.TextSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                ImageToolCompactInput("字库名称", name, !busy, Modifier.weight(1f)) { name = it.take(72) }
                GlyphMakerPrimaryAction(if (saving) "保存中…" else "保存字库", !busy && samples.isNotEmpty()) { save() }
            }
            }
            val footerActions: @Composable RowScope.() -> Unit = {
                GlyphAction(if (busy && !saving) "取消任务" else "返回", !saving) { if (busy) { cancel(); message = "任务已取消，样本保留" } else close() }
                if (compactFooter) GlyphMakerPrimaryAction(if (saving) "保存中…" else "保存字库", !busy && samples.isNotEmpty()) { save() }
                GlyphAction("撤销", !busy && history.canUndo) {
                    if (labelDirty) message = "请先应用或恢复当前标注"
                    else { showVersion(history.undo(selected)); message = "已撤销样本操作" }
                }
                GlyphAction("重做", !busy && history.canRedo) {
                    if (labelDirty) message = "请先应用或恢复当前标注"
                    else { showVersion(history.redo(selected)); message = "已重做样本操作" }
                }
                GlyphAction("快速测试", !busy && image != null && samples.isNotEmpty() && rgb != null && tol != null) { page = 2; test() }
                if (onUseSaved != null) GlyphAction("保存并识字", !busy && samples.isNotEmpty()) { save(true) }
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically,
                content = footerActions)
        }
        }
    }
    }
    if (discard) EditorPromptDialog("关闭字库制作？", "未保存的样本、修点、待标注内容及未使用的色组将丢弃；字库文件只保存字形，已保存文件不受影响。",
        { discard = false }, { discard = false; onDismiss() }, "继续制作", "放弃")
    replacePending?.let { batch -> EditorPromptDialog("重新切分？", "当前待加入的字形和标注会替换，已加入的样本保留。",
        { replacePending = null }, { replacePending = null; extract(batch, confirmed = true) }, "保留当前", "重新切分") }
    chooser?.let { kind ->
        val paths = snapshot.manifest.resources.filter { if (kind == "image") it.get("kind")?.asString == "image" else isGlyphDictionaryResource(it) }
            .mapNotNull { it.get("path")?.asString }.sorted()
        StudioPanelDialog(if (kind == "image") "选择项目图片" else "打开项目字库", { chooser = null }, showFooter = false, compactHeader = true) {
            Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()).padding(10.dp)) {
                if (paths.isEmpty()) Text("暂无资源；可先导入或制作。", fontSize = 12.sp)
                paths.forEach { path -> TextButton(onClick = {
                    chooser = null
                    if (kind == "image") loadImage { decodeGlyphMakerImage(imageRecognitionFile(snapshot, path)) }
                    else if (!busy && !dirty) {
                        val ticket = ++operationTicket
                        busy = true
                        job = scope.launch {
                            try {
                                val loaded = withContext(Dispatchers.IO) {
                                    val file = imageRecognitionDictionary(snapshot, path)
                                    require(file.length() <= GlyphDictionaryMaker.MAX_BITMAP_BYTES + 256L * 44 + 16) { "字库超过制作预算，原文件未改变" }
                                    GlyphDictionaryMaker.decode(file.readBytes())
                                }
                                showVersion(history.reset(loaded))
                                pendingBatch = null; pendingBatchId++
                                savedRevision = revision; lastSaved = null; testResult = null
                                name = File(path).nameWithoutExtension; query = ""; page = 1
                                message = "已载入字库；修改先保留在样本中，同名保存需确认覆盖。"
                            } catch (cancelled: CancellationException) { throw cancelled }
                            catch (invalid: IllegalArgumentException) { message = invalid.message.orEmpty() }
                            catch (_: Exception) { message = "字库读取失败或不符合制作格式，原文件保留" }
                            finally { if (ticket == operationTicket) { busy = false; job = null } }
                        }
                    }
                }) { Text(path, fontSize = 12.sp) } }
            }
        }
    }
}

private fun decodeGlyphMakerImage(file: File): Bitmap {
    require(file.length() in 1..24L * 1024 * 1024)
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    require(bounds.outWidth in 1..4096 && bounds.outHeight in 1..4096 &&
        bounds.outWidth.toLong() * bounds.outHeight <= GlyphDictionaryMaker.MAX_IMAGE_PIXELS)
    return requireNotNull(BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply {
        inPreferredConfig = Bitmap.Config.ARGB_8888; inScaled = false
    }))
}

@Composable
private fun GlyphAction(text: String, enabled: Boolean, onClick: () -> Unit) {
    ImageToolCompactAction(text, enabled, onClick = onClick)
}

@Composable
internal fun GlyphMask(glyph: MakerGlyph, modifier: Modifier) {
    // Bound the whole 256-item thumbnail collection to at most 4 MiB instead of
    // issuing up to 16 million Canvas rectangles again on every recomposition.
    // The separate pixel editor keeps its full original pixel grid.
    val preview = remember(glyph.width, glyph.height, glyph.bits) {
        runCatching {
            val ratio = min(1f, 64f / max(glyph.width, glyph.height))
            val w = max(1, (glyph.width * ratio).toInt()); val h = max(1, (glyph.height * ratio).toInt())
            val colors = IntArray(w * h) { index ->
                if (glyph.foreground((index % w) * glyph.width / w, (index / w) * glyph.height / h))
                    0xff171923.toInt() else -1
            }
            Bitmap.createBitmap(colors, w, h, Bitmap.Config.ARGB_8888)
        }.getOrNull()
    }
    DisposableEffect(preview) { onDispose { preview?.recycle() } }
    if (preview == null) {
        Box(modifier, contentAlignment = Alignment.Center) { Text("预览内存不足", fontSize = 9.sp) }
        return
    }
    val image = remember(preview) { preview.asImageBitmap() }
    Canvas(modifier.background(Color(0xffededf1))) {
        val scale = glyphMakerMaskScale(glyph.width, glyph.height, size.width.toInt(), size.height.toInt())
        val dx = (size.width - glyph.width * scale) / 2; val dy = (size.height - glyph.height * scale) / 2
        drawImage(image, dstOffset = IntOffset(dx.toInt(), dy.toInt()),
            dstSize = IntSize(max(1, (glyph.width * scale).toInt()), max(1, (glyph.height * scale).toInt())),
            filterQuality = FilterQuality.None)
    }
}

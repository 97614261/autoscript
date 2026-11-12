package com.autoscript.studio

import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import com.autoscript.project.store.ProjectSnapshot
import com.autoscript.project.store.ProjectStore
import com.autoscript.project.store.ProjectImageRecipe
import com.autoscript.project.store.ProjectImageRecipeTail
import com.autoscript.runtime.client.RuntimeClient
import kotlinx.coroutines.*

private data class ImageWorkbenchSaveRequest(
    val image: Bitmap, val pipeline: ImageProcessingPipeline?, val target: ImageSaveTarget,
)

/** One editor workspace for the library and screenshot tools; never owns the borrowed source. */
@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun ImageProcessingWorkbench(bitmap: Bitmap, snapshot: ProjectSnapshot, store: ProjectStore,
    runtimeClient: RuntimeClient, onSnapshotChanged: (ProjectSnapshot) -> Unit, onDismiss: () -> Unit,
    originalImagePath: String? = null, insertionTarget: ImageWorkbenchInsertionTarget? = null,
    initialFilterOpen: Boolean = false,capturedScreen:Boolean=false,initialPolygonOpen:Boolean=false, capture: ImageWorkspaceCapture? = null,
    modifier: Modifier = Modifier) {
    val history = remember(bitmap, snapshot.manifest.projectId) {
        runCatching { ImageProcessingHistory(ImageProcessingVersion(bitmap, ImageProcessingPipeline(bitmap.width, bitmap.height)),
            { it.image.allocationByteCount.toLong() }, { it.image.recycle() }) }.getOrNull()
    }
    val workspaceIdentity = remember(bitmap, snapshot.manifest.projectId) { Any() }
    val drafts = remember(workspaceIdentity) { ImageWorkspaceDrafts() }
    val surface = remember(workspaceIdentity) { ImageWorkspaceSurface().also {
        it.mode.value = capture?.initialMode ?: ImageToolMode.CROP
        it.polygonEditing = initialPolygonOpen || capture?.initialSearchPolygon != null
        if (it.polygonEditing) it.toolbarMenu = ImageToolbarMenu.SELECTION
        val points = runCatching { ImageSearchPolygon.decode(capture?.initialSearchPolygon.orEmpty()) }.getOrDefault(emptyList())
        if (points.isNotEmpty()) it.selection.value = ImageToolSelection(box=ImageSearchPolygon.bounds(points),polygon=points)
    } }
    val navigation = remember(workspaceIdentity) { ImageWorkspaceNavigation(if (initialFilterOpen) "filter" else null) }
    val panel = navigation.panel
    var captureAfterClose by remember(workspaceIdentity) { mutableStateOf(false) }
    SideEffect { surface.cancelNavigation = { navigation.cancelPending() } }
    fun closePanel() { navigation.finish() }
    var revision by remember(workspaceIdentity) { mutableLongStateOf(0) }
    var originalVisible by remember(workspaceIdentity) { mutableStateOf(false) }
    var processing by remember(workspaceIdentity) { mutableStateOf(false) }
    var saving by remember(workspaceIdentity) { mutableStateOf(false) }
    var task by remember(workspaceIdentity) { mutableStateOf<Job?>(null) }
    var message by remember(workspaceIdentity) { mutableStateOf(if (history == null) "原图超出处理预算，可框选后存模板。" else "") }
    StudioSystemLogEffect("图像处理", message, skipInitial = true)
    LaunchedEffect(capture?.message) { capture?.message?.takeIf { it.isNotBlank() }?.let { message = it } }
    var saveRequest by remember(workspaceIdentity) { mutableStateOf<ImageWorkbenchSaveRequest?>(null) }
    var recipeRecognition by remember(workspaceIdentity) {mutableStateOf<Pair<ProjectImageRecipeTail,ImageToolCodeGen.Roi>?>(null)}
    var loadedRecipe by remember(workspaceIdentity) {mutableStateOf<ProjectImageRecipe?>(null)}
    var pendingRecipe by remember(workspaceIdentity) {mutableStateOf<Pair<Bitmap,ProjectImageRecipe>?>(null)}
    var lastFilter by remember(workspaceIdentity) { mutableStateOf(com.autoscript.project.store.ProjectImageFilter()) }
    var glyphUse by remember(workspaceIdentity) { mutableStateOf<Triple<ProjectSnapshot, ImageToolOcrConfig, ImageToolCodeGen.Roi?>?>(null) }
    var closeConfirm by remember(workspaceIdentity) { mutableStateOf(false) }
    var lastSavedImage by remember(workspaceIdentity) { mutableStateOf(bitmap) }
    var insertionConfiguration by remember(workspaceIdentity) { mutableStateOf(false) }
    var recognitionTail by remember(workspaceIdentity) { mutableStateOf<ImagePipelineRecognitionTail?>(null) }
    var recognitionValidity by remember(workspaceIdentity) { mutableStateOf<(() -> Boolean)?>(null) }
    var insertion by remember(workspaceIdentity) { mutableStateOf<Pair<ImageBlockInsertionRequest, ImageWorkbenchInsertionTarget>?>(null) }
    var inserting by remember(workspaceIdentity) { mutableStateOf(false) }
    var showRulers by remember(workspaceIdentity) { mutableStateOf(false) }
    var selection by surface.selection
    var viewport by surface.viewport
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val latestHistory by rememberUpdatedState(history)
    val latestWorkspaceIdentity by rememberUpdatedState(workspaceIdentity)
    val latestSnapshot by rememberUpdatedState(snapshot)
    val latestSnapshotChanged by rememberUpdatedState(onSnapshotChanged)
    // revision makes otherwise non-Compose history changes observable.
    val version = remember(history, revision, originalVisible) { if (originalVisible) history?.original else history?.current }
    val displayed = version?.image ?: bitmap
    val selectionProblem=remember(displayed,selection.box,selection.polygon) {imageSelectionProblem(selection,displayed.width,displayed.height)}
    val imageIdentity = remember(displayed) { Any() }
    val busy = processing || saving || inserting || saveRequest != null
    DisposableEffect(workspaceIdentity) { onDispose { task?.cancel(); history?.close() } }
    LaunchedEffect(displayed) {
        if (selection.box?.let {it.right>displayed.width || it.bottom>displayed.height} == true ||
            selection.points.any {it.x>=displayed.width || it.y>=displayed.height}) selection=ImageToolSelection()
        recognitionTail=null;recognitionValidity=null
        val restored=pendingRecipe?.takeIf {it.first===displayed}?.second
        loadedRecipe=restored
        if (restored != null) { drafts.match = null; drafts.ocr = null }
        recipeRecognition=restored?.tail?.let {it to requireNotNull(restored.region).toolRegion()}
        restored?.region?.let {selection=selection.copy(box=it.toolRegion())}
        pendingRecipe=null
    }
    fun boundRecognitionProof(validity: () -> Boolean): () -> Boolean {
        val resources = snapshot.manifest.resources.toString()
        val resourceRevision = snapshot.resourceRevision
        return { latestSnapshot.resourceRevision == resourceRevision &&
            latestSnapshot.manifest.resources.toString() == resources && validity() }
    }

    fun applyRecipe(recipe:ProjectImageRecipe) {
        val owner=history ?: return
        if(busy) return
        val pipeline=try {
            require(recipe.sourceWidth==bitmap.width && recipe.sourceHeight==bitmap.height)
            recipe.validateResources(snapshot.manifest.resources)
            ImageProcessingPipeline(bitmap.width,bitmap.height,recipe.steps.map {it.pixelOperation()}).also {
                owner.validateResultBytes(it.dimensions.first.toLong()*it.dimensions.second*4)
            }
        } catch(_:Exception) {message="方案尺寸、资源或预算已变化；原图和当前预览保留";return}
        val ticket=owner.revision
        val frozen=runCatching {bitmap.copy(Bitmap.Config.ARGB_8888,false)}.getOrNull()
            ?: run {message="无法准备方案原图，当前预览保留";return}
        processing=true;originalVisible=false
        task=scope.launch {
            var result:Bitmap?=null
            try {
                result=withContext(Dispatchers.Default) {
                    val work=currentCoroutineContext();val deadline=System.nanoTime()+5_000_000_000L
                    processImagePipelineBitmap(frozen,pipeline) {work.ensureActive();require(System.nanoTime()<deadline)}.also {result=it}
                }
                ensureActive();val published=requireNotNull(result);result=null
                if(owner.publish(ImageProcessingVersion(published,pipeline),ticket)) {
                    pendingRecipe=published to recipe;revision=owner.revision;glyphUse=null
                    message="已载入 ${recipe.name} · ${pipeline.operations.size}步 · 未自动测试；请重新测试后加入"
                }
            } catch(cancelled:CancellationException) {throw cancelled}
            catch(_:Throwable) {if(owner===latestHistory) message="方案处理失败、超时或超过预算，原图和预览保留"}
            finally {result?.recycle();frozen.recycle();if(owner===latestHistory) {processing=false;task=null}}
        }.also {it.invokeOnCompletion {if(!frozen.isRecycled) frozen.recycle()}}
    }

    fun requestDismiss() {
        if (busy || history?.current?.image?.let { it !== bitmap && it !== lastSavedImage } == true) closeConfirm = true
        else onDismiss()
    }
    fun requestWorkspaceClose() {
        navigation.cancelPending()
        if (panel != null) surface.requestClose?.invoke() ?: closePanel() else requestDismiss()
    }

    fun applyOperations(operations: List<ImagePixelOperation>, saveAfterApply: Boolean = false) {
        val owner = history ?: return
        if (processing || saving || inserting || saveRequest != null || originalVisible) return
        val source = owner.current.image
        val saveTarget = if (saveAfterApply) imageSaveTarget(selection, source.width, source.height)
            ?: run { message = imageSelectionProblem(selection, source.width, source.height).orEmpty(); return } else null
        val identity = workspaceIdentity
        val ticket = owner.revision
        val pipeline = try { operations.fold(owner.current.pipeline) {p,op -> p.append(op)} }
            catch (_: Exception) { message = "处理最多64步，请保存图片后重新打开作为新来源"; return }
        val frozen = try {
            val size = pipeline.dimensions
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
                    processImagePipelineBitmap(frozen,ImageProcessingPipeline(frozen.width,frozen.height,operations),checkpoint)
                        .also { result = it }
                }
                ensureActive()
                val published = requireNotNull(result)
                result = null // publish consumes ownership, including stale failure.
                if (owner.publish(ImageProcessingVersion(published, pipeline), ticket)) {
                    if(operations.any {it.mode in setOf("crop","resize","rotate90","rotate180","rotate270","flipX","flipY")}) selection=ImageToolSelection()
                    revision = owner.revision
                    message = "处理完成 · ${published.width}×${published.height}px · 保留${owner.editCount}步"
                    if (saveTarget != null && identity === latestWorkspaceIdentity && owner === latestHistory)
                        saveRequest = ImageWorkbenchSaveRequest(published, pipeline, saveTarget)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Throwable) { if (owner === latestHistory) message = "处理失败或超过预算，当前图片保留" }
            finally { result?.recycle(); frozen.recycle(); if (owner === latestHistory) { processing = false; task = null } }
        }.also { it.invokeOnCompletion { if (!frozen.isRecycled) frozen.recycle() } }
    }
    fun apply(operation:ImagePixelOperation)=applyOperations(listOf(operation))
    fun applyAndSave(operation: ImagePixelOperation) = applyOperations(listOf(operation), saveAfterApply = true)
    fun openPanel(next: String) {
        if (busy || panel == next) return
        navigation.afterClose(surface.requestClose) {
            if (originalVisible) { originalVisible = false; selection = ImageToolSelection() }
            surface.toolbarMenu = null
            navigation.open(next, null)
        }
    }
    fun openToolbar(menu: ImageToolbarMenu) {
        if (busy) return
        navigation.afterClose(surface.requestClose) { surface.toolbarMenu = if(surface.toolbarMenu==menu) null else menu }
    }
    val latestToolbar by rememberUpdatedState<(ImageToolbarMenu) -> Unit>({ openToolbar(it) })
    val returnToTools = remember(workspaceIdentity) { { latestToolbar(ImageToolbarMenu.PROCESS) } }
    SideEffect { surface.backToTools = returnToTools.takeIf { panel != null } }
    fun requestCapture() {
        if (busy || capture == null) return
        navigation.afterClose(surface.requestClose) {
            if (history?.current?.image?.let { it !== bitmap && it !== lastSavedImage } == true) {
                captureAfterClose = true; closeConfirm = true
            } else capture.onCapture()
        }
    }

    fun requestSave(wholeImage: Boolean = false) {
        if (busy) return
        val target = imageSaveTarget(selection, displayed.width, displayed.height, wholeImage)
            ?: run { message = imageSelectionProblem(selection, displayed.width, displayed.height).orEmpty(); return }
        // Polygon templates need transparent corners, not the unrelated pixels of their bounding box.
        if (target.polygon.isEmpty() && capture != null && version?.pipeline?.operations?.isEmpty() != false) capture.onSaveRegion(target.region)
        else saveRequest = ImageWorkbenchSaveRequest(displayed, version?.pipeline, target)
    }
    fun canQuickAction(key: String): Boolean {
        if (busy) return false
        val editable = !originalVisible && history != null
        return when(key) {
            "crop" -> editable && selectionProblem==null
            "keep", "ignore" -> editable && selection.polygon.isNotEmpty() && selectionProblem==null
            "match", "ocr", "glyph", "saveAll", "rulers" -> true
            "insert" -> editable && insertionTarget!=null && version!=null
            "recipe", "coordinate" -> history!=null
            else -> editable
        }
    }
    fun quickAction(key: String) {
        if (!canQuickAction(key)) return
        when(key) {
            "crop", "keep", "ignore" -> {
                if (key=="crop" && selection.polygon.isEmpty()) apply(ImagePixelOperation("crop",roi=selection.box))
                else applyOperations(polygonSelectionOperations(displayed.width,displayed.height,selection.polygon,crop=key=="crop",keep=key!="ignore"))
            }
            "gray", "rotate270", "rotate90", "rotate180", "flipX", "flipY" -> apply(ImagePixelOperation(key))
            "rulers" -> showRulers=!showRulers
            "saveAll" -> requestSave(wholeImage = true)
            "insert" -> {recognitionTail=null;recognitionValidity=null;insertionConfiguration=true}
            else -> {
                if (key=="match") selection=selection.copy(points=emptyList())
                if (key=="ocr") glyphUse=null
                openPanel(key)
            }
        }
    }
    val parameterPanel: @Composable () -> Unit = {
        CompositionLocalProvider(LocalImageWorkspace provides surface) {
        key(workspaceIdentity, panel, displayed) {
        if(panel=="coordinate") version?.let {ImageCoordinateDialog(it.pipeline,capturedScreen,selection.box) {closePanel()}}

    if (panel == "resize") ImageWorkbenchResizeDialog(displayed.width, displayed.height,
        onDismiss = { closePanel() }, onApply = { w, h -> closePanel(); apply(ImagePixelOperation("resize", w, h)) })
    if (panel == "threshold") ImageWorkbenchThresholdDialog(displayed, onDismiss = { closePanel() }, onApply = {
        closePanel(); apply(ImagePixelOperation("threshold", threshold = it))
    }, onApplyAndSave = {
        closePanel(); applyAndSave(ImagePixelOperation("threshold", threshold = it))
    })
    if (panel == "filter") ImageColorFilterDialog(lastFilter, onDismiss = { closePanel() }, onApply = {
        lastFilter=it;closePanel();apply(ImagePixelOperation("filter",filter=it))
    },bitmap=displayed,snapshot=snapshot,store=store,onApplyAndSave={
        lastFilter=it;closePanel();applyAndSave(ImagePixelOperation("filter",filter=it))
    })
    if (panel == "mask") ImageMaskDialog(displayed,onDismiss={closePanel()},
        onApply={closePanel();apply(ImagePixelOperation("mask",mask=it))},
        onApplyAndSave={closePanel();applyAndSave(ImagePixelOperation("mask",mask=it))})
    if (panel == "cleanup") ImageCleanupDialog(displayed,onDismiss={closePanel()},
        onApply={mode,spec ->closePanel();apply(ImagePixelOperation(mode,cleanup=spec))},
        onApplyAndSave={mode,spec ->closePanel();applyAndSave(ImagePixelOperation(mode,cleanup=spec))})
    if (panel == "match") ImageWorkbenchMatchDialog(displayed, selection.box, snapshot, runtimeClient,
        onResult = { result ->
            selection = selection.copy(points = if (result != null && result.status == com.autoscript.runtime.api.RecognitionPreviewReply.SUCCESS &&
                result.x in 0 until displayed.width && result.y in 0 until displayed.height)
                listOf(ImageToolCodeGen.PickedPoint(result.x, result.y, displayed.getPixel(result.x, result.y) and 0xFFFFFF)) else emptyList())
        }, onDismiss = { closePanel() }, onConfirm = if (insertionTarget == null) null else { settings, roi, validity ->
            closePanel(); recognitionTail = ImagePipelineRecognitionTail.Match(settings, roi); recognitionValidity = boundRecognitionProof(validity); insertionConfiguration = true
        },initialConfig=loadedRecipe?.matchSettings(),onSaveRecipe={settings,area ->recipeRecognition=matchRecipeTail(settings) to area;closePanel();openPanel("recipe")},
        initialDraft=drafts.match,onRememberDraft={drafts.match=it})
    if (panel == "glyph") GlyphMakerDialog(snapshot, store, runtimeClient, onSnapshotChanged,
        onDismiss = { closePanel() }, initialBitmap = displayed, initialRoi = selection.box,
        initialForeground = selection.points.lastOrNull()?.rgb ?: 0xFFFFFF,
        onUseSaved = { updated, config, roi ->
            drafts.ocr = null; glyphUse = Triple(updated, config, roi); closePanel(); openPanel("ocr")
        })
    if (panel == "ocr") ImageToolOcrDialog(displayed, glyphUse?.third ?: selection.box ?: ImageToolCodeGen.Roi(0, 0, displayed.width, displayed.height),
        snapshot, runtimeClient,
        onDismiss = { closePanel() }, onConfirm = { settings, roi ->
            if (capture != null && version?.pipeline?.operations?.isEmpty() != false) {
                val snippet = settings.snippet(roi, bitmap.width, bitmap.height)
                if (snippet.rejection != null) message = snippet.rejection else { closePanel(); capture.onEmit(snippet) }
            } else { closePanel(); recognitionTail = ImagePipelineRecognitionTail.Ocr(settings, roi); insertionConfiguration = true }
        }, allowBackfill = insertionTarget != null || (capture != null && version?.pipeline?.operations?.isEmpty() != false), confirmLabel = if (version?.pipeline?.operations?.isEmpty() != false) "加入取字" else "加入处理 + 取字", onProofConfirmed = { recognitionValidity = boundRecognitionProof(it) },
        resultReadTarget = insertionTarget, initialConfig = glyphUse?.second ?: loadedRecipe?.ocrSettings(),
        onSaveRecipe={settings,area ->recipeRecognition=ocrRecipeTail(settings) to area;closePanel();openPanel("recipe")},
        initialDraft=drafts.ocr,sourceIdentity=imageIdentity,onRememberDraft={drafts.ocr=it})
    if (panel == "recipe") {
        val current=runCatching {
            val plan=requireNotNull(version).pipeline
            ProjectImageRecipe("当前配置",plan.sourceWidth,plan.sourceHeight,plan.operations.map {it.recipeStep()},
                recipeRecognition?.second?.recipeRegion() ?: selection.box?.recipeRegion(),recipeRecognition?.first)
        }.getOrNull()
        ImageRecipeDialog(snapshot,store,current,onDismiss={closePanel()},onLoad={closePanel();applyRecipe(it)})
    }
        }
        }
    }
    val workspaceContent: @Composable () -> Unit = {
        ImageWorkspaceTheme {
        val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
        BoxWithConstraints(modifier.fillMaxSize().background(ToolChrome)) {
            val fullWidth = maxWidth.value; val fullHeight = maxHeight.value
            val keyboard = WindowInsets.ime.getBottom(LocalDensity.current) > 0
            BoxWithConstraints(Modifier.fillMaxSize().imePadding()) {
            val layout = imageWorkspaceLayout(fullWidth, fullHeight, maxHeight.value, keyboard, landscape,
                compact = false)
            val canvas: @Composable (Modifier) -> Unit = { bounds ->
                Box(bounds.clipToBounds().background(androidx.compose.ui.graphics.Color.Black)) {
                    ImageToolWindow(displayed, capture?.designWidth ?: bitmap.width, capture?.designHeight ?: bitmap.height,
                        capture?.scaleMode ?: "letterbox", initialMode=capture?.initialMode ?: ImageToolMode.CROP,
                        capturing=false, message=message.takeIf {it.isNotBlank()},
                        onCapture={ requestCapture() },
                        onEmit={ snippet -> if(capture!=null) capture.onEmit(snippet) else message="选区已保留，请选择识别测试或保存图片" },
                        onCropToTemplate={requestSave()}, onClose={requestWorkspaceClose()},
                        pointerInputSupported=capture?.pointerInputSupported ?: false,
                        onOpenRecorder=capture?.onOpenRecorder,
                        workspace=ImageWorkspaceActions(surface,
                            onSelection={openToolbar(ImageToolbarMenu.SELECTION)},onProcessing={openToolbar(ImageToolbarMenu.PROCESS)},onOcr={glyphUse=null;openPanel("ocr")},
                            onUndo=if(!busy && panel==null && history?.canUndo==true) ({history.undo();revision=history.revision;originalVisible=false;selection=ImageToolSelection()}) else null,
                            onRedo=if(!busy && panel==null && history?.canRedo==true) ({history.redo();revision=history.revision;originalVisible=false;selection=ImageToolSelection()}) else null,
                            onOriginal={navigation.afterClose(surface.requestClose) {originalVisible=!originalVisible;selection=ImageToolSelection()}},
                            showingOriginal=originalVisible,
                            onRestore={navigation.afterClose(surface.requestClose) {selection=ImageToolSelection();applyRecipe(ProjectImageRecipe("原图",bitmap.width,bitmap.height,emptyList()))}},
                            onMapPoint={x,y -> version?.pipeline?.mapToOriginal(x,y) ?: (x to y)},
                            sourceWidth=bitmap.width,sourceHeight=bitmap.height,busy=busy,
                            captureAvailable=capture!=null,panelOpen=panel!=null,onTest={openToolbar(ImageToolbarMenu.TEST)},
                            sampleColor={x,y -> val xy=version?.pipeline?.mapToOriginal(x,y) ?: (x to y); bitmap.getPixel(xy.first,xy.second) and 0xFFFFFF},
                            mapSelection={selected -> version?.pipeline?.selectionToOriginal(selected) ?: selected},
                            onQuickAction={quickAction(it)},canQuickAction={canQuickAction(it)}))
                    if(showRulers && surface.canvas==null) viewport?.let {ImageWorkbenchRulers(it)}
                }
            }
            Box(Modifier.fillMaxSize()) {
                canvas(Modifier.fillMaxSize())
                if(panel!=null) ImageDraggablePanelHost(landscape, surface.panelPlacement, Modifier.fillMaxSize(),
                    defaultPosition = ImagePanelPosition(1f, if(layout.side) 0f else 1f)) { position ->
                    val panelWidth = if(layout.side) layout.panelWidthDp.dp.coerceAtMost(maxWidth)
                        else maxWidth.coerceAtMost(440.dp)
                    val panelHeight = if(layout.side) maxHeight.coerceAtMost(520.dp)
                        else layout.panelHeightDp.dp.coerceAtMost(maxHeight)
                    Box(position.width(panelWidth).height(panelHeight)) {parameterPanel()}
                }
            }
        }
    }
    }
    }
    if(capture!=null) workspaceContent() else StudioDialog(onDismissRequest={requestWorkspaceClose()},
        properties=DialogProperties(usePlatformDefaultWidth=false,dismissOnClickOutside=false)) {workspaceContent()}
    if (insertionConfiguration && insertionTarget != null && version != null) ImagePipelineInsertDialog(version.pipeline, originalImagePath,
        snapshot, insertionTarget, onDismiss = { insertionConfiguration = false }, onReady = {
            insertion = it to insertionTarget
        }, recognitionTail = recognitionTail, recognitionIsCurrent = recognitionValidity ?: { false }, visible = insertion == null && !inserting,
        initialDraft=drafts.insertion,onRememberDraft={drafts.insertion=it})
    insertion?.let { (request, target) ->
        if (!inserting) ImageInsertionPositionDialog(request, target.editor,
            onDismiss = { insertion = null; insertionConfiguration = true }, onConfirm = { position, slot ->
                inserting = true
                task = scope.launch {
                    try {
                        val problem = target.apply(request, position, slot)
                        if (problem == null) { insertion = null; insertionConfiguration = false; message = "处理/识别积木已整批加入，可一次撤销；帧释放按所选配置执行" }
                        else { insertion = null; insertionConfiguration = true; message = problem }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { insertion = null; message = "校验或加入失败，原草稿与处理预览保留" }
                    finally { inserting = false; task = null }
                }
            })
    }

    saveRequest?.let { request -> ImageTemplateSaveDialog(store = store, projectId = snapshot.manifest.projectId,
        pixelSize = request.target.region.let {it.width to it.height},
        imagePaths = snapshot.manifest.resources.mapNotNull { it.get("path")?.asString },
        initialFolder = originalImagePath?.removePrefix("assets/images/")?.substringBeforeLast('/', "").orEmpty(),
        defaultName = originalImagePath?.substringAfterLast('/')?.substringBeforeLast('.')?.let { "$it.png" }
            ?: "processed-${System.currentTimeMillis()}.png",
        onDismiss = { saveRequest = null }, onSave = { folder, name, mode ->
            val savedImage = request.image
            val savedRegion = request.target.region
            val savedSnapshot = snapshot
            val onSavedTemplate = capture?.onTemplateSaved
            val screenRegion = if (!capturedScreen) null else if (request.pipeline != null)
                request.pipeline.screenshotTemplateRegion(savedRegion)
                else ScreenshotTemplateRegion(bitmap.width, bitmap.height, savedRegion).takeIf { it.valid() }
            val polygon = request.target.polygon.map { it.copy(x = it.x - savedRegion.left, y = it.y - savedRegion.top) }
            val (cropWidth, cropHeight) = templateCropDimensions(savedImage.width, savedImage.height, savedRegion)
            // Freeze only the selected patch; masking a small polygon must not process the entire screen.
            val patch = Bitmap.createBitmap(savedImage, savedRegion.left, savedRegion.top, cropWidth, cropHeight)
            val frozen = try { requireNotNull(patch.copy(Bitmap.Config.ARGB_8888, false)) { "无法准备保存图片；当前预览保留" } }
                finally { if (patch !== savedImage) patch.recycle() }
            saving = true
            try {
                val (updated, path) = withContext(Dispatchers.IO) {
                    ensureActive()
                    var masked: Bitmap? = null
                    try {
                        val work = currentCoroutineContext()
                        val deadline = System.nanoTime() + 5_000_000_000L
                        val material = if (polygon.isEmpty()) frozen else processImagePipelineBitmap(frozen,
                            ImageProcessingPipeline(frozen.width, frozen.height,
                                polygonSelectionOperations(frozen.width, frozen.height, polygon, crop=false, keep=true))) {
                            work.ensureActive(); check(System.nanoTime() < deadline) { "多边形保存处理超时" }
                        }.also { masked = it }
                        cropAndImportTemplate(store, savedSnapshot, material, ImageToolCodeGen.Roi(0, 0, cropWidth, cropHeight), context, folder, name,
                            mode = mode, screenRegion = screenRegion)
                    } finally { masked?.takeIf { it !== frozen }?.recycle() }
                }
                if (workspaceIdentity === latestWorkspaceIdentity) {
                    latestSnapshotChanged(updated)
                    if (polygon.isEmpty() && savedRegion == ImageToolCodeGen.Roi(0, 0, savedImage.width, savedImage.height)) lastSavedImage = savedImage
                    saveRequest = null
                    message = "已保存：${path.removePrefix("assets/images/")}"
                    onSavedTemplate?.invoke(path)
                }
            } finally { frozen.recycle(); if (workspaceIdentity === latestWorkspaceIdentity) saving = false }
        }) }
    if (closeConfirm) StudioConfirmDialog(onDismissRequest = { closeConfirm = false; captureAfterClose = false }, title = { Text(if(captureAfterClose) "重新截图？" else "关闭图像工具？") },
        text = { Text("未保存的处理预览将丢弃，进行中的任务会取消；已经提交的保存不会撤销。", fontSize = 12.sp) },
        confirmButton = { TextButton(onClick = { task?.cancel(); closeConfirm = false; if(captureAfterClose) {captureAfterClose=false;capture?.onCapture?.invoke()} else onDismiss() }) { Text(if(captureAfterClose) "重新截图" else "关闭") } },
        dismissButton = { TextButton(onClick = { closeConfirm = false; captureAfterClose=false }) { Text("继续编辑") } })
}

@Composable
private fun ImageWorkbenchResizeDialog(width: Int, height: Int, onDismiss: () -> Unit, onApply: (Int, Int) -> Unit) {
    var w by remember { mutableStateOf(width.toString()) }; var h by remember { mutableStateOf(height.toString()) }
    val valid = runCatching { ImagePixelOperation("resize", w.toInt(), h.toInt()).dimensions(width, height) }.getOrNull()
    ImageWorkspaceConfirmPanel(onDismissRequest = onDismiss, prompt = false, title = { Text("改尺寸") }, text = {
        Column {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ImageToolCompactInput("宽 px", w, true, Modifier.weight(1f), numeric=true,dense=true) { w=it.take(5) }
                ImageToolCompactInput("高 px", h, true, Modifier.weight(1f), numeric=true,dense=true) { h=it.take(5) }
            }
            if (valid == null) Text("每边1–4096，总计不超过400万像素", color = androidx.compose.ui.graphics.Color.Red, fontSize = 10.sp)
            ImageToolHelp("按像素设置输出宽高，最近邻缩放，不平滑、不自动压缩。")
        }
    }, confirmButton = { GlyphMakerPrimaryAction("应用", valid != null) { valid?.let { onApply(it.first, it.second) } } },
        dismissButton = { ImageToolCompactAction("取消",true,onClick=onDismiss) })
}

@Composable
private fun ImageWorkbenchThresholdDialog(bitmap: Bitmap, onDismiss: () -> Unit, onApply: (Int) -> Unit,
    onApplyAndSave: (Int) -> Unit) {
    var text by remember { mutableStateOf("128") }
    val threshold = text.toIntOrNull()?.takeIf { it in 0..255 }
    val preview=rememberImageProcessingPreview(bitmap,threshold?.let {ImagePixelOperation("threshold",threshold=it)})
    ImageWorkspaceConfirmPanel(onDismissRequest = onDismiss, prompt = false, title = { Text("二值化") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            ImageProcessingPreviewCanvas(preview.bitmap,96)
            ImageToolCompactInput("阈值 0–255", text, true, Modifier.fillMaxWidth(), numeric=true,dense=true) { text=it.take(3) }
            if (threshold == null) Text("请输入0–255之间的整数，不会自动截断", fontSize = 11.sp, color = androidx.compose.ui.graphics.Color.Red)
            if(preview.message.startsWith("处理无效") || preview.message.startsWith("预览内存不足"))
                ImageWorkspaceHint(preview.message,error=true)
            ImageToolHelp("灰度大于或等于阈值为白，否则为黑；保留透明度和原图。黑白找图需对运行画面应用同样处理。")
        }
    }, confirmButton = {
        ImageToolCompactAction("应用", threshold != null) { threshold?.let(onApply) }
        GlyphMakerPrimaryAction("应用并存图", threshold != null) { threshold?.let(onApplyAndSave) }
    }, dismissButton = { ImageToolCompactAction("取消", true, onClick = onDismiss) })
}

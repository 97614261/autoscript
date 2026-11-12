package com.autoscript.studio

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autoscript.core.designsystem.AutoScriptPalette
import com.autoscript.project.store.ProjectSnapshot
import com.autoscript.project.store.ProjectStore
import com.autoscript.runtime.client.RuntimeClient
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * 图像工具：**全屏画布 + 浮层工具条**。
 *
 * 形态与交互逐条对照参考实现 `资料/erjianwan.apk` 的
 * `assets/web/html/script/图片工具.html`（已抽到 `build/erjianwan-ui/x-3d4f4b39.html`）：
 *
 * - `#container` 是 `100vw × 100vh` 的黑底全屏，图片用 `transform: translate scale` 铺在里面；
 *   工具条、菜单、准星、放大镜都是 `position: fixed` 浮在图上。第一版把它做成了 300×260dp
 *   的小窗嵌一块画布，形态就是错的。
 * - 单击/取色不是"点哪算哪"：手指会挡住目标像素，所以用可独立拖动的十字准星 + 11×11
 *   放大镜取点，提示语「拖动图片定位，点击确定」。
 * - 滑动用起/终两个可拖动标记 + 连线，标记做反向缩放以保持视觉大小。
 * - 选框有四条边手柄，拖动折算成图片整数像素，点一下微调 1px；已有框时禁止另画新框。
 *
 * 视图变换、放大镜取样与定位、菜单定位、选框微调/拖边的算法都在 [ImageToolViewport]，
 * 有单测覆盖；这里只负责渲染与手势分发。
 */
@Composable
internal fun ImageToolWindow(
    bitmap: Bitmap?,
    designWidth: Int,
    designHeight: Int,
    scaleMode: String,
    initialMode: ImageToolMode = ImageToolMode.TAP,
    initialSearchPolygon: String? = null,
    capturing: Boolean,
    message: String?,
    onCapture: () -> Unit,
    onEmit: (ImageToolCodeGen.Snippet) -> Unit,
    onCropToTemplate: (ImageToolCodeGen.Roi) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    pointerInputSupported: Boolean = false,
    snapshot: ProjectSnapshot? = null,
    runtimeClient: RuntimeClient? = null,
    onOpenRecorder: (() -> Unit)? = null,
    projectStore: ProjectStore? = null,
    onWorkbenchSnapshotChanged: ((ProjectSnapshot) -> Unit)? = null,
    insertionTarget: ImageWorkbenchInsertionTarget? = null,
    onWorkbenchTemplateSaved: ((String) -> Unit)? = null,
    workspace: ImageWorkspaceActions? = null,
    touchTools: Boolean = false,
) {
    if (!touchTools && workspace == null && bitmap != null && snapshot != null && runtimeClient != null && projectStore != null && onWorkbenchSnapshotChanged != null) {
        if (capturing) BackHandler(onBack = onClose)
        if (!capturing) ImageProcessingWorkbench(bitmap, snapshot, projectStore, runtimeClient, onWorkbenchSnapshotChanged,
            onDismiss = onClose, insertionTarget = insertionTarget, capturedScreen = true,
            capture = ImageWorkspaceCapture(designWidth, designHeight, scaleMode, initialMode, initialSearchPolygon,
                pointerInputSupported, onCapture, onEmit, onOpenRecorder, onCropToTemplate, message, onWorkbenchTemplateSaved), modifier = modifier)
        return
    }
    BackHandler(onBack = onClose)
    // 参考 html{background:#000} 的全屏黑底只在有图时铺——它是给图片当衬底的，不是遮罩。
    // 没图时保持透明，让工作台透出来；截图期间更不能有任何不透明的东西，否则截到的就是自己的黑底。
    // （第一版一打开就铺黑，用户点「截图」截到的是一片黑。）
    val showBackdrop = bitmap != null && !capturing
    BoxWithConstraints(modifier.fillMaxSize().background(if (showBackdrop) Color.Black else Color.Transparent)) {
        val density = LocalDensity.current
        val viewWidth = constraints.maxWidth.toFloat()
        val viewHeight = constraints.maxHeight.toFloat()

        val modeState: MutableState<ImageToolMode> = workspace?.surface?.mode ?: rememberSaveable(initialMode) { mutableStateOf(initialMode) }
        var mode by modeState
        var localMenu by remember { mutableStateOf<ImageToolbarMenu?>(null) }
        val toolbarMenu = if(workspace!=null) workspace.surface.toolbarMenu else localMenu
        fun setMenu(next: ImageToolbarMenu?) { if(workspace!=null) workspace.surface.toolbarMenu=next else localMenu=next }
        fun toggleMenu(next: ImageToolbarMenu) { setMenu(if(toolbarMenu==next) null else next) }
        var zoomMode by rememberSaveable { mutableStateOf(false) }
        val selectionState: MutableState<ImageToolSelection> = workspace?.surface?.selection ?: remember(bitmap) { mutableStateOf(runCatching {
            val points=ImageSearchPolygon.decode(initialSearchPolygon.orEmpty())
            if(points.isEmpty()) ImageToolSelection() else ImageToolSelection(box=ImageSearchPolygon.bounds(points),polygon=points)
        }.getOrDefault(ImageToolSelection())) }
        var selection by selectionState
        val selectionProblem=remember(bitmap,selection.box,selection.polygon) {
            if(bitmap==null) "请先截图" else imageSelectionProblem(selection,bitmap.width,bitmap.height)
        }
        val saveTarget = remember(bitmap, selection.box, selection.polygon) {
            bitmap?.let { imageSaveTarget(selection, it.width, it.height) }
        }
        var localPolygonEditing by remember(bitmap) { mutableStateOf(false) }
        val polygonEditing = workspace?.surface?.polygonEditing ?: localPolygonEditing
        fun setPolygonEditing(next: Boolean) { if(workspace!=null) workspace.surface.polygonEditing=next else localPolygonEditing=next }
        val polygonHistory = remember(bitmap) { ImagePolygonHistory(selection.polygon) }
        var selectedVertex by remember(bitmap) { mutableStateOf(-1) }
        var polygonGesture by remember(bitmap) { mutableStateOf("edit") }
        LaunchedEffect(selection.polygon) { polygonHistory.synchronize(selection.polygon) }
        fun publishPolygon() { selection=polygonToolSelection(polygonHistory.points,selection);selectedVertex=selectedVertex.coerceAtMost(polygonHistory.points.lastIndex) }
        var blocked by remember { mutableStateOf<String?>(null) }
        var hint by remember { mutableStateOf<String?>(null) }
        var swipeDuration by rememberSaveable { mutableFloatStateOf(300f) }
        var gestureDuration by rememberSaveable { mutableFloatStateOf(800f) }
        var tolerance by rememberSaveable { mutableFloatStateOf(8f) }
        var ocrRegion by remember(bitmap, snapshot?.manifest?.projectId) { mutableStateOf<ImageToolCodeGen.Roi?>(null) }
        var pixelAdjustOpen by remember(bitmap, mode) { mutableStateOf(false) }
        var glyphUse by remember(bitmap, snapshot?.manifest?.projectId) { mutableStateOf<Triple<ProjectSnapshot, ImageToolOcrConfig, ImageToolCodeGen.Roi?>?>(null) }

        val context = LocalContext.current
        val toolbarPreferences = remember(context) { context.getSharedPreferences("image-toolbar", android.content.Context.MODE_PRIVATE) }
        val positionKey = if (viewWidth > viewHeight) "landscape" else "portrait"
        var toolbarX by remember(positionKey) { mutableFloatStateOf(
            toolbarPreferences.getFloat("$positionKey-x", Float.NaN).takeIf { it.isFinite() }?.let { it.coerceIn(0f, 1f) * viewWidth } ?: Float.NaN) }
        var toolbarY by remember(positionKey) { mutableFloatStateOf(
            toolbarPreferences.getFloat("$positionKey-y", Float.NaN).takeIf { it.isFinite() }?.let { it.coerceIn(0f, 1f) * viewHeight } ?: Float.NaN) }
        // 工具条会因模式增加“缩放 / 容差 / 时长”等按钮，不能假定恒定宽度。
        // 首帧先用最大的保守值，完成测量后再按真实宽度计算可拖动边界。
        var toolbarMeasuredWidthPx by remember { mutableFloatStateOf(0f) }
        var toolbarMeasuredHeightPx by remember { mutableFloatStateOf(0f) }
        val viewportState: MutableState<ImageToolViewport?> = workspace?.surface?.viewport ?: remember(bitmap) {
            mutableStateOf(
                bitmap?.let { ImageToolViewport.fit(it.width, it.height, viewWidth, viewHeight) },
            )
        }
        var viewport by viewportState
        // 准星初始在屏幕中心，对应参考 crosshairX = window.innerWidth / 2。
        var crosshair by remember(bitmap) { mutableStateOf(Offset(viewWidth / 2f, viewHeight / 2f)) }
        // Direct entry and recapture need both markers before any mode-switch gesture.
        val defaultSlide = remember(bitmap) { bitmap?.let { imageToolSlideDefaults(it.width, it.height) } }
        var slideStart by remember(bitmap) { mutableStateOf(defaultSlide?.first) }
        var slideEnd by remember(bitmap) { mutableStateOf(defaultSlide?.second) }

        val borrowedCanvas = workspace?.surface?.canvas != null
        LaunchedEffect(workspace?.panelOpen) {
            if (workspace?.panelOpen == true) { setMenu(null); zoomMode = false }
        }
        LaunchedEffect(bitmap, viewWidth, viewHeight, borrowedCanvas) {
            if (!borrowedCanvas && bitmap != null && viewWidth > 0f && viewHeight > 0f) {
                val old = viewport
                if (old == null || old.imageWidth != bitmap.width || old.imageHeight != bitmap.height) {
                    viewport = ImageToolViewport.fit(bitmap.width, bitmap.height, viewWidth, viewHeight)
                    crosshair = Offset(viewWidth / 2f, viewHeight / 2f)
                } else if (old.viewWidth != viewWidth || old.viewHeight != viewHeight) {
                    val point = old.toImage(crosshair.x, crosshair.y)
                    val resized = old.resized(viewWidth, viewHeight)
                    viewport = resized
                    crosshair = point?.let { resized.toView(it.first, it.second) }?.let {
                        Offset(it.first.coerceIn(0f, viewWidth), it.second.coerceIn(0f, viewHeight))
                    } ?: Offset(viewWidth / 2f, viewHeight / 2f)
                }
            }
        }

        val toolbarWidthPx = toolbarMeasuredWidthPx.takeIf { it > 0f }
            ?: minOf(viewWidth, with(density) { TOOLBAR_FALLBACK_WIDTH.toPx() })
        val toolbarHeightPx = toolbarMeasuredHeightPx.takeIf {it>0f} ?: with(density) { TOOLBAR_HEIGHT.toPx() }
        // 参考初始化把工具条放在水平居中、高度 1/3 处。
        val resolvedToolbar = clampToolbar(
            x = if (toolbarX.isNaN()) (viewWidth - toolbarWidthPx) / 2f else toolbarX,
            y = if (toolbarY.isNaN()) viewHeight / 3f else toolbarY,
            width = toolbarWidthPx,
            height = toolbarHeightPx,
            viewWidth = viewWidth,
            viewHeight = viewHeight,
        )

        val mapping = remember(bitmap, designWidth, designHeight, scaleMode) {
            bitmap?.let { ImageToolCodeGen.DesignMapping(workspace?.sourceWidth ?: it.width, workspace?.sourceHeight ?: it.height, designWidth, designHeight, scaleMode) }
        }

        /** 切模式：参考 switchActionButtons() —— 强制退出缩放、清掉别的模式的叠加层、弹提示横幅。 */
        fun switchMode(next: ImageToolMode) {
            if (next == ImageToolMode.RECORD && onOpenRecorder != null) {
                setMenu(null)
                onOpenRecorder()
                return
            }
            if (next.needsPointerInput && !pointerInputSupported) {
                blocked = "当前后端不支持持续触点（按下/移动/弹起）"
                return
            }
            if (!next.enabled) {
                blocked = next.blockedReason
                return
            }
            blocked = null
            zoomMode = false
            mode = next
            selection = selection.clearedFor(next)
            setPolygonEditing(false)
            setMenu(null)
            crosshair = Offset(viewWidth / 2f, viewHeight / 2f)
            hint = "${next.label}模式：${next.hint}"
        }

        // 提示横幅几秒后自动消失，对应参考的 showHint()/hideHint()。
        LaunchedEffect(hint) {
            if (hint != null) {
                delay(2_600)
                hint = null
            }
        }

        // 截图进行中什么都不画：对应参考在截图前先 hideFloating() 把自己藏起来。
        // 状态声明都在上面，这里返回不会丢掉模式/工具条位置。
        if (capturing) return@BoxWithConstraints

        if (workspace?.surface?.canvas != null) workspace.surface.canvas?.invoke()
        else if(polygonEditing && bitmap!=null) ImagePolygonCanvas(bitmap,viewport,selection.polygon,selectedVertex,
            pan=zoomMode || polygonGesture=="pan",moveShape=polygonGesture=="move",
            onViewport={viewport=it},onSelected={selectedVertex=it},onPublish={points ->
                if(polygonHistory.publish(points)) publishPolygon() else hint="顶点最多128个，不能重合"
            })
        else ImageToolCanvas(
            bitmap = bitmap,
            viewport = viewport,
            mode = mode,
            zoomMode = zoomMode,
            capturing = capturing,
            selection = selection,
            crosshair = crosshair,
            slideStart = slideStart,
            slideEnd = slideEnd,
            onViewport = { viewport = it },
            onSelection = { next -> selection = if(workspace==null) next else next.copy(points=next.points.map {p -> p.copy(rgb=workspace.sampleColor(p.x,p.y))}) },
            onCrosshair = { crosshair = it },
            onSlide = { start, end -> slideStart = start; slideEnd = end },
        )

        if (workspace?.surface?.canvas == null) ImageToolStatusStrip(
            mode = mode,
            zoomMode = zoomMode,
            viewport = viewport,
            selection = selection,
            crosshair = crosshair,
            polygonEditing = polygonEditing,
            polygonProblem = selectionProblem,
        )

        hint?.let { ImageToolHintBanner(it) }

        ImageToolBar(
            mode = mode,
            zoomMode = zoomMode,
            capturing = capturing,
            hasImage = bitmap != null,
            ready = workspace?.busy != true && workspace?.panelOpen != true &&
                (!(mode.usesDragBox || polygonEditing || selection.polygon.isNotEmpty()) || selectionProblem==null) &&
                isReady(mode, selection, slideStart, slideEnd, mapping),
            saveReady = bitmap!=null && workspace?.busy != true && workspace?.panelOpen != true &&
                saveTarget!=null,
            selection = selection,
            swipeDuration = swipeDuration.roundToInt(),
            gestureDuration = gestureDuration.roundToInt(),
            tolerance = tolerance.roundToInt(),
            offsetX = resolvedToolbar.first,
            offsetY = resolvedToolbar.second,
            onDrag = { delta ->
                val next = clampToolbar(
                    x = (if (toolbarX.isNaN()) resolvedToolbar.first else toolbarX) + delta.x,
                    y = (if (toolbarY.isNaN()) resolvedToolbar.second else toolbarY) + delta.y,
                    width = toolbarWidthPx,
                    height = toolbarHeightPx,
                    viewWidth = viewWidth,
                    viewHeight = viewHeight,
                )
                toolbarX = next.first
                toolbarY = next.second
            },
            onDragEnd = {
                if (viewWidth > 0 && viewHeight > 0 && toolbarX.isFinite() && toolbarY.isFinite())
                    toolbarPreferences.edit().putFloat("$positionKey-x", toolbarX / viewWidth)
                        .putFloat("$positionKey-y", toolbarY / viewHeight).apply()
            },
            onToggleMenu = { if (workspace?.panelOpen != true) toggleMenu(ImageToolbarMenu.MODE) },
            onZoom = { toggleMenu(ImageToolbarMenu.VIEW) },
            onCapture = onCapture,
            workspace = workspace,
            touchTools = touchTools,
            onClear = {
                if(workspace?.panelOpen==true) workspace.surface.clearDraft?.invoke()
                else {
                    if(polygonEditing) polygonHistory.publish(emptyList())
                    selection = ImageToolSelection(); selectedVertex=-1
                }
                slideStart = defaultSlide?.first
                slideEnd = defaultSlide?.second
                if (touchTools) crosshair = Offset(viewWidth / 2f, viewHeight / 2f)
                zoomMode = false
                blocked = null
            },
            onSwipeDuration = { swipeDuration = it },
            onGestureDuration = { gestureDuration = it },
            onTolerance = { tolerance = it },
            onConfirm = confirm@{
                val map = mapping
                if (mode.needsPointerInput && !pointerInputSupported) {
                    blocked = "当前后端不支持持续触点（按下/移动/弹起）"
                } else if (map == null) {
                    blocked = "请先截图"
                } else if (mode == ImageToolMode.OCR) {
                    if (workspace != null) workspace.onOcr()
                    else if (snapshot == null || runtimeClient == null) blocked = "取字需要已打开的项目与运行服务会话"
                    else { glyphUse = null; ocrRegion = selection.box }
                } else {
                    val outputSelection=try { workspace?.mapSelection?.invoke(selection) ?: selection }
                        catch (_: Exception) { blocked="处理后的选区不能还原到原图，请调整选区后重试";return@confirm }
                    val snippet = buildSnippet(
                        mode = mode,
                        selection = outputSelection,
                        crosshair = crosshair,
                        viewport = viewport,
                        bitmap = bitmap,
                        slideStart = slideStart,
                        slideEnd = slideEnd,
                        swipeDuration = swipeDuration.roundToInt(),
                        gestureDuration = gestureDuration.roundToInt(),
                        tolerance = tolerance.roundToInt(),
                        mapping = map,
                        mapPoint = workspace?.onMapPoint ?: { x, y -> x to y },
                        onError = { blocked = it },
                    )
                    if (snippet != null) {
                        if (snippet.rejection != null) blocked = snippet.rejection else onEmit(
                            if (mode in setOf(ImageToolMode.REGION, ImageToolMode.COLOR, ImageToolMode.MULTI_COLOR)) {
                                snippet.copy(imageSelection = ImageToolCodeGen.VisualSelection(
                                    mode = mode,
                                    roi = outputSelection.box,
                                    polygon = outputSelection.polygon,
                                    points = outputSelection.points.toList(),
                                    tolerance = tolerance.roundToInt(),
                                    frameWidth = workspace?.sourceWidth ?: bitmap?.width ?: 0,
                                    frameHeight = workspace?.sourceHeight ?: bitmap?.height ?: 0,
                                ))
                            } else snippet,
                        )
                    }
                }
            },
            onCropToTemplate = { saveTarget?.region?.let(onCropToTemplate) },
            onClose = onClose,
            onAdjust = if (bitmap != null && (mode.usesDragBox || mode.usesCrosshair || mode.usesPressPick || mode.usesSlideMarkers))
                ({ setMenu(null); pixelAdjustOpen = true }) else null,
            onSelectionShape = workspace?.onSelection ?: { toggleMenu(ImageToolbarMenu.SELECTION) },
            menu = toolbarMenu,
            secondaryContent = {
                toolbarMenu?.let { menu -> ImageToolSecondaryBar(menu,mode,polygonEditing,polygonHistory,selectedVertex,polygonGesture,
                    workspace,onOpenRecorder!=null,zoomMode,onMode={switchMode(it)},onShape={polygon ->
                        zoomMode=false;setPolygonEditing(polygon);polygonGesture="edit";selectedVertex=-1
                        if(mode !in setOf(ImageToolMode.CROP,ImageToolMode.REGION,ImageToolMode.COLOR,ImageToolMode.MULTI_COLOR)) mode=ImageToolMode.REGION
                        selection=if(polygon) polygonToolSelection(selection.polygon,selection) else selection.copy(polygon=emptyList())
                    },onPolygonGesture={polygonGesture=it;zoomMode=false},onPublishPolygon={publishPolygon()},
                    onDeleteVertex={
                        if(selectedVertex in polygonHistory.points.indices) {
                            polygonHistory.publish(polygonHistory.points.filterIndexed {i,_->i!=selectedVertex});publishPolygon();selectedVertex=-1
                        }
                    },onView={ action ->
                        val v=viewport
                        when(action) {
                            "pan" -> zoomMode=!zoomMode
                            "fit" -> viewport=v?.reset()
                            "actual" -> viewport=v?.actualSize()
                            "in", "out" -> if(v!=null) viewport=v.scaledBy(if(action=="in") 2f else .5f,v.viewWidth/2,v.viewHeight/2)
                        }
                    },onClose={setMenu(null)},touchTools=touchTools) }
            },
            modifier = Modifier.widthIn(max = (maxWidth - 8.dp).coerceAtLeast(1.dp))
                .heightIn(max = (maxHeight - 8.dp).coerceAtLeast(1.dp)).onSizeChanged { measured ->
                toolbarMeasuredWidthPx = measured.width.toFloat()
                toolbarMeasuredHeightPx = measured.height.toFloat()
            },
        )

        if (pixelAdjustOpen && bitmap != null) {
            val current = viewport?.toImage(crosshair.x, crosshair.y) ?: (bitmap.width / 2 to bitmap.height / 2)
            val pixels = when {
                mode.usesSlideMarkers -> listOf(slideStart ?: (bitmap.width / 4 to bitmap.height / 2),
                    slideEnd ?: (bitmap.width * 3 / 4 to bitmap.height / 2))
                mode.usesPressPick -> selection.points.map { it.x to it.y }
                else -> listOf(current)
            }
            ImageToolPixelAdjustDialog(mode, bitmap.width, bitmap.height, selection.box, pixels,
                onDismiss = { pixelAdjustOpen = false }, onApply = { rectangle, points ->
                    when {
                        mode.usesDragBox -> selection = selection.copy(box = rectangle,polygon=emptyList())
                        mode.usesSlideMarkers -> { slideStart = points[0]; slideEnd = points[1] }
                        mode.usesPressPick -> selection = selection.copy(points = points.map { (x, y) ->
                            ImageToolCodeGen.PickedPoint(x, y, workspace?.sampleColor?.invoke(x, y) ?: (bitmap.getPixel(x, y) and 0xFFFFFF))
                        })
                        else -> points.firstOrNull()?.let { (x, y) -> viewport?.toView(x, y)?.let { crosshair = Offset(it.first, it.second) } }
                    }
                    blocked = null
                    pixelAdjustOpen = false
                })
        }

        val ocrSource = bitmap
        ocrRegion?.let { roi ->
            if (ocrSource != null && snapshot != null && runtimeClient != null) ImageToolOcrDialog(
                bitmap = ocrSource, initialRoi = roi, snapshot = glyphUse?.first ?: snapshot, runtimeClient = runtimeClient,
                initialConfig = glyphUse?.second,
                resultReadTarget = insertionTarget,
                onDismiss = { ocrRegion = null }, onConfirm = { config, region ->
                    val snippet = config.snippet(region, ocrSource.width, ocrSource.height)
                    if (snippet.rejection != null) blocked = snippet.rejection else { ocrRegion = null; onEmit(snippet) }
                },
            )
        }

        val notice = blocked ?: message
        StudioSystemLogEffect("图像工具", if (workspace == null) notice else blocked)
        if (notice != null) {
            Text(
                notice,
                color = if (blocked != null) BlockedText else Color(0xFFD5DCE8),
                fontSize = 11.sp,
                lineHeight = 15.sp,
                maxLines = 2,
                textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Color(0xCC11151D))
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }
    }
}

/** 各模式的"可以产出了吗"。滑动看标记而不是 points——它用的是独立的起终点。 */
private fun isReady(
    mode: ImageToolMode,
    selection: ImageToolSelection,
    slideStart: Pair<Int, Int>?,
    slideEnd: Pair<Int, Int>?,
    mapping: ImageToolCodeGen.DesignMapping?,
): Boolean = mapping != null && when (mode) {
    ImageToolMode.CROP, ImageToolMode.REGION, ImageToolMode.OCR -> selection.box != null
    // 单击随时可确定（取的是准星中心）；取色/多点要先按住抬手取到点。
    ImageToolMode.TAP, ImageToolMode.LONG_PRESS, ImageToolMode.POINTER_DOWN, ImageToolMode.POINTER_MOVE, ImageToolMode.POINTER_UP -> true
    ImageToolMode.COLOR -> selection.points.isNotEmpty()
    ImageToolMode.MULTI_COLOR -> selection.points.size >= 2
    ImageToolMode.SWIPE, ImageToolMode.DRAG -> slideStart != null && slideEnd != null
    else -> false
}

/** 准星中心对应的图片像素与颜色。 */
private fun crosshairPoint(
    crosshair: Offset,
    viewport: ImageToolViewport?,
    bitmap: Bitmap?,
): ImageToolCodeGen.PickedPoint? {
    if (viewport == null || bitmap == null) return null
    val (x, y) = viewport.toImage(crosshair.x, crosshair.y) ?: return null
    return ImageToolCodeGen.PickedPoint(x, y, bitmap.getPixel(x, y) and 0xFFFFFF)
}

private fun buildSnippet(
    mode: ImageToolMode,
    selection: ImageToolSelection,
    crosshair: Offset,
    viewport: ImageToolViewport?,
    bitmap: Bitmap?,
    slideStart: Pair<Int, Int>?,
    slideEnd: Pair<Int, Int>?,
    swipeDuration: Int,
    gestureDuration: Int,
    tolerance: Int,
    mapping: ImageToolCodeGen.DesignMapping,
    mapPoint: (Int, Int) -> Pair<Int, Int> = { x, y -> x to y },
    onError: (String) -> Unit,
): ImageToolCodeGen.Snippet? = runCatching {
    val inputMapping = ImageToolCodeGen.DesignMapping(mapping.captureWidth, mapping.captureHeight, mapping.captureWidth, mapping.captureHeight)
    fun mapped(p: ImageToolCodeGen.PickedPoint): ImageToolCodeGen.PickedPoint {
        val xy = mapPoint(p.x,p.y); return p.copy(x=xy.first,y=xy.second)
    }
    fun crosshairPixel() = mapped(crosshairPoint(crosshair,viewport,bitmap) ?: error("请先截图"))
    val snippet = when (mode) {
        ImageToolMode.REGION -> ImageToolCodeGen.region(selection.box!!, mapping, selection.polygon)
        ImageToolMode.TAP -> ImageToolCodeGen.tap(
            crosshairPixel(),
            inputMapping,
        )
        ImageToolMode.LONG_PRESS -> ImageToolCodeGen.longPress(
            crosshairPixel(),
            gestureDuration,
            inputMapping,
        )
        ImageToolMode.POINTER_DOWN -> ImageToolCodeGen.pointerDown(crosshairPixel(), inputMapping)
        ImageToolMode.POINTER_MOVE -> ImageToolCodeGen.pointerMove(crosshairPixel(), inputMapping)
        ImageToolMode.POINTER_UP -> ImageToolCodeGen.pointerUp()
        // 取色用的是抬手时取到的点，不是准星——参考 colorpicker 没有常驻准星。
        ImageToolMode.COLOR -> ImageToolCodeGen.getColor(selection.points.first(), mapping)
        ImageToolMode.MULTI_COLOR -> ImageToolCodeGen.findMultiColor(
            anchor = selection.points.first(),
            samples = selection.points.drop(1),
            tolerance = tolerance,
            roi = selection.box,
            mapping = mapping,
            polygon = selection.polygon,
        )
        ImageToolMode.SWIPE -> {
            val start = slideStart ?: error("请先设置滑动起点")
            val end = slideEnd ?: error("请先设置滑动终点")
            val pixel = { p: Pair<Int, Int> ->
                mapped(ImageToolCodeGen.PickedPoint(p.first, p.second, bitmap?.getPixel(p.first, p.second)?.and(0xFFFFFF) ?: 0))
            }
            ImageToolCodeGen.swipe(pixel(start), pixel(end), swipeDuration, inputMapping)
        }
        ImageToolMode.DRAG -> {
            val start = slideStart ?: error("请先设置拖动起点")
            val end = slideEnd ?: error("请先设置拖动终点")
            val pixel = { p: Pair<Int, Int> ->
                mapped(ImageToolCodeGen.PickedPoint(p.first, p.second, bitmap?.getPixel(p.first, p.second)?.and(0xFFFFFF) ?: 0))
            }
            ImageToolCodeGen.drag(pixel(start), pixel(end), gestureDuration, inputMapping)
        }
        else -> null
    }
    if (snippet != null && mode in setOf(ImageToolMode.TAP, ImageToolMode.SWIPE, ImageToolMode.LONG_PRESS,
            ImageToolMode.DRAG, ImageToolMode.POINTER_DOWN, ImageToolMode.POINTER_MOVE, ImageToolMode.POINTER_UP))
        ImageToolCodeGen.screenInput(snippet) else snippet
}.getOrElse { error ->
    onError(error.message ?: "生成失败")
    null
}

private val TOOLBAR_FALLBACK_WIDTH = 304.dp
private val TOOLBAR_HEIGHT = 34.dp

internal val ToolChrome = Color(0xE61E2531)
internal val BoxStroke = Color(0xFFFF4D4F)
internal val BlockedText = Color(0xFFFF7875)
internal val ButtonBlue = AutoScriptPalette.Accent
internal val ButtonGreen = Color(0xFF1FA971)
internal val ButtonGrey = Color(0xFF3A4252)

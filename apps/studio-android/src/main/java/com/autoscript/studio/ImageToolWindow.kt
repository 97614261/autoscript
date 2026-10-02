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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autoscript.core.designsystem.AutoScriptPalette
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
    capturing: Boolean,
    message: String?,
    onCapture: () -> Unit,
    onEmit: (ImageToolCodeGen.Snippet) -> Unit,
    onCropToTemplate: (ImageToolCodeGen.Roi) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    pointerInputSupported: Boolean = false,
) {
    BackHandler(onBack = onClose)
    // 参考 html{background:#000} 的全屏黑底只在有图时铺——它是给图片当衬底的，不是遮罩。
    // 没图时保持透明，让工作台透出来；截图期间更不能有任何不透明的东西，否则截到的就是自己的黑底。
    // （第一版一打开就铺黑，用户点「截图」截到的是一片黑。）
    val showBackdrop = bitmap != null && !capturing
    BoxWithConstraints(modifier.fillMaxSize().background(if (showBackdrop) Color.Black else Color.Transparent)) {
        val density = LocalDensity.current
        val viewWidth = constraints.maxWidth.toFloat()
        val viewHeight = constraints.maxHeight.toFloat()

        var mode by rememberSaveable(initialMode) { mutableStateOf(initialMode) }
        var menuOpen by rememberSaveable { mutableStateOf(false) }
        var zoomMode by rememberSaveable { mutableStateOf(false) }
        var selection by remember { mutableStateOf(ImageToolSelection()) }
        var blocked by remember { mutableStateOf<String?>(null) }
        var hint by remember { mutableStateOf<String?>(null) }
        var swipeDuration by rememberSaveable { mutableFloatStateOf(300f) }
        var gestureDuration by rememberSaveable { mutableFloatStateOf(800f) }
        var tolerance by rememberSaveable { mutableFloatStateOf(8f) }

        var toolbarX by rememberSaveable { mutableFloatStateOf(Float.NaN) }
        var toolbarY by rememberSaveable { mutableFloatStateOf(Float.NaN) }
        // 工具条会因模式增加“缩放 / 容差 / 时长”等按钮，不能假定恒定宽度。
        // 首帧先用最大的保守值，完成测量后再按真实宽度计算可拖动边界。
        var toolbarMeasuredWidthPx by remember { mutableFloatStateOf(0f) }
        var viewport by remember(bitmap) {
            mutableStateOf(
                bitmap?.let { ImageToolViewport.fit(it.width, it.height, viewWidth, viewHeight) },
            )
        }
        // 准星初始在屏幕中心，对应参考 crosshairX = window.innerWidth / 2。
        var crosshair by remember(bitmap) { mutableStateOf(Offset(viewWidth / 2f, viewHeight / 2f)) }
        var slideStart by remember(bitmap) { mutableStateOf<Pair<Int, Int>?>(null) }
        var slideEnd by remember(bitmap) { mutableStateOf<Pair<Int, Int>?>(null) }

        val toolbarWidthPx = toolbarMeasuredWidthPx.takeIf { it > 0f }
            ?: with(density) { TOOLBAR_FALLBACK_WIDTH.toPx() }
        val toolbarHeightPx = with(density) { TOOLBAR_HEIGHT.toPx() }
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
            bitmap?.let { ImageToolCodeGen.DesignMapping(it.width, it.height, designWidth, designHeight, scaleMode) }
        }

        /** 切模式：参考 switchActionButtons() —— 强制退出缩放、清掉别的模式的叠加层、弹提示横幅。 */
        fun switchMode(next: ImageToolMode) {
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
            menuOpen = false
            crosshair = Offset(viewWidth / 2f, viewHeight / 2f)
            if (next.usesSlideMarkers && slideStart == null && bitmap != null) {
                // 参考的默认位置：图宽 25% / 75%，高 50%。
                slideStart = (bitmap.width * 0.25f).toInt() to (bitmap.height * 0.5f).toInt()
                slideEnd = (bitmap.width * 0.75f).toInt() to (bitmap.height * 0.5f).toInt()
            }
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

        ImageToolCanvas(
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
            onSelection = { selection = it },
            onCrosshair = { crosshair = it },
            onSlide = { start, end -> slideStart = start; slideEnd = end },
        )

        ImageToolStatusStrip(
            mode = mode,
            zoomMode = zoomMode,
            viewport = viewport,
            selection = selection,
            crosshair = crosshair,
            mapping = mapping,
        )

        hint?.let { ImageToolHintBanner(it) }

        // 缩放模式的控制条，对应参考 #controls 的「退出缩放 / 原图」。
        if (zoomMode && viewport != null) {
            ImageToolZoomControls(
                scale = viewport!!.scale,
                onReset = { viewport = viewport?.reset() },
                onExit = { zoomMode = false },
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 12.dp),
            )
        }

        ImageToolBar(
            mode = mode,
            zoomMode = zoomMode,
            capturing = capturing,
            hasImage = bitmap != null,
            ready = isReady(mode, selection, slideStart, slideEnd, mapping),
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
            onToggleMenu = { menuOpen = !menuOpen },
            onZoom = { zoomMode = true },
            onCapture = onCapture,
            onClear = {
                selection = ImageToolSelection()
                slideStart = null
                slideEnd = null
                blocked = null
            },
            onSwipeDuration = { swipeDuration = it },
            onGestureDuration = { gestureDuration = it },
            onTolerance = { tolerance = it },
            onConfirm = {
                val map = mapping
                if (mode.needsPointerInput && !pointerInputSupported) {
                    blocked = "当前后端不支持持续触点（按下/移动/弹起）"
                } else if (map == null) {
                    blocked = "请先截图"
                } else {
                    val snippet = buildSnippet(
                        mode = mode,
                        selection = selection,
                        crosshair = crosshair,
                        viewport = viewport,
                        bitmap = bitmap,
                        slideStart = slideStart,
                        slideEnd = slideEnd,
                        swipeDuration = swipeDuration.roundToInt(),
                        gestureDuration = gestureDuration.roundToInt(),
                        tolerance = tolerance.roundToInt(),
                        mapping = map,
                        onError = { blocked = it },
                    )
                    if (snippet != null) {
                        if (snippet.rejection != null) blocked = snippet.rejection else onEmit(
                            if (mode in setOf(ImageToolMode.REGION, ImageToolMode.COLOR, ImageToolMode.MULTI_COLOR)) {
                                snippet.copy(imageSelection = ImageToolCodeGen.VisualSelection(
                                    mode = mode,
                                    roi = selection.box,
                                    points = selection.points.toList(),
                                    tolerance = tolerance.roundToInt(),
                                    frameWidth = bitmap?.width ?: 0,
                                    frameHeight = bitmap?.height ?: 0,
                                ))
                            } else snippet,
                        )
                    }
                }
            },
            onCropToTemplate = { selection.box?.let(onCropToTemplate) },
            onClose = onClose,
            modifier = Modifier.onSizeChanged { measured ->
                toolbarMeasuredWidthPx = measured.width.toFloat()
            },
        )

        if (menuOpen) {
            val menuWidth = with(density) { MENU_WIDTH.toPx() }
            val menuHeight = minOf(with(density) { MENU_HEIGHT.toPx() }, (viewHeight - with(density) { 12.dp.toPx() }).coerceAtLeast(1f))
            val placement = menuPlacement(
                toolbarLeft = resolvedToolbar.first,
                toolbarTop = resolvedToolbar.second,
                toolbarHeight = toolbarHeightPx,
                menuWidth = menuWidth,
                menuHeight = menuHeight,
                viewWidth = viewWidth,
                viewHeight = viewHeight,
            )
            ImageToolMenu(
                current = mode,
                onPick = ::switchMode,
                modifier = Modifier.heightIn(max = with(density) { menuHeight.toDp() }).offset {
                    IntOffset(placement.first.roundToInt(), placement.second.roundToInt())
                },
            )
        }

        val notice = blocked ?: message
        if (notice != null) {
            Text(
                notice,
                color = if (blocked != null) BlockedText else Color(0xFFD5DCE8),
                fontSize = 11.sp,
                lineHeight = 15.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Color(0xCC11151D))
                    .padding(horizontal = 14.dp, vertical = 9.dp),
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
    ImageToolMode.CROP, ImageToolMode.REGION -> selection.box != null
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
    onError: (String) -> Unit,
): ImageToolCodeGen.Snippet? = runCatching {
    when (mode) {
        ImageToolMode.REGION -> ImageToolCodeGen.region(selection.box!!, mapping)
        ImageToolMode.TAP -> ImageToolCodeGen.tap(
            crosshairPoint(crosshair, viewport, bitmap) ?: error("请先截图"),
            mapping,
        )
        ImageToolMode.LONG_PRESS -> ImageToolCodeGen.longPress(
            crosshairPoint(crosshair, viewport, bitmap) ?: error("请先截图"),
            gestureDuration,
            mapping,
        )
        ImageToolMode.POINTER_DOWN -> ImageToolCodeGen.pointerDown(crosshairPoint(crosshair, viewport, bitmap) ?: error("请先截图"), mapping)
        ImageToolMode.POINTER_MOVE -> ImageToolCodeGen.pointerMove(crosshairPoint(crosshair, viewport, bitmap) ?: error("请先截图"), mapping)
        ImageToolMode.POINTER_UP -> ImageToolCodeGen.pointerUp()
        // 取色用的是抬手时取到的点，不是准星——参考 colorpicker 没有常驻准星。
        ImageToolMode.COLOR -> ImageToolCodeGen.getColor(selection.points.first(), mapping)
        ImageToolMode.MULTI_COLOR -> ImageToolCodeGen.findMultiColor(
            anchor = selection.points.first(),
            samples = selection.points.drop(1),
            tolerance = tolerance,
            roi = selection.box,
            mapping = mapping,
        )
        ImageToolMode.SWIPE -> {
            val start = slideStart ?: error("请先设置滑动起点")
            val end = slideEnd ?: error("请先设置滑动终点")
            val pixel = { p: Pair<Int, Int> ->
                ImageToolCodeGen.PickedPoint(p.first, p.second, bitmap?.getPixel(p.first, p.second)?.and(0xFFFFFF) ?: 0)
            }
            ImageToolCodeGen.swipe(pixel(start), pixel(end), swipeDuration, mapping)
        }
        ImageToolMode.DRAG -> {
            val start = slideStart ?: error("请先设置拖动起点")
            val end = slideEnd ?: error("请先设置拖动终点")
            val pixel = { p: Pair<Int, Int> ->
                ImageToolCodeGen.PickedPoint(p.first, p.second, bitmap?.getPixel(p.first, p.second)?.and(0xFFFFFF) ?: 0)
            }
            ImageToolCodeGen.drag(pixel(start), pixel(end), gestureDuration, mapping)
        }
        else -> null
    }
}.getOrElse { error ->
    onError(error.message ?: "生成失败")
    null
}

private val TOOLBAR_FALLBACK_WIDTH = 304.dp
private val TOOLBAR_HEIGHT = 44.dp
private val MENU_WIDTH = 124.dp
// Follow the actual two-column menu instead of hiding the last tools on short displays.
private val MENU_HEIGHT = (((ImageToolMode.menuOrder.size + 1) / 2) * 60 + 8).dp
private val MENU_ITEM = 56.dp

internal val ToolChrome = Color(0xE61E2531)
internal val BoxStroke = Color(0xFFFF4D4F)
internal val BlockedText = Color(0xFFFF7875)
internal val ButtonBlue = AutoScriptPalette.Accent
internal val ButtonGreen = Color(0xFF1FA971)
internal val ButtonGrey = Color(0xFF3A4252)

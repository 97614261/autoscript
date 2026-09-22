package com.autoscript.studio

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 全屏画布：按 [ImageToolViewport] 把截图画上去，并按模式分发手势。
 *
 * 手势分发对照参考 `x-3d4f4b39.html` 的 `handleStart/handleMove`：
 * - 缩放模式：单指平移、双指捏合（以两指中点为锚）
 * - 拖框模式：拖出选框；**已有框时不许另画新框**（参考 `handleSelectionStart` 的早返回）
 * - 准星模式：拖准星定位，图片本身也可拖
 * - 滑动模式：拖起/终标记
 */
@Composable
internal fun ImageToolCanvas(
    bitmap: Bitmap?,
    viewport: ImageToolViewport?,
    mode: ImageToolMode,
    zoomMode: Boolean,
    capturing: Boolean,
    selection: ImageToolSelection,
    crosshair: Offset,
    slideStart: Pair<Int, Int>?,
    slideEnd: Pair<Int, Int>?,
    onViewport: (ImageToolViewport) -> Unit,
    onSelection: (ImageToolSelection) -> Unit,
    onCrosshair: (Offset) -> Unit,
    onSlide: (Pair<Int, Int>?, Pair<Int, Int>?) -> Unit,
) {
    if (bitmap == null || viewport == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                if (capturing) "正在截图…" else "点工具条上的「截图」取当前屏幕",
                color = Color(0xFF7A8499),
                fontSize = 13.sp,
            )
        }
        return
    }

    val image = remember(bitmap) { bitmap.asImageBitmap() }
    val latestViewport by rememberUpdatedState(viewport)
    val latestSelection by rememberUpdatedState(selection)
    val latestCrosshair by rememberUpdatedState(crosshair)
    // pointerInput 的协程不会因外层重组自动重启；滑动端点必须从最新状态读取，
    // 否则拖过一次后下一次仍会命中初始端点，表现为“拖第二个点，第一个点却在跑”。
    val latestSlideStart by rememberUpdatedState(slideStart)
    val latestSlideEnd by rememberUpdatedState(slideEnd)

    // 拖框过程中的实时矩形（视图坐标），松手后转成图片像素提交。
    var dragFrom by remember(bitmap, mode) { mutableStateOf<Offset?>(null) }
    var dragTo by remember(bitmap, mode) { mutableStateOf<Offset?>(null) }
    var activeEdge by remember(bitmap, mode) { mutableStateOf<BoxEdge?>(null) }
    var resizeStartBox by remember(bitmap, mode) { mutableStateOf<ImageToolCodeGen.Roi?>(null) }
    var resizeStartPointer by remember(bitmap, mode) { mutableStateOf<Offset?>(null) }
    var draggingSlide by remember(bitmap, mode) { mutableStateOf<Boolean?>(null) }
    // 一次拖动开始时冻结两端：被拖的一端持续更新，另一端绝不能被后续重组中的旧值覆盖。
    var slideStartAtDrag by remember(bitmap, mode) { mutableStateOf<Pair<Int, Int>?>(null) }
    var slideEndAtDrag by remember(bitmap, mode) { mutableStateOf<Pair<Int, Int>?>(null) }
    // 取色/多点按住时手指所在的视图坐标：有值才画临时十字和放大镜，抬手清空并取点。
    var pressPoint by remember(bitmap, mode) { mutableStateOf<Offset?>(null) }
    // 拖滑动标记时手指所在的视图坐标：只在拖动期间给放大镜当锚点。
    var slideDragPos by remember(bitmap, mode) { mutableStateOf<Offset?>(null) }

    fun resetTransient() {
        dragFrom = null
        dragTo = null
        activeEdge = null
        resizeStartBox = null
        resizeStartPointer = null
        draggingSlide = null
        slideStartAtDrag = null
        slideEndAtDrag = null
        pressPoint = null
        slideDragPos = null
    }

    /** 单指抬手：拖框模式提交 ROI；取色/多点模式在抬手点取色（参考 handleColorPickerEnd）。 */
    fun commitBox() {
        pressPoint?.let { finger ->
            val v = latestViewport
            v.toImage(finger.x, finger.y)?.let { (x, y) ->
                onSelection(
                    latestSelection.withPoint(
                        mode,
                        ImageToolCodeGen.PickedPoint(x, y, bitmap.getPixel(x, y) and 0xFFFFFF),
                    ),
                )
            }
        }
        val from = dragFrom
        val to = dragTo
        if (from != null && to != null) {
            val v = latestViewport
            val a = v.toImage(from.x, from.y)
            val b = v.toImage(to.x, to.y)
            if (a != null && b != null && abs(a.first - b.first) > 1 && abs(a.second - b.second) > 1) {
                onSelection(
                    latestSelection.copy(
                        box = ImageToolCodeGen.Roi(
                            left = min(a.first, b.first),
                            top = min(a.second, b.second),
                            // ROI 半开区间，右下 +1 才把最后一列/行包进去。
                            right = max(a.first, b.first) + 1,
                            bottom = max(a.second, b.second) + 1,
                        ),
                    ),
                )
            }
        }
        resetTransient()
    }

    /** 单指按下时决定这次拖动要干什么。 */
    fun beginSingle(start: Offset) {
        val v = latestViewport
        when {
            zoomMode -> Unit
            mode.usesDragBox -> {
                val box = latestSelection.box
                val edge = box?.let { hitEdge(it, v, start) }
                if (edge != null) {
                    activeEdge = edge
                    resizeStartBox = box
                    resizeStartPointer = start
                } else if (box == null) {
                    // 参考 handleSelectionStart()：已有框时禁止另起新框，必须先点 × 关掉。
                    dragFrom = start
                    dragTo = start
                }
            }
            mode.usesSlideMarkers -> {
                val startMarker = latestSlideStart
                val endMarker = latestSlideEnd
                draggingSlide = nearerSlideMarker(startMarker, endMarker, v, start)
                if (draggingSlide != null) {
                    slideStartAtDrag = startMarker
                    slideEndAtDrag = endMarker
                    slideDragPos = start
                }
            }
            // 单击模式必须在按下时就更新准星。此前只在发生“拖动”时更新，
            // 用户轻点目标会保留上一次（或屏幕中心）的准星，因而生成错误坐标。
            mode.usesCrosshair -> onCrosshair(start)
            // 按下即开始跟手（参考 colorpicker 的十字光标跟随手指），抬手时才真正取点。
            mode.usesPressPick -> pressPoint = start
        }
    }

    /** 单指移动。 */
    fun moveSingle(position: Offset, delta: Offset, viewSize: Size) {
        val v = latestViewport
        when {
            zoomMode -> onViewport(v.panned(delta.x, delta.y))
            activeEdge != null -> {
                val box = resizeStartBox ?: return
                val start = resizeStartPointer ?: return
                onSelection(
                    latestSelection.copy(
                        box = resizeBox(
                            box = box,
                            edge = activeEdge!!,
                            deltaViewX = position.x - start.x,
                            deltaViewY = position.y - start.y,
                            scale = v.scale,
                            imageWidth = v.imageWidth,
                            imageHeight = v.imageHeight,
                        ),
                    ),
                )
            }
            dragFrom != null -> dragTo = position
            draggingSlide != null -> {
                slideDragPos = position
                val p = v.toImage(position.x, position.y) ?: return
                val fixedStart = slideStartAtDrag ?: latestSlideStart
                val fixedEnd = slideEndAtDrag ?: latestSlideEnd
                if (draggingSlide == true) onSlide(p, fixedEnd) else onSlide(fixedStart, p)
            }
            pressPoint != null -> pressPoint = position
            mode.usesCrosshair -> {
                // 准星模式下单指拖的是准星，不是图——参考把图的拖动放在缩放模式里。
                onCrosshair(
                    Offset(
                        (latestCrosshair.x + delta.x).coerceIn(0f, viewSize.width),
                        (latestCrosshair.y + delta.y).coerceIn(0f, viewSize.height),
                    ),
                )
            }
            else -> onViewport(v.panned(delta.x, delta.y))
        }
    }

    Box(
        Modifier.fillMaxSize()
            // 单一手势循环，按手指数分发——对应参考 handleStart/handleMove 里
            // `touches.length === 1` 走拖动、`=== 2` 走捏合的结构。
            // 不能把 detectTransformGestures 和 detectDragGestures 叠在同一个节点上：
            // 两个探测器会抢同一批事件，后加的先消费，另一个就收不到了。
            .pointerInput(bitmap, mode, zoomMode) {
                awaitEachGesture {
                    val first = awaitFirstDown(requireUnconsumed = false)
                    var pinching = false
                    var lastCentroid = Offset.Zero
                    var lastDistance = 0f
                    var lastSingle = first.position
                    beginSingle(first.position)
                    while (true) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.isEmpty()) break
                        if (pressed.size >= 2) {
                            val a = pressed[0].position
                            val b = pressed[1].position
                            val centroid = Offset((a.x + b.x) / 2f, (a.y + b.y) / 2f)
                            val distance = hypot(a.x - b.x, a.y - b.y)
                            if (!pinching) {
                                // 从单指切到双指：丢掉正在画的框，避免捏合时留下一个歪框。
                                pinching = true
                                resetTransient()
                            } else {
                                val v = latestViewport
                                val zoom = if (lastDistance > 0f) distance / lastDistance else 1f
                                val pan = centroid - lastCentroid
                                // 以两指中点为锚缩放，再叠加中点的位移——参考 scaleImage(factor, cx, cy)。
                                onViewport(v.scaledBy(zoom, centroid.x, centroid.y).panned(pan.x, pan.y))
                            }
                            lastCentroid = centroid
                            lastDistance = distance
                            pressed.forEach { it.consume() }
                        } else if (!pinching) {
                            val change = pressed.first()
                            val delta = change.position - lastSingle
                            lastSingle = change.position
                            if (delta != Offset.Zero) {
                                moveSingle(change.position, delta, Size(size.width.toFloat(), size.height.toFloat()))
                                change.consume()
                            }
                        } else {
                            // 从双指回到单指：不接着拖，等下一次按下重新开始，避免图突然跳动。
                            lastSingle = pressed.first().position
                        }
                    }
                    if (!pinching) commitBox() else resetTransient()
                }
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val v = latestViewport
            drawImage(
                image = image,
                dstOffset = IntOffset(v.translateX.roundToInt(), v.translateY.roundToInt()),
                dstSize = IntSize(v.scaledWidth.roundToInt(), v.scaledHeight.roundToInt()),
            )
            drawSelectionBox(latestSelection.box, v, zoomMode)
            drawLiveBox(dragFrom, dragTo)
            drawPickedPoints(latestSelection.points, v)
            if (mode.usesSlideMarkers) drawSlide(slideStart, slideEnd, v)
        }
        // 放大镜的显隐逐条对照参考：
        //   单击   —— 常驻，随准星（updateCrosshairPreview 在切入模式时就 display=block）
        //   取色/多点 —— 仅按住时，抬手隐藏（handleColorPickerMove / handleColorPickerEnd）
        //   滑动   —— 仅拖起/终标记时，松手隐藏（showMagnifier 唯一调用点 2849，handleEnd 2874 隐藏）
        //   选范围/裁剪 —— 没有
        //   缩放模式 —— 一律隐藏（enterZoomDrag 里 previewBox.style.display = "none"）
        if (!zoomMode) {
            when {
                mode.usesCrosshair -> {
                    ImageToolCrosshair(crosshair)
                    ImageToolMagnifier(bitmap = bitmap, viewport = viewport, anchor = crosshair)
                }
                pressPoint != null -> {
                    ImageToolCrosshair(pressPoint!!)
                    ImageToolMagnifier(bitmap = bitmap, viewport = viewport, anchor = pressPoint!!)
                }
                draggingSlide != null && slideDragPos != null ->
                    ImageToolMagnifier(bitmap = bitmap, viewport = viewport, anchor = slideDragPos!!)
            }
        }
    }
}

/** 选框：参考 `#crop-box` 的 1px 红边 + 30% 黑底；缩放模式下隐藏。 */
private fun DrawScope.drawSelectionBox(box: ImageToolCodeGen.Roi?, v: ImageToolViewport, zoomMode: Boolean) {
    if (box == null || zoomMode) return
    val (left, top) = v.toView(box.left, box.top)
    val size = Size(v.toViewLength(box.width), v.toViewLength(box.height))
    drawRect(Color.Black.copy(alpha = 0.3f), topLeft = Offset(left, top), size = size)
    drawRect(BoxStroke, topLeft = Offset(left, top), size = size, style = Stroke(width = 2f))
    // 四条边的手柄，对应参考的 crop-handle-left/top/right/bottom。
    val handle = HANDLE_RADIUS
    drawCircle(BoxStroke, handle, Offset(left, top + size.height / 2f))
    drawCircle(BoxStroke, handle, Offset(left + size.width / 2f, top))
    drawCircle(BoxStroke, handle, Offset(left + size.width, top + size.height / 2f))
    drawCircle(BoxStroke, handle, Offset(left + size.width / 2f, top + size.height))
}

private fun DrawScope.drawLiveBox(from: Offset?, to: Offset?) {
    if (from == null || to == null) return
    val topLeft = Offset(min(from.x, to.x), min(from.y, to.y))
    val size = Size(abs(to.x - from.x), abs(to.y - from.y))
    drawRect(BoxStroke, topLeft = topLeft, size = size, style = Stroke(width = 2f))
}

private fun DrawScope.drawPickedPoints(points: List<ImageToolCodeGen.PickedPoint>, v: ImageToolViewport) {
    points.forEachIndexed { index, point ->
        val (x, y) = v.toView(point.x, point.y)
        // 第一个是锚点，画大一圈区分。
        val radius = if (index == 0) 9f else 6f
        drawCircle(Color.White, radius + 2f, Offset(x, y))
        drawCircle(
            Color(
                red = (point.rgb shr 16) and 0xFF,
                green = (point.rgb shr 8) and 0xFF,
                blue = point.rgb and 0xFF,
            ),
            radius,
            Offset(x, y),
        )
    }
}

/** 滑动起终标记与连线，对应参考的 `slide-point-start/end` + `slide-line`（青色虚线）。 */
private fun DrawScope.drawSlide(start: Pair<Int, Int>?, end: Pair<Int, Int>?, v: ImageToolViewport) {
    if (start == null || end == null) return
    val (sx, sy) = v.toView(start.first, start.second)
    val (ex, ey) = v.toView(end.first, end.second)
    drawLine(Color(0xFF00E5FF), Offset(sx, sy), Offset(ex, ey), strokeWidth = 4f)
    // 标记大小不随缩放变化——参考用 scale(1/scale) 达到同样效果。
    drawCircle(Color(0xFF00E5FF), MARKER_RADIUS, Offset(sx, sy))
    drawCircle(Color.White, MARKER_RADIUS - 4f, Offset(sx, sy))
    drawCircle(Color(0xFFFF7A45), MARKER_RADIUS, Offset(ex, ey))
    drawCircle(Color.White, MARKER_RADIUS - 4f, Offset(ex, ey))
}

/** 命中哪条边的手柄；都没命中返回 null。 */
private fun hitEdge(box: ImageToolCodeGen.Roi, v: ImageToolViewport, point: Offset): BoxEdge? {
    val (left, top) = v.toView(box.left, box.top)
    val width = v.toViewLength(box.width)
    val height = v.toViewLength(box.height)
    val candidates = listOf(
        BoxEdge.LEFT to Offset(left, top + height / 2f),
        BoxEdge.TOP to Offset(left + width / 2f, top),
        BoxEdge.RIGHT to Offset(left + width, top + height / 2f),
        BoxEdge.BOTTOM to Offset(left + width / 2f, top + height),
    )
    return candidates.firstOrNull { (_, centre) -> hypot(point.x - centre.x, point.y - centre.y) <= HANDLE_TOUCH }?.first
}

/** 离哪个滑动标记近就拖哪个；true = 起点。 */
private fun nearerSlideMarker(
    start: Pair<Int, Int>?,
    end: Pair<Int, Int>?,
    v: ImageToolViewport,
    point: Offset,
): Boolean? {
    if (start == null || end == null) return null
    val (sx, sy) = v.toView(start.first, start.second)
    val (ex, ey) = v.toView(end.first, end.second)
    val toStart = hypot(point.x - sx, point.y - sy)
    val toEnd = hypot(point.x - ex, point.y - ey)
    if (min(toStart, toEnd) > MARKER_TOUCH) return null
    return toStart <= toEnd
}

private const val HANDLE_RADIUS = 9f
private const val HANDLE_TOUCH = 48f
private const val MARKER_RADIUS = 14f
private const val MARKER_TOUCH = 64f

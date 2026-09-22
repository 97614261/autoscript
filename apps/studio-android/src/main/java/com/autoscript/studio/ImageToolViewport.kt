package com.autoscript.studio

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 图像工具画布的视图变换：缩放 + 平移，以及视图坐标与图片像素的互转。
 *
 * 逐条对照参考实现 `资料/erjianwan.apk` 的 `assets/web/html/script/图片工具.html`
 * （已抽到 `build/erjianwan-ui/x-3d4f4b39.html`）：
 *
 * - `updateImageTransform()`：`transform: translate(tx, ty) scale(s)`，即先缩放后平移，
 *   图片左上角映射到 `(tx, ty)`，图片像素 `(px, py)` 落在视图 `(tx + px*s, ty + py*s)`。
 * - `limitTranslation()`：图小于容器时**居中**，大于容器时把平移夹在
 *   `[容器尺寸 - 缩放后尺寸, 0]`，防止把图拖出屏幕再也找不回来。
 * - `scaleImage(factor, cx, cy)`：以 `(cx, cy)` 为锚点缩放——先把锚点换算回图片坐标，
 *   缩放后再反推平移，使该点在屏幕上保持不动。
 * - `resetView()`：适应屏幕，且 `fitScale` 取 `min(宽比, 高比, 1)`——**不放大小图**。
 *
 * 纯数据类，不依赖 Compose，便于 JVM 单测。
 */
internal data class ImageToolViewport(
    val imageWidth: Int,
    val imageHeight: Int,
    val viewWidth: Float,
    val viewHeight: Float,
    val scale: Float,
    val translateX: Float,
    val translateY: Float,
) {
    /** 适应屏幕的缩放比：参考 `resetView()` 的 `min(w比, h比, 1)`，小图不放大。 */
    val fitScale: Float
        get() = min(min(viewWidth / imageWidth, viewHeight / imageHeight), 1f)

    /**
     * 最小允许缩放：参考 `scaleImage()` 里的 `minScale = min(fitWidthScale, fitHeightScale)`。
     *
     * 注意这里**不带** `resetView()` 的 `1f` 上限：一张比屏幕小的图，`fitScale` 会是 1，
     * 但 `minScale` 允许缩到更小（比如 0.4）。两者不是同一个量，别合并。
     */
    val minScale: Float
        get() = min(viewWidth / imageWidth, viewHeight / imageHeight)

    /** 图片按当前缩放在屏幕上占的尺寸。 */
    val scaledWidth: Float get() = imageWidth * scale
    val scaledHeight: Float get() = imageHeight * scale

    /**
     * 参考 `limitTranslation()`：小于容器则居中，大于容器则夹在边缘之间。
     *
     * 这个钳制必须在每次缩放/平移后都跑一遍，否则图会被拖到屏幕外。
     */
    fun clamped(): ImageToolViewport {
        val tx = if (scaledWidth <= viewWidth) {
            (viewWidth - scaledWidth) / 2f
        } else {
            max(viewWidth - scaledWidth, min(0f, translateX))
        }
        val ty = if (scaledHeight <= viewHeight) {
            (viewHeight - scaledHeight) / 2f
        } else {
            max(viewHeight - scaledHeight, min(0f, translateY))
        }
        return copy(translateX = tx, translateY = ty)
    }

    /** 平移（手指拖动）。 */
    fun panned(dx: Float, dy: Float): ImageToolViewport =
        copy(translateX = translateX + dx, translateY = translateY + dy).clamped()

    /**
     * 以视图坐标 [centerX]/[centerY] 为锚点缩放，对应参考的 `scaleImage(factor, cx, cy)`。
     *
     * 锚点在屏幕上保持不动是这段的全部意义：双指捏合时若不做这步，图会从左上角"长出去"。
     */
    fun scaledBy(factor: Float, centerX: Float, centerY: Float): ImageToolViewport {
        val imageX = (centerX - translateX) / scale
        val imageY = (centerY - translateY) / scale
        val next = (scale * factor).coerceIn(minScale, MAX_SCALE)
        return copy(
            scale = next,
            translateX = centerX - imageX * next,
            translateY = centerY - imageY * next,
        ).clamped()
    }

    /** 参考的「原图」按钮 `resetView()`：回到适应屏幕并居中。 */
    fun reset(): ImageToolViewport = copy(
        scale = fitScale,
        translateX = (viewWidth - imageWidth * fitScale) / 2f,
        translateY = (viewHeight - imageHeight * fitScale) / 2f,
    ).clamped()

    /**
     * 视图坐标 → 图片像素，对应参考的 `(clientX - rect.left - translateX) / scale`。
     *
     * [clamp] 为 true 时把落在图外的点吸附到最近边缘——参考在 `updateCrosshairPreview()`
     * 里就是这么做的（注释原文「坐标钳制 (自动吸附边缘)」），因为准星可以拖到图外，
     * 那时仍应显示一个有效坐标，而不是消失或报错。
     */
    fun toImage(viewX: Float, viewY: Float, clamp: Boolean = true): Pair<Int, Int>? {
        val px = ((viewX - translateX) / scale).toInt()
        val py = ((viewY - translateY) / scale).toInt()
        if (!clamp && (px < 0 || py < 0 || px >= imageWidth || py >= imageHeight)) return null
        return px.coerceIn(0, imageWidth - 1) to py.coerceIn(0, imageHeight - 1)
    }

    /** 图片像素 → 视图坐标，用于把选框/标记画回屏幕。 */
    fun toView(imageX: Int, imageY: Int): Pair<Float, Float> =
        (translateX + imageX * scale) to (translateY + imageY * scale)

    /** 图片像素长度 → 视图长度。 */
    fun toViewLength(imageLength: Int): Float = imageLength * scale

    companion object {
        /** 参考没有写死上限，但无限放大会让平移钳制失去意义；16 倍够用于逐像素对齐。 */
        const val MAX_SCALE = 16f

        /** 建一个适应屏幕并居中的初始视图。 */
        fun fit(imageWidth: Int, imageHeight: Int, viewWidth: Float, viewHeight: Float): ImageToolViewport {
            require(imageWidth > 0 && imageHeight > 0) { "图片尺寸必须为正：${imageWidth}x$imageHeight" }
            require(viewWidth > 0f && viewHeight > 0f) { "视图尺寸必须为正：${viewWidth}x$viewHeight" }
            return ImageToolViewport(
                imageWidth = imageWidth,
                imageHeight = imageHeight,
                viewWidth = viewWidth,
                viewHeight = viewHeight,
                scale = 1f,
                translateX = 0f,
                translateY = 0f,
            ).reset()
        }
    }
}

/**
 * 放大镜取样：以 [centerX]/[centerY] 为心取 [size]×[size] 的像素块。
 *
 * 对应参考的 `GetPreviewImage(imgX, imgY, 11, 11, 1)`——那是 WebView 注入的原生接口，
 * 把区域渲染成图片再回读；Compose 这边直接从 Bitmap 读像素即可，不需要那座桥。
 *
 * 越界的格子返回 null，由调用方画成空格，而不是把坐标夹回来——夹回来会让边缘区域
 * 显示一片重复的同色像素，反而看不出自己正处在边界。
 */
internal fun magnifierSamples(
    pixelAt: (Int, Int) -> Int?,
    centerX: Int,
    centerY: Int,
    size: Int = MAGNIFIER_GRID,
): List<Int?> {
    require(size > 0 && size % 2 == 1) { "放大镜边长必须是正奇数：$size" }
    val radius = size / 2
    return buildList(size * size) {
        for (dy in -radius..radius) {
            for (dx in -radius..radius) {
                add(pixelAt(centerX + dx, centerY + dy))
            }
        }
    }
}

/** 参考用的是 11×11。 */
internal const val MAGNIFIER_GRID = 11

/**
 * 放大镜浮层的位置：参考 `showMagnifier()` 的避让逻辑——
 * 默认放在手指左上方，越界则翻到另一侧，最后再做一次强制边界钳位。
 *
 * 返回浮层左上角的视图坐标。
 */
internal fun magnifierPlacement(
    anchorX: Float,
    anchorY: Float,
    boxSize: Float,
    viewWidth: Float,
    viewHeight: Float,
    offset: Float = 48f,
): Pair<Float, Float> {
    var left = anchorX - boxSize - offset
    var top = anchorY - boxSize - offset
    if (left < 0f) left = anchorX + offset
    if (top < 0f) top = anchorY + offset
    if (left + boxSize > viewWidth) left = viewWidth - boxSize
    if (top + boxSize > viewHeight) top = viewHeight - boxSize
    return max(0f, left) to max(0f, top)
}

/** 把浮动工具条的位置夹在屏幕内，留出可抓取的宽度。 */
internal fun clampToolbar(x: Float, y: Float, width: Float, height: Float, viewWidth: Float, viewHeight: Float): Pair<Float, Float> =
    x.coerceIn(0f, max(0f, viewWidth - width)) to y.coerceIn(0f, max(0f, viewHeight - height))

/**
 * 菜单位置：参考 `updateMenuPosition()`——默认贴在工具条下方，
 * 下方空间不足就翻到上方，左右超出则推回屏幕内。
 */
internal fun menuPlacement(
    toolbarLeft: Float,
    toolbarTop: Float,
    toolbarHeight: Float,
    menuWidth: Float,
    menuHeight: Float,
    viewWidth: Float,
    viewHeight: Float,
    padding: Float = 6f,
): Pair<Float, Float> {
    var left = toolbarLeft
    if (left + menuWidth > viewWidth) left = viewWidth - menuWidth
    left = max(0f, left)
    var top = toolbarTop + toolbarHeight + padding
    if (top + menuHeight > viewHeight) top = toolbarTop - menuHeight - padding
    top = max(0f, top)
    return left to top
}

/** 逐像素微调选框边界，对应参考 `handleHandleClick()` 的 `−/−/+/+`。 */
internal enum class BoxEdge { LEFT, TOP, RIGHT, BOTTOM }

/**
 * 参考 `handleHandleClick()`：左/上边点一下向内收 1px，右/下边点一下向外扩 1px，
 * 且左右两侧都保证不小于 [minSize]、不超出图片边界。
 */
internal fun nudgeBox(
    box: ImageToolCodeGen.Roi,
    edge: BoxEdge,
    imageWidth: Int,
    imageHeight: Int,
    minSize: Int = 1,
): ImageToolCodeGen.Roi = when (edge) {
    BoxEdge.LEFT -> if (box.width > minSize) box.copy(left = box.left + 1) else box
    BoxEdge.TOP -> if (box.height > minSize) box.copy(top = box.top + 1) else box
    BoxEdge.RIGHT -> box.copy(right = min(imageWidth, box.right + 1))
    BoxEdge.BOTTOM -> box.copy(bottom = min(imageHeight, box.bottom + 1))
}

/**
 * 拖动选框某条边，对应参考 `handleResizeMove()`：
 * 屏幕位移先按 `delta / scale` 折算成**图片整数像素**，再夹住最小尺寸与图片边界。
 */
internal fun resizeBox(
    box: ImageToolCodeGen.Roi,
    edge: BoxEdge,
    deltaViewX: Float,
    deltaViewY: Float,
    scale: Float,
    imageWidth: Int,
    imageHeight: Int,
    minSize: Int = 1,
): ImageToolCodeGen.Roi {
    val dx = (deltaViewX / scale).roundToInt()
    val dy = (deltaViewY / scale).roundToInt()
    var left = box.left
    var top = box.top
    var right = box.right
    var bottom = box.bottom
    when (edge) {
        BoxEdge.LEFT -> left = (left + dx).coerceIn(0, right - minSize)
        BoxEdge.RIGHT -> right = (right + dx).coerceIn(left + minSize, imageWidth)
        BoxEdge.TOP -> top = (top + dy).coerceIn(0, bottom - minSize)
        BoxEdge.BOTTOM -> bottom = (bottom + dy).coerceIn(top + minSize, imageHeight)
    }
    return ImageToolCodeGen.Roi(left, top, right, bottom)
}

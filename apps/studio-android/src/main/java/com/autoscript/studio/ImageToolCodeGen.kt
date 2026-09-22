package com.autoscript.studio

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 图像工具各模式的产出：把在截图上选取的像素坐标/颜色，转成可直接插入脚本的 Lua 片段。
 *
 * 纯函数，不碰 Compose，便于 JVM 单测。
 *
 * 两条硬约束：
 *
 * 1. **只生成契约内真实存在的 API。** 签名逐条核对自 `schema/api-schema/stubs/autoscript.lua`：
 *    `Input.tap(x, y)`、`Input.swipe(x1, y1, x2, y2, durationMs)`、`Screen.capture()`、
 *    `Screen.release(frame)`、`Screen.getColor(frame, x, y)`、
 *    `Screen.findMultiColor(frame, anchorRgb, anchorTolerance, samples, left, top, right, bottom)`。
 *    生成后仍应过一遍 [LuaSnippetGate] 兜底。
 *
 * 2. **输入和视觉 API 使用不同坐标系。** `Input.tap` / `Input.swipe` 使用设计坐标，
 *    因此要经 [DesignMapping] 换算；`Screen.getColor`、`Screen.findMultiColor` 和
 *    `Screen.findImage` 操作的是捕获帧，必须保留截图的原始像素坐标。
 */
internal object ImageToolCodeGen {

    /**
     * 截图像素 → 项目设计坐标。
     *
     * 截图是物理屏幕尺寸，项目按基准分辨率（创建项目时选的 720x1280 / 1080x1920 / 本机）出坐标。
     * 两者宽高比通常一致（同一块屏），但数值不同，必须按各自的轴独立缩放——
     * 若强行用单一比例，非等比场景下 Y 会系统性偏移。
     */
    data class DesignMapping(
        val captureWidth: Int,
        val captureHeight: Int,
        val designWidth: Int,
        val designHeight: Int,
        val scaleMode: String = "letterbox",
    ) {
        init {
            require(captureWidth > 0 && captureHeight > 0) { "截图尺寸必须为正：${captureWidth}x$captureHeight" }
            require(designWidth > 0 && designHeight > 0) { "设计分辨率必须为正：${designWidth}x$designHeight" }
            require(scaleMode in setOf("letterbox", "crop", "stretch")) { "未知缩放模式：$scaleMode" }
        }

        /** 映射后仍夹在设计坐标范围内，避免边缘像素换算后越界。 */
        fun toDesign(x: Int, y: Int): Pair<Int, Int> {
            val availableX = captureWidth.toDouble() / designWidth
            val availableY = captureHeight.toDouble() / designHeight
            val (scaleX, scaleY) = when (scaleMode) {
                "letterbox" -> minOf(availableX, availableY).let { it to it }
                "crop" -> maxOf(availableX, availableY).let { it to it }
                else -> availableX to availableY
            }
            val originX = (captureWidth - designWidth * scaleX) / 2.0
            val originY = (captureHeight - designHeight * scaleY) / 2.0
            val designX = ((x - originX) / scaleX).roundToInt().coerceIn(0, designWidth - 1)
            val designY = ((y - originY) / scaleY).roundToInt().coerceIn(0, designHeight - 1)
            return designX to designY
        }

        /**
         * ROI 用半开区间 `[left, right)`，与 `Screen.findMultiColor` 等 API 的契约一致
         * （schema 里写的是「右边界，不包含」）。所以右/下边界允许取到 design 宽高本身。
         */
        fun toDesignRoi(left: Int, top: Int, right: Int, bottom: Int): Roi {
            val (l, t) = toDesign(left, top)
            val r = (right.toDouble() * designWidth / captureWidth).roundToInt().coerceIn(l + 1, designWidth)
            val b = (bottom.toDouble() * designHeight / captureHeight).roundToInt().coerceIn(t + 1, designHeight)
            return Roi(l, t, r, b)
        }

        val isIdentity: Boolean get() = captureWidth == designWidth && captureHeight == designHeight

        /** The complete raw-frame ROI used by visual APIs. */
        val fullFrameRoi: Roi get() = Roi(0, 0, captureWidth, captureHeight)
    }

    /** 半开区间 ROI：`[left, right) x [top, bottom)`。 */
    data class Roi(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width: Int get() = right - left
        val height: Int get() = bottom - top
    }

    /** 在截图上取到的一个点及其颜色（`0xRRGGBB`，已去掉 alpha）。 */
    data class PickedPoint(val x: Int, val y: Int, val rgb: Int)

    /** 生成结果：片段本体 + 给用户看的说明；[rejection] 非空表示被 [LuaSnippetGate] 拦下。 */
    data class Snippet(val code: String, val summary: String, val rejection: String? = null)

    /** `0xRRGGBB` 的六位大写十六进制，供文案与代码共用。 */
    fun formatRgb(rgb: Int): String = "0x%06X".format(rgb and 0xFFFFFF)

    /** 单击：点一个坐标，产出一次点击。 */
    fun tap(point: PickedPoint, mapping: DesignMapping): Snippet {
        val (x, y) = mapping.toDesign(point.x, point.y)
        return gate(
            code = "Input.tap($x, $y)",
            summary = "点击设计坐标 ($x, $y)" + mappingNote(mapping, point.x, point.y),
        )
    }

    /**
     * 滑动：点起点和终点。
     *
     * 时长不猜——由调用方传入（界面上给默认值可调）。契约 `Input.swipe` 的 durationMs 是必填参数，
     * 这里做最小校验，避免生成 0 或负数这种一定会被运行时拒绝的代码。
     */
    fun swipe(start: PickedPoint, end: PickedPoint, durationMs: Int, mapping: DesignMapping): Snippet {
        require(durationMs > 0) { "滑动时长必须为正：$durationMs" }
        val (x1, y1) = mapping.toDesign(start.x, start.y)
        val (x2, y2) = mapping.toDesign(end.x, end.y)
        return gate(
            code = "Input.swipe($x1, $y1, $x2, $y2, $durationMs)",
            summary = "从 ($x1, $y1) 滑到 ($x2, $y2)，用时 ${durationMs}ms",
        )
    }

    /**
     * 取色：读一个点的颜色并与目标值比较。
     *
     * `Screen.getColor` 需要一个任务持有的帧句柄，所以片段里必须自带
     * `Screen.capture()`、`Screen.cache(captureId)` 与配对的 `Screen.release(frame)`——
     * capture 返回的是采集编号，不是可供视觉 API 使用的帧句柄；漏掉 release 会占住帧池租约。
     */
    fun getColor(point: PickedPoint, mapping: DesignMapping): Snippet {
        // `Screen.getColor` reads the captured frame, whose coordinate space is the
        // raw screenshot rather than the project's design coordinate space.
        val x = point.x
        val y = point.y
        val rgb = formatRgb(point.rgb)
        val code = buildString {
            appendLine("local captureId = Screen.capture()")
            appendLine("local frame = Screen.cache(captureId)")
            appendLine("local rgb = Screen.getColor(frame, $x, $y)")
            appendLine("Screen.release(frame)")
            append("-- 取色时该点为 $rgb")
        }
        return gate(code, "读取 ($x, $y) 的颜色，取色时为 $rgb")
    }

    /** 选范围：只产出 ROI 四个数，供找色/找图类调用填参。 */
    fun region(roi: Roi, mapping: DesignMapping): Snippet {
        val d = roi
        val code = buildString {
            appendLine("local left, top, right, bottom = ${d.left}, ${d.top}, ${d.right}, ${d.bottom}")
            append("-- 半开区间 [left, right) x [top, bottom)，${d.width}x${d.height}")
        }
        return gate(code, "范围 (${d.left}, ${d.top}) - (${d.right}, ${d.bottom})，${d.width}x${d.height}")
    }

    /**
     * 多点找色。
     *
     * 契约里 `samples` 的每项是「x、y、rgb、tolerance」，且 x/y 是**相对锚点的有符号偏移**
     * （schema 原文：「最多64个有符号偏移采样点」），不是绝对坐标——这里按偏移生成。
     *
     * @param anchor 锚点，采样点偏移以它为原点
     * @param samples 附加采样点（绝对坐标，函数内部转成偏移）
     * @param tolerance 逐通道容差，锚点与采样点共用
     * @param roi 搜索范围；为 null 表示全屏（用设计分辨率兜底）
     */
    fun findMultiColor(
        anchor: PickedPoint,
        samples: List<PickedPoint>,
        tolerance: Int,
        roi: Roi?,
        mapping: DesignMapping,
    ): Snippet {
        require(tolerance in 0..255) { "容差必须在 0..255：$tolerance" }
        require(samples.size <= MAX_MULTI_COLOR_SAMPLES) {
            "采样点最多 $MAX_MULTI_COLOR_SAMPLES 个，当前 ${samples.size} 个"
        }
        val ax = anchor.x
        val ay = anchor.y
        val searchRoi = roi ?: mapping.fullFrameRoi
        val sampleLines = samples.map { sample ->
            val sx = sample.x
            val sy = sample.y
            "  { x = ${sx - ax}, y = ${sy - ay}, rgb = ${formatRgb(sample.rgb)}, tolerance = $tolerance },"
        }
        val code = buildString {
            appendLine("local samples = {")
            sampleLines.forEach { appendLine(it) }
            appendLine("}")
            appendLine("local captureId = Screen.capture()")
            appendLine("local frame = Screen.cache(captureId)")
            appendLine(
                "local point = Screen.findMultiColor(frame, ${formatRgb(anchor.rgb)}, $tolerance, samples, " +
                    "${searchRoi.left}, ${searchRoi.top}, ${searchRoi.right}, ${searchRoi.bottom})",
            )
            append("Screen.release(frame)")
        }
        val summary = "锚点 ($ax, $ay) ${formatRgb(anchor.rgb)} + ${samples.size} 个采样点，容差 $tolerance"
        return gate(code, summary)
    }

    /**
     * 裁剪产出的模板找图片段。资源路径由调用方在图片真正落盘后传入，
     * 这里不猜路径——猜错会生成一段运行时必然失败的代码。
     */
    fun findImage(resourcePath: String, roi: Roi?, mapping: DesignMapping): Snippet {
        require(isImageResourcePath(resourcePath)) { "模板资源路径必须是 assets/images/ 下的规范项目资源：$resourcePath" }
        val searchRoi = roi ?: mapping.fullFrameRoi
        val code = buildString {
            appendLine("local template = Screen.loadImage(${luaString(resourcePath)})")
            appendLine("local captureId = Screen.capture()")
            appendLine("local frame = Screen.cache(captureId)")
            appendLine(
                "local point = Screen.findImage(frame, template, $DEFAULT_TOLERANCE, $DEFAULT_SIMILARITY_PERMILLE, " +
                    "${searchRoi.left}, ${searchRoi.top}, ${searchRoi.right}, ${searchRoi.bottom})",
            )
            appendLine("Screen.release(frame)")
            append("Screen.release(template)")
        }
        return gate(code, "在 (${searchRoi.left}, ${searchRoi.top}) - (${searchRoi.right}, ${searchRoi.bottom}) 内找 $resourcePath")
    }

    /** Lua 字符串字面量：转义反斜杠与双引号，避免路径里的特殊字符破坏语法。 */
    internal fun luaString(value: String): String =
        "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    /**
     * `Screen.loadImage` 只可读取已进入项目资源清单的图片。
     * 这里同步运行计划的规范路径约束，避免 UI 生成能通过 Lua 门禁、却必然在运行期失败的代码。
     */
    private fun isImageResourcePath(path: String): Boolean =
        path.startsWith("assets/images/") &&
            path.length in ("assets/images/".length + 1)..256 &&
            '\\' !in path &&
            '\u0000' !in path &&
            path.split('/').all { it.isNotEmpty() && it != "." && it != ".." }

    /** 生成后统一过一遍契约门禁，防止将来改动引入契约外的 API 而无人察觉。 */
    private fun gate(code: String, summary: String): Snippet =
        Snippet(code = code, summary = summary, rejection = LuaSnippetGate.reject(code))

    /** 截图与设计分辨率不一致时，在说明里点出原始像素，便于用户核对换算。 */
    private fun mappingNote(mapping: DesignMapping, rawX: Int, rawY: Int): String =
        if (mapping.isIdentity) "" else "（截图像素 ($rawX, $rawY)）"

    /** 契约写明采样点最多 64 个。 */
    const val MAX_MULTI_COLOR_SAMPLES = 64

    /** 找图默认参数：与参考产品一致的保守值，界面上可调。 */
    private const val DEFAULT_TOLERANCE = 8
    private const val DEFAULT_SIMILARITY_PERMILLE = 900
}

/** 颜色距离，用于多点取样时提示"这两点颜色太接近，区分度低"。 */
internal fun channelDistance(lhs: Int, rhs: Int): Int = maxOf(
    abs(((lhs shr 16) and 0xFF) - ((rhs shr 16) and 0xFF)),
    abs(((lhs shr 8) and 0xFF) - ((rhs shr 8) and 0xFF)),
    abs((lhs and 0xFF) - (rhs and 0xFF)),
)

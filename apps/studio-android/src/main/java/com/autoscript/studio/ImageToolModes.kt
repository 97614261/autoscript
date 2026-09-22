package com.autoscript.studio

import androidx.annotation.DrawableRes

/**
 * 图像工具的模式定义。
 *
 * 版式参考 `资料/erjianwan.apk` 的 `assets/web/html/script/图片工具.html`（`#buttonMenu`，2 列网格）
 * 与 `图片剪裁器.html`（底栏动作按钮）。那两个界面是 HTML 跑在 WebView 里，没有 XML 布局可抄，
 * 所以这里按它的交互语义用 Compose 重写，不照搬它的橙色配色，改用本项目设计令牌。
 *
 * [enabled] 为 false 的模式必须给出 [blockedReason]：仓库规则是「UI 不制造假能力，
 * 未开放功能显示明确禁用/预留」，禁用项点了不能有任何产出。
 */
internal enum class ImageToolMode(
    val label: String,
    @DrawableRes val icon: Int,
    val hint: String,
    val enabled: Boolean = true,
    val blockedReason: String? = null,
) {
    CROP(
        label = "裁剪",
        icon = R.drawable.tool_crop_24,
        hint = "拖动框选要裁出的区域",
    ),
    REGION(
        label = "范围",
        icon = R.drawable.tool_region_24,
        hint = "拖动框选搜索范围",
    ),
    COLOR(
        label = "取色",
        icon = R.drawable.tool_colorpick_24,
        hint = "点击图片取一个点的颜色",
    ),
    MULTI_COLOR(
        label = "多点",
        icon = R.drawable.tool_multipoint_24,
        hint = "先点锚点，再点若干采样点",
    ),
    TAP(
        label = "单击",
        icon = R.drawable.tool_tap_24,
        hint = "点击图片选一个点击坐标",
    ),
    SWIPE(
        label = "滑动",
        icon = R.drawable.tool_swipe_24,
        hint = "拖动蓝色起点和橙色终点设置滑动轨迹",
    ),
    OCR(
        label = "取字",
        icon = R.drawable.tool_region_24,
        hint = "框选区域识别文字",
        enabled = false,
        // NativeEngineBridge 只有 nativeCapturePreview / nativeRegisterTemplate /
        // nativeRegisterDictionary 和脚本生命周期，没有"对任意位图跑一次 OCR"的入口。
        // 字库 OCR 在 Rust 的 glyph-ocr crate 里，但编辑器侧调不到。
        blockedReason = "取字需要编辑器侧的位图识别通道，当前 JNI 桥未提供，属后续阶段",
    ),
    NODE(
        label = "节点",
        icon = R.drawable.tool_multipoint_24,
        hint = "点选界面控件",
        enabled = false,
        blockedReason = "节点选取需要无障碍服务读取控件树，本阶段只做 Root 后端",
    ),
    RECORD(
        label = "录制",
        icon = R.drawable.tool_swipe_24,
        hint = "录制一段操作",
        enabled = false,
        blockedReason = "录制需要底层事件录制流，尚未实现",
    ),
    ;

    /** 需要拖框的模式：裁剪、范围、以及将来的取字。 */
    val usesDragBox: Boolean get() = this == CROP || this == REGION || this == OCR

    /**
     * 用常驻十字准星定位的模式——只有单击。
     *
     * 参考 `图片工具.html` 的 singleClick：提示语「拖动图片定位，点击确定」，
     * `updateCrosshairPreview()` 在切入该模式时立即把准星和放大镜都 `display = "block"`，
     * 之后一直挂着。手指会挡住目标像素，所以靠可独立拖动的准星取点，「确定」取准星中心。
     */
    val usesCrosshair: Boolean get() = this == TAP

    /**
     * 「按住拖动、抬手取点」的模式——取色与多点。
     *
     * 参考 `图片剪裁器.html` 的 colorpicker：`handleColorPickerMove` 让十字光标跟着手指走并
     * `showMagnifier()`；`handleColorPickerEnd` 把光标和放大镜都 `display = "none"`，
     * 然后在**抬手的位置**取色。也就是说放大镜只在按住时出现，不常驻——和单击模式不同。
     */
    val usesPressPick: Boolean get() = this == COLOR || this == MULTI_COLOR

    /** 滑动用起/终两个可拖动标记 + 连线，对应参考的 `slide-point-start/end` 与 `slide-line`。 */
    val usesSlideMarkers: Boolean get() = this == SWIPE

    companion object {
        /** 菜单展示顺序：能用的在前，禁用的垫底，避免用户先点到点不动的。 */
        val menuOrder: List<ImageToolMode> = entries.sortedByDescending { it.enabled }
    }
}

/** 工具当前的选取状态。所有坐标都是**截图像素**，到生成代码时才换算成设计坐标。 */
internal data class ImageToolSelection(
    val box: ImageToolCodeGen.Roi? = null,
    val points: List<ImageToolCodeGen.PickedPoint> = emptyList(),
) {
    fun clearedFor(mode: ImageToolMode): ImageToolSelection =
        if (mode.usesDragBox) copy(points = emptyList()) else copy(box = null)

    /** 单击/取色只留最后一个点；滑动留两个；多点不限（上限在生成时校验）。 */
    fun withPoint(mode: ImageToolMode, point: ImageToolCodeGen.PickedPoint): ImageToolSelection = when (mode) {
        ImageToolMode.COLOR, ImageToolMode.TAP -> copy(points = listOf(point))
        ImageToolMode.SWIPE -> copy(points = (points + point).takeLast(2))
        ImageToolMode.MULTI_COLOR ->
            if (points.size >= ImageToolCodeGen.MAX_MULTI_COLOR_SAMPLES + 1) this
            else copy(points = points + point)
        else -> this
    }
}

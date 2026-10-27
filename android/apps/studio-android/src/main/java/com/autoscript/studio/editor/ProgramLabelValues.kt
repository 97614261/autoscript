package com.autoscript.studio

internal fun transformTitle(mode: String?): String? = when (mode) {
    "rotate90" -> "图像顺时针旋转90°"; "rotate180" -> "图像旋转180°"; "rotate270" -> "图像顺时针旋转270°"
    "flipX" -> "图像左右镜像"; "flipY" -> "图像上下镜像"; "gray" -> "图像灰度化"
    "threshold" -> "图像二值化"; "filter" -> "图像滤色"; "mask" -> "图像蒙版"
    "despeckle" -> "图像去噪点"; "erode" -> "图像腐蚀"; "dilate" -> "图像膨胀"
    "open" -> "图像开运算"; "close" -> "图像闭运算"; "resize" -> "图像缩放"
    else -> null
}

internal fun mathOperationTitle(operation: String?): String? = when (operation) {
    "abs" -> "取绝对值"; "ceil" -> "向上取整"; "floor" -> "向下取整"; "sqrt" -> "开平方"
    "ln" -> "自然对数"; "log10" -> "常用对数"; "exp" -> "自然指数"
    "sin" -> "正弦（弧度）"; "cos" -> "余弦（弧度）"; "tan" -> "正切（弧度）"
    "asin" -> "反正弦"; "acos" -> "反余弦"; "atan" -> "反正切"
    "deg" -> "弧度转角度"; "rad" -> "角度转弧度"
    "min" -> "取较小值"; "max" -> "取较大值"; "pow" -> "乘方"
    "atan2" -> "两分量反正切"; "log" -> "指定底数对数"
    else -> null
}

internal fun keyLabel(code: String?): String = when (code) {
    "3" -> "主页键（3）"; "4" -> "返回键（4）"; "24" -> "音量加（24）"; "25" -> "音量减（25）"
    "26" -> "电源键（26）"; "66" -> "回车键（66）"; "67" -> "退格键（67）"; "187" -> "最近任务键（187）"
    else -> "键码 ${code ?: "?"}"
}

internal fun programEnumLabel(key: String, value: String): String = when (key) {
    "operator" -> when (value) {
        "equals" -> "="; "notEquals" -> "≠"; "lessThan" -> "<"; "lessOrEqual" -> "≤"; "greaterThan" -> ">"; "greaterOrEqual" -> "≥"; else -> value
    }
    "channel" -> when (value) { "popup" -> "弹出提示"; "output" -> "运行提示"; "console" -> "日志"; else -> value }
    "level" -> when (value) { "info" -> "信息"; "warn" -> "警告"; "error" -> "错误"; else -> value }
    "successAction", "action" -> when (value) {
        "none" -> "无后续动作"; "tap" -> "点击"; "hold" -> "按下不弹起"; "tapWait" -> "点击后等待"; "pressRelease" -> "按下再弹起"; else -> value
    }
    "selection" -> when (value) { "first" -> "首项"; "all" -> "全部"; else -> value }
    "anchor" -> when (value) { "center" -> "中心点"; "topLeft" -> "左上角"; else -> value }
    "metric" -> when (value) { "count" -> "次数"; "elapsed" -> "耗时"; else -> value }
    "unit", "durationUnit" -> timeUnit(value)
    "field" -> resultFieldLabel(value)
    "section" -> when (value) {
        "device" -> "设备"; "display" -> "显示"; "battery" -> "电池"; "network" -> "网络"; "storage" -> "存储"; "permissions" -> "权限"; else -> value
    }
    "enabled", "visible", "draggable", "allowPartial", "blockOutside", "exactTiming" -> when (value) { "true" -> "是"; "false" -> "否"; else -> value }
    else -> value
}

private fun resultFieldLabel(value: String): String = when (value) {
    "count" -> "数量"; "truncated" -> "是否截断"; "text" -> "文字"; "coveragePermille" -> "覆盖率（千分比）"
    "averageScorePermille" -> "平均得分（千分比）"; "recognized" -> "是否识别"; "score" -> "得分"
    "scorePermille" -> "得分（千分比）"; "x" -> "X坐标"; "y" -> "Y坐标"; "width" -> "宽度"; "height" -> "高度"
    "path" -> "图片路径"; "area" -> "面积"; "left" -> "左边界"; "top" -> "上边界"; "right" -> "右边界"; "bottom" -> "下边界"
    "centerX" -> "中心X"; "centerY" -> "中心Y"; "changedPixels" -> "变化像素数"; "totalPixels" -> "总像素数"
    "ratioPermille" -> "变化比例（千分比）"; "fresh" -> "是否新帧"; "elapsedUs" -> "耗时（微秒）"
    "screenWidth" -> "屏幕宽度"; "screenHeight" -> "屏幕高度"; "scalePermille" -> "缩放比例（千分比）"
    else -> value
}

internal fun uiOperationUsesValue(operation: String?): Boolean = operation != null && !operation.startsWith("get:") &&
    operation !in setOf("show", "hide", "minimize", "groupReset", "itemsClear")

internal fun uiOperationSummary(operation: String?): String {
    if (operation == null) return "未设置操作"
    when (operation) {
        "show" -> return "显示窗口"; "hide" -> return "隐藏窗口"; "minimize" -> return "最小化窗口"
        "page" -> return "切换页面"; "restoreConfig" -> return "恢复页面参数"
        "groupReset" -> return "恢复整组默认值"; "itemsClear" -> return "清空选项"
        "itemAdd" -> return "追加选项"; "itemInsert" -> return "插入选项"; "itemSet" -> return "修改选项"; "itemRemove" -> return "删除选项"
    }
    val property = operation.removePrefix("get:")
    val label = when (property) {
        "backgroundImage" -> "背景图片"; "groupVisible" -> "整组可见"; "groupEnabled" -> "整组启用"; "groupCount" -> "组内控件数"
        "windowPosition" -> "窗口位置"; "bubblePosition" -> "界面浮球位置"; "hint" -> "占位提示"
        "minimum" -> "数值下限"; "maximum" -> "数值上限"; "step" -> "滑块步长"; "range" -> "数值范围"
        "config" -> "页面参数"; "pages" -> "页面列表"; "controls" -> "控件列表"
        "itemCount" -> "选项数量"; "selectedIndex" -> "单选索引"; "selectedIndices" -> "多选索引"
        "value" -> "控件值"; "text" -> "文字"; "visible" -> "可见性"; "enabled" -> "启用状态"; "items" -> "选项"; "progress" -> "进度"
        "x" -> "X位置"; "y" -> "Y位置"; "width" -> "宽度"; "height" -> "高度"; "fontPx" -> "字号"
        "textColor" -> "文字颜色"; "background" -> "背景颜色"; "alignment" -> "文字对齐"; "fontBold" -> "粗体"
        "textUnderline" -> "下划线"; "borderColor" -> "边框颜色"; "borderWidthPx" -> "边框宽度"; "cornerPx" -> "圆角"
        else -> property
    }
    return (if (operation.startsWith("get:")) "读取" else "设置") + label
}

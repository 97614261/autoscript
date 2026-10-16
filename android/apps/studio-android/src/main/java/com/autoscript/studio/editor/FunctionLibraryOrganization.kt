package com.autoscript.studio

import com.autoscript.studio.generated.BlockCategory

internal val FUNCTION_GROUP_ORDER = listOf(
    "插件", "变量", "文本", "集合", "计算", "按键", "屏幕", "图像", "文字",
    "判断", "循环", "跳转", "界面", "浮窗", "文件", "系统", "时间", "音频", "任务", "调试", "旧版兼容",
)

internal val SCRIPT_DIALOG_KINDS = listOf("ui.confirm", "ui.dialog", "ui.dialogclose")
internal fun scriptDialogInsertionHint(kind: String): String? = kind.takeIf { it in SCRIPT_DIALOG_KINDS }
    ?.let { "${FunctionCatalog.BLOCK_HINT_PREFIX}$it\n" }
private val IMAGE_PROCESSING_KINDS = setOf("screen.crop", "screen.transform", "screen.savepng")
private val CLOCK_KINDS = setOf("system.time", "system.elapsed")

internal fun functionGroup(kind: String, category: BlockCategory? = null): String = when (kind.substringBefore('.')) {
    "flow" -> "插件"
    "variable" -> "变量"
    "text" -> "文本"
    "data" -> "集合"
    "math" -> "计算"
    "input" -> "按键"
    "screen" -> if (kind in IMAGE_PROCESSING_KINDS || kind == "screen.loadimage") "图像" else "屏幕"
    "vision" -> "图像"
    "ocr" -> "文字"
    "ui" -> if (kind in SCRIPT_DIALOG_KINDS) "调试" else "界面"
    "floating" -> "浮窗"
    "files" -> "文件"
    "system" -> if (kind in CLOCK_KINDS) "时间" else "系统"
    "clipboard", "app" -> "系统"
    "date" -> "时间"
    "audio" -> "音频"
    "legacy" -> "旧版兼容"
    "timer" -> "任务"
    "control" -> when (kind) {
        "control.if" -> "判断"
        "control.repeat", "control.while", "control.cycle", "control.loopmetric", "control.loopcheck" -> "循环"
        else -> "跳转"
    }
    "task" -> if (kind in setOf("task.log", "task.prompt", "task.runprompt", "task.message", "task.comment",
            "task.logenabled", "task.promptenabled")) "调试" else "任务"
    else -> category?.let(FunctionCatalog::categoryLabel) ?: error("Unclassified function: $kind")
}

internal fun functionSection(kind: String): String = when {
    kind.startsWith("variable.") -> "赋值与计算"
    kind in setOf("control.repeat", "control.while", "control.cycle") -> "循环结构"
    kind in setOf("control.loopmetric", "control.loopcheck") -> "循环指标与退出"
    kind == "control.if" -> "条件分支"
    kind in setOf("flow.call", "flow.call.branch") -> "调用插件"
    kind.startsWith("flow.argument.") -> "调用参数"
    kind.startsWith("flow.return.") || kind in setOf("flow.return", "flow.status.return") -> "返回参数与状态"
    kind == "screen.loadimage" -> "图片读取"
    kind in setOf("data.get", "data.getjson", "data.length", "data.keys", "data.type", "data.has", "data.valid", "data.text") -> "读取与查询"
    kind.startsWith("data.") -> "集合修改"
    kind in SCRIPT_DIALOG_KINDS -> "对话框"
    kind in IMAGE_PROCESSING_KINDS -> "图像处理"
    kind in CLOCK_KINDS -> "时钟读取"
    kind.startsWith("date.") -> "日期计算"
    kind in setOf("task.prompt", "task.runprompt", "task.message", "task.log") -> "提示与日志"
    kind in setOf("task.promptenabled", "task.logenabled") -> "输出开关"
    kind == "task.comment" -> "文字说明"
    kind.startsWith("files.kv") -> "键值存储"
    kind in setOf("files.zip", "files.unzip", "files.tobase64", "files.frombase64") -> "编码与压缩"
    kind in setOf("files.list", "files.mkdir", "files.isdir", "files.rmdir", "files.directorystats") -> "目录"
    kind in setOf("files.read", "files.write", "files.append", "files.readline", "files.writeline",
        "files.insertline", "files.deleteline", "files.linecount") -> "文本文件"
    kind.startsWith("files.") -> "文件管理"
    kind.startsWith("text.regex") -> "正则"
    kind in setOf("text.contains", "text.startswith", "text.endswith", "text.count", "text.find", "text.length") -> "查找与判断"
    kind.startsWith("text.") -> "截取与处理"
    kind in setOf("vision.findimage", "vision.findgray", "vision.findallimages", "vision.result") -> "图片匹配"
    kind.startsWith("vision.") -> "颜色识别"
    kind.startsWith("legacy.") -> "旧工程兼容"
    else -> ""
}

internal fun functionSectionOrder(kind: String): Int = listOf(
    "", "赋值与计算", "条件分支", "循环结构", "循环指标与退出", "调用插件", "调用参数", "返回参数与状态",
    "读取与查询", "集合修改", "图片读取", "文件管理", "文本文件", "目录", "键值存储", "编码与压缩",
    "查找与判断", "截取与处理", "正则", "图片匹配", "颜色识别", "图像处理",
    "提示与日志", "对话框", "输出开关", "文字说明", "时钟读取", "日期计算", "旧工程兼容",
).indexOf(functionSection(kind))

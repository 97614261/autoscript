package com.autoscript.studio

import com.autoscript.studio.generated.BlockContract
import com.google.gson.JsonObject

/** Display only: no defaults, mutation, evaluation or generated Lua. */
internal fun compactProgramLabel(contract: BlockContract?, args: JsonObject?, fallbackKind: String,
    flowNames: Map<String, String>? = null, rowSummary: Boolean = false): String {
    if (contract == null) return compactDisplayText(fallbackKind)
    if (args == null) return contract.title
    val values = ProgramLabelArguments(args, rowSummary, flowNames)
    val title = values.title(contract)
    val details = values.details(contract)
    val visibleDetails = if (rowSummary) compactDisplayText(details, 64) else details
    return compactDisplayText(if (visibleDetails.isBlank()) title else "$title · $visibleDetails", 260)
}

private fun ProgramLabelArguments.title(contract: BlockContract): String {
    if (compact) when (contract.kind) {
        "ui.confirm" -> return "确认框"
        "vision.findimage" -> return "找图"
        "ocr.alphanumeric" -> return "英文数字V4"
        "ocr.alphanumericv5" -> return "英文数字V5"
        "task.message" -> return when (text("channel")) {
            "popup" -> "动态弹出"; "console" -> "动态日志"; "output" -> "动态提示"; else -> contract.title
        }
        "files.writeline" -> return "修改文件行"
        "files.insertline" -> return "插入文件行"
        "files.deleteline" -> return "删除文件行"
        "files.readline" -> return "读取文件行"
        "control.while" -> if (boolean("always") == true) return if (duration() == null) "保护循环" else "限时保护循环"
    }
    return when (contract.kind) {
    "control.cycle" -> if (duration() == null) "无限循环" else "限时循环"
    "control.repeat" -> "次数循环"
    "control.while" -> if (boolean("always") == true) {
        if (duration() == null) "循环（次数保护）" else "限时循环（次数保护）"
    } else "条件循环"
    "control.if" -> "条件判断"
    "control.loopcheck" -> when (text("metric")) { "count" -> "达到次数跳出"; "elapsed" -> "达到时长跳出"; else -> contract.title }
    "control.loopmetric" -> when (text("metric")) { "count" -> "读取循环次数"; "elapsed" -> "读取循环耗时"; else -> contract.title }
    "flow.call.branch" -> "调用插件"
    "flow.status.return" -> when (boolean("success")) { true -> "返回成功"; false -> "返回失败"; null -> "返回成功 / 失败" }
    "vision.findallimages" -> "多结果找图"
    "vision.findgray" -> "灰度找图"
    "ocr.alphanumeric" -> "英文数字识别（V4）"
    "ocr.alphanumericv5" -> "英文数字识别（V5）"
    "screen.capture" -> "截图"
    "screen.captureregion" -> "区域截图"
    "screen.transform" -> transformTitle(text("mode")) ?: contract.title
    "math.unary", "math.binary" -> if (variable("operationVariable") == null) mathOperationTitle(text("operation")) ?: contract.title else contract.title
    "task.promptenabled" -> when (boolean("enabled")) { true -> "开启运行提示"; false -> "关闭运行提示"; null -> contract.title }
    "task.logenabled" -> when (boolean("enabled")) { true -> "开启脚本日志"; false -> "关闭脚本日志"; null -> contract.title }
    else -> contract.title.substringBefore(" · ")
    }
}

private fun ProgramLabelArguments.details(contract: BlockContract): String = when (contract.kind) {
    "input.tap", "input.pointerdown", "input.pointermove" -> point("x", "y")
    "input.swipe" -> parts("${point("x1", "y1")} → ${point("x2", "y2")}", "${input("durationMs")}ms")
    "input.touchdown", "input.touchmove" -> parts("触点 ${input("pointerId")}", point("x", "y"), output())
    "input.touchup" -> parts("触点 ${input("pointerId")}", output())
    "input.keyevent" -> keyLabel(text("keyCode"))
    "input.pastetext", "input.imetext", "clipboard.settext" -> parts(input("text", quoted = true), output())
    "input.record" -> parts("${input("durationMs")}ms", text("eventBudget")?.let { "最多 $it 动作" }, output())
    "input.playrecording" -> variable("recordingVariable") ?: "未设置动作来源"
    "variable.set" -> "${brief(text("name") ?: "?")} = ${brief(scalar("value"))}"
    "variable.copy" -> "${brief(text("name") ?: "?")} ← ${brief(text("sourceName") ?: "?")}"
    "variable.calculate" -> "${brief(text("name") ?: "?")} = ${brief(text("expression") ?: "?")}"
    "task.sleep" -> parts("${input("milliseconds")}${if (compact) "" else " "}ms", if (boolean("exactTiming") == true) "精确计时" else null)
    "task.message" -> parts(if (compact && text("channel") in setOf("popup", "output", "console")) null else enum("channel"),
        text("expression") ?: "未设置表达式")
    "task.runprompt", "task.prompt", "task.comment" -> brief(variable("valueVariable") ?: text("message").orEmpty())
    "task.log" -> parts(enum("level"), variable("valueVariable") ?: text("message"))
    "task.promptenabled", "task.logenabled" -> if (boolean("enabled") == null) "未设置" else ""
    "control.label", "control.goto" -> text("name") ?: "未设置标记"
    "control.repeat" -> parts("${input("times")} 次", text("indexVariable")?.let { "次数→$it" })
    "control.if" -> parts(comparison(), arraySize("elseIf")?.takeIf { it > 0 }?.let { "$it 个否则如果" })
    "control.while" -> parts(if (boolean("always") == true) null else comparison(), duration(),
        text("maxIterations")?.let { "保护上限 $it 次" })
    "control.cycle" -> parts(duration(), text("iterationVariable")?.let { "次数→$it" }, text("elapsedVariable")?.let { "耗时→$it" })
    "control.loopcheck" -> "${input("limit")} ${if (text("metric") == "count") "次" else timeUnit(text("unit"))}"
    "control.loopmetric" -> parts(if (text("metric") == "elapsed") timeUnit(text("unit")) else null, output("name"))
    "flow.call", "flow.call.branch", "task.spawn", "timer.every" -> parts(pluginName(),
        if (contract.kind == "timer.every") text("periodMs")?.let { "每 ${it}ms" } else null,
        objectSize("arguments")?.takeIf { it > 0 }?.let { if (compact) "$it 参" else "$it 个参数" }, output())
    "flow.argument.get", "flow.return.get" -> parts("第 ${text("index") ?: "?"} 项", output("targetVariable"))
    "flow.argument.set", "flow.return.set" -> "第 ${text("index") ?: "?"} 项 = ${input("value", quoted = true)}"
    "flow.status.return" -> if (boolean("success") == null) "未设置" else ""
    "task.cancel", "timer.cancel" -> variable("idVariable") ?: "未设置任务"
    "ui.confirm" -> if (compact) parts(text("message") ?: text("title"), output())
        else parts(text("title")?.let { "标题 $it" }, text("message"), output())
    "ui.dialog" -> parts(text("pageId")?.let { "页面 $it" }, text("timeoutMs")?.let { "超时 ${it}ms" }, output())
    "ui.command" -> parts(text("controlId"), uiOperationSummary(text("operation")),
        if (uiOperationUsesValue(text("operation"))) input("value", quoted = true) else null, output())
    "ui.get" -> parts(text("controlId"), output())
    "ui.set" -> "${text("controlId") ?: "?"} ← ${input("value", quoted = true)}"
    "floating.image" -> parts(input("name"), fileInput("imagePath"),
        variable("draggableVariable")?.let { "可拖动=$it" } ?: boolean("draggable")?.let { if (it) "可拖动" else "不可拖动" }, output())
    "floating.show" -> parts(text("name"), variable("textVariable") ?: text("text") ?: fileInput("imagePath"))
    "floating.closeimage", "floating.waitevent", "floating.bounds", "floating.hide", "floating.close" -> parts(
        input("name"), if (contract.kind == "floating.waitevent") "等待 ${input("timeoutMs")} ms" else null, output())
    "network.request" -> parts(input("method"), input("url"), output())
    "app.openurl" -> parts(input("url"), output())
    "system.screensize" -> parts(text("widthVariable")?.let { "宽→$it" }, text("heightVariable")?.let { "高→$it" })
    else -> visionDetails(contract.kind) ?: utilityDetails(contract.kind) ?: genericDetails(contract)
}

internal fun applyProgramDisplayLabel(defaultLabel: String, settings: com.autoscript.studio.compiler.NodeDisplaySettings): String {
    if (settings.alias.isBlank()) return defaultLabel
    val details = defaultLabel.substringAfter(" · ", "")
    return settings.alias + if (settings.showParameters && details.isNotBlank()) " · $details" else ""
}

/** Bound display work and prevent newlines/control characters from creating extra rows. */
internal fun compactDisplayText(value: String, limit: Int = 80): String {
    val prefix = value.take(limit).map { if (it.isISOControl() || it == '\u2028' || it == '\u2029') ' ' else it }.joinToString("")
    return prefix + if (value.length > limit) "…" else ""
}

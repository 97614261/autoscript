package com.autoscript.studio

import com.autoscript.studio.generated.BlockContract
import com.google.gson.JsonElement
import com.google.gson.JsonObject

/** Display only: no defaults, mutation, evaluation or generated Lua. */
internal fun compactProgramLabel(contract: BlockContract?, args: JsonObject?, fallbackKind: String): String {
    if (contract == null) return compactDisplayText(fallbackKind)
    if (args == null) return contract.title
    fun text(key: String): String? = args.get(key)?.takeIf { it.isJsonPrimitive }
        ?.let { compactDisplayText(it.asString) }?.takeIf(String::isNotBlank)
    fun scalar(key: String): String = args.get(key)?.let(::compactScalar) ?: "?"
    fun point(x: String, y: String) = "(${text(x) ?: "?"}, ${text(y) ?: "?"})"
    fun fromVariable(key: String): String? = text(key)?.let { "变量 $it" }
    fun duration(): String? = fromVariable("durationVariable")?.let {
        val unit = when (text("durationUnit")) { "seconds" -> "秒"; "minutes" -> "分钟"; else -> "ms" }
        "限时 $it $unit"
    } ?: text("durationMs")?.let { "限时 ${it}ms" }
    fun comparison(): String {
        if (args.has("condition")) return "复合条件"
        val op = when (text("operator")) {
            "equals" -> "="; "notEquals" -> "≠"; "lessThan" -> "<"
            "lessOrEqual" -> "≤"; "greaterThan" -> ">"; "greaterOrEqual" -> "≥"
            else -> text("operator") ?: "?"
        }
        return "${text("variable") ?: "?"} $op ${fromVariable("valueVariable") ?: scalar("value")}"
    }
    val details = when (contract.kind) {
        "input.tap", "input.pointerdown", "input.pointermove" -> point("x", "y")
        "input.swipe" -> "${point("x1", "y1")} → ${point("x2", "y2")} · ${text("durationMs") ?: "?"}ms"
        "input.keyevent" -> "键码 ${text("keyCode") ?: "?"}"
        "variable.set" -> "${text("name") ?: "?"} = ${scalar("value")}"
        "variable.copy" -> "${text("name") ?: "?"} ← ${text("sourceName") ?: "?"}"
        "variable.calculate" -> "${text("name") ?: "?"} = ${text("expression") ?: "?"}"
        "task.sleep" -> "${fromVariable("millisecondsVariable") ?: text("milliseconds") ?: "?"}ms"
        "task.runprompt" -> listOfNotNull(fromVariable("valueVariable") ?: text("message"), "持续显示").joinToString(" · ")
        "task.prompt" -> listOfNotNull(fromVariable("valueVariable") ?: text("message"), "到期消失").joinToString(" · ")
        "task.comment", "task.log", "task.message" ->
            fromVariable("valueVariable") ?: text("message").orEmpty()
        "control.label", "control.goto" -> text("name").orEmpty()
        "control.repeat" -> "${fromVariable("timesVariable") ?: text("times") ?: "?"} 次"
        "control.if" -> comparison()
        "control.while" -> listOfNotNull(
            if (text("always") == "true") "持续循环" else comparison(), duration(),
            text("maxIterations")?.let { "上限 $it 次" },
        ).joinToString(" · ")
        "control.cycle" -> duration() ?: "持续循环"
        "vision.findimage", "vision.findallimages", "vision.findimagecv" -> {
            val templates = when {
                text("imageDirectory") != null -> "目录 ${text("imageDirectory")?.substringAfterLast('/')}"
                args.get("imagePaths")?.isJsonArray == true -> "${args.getAsJsonArray("imagePaths").size()} 张模板"
                text("imagePath") != null -> text("imagePath")?.substringAfterLast('/')
                else -> null
            }
            listOfNotNull(templates, text("resultVariable")?.let { "→ $it" }).joinToString(" · ")
        }
        else -> contract.properties.asSequence().mapNotNull { property ->
            val value = args.get(property.path) ?: return@mapNotNull null
            if (!value.isJsonPrimitive || property.path in setOf("frameVariable", "autoCapture")) return@mapNotNull null
            val display = compactDisplayText(value.asString).takeIf(String::isNotBlank) ?: return@mapNotNull null
            "${property.label.substringBefore('(').substringBefore('（')} $display"
        }.take(2).joinToString(" · ")
    }
    return if (details.isBlank()) contract.title else "${contract.title} · $details"
}

internal fun applyProgramDisplayLabel(defaultLabel: String, settings: com.autoscript.studio.compiler.NodeDisplaySettings): String {
    if (settings.alias.isBlank()) return defaultLabel
    val details = defaultLabel.substringAfter(" · ", "")
    return settings.alias + if (settings.showParameters && details.isNotBlank()) " · $details" else ""
}

private fun compactScalar(value: JsonElement): String = when {
    value.isJsonNull -> "null"
    !value.isJsonPrimitive -> "结构化值"
    value.asJsonPrimitive.isString -> "\"${compactDisplayText(value.asString)}\""
    else -> compactDisplayText(value.asString)
}

/** Bound display work and prevent newlines/control characters from creating extra rows. */
private fun compactDisplayText(value: String): String {
    val prefix = value.take(80).map { if (it.isISOControl()) ' ' else it }.joinToString("")
    return prefix + if (value.length > 80) "…" else ""
}

package com.autoscript.studio

import com.autoscript.studio.generated.BlockContract
import com.google.gson.JsonElement
import com.google.gson.JsonObject

/** Read-only, bounded summaries; never resolve variables or evaluate user expressions. */
internal class ProgramLabelArguments(private val args: JsonObject, val compact: Boolean = false,
    private val flowNames: Map<String, String>? = null) {
    fun text(key: String): String? = args.get(key)?.takeIf { it.isJsonPrimitive }
        ?.let { compactDisplayText(it.asString) }?.takeIf(String::isNotBlank)

    fun boolean(key: String): Boolean? = args.get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean
    fun arraySize(key: String): Int? = args.get(key)?.takeIf { it.isJsonArray }?.asJsonArray?.size()
    fun objectSize(key: String): Int? = args.get(key)?.takeIf { it.isJsonObject }?.asJsonObject?.size()
    fun additionalColorCount(): Int? = args.get("additionalColors")?.takeIf { it.isJsonPrimitive }?.asString
        ?.takeIf { it.isNotBlank() && it.length <= 164 }?.let { value -> 1 + value.count { it == ';' } }
    fun variable(key: String): String? = text(key)?.let { if (compact) "\$$it" else "变量 $it" }
    fun scalar(key: String): String = args.get(key)?.let(::scalarValue) ?: "?"
    fun input(key: String, quoted: Boolean = false): String = variable("${key}Variable")
        ?: if (quoted) scalar(key) else text(key) ?: "?"
    fun output(key: String = "resultVariable"): String? = text(key)?.let { if (compact) "→$it" else "→ $it" }
    fun enum(key: String): String? = text(key)?.let { programEnumLabel(key, it) }
    fun point(x: String, y: String): String = "(${input(x)},${if (compact) "" else " "}${input(y)})"
    fun fileInput(key: String): String? = variable("${key}Variable") ?: args.get(key)
        ?.takeIf { it.isJsonPrimitive }?.asString?.takeIf(String::isNotBlank)
        ?.let { compactDisplayText(it.substringAfterLast('/').substringAfterLast('\\')) }

    fun duration(): String? = variable("durationVariable")?.let { "${if (compact) "" else "限时 "}$it ${timeUnit(text("durationUnit"))}" }
        ?: text("durationMs")?.let { "${if (compact) "" else "限时 "}${it}ms" }

    fun pluginName(): String {
        // Resolve the original identity, never a truncated display value or a guessed filename.
        val id = args.get("targetFlowId")?.takeIf { it.isJsonPrimitive }?.asString?.takeIf(String::isNotBlank)
            ?: return "未选择插件"
        return flowNames?.get(id)?.takeIf(String::isNotBlank)?.let { compactDisplayText(it) }
            ?: if (flowNames == null) "插件" else "插件不存在"
    }

    fun parts(vararg values: String?): String {
        val present = values.filterNotNull().filter(String::isNotBlank)
        return if (compact) present.take(4).joinToString(" · ") { compactDisplayText(it, 16) }
            else present.joinToString(" · ")
    }

    fun brief(value: String): String = if (compact) compactDisplayText(value, 16) else value

    fun comparison(): String {
        if (args.has("condition")) {
            val condition = args.get("condition")?.takeIf { it.isJsonObject }?.asJsonObject
                ?: return "复合条件（未完成）"
            val values = ProgramLabelArguments(condition, compact)
            return when (values.text("type")) {
                "compare" -> values.simpleComparison()
                "all", "any" -> values.arraySize("conditions")?.takeIf { it > 0 }?.let {
                    "${if (values.text("type") == "all") "全部满足" else "任一满足"}（$it 项条件）"
                } ?: "复合条件（未完成）"
                "not" -> "条件取反"
                else -> "复合条件（未完成）"
            }
        }
        return simpleComparison()
    }

    private fun simpleComparison(): String = "${text("variable") ?: "?"} ${enum("operator") ?: "?"} ${input("value", quoted = true)}"

    fun region(useEdgeVariables: Boolean = false): String? {
        val region = args.get("region")
        if (region != null) {
            if (!region.isJsonObject) return "区域未完成"
            if (compact) return "选区"
            val fixed = ProgramLabelArguments(region.asJsonObject)
            if (!useEdgeVariables) return fixed.rectangle()
            fun edge(key: String) = variable("${key}Variable") ?: fixed.input(key)
            return "区域 (${edge("left")}, ${edge("top")})–(${edge("right")}, ${edge("bottom")})"
        }
        return if (args.has("left") || args.has("top") || args.has("right") || args.has("bottom")) {
            if (compact) "选区" else rectangle()
        } else null
    }

    private fun rectangle(): String = "区域 (${input("left")}, ${input("top")})–(${input("right")}, ${input("bottom")})"

    fun legacyRegion(): String? = args.get("region")?.takeIf { it.isJsonObject }?.asJsonObject?.let {
        val value = ProgramLabelArguments(it)
        "起点 ${value.point("left", "top")}，宽高 ${value.input("width")} × ${value.input("height")}"
    }

    fun pointArgument(): String = args.get("point")?.takeIf { it.isJsonObject }?.asJsonObject
        ?.let { ProgramLabelArguments(it, compact).point("x", "y") } ?: "(?, ?)"

    fun color(key: String): String? {
        variable("${key}Variable")?.let { return it }
        val value = text(key) ?: return null
        val number = value.toIntOrNull()?.takeIf { it in 0..0xFFFFFF } ?: return value
        return "#" + number.toString(16).uppercase(java.util.Locale.ROOT).padStart(6, '0')
    }

    fun score(key: String): String? = text(key)?.let { value ->
        val number = value.toIntOrNull()
        if (number == null) value else "${number / 10}${if (number % 10 == 0) "" else ".${number % 10}"}%"
    }

    fun genericDetails(contract: BlockContract): String {
        val inputs = contract.properties.asSequence().filter { property ->
            property.path !in setOf("frameVariable", "autoCapture", "resultVariable", "targetVariable") &&
                !property.path.endsWith("Variable")
        }.mapNotNull { property ->
            val source = variable("${property.path}Variable")
            val value = args.get(property.path)
            if (source == null && (value == null || value.isJsonNull || (value.isJsonPrimitive && value.asString.isBlank()))) return@mapNotNull null
            val display = source ?: if (value!!.isJsonPrimitive && value.asJsonPrimitive.isBoolean) {
                if (value.asBoolean) "是" else "否"
            } else if (value.isJsonPrimitive) programEnumLabel(property.path, value.asString)
                else if (value.isJsonArray) "${value.asJsonArray.size()} 项" else "已配置"
            if (compact) compactDisplayText(display, 16)
            else "${property.label.substringBefore('(').substringBefore('（')} ${compactDisplayText(display)}"
        }.take(if (compact) 1 else 2).toList()
        return parts(*inputs.toTypedArray(), output() ?: output("targetVariable"))
    }

    private fun scalarValue(value: JsonElement): String = when {
        value.isJsonNull -> "null"
        !value.isJsonPrimitive -> "结构化值"
        value.asJsonPrimitive.isString -> "\"${compactDisplayText(value.asString)}\""
        value.asJsonPrimitive.isBoolean -> if (value.asBoolean) "真" else "假"
        else -> compactDisplayText(value.asString)
    }
}

internal fun parts(vararg values: String?): String = values.filterNotNull().filter(String::isNotBlank).joinToString(" · ")
internal fun timeUnit(unit: String?): String = when (unit) { null, "milliseconds" -> "ms"; "seconds" -> "秒"; "minutes" -> "分钟"; else -> unit }

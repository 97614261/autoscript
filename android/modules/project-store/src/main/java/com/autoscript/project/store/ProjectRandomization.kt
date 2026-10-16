package com.autoscript.project.store

import com.google.gson.JsonElement
import com.google.gson.JsonObject

/** Actual screen-pixel offsets, after the shared coordinate transform. */
data class PositionRandomization(val enabled: Boolean = false, val xPx: Int = 0, val yPx: Int = 0) {
    fun valid() = xPx in 0..4096 && yPx in 0..4096
}
data class WaitRandomization(val enabled: Boolean = false, val unit: String = "milliseconds", val min: Int = 0, val max: Int = 0) {
    fun valid(): Boolean {
        val limit = when (unit) { "milliseconds" -> 60_000; "percent" -> 100; else -> return false }
        return min >= -limit && max <= limit && min <= max
    }
}
data class ProjectRandomization(val touch: PositionRandomization = PositionRandomization(),
    val recognition: PositionRandomization = PositionRandomization(), val wait: WaitRandomization = WaitRandomization()) {
    fun valid() = touch.valid() && recognition.valid() && wait.valid()
}

internal fun validateRandomizationJson(value: JsonElement) {
    fun objectFields(element: JsonElement, keys: Set<String>): JsonObject {
        require(element.isJsonObject && element.asJsonObject.keySet() == keys) { "随机策略字段无效" }
        return element.asJsonObject
    }
    fun integer(json: JsonObject, key: String): Int {
        val number = json[key]
        require(number.isJsonPrimitive && number.asJsonPrimitive.isNumber) { "随机范围必须是整数" }
        return try { number.asBigDecimal.intValueExact() } catch (_: ArithmeticException) { throw IllegalArgumentException("随机范围超限或不是整数") }
    }
    fun enabled(json: JsonObject) { require(json["enabled"].isJsonPrimitive && json["enabled"].asJsonPrimitive.isBoolean) { "随机开关必须是布尔值" } }
    val root = objectFields(value, setOf("touch", "recognition", "wait"))
    for (key in listOf("touch", "recognition")) {
        val point = objectFields(root[key],setOf("enabled", "xPx", "yPx")); enabled(point)
        require(PositionRandomization(false,integer(point,"xPx"),integer(point,"yPx")).valid()) { "随机偏移超限" }
    }
    val wait = objectFields(root["wait"],setOf("enabled", "unit", "min", "max")); enabled(wait)
    require(wait["unit"].isJsonPrimitive && wait["unit"].asJsonPrimitive.isString) { "等待随机单位无效" }
    require(WaitRandomization(false,wait["unit"].asString,integer(wait,"min"),integer(wait,"max")).valid()) { "等待随机范围无效" }
}

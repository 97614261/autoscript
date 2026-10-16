package com.autoscript.studio.compiler

import com.google.gson.JsonElement
import com.google.gson.JsonObject

/** Strict envelope validation, independent of UI and project-store dependencies. */
object NodeRandomizationSettings {
    fun supports(kind: String) = kind in setOf("input.tap", "input.swipe", "input.pointerdown",
        "task.sleep", "vision.findimage", "vision.findallimages", "vision.findgray", "ocr.alphanumeric")
    fun validate(value: JsonElement?) {
        if (value == null) return
        val node = objectFields(value, setOf("mode", "position", "wait"))
        require(node["mode"].isJsonPrimitive && node["mode"].asJsonPrimitive.isString && node["mode"].asString in setOf("follow", "disabled", "custom")) { "随机模式无效" }
        position(node["position"]); wait(node["wait"])
    }
    fun position(value: JsonElement) {
        val json = objectFields(value, setOf("enabled", "xPx", "yPx"))
        boolean(json, "enabled"); require(integer(json, "xPx") in 0..4096 && integer(json, "yPx") in 0..4096) { "偏移范围应为0～4096px" }
    }
    fun wait(value: JsonElement) {
        val json = objectFields(value, setOf("enabled", "unit", "min", "max")); boolean(json, "enabled")
        require(json["unit"].isJsonPrimitive && json["unit"].asJsonPrimitive.isString) { "等待单位无效" }
        val limit = when (json["unit"].asString) { "milliseconds" -> 60_000; "percent" -> 100; else -> throw IllegalArgumentException("等待单位无效") }
        val min = integer(json, "min"); val max = integer(json, "max")
        require(min >= -limit && max <= limit && min <= max) { "等待浮动范围无效" }
    }
    private fun objectFields(value: JsonElement, fields: Set<String>): JsonObject {
        require(value.isJsonObject && value.asJsonObject.keySet() == fields) { "随机策略字段无效" }
        return value.asJsonObject
    }
    private fun boolean(json: JsonObject, key: String) { require(json[key].isJsonPrimitive && json[key].asJsonPrimitive.isBoolean) { "随机开关必须是布尔值" } }
    private fun integer(json: JsonObject, key: String): Int {
        require(json[key].isJsonPrimitive && json[key].asJsonPrimitive.isNumber) { "随机范围必须是整数" }
        return try { json[key].asBigDecimal.intValueExact() } catch (_: ArithmeticException) { throw IllegalArgumentException("随机范围必须是有界整数") }
    }
}

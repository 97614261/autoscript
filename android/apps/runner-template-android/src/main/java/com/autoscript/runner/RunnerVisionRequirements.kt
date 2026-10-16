package com.autoscript.runner

import com.google.gson.JsonParser
import com.google.gson.JsonObject

/** Reads original digest-verified bytes; do not round-trip JSON numbers through JSONObject. */
internal fun runnerVisionRequirements(sourceMap: ByteArray): Set<String>? {
    require(sourceMap.size <= 8 * 1024 * 1024) { "发布 source map 超出预算" }
    val root = JsonParser.parseString(sourceMap.toString(Charsets.UTF_8)).asJsonObject
    return runnerVisionRequirements(root)
}

private fun runnerVisionRequirements(root: JsonObject): Set<String>? {
    if (!root.has("visionRequirements")) return null
    val encoded = root.get("visionRequirements")
    require(encoded.isJsonObject) { "发布视觉需求格式无效" }
    val value = encoded.asJsonObject
    require(value.keySet() == setOf("version", "components")) { "发布视觉需求字段无效" }
    val version = value.get("version")
    require(version.isJsonPrimitive && version.asJsonPrimitive.isNumber && version.toString() == "1") { "发布视觉需求版本不支持" }
    val array = value.get("components")
    require(array.isJsonArray && array.asJsonArray.size() <= 2) { "发布视觉需求数量无效" }
    val components = array.asJsonArray.map { item ->
        require(item.isJsonPrimitive && item.asJsonPrimitive.isString) { "发布视觉需求标识无效" }
        item.asString.also { require(it == "opencv" || it == "onnx") { "发布视觉组件不支持" } }
    }
    require(components == listOf("opencv", "onnx").filter { it in components }) { "发布视觉需求重复或顺序无效" }
    return components.toSet()
}

/** Pure reader used by the actual digest-verified release loader and JVM regression tests. */
internal fun parseRunnerSourceMap(bytes: ByteArray): RunnerSourceMap {
    require(bytes.size <= 8 * 1024 * 1024) { "发布 source map 超出预算" }
    val encoded = JsonParser.parseString(bytes.toString(Charsets.UTF_8))
    require(encoded.isJsonObject) { "发布 source map 格式无效" }
    val root = encoded.asJsonObject
    val keys = setOf("schemaVersion", "generationId", "entries") + if (root.has("visionRequirements")) setOf("visionRequirements") else emptySet()
    require(root.keySet() == keys) { "发布 source map 字段无效" }
    fun integer(value: JsonObject, name: String): Int {
        val item = value.get(name)
        require(item.isJsonPrimitive && item.asJsonPrimitive.isNumber && Regex("0|[1-9][0-9]*").matches(item.toString())) { "source map 整数无效" }
        return requireNotNull(item.asString.toIntOrNull()) { "source map 整数超出范围" }
    }
    fun text(value: JsonObject, name: String): String {
        val item = value.get(name)
        require(item.isJsonPrimitive && item.asJsonPrimitive.isString) { "source map 标识无效" }
        return item.asString.also { require(it.isNotEmpty() && it.codePointCount(0, it.length) <= 128 && it.none(Char::isISOControl)) { "source map 标识无效" } }
    }
    require(integer(root, "schemaVersion") == 1) { "不支持的 source map 版本" }
    val generation = text(root, "generationId")
    require(Regex("gen-[0-9a-f]{32}").matches(generation)) { "source map 代次无效" }
    val array = root.get("entries")
    require(array.isJsonArray && array.asJsonArray.size() <= 100_000) { "source map 条目超出预算" }
    val entries = array.asJsonArray.map { item ->
        require(item.isJsonObject) { "source map 条目格式无效" }
        val value = item.asJsonObject
        require(value.keySet() == setOf("flowId", "nodeId", "luaStartLine", "luaEndLine")) { "source map 条目字段无效" }
        val start = integer(value, "luaStartLine"); val end = integer(value, "luaEndLine")
        require(start in 1..end) { "source map 行范围无效" }
        RunnerSourceMapEntry(text(value, "flowId"), text(value, "nodeId"), start, end)
    }
    return RunnerSourceMap(generation, entries, runnerVisionRequirements(root))
}

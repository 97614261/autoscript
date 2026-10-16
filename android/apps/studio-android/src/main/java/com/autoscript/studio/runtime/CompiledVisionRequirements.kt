package com.autoscript.studio

import com.google.gson.JsonObject

/** Called only for a digest-verified source map. Null means historical/unknown. */
internal fun compiledVisionRequirements(sourceMap: JsonObject): Set<String>? {
    if (!sourceMap.has("visionRequirements")) return null
    val encoded = sourceMap.get("visionRequirements")
    require(encoded.isJsonObject) { "视觉组件需求格式无效，请重新编译" }
    val value = encoded.asJsonObject
    require(value.keySet() == setOf("version", "components")) { "视觉组件需求字段无效，请重新编译" }
    val version = value.get("version")
    require(version.isJsonPrimitive && version.asJsonPrimitive.isNumber && version.toString() == "1") {
        "视觉组件需求版本不支持，请重新编译"
    }
    val array = value.get("components")
    require(array.isJsonArray && array.asJsonArray.size() <= 2) { "视觉组件需求数量无效，请重新编译" }
    val components = array.asJsonArray.map { item ->
        require(item.isJsonPrimitive && item.asJsonPrimitive.isString) { "视觉组件需求标识无效，请重新编译" }
        item.asString.also { require(it == "opencv" || it == "onnx") { "视觉组件需求不支持，请重新编译" } }
    }
    require(components == listOf("opencv", "onnx").filter { it in components }) {
        "视觉组件需求重复或顺序无效，请重新编译"
    }
    return components.toSet()
}

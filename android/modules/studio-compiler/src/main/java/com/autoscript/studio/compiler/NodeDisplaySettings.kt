package com.autoscript.studio.compiler

import com.google.gson.JsonElement
import com.google.gson.JsonObject

data class NodeDisplaySettings(val alias: String = "", val showParameters: Boolean = true) {
    fun isValid(): Boolean = alias.codePointCount(0, alias.length) <= 80 && alias.indices.all { index ->
        val ch = alias[index]
        !Character.isISOControl(ch) && when {
            Character.isHighSurrogate(ch) -> index + 1 < alias.length && Character.isLowSurrogate(alias[index + 1])
            Character.isLowSurrogate(ch) -> index > 0 && Character.isHighSurrogate(alias[index - 1])
            else -> true
        }
    }
    fun toJson(): JsonObject {
        require(isValid()) { "别名最多80字，不允许控制字符" }
        return JsonObject().apply {
            addProperty("alias", alias)
            addProperty("showParameters", showParameters)
        }
    }
    companion object {
        fun parse(value: JsonElement?): NodeDisplaySettings {
            if (value == null) return NodeDisplaySettings()
            require(value.isJsonObject) { "display 必须是对象" }
            val json = value.asJsonObject
            require(json.keySet() == setOf("alias", "showParameters")) { "display 字段无效" }
            require(json["alias"].isJsonPrimitive && json["alias"].asJsonPrimitive.isString &&
                json["showParameters"].isJsonPrimitive && json["showParameters"].asJsonPrimitive.isBoolean) { "显示设置类型无效" }
            return NodeDisplaySettings(json["alias"].asString, json["showParameters"].asBoolean).also {
                require(it.isValid()) { "别名最多80字，不允许控制字符" }
            }
        }
    }
}

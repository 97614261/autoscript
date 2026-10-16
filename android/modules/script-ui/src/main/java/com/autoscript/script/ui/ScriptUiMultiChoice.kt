package com.autoscript.script.ui

import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.StringReader

/** JSON, not delimiter-separated text: option punctuation is meaningful. */
fun uiSelections(value: String, options: List<String>, required: Boolean = false): List<String> {
    require(value.length <= 2048) { "多选值超过2048字符" }
    val selected = try {
        JsonReader(StringReader(value)).use { reader ->
            require(reader.peek() == JsonToken.BEGIN_ARRAY) { "多选值必须为数组" }
            reader.beginArray()
            val result = mutableListOf<String>()
            while (reader.hasNext()) {
                require(result.size < 64 && reader.peek() == JsonToken.STRING) { "多选值必须为最多64项的字符串数组" }
                result.add(reader.nextString())
            }
            reader.endArray()
            require(reader.peek() == JsonToken.END_DOCUMENT) { "多选值含额外数据" }
            result
        }
    } catch (error: java.io.IOException) { throw IllegalArgumentException("多选值必须为JSON字符串数组", error) }
    require(selected.distinct().size == selected.size && selected.all { it in options } && (!required || selected.isNotEmpty())) { "多选项无效、重复或为空" }
    return selected
}

fun uiSelectionsJson(selected: List<String>): String = canonicalUiJson(com.google.gson.Gson().toJsonTree(selected)).also { require(it.length <= 2048) { "多选值超过2048字符" } }

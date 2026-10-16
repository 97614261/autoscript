package com.autoscript.studio

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser

internal fun visionSamplesFromSelection(selection: ImageToolCodeGen.VisualSelection): String? {
    val anchor = selection.points.firstOrNull() ?: return null
    return JsonArray().apply { selection.points.take(64).forEach { point -> add(JsonObject().apply {
        addProperty("dx", point.x - anchor.x); addProperty("dy", point.y - anchor.y)
        addProperty("rgb", point.rgb and 0xFFFFFF); addProperty("tolerance", selection.tolerance)
    }) } }.toString()
}

/** Invalid numeric text stays in the JSON draft as a string and must fail normal validation. */
internal fun updateVisionSample(value: String, row: Int, key: String, text: String): String {
    require(key in setOf("dx", "dy", "rgb", "tolerance"))
    val array = JsonParser.parseString(value).asJsonArray.deepCopy()
    require(row in 0 until array.size() && array.size() <= 64)
    val numeric = if (key == "rgb" && text.startsWith("#")) text.removePrefix("#").takeIf { it.length == 6 }?.toIntOrNull(16) else text.toIntOrNull()
    if (numeric == null) array[row].asJsonObject.addProperty(key, text) else array[row].asJsonObject.addProperty(key, numeric)
    return array.toString()
}

internal fun visionSampleError(value:String):String? {
    if(value.toByteArray(Charsets.UTF_8).size>16384) return "色点数据：超过16384字节"
    val rows=runCatching { JsonParser.parseString(value).asJsonArray }.getOrNull() ?: return "色点JSON：须为数组"
    if(rows.size() !in 1..64) return "色点数量：须为1–64"
    rows.forEachIndexed { index,item ->
        if(!item.isJsonObject || item.asJsonObject.keySet()!=setOf("dx","dy","rgb","tolerance")) return "第${index+1}个色点：须包含且仅包含ΔX、ΔY、RGB、容差"
        for((key,range) in listOf("dx" to -32768..32768,"dy" to -32768..32768,"rgb" to 0..16777215,"tolerance" to 0..255)) {
            val primitive=item.asJsonObject[key]
            val number=primitive.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asString
            val n=number?.takeIf { it.matches(Regex("-?(0|[1-9][0-9]*)")) }?.toLongOrNull()
            if(n==null || n !in range.first.toLong()..range.last.toLong()) return "第${index+1}个色点 · ${mapOf("dx" to "ΔX","dy" to "ΔY","rgb" to "RGB","tolerance" to "容差")[key]}：须为${range.first}–${range.last}整数"
        }
    }
    return null
}

@Composable
internal fun VisionSampleEditor(value: String, onChange: (String) -> Unit) {
    var raw by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    fun change(next:String) { if(next.toByteArray(Charsets.UTF_8).size<=16384) {onChange(next);error=null} else error="色点数据最多16384字节，未接受超长输入" }
    val rows = runCatching { JsonParser.parseString(value).asJsonArray.takeIf { a -> a.size() <= 64 && a.all { it.isJsonObject } } }.getOrNull()
    Row(Modifier.fillMaxWidth()) {
        Text("色点 ${rows?.size() ?: "?"}/64 · 首点为锚点", Modifier.weight(1f), fontSize = 11.sp)
        DesignerAction(if (raw) "点表" else "原始JSON", onClick = { raw = !raw })
    }
    if (raw || rows == null) {
        DesignerInput("色点JSON（无效输入会保留）", value, true, Modifier.fillMaxWidth()) { change(it) }
        if (rows == null) Text("当前数据无法显示为点表；修正JSON后再切换，不会自动清空。", fontSize = 10.sp)
    } else {
        Column(Modifier.fillMaxWidth().heightIn(max = 220.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            rows.forEachIndexed { index, item ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    listOf("dx" to "ΔX", "dy" to "ΔY", "rgb" to "RGB/#HEX", "tolerance" to "容差").forEach { (key, label) ->
                        DesignerInput("${index + 1} $label", item.asJsonObject[key]?.takeIf { it.isJsonPrimitive }?.asString.orEmpty(), true,
                            Modifier.weight(if (key == "rgb") 1.7f else 1f), numeric = key != "rgb") { change(updateVisionSample(value, index, key, it)) }
                    }
                    DesignerAction("删", onClick = { onChange(rows.deepCopy().apply { remove(index) }.toString()) })
                }
            }
        }
        if (rows.size() < 64) DesignerAction("＋色点", onClick = { onChange(rows.deepCopy().apply { add(JsonObject().apply {
            addProperty("dx",0); addProperty("dy",0); addProperty("rgb",16777215); addProperty("tolerance",0)
        }) }.toString()) })
    }
    error?.let { Text(it,fontSize=10.sp,color=com.autoscript.core.designsystem.AutoScriptPalette.Danger) }
    if(error==null) visionSampleError(value)?.let { Text(it,fontSize=10.sp,color=com.autoscript.core.designsystem.AutoScriptPalette.Danger) }
}

package com.autoscript.studio

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

internal val recognitionActionFields = setOf(
    "successAction", "actionAnchor", "actionRandom", "actionRandomX", "actionRandomY",
    "offsetX", "offsetY", "actionDurationMs", "allMatches",
)

internal val recognitionActions = linkedMapOf(
    "none" to "不操作", "tap" to "点击", "hold" to "按下不弹起",
    "tapWait" to "点击后等待", "pressRelease" to "长按后弹起",
)

internal fun recognitionActionError(inputs: Map<String, String>): String? = when {
    inputs["actionAnchor"]?.let { it !in setOf("topLeft", "center") } == true -> "请选择图片中心或左上角"
    inputs["actionRandom"]?.let { it !in setOf("follow", "disabled", "offset", "inside") } == true -> "请选择有效的点击随机方式"
    listOf("actionRandomX", "actionRandomY").any { name ->
        inputs[name]?.let { it.toIntOrNull() !in 0..4096 } == true
    } -> "随机偏移应为0–4096像素"
    else -> null
}

@Composable
internal fun RecognitionActionFields(inputs: Map<String, String>, onChange: (String, String) -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        ImageToolChoice("找到后", inputs["successAction"] ?: "none", recognitionActions.toList(), true, dense = true) {
            onChange("successAction", it)
        }
        if (inputs["successAction"].orEmpty() !in setOf("", "none")) {
            val inside = inputs["actionRandom"] == "inside"
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (!inside) Box(Modifier.weight(1f)) {
                    ImageToolChoice("位置", inputs["actionAnchor"] ?: "topLeft",
                        listOf("center" to "图片中心", "topLeft" to "左上角"), true, dense = true) { onChange("actionAnchor", it) }
                }
                Box(Modifier.weight(1f)) {
                    ImageToolChoice("随机", inputs["actionRandom"] ?: "follow", listOf(
                        "follow" to "跟随原设置", "disabled" to "关闭", "offset" to "指定偏移", "inside" to "图片范围内",
                    ), true, dense = true) { onChange("actionRandom", it) }
                }
            }
            val fields = buildList {
                if (!inside) { add("offsetX" to "偏移X"); add("offsetY" to "偏移Y") }
                if (inputs["actionRandom"] == "offset") { add("actionRandomX" to "随机X±"); add("actionRandomY" to "随机Y±") }
                if (inputs["successAction"] in setOf("tapWait", "pressRelease")) add("actionDurationMs" to "时长ms")
            }
            StudioCompactGrid(fields, cellDp = 100, maximum = 2) { (key, label), cell ->
                ImageToolCompactInput(label, inputs[key] ?: if (key == "actionDurationMs") "100" else "0",
                    true, cell, numeric = true, dense = true) { onChange(key, it) }
            }
            if ("allMatches" in inputs) ImageToolToggle("逐项操作全部结果", inputs["allMatches"] == "true", true) {
                onChange("allMatches", it.toString())
            }
        }
    }
}

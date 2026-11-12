package com.autoscript.studio

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autoscript.core.designsystem.AutoScriptPalette
import com.autoscript.studio.generated.BlockContract
import com.autoscript.studio.generated.BlockPropertyEditor
import com.google.gson.JsonObject

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CompactInputArgumentsDialog(contract: BlockContract, arguments: JsonObject,
    onDismiss: () -> Unit, onConfirm: (JsonObject) -> Unit, confirmLabel: String) {
    val original = remember(contract.kind, arguments) { blockPropertyTexts(contract, arguments) }
    var inputs by remember(contract.kind, arguments) { mutableStateOf(original) }
    var error by remember { mutableStateOf<String?>(null) }
    var discard by remember { mutableStateOf(false) }
    fun close() { if (inputs != original) discard = true else onDismiss() }
    fun update(path: String, value: String) { inputs = inputs + (path to value.take(128)); error = null }
    StudioPanelDialog(contract.title, onDismiss = ::close, confirmLabel = confirmLabel, landscapeWidthDp = 440, onConfirm = {
        val parsed = parseBlockArguments(contract, inputs, null, emptyMap(), emptyList())
        if (parsed.first == null) error = parsed.second else onConfirm(requireNotNull(parsed.first))
    }) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (contract.properties.any { it.path == "screenPixels" }) {
                ImageToolChoice("坐标", inputs["screenPixels"].orEmpty().ifEmpty { "false" },
                    listOf("true" to "屏幕像素", "false" to "项目坐标"), true, dense = true) { update("screenPixels", it) }
            }
            StudioCompactGrid(contract.properties.filter { it.path !in setOf("screenPixels", "durationMs") }, cellDp = 124, maximum = 4,
                modifier = Modifier.fillMaxWidth()) { field, cell ->
                val value = inputs[field.path].orEmpty()
                when (field.editor) {
                    BlockPropertyEditor.BOOLEAN -> ImageToolToggle(field.label, value == "true", true, cell) { update(field.path, it.toString()) }
                    BlockPropertyEditor.ENUM, BlockPropertyEditor.INTEGER_ENUM -> Box(cell) {
                        ImageToolChoice(field.label, value, field.choices.map { it to it }, true, dense = true) { update(field.path, it) }
                    }
                    else -> ImageToolCompactInput(compactInputLabel(field.path, field.label), value, true, cell,
                        numeric = field.editor == BlockPropertyEditor.INTEGER, dense = true) { update(field.path, it) }
                }
            }
            if (contract.properties.any { it.path == "durationMs" }) {
                var presets by remember { mutableStateOf(false) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ImageToolCompactInput("时长ms", inputs["durationMs"].orEmpty(), true, Modifier.weight(1f), numeric = true, dense = true) {
                        update("durationMs", it)
                    }
                    Box {
                        StudioToolButton("常用") { presets = true }
                        DropdownMenu(presets, { presets = false }) {
                            listOf(100, 300, 500, 800, 1000, 2000).forEach { duration ->
                                DropdownMenuItem(text = { Text("${duration}ms", fontSize = 11.sp) },
                                    onClick = { update("durationMs", duration.toString()); presets = false })
                            }
                        }
                    }
                }
            }
            if (contract.properties.isEmpty()) Text("此动作没有参数。", fontSize = 11.sp)
            if (inputs["screenPixels"] != original["screenPixels"]) {
                Text("切换坐标类型不会换算数值，请核对位置。", fontSize = 10.sp, color = AutoScriptPalette.TextSecondary)
            }
            error?.let { Text(it, fontSize = 11.sp, color = AutoScriptPalette.Danger) }
        }
    }
    if (discard) StudioConfirmDialog(onDismissRequest = { discard = false }, title = { Text("放弃本次修改？") },
        text = { Text("尚未确认的参数会丢失。") }, actions = listOf(
            StudioConfirmAction("继续编辑", { discard = false }), StudioConfirmAction("放弃修改", onDismiss, destructive = true)))
}

private fun compactInputLabel(path: String, fallback: String): String = when (path) {
    "x" -> "X"; "y" -> "Y"; "x1" -> "起X"; "y1" -> "起Y"; "x2" -> "终X"; "y2" -> "终Y"
    "durationMs" -> "时长ms"
    else -> fallback
}

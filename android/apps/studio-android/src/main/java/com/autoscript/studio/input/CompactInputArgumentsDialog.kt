package com.autoscript.studio

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
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
    StudioPanelDialog("触控 · ${contract.title}", onDismiss = ::close, confirmLabel = confirmLabel, landscapeWidthDp = 600, onConfirm = {
        val parsed = parseBlockArguments(contract, inputs, null, emptyMap(), emptyList())
        if (parsed.first == null) error = parsed.second else onConfirm(requireNotNull(parsed.first))
    }) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (contract.properties.any { it.path == "screenPixels" }) {
                ImageToolChoice("坐标类型", inputs["screenPixels"].orEmpty().ifEmpty { "false" },
                    listOf("true" to "屏幕像素", "false" to "项目设计坐标"), true) { update("screenPixels", it) }
            }
            StudioCompactGrid(contract.properties.filter { it.path != "screenPixels" }, cellDp = 130, maximum = 4,
                modifier = Modifier.fillMaxWidth()) { field, cell ->
                val value = inputs[field.path].orEmpty()
                when (field.editor) {
                    BlockPropertyEditor.BOOLEAN -> ImageToolToggle(field.label, value == "true", true, cell) { update(field.path, it.toString()) }
                    BlockPropertyEditor.ENUM, BlockPropertyEditor.INTEGER_ENUM -> StudioFormGroup(field.label, cell) {
                        ImageToolChoice("", value, field.choices.map { it to it }, true) { update(field.path, it) }
                    }
                    else -> StudioFormGroup(field.label, cell) {
                        ImageToolCompactInput("", value, true, Modifier.fillMaxWidth(),
                            numeric = field.editor == BlockPropertyEditor.INTEGER) { update(field.path, it) }
                    }
                }
            }
            if (contract.properties.any { it.path == "durationMs" }) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    listOf(100, 300, 500, 800, 1000, 2000).forEach { duration ->
                        ImageToolCompactAction("${duration}ms", true) { update("durationMs", duration.toString()) }
                    }
                }
            }
            if (contract.properties.isEmpty()) Text("此动作没有参数。", fontSize = 11.sp)
            if (contract.properties.any { it.path == "screenPixels" }) {
                ImageToolHelp(if (inputs["screenPixels"] == "true") "屏幕像素直接作用于原位置；分辨率或方向改变后请重新取点。切换类型只改变解释方式，不会自动换算数值。按下、等待、弹起需成组执行。"
                    else "项目设计坐标按项目比例换算。切换类型只改变解释方式，不会自动换算数值，请核对坐标。按下、等待、弹起需成组执行。")
            }
            error?.let { Text(it, fontSize = 11.sp, color = AutoScriptPalette.Danger) }
        }
    }
    if (discard) StudioConfirmDialog(onDismissRequest = { discard = false }, title = { Text("放弃本次修改？") },
        text = { Text("尚未确认的参数会丢失。") }, actions = listOf(
            StudioConfirmAction("继续编辑", { discard = false }), StudioConfirmAction("放弃修改", onDismiss, destructive = true)))
}

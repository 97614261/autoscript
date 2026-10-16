package com.autoscript.studio

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autoscript.project.store.ProjectSnapshot
import com.autoscript.project.store.ProjectVariableType
import com.autoscript.studio.generated.BlockContract
import com.autoscript.studio.generated.BlockPropertyEditor
import com.google.gson.JsonObject

internal val visionExtensionKinds = setOf("screen.transform", "screen.savepng", "vision.findallmulticolor", "vision.components", "ocr.glyphdetails", "ocr.findtext")

internal fun visionExtensionArgumentError(kind: String, inputs: Map<String, String>, variables: List<CalculationVariable>): String? {
    fun type(key: String) = variables.find { it.name == inputs[key] }?.type
    val source = if (kind == "ocr.findtext") "detailsVariable" else "frameVariable"
    if (type(source) != if (kind == "ocr.findtext") ProjectVariableType.STRING else ProjectVariableType.IMAGE) return "请选择类型匹配的来源变量"
    if (kind != "screen.savepng") {
        if (type("resultVariable") != if (kind == "screen.transform") ProjectVariableType.IMAGE else ProjectVariableType.STRING) return "请选择类型匹配的结果变量"
        if (inputs[source] == inputs["resultVariable"]) return "来源和结果请使用不同变量"
    }
    if (kind == "screen.transform") {
        if (inputs["mode"] !in setOf("rotate90", "rotate180", "rotate270", "flipX", "flipY", "gray", "threshold", "resize")) return "请选择有效的变换模式"
        if ((inputs["threshold"] ?: "128").toIntOrNull() !in 0..255) return "二值阈值须为0–255整数"
        val width = inputs["width"]?.toLongOrNull() ?: return "宽度无效"
        val height = inputs["height"]?.toLongOrNull() ?: return "高度无效"
        if (inputs["mode"] == "resize") {
            if (width !in 1..4194304 || height !in 1..4194304 || width * height > 4194304) return "缩放超过4194304像素预算"
        } else if (width != 0L || height != 0L) return "非缩放模式宽高为0"
    }
    if (kind in setOf("vision.findallmulticolor", "vision.components", "ocr.glyphdetails")) {
        val left = inputs["left"]?.toLongOrNull() ?: return "左边界无效"
        val top = inputs["top"]?.toLongOrNull() ?: return "上边界无效"
        val right = inputs["right"]?.toLongOrNull() ?: return "右边界无效"
        val bottom = inputs["bottom"]?.toLongOrNull() ?: return "下边界无效"
        if (left !in 0..32768 || top !in 0..32768 || right !in 1..32768 || bottom !in 1..32768 || left >= right || top >= bottom || (right-left)*(bottom-top) > 4194304) return "区域为空或超过像素预算"
    }
    if (kind == "vision.findallmulticolor") {
        visionSampleError(inputs["samples"].orEmpty())?.let { return it }
    }
    return null
}

@Composable
internal fun VisualVisionExtensionDialog(contract: BlockContract, arguments: JsonObject, project: ProjectSnapshot, flowId: String,
    onManageVariables: (() -> Unit)?, onDismiss: () -> Unit, onConfirm: (JsonObject) -> Unit, confirmLabel: String,
    visible: Boolean = true, allowCaptureSource: Boolean = false, onConfirmSource: ((JsonObject, Boolean) -> Unit)? = null,
    captureSelection: ImageToolCodeGen.VisualSelection? = null, onPickFromScreen: ((ImageToolMode) -> Unit)? = null) {
    val initial = remember(contract.kind, arguments) { blockPropertyTexts(contract, arguments) }
    var inputs by remember(initial) { mutableStateOf(initial) }
    var error by remember { mutableStateOf<String?>(null) }
    var discard by remember { mutableStateOf(false) }
    var fullScreen by remember { mutableStateOf(false) }
    val canCapture = contract.kind in setOf("vision.findallmulticolor", "vision.components", "ocr.glyphdetails") && allowCaptureSource
    var capture by remember(initial) { mutableStateOf(canCapture) }
    var overwriteConfirmed by remember(initial) { mutableStateOf(false) }
    val variables = calculationVariables(project.manifest.variables, flowId, project.manifest.flows)
    fun update(key: String, value: String) { inputs = inputs + (key to value); error = null; if(key=="frameVariable") overwriteConfirmed=false }
    LaunchedEffect(captureSelection) {
        captureSelection?.let { selected ->
            selected.roi?.let { roi -> listOf("left" to roi.left,"top" to roi.top,"right" to roi.right,"bottom" to roi.bottom).forEach { (key,value) -> if (key in inputs) update(key,value.toString()) } }
            selected.points.firstOrNull()?.let { point -> listOf("rgb","foregroundRgb").forEach { if (it in inputs) update(it,(point.rgb and 0xFFFFFF).toString()) } }
            if (contract.kind == "vision.findallmulticolor") visionSamplesFromSelection(selected)?.let { update("samples",it) }
        }
    }
    if (visible) StudioPanelDialog(contract.title, { if (inputs != initial || capture != canCapture) discard = true else onDismiss() }, confirmLabel,
        fullScreen = fullScreen, onToggleFullScreen = { fullScreen = !fullScreen }, onConfirm = {
        error = visionExtensionArgumentError(contract.kind, inputs, variables)
        if(error==null && canCapture && capture && !overwriteConfirmed) error="请确认专用截图暂存变量允许覆盖，并在识别后释放"
        if (error == null) {
            val parsed = parseBlockArguments(contract, inputs, null, emptyMap(), project.manifest.resources)
            error = parsed.second
            parsed.first?.let { if (onConfirmSource != null) onConfirmSource(it, capture) else onConfirm(it) }
        }
    }) {
        Column(Modifier.fillMaxWidth().heightIn(max = if (fullScreen) 900.dp else 360.dp).verticalScroll(rememberScrollState()).padding(10.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(contract.summary, fontSize = 11.sp)
            if (onPickFromScreen != null && contract.kind in setOf("vision.findallmulticolor","vision.components","ocr.glyphdetails"))
                Row { DesignerAction("选范围", onClick = { onPickFromScreen(ImageToolMode.REGION) }); DesignerAction(if (contract.kind == "vision.findallmulticolor") "屏幕选色点" else "屏幕取色", onClick = {
                    onPickFromScreen(if (contract.kind == "vision.findallmulticolor") ImageToolMode.MULTI_COLOR else ImageToolMode.COLOR)
                }) }
            if (listOf("left","top","right","bottom").all { it in inputs }) Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf("left" to "左","top" to "上","right" to "右不含","bottom" to "下不含").forEach { (key,label) ->
                    DesignerInput(label,inputs[key].orEmpty(),true,Modifier.weight(1f),numeric=true) { update(key,it) }
                }
            }
            if (canCapture) DesignerChoice("图像来源", if (capture) "运行时截图" else "已有图像", listOf("capture" to "运行时截图", "frame" to "已有图像"), true) { capture = it == "capture";overwriteConfirmed=false }
            if (canCapture) Text(if (capture) "截图写入所选图像变量，识别后释放；原值会被覆盖。" else "读取已有图像，不截图也不释放；坐标属于来源图像。", fontSize = 10.sp)
            if(canCapture && capture) DesignerToggle("专用暂存变量，允许覆盖并释放",overwriteConfirmed,true) { overwriteConfirmed=it }
            contract.properties.forEach { property ->
                val key = property.path
                if (key in setOf("left","top","right","bottom")) return@forEach
                if (key == "samples") { VisionSampleEditor(inputs[key].orEmpty()) { update(key,it) }; return@forEach }
                // Invalid hidden input must remain editable rather than blocking an unrelated mode invisibly.
                if (contract.kind == "screen.transform" && ((key == "threshold" && inputs["mode"] != "threshold" &&
                    (inputs["threshold"] ?: "128").toIntOrNull() in 0..255) ||
                    (key in setOf("width", "height") && inputs["mode"] != "resize"))) return@forEach
                when {
                    key.endsWith("Variable") -> {
                        val expected = if (key == "frameVariable" || (key == "resultVariable" && contract.kind == "screen.transform")) ProjectVariableType.IMAGE else ProjectVariableType.STRING
                        val sourceKey=if(contract.kind=="ocr.findtext") "detailsVariable" else "frameVariable"
                        DesignerChoice(property.label, inputs[key].orEmpty().ifEmpty { "请选择" }, variables.filter { it.type == expected &&
                            (key!="resultVariable" || it.name!=inputs[sourceKey]) && (key!=sourceKey || it.name!=inputs["resultVariable"])
                        }.map { it.name to it.name }, true) { update(key, it) }
                    }
                    key == "dictionaryPath" -> DesignerChoice(property.label, inputs[key].orEmpty(), project.manifest.resources.filter(::isGlyphDictionaryResource).map { it.get("path").asString.let { path -> path to path } }, true) { update(key, it) }
                    property.editor in setOf(BlockPropertyEditor.ENUM, BlockPropertyEditor.INTEGER_ENUM) -> {
                        val labels = mapOf("rotate90" to "顺时针90°", "rotate180" to "180°", "rotate270" to "顺时针270°", "flipX" to "水平镜像", "flipY" to "垂直镜像", "gray" to "灰度", "threshold" to "灰度二值化", "resize" to "缩放", "4" to "4邻接", "8" to "8邻接")
                        DesignerChoice(property.label, inputs[key].orEmpty(), property.choices.map { it to (labels[it] ?: it) }, true) { update(key, it); if (key == "mode" && it != "resize") { update("width", "0"); update("height", "0") } }
                    }
                    else -> DesignerInput(property.label, inputs[key].orEmpty(), true, Modifier.fillMaxWidth(), numeric = property.editor == BlockPropertyEditor.INTEGER) { update(key, it) }
                }
            }
            Text("范围使用来源帧原像素；颜色、连通块、逐字结果用“读取识别结果属性”读取，不受通用Data数据项预算限制。", fontSize = 11.sp)
            onManageVariables?.let { TextButton(onClick = it) { Text("维护变量", fontSize = 11.sp) } }
            error?.let { Text(it, fontSize = 11.sp) }
        }
    }
    if (discard && visible) StudioConfirmDialog(onDismissRequest = { discard = false }, title = { Text("放弃未保存参数？") }, text = { Text("不会加入或修改积木。") }, actions = listOf(
        StudioConfirmAction("继续编辑", { discard = false }), StudioConfirmAction("放弃", { discard = false; onDismiss() }, destructive = true)))
}

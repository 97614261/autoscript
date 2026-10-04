package com.autoscript.studio

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.autoscript.core.designsystem.AutoScriptPalette
import com.autoscript.project.store.ProjectSnapshot
import com.autoscript.project.store.ProjectVariableType
import com.autoscript.studio.generated.BlockContract
import com.autoscript.studio.generated.BlockPropertyEditor
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** X/Y/W/H is a UI convention only; persisted nodes keep half-open L/T/R/B pixels. */
internal data class ImageRecognitionForm(
    val imagePath: String,
    val frameVariable: String,
    val tolerance: String,
    val similarity: String,
    val bounds: List<String>,
    val foundVariable: String,
    val xVariable: String,
    val yVariable: String,
) {
    fun arguments(resources: List<JsonObject>): Pair<JsonObject?, String?> {
        if (resources.none { it.get("kind")?.asString == "image" && it.get("path")?.asString == imagePath }) {
            return null to "请选择项目中已保存的模板图片"
        }
        val toleranceValue = tolerance.toIntOrNull()?.takeIf { it in 0..255 }
            ?: return null to "像素容差应为 0–255"
        val similarityValue = similarity.toIntOrNull()?.takeIf { it in 0..1000 }
            ?: return null to "相似度应为 0–1000‰"
        val region = imageRecognitionBounds(bounds) ?: return null to "范围需要非负 X/Y、正数宽高，且不能溢出"
        val variables = listOf(frameVariable, foundVariable, xVariable, yVariable)
        if (variables.any { !it.matches(Regex("[A-Za-z_][A-Za-z0-9_]{0,63}")) }) {
            return null to "变量名以字母或下划线开头，只含字母、数字或下划线，最多64字符"
        }
        if (variables.distinct().size != variables.size) return null to "帧、是否找到、X、Y 需使用不同变量，避免覆盖结果"
        return JsonObject().apply {
            addProperty("imagePath", imagePath)
            addProperty("frameVariable", frameVariable)
            addProperty("tolerance", toleranceValue)
            addProperty("similarityPermille", similarityValue)
            add("region", region)
            addProperty("foundVariable", foundVariable)
            addProperty("xVariable", xVariable)
            addProperty("yVariable", yVariable)
        } to null
    }

    companion object {
        fun from(arguments: JsonObject): ImageRecognitionForm {
            fun value(name: String, fallback: String) = arguments.get(name)?.takeIf { it.isJsonPrimitive }?.asString ?: fallback
            val region = arguments.getAsJsonObject("region")
            val left = region?.get("left")?.asLong ?: 0L
            val top = region?.get("top")?.asLong ?: 0L
            val right = region?.get("right")?.asLong ?: 720L
            val bottom = region?.get("bottom")?.asLong ?: 1280L
            return ImageRecognitionForm(
                value("imagePath", ""), value("frameVariable", "frame"), value("tolerance", "0"),
                value("similarityPermille", "900"), listOf(left, top, right - left, bottom - top).map(Long::toString),
                value("foundVariable", "found"), value("xVariable", "foundX"), value("yVariable", "foundY"),
            )
        }
    }
}

internal fun imageRecognitionBounds(values: List<String>): JsonObject? {
    if (values.size != 4) return null
    val numbers = values.map { it.toLongOrNull()?.takeIf { number -> number in 0..Int.MAX_VALUE.toLong() } ?: return null }
    val (x, y, width, height) = numbers
    if (width <= 0 || height <= 0 || x + width > Int.MAX_VALUE || y + height > Int.MAX_VALUE) return null
    return JsonObject().apply {
        addProperty("left", x.toInt()); addProperty("top", y.toInt())
        addProperty("right", (x + width).toInt()); addProperty("bottom", (y + height).toInt())
    }
}

internal fun imageRecognitionDragRegion(startX: Float, startY: Float, endX: Float, endY: Float, width: Int, height: Int): List<String>? {
    if (width <= 0 || height <= 0 || listOf(startX, startY, endX, endY).any { !it.isFinite() }) return null
    val left = floor(min(startX, endX)).toInt().coerceIn(0, width - 1)
    val top = floor(min(startY, endY)).toInt().coerceIn(0, height - 1)
    val right = ceil(max(startX, endX)).toInt().coerceIn(left + 1, width)
    val bottom = ceil(max(startY, endY)).toInt().coerceIn(top + 1, height)
    return listOf(left, top, right - left, bottom - top).map(Int::toString)
}

private data class RecognitionPreview(val bitmap: Bitmap, val width: Int, val height: Int)
private data class RecognitionPreviewLoad(val preview: RecognitionPreview? = null, val error: String? = null)

@Composable
private fun recognitionPreview(snapshot: ProjectSnapshot?, path: String): RecognitionPreviewLoad {
    val preview by produceState(RecognitionPreviewLoad(), snapshot?.manifest?.resources, snapshot?.directory, path) {
        value = RecognitionPreviewLoad()
        value = withContext(Dispatchers.IO) {
            runCatching {
                val file = imageRecognitionFile(requireNotNull(snapshot), path)
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.path, bounds)
                require(bounds.outWidth in 1..4096 && bounds.outHeight in 1..4096 && bounds.outWidth.toLong() * bounds.outHeight <= 4_194_304)
                var sample = 1
                while (max(bounds.outWidth, bounds.outHeight) / sample > 768) sample *= 2
                val bitmap = requireNotNull(BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply {
                    inScaled = false; inSampleSize = sample
                }))
                RecognitionPreviewLoad(RecognitionPreview(bitmap, bounds.outWidth, bounds.outHeight))
            }.getOrElse { RecognitionPreviewLoad(error = "图片不可读、未登记或超过4096边长/400万像素预算") }
        }
    }
    return preview
}

internal val visualRecognitionKinds = listOf(
    "vision.findimage", "vision.findallimages", "vision.findcolor", "vision.findmulticolor", "vision.getcolor",
    "vision.comparecolor", "vision.countcolor", "vision.findallcolor", "ocr.glyph", "vision.findgray", "ocr.alphanumeric",
)

internal enum class RecognitionSection(val title: String) { MATCH("识别设置"), OUTPUT("输出结果"), ADVANCED("高级 / 动作") }

internal fun recognitionPropertySection(path: String): RecognitionSection = when {
    path == "frameVariable" || path in setOf("frequency", "autoCapture", "successAction", "offsetX", "offsetY", "actionDurationMs", "allResultsAction",
        "leftVariable", "topVariable", "rightVariable", "bottomVariable", "applyToAll", "allMatches", "beforeWaitMs", "afterWaitMs") -> RecognitionSection.ADVANCED
    path.endsWith("Variable") && path !in setOf("sourceVariable") -> RecognitionSection.OUTPUT
    else -> RecognitionSection.MATCH
}

internal data class RecognitionInsertRequest(
    val contract: BlockContract,
    val arguments: JsonObject,
    val childSlot: String? = null,
    val position: EditorInsertPosition = EditorInsertPosition.BELOW,
)

/** Function-library insertion keeps its requested location and does not silently create a frame. */
internal fun insertConfiguredRecognition(editor: VisualEditorState, request: RecognitionInsertRequest, arguments: JsonObject): Boolean {
    if (request.position == EditorInsertPosition.REPLACE) return editor.replaceSelectedBlock(request.contract, arguments)
    val selected = editor.selectedNodeId
    if (request.position == EditorInsertPosition.LIST_BOTTOM) editor.selectedNodeId = editor.rows.lastOrNull { it.depth == 0 }?.nodeId
    val inserted = editor.insertBlock(request.contract, arguments, intoChildBlockName = request.childSlot)
    if (inserted == null) {
        editor.selectedNodeId = selected
        return false
    }
    if (request.position == EditorInsertPosition.ABOVE && selected != null) editor.moveSelected(-1)
    return true
}

/** Empty resource paths are allowed only in an unsaved editor draft, never at confirmation. */
internal fun initialRecognitionArguments(contract: BlockContract, snapshot: ProjectSnapshot): JsonObject =
    requireNotNull(initialBlockArguments(contract, snapshot.manifest.flows, snapshot.manifest.resources, "", allowMissingResources = true)).apply {
        if (has("region") && contract.kind != "ocr.alphanumeric") add("region", requireNotNull(imageRecognitionBounds(listOf(
            "0", "0", snapshot.manifest.design.width.toString(), snapshot.manifest.design.height.toString(),
        ))))
    }

/** A capture updates only the selected geometry/colors, not unsaved thresholds or output names. */
internal fun recognitionSelectionInputs(
    inputs: Map<String, String>,
    selection: ImageToolCodeGen.VisualSelection,
): Map<String, String> {
    var result = inputs
    selection.roi?.let { roi ->
        if ("region" in inputs) result = result + ("region" to "${roi.left},${roi.top},${roi.right},${roi.bottom}")
        result = result.mapValues { (name, value) -> if (name in setOf("leftVariable", "topVariable", "rightVariable", "bottomVariable")) "" else value }
    }
    visualImageToolDraft(selection)?.second?.let { values ->
        val contract = BlockCatalog.find(if (selection.mode == ImageToolMode.MULTI_COLOR) "vision.findmulticolor" else "vision.findcolor")
        if (contract != null) result = result + blockPropertyTexts(contract, values).filterKeys {
            it in inputs && (it in setOf("rgb", "anchorRgb", "samples") || (it == "region" && selection.roi != null))
        }
    }
    selection.points.firstOrNull()?.let { point ->
        if ("point" in inputs) result = result + ("point" to "${point.x},${point.y}")
        if ("foregroundRgb" in inputs) result = result + ("foregroundRgb" to String.format(java.util.Locale.ROOT, "#%06X", point.rgb and 0xFFFFFF))
    }
    return result
}

internal fun recognitionPropertyTexts(contract: BlockContract, arguments: JsonObject): Map<String, String> =
    blockPropertyTexts(contract, arguments).let { values ->
        if (contract.kind == "vision.findimage" && arguments.has("imagePaths")) {
            values + ("imagePaths" to arguments.get("imagePaths").toString())
        } else values
    }

internal fun parseRecognitionArguments(contract: BlockContract, inputs: Map<String, String>, resources: List<JsonObject>): Pair<JsonObject?, String?> {
    if (contract.properties.any { it.required && it.path.endsWith("Variable") && inputs[it.path].isNullOrBlank() }) return null to "请填写帧与结果变量名"
    val variables = inputs.filterKeys { it.endsWith("Variable") }.values.filter(String::isNotBlank)
    if (variables.any { !it.matches(Regex("[A-Za-z_][A-Za-z0-9_]{0,63}")) }) return null to "变量名只允许字母、数字和下划线，不能以数字开头，最多64字符"
    val outputs = listOf("frameVariable", "foundVariable", "xVariable", "yVariable", "textVariable", "coverageVariable", "scoreVariable", "resultVariable", "templateVariable", "imageVariable").mapNotNull(inputs::get).filter(String::isNotBlank)
    if (outputs.distinct().size != outputs.size) return null to "帧与各结果变量不能重名"
    if (listOf("leftVariable", "topVariable", "rightVariable", "bottomVariable").mapNotNull(inputs::get).any { it.isNotBlank() && it in outputs }) return null to "范围变量不能与帧或识别输出重名"
    for (name in listOf("tolerance", "anchorTolerance")) {
        if (name in inputs && inputs[name]?.toIntOrNull() !in 0..255) return null to "颜色容差应为 0–255"
    }
    if ("similarityPermille" in inputs && inputs["similarityPermille"]?.toIntOrNull() !in 0..1000) return null to "相似度应为 0–1000‰"
    if ("minimumConfidencePermille" in inputs && inputs["minimumConfidencePermille"]?.toIntOrNull() !in 0..1000) return null to "最低置信度应为 0–1000‰"
    if (inputs["frequency"]?.takeIf(String::isNotBlank)?.let { it.toIntOrNull() !in 1..30 } == true) return null to "识别频次应为1–30"
    if (inputs["actionDurationMs"]?.takeIf(String::isNotBlank)?.let { it.toIntOrNull() !in 1..60_000 } == true) return null to "动作时长应为1–60000毫秒"
    if (contract.kind == "vision.findallimages" && inputs["limit"]?.toIntOrNull() !in 1..64) return null to "保留结果上限应为1–64"
    if (contract.kind == "vision.findallimages") {
        if (inputs["actionDurationMs"]?.toIntOrNull() !in 1..5000) return null to "多结果动作时长应为1–5000毫秒"
        if (listOf("beforeWaitMs", "afterWaitMs").any { inputs[it]?.toIntOrNull() !in 0..60_000 }) return null to "前后等待应为0–60000毫秒"
        if (inputs["successAction"] == "hold" && inputs["allMatches"] == "true") return null to "按下不弹起只能作用于首个结果，避免重复按下同一触点"
    }
    inputs["imageDirectory"]?.takeIf(String::isNotBlank)?.let { directory ->
        val selected = resourcePaths(resources, com.autoscript.studio.generated.BlockResourceKind.IMAGE).filter { it.startsWith(directory) }
        if (!directory.startsWith("assets/images/") || !directory.endsWith('/') || directory.contains("..") || directory.contains('\\') || selected.size !in 1..64) return null to "模板文件夹需包含1–64个已登记图片"
    }
    val parsed = parseBlockArguments(contract, inputs, null, emptyMap(), resources)
    if (contract.kind == "vision.result") {
        val aggregate = inputs["field"] in setOf("count", "truncated")
        val index = inputs["index"]?.toIntOrNull()
        if (index == null || (aggregate && index != 0) || (!aggregate && index !in 1..64)) {
            return null to "数量/截断属性填序号0；逐项属性填1–64"
        }
    }
    if (contract.kind == "vision.findimage" && parsed.first != null) {
        inputs["imagePaths"]?.takeIf(String::isNotBlank)?.let { text ->
            if (text.length > 16_384) return null to "模板列表过长"
            val paths = runCatching { JsonParser.parseString(text).asJsonArray }.getOrNull()
                ?: return null to "模板列表必须是图片路径数组"
            val names = runCatching { paths.map { value ->
                require(value.isJsonPrimitive && value.asJsonPrimitive.isString)
                value.asString
            } }.getOrNull() ?: return null to "模板列表只能包含图片路径"
            val declared = resourcePaths(resources, com.autoscript.studio.generated.BlockResourceKind.IMAGE)
            if (names.size !in 1..64 || names.distinct().size != names.size || names.any { it !in declared }) return null to "请选择1–64张不重复的已登记图片"
            if (!inputs["imageDirectory"].isNullOrBlank()) return null to "模板列表与文件夹不能同时选择"
            requireNotNull(parsed.first).add("imagePaths", paths)
        }
        val checked = ImageRecognitionForm.from(requireNotNull(parsed.first)).arguments(resources)
        if (checked.second != null) return checked
    }
    return parsed
}

/** Only the new typed result contract requires declarations; legacy implicit outputs remain editable. */
internal fun recognitionRequiredVariableType(kind: String, field: String, arguments: JsonObject): ProjectVariableType? = when {
    kind == "vision.findallimages" && field == "resultVariable" -> ProjectVariableType.STRING
    kind == "vision.findallimages" && field == "frameVariable" && arguments["autoCapture"]?.asBoolean != true -> ProjectVariableType.IMAGE
    kind == "vision.result" && field == "sourceVariable" -> ProjectVariableType.STRING
    kind == "vision.result" && field == "targetVariable" -> if (arguments["field"]?.asString == "path") ProjectVariableType.STRING else ProjectVariableType.INTEGER
    else -> null
}

internal fun recognitionProjectError(contract: BlockContract, arguments: JsonObject, snapshot: ProjectSnapshot?, flowId: String): String? {
    if (snapshot == null) return null
    val declarations = calculationVariables(snapshot.manifest.variables, flowId, snapshot.manifest.flows).associateBy { it.name }
    for (property in contract.properties) {
        val required = recognitionRequiredVariableType(contract.kind, property.path, arguments) ?: continue
        val name = arguments[property.path]?.asString.orEmpty()
        if (declarations[name]?.type != required) return "${property.label}需要已声明的${calculationTypeLabel(required)}变量；请先维护变量"
    }
    val action = arguments["successAction"]?.asString ?: "none"
    val required = contract.requiredCapabilities.toMutableSet()
    if (action != "none") required += "input.basic"
    if (action != "none" || contract.kind == "vision.findallimages") required += "core.task"
    val missing = required - snapshot.manifest.capabilities.toSet()
    return if (missing.isEmpty()) null else "项目未启用能力：${missing.sorted().joinToString()}（请在设置中启用）"
}

/** One compact shell for all supported recognition types, shared by both visual editor hosts. */
@Composable
internal fun VisualImageRecognitionDialog(
    contract: BlockContract,
    arguments: JsonObject,
    resources: List<JsonObject>,
    knownVariables: List<String>,
    snapshot: ProjectSnapshot?,
    confirmLabel: String = "确定",
    automaticCapture: Boolean = false,
    allowKindChange: Boolean = false,
    visible: Boolean = true,
    captureSelection: ImageToolCodeGen.VisualSelection? = null,
    captureTemplatePath: String? = null,
    onPickFromScreen: ((ImageToolMode) -> Unit)? = null,
    onDismiss: () -> Unit,
    onConfirm: (BlockContract, JsonObject) -> Unit,
    currentFlowId: String = "",
    onManageVariables: (() -> Unit)? = null,
) {
    // Resource refresh and opening the screen picker must not discard the current draft.
    var activeKind by remember(contract.kind, arguments) { mutableStateOf(contract.kind) }
    var drafts by remember(contract.kind, arguments) { mutableStateOf(mapOf(contract.kind to recognitionPropertyTexts(contract, arguments))) }
    val activeContract = requireNotNull(BlockCatalog.find(activeKind))
    val inputs = drafts.getValue(activeKind)
    fun update(name: String, value: String) { drafts = drafts + (activeKind to (drafts.getValue(activeKind) + (name to value))) }
    var error by remember { mutableStateOf<String?>(null) }
    var picker by remember { mutableStateOf<String?>(null) }
    var section by remember { mutableStateOf(RecognitionSection.MATCH) }
    var showCoordinates by remember { mutableStateOf(true) }
    val imagePath = inputs["imagePath"].orEmpty()
    val previewLoad = recognitionPreview(snapshot, imagePath)
    val preview = previewLoad.preview
    val variables = (knownVariables + inputs.filterKeys { it.endsWith("Variable") }.values).filter(String::isNotBlank).distinct().sorted()
    LaunchedEffect(captureSelection) {
        captureSelection?.let { selection -> drafts = drafts + (activeKind to recognitionSelectionInputs(drafts.getValue(activeKind), selection)) }
    }
    LaunchedEffect(captureTemplatePath) {
        if (captureTemplatePath != null && "imagePath" in inputs) update("imagePath", captureTemplatePath)
        if (captureTemplatePath != null && activeKind == "vision.findallimages") {
            val selected = recognitionTemplatePaths(inputs["imagePaths"].orEmpty())
            if (captureTemplatePath !in selected && selected.size < 64) update("imagePaths", recognitionTemplateJson(selected + captureTemplatePath))
        }
    }
    if (visible) Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth(.96f).widthIn(max = 550.dp).heightIn(max = 540.dp), shape = RoundedCornerShape(2.dp), color = Color.White, shadowElevation = 8.dp) {
            Column {
                RecognitionHeader(activeContract.title, onDismiss)
                if (activeKind != "vision.result") {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 5.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        RecognitionChoice("截图 / 取图", Modifier.weight(1f), enabled = onPickFromScreen != null) { onPickFromScreen?.invoke(ImageToolMode.CROP) }
                        RecognitionChoice(activeContract.title + if (allowKindChange) " ▾" else "", Modifier.weight(1f), enabled = allowKindChange) { picker = "kind" }
                    }
                    Row(Modifier.fillMaxWidth().height(34.dp).background(AutoScriptPalette.VisualEditor.ControlBackground)) {
                        RecognitionSection.entries.forEach { item ->
                            Box(Modifier.weight(1f).fillMaxHeight().background(if (section == item) AutoScriptPalette.AccentSoft else Color.Transparent)
                                .clickable { section = item }, contentAlignment = Alignment.Center) {
                                Text(item.title, fontSize = 11.sp, fontWeight = if (section == item) FontWeight.Bold else FontWeight.Normal,
                                    color = if (section == item) AutoScriptPalette.Accent else AutoScriptPalette.TextSecondary,
                                    style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false)))
                            }
                        }
                    }
                }
                HorizontalDivider(color = AutoScriptPalette.Divider)
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(start = 6.dp, end = 6.dp, top = 2.dp, bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (section == RecognitionSection.MATCH && activeKind in setOf("vision.findimage", "vision.findgray")) {
                        Row(Modifier.height(76.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                RecognitionSettingRow("模板选择") {
                                    RecognitionChoice(imagePath.ifBlank { "选择模板图片" }, Modifier.weight(1f)) { picker = "imagePath" }
                                }
                                RecognitionSettingRow("识别方式") {
                                    if (activeKind == "vision.findgray") RecognitionChoice("OpenCV · 灰度最高分", Modifier.weight(1f), enabled = false) {}
                                    else
                                    RecognitionChoice(when {
                                        !inputs["imageDirectory"].isNullOrBlank() -> "多模板 · 文件夹"
                                        !inputs["imagePaths"].isNullOrBlank() -> "多模板 · 指定图片"
                                        else -> "单模板 · 像素匹配"
                                    }, Modifier.weight(1f)) { picker = "support" }
                                }
                            }
                            Box(Modifier.size(70.dp).background(AutoScriptPalette.VisualEditor.ControlBackground).border(1.dp, AutoScriptPalette.Divider).clickable { picker = "imagePath" }, contentAlignment = Alignment.Center) {
                                if (preview != null) Image(preview.bitmap.asImageBitmap(), "选择模板", Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                                else Text("预览", color = AutoScriptPalette.TextSecondary, fontSize = 11.sp)
                            }
                        }
                        if (imagePath.isNotBlank()) previewLoad.error?.let {
                            Text(it, color = AutoScriptPalette.Danger, fontSize = 10.sp)
                        }
                    }
                    if (section == RecognitionSection.MATCH && "region" in inputs) {
                        val region = inputs.getValue("region").split(',').map { it.toLongOrNull() }
                        val bounds = if (region.size == 4 && region.all { it != null }) {
                            listOf(region[0]!!, region[1]!!, region[2]!! - region[0]!!, region[3]!! - region[1]!!).map(Long::toString)
                        } else listOf("", "", "", "")
                        RecognitionSettingRow("选取范围", onLabelClick = onPickFromScreen?.let { pick -> { pick(ImageToolMode.REGION) } }) {
                            bounds.forEachIndexed { index, value ->
                                RecognitionChoice("${listOf("X", "Y", "W", "H")[index]}:$value", Modifier.weight(1f)) { picker = "bounds" }
                            }
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            RecognitionChoice("屏幕框选", Modifier.weight(1f), enabled = onPickFromScreen != null) { onPickFromScreen?.invoke(ImageToolMode.REGION) }
                            RecognitionChoice("项目全屏", Modifier.weight(1f), enabled = snapshot != null) {
                                snapshot?.let { update("region", "0,0,${it.manifest.design.width},${it.manifest.design.height}"); error = null }
                            }
                        }
                    }
                    activeContract.properties.filter { property ->
                        property.path !in setOf("frameVariable", "imagePath", "region", "foundVariable", "xVariable", "yVariable", "leftVariable", "topVariable", "rightVariable", "bottomVariable", "autoCapture")
                    }.forEach { property ->
                        if (activeKind != "vision.result" && recognitionPropertySection(property.path) != section) return@forEach
                        if (property.path == "similarityPermille" && "tolerance" in inputs) return@forEach
                        RecognitionSettingRow(when (property.path) {
                            "tolerance" -> "像素容差"; "dictionaryPath" -> "字库选择"; "anchorRgb" -> "锚点颜色"
                            "samples" -> "偏移点"; "sourceVariable" -> "结果来源"; "targetVariable" -> "接收变量"
                            "field" -> "结果属性"; "index" -> "结果序号"; else -> property.label
                        }) {
                            when {
                                property.path == "successAction" -> RecognitionChoice(recognitionActions[inputs[property.path]] ?: "不执行", Modifier.weight(1f)) { picker = property.path }
                                property.editor == BlockPropertyEditor.BOOLEAN -> RecognitionChoice(if (inputs[property.path] == "true") "开启" else "关闭", Modifier.weight(1f)) { update(property.path, (inputs[property.path] != "true").toString()) }
                                property.path == "imageDirectory" -> {
                                    RecognitionChoice(inputs[property.path].orEmpty().ifBlank { "单模板" }, Modifier.weight(1f)) { picker = "imageDirectory" }
                                    RecognitionChoice("清除") { update("imageDirectory", "") }
                                }
                                property.path == "direction" -> RecognitionChoice(recognitionDirections.getOrElse(inputs["direction"]?.toIntOrNull() ?: 0) { "方向无效" }, Modifier.weight(1f)) { picker = "direction" }
                                property.editor == BlockPropertyEditor.RESOURCE -> RecognitionChoice(inputs[property.path].orEmpty().ifBlank { "选择资源" }, Modifier.weight(1f)) { picker = property.path }
                                property.editor == BlockPropertyEditor.RESOURCE_LIST -> RecognitionChoice("已选 ${recognitionTemplatePaths(inputs[property.path].orEmpty()).size} 张 · 编辑", Modifier.weight(1f)) { picker = property.path }
                                property.editor == BlockPropertyEditor.ENUM -> RecognitionChoice(inputs[property.path].orEmpty(), Modifier.weight(1f)) { picker = property.path }
                                property.path.endsWith("Variable") -> {
                                    RecognitionInput(inputs[property.path].orEmpty(), { update(property.path, it); error = null }, Modifier.weight(1f))
                                    RecognitionChoice("选择") { picker = property.path }
                                }
                                else -> RecognitionInput(inputs[property.path].orEmpty(), { update(property.path, it); error = null }, Modifier.weight(1f), numeric = property.editor == BlockPropertyEditor.INTEGER)
                            }
                            if (property.path == "tolerance" && "similarityPermille" in inputs) {
                                Text("相似度‰", fontSize = 10.sp)
                                RecognitionInput(inputs["similarityPermille"].orEmpty(), { update("similarityPermille", it); error = null }, Modifier.weight(1f), numeric = true)
                            }
                        }
                    }
                    if (section == RecognitionSection.OUTPUT && "foundVariable" in inputs) {
                        RecognitionSettingRow("是否找到") {
                            RecognitionInput(inputs["foundVariable"].orEmpty(), { update("foundVariable", it); error = null }, Modifier.weight(1f))
                            RecognitionChoice("选择") { picker = "foundVariable" }
                            RecognitionChoice(if (showCoordinates) "收起坐标 ▴" else "显示坐标 ▾") { showCoordinates = !showCoordinates }
                        }
                        if (showCoordinates) RecognitionSettingRow("坐标变量") {
                            RecognitionChoice("X:${inputs["xVariable"]}", Modifier.weight(1f)) { picker = "xVariable" }
                            RecognitionChoice("Y:${inputs["yVariable"]}", Modifier.weight(1f)) { picker = "yVariable" }
                        }
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        if (onManageVariables != null) RecognitionChoice("维护变量", onClick = onManageVariables)
                        if (section == RecognitionSection.MATCH && onPickFromScreen != null && activeKind !in setOf("vision.findimage", "vision.findallimages", "vision.findgray", "ocr.alphanumeric", "vision.result")) RecognitionChoice("屏幕取点/色") {
                            onPickFromScreen(if (activeKind == "vision.findmulticolor") ImageToolMode.MULTI_COLOR else ImageToolMode.COLOR)
                        }
                        Spacer(Modifier.weight(1f))
                    }
                    if (section == RecognitionSection.ADVANCED && activeKind != "vision.result") {
                        if ("frameVariable" in inputs) RecognitionVariableRow("帧变量", inputs["frameVariable"].orEmpty(), { update("frameVariable", it); error = null }) { picker = "frameVariable" }
                        if (activeKind == "vision.findimage") {
                            listOf("leftVariable", "topVariable", "rightVariable", "bottomVariable").forEachIndexed { index, field ->
                                RecognitionVariableRow(listOf("左边界变量", "上边界变量", "右边界变量", "下边界变量")[index], inputs[field].orEmpty(), { update(field, it); error = null }) { picker = field }
                            }
                            Text("范围变量留空使用固定值；变量在运行时必须为非负整数，右/下边界不包含。", color = AutoScriptPalette.TextSecondary, fontSize = 10.sp)
                        }
                        if ("frameVariable" in inputs) Text(if (automaticCapture || inputs["autoCapture"] == "true") "自动截图并释放；关闭后才读取帧变量。范围使用原始屏幕像素。" else "读取帧变量指向的已有截图；不会释放用户帧。范围使用原始屏幕像素。", color = AutoScriptPalette.TextSecondary, fontSize = 10.sp)
                        if ("autoCapture" in inputs) {
                            RecognitionSettingRow("自动截图") { RecognitionChoice(if (inputs["autoCapture"] == "true" || automaticCapture) "开启" else "关闭", Modifier.weight(1f), enabled = !automaticCapture) { update("autoCapture", (inputs["autoCapture"] != "true").toString()) } }
                        }
                        if (activeKind == "vision.findimage") {
                            Text("频次N表示首次识别、之后每N次访问识别一次。图像输出是匹配区域裁剪句柄，覆盖旧结果会释放，插件结束自动清理。动作偏移使用原始屏幕像素。", color = AutoScriptPalette.TextSecondary, fontSize = 10.sp)
                        }
                        Text(when (activeKind) {
                            "vision.findgray" -> "灰度最高分匹配，不缩放、不旋转；模板≤256K像素，范围≤400万像素，超出计算预算会明确报错。是否找到为1/0。"
                            "vision.findallimages" -> "按模板选择顺序及方向保留结果（包括重叠），最多64项。读取truncated判断是否截断；结果变量需先在变量页声明为字类型。动作默认只作用首项，开启全结果才逐项执行；未保留结果不执行。按住仅限首项。等待可取消；动作前已释放临时帧。成功/失败分支互斥。"
                            "vision.result" -> "来源选择多结果找图的字变量。数量count/截断truncated使用序号0；其余属性从1开始，越界明确报错。路径path接收到字变量，其他属性接收到整变量。"
                            "ocr.alphanumeric" -> "仅识别所选单行区域的A–Z、a–z、0–9；不做文字检测，宽高比≤20，标点过滤。置信度为已接受字符的平均值。"
                            else -> "图像输出为匹配区域，不与参考软件的不透明图像数据格式混用。"
                        }, color = AutoScriptPalette.TextSecondary, fontSize = 10.sp)
                    }
                    error?.let { Text(it, color = AutoScriptPalette.Danger, fontSize = 12.sp) }
                }
                RecognitionFooter("取消", confirmLabel, onDismiss) {
                    val parsed = parseRecognitionArguments(activeContract, inputs, resources)
                    val configured = parsed.first?.deepCopy()?.apply {
                        if (automaticCapture && has("autoCapture")) addProperty("autoCapture", true)
                    }
                    error = parsed.second ?: configured?.let { recognitionProjectError(activeContract, it, snapshot, currentFlowId) }
                    if (configured != null && error == null) onConfirm(activeContract, configured)
                }
            }
        }
    }
    if (visible) picker?.let { field ->
        if (activeContract.properties.any { it.path == field && it.editor == BlockPropertyEditor.RESOURCE_LIST }) {
            RecognitionTemplatesDialog(resourcePaths(resources, com.autoscript.studio.generated.BlockResourceKind.IMAGE), recognitionTemplatePaths(inputs[field].orEmpty()), { picker = null }) {
                update(field, recognitionTemplateJson(it)); picker = null; error = null
            }
            return@let
        }
        if (field == "bounds") {
            RecognitionBoundsDialog(inputs["region"].orEmpty(), snapshot, { picker = null }) { update("region", it); picker = null; error = null }
            return@let
        }
        val resourceProperty = activeContract.properties.firstOrNull { it.path == field && it.editor == BlockPropertyEditor.RESOURCE }
        val kinds = visualRecognitionKinds.mapNotNull(BlockCatalog::find)
        val enumProperty = activeContract.properties.firstOrNull { it.path == field && it.editor == BlockPropertyEditor.ENUM }
        val requiredType = recognitionRequiredVariableType(activeKind, field, JsonObject().apply {
            addProperty("autoCapture", automaticCapture || inputs["autoCapture"] == "true")
            addProperty("field", inputs["field"].orEmpty())
        })
        val choices = when {
            field == "kind" -> kinds.map { it.title }
            field == "direction" -> recognitionDirections
            field == "successAction" -> recognitionActions.values.toList()
            field == "support" -> listOf("单模板 · Rust 像素匹配", "多模板 · 文件夹", "OpenCV · 灰度最高分")
            field == "imageDirectory" -> resourcePaths(resources, com.autoscript.studio.generated.BlockResourceKind.IMAGE).flatMap { path ->
                val parts = path.split('/'); (2 until parts.size).map { parts.take(it).joinToString("/", postfix = "/") }
            }.distinct().sorted()
            resourceProperty != null -> resourcePaths(resources, resourceProperty.resourceKind)
            enumProperty != null -> enumProperty.choices
            requiredType != null && snapshot != null -> calculationVariables(snapshot.manifest.variables, currentFlowId, snapshot.manifest.flows).filter { it.type == requiredType }.map { it.name }
            else -> variables
        }
        RecognitionPicker(when (field) { "kind" -> "识别类型"; "support" -> "识别方式"; else -> resourceProperty?.label ?: enumProperty?.label ?: "选择变量" }, choices,
            if (field == "kind") activeContract.title else inputs[field].orEmpty(),
            onDismiss = { picker = null }, onSelect = { value ->
                if (field == "kind") {
                    val next = kinds.first { it.title == value }
                    if (next.kind !in drafts) drafts = drafts + (next.kind to blockPropertyTexts(next, initialRecognitionArguments(next, requireNotNull(snapshot))))
                    activeKind = next.kind
                } else if (field == "direction") {
                    update(field, recognitionDirections.indexOf(value).toString())
                } else if (field == "successAction") {
                    update(field, recognitionActions.entries.first { it.value == value }.key)
                } else if (field == "imageDirectory") {
                    update(field, value)
                    update("imagePaths", "")
                    resourcePaths(resources, com.autoscript.studio.generated.BlockResourceKind.IMAGE).firstOrNull { it.startsWith(value) }?.let { update("imagePath", it) }
                } else if (field == "support") {
                    if (value.startsWith("多模板")) { picker = "imageDirectory"; return@RecognitionPicker }
                    if (value.startsWith("OpenCV")) {
                        val next = requireNotNull(BlockCatalog.find("vision.findgray"))
                        val values = blockPropertyTexts(next, initialRecognitionArguments(next, requireNotNull(snapshot))).toMutableMap()
                        values.keys.toList().forEach { name -> inputs[name]?.let { values[name] = it } }
                        drafts = drafts + (next.kind to values)
                        activeKind = next.kind
                        picker = null
                        error = null
                        return@RecognitionPicker
                    }
                    update("imageDirectory", "")
                    update("imagePaths", "")
                } else if (field != "support") {
                    update(field, value)
                }
                picker = null
                error = null
            }, allowNewValue = requiredType == null && enumProperty == null && resourceProperty == null && field !in setOf("kind", "support", "direction", "imageDirectory", "successAction"))
    }
}

private val recognitionDirections = listOf("左上 → 右下", "右上 → 左下", "左下 → 右上", "右下 → 左上", "中心向外")

internal fun recognitionTemplatePaths(text: String): List<String> = runCatching {
    require(text.toByteArray(Charsets.UTF_8).size <= 16_384)
    val values = JsonParser.parseString(text).asJsonArray
    require(values.size() <= 64)
    values.map { require(it.isJsonPrimitive && it.asJsonPrimitive.isString); it.asString }.distinct()
}.getOrDefault(emptyList())

internal fun recognitionTemplateJson(paths: List<String>): String = com.google.gson.JsonArray().apply {
    paths.forEach { add(it) }
}.toString()

@Composable
private fun RecognitionTemplatesDialog(choices: List<String>, current: List<String>, onDismiss: () -> Unit, onConfirm: (List<String>) -> Unit) {
    var selected by remember(current) { mutableStateOf(current.filter { it in choices }) }
    var search by remember { mutableStateOf("") }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth(.92f).widthIn(max = 540.dp).heightIn(max = 420.dp), color = Color.White) {
            Column {
                RecognitionHeader("模板 · 已选${selected.size}/64（按选择顺序）", onDismiss)
                RecognitionInput(search, { search = it }, Modifier.fillMaxWidth().padding(6.dp))
                LazyColumn(Modifier.weight(1f, fill = false).padding(horizontal = 8.dp)) {
                    items(choices.filter { it.contains(search, ignoreCase = true) }, key = { it }) { path ->
                        val index = selected.indexOf(path)
                        Row(Modifier.fillMaxWidth().height(34.dp).clickable(enabled = index >= 0 || selected.size < 64) {
                            selected = if (index >= 0) selected - path else selected + path
                        }, verticalAlignment = Alignment.CenterVertically) {
                            Text(if (index >= 0) "${index + 1}." else "○", Modifier.width(28.dp), color = AutoScriptPalette.Accent, fontSize = 11.sp)
                            Text(path.removePrefix("assets/images/"), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                Text("取消勾选再添加可调整优先顺序；最多64张。", Modifier.padding(6.dp), color = AutoScriptPalette.TextSecondary, fontSize = 10.sp)
                RecognitionFooter("取消", "确定", onDismiss) { onConfirm(selected) }
            }
        }
    }
}
private val recognitionActions = linkedMapOf("none" to "不执行", "tap" to "点击", "hold" to "按下不弹起", "tapWait" to "点击后停顿", "pressRelease" to "按下后定时弹起")

@Composable
private fun RecognitionSettingRow(label: String, onLabelClick: (() -> Unit)? = null, content: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth().height(34.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, Modifier.width(68.dp).then(if (onLabelClick != null) Modifier.clickable(onClick = onLabelClick) else Modifier), color = if (onLabelClick != null) AutoScriptPalette.Accent else AutoScriptPalette.TextPrimary, style = TextStyle(fontSize = 11.sp, lineHeight = 13.sp, platformStyle = PlatformTextStyle(includeFontPadding = false)), maxLines = 1, overflow = TextOverflow.Ellipsis)
        content()
    }
}

@Composable
private fun RecognitionBoundsDialog(region: String, snapshot: ProjectSnapshot?, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    val values = region.split(',').map { it.toLongOrNull() ?: 0L }
    var bounds by remember(region) { mutableStateOf(if (values.size == 4) listOf(values[0], values[1], values[2] - values[0], values[3] - values[1]).map(Long::toString) else listOf("0", "0", "720", "1280")) }
    var error by remember { mutableStateOf<String?>(null) }
    Dialog(onDismissRequest = onDismiss) {
        Surface(color = Color.White) {
            Column(Modifier.padding(8.dp)) {
                RecognitionHeader("范围 · 原始像素", onDismiss)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf("X", "Y", "宽", "高").forEachIndexed { index, name ->
                        Column(Modifier.weight(1f)) {
                            Text(name, Modifier.fillMaxWidth(), textAlign = TextAlign.Center, fontSize = 11.sp)
                            RecognitionInput(bounds[index], { bounds = bounds.toMutableList().apply { set(index, it) }; error = null }, numeric = true)
                        }
                    }
                }
                snapshot?.let { project -> RecognitionChoice("项目全屏") { bounds = listOf("0", "0", project.manifest.design.width.toString(), project.manifest.design.height.toString()) } }
                error?.let { Text(it, color = AutoScriptPalette.Danger, fontSize = 11.sp) }
                RecognitionFooter("取消", "确定", onDismiss) {
                    val rectangle = imageRecognitionBounds(bounds)
                    if (rectangle == null) error = "请输入非负坐标和正数宽高，且不能溢出"
                    else onConfirm(listOf("left", "top", "right", "bottom").joinToString(",") { rectangle.get(it).asString })
                }
            }
        }
    }
}

@Composable
private fun RecognitionHeader(title: String, onDismiss: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(40.dp).padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), color = AutoScriptPalette.Accent, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        Box(Modifier.size(40.dp).clickable(onClick = onDismiss), contentAlignment = Alignment.Center) {
            Text("×", color = AutoScriptPalette.TextSecondary, fontSize = 23.sp)
        }
    }
    HorizontalDivider(color = AutoScriptPalette.Divider)
}

@Composable
private fun RecognitionFooter(left: String, right: String, onLeft: () -> Unit, onRight: () -> Unit) {
    HorizontalDivider(color = AutoScriptPalette.Divider)
    Row(Modifier.fillMaxWidth().height(40.dp)) {
        Box(Modifier.weight(1f).fillMaxHeight().clickable(onClick = onLeft), contentAlignment = Alignment.Center) { Text(left, style = TextStyle(fontSize = 13.sp, lineHeight = 15.sp, platformStyle = PlatformTextStyle(includeFontPadding = false))) }
        Box(Modifier.width(1.dp).fillMaxHeight().background(AutoScriptPalette.Divider))
        Box(Modifier.weight(1f).fillMaxHeight().clickable(onClick = onRight), contentAlignment = Alignment.Center) { Text(right, color = AutoScriptPalette.Accent, style = TextStyle(fontSize = 13.sp, lineHeight = 15.sp, platformStyle = PlatformTextStyle(includeFontPadding = false)), fontWeight = FontWeight.Bold) }
    }
}

@Composable
private fun RecognitionInput(value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier, numeric: Boolean = false) {
    BasicTextField(value, { onChange(it.take(128)) }, singleLine = true,
        textStyle = TextStyle(color = AutoScriptPalette.TextPrimary, fontSize = 12.sp, lineHeight = 14.sp, platformStyle = PlatformTextStyle(includeFontPadding = false), textAlign = if (numeric) TextAlign.Center else TextAlign.Start),
        keyboardOptions = KeyboardOptions(keyboardType = if (numeric) KeyboardType.Number else KeyboardType.Text),
        modifier = modifier.height(30.dp).background(AutoScriptPalette.VisualEditor.ControlBackground, RoundedCornerShape(3.dp)).border(1.dp, AutoScriptPalette.Divider, RoundedCornerShape(3.dp)),
        decorationBox = { inner -> Box(Modifier.fillMaxSize().padding(horizontal = 6.dp), contentAlignment = Alignment.CenterStart) { inner() } })
}

@Composable
private fun RecognitionChoice(value: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Box(modifier.height(30.dp).border(1.dp, AutoScriptPalette.Divider, RoundedCornerShape(3.dp)).clickable(enabled = enabled, onClick = onClick).padding(horizontal = 6.dp), contentAlignment = Alignment.Center) {
        Text(value, color = if (enabled) AutoScriptPalette.Accent else AutoScriptPalette.TextSecondary, style = TextStyle(fontSize = 11.sp, lineHeight = 13.sp, platformStyle = PlatformTextStyle(includeFontPadding = false)), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun RecognitionVariableRow(label: String, value: String, onChange: (String) -> Unit, onSelect: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, Modifier.width(64.dp), fontSize = 12.sp, maxLines = 1)
        RecognitionInput(value, onChange, Modifier.weight(1f))
        RecognitionChoice("选择", onClick = onSelect)
    }
}

@Composable
private fun RecognitionPicker(title: String, choices: List<String>, current: String, onDismiss: () -> Unit, onSelect: (String) -> Unit, allowNewValue: Boolean = false, disabledValues: Set<String> = emptySet()) {
    var search by remember { mutableStateOf("") }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth(.92f).widthIn(max = 540.dp).heightIn(max = 480.dp), color = Color.White, shape = RoundedCornerShape(3.dp)) {
            Column {
                RecognitionHeader(title, onDismiss)
                RecognitionInput(search, { search = it }, Modifier.fillMaxWidth().padding(10.dp))
                if (allowNewValue && search.matches(Regex("[A-Za-z_][A-Za-z0-9_]{0,63}"))) {
                    RecognitionChoice("使用变量：$search", Modifier.fillMaxWidth().padding(horizontal = 10.dp)) { onSelect(search) }
                }
                val filtered = choices.filter { it.contains(search, ignoreCase = true) }
                LazyColumn(Modifier.weight(1f, fill = false).padding(10.dp)) {
                    if (filtered.isEmpty()) item { Text("没有可选项", fontSize = 12.sp) }
                    items(filtered, key = { it }) { choice ->
                        Row(Modifier.fillMaxWidth().clickable(enabled = choice !in disabledValues) { onSelect(choice) }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(if (choice == current) "●" else "○", color = AutoScriptPalette.Accent, modifier = Modifier.padding(end = 8.dp))
                            Text(choice, fontSize = 12.sp, color = if (choice in disabledValues) AutoScriptPalette.TextSecondary else if (choice == current) AutoScriptPalette.Accent else AutoScriptPalette.TextPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        HorizontalDivider(color = AutoScriptPalette.Divider)
                    }
                }
            }
        }
    }
}

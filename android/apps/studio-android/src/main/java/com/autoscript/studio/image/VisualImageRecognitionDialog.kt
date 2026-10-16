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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autoscript.studio.StudioDialog as Dialog
import androidx.compose.ui.window.DialogProperties
import com.autoscript.core.designsystem.AutoScriptPalette
import com.autoscript.project.store.ProjectSnapshot
import com.autoscript.project.store.ProjectVariableType
import com.autoscript.runtime.client.RuntimeClient
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
        var prepared: Bitmap? = null
        try { value = withContext(Dispatchers.IO) {
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
                prepared = bitmap
                RecognitionPreviewLoad(RecognitionPreview(bitmap, bounds.outWidth, bounds.outHeight))
            }.getOrElse { RecognitionPreviewLoad(error = "图片不可读、未登记或超过4096边长/400万像素预算") }
        }; prepared = null } finally { prepared?.recycle() }
    }
    // Published bitmaps are also retained by Compose/RenderThread. Do not recycle them
    // from an effect reading the current delegated state: disposing the old loading
    // effect can otherwise recycle the newly published thumbnail before its first draw.
    // Unpublished/cancelled decodes above are still explicitly recycled; published,
    // bounded thumbnails are reclaimed once all UI/render references are gone.
    return preview
}

internal val visualRecognitionKinds = listOf(
    "vision.findimage", "vision.findallimages", "vision.findcolor", "vision.findmulticolor", "vision.getcolor",
    "vision.comparecolor", "vision.countcolor", "vision.findallcolor", "ocr.glyph", "vision.findgray", "ocr.alphanumeric",
)

internal enum class RecognitionSection(val title: String) { MATCH("常用配置"), OUTPUT("结果变量"), ADVANCED("更多参数") }

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
    runtimeClient: RuntimeClient? = null,
    onConfirmSource: ((BlockContract, JsonObject, Boolean) -> Unit)? = null,
    onWorkflow: ((List<VisualBlockTree>) -> Unit)? = null,
) {
    // Resource refresh and opening the screen picker must not discard the current draft.
    var activeKind by remember(contract.kind, arguments) { mutableStateOf(contract.kind) }
    var drafts by remember(contract.kind, arguments) { mutableStateOf(mapOf(contract.kind to recognitionPropertyTexts(contract, arguments))) }
    var baselines by remember(contract.kind, arguments) { mutableStateOf(drafts) }
    val initialCapture = automaticCapture || arguments["autoCapture"]?.asBoolean == true
    var capture by remember(contract.kind, arguments) { mutableStateOf(initialCapture) }
    var discard by remember(contract.kind, arguments) { mutableStateOf(false) }
    var workflow by remember(contract.kind, arguments) { mutableStateOf<Pair<BlockContract, JsonObject>?>(null) }
    var overwriteConfirmed by remember(contract.kind, arguments) { mutableStateOf(false) }
    var manualActionConfirm by remember(contract.kind, arguments) { mutableStateOf<Pair<BlockContract,JsonObject>?>(null) }
    val activeContract = requireNotNull(BlockCatalog.find(activeKind))
    val inputs = drafts.getValue(activeKind)
    val effectiveCapture = if (automaticCapture) capture else inputs["autoCapture"] == "true"
    fun requestClose() {
        if (activeKind != contract.kind || capture != initialCapture || drafts.any { (kind, values) -> values != baselines[kind] }) discard = true
        else onDismiss()
    }
    fun update(name: String, value: String) { drafts = drafts + (activeKind to (drafts.getValue(activeKind) + (name to value)));if(name=="frameVariable") overwriteConfirmed=false }
    var error by remember { mutableStateOf<String?>(null) }
    var picker by remember { mutableStateOf<String?>(null) }
    var section by remember { mutableStateOf(RecognitionSection.MATCH) }
    val contentScroll = key(section) { rememberScrollState() }
    var showCoordinates by remember { mutableStateOf(true) }
    var showHelp by remember { mutableStateOf(false) }
    val imagePath = inputs["imagePath"].orEmpty()
    val previewLoad = recognitionPreview(snapshot, imagePath)
    val preview = previewLoad.preview
    val variables = (knownVariables + inputs.filterKeys { it.endsWith("Variable") }.values).filter(String::isNotBlank).distinct().sorted()
    LaunchedEffect(captureSelection) {
        captureSelection?.let { selection -> drafts = drafts + (activeKind to recognitionSelectionInputs(drafts.getValue(activeKind), selection)) }
    }
    LaunchedEffect(captureTemplatePath) {
        if (captureTemplatePath != null) runCatching {
            recognitionCapturedTemplate(inputs, activeKind, captureTemplatePath)
        }.onSuccess { drafts = drafts + (activeKind to it) }.onFailure { error = it.message }
    }
    val screenHeight = LocalConfiguration.current.screenHeightDp
    var fullScreen by remember { mutableStateOf(false) }
    if (visible && workflow == null) Dialog(onDismissRequest = ::requestClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(if (fullScreen) Modifier.fillMaxSize() else Modifier.widthIn(max = 550.dp).fillMaxWidth(.96f)
            .heightIn(max = min(540, (screenHeight - 32).coerceAtLeast(120)).dp),
            shape = RoundedCornerShape(if (fullScreen) 0.dp else 6.dp), color = Color.White, shadowElevation = 8.dp) {
            Column {
                RecognitionHeader(activeContract.title, ::requestClose, fullScreen) { fullScreen = !fullScreen }
                if (activeKind != "vision.result") {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 5.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(recognitionFrameSummary(inputs + ("autoCapture" to effectiveCapture.toString()), effectiveCapture), Modifier.weight(1f).clickable { section = RecognitionSection.ADVANCED },
                            color = AutoScriptPalette.TextSecondary, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        RecognitionChoice(when (recognitionScreenPickMode(activeKind)) {
                            ImageToolMode.CROP -> "截图取材"; ImageToolMode.COLOR, ImageToolMode.MULTI_COLOR -> "取点取色"; else -> "屏幕框选"
                        }, enabled = onPickFromScreen != null) { onPickFromScreen?.invoke(recognitionScreenPickMode(activeKind)) }
                        if (allowKindChange && snapshot != null) RecognitionChoice("类型 ▾") { picker = "kind" }
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
                Column(Modifier.weight(1f, fill = false).verticalScroll(contentScroll).padding(8.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    if (runtimeClient != null) RecognitionAvailabilityNotice(activeKind, runtimeClient)
                    if (automaticCapture && activeKind != "vision.result") RecognitionSettingRow("图像来源") {
                        RecognitionChoice(if (capture) "运行时截图" else "已有图像", Modifier.weight(1f)) { capture = !capture;overwriteConfirmed=false }
                    }
                    if (section == RecognitionSection.MATCH && activeKind in setOf("vision.findimage", "vision.findgray")) {
                        val templateSource = recognitionTemplateSource(inputs)
                        Row(Modifier.height(74.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                RecognitionSettingRow("模板选择") {
                                    RecognitionChoice(when (templateSource) {
                                        RecognitionTemplateSource.LIST -> "已选 ${recognitionTemplatePaths(inputs["imagePaths"].orEmpty()).size} 张 · 编辑"
                                        RecognitionTemplateSource.DIRECTORY -> inputs["imageDirectory"].orEmpty().removePrefix("assets/images/").ifBlank { "图片根目录" }
                                        else -> imagePath.substringAfterLast('/').ifBlank { "选择模板图片" }
                                    }, Modifier.weight(1f)) { picker = when (templateSource) {
                                        RecognitionTemplateSource.LIST -> "imagePaths"; RecognitionTemplateSource.DIRECTORY -> "imageDirectory"; else -> "imagePath"
                                    } }
                                }
                                RecognitionSettingRow("模板来源") {
                                    RecognitionChoice(if (activeKind == "vision.findgray") "单张 · 灰度匹配" else "${templateSource.title} ▾",
                                        Modifier.weight(1f), enabled = activeKind == "vision.findimage") { picker = "support" }
                                }
                            }
                            Box(Modifier.size(62.dp).background(AutoScriptPalette.VisualEditor.ControlBackground, RoundedCornerShape(4.dp)).border(1.dp, AutoScriptPalette.Divider, RoundedCornerShape(4.dp)).clickable {
                                picker = when (templateSource) { RecognitionTemplateSource.LIST -> "imagePaths"; RecognitionTemplateSource.DIRECTORY -> "imageDirectory"; else -> "imagePath" }
                            }, contentAlignment = Alignment.Center) {
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
                            RecognitionChoice("设计范围", Modifier.weight(1f), enabled = snapshot != null && activeKind != "ocr.alphanumeric") {
                                snapshot?.let {
                                    drafts = drafts + (activeKind to recognitionSelectionInputs(inputs,
                                        ImageToolCodeGen.VisualSelection(ImageToolMode.REGION,
                                            ImageToolCodeGen.Roi(0, 0, it.manifest.design.width, it.manifest.design.height), emptyList(), 0,
                                            it.manifest.design.width, it.manifest.design.height)))
                                    error = null
                                }
                            }
                        }
                    }
                    activeContract.properties.filter { property ->
                        property.path !in setOf("frameVariable", "imagePath", "region", "foundVariable", "xVariable", "yVariable", "leftVariable", "topVariable", "rightVariable", "bottomVariable", "autoCapture")
                    }.forEach { property ->
                        val propertySection = recognitionPropertySection(property.path)
                        if (activeKind != "vision.result" && propertySection != section &&
                            !(section == RecognitionSection.MATCH && propertySection == RecognitionSection.OUTPUT && property.required)) return@forEach
                        if (property.path == "imageDirectory" && activeKind == "vision.findimage") return@forEach
                        if (!recognitionPropertyVisible(property.path, inputs)) return@forEach
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
                                property.editor == BlockPropertyEditor.MULTI_COLOR_SAMPLES -> RecognitionChoice("${inputs[property.path].orEmpty().split(';').count { it.isNotBlank() }} 个采样点 · 编辑", Modifier.weight(1f)) { picker = "samplesEditor" }
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
                    if (section in setOf(RecognitionSection.MATCH, RecognitionSection.OUTPUT) && "foundVariable" in inputs) {
                        RecognitionSettingRow("是否找到") {
                            RecognitionInput(inputs["foundVariable"].orEmpty(), { update("foundVariable", it); error = null }, Modifier.weight(1f))
                            RecognitionChoice("选择") { picker = "foundVariable" }
                            if (section == RecognitionSection.OUTPUT) RecognitionChoice(if (showCoordinates) "收起坐标 ▴" else "显示坐标 ▾") { showCoordinates = !showCoordinates }
                        }
                        if (showCoordinates || section == RecognitionSection.MATCH) RecognitionSettingRow("坐标变量") {
                            RecognitionChoice("X:${inputs["xVariable"]}", Modifier.weight(1f)) { picker = "xVariable" }
                            RecognitionChoice("Y:${inputs["yVariable"]}", Modifier.weight(1f)) { picker = "yVariable" }
                        }
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        if (onManageVariables != null) RecognitionChoice("维护变量", onClick = onManageVariables)
                        if (activeKind == "vision.result") Text("数量 / 截断用序号0；逐项属性从1开始。", fontSize = 10.sp, color = AutoScriptPalette.TextSecondary)
                        Spacer(Modifier.weight(1f))
                    }
                    if (section == RecognitionSection.ADVANCED && activeKind != "vision.result") {
                        if ("frameVariable" in inputs && (!effectiveCapture || !recognitionUsesOwnCapture(activeKind))) RecognitionVariableRow(if (effectiveCapture) "截图暂存帧" else "帧变量", inputs["frameVariable"].orEmpty(), { update("frameVariable", it); error = null }) { picker = "frameVariable" }
                        if (activeKind == "vision.findimage") {
                            listOf("leftVariable", "topVariable", "rightVariable", "bottomVariable").forEachIndexed { index, field ->
                                RecognitionVariableRow(listOf("左边界变量", "上边界变量", "右边界变量", "下边界变量")[index], inputs[field].orEmpty(), { update(field, it); error = null }) { picker = field }
                            }
                            Text("范围变量留空使用固定值；变量在运行时必须为非负整数，右/下边界不包含。", color = AutoScriptPalette.TextSecondary, fontSize = 10.sp)
                        }
                        if ("frameVariable" in inputs) Text(when {
                            !effectiveCapture -> "读取已有图像，不截图、不释放用户帧；范围属于来源图像原像素。"
                            recognitionUsesOwnCapture(activeKind) -> "自动截图并释放内部临时帧；不覆盖用户图像变量。范围为原始屏幕像素。"
                            else -> "加入截图→识别→释放；截图暂存帧原值会被覆盖，请选择专用图像变量。"
                        }, color = AutoScriptPalette.TextSecondary, fontSize = 10.sp)
                        if(automaticCapture && effectiveCapture && !recognitionUsesOwnCapture(activeKind) && "frameVariable" in inputs)
                            DesignerToggle("专用暂存帧，允许覆盖并释放",overwriteConfirmed,true) { overwriteConfirmed=it }
                        if ("autoCapture" in inputs) {
                            if (!automaticCapture) RecognitionSettingRow("自动截图") { RecognitionChoice(if (effectiveCapture) "开启" else "关闭", Modifier.weight(1f)) { update("autoCapture", (!effectiveCapture).toString()) } }
                        }
                        RecognitionChoice(if (showHelp) "收起参数说明 ▴" else "查看参数说明 ▾", Modifier.fillMaxWidth()) { showHelp = !showHelp }
                        if (showHelp) Text(recognitionAlgorithmHelp(activeKind), color = AutoScriptPalette.TextSecondary, fontSize = 10.sp)
                    }
                }
                error?.let { Text(it, Modifier.fillMaxWidth().background(AutoScriptPalette.AccentSoft).padding(horizontal = 8.dp, vertical = 5.dp),
                    color = AutoScriptPalette.Danger, fontSize = 11.sp, maxLines = 3, overflow = TextOverflow.Ellipsis) }
                if (onWorkflow != null && activeKind in recognitionWaitKinds) RecognitionChoice("等待出现 / 消失 · 重试 / 连续未命中", Modifier.fillMaxWidth().padding(6.dp)) {
                    val parsed = parseRecognitionArguments(activeContract, inputs, resources)
                    error = parsed.second
                    if (parsed.first != null) {
                        if (!effectiveCapture) error = "等待识别需要每次采集新画面，请选择运行时截图"
                        else if(!recognitionUsesOwnCapture(activeKind) && !overwriteConfirmed) error="请先在更多参数确认专用暂存帧允许覆盖并释放"
                        else workflow = activeContract to requireNotNull(parsed.first).deepCopy()
                    }
                }
                RecognitionFooter("取消", confirmLabel, ::requestClose) {
                    val parsed = parseRecognitionArguments(activeContract, inputs, resources)
                    val configured = parsed.first?.deepCopy()?.apply {
                        if (automaticCapture && has("autoCapture")) addProperty("autoCapture", effectiveCapture)
                    }
                    error = parsed.second ?: configured?.let { recognitionProjectError(activeContract, it, snapshot, currentFlowId) }
                    if (configured != null && error == null && automaticCapture && (!effectiveCapture || !recognitionUsesOwnCapture(activeKind))) {
                        val frame = configured["frameVariable"]?.asString
                        if (snapshot != null && frame != null && calculationVariables(snapshot.manifest.variables, currentFlowId, snapshot.manifest.flows).none { it.name == frame && it.type == ProjectVariableType.IMAGE }) error = "已有图像需选择已声明的图像变量"
                    }
                    if(configured!=null && error==null && automaticCapture && effectiveCapture && !recognitionUsesOwnCapture(activeKind) && "frameVariable" in inputs && !overwriteConfirmed)
                        error="请在更多参数确认专用截图暂存帧允许覆盖并释放"
                    if (configured != null && error == null) {
                        if(!effectiveCapture && configured["successAction"]?.asString !in setOf(null,"none")) manualActionConfirm=activeContract to configured.deepCopy()
                        else if (onConfirmSource != null) onConfirmSource(activeContract, configured, effectiveCapture) else onConfirm(activeContract, configured)
                    }
                }
            }
        }
    }
    if(visible) manualActionConfirm?.let { (selected,configured) -> EditorPromptDialog("确认已有帧动作坐标", "已有帧可能来自裁剪、旋转或缩放。找到后的动作直接使用原屏像素；只有确定该帧与当前屏幕坐标一致才继续，否则取消并关闭动作。",
        {manualActionConfirm=null},{manualActionConfirm=null;if(onConfirmSource!=null) onConfirmSource(selected,configured,false) else onConfirm(selected,configured)},"取消","确认原屏坐标") }
    if (visible) picker?.let { field ->
        if (field == "samplesEditor") {
            RecognitionSamplesDialog(inputs["samples"].orEmpty(), { picker = null }) { update("samples", it); picker = null; error = null }
            return@let
        }
        if ((field == "imagePaths" && activeKind == "vision.findimage") || activeContract.properties.any { it.path == field && it.editor == BlockPropertyEditor.RESOURCE_LIST }) {
            RecognitionTemplatesDialog(resourcePaths(resources, com.autoscript.studio.generated.BlockResourceKind.IMAGE), recognitionTemplatePaths(inputs[field].orEmpty()), { picker = null }) {
                drafts = drafts + (activeKind to recognitionSelectTemplates(inputs, it)); picker = null; error = null
            }
            return@let
        }
        if (field == "bounds") {
            RecognitionBoundsDialog(inputs["region"].orEmpty(), snapshot?.takeUnless { activeKind == "ocr.alphanumeric" }, { picker = null }) {
                drafts = drafts + (activeKind to recognitionFixedRegionInputs(inputs, it)); picker = null; error = null
            }
            return@let
        }
        val resourceProperty = activeContract.properties.firstOrNull { it.path == field && it.editor == BlockPropertyEditor.RESOURCE }
        val kinds = visualRecognitionKinds.mapNotNull(BlockCatalog::find)
        val enumProperty = activeContract.properties.firstOrNull { it.path == field && it.editor == BlockPropertyEditor.ENUM }
        val requiredType = (if (field == "frameVariable") ProjectVariableType.IMAGE else null) ?: recognitionRequiredVariableType(activeKind, field, JsonObject().apply {
            addProperty("autoCapture", effectiveCapture)
            addProperty("field", inputs["field"].orEmpty())
        })
        val choices = when {
            field == "kind" -> kinds.map { it.title }
            field == "direction" -> recognitionDirections
            field == "successAction" -> recognitionActions.values.toList()
            field == "support" -> RecognitionTemplateSource.entries.map { it.title }
            field == "imageDirectory" -> resourcePaths(resources, com.autoscript.studio.generated.BlockResourceKind.IMAGE).flatMap { path ->
                val parts = path.split('/'); (2 until parts.size).map { parts.take(it).joinToString("/", postfix = "/") }
            }.distinct().sorted()
            resourceProperty != null -> resourcePaths(resources, resourceProperty.resourceKind)
            enumProperty != null -> enumProperty.choices
            requiredType != null && snapshot != null -> calculationVariables(snapshot.manifest.variables, currentFlowId, snapshot.manifest.flows).filter { it.type == requiredType }.map { it.name }
            else -> variables
        }
        RecognitionPicker(when (field) { "kind" -> "识别类型"; "support" -> "模板来源"; "imageDirectory" -> "模板文件夹"; else -> resourceProperty?.label ?: enumProperty?.label ?: "选择变量" }, choices,
            when (field) { "kind" -> activeContract.title; "support" -> recognitionTemplateSource(inputs).title; else -> inputs[field].orEmpty() },
            onDismiss = { picker = null }, onSelect = { value ->
                if (field == "kind") {
                    val next = kinds.first { it.title == value }
                    if (next.kind !in drafts) {
                        val initial = blockPropertyTexts(next, initialRecognitionArguments(next, requireNotNull(snapshot)))
                        drafts = drafts + (next.kind to initial); baselines = baselines + (next.kind to initial)
                    }
                    activeKind = next.kind
                    overwriteConfirmed = false
                } else if (field == "direction") {
                    update(field, recognitionDirections.indexOf(value).toString())
                } else if (field == "successAction") {
                    update(field, recognitionActions.entries.first { it.value == value }.key)
                } else if (field == "imageDirectory") {
                    drafts = drafts + (activeKind to recognitionSelectTemplateDirectory(inputs, value,
                        resourcePaths(resources, com.autoscript.studio.generated.BlockResourceKind.IMAGE)))
                } else if (field == "support") {
                    when (value) {
                        RecognitionTemplateSource.DIRECTORY.title -> { picker = "imageDirectory"; return@RecognitionPicker }
                        RecognitionTemplateSource.LIST.title -> { picker = "imagePaths"; return@RecognitionPicker }
                        else -> drafts = drafts + (activeKind to recognitionSelectSingleTemplate(inputs, imagePath))
                    }
                } else if (field != "support") {
                    update(field, value)
                }
                picker = null
                error = null
            }, allowNewValue = requiredType == null && enumProperty == null && resourceProperty == null && field !in setOf("kind", "support", "direction", "imageDirectory", "successAction"))
    }
    if (discard && visible) EditorPromptDialog("放弃识别参数？", "本次参数、图像来源及模板选择尚未加入，放弃后不会修改原积木。",
        { discard = false }, { discard = false; onDismiss() }, "继续编辑", "放弃")
    workflow?.let { (selected, configured) -> if (snapshot != null && onWorkflow != null) RecognitionWorkflowDialog(
        selected, configured, snapshot, currentFlowId, onManageVariables, onDismiss = { workflow = null }, onConfirm = onWorkflow, visible = visible) }
}

private val recognitionDirections = recognitionSearchDirections

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
    val baseline = remember(current) { current.filter { it in choices } }
    var discard by remember { mutableStateOf(false) }
    fun close() { if (selected != baseline) discard = true else onDismiss() }
    var search by remember { mutableStateOf("") }
    Dialog(onDismissRequest = ::close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth(.92f).widthIn(max = 540.dp).heightIn(max = 420.dp), color = Color.White) {
            Column {
                RecognitionHeader("模板 · 已选${selected.size}/64（按选择顺序）", ::close)
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
                RecognitionFooter("取消", "确定", ::close) { onConfirm(selected) }
            }
        }
    }
    if (discard) EditorPromptDialog("放弃模板选择？", "未确认的模板及顺序将丢弃。", { discard = false }, { discard = false; onDismiss() }, "继续选择", "放弃")
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
    val baseline = remember(region) { bounds }
    var discard by remember { mutableStateOf(false) }
    fun close() { if (bounds != baseline) discard = true else onDismiss() }
    var error by remember { mutableStateOf<String?>(null) }
    Dialog(onDismissRequest = ::close) {
        Surface(color = Color.White) {
            Column(Modifier.padding(8.dp)) {
                RecognitionHeader("范围 · 原始像素", ::close)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf("X", "Y", "宽", "高").forEachIndexed { index, name ->
                        Column(Modifier.weight(1f)) {
                            Text(name, Modifier.fillMaxWidth(), textAlign = TextAlign.Center, fontSize = 11.sp)
                            RecognitionInput(bounds[index], { bounds = bounds.toMutableList().apply { set(index, it) }; error = null }, numeric = true)
                        }
                    }
                }
                snapshot?.let { project -> RecognitionChoice("设计范围（不是设备分辨率）") { bounds = listOf("0", "0", project.manifest.design.width.toString(), project.manifest.design.height.toString()) } }
                error?.let { Text(it, color = AutoScriptPalette.Danger, fontSize = 11.sp) }
                RecognitionFooter("取消", "确定", ::close) {
                    val rectangle = imageRecognitionBounds(bounds)
                    if (rectangle == null) error = "请输入非负坐标和正数宽高，且不能溢出"
                    else onConfirm(listOf("left", "top", "right", "bottom").joinToString(",") { rectangle.get(it).asString })
                }
            }
        }
    }
    if (discard) EditorPromptDialog("放弃范围修改？", "未确认的坐标和尺寸将丢弃。", { discard = false }, { discard = false; onDismiss() }, "继续编辑", "放弃")
}

@Composable
internal fun RecognitionHeader(title: String, onDismiss: () -> Unit, fullScreen: Boolean = false, onToggleFullScreen: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().height(40.dp).padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), color = AutoScriptPalette.Accent, fontSize = 15.sp, fontWeight = FontWeight.Bold,
            maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false)))
        if (onToggleFullScreen != null) StudioPanelFullScreenButton(fullScreen, onToggleFullScreen)
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
internal fun RecognitionInput(value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier, numeric: Boolean = false, enabled: Boolean = true) {
    BasicTextField(value, { if (it.length <= 128) onChange(it) }, singleLine = true, enabled = enabled,
        textStyle = TextStyle(color = AutoScriptPalette.TextPrimary, fontSize = 12.sp, lineHeight = 14.sp, platformStyle = PlatformTextStyle(includeFontPadding = false), textAlign = if (numeric) TextAlign.Center else TextAlign.Start),
        keyboardOptions = KeyboardOptions(keyboardType = if (numeric) KeyboardType.Number else KeyboardType.Text),
        modifier = modifier.height(studioCompactControlHeight(30, LocalConfiguration.current.fontScale).dp).background(AutoScriptPalette.VisualEditor.ControlBackground, RoundedCornerShape(3.dp)).border(1.dp, AutoScriptPalette.Divider, RoundedCornerShape(3.dp)),
        decorationBox = { inner -> Box(Modifier.fillMaxSize().padding(horizontal = 6.dp), contentAlignment = if (numeric) Alignment.Center else Alignment.CenterStart) { inner() } })
}

@Composable
internal fun RecognitionChoice(value: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Box(modifier.height(studioCompactControlHeight(30, LocalConfiguration.current.fontScale).dp).border(1.dp, AutoScriptPalette.Divider, RoundedCornerShape(3.dp)).clickable(enabled = enabled, onClick = onClick).padding(horizontal = 6.dp), contentAlignment = Alignment.Center) {
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
private fun RecognitionSamplesDialog(value: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var draft by remember(value) { mutableStateOf(value) }
    var error by remember { mutableStateOf<String?>(null) }
    var discard by remember { mutableStateOf(false) }
    fun close() { if (draft != value) discard = true else onDismiss() }
    StudioPanelDialog("偏移采样点", ::close, "确定", { onConfirm(draft) }) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("格式：X偏移,Y偏移,#RRGGBB,容差；多个点用英文 ; 分隔。最多64点，加入时统一校验。", fontSize = 11.sp, color = AutoScriptPalette.TextSecondary)
            BasicTextField(draft, {
                if (it.length <= 8192) { draft = it; error = null } else error = "采样点文本最多8192字符，未接受本次输入"
            }, textStyle = TextStyle(fontSize = 12.sp, color = AutoScriptPalette.TextPrimary),
                modifier = Modifier.fillMaxWidth().height(120.dp).background(AutoScriptPalette.VisualEditor.ControlBackground, RoundedCornerShape(4.dp))
                    .border(1.dp, AutoScriptPalette.Divider, RoundedCornerShape(4.dp)).verticalScroll(rememberScrollState()).padding(8.dp))
            Text("${draft.split(';').count { it.isNotBlank() }} 个采样点 · ${draft.length}/8192字符", fontSize = 10.sp, color = AutoScriptPalette.TextSecondary)
            error?.let { Text(it, fontSize = 11.sp, color = AutoScriptPalette.Danger) }
        }
    }
    if (discard) EditorPromptDialog("放弃采样点修改？", "未确认的偏移采样点将丢弃。", { discard = false }, { discard = false; onDismiss() }, "继续编辑", "放弃")
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

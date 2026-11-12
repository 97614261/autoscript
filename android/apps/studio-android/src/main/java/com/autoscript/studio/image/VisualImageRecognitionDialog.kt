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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autoscript.studio.StudioDialog as Dialog
import androidx.compose.ui.window.DialogProperties
import com.autoscript.project.store.ProjectSnapshot
import com.autoscript.project.store.ProjectVariableType
import com.autoscript.runtime.client.RuntimeClient
import com.autoscript.studio.generated.BlockContract
import com.autoscript.studio.generated.BlockPropertyEditor
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
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
        if (variables.any { !it.matches(com.autoscript.project.store.ProjectNames.pattern) }) {
            return null to "变量名支持中文、英文字母、数字和下划线，不能以数字开头，最多64字符"
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

private data class RecognitionPreview(val bitmap: Bitmap, val width: Int, val height: Int)
private data class RecognitionPreviewLoad(val preview: RecognitionPreview? = null, val error: String? = null)

private data class TemplateRegionLoad(val paths: List<String>, val ready: Boolean = false,
    val region: ScreenshotTemplateRegion? = null)

@Composable
private fun recognitionTemplateRegion(snapshot: ProjectSnapshot?, paths: List<String>): TemplateRegionLoad {
    return key(snapshot?.directory, snapshot?.manifest?.resources, snapshot?.resourceRevision, paths) {
    val result by produceState(TemplateRegionLoad(paths)) {
        value = TemplateRegionLoad(paths)
        val region = withContext(Dispatchers.IO) {
            if (snapshot == null || paths.size !in 1..64) null else combinedTemplateRegion(paths.map { path ->
                ensureActive()
                runCatching { imageRecognitionFile(snapshot, path).inputStream().buffered().use(ScreenshotTemplatePng::read) }.getOrNull()
            })
        }
        value = TemplateRegionLoad(paths, ready = true, region = region)
    }
    result
    }
}

@Composable
private fun recognitionPreview(snapshot: ProjectSnapshot?, path: String): RecognitionPreviewLoad {
    return key(snapshot?.manifest?.resources, snapshot?.directory, snapshot?.resourceRevision, path) {
    val preview by produceState(RecognitionPreviewLoad()) {
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
    preview
    }
}

internal val visualRecognitionKinds = listOf(
    "vision.findimage", "vision.findallimages", "vision.findcolor", "vision.findmulticolor", "vision.getcolor",
    "vision.comparecolor", "vision.countcolor", "vision.findallcolor", "ocr.glyph", "vision.findgray", "ocr.alphanumeric",
)

internal enum class RecognitionSection { MATCH, OUTPUT, ADVANCED }

internal fun recognitionPropertySection(path: String): RecognitionSection = when {
    path == "frameVariable" || path in setOf("frequency", "autoCapture", "successAction", "offsetX", "offsetY", "actionDurationMs", "allResultsAction",
        "actionAnchor", "actionRandom", "actionRandomX", "actionRandomY", "leftVariable", "topVariable", "rightVariable", "bottomVariable", "applyToAll", "allMatches", "beforeWaitMs", "afterWaitMs") -> RecognitionSection.ADVANCED
    path.endsWith("Variable") && path !in setOf("sourceVariable") -> RecognitionSection.OUTPUT
    else -> RecognitionSection.MATCH
}

internal data class RecognitionInsertRequest(
    val contract: BlockContract,
    val arguments: JsonObject,
    val context: EditorPasteRequest,
    val childSlot: String? = null,
    val position: EditorInsertPosition = EditorInsertPosition.BELOW,
    val onCommitted: () -> Unit = {},
)

/** Empty resource paths are allowed only in an unsaved editor draft, never at confirmation. */
internal fun initialRecognitionArguments(contract: BlockContract, snapshot: ProjectSnapshot): JsonObject =
    requireNotNull(initialBlockArguments(contract, snapshot.manifest.flows, snapshot.manifest.resources, "", allowMissingResources = true)).apply {
        if (contract.kind == "ocr.alphanumeric") addProperty("includeSymbols", true)
        if (contract.properties.any { it.path == "actionAnchor" }) addProperty("actionAnchor", "center")
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
        if ("polygon" in inputs) result=result+("polygon" to ImageSearchPolygon.encode(selection.polygon))
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
    recognitionActionError(inputs)?.let { return null to it }
    if (contract.properties.any { it.required && it.path.endsWith("Variable") && inputs[it.path].isNullOrBlank() }) return null to "请填写帧与结果变量名"
    val variables = inputs.filterKeys { it.endsWith("Variable") }.filterValues(String::isNotBlank)
    if (variables.any { (key, value) -> if (scalarInputTypes(contract.kind, key) != null) !isScalarSource(value)
        else !value.matches(com.autoscript.project.store.ProjectNames.pattern) }) return null to "请选择变量或有效的输入表达式；输出变量名最多64字符"
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
    if (!inputs["polygon"].isNullOrEmpty()) {
        val points = runCatching { ImageSearchPolygon.decode(inputs.getValue("polygon")) }.getOrElse {
            return null to "多边形选区无效，请重新调整"
        }
        val bounds = ImageSearchPolygon.bounds(points)
        val rect = inputs["region"]?.split(',')?.map { it.trim().toIntOrNull() }
        if (rect?.size != 4 || rect.any { it == null } || bounds.left < rect[0]!! || bounds.top < rect[1]!! ||
            bounds.right > rect[2]!! || bounds.bottom > rect[3]!!) return null to "多边形顶点必须在搜索范围内"
        if (listOf("leftVariable", "topVariable", "rightVariable", "bottomVariable").any { !inputs[it].isNullOrBlank() })
            return null to "多边形使用截图坐标，请清除范围变量或改用矩形"
    }
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
    if (arguments["autoCapture"]?.asBoolean == true) required += "screen.capture"
    if (action != "none") required += "input.basic"
    if (action != "none" || contract.kind == "vision.findallimages") required += "core.task"
    val missing = required - snapshot.manifest.capabilities.toSet()
    return if (missing.isEmpty()) null else "项目未启用能力：${missing.sorted().joinToString()}（请在设置中启用）"
}

internal fun recognitionFrameVariableError(contract: BlockContract, arguments: JsonObject,
    snapshot: ProjectSnapshot?, flowId: String, capture: Boolean): String? {
    if (snapshot == null || !arguments.has("frameVariable") || capture && recognitionUsesOwnCapture(contract.kind)) return null
    val frame = arguments["frameVariable"].asString
    val declared = calculationVariables(snapshot.manifest.variables, flowId, snapshot.manifest.flows)
        .any { it.name == frame && it.type == ProjectVariableType.IMAGE }
    if (declared) return null
    return if (capture) "截图暂存需选择已声明的图像变量；请先维护变量，或改用可自动管理临时帧的识别方式"
        else "共用图像需选择已声明的图像变量，并先用截图或加载图片积木赋值；也可切换为每次截图"
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
    onPickPolygon: ((String) -> Unit)? = null,
    onDismiss: () -> Unit,
    onConfirm: (BlockContract, JsonObject) -> Unit,
    currentFlowId: String = "",
    onManageVariables: (() -> Unit)? = null,
    runtimeClient: RuntimeClient? = null,
    onConfirmSource: ((BlockContract, JsonObject, Boolean) -> Unit)? = null,
    onWorkflow: ((List<VisualBlockTree>) -> Unit)? = null,
    applyInitialTemplateRegion: Boolean = false,
) {
    // Resource refresh and opening the screen picker must not discard the current draft.
    var activeKind by remember(contract.kind, arguments) { mutableStateOf(contract.kind) }
    var drafts by remember(contract.kind, arguments) { mutableStateOf(mapOf(contract.kind to recognitionPropertyTexts(contract, arguments))) }
    var baselines by remember(contract.kind, arguments) { mutableStateOf(drafts) }
    var regionFallbacks by remember(contract.kind, arguments) { mutableStateOf(drafts.mapValues { it.value["region"] }) }
    var manualRegions by remember(contract.kind, arguments) {
        mutableStateOf(if (applyInitialTemplateRegion) emptySet<String>() else setOf(contract.kind))
    }
    val defaultCaptureSource = rememberDefaultImageCaptureSource()
    val initialCapture = remember(contract.kind, arguments) {
        initialImageCaptureSource(automaticCapture, arguments["autoCapture"]?.asBoolean == true, defaultCaptureSource).capture
    }
    var capture by remember(contract.kind, arguments) { mutableStateOf(initialCapture) }
    var discard by remember(contract.kind, arguments) { mutableStateOf(false) }
    var workflow by remember(contract.kind, arguments) { mutableStateOf<Pair<BlockContract, JsonObject>?>(null) }
    var overwriteConfirmed by remember(contract.kind, arguments) { mutableStateOf(false) }
    var manualActionConfirm by remember(contract.kind, arguments) { mutableStateOf<Pair<BlockContract,JsonObject>?>(null) }
    val activeContract = requireNotNull(BlockCatalog.find(activeKind))
    val inputs = drafts.getValue(activeKind)
    val effectiveCapture = if (automaticCapture) capture else inputs["autoCapture"] == "true"
    val canChooseCaptureSource = "frameVariable" in inputs && (automaticCapture || recognitionUsesOwnCapture(activeKind))
    fun requestClose() {
        if (activeKind != contract.kind || capture != initialCapture || drafts.any { (kind, values) -> values != baselines[kind] }) discard = true
        else onDismiss()
    }
    fun update(name: String, value: String) {
        if (name in setOf("region", "leftVariable", "topVariable", "rightVariable", "bottomVariable")) manualRegions += activeKind
        val shapeReset=if(name in setOf("region","leftVariable","topVariable","rightVariable","bottomVariable")) mapOf("polygon" to "") else emptyMap()
        drafts = drafts + (activeKind to (drafts.getValue(activeKind) + shapeReset + (name to value)))
        if (name == "frameVariable") overwriteConfirmed = false
    }
    var error by remember { mutableStateOf<String?>(null) }
    var picker by remember { mutableStateOf<String?>(null) }
    var glyphPalette by remember { mutableStateOf<GlyphPaletteDraft?>(null) }
    var colorPaletteDraft by remember { mutableStateOf<GlyphPaletteDraft?>(null) }
    var colorPalettePicking by remember { mutableStateOf(false) }
    val contentScroll = rememberScrollState()
    val parameterScroll = rememberScrollState()
    var showOutputs by remember { mutableStateOf(true) }
    var showAdvanced by remember { mutableStateOf(false) }
    var showHelp by remember { mutableStateOf(false) }
    val imagePath = inputs["imagePath"].orEmpty()
    val previewLoad = recognitionPreview(snapshot, imagePath)
    val preview = previewLoad.preview
    val templatePaths = if (activeKind in setOf("vision.findimage", "vision.findgray", "vision.findallimages"))
        recognitionSelectedTemplatePaths(inputs, resources) else emptyList()
    val templateRegion = recognitionTemplateRegion(snapshot, templatePaths)
    fun templateAwareInputs(): Map<String, String>? {
        if (templatePaths.isEmpty() || activeKind in manualRegions || !inputs["polygon"].isNullOrEmpty() || !effectiveCapture) return inputs
        if (!templateRegion.ready || templateRegion.paths != templatePaths) {
            error = "正在读取模板范围，请稍候再加入"
            return null
        }
        return if (templateRegion.region != null) recognitionTemplateRegionInputs(inputs, templateRegion.region, true)
            else regionFallbacks[activeKind]?.let { inputs + ("region" to it) } ?: inputs
    }
    LaunchedEffect(templateRegion, activeKind, manualRegions, effectiveCapture) {
        if (templateRegion.ready && templateRegion.paths == templatePaths && activeKind !in manualRegions && effectiveCapture &&
            drafts.getValue(activeKind)["polygon"].isNullOrEmpty()) {
            val current = drafts.getValue(activeKind)
            val fallback = regionFallbacks[activeKind]
            val updated = if (templateRegion.region != null) recognitionTemplateRegionInputs(current, templateRegion.region, true)
                else if (fallback != null) current + ("region" to fallback) else current
            drafts = drafts + (activeKind to updated)
            // Derived initial defaults are not user edits. A later template selection still is.
            baselines[activeKind]?.let { baseline ->
                if (recognitionSelectedTemplatePaths(baseline, resources) == templatePaths) {
                    val adjusted = if (templateRegion.region != null) recognitionTemplateRegionInputs(baseline, templateRegion.region, true)
                        else if (fallback != null) baseline + ("region" to fallback) else baseline
                    baselines = baselines + (activeKind to adjusted)
                }
            }
        }
    }
    val variables = (knownVariables + inputs.filterKeys { it.endsWith("Variable") }.values).filter(String::isNotBlank).distinct().sorted()
    LaunchedEffect(captureSelection) {
        captureSelection?.let { selection ->
            if (colorPalettePicking && glyphPalette != null) {
                colorPaletteDraft = colorPaletteDraft?.let { recognitionPaletteSelection(it, selection) }
                colorPalettePicking = false
            } else {
                if (selection.roi != null) manualRegions += activeKind
                drafts = drafts + (activeKind to recognitionSelectionInputs(drafts.getValue(activeKind), selection))
            }
        }
    }
    LaunchedEffect(captureTemplatePath) {
        if (captureTemplatePath != null) runCatching {
            recognitionCapturedTemplate(inputs, activeKind, captureTemplatePath)
        }.onSuccess {
            drafts = drafts + (activeKind to it)
            manualRegions -= activeKind
        }.onFailure { error = it.message }
    }
    var fullScreen by remember { mutableStateOf(false) }
    ImageWorkspaceTheme {
    CompositionLocalProvider(LocalImageWorkspace provides null) {
    if (visible && workflow == null) Dialog(onDismissRequest = ::requestClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BoxWithConstraints((if (fullScreen) Modifier.fillMaxSize() else Modifier.fillMaxWidth()).imePadding(), contentAlignment = Alignment.Center) {
        val short = studioShortViewport(maxHeight.value)
        val landscape = androidx.compose.ui.platform.LocalConfiguration.current.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        Surface(if (fullScreen) Modifier.fillMaxSize() else Modifier.width(studioToolPanelWidth(maxWidth.value, maxHeight.value, LocalDensity.current.fontScale, landscapeWidthDp = 500).dp)
            .heightIn(max = min(540f, maxHeight.value).dp),
            shape = RoundedCornerShape(if (fullScreen) 0.dp else 10.dp), color = MaterialTheme.colorScheme.surface, contentColor = MaterialTheme.colorScheme.onSurface,
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), shadowElevation = 8.dp) {
            val navigation: @Composable () -> Unit = {
                if (activeKind != "vision.result") Row(Modifier.fillMaxWidth().padding(6.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if ("frameVariable" in inputs) Box(Modifier.weight(1f)) {
                        ImageCaptureSourceControl(ImageCaptureSource.fromCapture(effectiveCapture), label="来源", showDescription=false,
                            onSelect=if (canChooseCaptureSource) { source ->
                                if (automaticCapture) capture=source.capture else update("autoCapture",source.capture.toString())
                                overwriteConfirmed=false;error=null
                            } else null)
                    } else Spacer(Modifier.weight(1f))
                    RecognitionChoice(when (recognitionScreenPickMode(activeKind)) {
                        ImageToolMode.CROP -> "截模板"; ImageToolMode.COLOR, ImageToolMode.MULTI_COLOR -> "取色"; else -> "框选"
                    },enabled=onPickFromScreen!=null) {onPickFromScreen?.invoke(recognitionScreenPickMode(activeKind))}
                    if (allowKindChange && snapshot!=null) RecognitionChoice("类型 ▾") {picker="kind"}
                }
            }
            val configuration: @Composable () -> Unit = {
                if ("frameVariable" in inputs && (!effectiveCapture || !recognitionUsesOwnCapture(activeKind))) {
                    RecognitionVariableRow(if(effectiveCapture) "截图暂存" else "来源图像",inputs["frameVariable"].orEmpty(),
                        {update("frameVariable",it);error=null}, {picker="frameVariable"})
                    if (automaticCapture && effectiveCapture) ImageToolToggle("允许覆盖并释放暂存帧",overwriteConfirmed,true) {overwriteConfirmed=it}
                }
                if (activeKind in setOf("vision.findimage","vision.findgray","vision.findallimages")) {
                    val templateSource=recognitionTemplateSource(inputs)
                    fun pickTemplate() {picker=when(templateSource) {
                        RecognitionTemplateSource.LIST -> "imagePaths"
                        RecognitionTemplateSource.DIRECTORY -> "imageDirectory"
                        else -> "imagePath"
                    }}
                    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                        Box(Modifier.size(36.dp).background(MaterialTheme.colorScheme.surfaceContainerHighest,RoundedCornerShape(4.dp))
                            .clickable {pickTemplate()},contentAlignment=Alignment.Center) {
                            if(preview!=null) Image(preview.bitmap.asImageBitmap(),"选择模板",Modifier.fillMaxSize(),contentScale=ContentScale.Fit)
                            else Text("模板",fontSize=10.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        RecognitionChoice(when(templateSource) {
                            RecognitionTemplateSource.LIST -> "已选 ${recognitionTemplatePaths(inputs["imagePaths"].orEmpty()).size} 张"
                            RecognitionTemplateSource.DIRECTORY -> inputs["imageDirectory"].orEmpty().removePrefix("assets/images/").ifBlank {"图片目录"}
                            else -> imagePath.substringAfterLast('/').ifBlank {"选择模板"}
                        },Modifier.weight(1f)) {pickTemplate()}
                        if(activeKind=="vision.findimage") RecognitionChoice(when(templateSource) {
                            RecognitionTemplateSource.SINGLE -> "单张 ▾";RecognitionTemplateSource.LIST -> "多张 ▾";else -> "目录 ▾"
                        }) {picker="support"}
                    }
                    if(imagePath.isNotBlank()) previewLoad.error?.let {ImageWorkspaceHint(it,error=true)}
                }
                    if ("region" in inputs) {
                        Column(verticalArrangement=Arrangement.spacedBy(3.dp)) {
                        val region = inputs.getValue("region").split(',').map { it.toLongOrNull() }
                        val bounds = if (region.size == 4 && region.all { it != null }) {
                            listOf(region[0]!!, region[1]!!, region[2]!! - region[0]!!, region[3]!! - region[1]!!).map(Long::toString)
                        } else listOf("", "", "", "")
                        val polygonCount=remember(inputs["polygon"]) {runCatching {ImageSearchPolygon.decode(inputs["polygon"].orEmpty()).size}.getOrDefault(0)}
                        RecognitionSettingRow("范围", onLabelClick = onPickFromScreen?.let { pick -> { pick(ImageToolMode.REGION) } }) {
                            RecognitionChoice(if(polygonCount>0) "多边形 · ${polygonCount}点" else "X:${bounds[0]} Y:${bounds[1]} · ${bounds[2]}×${bounds[3]}", Modifier.weight(1f)) {
                                if (polygonCount > 0) onPickPolygon?.invoke(inputs["polygon"].orEmpty()) else picker = "bounds"
                            }
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                            if (templatePaths.isNotEmpty()) RecognitionChoice("模板范围", Modifier.weight(1f)) {
                                val origin = templateRegion.region.takeIf { templateRegion.ready && templateRegion.paths == templatePaths }
                                if (origin == null) error = if (!templateRegion.ready) "正在读取模板范围" else "旧模板未记录截图位置，请重新截图保存或屏幕框选"
                                else {
                                    drafts = drafts + (activeKind to recognitionFixedRegionInputs(inputs, origin.regionText()))
                                    manualRegions -= activeKind
                                    error = null
                                }
                            }
                            RecognitionChoice("框选", Modifier.weight(1f), enabled = onPickFromScreen != null) { onPickFromScreen?.invoke(ImageToolMode.REGION) }
                            if (activeKind in ImageSearchPolygon.kinds) RecognitionChoice(if (polygonCount > 0) "多边形✓" else "多边形",
                                Modifier.weight(1f), enabled = onPickPolygon != null) { onPickPolygon?.invoke(inputs["polygon"].orEmpty()) }
                            if (snapshot != null && activeKind != "ocr.alphanumeric") RecognitionChoice(if (templateRegion.region != null) "全屏" else "设计", Modifier.weight(1f)) {
                                snapshot?.let {
                                    manualRegions += activeKind
                                    drafts = drafts + (activeKind to recognitionSelectionInputs(inputs,
                                        ImageToolCodeGen.VisualSelection(ImageToolMode.REGION,
                                            ImageToolCodeGen.Roi(0, 0, templateRegion.region?.frameWidth ?: it.manifest.design.width, templateRegion.region?.frameHeight ?: it.manifest.design.height), emptyList(), 0,
                                            templateRegion.region?.frameWidth ?: it.manifest.design.width, templateRegion.region?.frameHeight ?: it.manifest.design.height)))
                                    error = null
                                }
                            }
                        }
                        if (polygonCount > 0) RecognitionChoice("改用矩形") { update("polygon", ""); error = null }
                        if (showHelp && templatePaths.isNotEmpty()) Text(
                            templateRegion.region?.let { "原屏 ${it.frameWidth}×${it.frameHeight}" } ?: "模板未记录原屏位置",
                            color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp)
                        }
                    }
            }
            val properties: @Composable (RecognitionSection) -> Unit = { group ->
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    val fields = activeContract.properties.filter { property ->
                        val propertySection = recognitionPropertySection(property.path)
                        property.path !in setOf("frameVariable", "imagePath", "region", "foundVariable", "xVariable", "yVariable", "leftVariable", "topVariable", "rightVariable", "bottomVariable", "autoCapture", "polygon") &&
                            property.path !in recognitionActionFields &&
                            (if (activeKind == "vision.result") group == RecognitionSection.MATCH else propertySection == group) &&
                            !(property.path == "imageDirectory" && activeKind == "vision.findimage" || property.path == "imagePaths" && activeKind in setOf("vision.findimage", "vision.findallimages")) &&
                            !(property.path == "limit" && activeKind == "vision.countcolor" && inputs["limit"] == "0") &&
                            recognitionPropertyVisible(property.path, inputs)
                    }
                    val compact = fields.filter { it.editor in setOf(BlockPropertyEditor.INTEGER, BlockPropertyEditor.BOOLEAN) || it.path.endsWith("Variable") }
                    StudioCompactGrid(compact, cellDp = 112, maximum = 2) { property, cell ->
                        if (property.path == "direction") Box(cell) {
                            ImageToolChoice("方向", inputs[property.path] ?: "0", recognitionDirections.mapIndexed { index, label -> index.toString() to label }, true, dense = true) {
                                update(property.path, it); error = null
                            }
                        } else if (property.path.endsWith("Variable")) RecognitionVariableRow(when (property.path) {
                            "templateVariable" -> "模板"; "scoreVariable" -> "得分"; "imageVariable" -> "图像"
                            "textVariable" -> "文字"; "coverageVariable" -> "覆盖率"; "resultVariable" -> if (activeKind == "vision.countcolor") "数量" else "结果"
                            "targetVariable" -> "接收"; else -> property.label.removeSuffix("变量")
                        }, inputs[property.path].orEmpty(), { update(property.path, it); error = null }, { picker = property.path }, cell)
                        else if (property.editor == BlockPropertyEditor.BOOLEAN) Box(cell) {
                            ImageToolToggle(property.label, inputs[property.path] == "true", true) { update(property.path, it.toString()); error = null }
                        } else ImageToolCompactInput(when (property.path) {
                            "tolerance" -> "容差"; "similarityPermille" -> "相似‰"; "minimumConfidencePermille" -> "置信‰"
                            "beforeWaitMs" -> "前等ms"; "afterWaitMs" -> "后等ms"; "frequency" -> "频次"
                            "limit" -> "数量"; else -> property.label
                        }, inputs[property.path].orEmpty(), true, cell, numeric = true, dense = true,
                            trailing = if (recognitionValuePresets(property.path, activeKind).isEmpty()) null else ({
                                Icon(Icons.Filled.KeyboardArrowDown, "常用数值 · ${property.label}",
                                    Modifier.size(20.dp).clickable { picker = "preset:${property.path}" },
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            })) { update(property.path, it); error = null }
                    }
                    fields.filter { it !in compact }.forEach { property ->
                        RecognitionSettingRow(when (property.path) {
                            "tolerance" -> "像素容差"; "dictionaryPath" -> "字库选择"; "anchorRgb" -> "锚点颜色"
                            "similarityPermille" -> "相似度‰"; "minimumConfidencePermille" -> "置信度‰"
                            "templateVariable" -> "命中模板"; "scoreVariable" -> "匹配得分"; "imageVariable" -> "匹配图像"
                            "limit" -> if (activeKind == "vision.countcolor") "达到数量即停" else "数量上限"; "additionalColors" -> if (activeKind in recognitionColorGroupKinds) "匹配色组" else "前景色组"
                            "samples" -> "偏移点"; "sourceVariable" -> "结果来源"; "targetVariable" -> "接收变量"
                            "successAction" -> "找到后"; "resultVariable" -> if (activeKind == "vision.countcolor") "计数变量" else "结果变量"
                            "field" -> "结果属性"; "index" -> "结果序号"; else -> property.label
                        }, onLabelClick = if (recognitionValuePresets(property.path, activeKind).isEmpty()) null else {
                            { picker = "preset:${property.path}" }
                        }) {
                            when {
                                property.path == "additionalColors" -> {
                                    val colorGroup = activeKind in recognitionColorGroupKinds
                                    val count = inputs[property.path].orEmpty().split(';').count { it.isNotBlank() } + 1
                                    RecognitionChoice(if (colorGroup) "$count 色 · 任意命中 · 编辑" else "编辑前景色组", Modifier.weight(1f)) {
                                        runCatching { glyphPaletteFromInputs(inputs[if (colorGroup) "rgb" else "foregroundRgb"].orEmpty(),inputs["tolerance"].orEmpty(),inputs["additionalColors"].orEmpty()) }
                                            .onSuccess { glyphPalette=it; colorPaletteDraft=it; colorPalettePicking=false }.onFailure { error="请检查主色、容差和附加色格式" }
                                    }
                                    if (!colorGroup) RecognitionInput(inputs[property.path].orEmpty(), { update(property.path,it); error=null }, Modifier.weight(1f))
                                }
                                property.editor == BlockPropertyEditor.COLOR -> {
                                    RecognitionInput(inputs[property.path].orEmpty(), { update(property.path, it); error = null }, Modifier.weight(1f))
                                    StudioColorButton(property.label, inputs[property.path].orEmpty(), StudioColorFormat.RGB_HEX) { update(property.path, it); error = null }
                                }
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
                        }
                    }
                    }
            }
            val parameters: @Composable () -> Unit = {
                if (activeKind == "vision.countcolor") ImageToolToggle("达到数量即停",inputs["limit"]!="0",true) {
                    update("limit",if(it) "1000" else "0");error=null
                }
                properties(RecognitionSection.MATCH)
                if ("successAction" in inputs) RecognitionActionFields(inputs) { field, value -> update(field, value); error = null }
                if (activeKind != "vision.result") {
                    RecognitionDisclosure("结果变量",showOutputs,inputs["foundVariable"] ?: inputs["textVariable"] ?: inputs["resultVariable"].orEmpty()) {showOutputs=!showOutputs}
                }
                if (showOutputs || activeKind == "vision.result") {
                    StudioCompactGrid(listOf("foundVariable" to "找到", "xVariable" to "X", "yVariable" to "Y").filter { it.first in inputs },
                        cellDp = 100, maximum = 3) { (field, label), cell ->
                        RecognitionVariableRow(label, inputs[field].orEmpty(), { update(field, it); error = null }, { picker = field }, cell)
                    }
                    properties(RecognitionSection.OUTPUT)
                    if (onManageVariables!=null) RecognitionChoice("管理变量",onClick=onManageVariables)
                }
                if (activeKind != "vision.result") {
                    RecognitionDisclosure("高级参数",showAdvanced) {showAdvanced=!showAdvanced}
                    if (showAdvanced) {
                        properties(RecognitionSection.ADVANCED)
                        if (activeKind=="vision.findimage") {
                            StudioCompactGrid(listOf("leftVariable" to "左边界", "topVariable" to "上边界", "rightVariable" to "右边界", "bottomVariable" to "下边界"), cellDp = 112, maximum = 2) { (field, label), cell ->
                                RecognitionVariableRow(label, inputs[field].orEmpty(), {update(field,it);error=null}, {picker=field}, cell)
                            }
                        }
                    }
                    if (showAdvanced && onWorkflow!=null && activeKind in recognitionWaitKinds) RecognitionChoice("等待 / 重试",Modifier.fillMaxWidth()) {
                        val parsed=parseRecognitionArguments(activeContract,templateAwareInputs() ?: return@RecognitionChoice,resources)
                        error=parsed.second
                        if(parsed.first!=null) {
                            if(!effectiveCapture) error="等待识别需要新画面，请选择每次截图"
                            else if(!recognitionUsesOwnCapture(activeKind) && !overwriteConfirmed) error="请确认专用暂存帧允许覆盖并释放"
                            else workflow=activeContract to requireNotNull(parsed.first).deepCopy()
                        }
                    }
                    if(showAdvanced) RecognitionDisclosure("参数说明",showHelp) {showHelp=!showHelp}
                    if (showHelp && showAdvanced) {
                        Text(recognitionAlgorithmHelp(activeKind),fontSize=10.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        if(runtimeClient!=null) RecognitionAvailabilityNotice(activeKind,runtimeClient)
                    }
                }
            }
            Column {
                RecognitionHeader(activeContract.title,::requestClose,fullScreen,compact=true) {fullScreen=!fullScreen}
                // Navigation scrolls with the form when IME leaves only a narrow strip.
                if(!short) navigation()
                BoxWithConstraints(Modifier.weight(1f,fill=false).fillMaxWidth()) {
                    if (activeKind != "vision.result" && recognitionFormSideBySide(maxWidth.value,landscape,LocalDensity.current.fontScale)) Row(Modifier.fillMaxWidth().padding(6.dp),horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                        Column(Modifier.weight(1f).verticalScroll(contentScroll),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                            if(short) navigation()
                            configuration()
                        }
                        Column(Modifier.weight(1f).verticalScroll(parameterScroll),verticalArrangement=Arrangement.spacedBy(4.dp)) {parameters()}
                    } else Column(Modifier.fillMaxWidth().verticalScroll(contentScroll).padding(6.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                        if(short) navigation()
                        configuration();parameters()
                    }
                }
                error?.let { Text(it, Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.errorContainer).padding(horizontal = 8.dp, vertical = 5.dp),
                    color = MaterialTheme.colorScheme.onErrorContainer, fontSize = 11.sp, maxLines = 3, overflow = TextOverflow.Ellipsis) }
                RecognitionFooter("取消", confirmLabel, ::requestClose, compact = true) {
                    val parsed = parseRecognitionArguments(activeContract, templateAwareInputs() ?: return@RecognitionFooter, resources)
                    val configured = parsed.first?.deepCopy()?.apply {
                        if (automaticCapture && has("autoCapture")) addProperty("autoCapture", effectiveCapture)
                    }
                    error = parsed.second ?: configured?.let { recognitionProjectError(activeContract, it, snapshot, currentFlowId) }
                    if (configured != null && error == null) error = recognitionFrameVariableError(activeContract, configured, snapshot, currentFlowId, effectiveCapture)
                    if(configured!=null && error==null && automaticCapture && effectiveCapture && !recognitionUsesOwnCapture(activeKind) && "frameVariable" in inputs && !overwriteConfirmed)
                        error="请确认专用截图暂存帧允许覆盖并释放"
                    if (configured != null && error == null) {
                        if(!effectiveCapture && configured["successAction"]?.asString !in setOf(null,"none")) manualActionConfirm=activeContract to configured.deepCopy()
                        else if (onConfirmSource != null) onConfirmSource(activeContract, configured, effectiveCapture) else onConfirm(activeContract, configured)
                    }
                }
            }
        }
        }
    }
    if(visible) manualActionConfirm?.let { (selected,configured) -> EditorPromptDialog("确认已有帧动作坐标", "已有帧可能来自裁剪、旋转或缩放。找到后的动作直接使用原屏像素；只有确定该帧与当前屏幕坐标一致才继续，否则取消并关闭动作。",
        {manualActionConfirm=null},{manualActionConfirm=null;if(onConfirmSource!=null) onConfirmSource(selected,configured,false) else onConfirm(selected,configured)},"取消","确认原屏坐标") }
    if (visible) picker?.let { field ->
        val scalarTypes = scalarInputTypes(activeKind, field)
        if (scalarTypes != null) {
            ScalarValueSourcePicker("选择范围取值", inputs[field].orEmpty(),
                snapshot?.let { calculationVariables(it.manifest.variables, currentFlowId, it.manifest.flows) }.orEmpty(), scalarTypes,
                allowFixed = true, onDismiss = { picker = null }, onSelect = { update(field, it); picker = null; error = null })
            return@let
        }
        if (field.startsWith("preset:")) {
            val path = field.removePrefix("preset:")
            RecognitionPicker(if (activeKind == "vision.countcolor" && path == "limit") "计数上限 · 0统计全部，其余达到即停止" else "常用数值 · 选后仍可手动输入", recognitionValuePresets(path, activeKind), inputs[path].orEmpty(),
                onDismiss = { picker = null }, onSelect = { value -> update(path, value); picker = null; error = null })
            return@let
        }
        if (field == "samplesEditor") {
            RecognitionSamplesDialog(inputs["samples"].orEmpty(), { picker = null }) { update("samples", it); picker = null; error = null }
            return@let
        }
    if ((field == "imagePaths" && activeKind == "vision.findimage") || activeContract.properties.any { it.path == field && it.editor == BlockPropertyEditor.RESOURCE_LIST }) {
            RecognitionTemplatesDialog(resourcePaths(resources, com.autoscript.studio.generated.BlockResourceKind.IMAGE), recognitionTemplatePaths(inputs[field].orEmpty()), snapshot, { picker = null }) {
                drafts = drafts + (activeKind to recognitionSelectTemplates(inputs, it)); picker = null; error = null
            }
            return@let
        }
        if (field == "bounds") {
            RecognitionBoundsDialog(inputs["region"].orEmpty(), snapshot?.takeUnless { activeKind == "ocr.alphanumeric" }, { picker = null }) {
                manualRegions += activeKind
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
        if (resourceProperty?.resourceKind == com.autoscript.studio.generated.BlockResourceKind.IMAGE && snapshot != null) {
            ProjectImagePickerDialog(snapshot, choices, inputs[field].orEmpty(), { picker = null }, { value ->
                update(field, value); picker = null; error = null
            }, title = resourceProperty.label)
            return@let
        }
        RecognitionPicker(when (field) { "kind" -> "识别类型"; "support" -> "模板来源"; "imageDirectory" -> "模板文件夹"; else -> resourceProperty?.label ?: enumProperty?.label ?: "选择变量" }, choices,
            when (field) { "kind" -> activeContract.title; "support" -> recognitionTemplateSource(inputs).title; else -> inputs[field].orEmpty() },
            onDismiss = { picker = null }, onSelect = { value ->
                if (field == "kind") {
                    val next = kinds.first { it.title == value }
                    if (next.kind !in drafts) {
                        val initial = blockPropertyTexts(next, initialRecognitionArguments(next, requireNotNull(snapshot)))
                        drafts = drafts + (next.kind to initial); baselines = baselines + (next.kind to initial)
                        regionFallbacks = regionFallbacks + (next.kind to initial["region"])
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
            }, allowNewValue = requiredType == null && enumProperty == null && resourceProperty == null && field !in setOf("kind", "support", "direction", "imageDirectory", "successAction"),
            variableOptions = if (enumProperty == null && resourceProperty == null && field !in setOf("kind", "support", "direction", "imageDirectory", "successAction"))
                variablePickerOptions(choices, snapshot?.let { calculationVariables(it.manifest.variables, currentFlowId, it.manifest.flows) }.orEmpty()) else null)
    }
    if(visible) glyphPalette?.let { initial -> GlyphColorPaletteDialog(initial,{glyphPalette=null; colorPaletteDraft=null; colorPalettePicking=false},
        title = if (activeKind in recognitionColorGroupKinds) "匹配色组" else "字库前景色组",
        description = if (activeKind in recognitionColorGroupKinds) "最多16色，各自容差。命中任意一色即成功；重叠像素只计一次。多点找色则要求多个位置同时匹配。" else "最多16色，各自容差，命中任意颜色即为文字笔画。",
        currentDraft = colorPaletteDraft, onDraftChange = { colorPaletteDraft=it },
        onPickFromScreen = onPickFromScreen?.let { pick -> { colorPalettePicking=true; pick(ImageToolMode.COLOR) } },
    ) { colors ->
        update(if (activeKind in recognitionColorGroupKinds) "rgb" else "foregroundRgb","#%06X".format(java.util.Locale.ROOT,colors.first().rgb))
        update("tolerance",colors.first().tolerance.toString())
        update("additionalColors",com.autoscript.project.store.ProjectGlyphColor.encodeAdditional(colors.drop(1)))
        glyphPalette=null;colorPaletteDraft=null;colorPalettePicking=false;error=null
    } }
    if (discard && visible) EditorPromptDialog("放弃识别参数？", "本次参数、图像来源及模板选择尚未加入，放弃后不会修改原积木。",
        { discard = false }, { discard = false; onDismiss() }, "继续编辑", "放弃")
    workflow?.let { (selected, configured) -> if (snapshot != null && onWorkflow != null) RecognitionWorkflowDialog(
        selected, configured, snapshot, currentFlowId, onManageVariables, onDismiss = { workflow = null }, onConfirm = onWorkflow, visible = visible) }
    }
    }
}

private val recognitionDirections = recognitionSearchDirections

internal val recognitionColorGroupKinds = setOf("vision.findcolor", "vision.comparecolor", "vision.countcolor", "vision.findallcolor")

/** Palette picks replace the selected slot without changing the comparison point or search area. */
internal fun recognitionPaletteSelection(draft: GlyphPaletteDraft, selection: ImageToolCodeGen.VisualSelection): GlyphPaletteDraft =
    selection.points.firstOrNull()?.let { draft.copy(replaceOnPick = true).pick(it.rgb and 0xFFFFFF) } ?: draft

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
private fun RecognitionTemplatesDialog(choices: List<String>, current: List<String>, snapshot: ProjectSnapshot?, onDismiss: () -> Unit, onConfirm: (List<String>) -> Unit) {
    var selected by remember(current) { mutableStateOf(current.filter { it in choices }) }
    val baseline = remember(current) { current.filter { it in choices } }
    var discard by remember { mutableStateOf(false) }
    fun close() { if (selected != baseline) discard = true else onDismiss() }
    var search by remember { mutableStateOf("") }
    ImageWorkspacePanel("模板 · ${selected.size}/64", ::close, showFooter = false, compactHeader = true) {
            Column(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                LazyColumn(Modifier.weight(1f, fill = false).padding(horizontal = 8.dp)) {
                    item(key = "search") { ImageToolCompactInput("筛选",search,true,Modifier.fillMaxWidth().padding(vertical = 6.dp)) {search=it} }
                    items(choices.filter { it.contains(search, ignoreCase = true) }, key = { it }) { path ->
                        val index = selected.indexOf(path)
                        ProjectImageChoiceRow(snapshot, path, selected = index >= 0, enabled = index >= 0 || selected.size < 64,
                            multiple = true, selectionLabel = "${index + 1}.") {
                            selected = if (index >= 0) selected - path else selected + path
                        }
                    }
                    item(key = "help") { ImageWorkspaceHint("取消勾选再添加可调整优先顺序；最多64张。") }
                }
                RecognitionFooter("取消", "确定", ::close) { onConfirm(selected) }
            }
    }
    if (discard) EditorPromptDialog("放弃模板选择？", "未确认的模板及顺序将丢弃。", { discard = false }, { discard = false; onDismiss() }, "继续选择", "放弃")
}

@Composable
private fun RecognitionSettingRow(label: String, onLabelClick: (() -> Unit)? = null, content: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 30.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(if (onLabelClick != null) "$label ▾" else label, Modifier.width(52.dp).then(if (onLabelClick != null) Modifier.clickable(onClick = onLabelClick) else Modifier), color = if (onLabelClick != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, style = TextStyle(fontSize = 10.sp, lineHeight = 13.sp, platformStyle = PlatformTextStyle(includeFontPadding = false)), maxLines = 2, overflow = TextOverflow.Ellipsis)
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
    ImageWorkspacePanel("范围 · 原始像素", ::close, showFooter = false, compactHeader = true) {
            Column(Modifier.fillMaxWidth().heightIn(max = 280.dp)) {
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                StudioCompactGrid(listOf("X", "Y", "宽", "高").withIndex().toList(), cellDp = 82, maximum = 4) { (index, name), cell ->
                    ImageToolCompactInput(name, bounds[index], true, cell, numeric = true) {
                        bounds = bounds.toMutableList().apply { set(index, it) }; error = null
                    }
                }
                snapshot?.let { project -> RecognitionChoice("设计范围（不是设备分辨率）") { bounds = listOf("0", "0", project.manifest.design.width.toString(), project.manifest.design.height.toString()) } }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 11.sp) }
                }
                RecognitionFooter("取消", "确定", ::close) {
                    val rectangle = imageRecognitionBounds(bounds)
                    if (rectangle == null) error = "请输入非负坐标和正数宽高，且不能溢出"
                    else onConfirm(listOf("left", "top", "right", "bottom").joinToString(",") { rectangle.get(it).asString })
                }
            }
    }
    if (discard) EditorPromptDialog("放弃范围修改？", "未确认的坐标和尺寸将丢弃。", { discard = false }, { discard = false; onDismiss() }, "继续编辑", "放弃")
}

@Composable
internal fun RecognitionHeader(title: String, onDismiss: () -> Unit, fullScreen: Boolean = false, compact: Boolean = false, onToggleFullScreen: (() -> Unit)? = null) {
    val height = studioCompactControlHeight(if (compact) 32 else 36, LocalDensity.current.fontScale).dp
    Row(Modifier.fillMaxWidth().height(height).background(MaterialTheme.colorScheme.surfaceContainer).padding(start = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurface, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
            maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false)))
        if (onToggleFullScreen != null) StudioPanelFullScreenButton(fullScreen, onToggleFullScreen, compact)
        Box(Modifier.size(height).clickable(onClick = onDismiss), contentAlignment = Alignment.Center) {
            Text("×", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 23.sp)
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
private fun RecognitionFooter(left: String, right: String, onLeft: () -> Unit, compact: Boolean = false, onRight: () -> Unit) {
    ImageWorkspaceFooter {
        ImageToolCompactAction(left, true, Modifier.weight(1f).heightIn(min=if(compact)30.dp else 32.dp), onClick=onLeft)
        ImageToolCompactAction(right, true, Modifier.weight(1f).heightIn(min=if(compact)30.dp else 32.dp), primary=true, onClick=onRight)
    }
}

@Composable
internal fun RecognitionInput(value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier, numeric: Boolean = false, enabled: Boolean = true) {
    ImageToolCompactInput("", value, enabled, modifier, numeric = numeric, dense = true) { if (it.length <= 128) onChange(it) }
}

@Composable
internal fun RecognitionChoice(value: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    ImageToolCompactAction(value, enabled, modifier, dense = true, onClick = onClick)
}

@Composable
private fun RecognitionVariableRow(label: String, value: String, onChange: (String) -> Unit, onSelect: () -> Unit, modifier: Modifier = Modifier) {
    ImageToolCompactInput(label, value.removePrefix("="), true, modifier.fillMaxWidth(), dense=true, trailing = {
        Box(Modifier.size(24.dp).clickable(onClick = onSelect), contentAlignment = Alignment.Center) {
            androidx.compose.material3.Icon(androidx.compose.ui.res.painterResource(R.drawable.ic_list_24),
                "选择变量", Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
        }
    }) {
        if (value.startsWith("=")) { if(it.toByteArray(Charsets.UTF_8).size<=1024) onChange("=$it") }
        else if (it.length <= 128) onChange(it)
    }
}

@Composable
private fun RecognitionSamplesDialog(value: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var draft by remember(value) { mutableStateOf(value) }
    var error by remember { mutableStateOf<String?>(null) }
    var discard by remember { mutableStateOf(false) }
    fun close() { if (draft != value) discard = true else onDismiss() }
    ImageWorkspacePanel("偏移采样点", ::close, showFooter = true, confirmLabel = "确定", onConfirm = { onConfirm(draft) }) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            ImageWorkspaceHint("X偏移,Y偏移,#RRGGBB,容差；多个点用英文 ; 分隔。最多64点，加入时统一校验。")
            BasicTextField(draft, {
                if (it.length <= 8192) { draft = it; error = null } else error = "采样点文本最多8192字符，未接受本次输入"
            }, textStyle = TextStyle(fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface),
                modifier = Modifier.fillMaxWidth().heightIn(min=72.dp,max=120.dp).background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(4.dp))
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(4.dp)).verticalScroll(rememberScrollState()).padding(8.dp))
            Text("${draft.split(';').count { it.isNotBlank() }} 个采样点 · ${draft.length}/8192字符", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            StudioSampleColors(draft, 2) { updated ->
                if (updated.length <= 8192) { draft = updated; error = null } else error = "采样点文本最多8192字符"
            }
            error?.let { Text(it, fontSize = 11.sp, color = MaterialTheme.colorScheme.error) }
        }
    }
    if (discard) EditorPromptDialog("放弃采样点修改？", "未确认的偏移采样点将丢弃。", { discard = false }, { discard = false; onDismiss() }, "继续编辑", "放弃")
}

@Composable
private fun RecognitionPicker(title: String, choices: List<String>, current: String, onDismiss: () -> Unit, onSelect: (String) -> Unit, allowNewValue: Boolean = false,
    disabledValues: Set<String> = emptySet(), variableOptions: List<VariablePickerOption>? = null) {
    if (variableOptions != null) {
        CompactVariablePickerDialog(title, variableOptions.map { it.copy(enabled = it.enabled && it.value !in disabledValues) }, current,
            onDismiss, onSelect, allowNewValue = allowNewValue)
        return
    }
    var search by remember { mutableStateOf("") }
    ImageWorkspacePanel(title, onDismiss, compactHeader = true) {
                val filtered = choices.filter { it.contains(search, ignoreCase = true) }
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 400.dp).padding(10.dp)) {
                item(key = "search") { ImageToolCompactInput("筛选",search,true,Modifier.fillMaxWidth().padding(bottom=6.dp)) {search=it} }
                if (allowNewValue && search.matches(com.autoscript.project.store.ProjectNames.pattern)) {
                    item(key = "use-new") { RecognitionChoice("使用变量：$search", Modifier.fillMaxWidth()) { onSelect(search) } }
                }
                    if (filtered.isEmpty()) item(key = "empty") { Text("没有可选项", fontSize = 12.sp) }
                    items(filtered, key = { "choice:$it" }) { choice ->
                        Row(Modifier.fillMaxWidth().background(if(choice==current) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                            .clickable(enabled = choice !in disabledValues) { onSelect(choice) }.padding(horizontal=7.dp,vertical=8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(choice, Modifier.weight(1f),fontSize = 12.sp, color = if (choice in disabledValues) MaterialTheme.colorScheme.onSurfaceVariant else if (choice == current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            if(choice==current) Text("已选",fontSize=10.sp,color=MaterialTheme.colorScheme.primary)
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
    }
}

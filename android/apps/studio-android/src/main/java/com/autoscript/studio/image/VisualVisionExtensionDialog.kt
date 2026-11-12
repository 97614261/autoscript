package com.autoscript.studio

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.autoscript.project.store.ProjectSnapshot
import com.autoscript.project.store.ProjectVariableType
import com.autoscript.project.store.ProjectImageFilter
import com.autoscript.project.store.ProjectImageMask
import com.autoscript.project.store.ProjectImageCleanup
import com.autoscript.project.store.imageCleanupModes
import com.autoscript.project.store.ProjectStore
import com.autoscript.studio.generated.BlockContract
import com.autoscript.studio.generated.BlockPropertyEditor
import com.google.gson.JsonObject

internal val visionExtensionKinds = setOf("screen.transform", "screen.savepng", "screen.compareframes", "screen.mappoint", "vision.findscaled", "vision.findfeatures", "ocr.alphanumericv5", "vision.findallmulticolor", "vision.components", "ocr.glyphdetails", "ocr.findtext")

/** A region wait does not need the ordinary comparison's previous frame or result mapping. */
internal fun regionWaitArguments(inputs:Map<String,String>,variables:List<CalculationVariable>):JsonObject {
    val frame=inputs["frameVariable"].orEmpty()
    require(variables.any {it.name==frame && it.type==ProjectVariableType.IMAGE}) {"请先选择当前截图专用图像变量"}
    val bounds=listOf("left","top","right","bottom").associateWith {requireNotNull(inputs[it]?.toIntOrNull()) {"请填写有效的范围"}}
    val left=bounds.getValue("left");val top=bounds.getValue("top");val right=bounds.getValue("right");val bottom=bounds.getValue("bottom")
    require(left in 0..32768 && top in 0..32768 && right in left+1..32768 && bottom in top+1..32768 &&
        (right-left).toLong()*(bottom-top)<=4194304) {"范围不能为空或超过4194304像素"}
    val tolerance=requireNotNull(inputs["tolerance"]?.toIntOrNull()) {"RGB容差须为0–255整数"}
    require(tolerance in 0..255) {"RGB容差须为0–255整数"}
    return JsonObject().apply {addProperty("frameVariable",frame);addProperty("tolerance",tolerance)
        add("region",JsonObject().apply {bounds.forEach {(key,value)->addProperty(key,value)}})}
}

internal fun visionExtensionArgumentError(kind: String, inputs: Map<String, String>, variables: List<CalculationVariable>): String? {
    if (!inputs["polygon"].isNullOrEmpty()) {
        val points=runCatching {ImageSearchPolygon.decode(inputs.getValue("polygon"))}.getOrElse {return "多边形选区无效，请重新调整"}
        val bounds=ImageSearchPolygon.bounds(points)
        val rect=listOf("left","top","right","bottom").map {inputs[it]?.toIntOrNull()}
        if(rect.any {it==null} || bounds.left<rect[0]!! || bounds.top<rect[1]!! || bounds.right>rect[2]!! || bounds.bottom>rect[3]!!) return "多边形顶点必须在搜索范围内"
    }
    fun type(key: String) = variables.find { it.name == inputs[key] }?.type
    val source = if (kind == "ocr.findtext") "detailsVariable" else "frameVariable"
    if (type(source) != if (kind == "ocr.findtext") ProjectVariableType.STRING else ProjectVariableType.IMAGE) return "请选择类型匹配的来源变量"
    if (kind != "screen.savepng") {
        if (type("resultVariable") != if (kind == "screen.transform") ProjectVariableType.IMAGE else ProjectVariableType.STRING) return "请选择类型匹配的结果变量"
        if (inputs[source] == inputs["resultVariable"]) return "来源和结果请使用不同变量"
    }
    if (kind == "screen.transform") {
        if (inputs["mode"] !in setOf("rotate90", "rotate180", "rotate270", "flipX", "flipY", "gray", "threshold", "resize", "filter", "mask")+imageCleanupModes) return "请选择有效的变换模式"
        if (inputs["mode"] == "filter" && runCatching { ProjectImageFilter.decode(inputs["filter"].orEmpty()) }.isFailure) return "请配置有效的滤色规则"
        if (inputs["mode"] != "filter" && !inputs["filter"].isNullOrEmpty()) return "其他变换模式不能附带滤色规则"
        if(inputs["mode"]=="mask" && runCatching {ProjectImageMask.decode(inputs["mask"].orEmpty())}.isFailure) return "请配置有效的蒙版规则"
        if(inputs["mode"]!="mask" && !inputs["mask"].isNullOrEmpty()) return "其他模式不能附带蒙版"
        if(inputs["mode"] in imageCleanupModes && runCatching {ProjectImageCleanup.decode(inputs["cleanup"].orEmpty())}.isFailure) return "请配置有效的数字清理参数"
        if(inputs["mode"] !in imageCleanupModes && !inputs["cleanup"].isNullOrEmpty()) return "其他模式不能附带清理参数"
        if ((inputs["threshold"] ?: "128").toIntOrNull() !in 0..255) return "二值阈值须为0–255整数"
        val width = inputs["width"]?.toLongOrNull() ?: return "宽度无效"
        val height = inputs["height"]?.toLongOrNull() ?: return "高度无效"
        if (inputs["mode"] == "resize") {
            if (width !in 1..4194304 || height !in 1..4194304 || width * height > 4194304) return "缩放超过4194304像素预算"
        } else if (width != 0L || height != 0L) return "非缩放模式宽高为0"
    }
    if(kind=="screen.compareframes" && (type("previousFrameVariable")!=ProjectVariableType.IMAGE || inputs["previousFrameVariable"]==inputs["frameVariable"])) return "请选择两个不同的图像变量"
    if(kind=="screen.mappoint") for(key in listOf("xVariable","yVariable")) {
        if(!inputs[key].isNullOrEmpty() && scalarSourceError(inputs[key].orEmpty(),variables,setOf(ProjectVariableType.INTEGER))!=null) return "坐标需为整数变量或表达式；留空使用固定值"
    }
    if(kind=="vision.findscaled") {
        val min=inputs["minScalePermille"]?.toIntOrNull();val max=inputs["maxScalePermille"]?.toIntOrNull();val step=inputs["stepPermille"]?.toIntOrNull()
        if(min==null || max==null || step==null || min !in 250..4000 || max !in min..4000 || step !in 1..1000 || (max-min)%step!=0 || (max-min)/step+1>16) return "比例250–4000‰、整除步长，最多16档"
    }
    if(kind=="vision.findfeatures" && inputs["imagePath"]?.startsWith("assets/images/")!=true) return "请选择声明的模板图片资源"
    if(kind in setOf("vision.findfeatures","ocr.alphanumericv5")) {
        val l=inputs["left"]?.toLongOrNull();val t=inputs["top"]?.toLongOrNull();val r=inputs["right"]?.toLongOrNull();val b=inputs["bottom"]?.toLongOrNull()
        if(l==null||t==null||r==null||b==null||l !in 0..32768||t !in 0..32768||r !in l+1..32768||b !in t+1..32768||(r-l)*(b-t)>(if(kind=="vision.findfeatures")1048576 else 4194304)) return "范围无效或超过像素预算"
        if(inputs["minimumScorePermille"]?.toIntOrNull() !in 0..1000) return "最低分数需为0–1000整数"
    }
    if (kind in setOf("vision.findallmulticolor", "vision.components", "ocr.glyphdetails", "screen.compareframes", "vision.findscaled")) {
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
@OptIn(ExperimentalLayoutApi::class)
internal fun VisualVisionExtensionDialog(contract: BlockContract, arguments: JsonObject, project: ProjectSnapshot, flowId: String,
    onManageVariables: (() -> Unit)?, onDismiss: () -> Unit, onConfirm: (JsonObject) -> Unit, confirmLabel: String,
    visible: Boolean = true, allowCaptureSource: Boolean = false, onConfirmSource: ((JsonObject, Boolean) -> Unit)? = null,
    captureSelection: ImageToolCodeGen.VisualSelection? = null, onPickFromScreen: ((ImageToolMode) -> Unit)? = null,
    projectStore: ProjectStore? = null,onWorkflow:((List<VisualBlockTree>)->Unit)?=null,onPickPolygon:((String)->Unit)?=null) {
    val initial = remember(contract.kind, arguments) { blockPropertyTexts(contract, arguments) }
    var inputs by remember(initial) { mutableStateOf(initial) }
    var error by remember { mutableStateOf<String?>(null) }
    var discard by remember { mutableStateOf(false) }
    var fullScreen by remember { mutableStateOf(false) }
    var filterEditor by remember { mutableStateOf(false) }
    var maskEditor by remember { mutableStateOf(false) }
    var cleanupEditor by remember { mutableStateOf(false) }
    var glyphPalette by remember { mutableStateOf<GlyphPaletteDraft?>(null) }
    var regionWait by remember(initial) {mutableStateOf<JsonObject?>(null)}
    var lastMask by remember(initial) {mutableStateOf(initial["mask"].orEmpty())}
    var lastCleanup by remember(initial) {mutableStateOf(initial["cleanup"].orEmpty().ifEmpty {ProjectImageCleanup().encode()})}
    var lastFilter by remember(initial) { mutableStateOf(initial["filter"].orEmpty().ifEmpty { ProjectImageFilter().encode() }) }
    val canCapture = recognitionCanWrapCapture(contract.kind) && allowCaptureSource
    val defaultCaptureSource = rememberDefaultImageCaptureSource()
    val initialCapture = remember(initial) {
        canCapture && initialImageCaptureSource(true, false, defaultCaptureSource).capture
    }
    var capture by remember(initial) { mutableStateOf(initialCapture) }
    var overwriteConfirmed by remember(initial) { mutableStateOf(false) }
    val variables = calculationVariables(project.manifest.variables, flowId, project.manifest.flows)
    fun update(key: String, value: String) {
        val reset=if(key in setOf("left","top","right","bottom") && "polygon" in inputs) mapOf("polygon" to "") else emptyMap()
        inputs = inputs + reset + (key to value); error = null; if(key=="frameVariable") overwriteConfirmed=false
    }
    fun changeMode(mode:String) {
        inputs["filter"]?.takeIf(String::isNotEmpty)?.let {lastFilter=it}
        inputs["mask"]?.takeIf(String::isNotEmpty)?.let {lastMask=it}
        inputs["cleanup"]?.takeIf(String::isNotEmpty)?.let {lastCleanup=it}
        inputs=inputs-"filter"-"mask"-"cleanup"+("mode" to mode)
        if(mode!="resize") {update("width","0");update("height","0")}
        when {mode=="filter"->update("filter",lastFilter);mode=="mask"->update("mask",lastMask);mode in imageCleanupModes->update("cleanup",lastCleanup)}
    }
    LaunchedEffect(captureSelection) {
        captureSelection?.let { selected ->
            selected.roi?.let { roi -> listOf("left" to roi.left,"top" to roi.top,"right" to roi.right,"bottom" to roi.bottom).forEach { (key,value) -> if (key in inputs) update(key,value.toString()) } }
            if("polygon" in inputs && selected.roi!=null) update("polygon",ImageSearchPolygon.encode(selected.polygon))
            selected.points.firstOrNull()?.let { point -> listOf("rgb","foregroundRgb").forEach { if (it in inputs) update(it,(point.rgb and 0xFFFFFF).toString()) } }
            if (contract.kind == "vision.findallmulticolor") visionSamplesFromSelection(selected)?.let { update("samples",it) }
        }
    }
    if (visible && regionWait==null) ImageWorkspacePanel(contract.title, { if (inputs != initial || capture != initialCapture) discard = true else onDismiss() },
        confirmLabel=confirmLabel,showFooter=true,fullScreen = fullScreen, onToggleFullScreen = { fullScreen = !fullScreen }, onConfirm = {
        error = visionExtensionArgumentError(contract.kind, inputs, variables)
        if(error==null && canCapture && capture && !overwriteConfirmed) error="请确认专用截图暂存变量允许覆盖，并在识别后释放"
        if (error == null) {
            val parsed = parseBlockArguments(contract, inputs, null, emptyMap(), project.manifest.resources)
            error = parsed.second
            parsed.first?.let { if (onConfirmSource != null) onConfirmSource(it, capture) else onConfirm(it) }
        }
    }) {
        Column(Modifier.fillMaxWidth().heightIn(max = if (fullScreen) 900.dp else 360.dp).verticalScroll(rememberScrollState()).padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (canCapture) {
                ImageToolChoice("来源",ImageCaptureSource.fromCapture(capture).name,ImageCaptureSource.entries.map {it.name to it.title},true) {
                    capture=ImageCaptureSource.valueOf(it).capture;overwriteConfirmed=false;error=null
                }
                if (capture) ImageToolToggle("允许覆盖专用暂存变量并释放", overwriteConfirmed, true) { overwriteConfirmed = it }
            }
            val hasBounds=listOf("left","top","right","bottom").all { it in inputs }
            if(hasBounds || contract.kind in ImageSearchPolygon.kinds || (contract.kind=="screen.compareframes" && onWorkflow!=null)) ImageWorkspaceSection("操作范围") {
            FlowRow(horizontalArrangement=Arrangement.spacedBy(4.dp),verticalArrangement=Arrangement.spacedBy(3.dp)) {
            if(contract.kind=="screen.compareframes" && onWorkflow!=null) ImageToolCompactAction("变化 / 稳定等待",true) {
                runCatching {regionWaitArguments(inputs,variables)}.onSuccess {regionWait=it}.onFailure {error=it.message}
            }
            if(onPickFromScreen!=null && contract.kind in setOf("screen.compareframes","vision.findscaled","vision.findfeatures"))
                ImageToolCompactAction("屏幕框选",true) {onPickFromScreen(ImageToolMode.REGION)}
            if(contract.kind in ImageSearchPolygon.kinds) {
                val points=runCatching {ImageSearchPolygon.decode(inputs["polygon"].orEmpty()).size}.getOrDefault(0)
                ImageToolCompactAction(if(points>0) "多边形 · ${points}点" else "多边形",onPickPolygon!=null) {onPickPolygon?.invoke(inputs["polygon"].orEmpty())}
                if(points>0) ImageToolCompactAction("改用矩形",true) {update("polygon","")}
            }
            if (onPickFromScreen != null && contract.kind in setOf("vision.findallmulticolor","vision.components","ocr.glyphdetails")) {
                ImageToolCompactAction("屏幕框选",true) {onPickFromScreen(ImageToolMode.REGION)}
                ImageToolCompactAction(if (contract.kind == "vision.findallmulticolor") "屏幕选色点" else "屏幕取色",true) {
                    onPickFromScreen(if (contract.kind == "vision.findallmulticolor") ImageToolMode.MULTI_COLOR else ImageToolMode.COLOR)
                }
            }
            }
            if (hasBounds) StudioCompactGrid(
                listOf("left" to "左","top" to "上","right" to "右不含","bottom" to "下不含"), cellDp = 88, maximum = 4) { (key, label), cell ->
                ImageToolCompactInput(label,inputs[key].orEmpty(),true,cell,numeric=true) { update(key,it) }
            }
            }
            val sourceKeys=setOf("frameVariable","previousFrameVariable","templateVariable","detailsVariable","imagePath","dictionaryPath")
            val propertyGroups=contract.properties.filter {it.path !in setOf("left","top","right","bottom","polygon")}.groupBy {
                when {it.path in sourceKeys -> "素材与来源";it.path=="resultVariable" -> "结果变量";else -> "操作参数"}
            }
            listOf("素材与来源","操作参数","结果变量").forEach {group ->
            propertyGroups[group]?.takeIf {it.isNotEmpty()}?.let {properties -> ImageWorkspaceSection(group) {
            properties.forEach property@ { property ->
                val key = property.path
                if(key=="additionalColors") {
                    ImageToolCompactInput(property.label,inputs[key].orEmpty(),true,Modifier.fillMaxWidth()) {update(key,it)}
                    ImageToolCompactAction("编辑前景色组",true) {runCatching {glyphPaletteFromInputs(inputs["foregroundRgb"].orEmpty(),inputs["tolerance"].orEmpty(),inputs[key].orEmpty())}
                        .onSuccess {glyphPalette=it}.onFailure {error="请检查主色、容差及附加色格式"}}
                    return@property
                }
                if (key == "samples") { VisionSampleEditor(inputs[key].orEmpty()) { update(key,it) }; return@property }
                if(contract.kind=="screen.transform" && key in setOf("mask","cleanup")) {
                    if(key=="mask" && inputs["mode"]=="mask") {
                        val configured=runCatching {ProjectImageMask.decode(inputs[key].orEmpty())}.getOrNull()
                        ImageWorkspaceHint(configured?.let {"${it.width}×${it.height}px · ${it.strokes.size}笔 · 原像素蒙版"} ?: "请明确配置蒙版尺寸和区域")
                        ImageToolCompactAction("编辑蒙版",true) {maskEditor=true}
                    }
                    if(key=="cleanup" && inputs["mode"] in imageCleanupModes) ImageToolCompactAction("数字清理参数",true) {cleanupEditor=true}
                    return@property
                }
                if (contract.kind == "screen.transform" && key == "filter") {
                    if (inputs["mode"] == "filter") {
                        val configured=runCatching { ProjectImageFilter.decode(inputs[key].orEmpty()) }.getOrNull()
                        ImageWorkspaceHint(configured?.let { "${it.rules.size}条规则 · ${if(it.output=="keep") "透明保留" else "黑白输出"} · 参数随积木保存" } ?: "当前滤色规则无效，请重新配置")
                        ImageToolCompactAction("编辑滤色",true) {filterEditor=true}
                    }
                    return@property
                }
                // Invalid hidden input must remain editable rather than blocking an unrelated mode invisibly.
                if (contract.kind == "screen.transform" && ((key == "threshold" && inputs["mode"] != "threshold" &&
                    (inputs["threshold"] ?: "128").toIntOrNull() in 0..255) ||
                    (key in setOf("width", "height") && inputs["mode"] != "resize"))) return@property
                when {
                    key.endsWith("Variable") -> {
                        val expected = when {
                            key in setOf("xVariable","yVariable") -> ProjectVariableType.INTEGER
                            key in setOf("frameVariable","previousFrameVariable","templateVariable") || (key == "resultVariable" && contract.kind == "screen.transform") -> ProjectVariableType.IMAGE
                            else -> ProjectVariableType.STRING
                        }
                        val sourceKey=if(contract.kind=="ocr.findtext") "detailsVariable" else "frameVariable"
                        val label = if (canCapture && key == "frameVariable") {
                            if (capture) "截图暂存" else "来源图像"
                        } else property.label
                        CompactVariableChoice(label, inputs[key].orEmpty(), variables.filter { it.type == expected &&
                            (key!="resultVariable" || it.name!=inputs[sourceKey]) && (key!=sourceKey || it.name!=inputs["resultVariable"])
                        }.map { it.name to it.name }+(if(key in setOf("xVariable","yVariable")) listOf("" to "固定值") else emptyList()), true, variables,
                            inputTypes = scalarInputTypes(contract.kind,key)) { update(key, it) }
                    }
                    studioBlockColorFormat(key, property.editor) != null ->
                        StudioColorInput(property.label, inputs[key].orEmpty(), true, Modifier.fillMaxWidth(),
                            requireNotNull(studioBlockColorFormat(key, property.editor))) { update(key, it) }
                    key == "dictionaryPath" -> ImageToolChoice(property.label, inputs[key].orEmpty(), project.manifest.resources.filter(::isGlyphDictionaryResource).map { it.get("path").asString.let { path -> path to path } }, true) { update(key, it) }
                    key == "imagePath" -> ImageToolChoice(property.label,inputs[key].orEmpty(),project.manifest.resources.filter {it["kind"]?.asString=="image"}.map {it["path"].asString.let {path->path to path.removePrefix("assets/images/")}},true,imageProject=project) {update(key,it)}
                    property.editor in setOf(BlockPropertyEditor.ENUM, BlockPropertyEditor.INTEGER_ENUM) -> {
                        val labels = mapOf("rotate90" to "顺时针90°", "rotate180" to "180°", "rotate270" to "顺时针270°", "flipX" to "水平镜像", "flipY" to "垂直镜像", "gray" to "灰度", "threshold" to "灰度二值化", "filter" to "多组滤色", "mask" to "模板蒙版", "resize" to "缩放", "4" to "4邻接", "8" to "8邻接")+imageCleanupLabels
                        ImageToolChoice(property.label, inputs[key].orEmpty(), property.choices.map { it to (labels[it] ?: it) }, true) {
                            if(key=="mode") changeMode(it) else update(key,it)
                        }
                    }
                    else -> ImageToolCompactInput(property.label, inputs[key].orEmpty(), true, Modifier.fillMaxWidth(), numeric = property.editor == BlockPropertyEditor.INTEGER) { update(key, it) }
                }
            }
            } }
            }
            ImageToolHelp(contract.summary+"\n范围使用来源帧原像素；颜色、连通块、逐字结果用“读取识别结果属性”读取，不受通用Data数据项预算限制。"+
                (if(canCapture) "\n"+ImageCaptureSource.fromCapture(capture).description else "")+
                if(contract.kind=="screen.compareframes" && onWorkflow!=null) "\n变化/稳定等待只需当前截图变量和范围，无需前帧或普通比较输出。" else "")
            onManageVariables?.let { ImageToolCompactAction("管理变量",true,onClick=it) }
            error?.let { ImageWorkspaceHint(it,error=true) }
        }
    }
    regionWait?.let { configured -> RecognitionWorkflowDialog(contract,configured,project,flowId,onManageVariables,
        {regionWait=null},{onWorkflow?.invoke(it)},visible=visible) }
    if(filterEditor && visible) ImageColorFilterDialog(runCatching { ProjectImageFilter.decode(inputs["filter"].orEmpty()) }.getOrElse { ProjectImageFilter() },
        onDismiss={filterEditor=false},onApply={lastFilter=it.encode();update("filter",lastFilter);filterEditor=false},snapshot=project,store=projectStore)
    if(maskEditor && visible) ImageMaskDialog(null,runCatching {ProjectImageMask.decode(inputs["mask"].orEmpty())}.getOrNull(),
        project.manifest.design.width,project.manifest.design.height,onDismiss={maskEditor=false},onApply={lastMask=it.encode();update("mask",lastMask);maskEditor=false})
    if(cleanupEditor && visible) ImageCleanupDialog(null,inputs["mode"].orEmpty(),runCatching {ProjectImageCleanup.decode(inputs["cleanup"].orEmpty())}.getOrElse {ProjectImageCleanup()},
        onDismiss={cleanupEditor=false},onApply={mode,spec ->lastCleanup=spec.encode();changeMode(mode);update("cleanup",lastCleanup);cleanupEditor=false})
    if(visible) glyphPalette?.let {initial -> GlyphColorPaletteDialog(initial,{glyphPalette=null}) {colors ->
        update("foregroundRgb",colors.first().rgb.toString());update("tolerance",colors.first().tolerance.toString())
        update("additionalColors",com.autoscript.project.store.ProjectGlyphColor.encodeAdditional(colors.drop(1)));glyphPalette=null
    }}
    if (discard && visible) StudioConfirmDialog(onDismissRequest = { discard = false }, title = { Text("放弃未保存参数？") }, text = { Text("不会加入或修改积木。") }, actions = listOf(
        StudioConfirmAction("继续编辑", { discard = false }), StudioConfirmAction("放弃", { discard = false; onDismiss() }, destructive = true)))
}

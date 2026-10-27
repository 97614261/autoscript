package com.autoscript.studio

import com.autoscript.studio.generated.BlockContract
import com.google.gson.JsonObject

/** UI choices only; node kinds, algorithm semantics and resource declarations stay unchanged. */
internal enum class RecognitionTemplateSource(val title: String) {
    SINGLE("单张模板"), LIST("多张模板"), DIRECTORY("模板文件夹"),
}

internal fun recognitionTemplateSource(inputs: Map<String, String>): RecognitionTemplateSource = when {
    !inputs["imageDirectory"].isNullOrBlank() -> RecognitionTemplateSource.DIRECTORY
    !inputs["imagePaths"].isNullOrBlank() -> RecognitionTemplateSource.LIST
    else -> RecognitionTemplateSource.SINGLE
}

internal fun recognitionSelectedTemplatePaths(inputs: Map<String, String>, resources: List<JsonObject>): List<String> =
    when (recognitionTemplateSource(inputs)) {
        RecognitionTemplateSource.SINGLE -> listOfNotNull(inputs["imagePath"]?.takeIf(String::isNotBlank))
        RecognitionTemplateSource.LIST -> recognitionTemplatePaths(inputs["imagePaths"].orEmpty())
        RecognitionTemplateSource.DIRECTORY -> resourcePaths(resources, com.autoscript.studio.generated.BlockResourceKind.IMAGE)
            .filter { it.startsWith(inputs["imageDirectory"].orEmpty()) }
    }

internal fun recognitionSelectTemplates(inputs: Map<String, String>, paths: List<String>): Map<String, String> =
    inputs + mapOf("imagePaths" to recognitionTemplateJson(paths), "imageDirectory" to "",
        "imagePath" to paths.firstOrNull().orEmpty())

internal fun recognitionSelectTemplateDirectory(inputs: Map<String, String>, directory: String, paths: List<String>): Map<String, String> =
    inputs + mapOf("imageDirectory" to directory, "imagePaths" to "",
        "imagePath" to paths.firstOrNull { it.startsWith(directory) }.orEmpty())

internal fun recognitionSelectSingleTemplate(inputs: Map<String, String>, path: String): Map<String, String> =
    inputs + mapOf("imagePath" to path, "imagePaths" to "", "imageDirectory" to "")

/** A saved capture is a deliberate template selection, not a reset of the recognition draft. */
internal fun recognitionCapturedTemplate(inputs: Map<String, String>, kind: String, path: String): Map<String, String> = when {
    kind == "vision.findallimages" || recognitionTemplateSource(inputs) == RecognitionTemplateSource.LIST -> {
        val selected = recognitionTemplatePaths(inputs["imagePaths"].orEmpty())
        require(path in selected || selected.size < 64) { "已选64张模板，请先移除一张再添加截图" }
        recognitionSelectTemplates(inputs, if (path in selected) selected else selected + path)
    }
    "imagePath" in inputs -> recognitionSelectSingleTemplate(inputs, path)
    else -> inputs
}

internal fun recognitionScreenPickMode(kind: String): ImageToolMode = when (kind) {
    "vision.findimage", "vision.findgray", "vision.findallimages" -> ImageToolMode.CROP
    "vision.findmulticolor" -> ImageToolMode.MULTI_COLOR
    "vision.findcolor", "vision.getcolor", "vision.comparecolor", "vision.countcolor", "vision.findallcolor" -> ImageToolMode.COLOR
    else -> ImageToolMode.REGION
}

internal fun recognitionPropertyVisible(path: String, inputs: Map<String, String>): Boolean = when (path) {
    "offsetX", "offsetY", "allMatches", "allResultsAction", "applyToAll" -> inputs["successAction"].orEmpty() !in setOf("", "none")
    "actionDurationMs" -> inputs["successAction"] in setOf("tapWait", "pressRelease") ||
        inputs[path]?.toIntOrNull() !in 1..(if ("allMatches" in inputs) 5000 else 60_000)
    else -> true
}

internal fun recognitionFrameSummary(inputs: Map<String, String>, automaticCapture: Boolean): String = when {
    "frameVariable" !in inputs -> "读取已保存的识别结果"
    automaticCapture || inputs["autoCapture"] == "true" -> "运行时自动截图 · 用完释放"
    else -> "读取已有帧：${inputs["frameVariable"].orEmpty().ifBlank { "未设置" }}"
}

internal fun recognitionUsesOwnCapture(kind: String): Boolean = kind in setOf(
    "vision.findimage", "vision.findallimages", "vision.findgray", "ocr.alphanumeric",
)

/** Reading results is not recognition: it must never insert a capture or require capture permission. */
internal fun recognitionImageEntryBlocks(contract: BlockContract, arguments: JsonObject, capture: Boolean = true): List<Pair<BlockContract, JsonObject>> {
    if (contract.kind in setOf("vision.result","vision.readresult","vision.resultaction","ocr.findtext") || contract.kind.startsWith("screen.") || contract.kind in visualUtilityKinds) return listOf(contract to arguments.deepCopy())
    if (recognitionUsesOwnCapture(contract.kind)) return listOf(contract to arguments.deepCopy().apply { addProperty("autoCapture", capture) })
    if (!capture) return listOf(contract to arguments.deepCopy())
    val frame = arguments["frameVariable"]?.asString ?: "frame"
    return listOf(
        requireNotNull(BlockCatalog.find("screen.capture")) to JsonObject().apply { addProperty("resultVariable", frame) },
        contract to arguments.deepCopy(),
        requireNotNull(BlockCatalog.find("screen.release")) to JsonObject().apply { addProperty("frameVariable", frame) },
    )
}

internal fun recognitionFixedRegionInputs(inputs: Map<String, String>, region: String): Map<String, String> =
    (inputs + ("region" to region)).mapValues { (name, value) ->
        if (name in setOf("leftVariable", "topVariable", "rightVariable", "bottomVariable")) "" else value
    }

internal fun recognitionAlgorithmHelp(kind: String): String = when (kind) {
    "vision.findimage" -> "像素匹配按模板顺序返回首个命中，不是最高分。频次N表示首次及之后每N次访问识别，跳过轮次不截图或执行动作。裁剪图像覆盖时释放，插件结束清理；动作偏移使用原始屏幕像素。"
    "vision.findgray" -> "灰度最高分匹配，不缩放、不旋转；模板≤256K像素，范围≤400万像素，超出计算预算明确报错。是否找到为1/0。"
    "vision.findallimages" -> "按模板顺序及方向保留结果（包括重叠），最多64项；truncated表示存在未保留项。结果集合需声明为字变量。动作默认作用首项，开启全结果才逐项执行；按住仅限首项。等待可取消，动作前释放临时帧，成功/失败分支互斥。"
    "ocr.alphanumeric" -> "识别指定单行英文、数字，宽高比≤20；开启保留符号后可输出括号、加减号、小数点、百分号、空格等ASCII字符（内置ONNX/V3组件）。结果写入文本变量，可交给通用文本工具提取、拆分或替换。置信度为已接受字符平均值，不代表正确率；不做中文或文字检测。"
    else -> "坐标使用原始屏幕像素，范围右/下边界不包含。已有帧由脚本管理；图像输出不与参考软件未知格式混用。"
}

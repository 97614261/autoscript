package com.autoscript.studio

import com.autoscript.project.store.ProjectVariableType
import com.autoscript.runtime.api.RecognitionPreviewReply as Reply
import com.autoscript.studio.generated.BlockContract
import com.google.gson.JsonObject

internal data class ImageRecognitionOutput(val field: String, val label: String, val type: ProjectVariableType, val suggestion: String)

/** Immutable tested settings. Coordinates always belong to the processed frame. */
internal sealed class ImagePipelineRecognitionTail(val roi: ImageToolCodeGen.Roi) {
    abstract val title: String
    abstract val outputs: List<ImageRecognitionOutput>
    protected abstract fun configured(width: Int, height: Int, resources: List<JsonObject>): Pair<String, JsonObject>

    fun block(frame: String, width: Int, height: Int, names: Map<String, String>, variables: List<CalculationVariable>,
        resources: List<JsonObject>): Pair<BlockContract, JsonObject> {
        templateCropDimensions(width, height, roi)
        require(names.keys == outputs.map { it.field }.toSet()) { "识别输出配置不完整或包含未知字段" }
        require(names.values.toSet().size == names.size && frame !in names.values) { "识别输出不能重名或覆盖帧变量" }
        outputs.forEach { output ->
            val name = names.getValue(output.field)
            require(name.matches(com.autoscript.project.store.ProjectNames.pattern) && variables.any { it.name == name && it.type == output.type }) {
                "${output.label}请选择类型匹配的已声明变量"
            }
        }
        val (kind, args) = configured(width, height, resources)
        args.addProperty("frameVariable", frame)
        names.forEach { (key, value) -> args.addProperty(key, value) }
        return requireNotNull(BlockCatalog.find(kind)) to args
    }

    class Match(val settings: RecognitionPreviewConfiguration, roi: ImageToolCodeGen.Roi) : ImagePipelineRecognitionTail(roi) {
        init { require(settings.region == roi) { "加入范围必须与测试范围一致，请重新测试" } }
        override val title = when(settings.operation) {Reply.GRAY_TEMPLATE->"灰度找图";Reply.FEATURE_TEMPLATE->"特征匹配";Reply.SCALED_TEMPLATE->"多尺度像素找图";else->"像素找图"}
        override val outputs = if(settings.operation in setOf(Reply.SCALED_TEMPLATE,Reply.FEATURE_TEMPLATE)) listOf(ImageRecognitionOutput("resultVariable","匹配集合 · 字符变量",ProjectVariableType.STRING,if(settings.operation==Reply.FEATURE_TEMPLATE) "featureMatches" else "scaledMatches")) else listOf(
            ImageRecognitionOutput("foundVariable", "是否找到 · 整数", ProjectVariableType.INTEGER, "found"),
            ImageRecognitionOutput("xVariable", "结果X · 整数", ProjectVariableType.INTEGER, "foundX"),
            ImageRecognitionOutput("yVariable", "结果Y · 整数", ProjectVariableType.INTEGER, "foundY"),
            ImageRecognitionOutput("scoreVariable", "得分 · 整数", ProjectVariableType.INTEGER, "matchScore"),
        )
        override fun configured(width: Int, height: Int, resources: List<JsonObject>): Pair<String, JsonObject> {
            require(settings.width == width && settings.height == height) { "处理图尺寸已变化，请重新测试" }
            require(resources.any { it.get("kind")?.asString == "image" && it.get("path")?.asString == settings.template }) { "模板图片未声明" }
            val kind = when(settings.operation) {Reply.GRAY_TEMPLATE->"vision.findgray";Reply.FEATURE_TEMPLATE->"vision.findfeatures";Reply.SCALED_TEMPLATE->"vision.findscaled";else->"vision.findimage"}
            return kind to settings.arguments().apply {
                if(settings.operation !in setOf(Reply.SCALED_TEMPLATE,Reply.FEATURE_TEMPLATE)) addProperty("autoCapture", false)
                if (kind == "vision.findimage") addProperty("successAction", "none")
            }
        }
    }

    class Ocr(val settings: ImageToolOcrConfig, roi: ImageToolCodeGen.Roi) : ImagePipelineRecognitionTail(roi) {
        override val title = if (settings.operation == Reply.GLYPH) "字库取字" else "字母数字取字"
        override val outputs = if(settings.operation==Reply.ALPHANUMERIC_V5) listOf(ImageRecognitionOutput("resultVariable","识别JSON · 字符串",ProjectVariableType.STRING,"ocrResult")) else listOf(
            ImageRecognitionOutput("textVariable", "识别文本 · 字符串", ProjectVariableType.STRING, "ocrText"),
            ImageRecognitionOutput("scoreVariable", "得分 · 整数", ProjectVariableType.INTEGER, "ocrScore"),
        ) + if (settings.operation == Reply.GLYPH) listOf(
            ImageRecognitionOutput("coverageVariable", "覆盖率 · 整数", ProjectVariableType.INTEGER, "ocrCoverage")) else emptyList()
        override fun configured(width: Int, height: Int, resources: List<JsonObject>): Pair<String, JsonObject> {
            if (settings.operation == Reply.GLYPH) require(resources.any { isGlyphDictionaryResource(it) && it.get("path")?.asString == settings.dictionaryPath }) { "字库未声明" }
            else require(roi.width.toLong() <= roi.height.toLong() * 20) { "字母数字仅支持宽高比不超过20的单行区域" }
            return settings.kind to settings.arguments(roi).apply {
                if (settings.kind == "ocr.alphanumeric") addProperty("autoCapture", false)
            }
        }
    }
}

internal fun imageProcessingRecognitionBlocks(pipeline: ImageProcessingPipeline, imagePath: String?, scratch: String, result: String,
    tail: ImagePipelineRecognitionTail, names: Map<String, String>, variables: List<CalculationVariable>, resources: List<JsonObject>,
    retainFinal: Boolean = false): List<Pair<BlockContract, JsonObject>> {
    require(scratch !in names.values && result !in names.values) { "识别输出不能覆盖中间帧或最终帧" }
    val blocks = imageProcessingBlocks(pipeline, imagePath, scratch, result, variables, resources)
    val match = tail.block(result, pipeline.dimensions.first, pipeline.dimensions.second, names, variables, resources)
    return blocks + match + if (retainFinal) emptyList() else listOf(requireNotNull(BlockCatalog.find("screen.release")) to
        JsonObject().apply { addProperty("frameVariable", result) })
}

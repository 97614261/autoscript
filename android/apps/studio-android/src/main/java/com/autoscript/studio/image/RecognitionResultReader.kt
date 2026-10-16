package com.autoscript.studio

import com.autoscript.project.store.ProjectVariableType
import com.autoscript.studio.generated.BlockContract
import com.google.gson.JsonObject

internal const val RECOGNITION_RESULT_READER = "studio:recognition-result-reader"
internal enum class RecognitionResultShape(val label: String, val fields: List<String>) {
    IMAGES("多结果找图", listOf("count", "truncated", "x", "y", "width", "height", "scorePermille", "path", "centerX", "centerY")),
    GLYPH_SUMMARY("字库详细结果 · 总览", listOf("text", "coveragePermille", "averageScorePermille", "count")),
    GLYPH_ITEM("字库详细结果 · 逐字", listOf("count", "text", "left", "top", "right", "bottom", "score", "recognized", "centerX", "centerY")),
    TEXT_ITEM("文字查找结果", listOf("count", "text", "left", "top", "right", "bottom", "centerX", "centerY")),
    COLORS("颜色位置结果", listOf("count", "x", "y")),
    COMPONENTS("颜色连通块", listOf("count", "area", "left", "top", "right", "bottom", "centerX", "centerY")),
}

internal fun recognitionResultProducerHint(shape: RecognitionResultShape): String = when(shape) {
    RecognitionResultShape.IMAGES -> "先执行多结果找图，选择其集合输出；单点找到/X/Y不是集合。"
    RecognitionResultShape.GLYPH_ITEM, RecognitionResultShape.GLYPH_SUMMARY -> "先执行字库逐字结果，选择其JSON字符输出；普通取字文本不是逐字详情。"
    RecognitionResultShape.TEXT_ITEM -> "先执行从字库结果找文字，选择匹配集合；不是目标文字或普通OCR文本。"
    RecognitionResultShape.COLORS -> "先执行查找全部颜色或批量多点找色，选择坐标集合；单点找到1/0不是集合。"
    RecognitionResultShape.COMPONENTS -> "先执行颜色连通块，选择连通块集合，不是颜色坐标集合。"
}

internal fun recognitionActionClearsConfirmation(key: String) = key in setOf(
    "sourceVariable", "shape", "offsetX", "offsetY", "anchor", "selection", "action", "limit", "durationMs")
internal fun recognitionResultType(field: String) = if (field in setOf("text", "path")) ProjectVariableType.STRING else ProjectVariableType.INTEGER

internal fun recognitionResultFieldLabel(field: String): String = mapOf("count" to "结果数量", "truncated" to "是否截断 1/0",
    "text" to "文字", "coveragePermille" to "覆盖率 ‰", "averageScorePermille" to "平均得分 ‰", "scorePermille" to "得分 ‰",
    "score" to "逐字得分 ‰", "recognized" to "已识别 1/0", "centerX" to "中心 X", "centerY" to "中心 Y", "x" to "X", "y" to "Y",
    "left" to "左", "top" to "上", "right" to "右（不含）", "bottom" to "下（不含）", "width" to "宽", "height" to "高", "area" to "面积", "path" to "模板路径")[field] ?: field

/** Compose the existing result/Data/conversion contracts without changing their JSON semantics. */
internal fun recognitionResultBlocks(shape: RecognitionResultShape, field: String, index: Int, source: String,
    @Suppress("UNUSED_PARAMETER") scratch: String, target: String, variables: List<CalculationVariable>): List<Pair<BlockContract, JsonObject>> {
    require(field in shape.fields) { "请选择当前结果类型的属性" }
    fun declared(name: String, type: ProjectVariableType) = variables.any { it.name == name && it.type == type }
    require(declared(source, ProjectVariableType.STRING) && declared(target, recognitionResultType(field)) && source != target) { "来源需为字符变量，接收变量需类型匹配且与来源不同" }
    fun args(vararg values: Pair<String, Any>) = JsonObject().apply { values.forEach { (key, value) ->
        if (value is Number) addProperty(key, value) else addProperty(key, value.toString())
    } }
    val aggregate = field in setOf("count", "truncated") || shape == RecognitionResultShape.GLYPH_SUMMARY
    val limit = when (shape) { RecognitionResultShape.IMAGES -> 64; RecognitionResultShape.COLORS, RecognitionResultShape.COMPONENTS -> 256; else -> 2048 }
    require(if (aggregate) index == 0 else index in 1..limit) { "总览序号0，逐项序号1–$limit" }
    return listOf(requireNotNull(BlockCatalog.find("vision.readresult")) to args("sourceVariable" to source, "shape" to shape.name,
        "index" to index, "field" to field, "resultVariable" to target))
}

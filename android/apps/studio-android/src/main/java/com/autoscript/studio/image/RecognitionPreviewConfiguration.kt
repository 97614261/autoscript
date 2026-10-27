package com.autoscript.studio

import com.autoscript.runtime.api.RecognitionPreviewReply
import com.google.gson.JsonObject

/** One immutable configuration drives saved-image preview and the block backfill. */
internal data class RecognitionPreviewConfiguration(
    val operation: Int,
    val template: String,
    val tolerance: Int,
    val similarityPermille: Int,
    val width: Int,
    val height: Int,
    val region: ImageToolCodeGen.Roi = ImageToolCodeGen.Roi(0, 0, width, height),
    val direction: Int = 0,
    val minScalePermille:Int=1000,val maxScalePermille:Int=1000,val stepPermille:Int=100,
) {
    init {
        require(operation in setOf(RecognitionPreviewReply.TEMPLATE, RecognitionPreviewReply.GRAY_TEMPLATE,RecognitionPreviewReply.SCALED_TEMPLATE,RecognitionPreviewReply.FEATURE_TEMPLATE))
        require(template.startsWith("assets/images/") && template.split('/').none { it in setOf("", ".", "..") } &&
            '\\' !in template && template.length <= 512)
        require(tolerance in 0..255 && similarityPermille in 0..1000)
        require(operation !in setOf(RecognitionPreviewReply.GRAY_TEMPLATE,RecognitionPreviewReply.FEATURE_TEMPLATE) || tolerance == 0) { "灰度/特征不使用颜色容差" }
        require(width in 1..4096 && height in 1..4096 && width.toLong() * height <= 4_194_304)
        require(region.left >= 0 && region.top >= 0 && region.right <= width && region.bottom <= height &&
            region.left < region.right && region.top < region.bottom) { "测试范围须为原图内的非空矩形" }
        require(direction in 0..4 && (operation !in setOf(RecognitionPreviewReply.GRAY_TEMPLATE,RecognitionPreviewReply.FEATURE_TEMPLATE) || direction == 0)) {
            "灰度为最高分匹配，不支持搜索方向"
        }
        if(operation==RecognitionPreviewReply.FEATURE_TEMPLATE) require(region.width.toLong()*region.height<=1_048_576)
        if(operation==RecognitionPreviewReply.SCALED_TEMPLATE) require(minScalePermille in 250..4000 && maxScalePermille in minScalePermille..4000 && stepPermille in 1..1000 &&
            (maxScalePermille-minScalePermille)%stepPermille==0 && (maxScalePermille-minScalePermille)/stepPermille+1<=16) {"范围250–4000‰，含端点最多16档且整除步长"}
    }

    fun options(): IntArray = intArrayOf(region.left, region.top, region.right, region.bottom,
        similarityPermille, 0xFFFFFF, tolerance, direction).let {if(operation==RecognitionPreviewReply.SCALED_TEMPLATE) it+intArrayOf(minScalePermille,maxScalePermille,stepPermille) else it}

    fun arguments(): JsonObject = JsonObject().apply {
        if(operation==RecognitionPreviewReply.FEATURE_TEMPLATE) {
            addProperty("imagePath",template);addProperty("frameVariable","frame");addProperty("resultVariable","featureMatches")
            addProperty("minimumScorePermille",similarityPermille)
            addProperty("left",region.left);addProperty("top",region.top);addProperty("right",region.right);addProperty("bottom",region.bottom)
            return@apply
        }
        if(operation==RecognitionPreviewReply.SCALED_TEMPLATE) {
            addProperty("imagePath",template);addProperty("frameVariable","frame");addProperty("resultVariable","matches")
            addProperty("similarityPermille",similarityPermille);addProperty("tolerance",tolerance);addProperty("direction",direction)
            addProperty("minScalePermille",minScalePermille);addProperty("maxScalePermille",maxScalePermille);addProperty("stepPermille",stepPermille)
            addProperty("left",region.left);addProperty("top",region.top);addProperty("right",region.right);addProperty("bottom",region.bottom)
            return@apply
        }
        addProperty("imagePath", template)
        addProperty("similarityPermille", similarityPermille)
        addProperty("frameVariable", "frame")
        addProperty("foundVariable", "found")
        addProperty("xVariable", "foundX")
        addProperty("yVariable", "foundY")
        if (operation == RecognitionPreviewReply.TEMPLATE) {
            addProperty("tolerance", tolerance)
            addProperty("direction", direction)
        } else {
            addProperty("scoreVariable", "grayScore")
            addProperty("autoCapture", true)
        }
        add("region", JsonObject().apply {
            addProperty("left", region.left); addProperty("top", region.top)
            addProperty("right", region.right); addProperty("bottom", region.bottom)
        })
    }
}

/** X/Y/width/height text stays editable; invalid or overflowing input never becomes another ROI. */
internal fun recognitionPreviewRegion(values: List<String>, width: Int, height: Int): ImageToolCodeGen.Roi? {
    if (values.size != 4) return null
    val numbers = values.map { it.toLongOrNull() ?: return null }
    val (left, top, w, h) = numbers
    if (left !in 0L until width.toLong() || top !in 0L until height.toLong() || w !in 1L..width.toLong() || h !in 1L..height.toLong()) return null
    if (left + w > width || top + h > height) return null
    return ImageToolCodeGen.Roi(left.toInt(), top.toInt(), (left + w).toInt(), (top + h).toInt())
}

internal val recognitionSearchDirections = listOf("左上 → 右下", "右上 → 左下", "左下 → 右上", "右下 → 左上", "中心向外")

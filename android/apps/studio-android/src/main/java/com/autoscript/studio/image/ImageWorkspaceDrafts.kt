package com.autoscript.studio

/** One workspace only: raw fields survive panel switches, never results, jobs or proof. */
internal class ImageWorkspaceDrafts {
    var match: ImageMatchDraft? = null
    var ocr: ImageOcrDraft? = null
    var insertion: ImageInsertionDraft? = null
}

internal data class ImageMatchDraft(
    val template: String, val similarity: String, val tolerance: String,
    val gray: Boolean, val scaled: Boolean, val feature: Boolean,
    val scaleMin: String, val scaleMax: String, val scaleStep: String, val direction: Int,
)

internal data class ImageOcrDraft(
    val operation: Int, val dictionary: String, val palette: GlyphPaletteDraft,
    val threshold: String, val gap: String, val detailed: Boolean,
    val bounds: List<String>, val sourceIdentity: Any, val selection: ImageToolCodeGen.Roi,
) {
    fun boundsFor(source: Any, selected: ImageToolCodeGen.Roi): List<String> =
        if (source === sourceIdentity && selected == selection) bounds
        else listOf(selected.left, selected.top, selected.width, selected.height).map(Int::toString)
}

internal data class ImageInsertionDraft(
    val scratch: String, val output: String, val assetSource: Boolean,
    val retainFinal: Boolean, val outputs: Map<String, String>,
)

/** Only fixed-size matching has this rejection; scaled/features decide in their own engine. */
internal fun fixedTemplateSizeProblem(operation: Int, width: Int, height: Int, region: ImageToolCodeGen.Roi): String? {
    if (operation !in setOf(com.autoscript.runtime.api.RecognitionPreviewReply.TEMPLATE,
            com.autoscript.runtime.api.RecognitionPreviewReply.GRAY_TEMPLATE)) return null
    if (width <= 0 || height <= 0) return "模板图片无法读取，请重新选择或保存模板；未执行识别"
    return if (width > region.width || height > region.height)
        "模板 ${width}×${height}px 大于搜索范围 ${region.width}×${region.height}px，请扩大范围；未执行识别"
    else null
}

internal class ImageMatchInputException(message: String) : IllegalArgumentException(message)

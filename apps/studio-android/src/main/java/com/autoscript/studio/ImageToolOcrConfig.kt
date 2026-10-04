package com.autoscript.studio

import com.autoscript.project.store.ProjectSnapshot
import com.autoscript.runtime.api.RecognitionPreviewReply
import com.google.gson.JsonObject
import java.io.File

internal data class ImageToolOcrConfig(
    val operation: Int = RecognitionPreviewReply.ALPHANUMERIC,
    val dictionaryPath: String = "",
    val foregroundRgb: Int = 0xFFFFFF,
    val tolerance: Int = 16,
    val threshold: Int = 500,
    val spaceGapColumns: Int = 2,
) {
    val kind: String get() = if (operation == RecognitionPreviewReply.GLYPH) "ocr.glyph" else "ocr.alphanumeric"
    fun options(roi: ImageToolCodeGen.Roi): IntArray = intArrayOf(roi.left, roi.top, roi.right, roi.bottom,
        threshold, foregroundRgb, tolerance, spaceGapColumns)
    fun arguments(roi: ImageToolCodeGen.Roi): JsonObject {
        require(operation in setOf(RecognitionPreviewReply.GLYPH, RecognitionPreviewReply.ALPHANUMERIC) &&
            RecognitionPreviewReply.validOptions(operation, options(roi)))
        return JsonObject().apply {
            addProperty("frameVariable", "frame")
            addProperty("textVariable", "ocrText")
            addProperty("scoreVariable", "ocrScore")
            add("region", JsonObject().apply {
                addProperty("left", roi.left); addProperty("top", roi.top)
                addProperty("right", roi.right); addProperty("bottom", roi.bottom)
            })
            if (operation == RecognitionPreviewReply.GLYPH) {
                require(dictionaryPath.matches(Regex("dictionaries/[^\\\\]+\\.asglyph")) && dictionaryPath.length <= 256 &&
                    dictionaryPath.split('/').none { it == "." || it == ".." || it.isEmpty() })
                addProperty("dictionaryPath", dictionaryPath)
                addProperty("foregroundRgb", "#%06X".format(java.util.Locale.ROOT, foregroundRgb))
                addProperty("tolerance", tolerance)
                addProperty("similarityPermille", threshold)
                addProperty("spaceGapColumns", spaceGapColumns)
                addProperty("coverageVariable", "ocrCoverage")
            } else {
                addProperty("minimumConfidencePermille", threshold)
                addProperty("autoCapture", true)
            }
        }
    }
    fun snippet(roi: ImageToolCodeGen.Roi, frameWidth: Int, frameHeight: Int): ImageToolCodeGen.Snippet {
        arguments(roi)
        require(roi.right <= frameWidth && roi.bottom <= frameHeight)
        val bounds = "${roi.left}, ${roi.top}, ${roi.right}, ${roi.bottom}"
        val code = buildString {
            if (operation == RecognitionPreviewReply.GLYPH) appendLine("local dictionary = Ocr.loadDictionary(${ImageToolCodeGen.luaString(dictionaryPath)})")
            appendLine("local frame = Screen.capture()")
            if (operation == RecognitionPreviewReply.GLYPH) {
                appendLine("local result = Ocr.glyph(frame, dictionary, ${ImageToolCodeGen.formatRgb(foregroundRgb)}, $tolerance, $threshold, $bounds, $spaceGapColumns)")
                appendLine("local ocrText, ocrCoverage, ocrScore = result.text, result.coveragePermille, result.averageScorePermille")
            } else {
                appendLine("local result = Ocr.alphanumeric(frame, $bounds, $threshold)")
                appendLine("local ocrText, ocrScore = result.text, result.averageScorePermille")
            }
            append("Screen.release(frame)")
        }
        return ImageToolCodeGen.Snippet(code, "取字配置 · ${if (operation == RecognitionPreviewReply.GLYPH) "字库" else "字母数字"} · 原图范围 $bounds",
            rejection = LuaSnippetGate.reject(code), imageSelection = ImageToolCodeGen.VisualSelection(
                ImageToolMode.OCR, roi, emptyList(), tolerance, frameWidth, frameHeight, this))
    }
}

internal fun imageRecognitionDictionary(snapshot: ProjectSnapshot, path: String): File {
    require(snapshot.manifest.resources.any { it.get("kind")?.asString == "glyph_dictionary" && it.get("path")?.asString == path }) { "字库未在项目中声明" }
    return projectFileWithinRoot(snapshot.directory, path).also { require(it.isFile && it.length() in 1..8L * 1024 * 1024) { "字库无效或超出预算" } }
}

internal fun recognitionPreviewMessage(reply: RecognitionPreviewReply): String = when (reply.status) {
    RecognitionPreviewReply.SUCCESS -> if (reply.operation in setOf(RecognitionPreviewReply.TEMPLATE, RecognitionPreviewReply.GRAY_TEMPLATE))
        "已找到：(${reply.x}, ${reply.y}) · ${reply.scorePermille}‰" else
        "识别文本：${reply.text}\n得分 ${reply.scorePermille}‰" + if (reply.operation == RecognitionPreviewReply.GLYPH) " · 覆盖率 ${reply.coveragePermille}‰" else ""
    RecognitionPreviewReply.NOT_FOUND -> "未识别到符合阈值的文字或模板"
    RecognitionPreviewReply.BUSY -> "脚本运行或其他测试进行中，请先停止或等待完成"
    RecognitionPreviewReply.SESSION_MISMATCH -> "运行服务会话未建立或已变化，请重新测试"
    RecognitionPreviewReply.BUDGET_EXCEEDED -> "超过识别预算，请缩小区域或模板后重试"
    RecognitionPreviewReply.CANCELLED -> "已取消识别"
    RecognitionPreviewReply.TIMEOUT -> "识别超过5秒，请缩小区域后重试"
    RecognitionPreviewReply.ENGINE_ERROR -> "识别引擎不可用或识别失败"
    else -> "图像、字库、范围或参数无效；字母数字模式仅支持宽高比不超过20的单行区域"
}

/** Glyph lacks an implicit-capture property, so insert a balanced frame lifecycle. */
internal fun ocrRecognitionSequence(kind: String, arguments: JsonObject) = if (kind == "ocr.glyph") listOf(
    requireNotNull(BlockCatalog.find("screen.capture")) to JsonObject().apply { addProperty("resultVariable", arguments.get("frameVariable").asString) },
    requireNotNull(BlockCatalog.find(kind)) to arguments.deepCopy(),
    requireNotNull(BlockCatalog.find("screen.release")) to JsonObject().apply { addProperty("frameVariable", arguments.get("frameVariable").asString) },
) else listOf(requireNotNull(BlockCatalog.find(kind)) to arguments.deepCopy())

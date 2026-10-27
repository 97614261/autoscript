package com.autoscript.studio

import com.autoscript.project.store.ProjectSnapshot
import com.autoscript.project.store.ProjectGlyphColor
import com.autoscript.runtime.api.RecognitionPreviewReply
import com.google.gson.JsonObject
import java.io.File

internal data class ImageToolOcrConfig(
    val operation: Int = RecognitionPreviewReply.LATIN_TEXT,
    val dictionaryPath: String = "",
    val foregroundRgb: Int = 0xFFFFFF,
    val tolerance: Int = 16,
    val threshold: Int = 500,
    val spaceGapColumns: Int = 2,
    val additionalColors: String = "",
) {
    val kind: String get() = when(operation) {RecognitionPreviewReply.GLYPH->"ocr.glyph";RecognitionPreviewReply.ALPHANUMERIC_V5->"ocr.alphanumericv5";else->"ocr.alphanumeric"}
    /** Only recognition settings cross authoring contexts, never sample geometry or output names. */
    fun applyGlyphSettings(arguments: JsonObject): JsonObject {
        require(operation == RecognitionPreviewReply.GLYPH)
        this.arguments(ImageToolCodeGen.Roi(0, 0, 1, 1)) // validate the saved path and numeric settings
        return arguments.deepCopy().apply {
            addProperty("dictionaryPath", dictionaryPath)
            addProperty("foregroundRgb", foregroundRgb)
            addProperty("tolerance", tolerance)
            addProperty("similarityPermille", threshold)
            addProperty("spaceGapColumns", spaceGapColumns)
            remove("additionalColors")
            if (additionalColors.isNotEmpty()) addProperty("additionalColors", additionalColors)
        }
    }
    fun options(roi: ImageToolCodeGen.Roi): IntArray {
        require(operation == RecognitionPreviewReply.GLYPH || additionalColors.isEmpty())
        val extra = ProjectGlyphColor.decodeAdditional(additionalColors, foregroundRgb)
        return intArrayOf(roi.left, roi.top, roi.right, roi.bottom, threshold, foregroundRgb, tolerance, spaceGapColumns) +
            extra.flatMap { listOf(it.rgb, it.tolerance) }.toIntArray()
    }
    fun arguments(roi: ImageToolCodeGen.Roi): JsonObject {
        require(operation in setOf(RecognitionPreviewReply.GLYPH, RecognitionPreviewReply.ALPHANUMERIC,RecognitionPreviewReply.ALPHANUMERIC_V5,RecognitionPreviewReply.LATIN_TEXT) &&
            RecognitionPreviewReply.validOptions(operation, options(roi)))
        return JsonObject().apply {
            addProperty("frameVariable", "frame")
            if(operation==RecognitionPreviewReply.ALPHANUMERIC_V5) {
                addProperty("resultVariable","ocrResult");addProperty("minimumScorePermille",threshold)
                addProperty("left",roi.left);addProperty("top",roi.top);addProperty("right",roi.right);addProperty("bottom",roi.bottom)
                return@apply
            }
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
                addProperty("foregroundRgb", foregroundRgb)
                addProperty("tolerance", tolerance)
                addProperty("similarityPermille", threshold)
                addProperty("spaceGapColumns", spaceGapColumns)
                addProperty("coverageVariable", "ocrCoverage")
                if (additionalColors.isNotEmpty()) addProperty("additionalColors", additionalColors)
            } else {
                addProperty("minimumConfidencePermille", threshold)
                addProperty("autoCapture", true)
                addProperty("includeSymbols", operation == RecognitionPreviewReply.LATIN_TEXT)
            }
        }
    }
    fun snippet(roi: ImageToolCodeGen.Roi, frameWidth: Int, frameHeight: Int): ImageToolCodeGen.Snippet {
        arguments(roi)
        require(roi.right <= frameWidth && roi.bottom <= frameHeight)
        val bounds = "${roi.left}, ${roi.top}, ${roi.right}, ${roi.bottom}"
        val code = buildString {
            if (operation == RecognitionPreviewReply.GLYPH && additionalColors.isNotEmpty())
                appendLine("if Ocr.glyphPaletteVersion ~= 1 then error('GLYPH_PALETTE_UNSUPPORTED') end")
            if (operation == RecognitionPreviewReply.GLYPH) appendLine("local dictionary = Ocr.loadDictionary(${ImageToolCodeGen.luaString(dictionaryPath)})")
            appendLine("local frame = Screen.capture()")
            if (operation == RecognitionPreviewReply.GLYPH) {
                val extra = if (additionalColors.isEmpty()) "" else ", ${ImageToolCodeGen.luaString(additionalColors)}"
                appendLine("local result = Ocr.glyph(frame, dictionary, ${ImageToolCodeGen.formatRgb(foregroundRgb)}, $tolerance, $threshold, $bounds, $spaceGapColumns$extra)")
                appendLine("local ocrText, ocrCoverage, ocrScore = result.text, result.coveragePermille, result.averageScorePermille")
            } else if(operation==RecognitionPreviewReply.ALPHANUMERIC_V5) {
                appendLine("local ocrResult = Ocr.alphanumericV5(frame, $bounds, $threshold)")
            } else {
                val api = if (operation == RecognitionPreviewReply.LATIN_TEXT) "latinText" else "alphanumeric"
                appendLine("local result = Ocr.$api(frame, $bounds, $threshold)")
                appendLine("local ocrText, ocrScore = result.text, result.averageScorePermille")
            }
            append("Screen.release(frame)")
        }
        return ImageToolCodeGen.Snippet(code, "取字配置 · ${if (operation == RecognitionPreviewReply.GLYPH) "字库" else "字母数字"} · 原图范围 $bounds",
            rejection = LuaSnippetGate.reject(code), imageSelection = ImageToolCodeGen.VisualSelection(
                ImageToolMode.OCR, roi, emptyList(), tolerance, frameWidth, frameHeight, this))
    }
}

internal fun isGlyphDictionaryResource(resource: JsonObject): Boolean = resource.get("kind")?.asString in setOf("glyphDictionary", "glyph_dictionary")

internal fun imageRecognitionDictionary(snapshot: ProjectSnapshot, path: String): File {
    require(snapshot.manifest.resources.any { isGlyphDictionaryResource(it) && it.get("path")?.asString == path }) { "字库未在项目中声明" }
    return projectFileWithinRoot(snapshot.directory, path).also { require(it.isFile && it.length() in 1..8L * 1024 * 1024) { "字库无效或超出预算" } }
}

internal fun recognitionPreviewMessage(reply: RecognitionPreviewReply): String = when (reply.status) {
    RecognitionPreviewReply.SUCCESS -> if(reply.operation==RecognitionPreviewReply.SCALED_TEMPLATE)
        "已找到：(${reply.x}, ${reply.y}) · ${reply.scorePermille}‰ · 比例"+runCatching {com.google.gson.JsonParser.parseString(reply.text).asJsonArray[0].asJsonObject["scalePermille"].asInt.toString()}.getOrDefault("未知")+"‰"
    else if (reply.operation in setOf(RecognitionPreviewReply.TEMPLATE, RecognitionPreviewReply.GRAY_TEMPLATE,RecognitionPreviewReply.FEATURE_TEMPLATE))
        "已找到：(${reply.x}, ${reply.y}) · ${reply.scorePermille}‰" else
        "识别文本：${if(reply.operation==RecognitionPreviewReply.GLYPH_DETAILS) runCatching { com.google.gson.JsonParser.parseString(reply.text).asJsonObject["text"].asString }.getOrDefault("逐字结果无效") else reply.text}\n得分 ${reply.scorePermille}‰" + if (reply.operation in setOf(RecognitionPreviewReply.GLYPH,RecognitionPreviewReply.GLYPH_DETAILS)) " · 覆盖率 ${reply.coveragePermille}‰" else ""
    RecognitionPreviewReply.NOT_FOUND -> "未识别到符合阈值的文字或模板"
    RecognitionPreviewReply.BUSY -> "脚本运行或其他测试进行中，请先停止或等待完成"
    RecognitionPreviewReply.SESSION_MISMATCH -> "运行服务会话未建立或已变化，请重新测试"
    RecognitionPreviewReply.BUDGET_EXCEEDED -> "超过识别预算，请缩小区域或模板后重试"
    RecognitionPreviewReply.CANCELLED -> "已取消识别"
    RecognitionPreviewReply.TIMEOUT -> "识别超过5秒，请缩小区域后重试"
    RecognitionPreviewReply.ENGINE_ERROR -> recognitionEngineFailure(reply.text)
    else -> "图像、字库、范围或参数无效；字母数字模式仅支持宽高比不超过20的单行区域"
}

internal fun recognitionEngineFailure(code: String): String = when (com.autoscript.runtime.api.RecognitionPreviewErrors.accepted(code)) {
    "VISION_COMPONENT_NOT_INSTALLED", "VISION_COMPONENT_MISSING" -> "缺少所需视觉组件，请在我的/运行环境管理；未执行识别"
    "VISION_COMPONENT_CHECKING", "VISION_COMPONENT_BUSY", "VISION_BUSY" -> "视觉组件检查或初始化忙，请复查后重试"
    "VISION_COMPONENT_CORRUPT", "VISION_COMPONENT_INCOMPATIBLE", "VISION_COMPONENT_DEVICE_UNSUPPORTED" -> "视觉组件损坏、版本或ABI不兼容，请在运行环境查看具体状态"
    "VISION_COMPONENT_LOAD_FAILED", "VISION_INITIALIZATION_FAILED", "VISION_LIBRARY_UNAVAILABLE" -> "视觉SDK/模型初始化或原生库加载失败；不是未找到"
    "VISION_COMPONENT_RESTART_REQUIRED" -> "当前进程已加载另一组件，更换后需手动重启智构"
    "VISION_CANCELLED_OR_TIMEOUT" -> "识别已取消或超过执行期限；不是未找到"
    "VISION_CLEANUP_FAILED", "VISION_CLOSED" -> "视觉会话关闭或资源清理失败，请复查运行环境"
    else -> "识别引擎失败，无法判断是否找到；请查看诊断日志"
}

/** Glyph lacks an implicit-capture property, so insert a balanced frame lifecycle. */
internal fun ocrRecognitionSequence(kind: String, arguments: JsonObject) = if (kind in setOf("ocr.glyph","ocr.alphanumericv5")) listOf(
    requireNotNull(BlockCatalog.find("screen.capture")) to JsonObject().apply { addProperty("resultVariable", arguments.get("frameVariable").asString) },
    requireNotNull(BlockCatalog.find(kind)) to arguments.deepCopy(),
    requireNotNull(BlockCatalog.find("screen.release")) to JsonObject().apply { addProperty("frameVariable", arguments.get("frameVariable").asString) },
) else listOf(requireNotNull(BlockCatalog.find(kind)) to arguments.deepCopy())

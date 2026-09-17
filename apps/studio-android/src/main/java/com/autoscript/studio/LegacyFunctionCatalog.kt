package com.autoscript.studio

import com.autoscript.studio.generated.BlockCategory

/** 函数库里的一项：可视化项目指向一个积木 kind，Lua 项目是一段真实存在的脚本 API 调用模板。 */
internal data class LegacyFunctionEntry(
    val title: String,
    val detail: String,
    val snippet: String,
    val blockKind: String?,
    val requiredCapabilities: Set<String>,
    val parameters: List<String>,
)

internal data class LegacyFunctionGroup(
    val label: String,
    val entries: List<LegacyFunctionEntry>,
)

/**
 * 函数库内容只来自项目真实注册的能力：可视化项目用 `schema/block-catalog` 生成的积木目录，
 * Lua 项目用 `schema/api-schema/functions` 里的 25 个脚本 API。不再出现未实现的片段。
 */
internal object LegacyFunctionCatalog {
    /** 可视化项目的函数库把积木 kind 编成一行提示；宿主用 [blockKindOf] 还原后直接查目录，不做模糊搜索。 */
    const val BLOCK_HINT_PREFIX = "--@autoscript-block:"

    fun blockKindOf(snippet: String): String? = snippet.lineSequence().firstOrNull()
        ?.takeIf { it.startsWith(BLOCK_HINT_PREFIX) }
        ?.removePrefix(BLOCK_HINT_PREFIX)
        ?.trim()
        ?.takeIf(String::isNotEmpty)

    fun visualGroups(): List<LegacyFunctionGroup> = BlockCatalog.all
        .groupBy { it.category }
        .toSortedMap(compareBy { it.ordinal })
        .map { (category, blocks) ->
            LegacyFunctionGroup(
                label = categoryLabel(category),
                entries = blocks.sortedBy { it.title }.map { block ->
                    LegacyFunctionEntry(
                        title = block.title,
                        detail = block.summary,
                        snippet = "$BLOCK_HINT_PREFIX${block.kind}\n",
                        blockKind = block.kind,
                        requiredCapabilities = block.requiredCapabilities,
                        parameters = block.properties.map { it.label },
                    )
                },
            )
        }

    fun luaGroups(): List<LegacyFunctionGroup> = LUA_GROUPS

    fun categoryLabel(category: BlockCategory): String = when (category) {
        BlockCategory.FLOW -> "流程"
        BlockCategory.TASK -> "任务"
        BlockCategory.CONTROL -> "控制"
        BlockCategory.VARIABLE -> "变量"
        BlockCategory.SCREEN -> "屏幕"
        BlockCategory.VISION -> "视觉"
        BlockCategory.OCR -> "文字"
    }

    private fun lua(title: String, name: String, vararg parameters: String, detail: String = ""): LegacyFunctionEntry =
        LegacyFunctionEntry(
            title = title,
            detail = detail.ifEmpty { name },
            snippet = "$name(${parameters.joinToString(", ")})\n",
            blockKind = null,
            requiredCapabilities = emptySet(),
            parameters = parameters.toList(),
        )

    private val LUA_GROUPS = listOf(
        LegacyFunctionGroup(
            "任务",
            listOf(lua("等待", "Task.sleep", "milliseconds", detail = "挂起当前任务指定毫秒。")),
        ),
        LegacyFunctionGroup(
            "按键",
            listOf(
                lua("点击", "Input.tap", "x", "y", detail = "在设计坐标执行一次 Root 点击。"),
                lua("滑动", "Input.swipe", "x1", "y1", "x2", "y2", "durationMs", detail = "从起点滑到终点，1–5000 毫秒。"),
                lua("设备按键", "Input.keyEvent", "keyCode", detail = "发送 Android KeyEvent 键码。"),
            ),
        ),
        LegacyFunctionGroup(
            "屏幕",
            listOf(
                lua("截图", "Screen.capture", detail = "采集一帧，返回采集编号。"),
                lua("缓存帧", "Screen.cache", "captureId", detail = "把采集结果固定为任务持有的帧句柄。"),
                lua("释放帧", "Screen.release", "frame", detail = "释放帧句柄占用的内存。"),
                lua("连续截图", "Screen.captureSeries", "maxFrames", "durationMs", "targetFps", "allowPartial"),
                lua("载入图片", "Screen.loadImage", "path", detail = "载入 assets/images/ 下的模板图片。"),
                lua("取点颜色", "Screen.getColor", "frame", "x", "y"),
                lua("单点比色", "Screen.compareColor", "frame", "x", "y", "rgb", "tolerance"),
                lua("区域找色", "Screen.findColor", "frame", "rgb", "tolerance", "left", "top", "right", "bottom"),
                lua("全部找色", "Screen.findAllColor", "frame", "rgb", "tolerance", "left", "top", "right", "bottom", "limit"),
                lua("颜色计数", "Screen.countColor", "frame", "rgb", "tolerance", "left", "top", "right", "bottom", "limit"),
                lua("多点找色", "Screen.findMultiColor", "frame", "anchorRgb", "anchorTolerance", "samples", "left", "top", "right", "bottom"),
                lua("区域找图", "Screen.findImage", "frame", "template", "tolerance", "similarityPermille", "left", "top", "right", "bottom"),
            ),
        ),
        LegacyFunctionGroup(
            "文字",
            listOf(
                lua("载入字库", "Ocr.loadDictionary", "path", detail = "载入 dictionaries/ 下的 ASGLYPH 字库。"),
                lua("释放字库", "Ocr.releaseDictionary", "dictionary"),
                lua("字库识字", "Ocr.glyph", "frame", "dictionary", "foregroundRgb", "tolerance", "similarityPermille", "left", "top", "right", "bottom", "spaceGapColumns"),
            ),
        ),
        LegacyFunctionGroup(
            "旧版兼容",
            listOf(
                lua("多点找色", "Legacy.duoDianZhaoSe", "frame", "left", "top", "width", "height", "parameters", "direction", "minimumMatchPercent"),
                lua("多点比色", "Legacy.duoDianBiSe", "frame", "parameters", "minimumMatchPercent"),
                lua("区域颜色数", "Legacy.getRectColorNum", "frame", "left", "top", "width", "height", "parameters"),
                lua("RGB 转颜色值", "Legacy.getRgbColor", "red", "green", "blue"),
            ),
        ),
        LegacyFunctionGroup(
            "其它",
            listOf(
                lua("两点距离", "Math.distance", "x1", "y1", "x2", "y2"),
                lua("屏幕尺寸", "System.getScreenSize", detail = "返回当前屏幕宽高。"),
            ),
        ),
    )
}

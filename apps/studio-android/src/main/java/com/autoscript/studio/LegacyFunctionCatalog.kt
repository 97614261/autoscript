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
 * Lua 项目用 `schema/api-schema/functions` 里的真实脚本 API。不再出现未实现的片段。
 */
internal object LegacyFunctionCatalog {
    /** 可视化项目的函数库把积木 kind 编成一行提示；宿主用 [blockKindOf] 还原后直接查目录，不做模糊搜索。 */
    const val BLOCK_HINT_PREFIX = "--@autoscript-block:"
    /** 循环面板的参数不能安全地表示成 Lua；这里用仅限编辑器内部的结构化提示传递。 */
    const val LOOP_HINT_PREFIX = "--@autoscript-loop:"

    fun blockKindOf(snippet: String): String? {
        val line = snippet.lineSequence().firstOrNull()?.trim().orEmpty()
        return when {
            line.startsWith(BLOCK_HINT_PREFIX) -> line.removePrefix(BLOCK_HINT_PREFIX)
                .trim().takeIf(String::isNotEmpty)
            line.startsWith(LOOP_HINT_PREFIX + "repeat:") -> "control.repeat"
            line.startsWith(LOOP_HINT_PREFIX + "forever") ||
                line.startsWith(LOOP_HINT_PREFIX + "timed:") -> "control.while"
            else -> null
        }
    }

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

    private fun template(title: String, snippet: String, detail: String): LegacyFunctionEntry =
        LegacyFunctionEntry(
            title = title,
            detail = detail,
            snippet = snippet.trimIndent().trimEnd() + "\n",
            blockKind = null,
            requiredCapabilities = emptySet(),
            parameters = emptyList(),
        )

    private val LUA_GROUPS = listOf(
        LegacyFunctionGroup(
            "任务",
            // 方法库是“快速插入”，所以常用动作必须给可运行的字面量，而不是把
            // milliseconds / x / message 当成未定义的 Lua 全局变量塞进编辑器。
            listOf(lua("等待", "Task.sleep", "1000", detail = "挂起当前任务 1000 毫秒。")),
        ),
        LegacyFunctionGroup(
            "判断",
            listOf(
                template("条件判断", """
                    if condition then
                        -- 条件成立时执行
                    end
                """, "按条件执行一段代码。将 condition 改为实际判断表达式。"),
                template("条件分支", """
                    if condition then
                        -- 条件成立时执行
                    else
                        -- 条件不成立时执行
                    end
                """, "在条件成立和不成立时分别执行不同代码。"),
            ),
        ),
        LegacyFunctionGroup(
            "循环",
            listOf(
                template("计数循环", """
                    for index = 1, 10 do
                        -- 每次循环执行
                    end
                """, "从 1 循环到 10；可修改起点、终点和循环变量。"),
                template("条件循环", """
                    while condition do
                        -- 条件成立时持续执行
                    end
                """, "condition 为 true 时重复执行；循环内应包含退出条件或等待。"),
                template("重复直到", """
                    repeat
                        -- 至少执行一次
                    until condition
                """, "先执行一次，再重复到 condition 为 true。"),
            ),
        ),
        LegacyFunctionGroup(
            "按键",
            listOf(
                lua("点击", "Input.tap", "0", "0", detail = "在设计坐标执行一次 Root 点击；建议用截图工具插入实际坐标。"),
                lua("滑动", "Input.swipe", "0", "0", "100", "100", "300", detail = "从起点滑到终点，1–5000 毫秒。"),
                lua("设备按键", "Input.keyEvent", "4", detail = "发送 Android KeyEvent 键码；默认 4 为返回键。"),
            ),
        ),
        LegacyFunctionGroup(
            "屏幕",
            listOf(
                lua("截图", "Screen.capture", detail = "采集一帧，返回采集编号。"),
                lua("缓存帧", "Screen.cache", "captureId", detail = "把采集结果固定为任务持有的帧句柄。"),
                lua("释放帧", "Screen.release", "frame", detail = "释放帧句柄占用的内存。"),
                lua("连续截图", "Screen.captureSeries", "1", "1000", "10", "false"),
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
            "调试",
            listOf(
                lua("信息日志", "Log.info", "\"message\"", detail = "向运行控制台输出一条信息。"),
                lua("警告日志", "Log.warn", "\"message\"", detail = "向运行控制台输出一条警告。"),
                lua("错误日志", "Log.error", "\"message\"", detail = "向运行控制台输出一条错误。"),
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
                lua("两点距离", "Math.distance", "0", "0", "100", "100"),
                lua("屏幕尺寸", "System.getScreenSize", detail = "返回当前屏幕宽高。"),
                lua("单调运行时间", "System.elapsedRealtimeMillis", detail = "返回设备启动后的单调毫秒数，适合计算循环耗时。"),
            ),
        ),
    )
}

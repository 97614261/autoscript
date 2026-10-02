package com.autoscript.studio

import com.autoscript.studio.generated.BlockCategory
import com.autoscript.studio.generated.GeneratedFunctionDocumentation
import java.util.Locale

internal data class FunctionParameterInfo(
    val name: String,
    val type: String,
    val required: Boolean,
    val description: String,
)

/** 函数库里的一项：可视化项目指向一个积木 kind，Lua 项目是一段真实存在的脚本 API 调用模板。 */
internal data class FunctionLibraryEntry(
    val title: String,
    val detail: String,
    val snippet: String,
    val blockKind: String?,
    val requiredCapabilities: Set<String>,
    val parameters: List<String>,
    val parameterInfo: List<FunctionParameterInfo> = emptyList(),
    val result: String = "",
    val notes: String = "",
    val keywords: List<String> = emptyList(),
    val apiName: String? = null,
    val since: String? = null,
)

internal data class FunctionLibraryGroup(
    val label: String,
    val entries: List<FunctionLibraryEntry>,
)

/**
 * 函数库内容只来自项目真实注册的能力：可视化项目用 `schema/block-catalog` 生成的积木目录，
 * Lua 项目用 `schema/api-schema/functions` 里的真实脚本 API。不再出现未实现的片段。
 */
internal object FunctionCatalog {
    /** 可视化项目的函数库把积木 kind 编成一行提示；宿主用 [blockKindOf] 还原后直接查目录，不做模糊搜索。 */
    const val BLOCK_HINT_PREFIX = "--@autoscript-block:"
    /** 循环面板的参数不能安全地表示成 Lua；这里用仅限编辑器内部的结构化提示传递。 */
    const val LOOP_HINT_PREFIX = "--@autoscript-loop:"

    fun blockKindOf(snippet: String): String? {
        val line = snippet.lineSequence().firstOrNull()?.trim().orEmpty()
        if (line.startsWith(LOOP_CONFIG_HINT) || line.startsWith(LOOP_HINT_PREFIX + "metric:")) {
            return loopInsertionFromHint(snippet)?.kind
        }
        return when {
            line.startsWith(VARIABLE_CALCULATION_HINT) -> "variable.calculate"
            line.startsWith(BLOCK_HINT_PREFIX) -> line.removePrefix(BLOCK_HINT_PREFIX)
                .trim().takeIf(String::isNotEmpty)
            line.startsWith(LOOP_HINT_PREFIX + "repeat:") -> "control.repeat"
            line.startsWith(LOOP_HINT_PREFIX + "forever") ||
                line.startsWith(LOOP_HINT_PREFIX + "timed:") -> "control.while"
            else -> null
        }
    }

    fun visualGroups(): List<FunctionLibraryGroup> = BlockCatalog.all
        .groupBy { block ->
            when {
                block.kind.startsWith("ui.") -> "界面"
                block.kind.startsWith("input.") -> "按键"
                block.kind == "control.if" -> "判断"
                block.kind in setOf("control.repeat", "control.while", "control.loopmetric", "control.loopcheck") -> "循环"
                block.kind.startsWith("control.") -> "跳转"
                block.kind in setOf("task.log", "task.prompt", "task.runprompt", "task.comment") -> "调试"
                else -> categoryLabel(block.category)
            }
        }
        .toSortedMap(compareBy { GROUP_ORDER.indexOf(it).takeIf { index -> index >= 0 } ?: Int.MAX_VALUE })
        .map { (label, blocks) ->
            FunctionLibraryGroup(
                label = label,
                entries = blocks.sortedBy { it.title }.map { block ->
                    FunctionLibraryEntry(
                        title = block.title,
                        detail = block.summary,
                        snippet = "$BLOCK_HINT_PREFIX${block.kind}\n",
                        blockKind = block.kind,
                        requiredCapabilities = block.requiredCapabilities,
                        parameters = block.properties.map { it.label },
                        parameterInfo = block.properties.map { property ->
                            FunctionParameterInfo(
                                property.label,
                                if (property.editor.name == "STRING" && property.label.contains("变量")) "变量名" else functionValueType(property.editor.name),
                                property.required,
                                buildList {
                                    add(property.path)
                                    property.defaultValue?.let { add("默认：${libraryChoiceLabel(property.path, it)}") }
                                    if (property.choices.isNotEmpty()) add("可选：${property.choices.joinToString(" / ") { libraryChoiceLabel(property.path, it) }}")
                                    property.resourceKind?.let { add("从项目已声明资源中选择") }
                                    property.dependsOn?.let { add("关联参数：$it") }
                                }.joinToString(" · "),
                            )
                        },
                        result = block.properties.filter {
                            it.path in OUTPUT_VARIABLE_PATHS ||
                                (it.path == "name" && (block.kind.startsWith("variable.") || block.kind == "control.loopmetric"))
                        }.joinToString("、") { it.label }.let { output ->
                            if (output.isEmpty()) block.summary else "写入变量：$output"
                        },
                        notes = buildList {
                            if (block.childBlocks.isNotEmpty()) add("包含子积木区：${block.childBlocks.joinToString(" / ") { libraryChildBlockLabel(it) }}。加入后可在子区继续编排。")
                            if (block.properties.any { it.editor.name == "FLOW_REFERENCE" }) add("目标插件需先创建；调用参数与目标插件的参数声明对应。")
                            if (block.properties.any { it.resourceKind != null }) add("图片或字库需先加入项目资源；不能使用任意外部路径。")
                            if (block.kind in setOf("control.break", "control.loopmetric", "control.loopcheck")) add("只能在循环体内使用，作用于最近一层循环。")
                            if (block.kind in setOf("control.goto", "control.label")) add("标记与跳转位于当前插件根层级，不能跨越插件。")
                            if (block.kind.startsWith("input.pointer")) add("单指触点需由同一任务按下、移动、弹起；依赖 Root 单指输入能力。")
                            if (block.kind == "ui.get") add("读取用户在已设计页面填写的参数；按控件 ID 定位，不受当前页限制。结果是字符串：复选框为 true/false，整数可用计算积木 int/float 转换。启动配置 RunnerConfig 与运行中的当前值不同；此积木读当前值。")
                            if (block.kind.startsWith("vision.") || block.kind.startsWith("ocr.")) add("帧变量保存不可变图像句柄；先截图缓存，再在该帧上识别。请按对应配置面板的坐标说明设置区域，右/下边界不包含。")
                            if (block.properties.any { it.path == "autoCapture" }) add("启用自动截图后，无需手动准备帧变量；临时截图使用后自动释放。")
                        }.joinToString("\n"),
                        keywords = block.searchTerms,
                    )
                },
            )
        }

    fun luaGroups(): List<FunctionLibraryGroup> = LUA_GROUPS

    fun search(groups: List<FunctionLibraryGroup>, query: String, group: String? = null): List<Pair<String, FunctionLibraryEntry>> {
        val terms = query.trim().lowercase(Locale.ROOT).split(Regex("\\s+")).filter(String::isNotEmpty)
        val needle = query.trim().lowercase(Locale.ROOT)
        return groups.filter { group == null || it.label == group }.flatMap { candidate ->
            candidate.entries.filter { entry ->
                val text = listOf(candidate.label, entry.title, entry.detail, entry.apiName.orEmpty(), entry.blockKind.orEmpty(),
                    entry.parameters.joinToString(" "), entry.parameterInfo.joinToString(" ") { it.description },
                    entry.keywords.joinToString(" "), entry.notes, entry.result, entry.snippet).joinToString(" ").lowercase(Locale.ROOT)
                terms.all(text::contains)
            }.map { candidate.label to it }
        }.sortedBy { (_, entry) ->
            if (needle.isNotEmpty() && listOf(entry.title, entry.apiName, entry.blockKind).any { it?.lowercase(Locale.ROOT) == needle }) 0 else 1
        }
    }

    private val GROUP_ORDER = listOf("插件", "变量", "按键", "屏幕", "图像", "文字", "判断", "循环", "跳转", "界面", "任务", "调试", "其它", "旧版兼容")
    private val OUTPUT_VARIABLE_PATHS = setOf("resultVariable", "foundVariable", "xVariable", "yVariable", "countVariable",
        "textVariable", "coverageVariable", "scoreVariable", "templateVariable", "imageVariable", "targetVariable",
        "indexVariable", "iterationVariable", "elapsedVariable")

    fun categoryLabel(category: BlockCategory): String = when (category) {
        BlockCategory.FLOW -> "插件"
        BlockCategory.TASK -> "任务"
        BlockCategory.CONTROL -> "控制"
        BlockCategory.VARIABLE -> "变量"
        BlockCategory.SCREEN -> "屏幕"
        BlockCategory.VISION -> "图像"
        BlockCategory.OCR -> "文字"
    }

    private fun template(title: String, snippet: String, detail: String): FunctionLibraryEntry =
        FunctionLibraryEntry(
            title = title,
            detail = detail,
            snippet = snippet.trimIndent().trimEnd() + "\n",
            blockKind = null,
            requiredCapabilities = emptySet(),
            parameters = emptyList(),
        )

    private val LUA_TEMPLATES = listOf(
        FunctionLibraryGroup(
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
        FunctionLibraryGroup(
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
    )

    private val titles = mapOf(
        "UI.waitEvent" to "等待界面事件", "UI.getValue" to "读取页面参数", "UI.setValue" to "设置控件值", "UI.command" to "界面操作",
        "Task.sleep" to "等待", "Task.spawn" to "启动子任务", "Task.cancel" to "取消子任务",
        "Timer.every" to "启动定时器", "Timer.cancel" to "取消定时器",
        "Input.tap" to "点击", "Input.swipe" to "滑动", "Input.keyEvent" to "设备按键",
        "Input.pointerDown" to "按下", "Input.pointerMove" to "移动", "Input.pointerUp" to "弹起",
        "Input.tapScreen" to "屏幕像素点击", "Input.pointerDownScreen" to "屏幕像素按下",
        "Screen.capture" to "截图", "Screen.cache" to "缓存帧", "Screen.release" to "释放帧",
        "Screen.captureSeries" to "连续截图", "Screen.loadImage" to "载入图片", "Screen.crop" to "裁剪帧",
        "Screen.getColor" to "取点颜色", "Screen.compareColor" to "单点比色", "Screen.findColor" to "区域找色",
        "Screen.findAllColor" to "全部找色", "Screen.countColor" to "颜色计数", "Screen.findMultiColor" to "多点找色",
        "Screen.findImage" to "区域找图", "Screen.findImages" to "多模板找图", "Screen.findGray" to "灰度找图",
        "Ocr.loadDictionary" to "载入字库", "Ocr.releaseDictionary" to "释放字库", "Ocr.glyph" to "字库识字",
        "Ocr.alphanumeric" to "字母数字识别", "Prompt.show" to "运行提示", "Prompt.toast" to "弹出提示",
        "Log.write" to "写入日志", "Legacy.duoDianZhaoSe" to "旧版多点找色", "Legacy.duoDianBiSe" to "旧版多点比色",
        "Legacy.getRectColorNum" to "旧版区域颜色数", "Legacy.getRgbColor" to "RGB 转颜色值",
        "Math.distance" to "两点距离", "System.getScreenSize" to "屏幕尺寸",
        "System.elapsedRealtimeMillis" to "单调运行时间",
    )

    private fun luaGroup(name: String): String = when (name.substringBefore('.')) {
        "Input" -> "按键"
        "Task", "Timer" -> "任务"
        "Screen" -> if (name in setOf("Screen.capture", "Screen.cache", "Screen.release", "Screen.captureSeries", "Screen.loadImage", "Screen.crop")) "屏幕" else "图像"
        "Ocr" -> "文字"
        "Prompt", "Log" -> "调试"
        "UI" -> "界面"
        "Legacy" -> "旧版兼容"
        else -> "其它"
    }

    private fun luaDefault(api: String, name: String): String = when (name) {
        "id" -> if (api.startsWith("UI.")) "\"control1\"" else name
        "operation" -> "\"visible\""
        "value" -> if (api.startsWith("UI.")) "\"true\"" else name
        "x", "y", "x1", "y1", "left", "top", "direction", "tolerance", "anchorTolerance", "level" -> "0"
        "x2", "y2", "right", "bottom", "width", "height" -> "100"
        "durationMs" -> if (api == "Input.swipe") "300" else "1000"
        "milliseconds" -> "1000"
        "keyCode" -> "4"
        "message" -> "\"message\""
        "rgb", "anchorRgb", "foregroundRgb" -> "0xFFFFFF"
        "red", "green", "blue" -> "255"
        "similarityPermille" -> "900"
        "minimumMatchPercent" -> "90"
        "limit" -> "10"
        "maxFrames", "spaceGapColumns" -> "1"
        "targetFps" -> "10"
        "allowPartial" -> "false"
        "path" -> if (api == "Ocr.loadDictionary") "\"dictionaries/example.asglyph\"" else "\"assets/images/template.png\""
        "pathsJson" -> "'[\"assets/images/template.png\"]'"
        "samples" -> "{{x = 10, y = 0, rgb = 0xFFFFFF, tolerance = 0}}"
        "callback" -> "function()\n    -- 在这里添加任务内容\n    Task.sleep(100)\nend"
        else -> name // Explicit prerequisites are explained next to the example, never disguised as literals.
    }

    private val LUA_GROUPS: List<FunctionLibraryGroup> by lazy {
        val apiEntries = GeneratedFunctionDocumentation.all.map { doc ->
            val args = doc.parameters.filter { it.required }.joinToString(", ") { parameter ->
                luaDefault(doc.name, parameter.name)
            }
            val assignment = if (doc.returnType == "void") "" else "local ${when (doc.name) {
                "Screen.capture" -> "captureId"
                "Screen.cache" -> "frame"
                "Screen.crop" -> "cropped"
                "Screen.loadImage" -> "template"
                "Ocr.loadDictionary" -> "dictionary"
                "Task.spawn" -> "task"
                "Timer.every" -> "timer"
                "System.getScreenSize" -> "size"
                else -> "result"
            }} = "
            val prerequisites = doc.parameters.filter { it.required && luaDefault(doc.name, it.name) == it.name }.map { it.name }
            val notes = buildList {
                if (prerequisites.isNotEmpty()) add("示例需已有变量：${prerequisites.joinToString("、")}。先采集并缓存帧；模板/字库先加载，任务/定时器 ID 先创建。")
                if (doc.parameters.any { it.name in setOf("path", "pathsJson") }) add("示例资源路径必须替换为项目已声明的实际文件。")
                if (doc.parameters.any { it.name == "right" }) add("区域为半开矩形，右/下边界不包含；示例区域 0,0,100,100 需按实际图像修改。")
                if (doc.name.startsWith("Input.")) add("需 Root 输入服务；tap/pointerDown 使用设计坐标，带 Screen 后缀的调用使用原始屏幕像素。")
                if (doc.name in setOf("Input.pointerDown", "Input.pointerDownScreen", "Input.pointerMove", "Input.pointerUp")) add("按下、移动、弹起需同一任务持有单指触点；使用完应弹起，任务退出或租约超时也会清理。")
                if (doc.returnType == "integer" && doc.name.startsWith("Screen.") && doc.name != "Screen.getColor" && doc.name != "Screen.countColor" && doc.name != "Screen.capture") add("返回的帧句柄用 Screen.release 释放；任务结束会清理未释放租约。")
                if (doc.name == "Prompt.show") add("用户可见的运行信息，不是开发控制台日志；显示受项目“运行提示”配置控制。")
                if (doc.name == "Prompt.toast") add("到时自动消失；在调试面板配置大小、字体、颜色和位置。")
                if (doc.name.startsWith("Log.")) add("用于脚本开发控制台日志；面向脚本使用者的提示请用 Prompt.show / Prompt.toast。")
                if (doc.name == "UI.getValue") add("读取页面参数（读取控件值）：把 control1 换成设计器交互栏中的控件 ID，可读取任意页。返回字符串；复选框为 true/false，整数可 tonumber 转换。RunnerConfig 是启动时的配置，UI.getValue 读取运行中的当前值。不存在的 ID 返回空字符串。")
            }.joinToString("\n")
            luaGroup(doc.name) to FunctionLibraryEntry(
                title = titles[doc.name] ?: doc.name,
                detail = doc.summary,
                snippet = "$assignment${doc.name}($args)\n",
                blockKind = null,
                requiredCapabilities = setOf(doc.capability),
                parameters = doc.parameters.map { it.name },
                parameterInfo = doc.parameters.map { FunctionParameterInfo(it.name, functionValueType(it.type), it.required, it.summary) },
                result = "${functionValueType(doc.returnType)}${if (doc.returnNullable) " / nil" else ""} · ${doc.returnSummary}",
                notes = notes,
                apiName = doc.name,
                since = doc.since,
            )
        }
        val log = apiEntries.first { it.second.apiName == "Log.write" }.second
        val wrappers = listOf("info" to "信息日志", "warn" to "警告日志", "error" to "错误日志").map { (name, title) ->
            "调试" to log.copy(title = title, apiName = "Log.$name", snippet = "Log.$name(\"message\")\n",
                detail = "向运行控制台输出${title.removeSuffix("日志")}；Log.write 的便捷封装。",
                parameters = listOf("message"), parameterInfo = log.parameterInfo.filter { it.name == "message" })
        }
        (apiEntries + wrappers + LUA_TEMPLATES.flatMap { group -> group.entries.map { group.label to it } })
            .groupBy({ it.first }, { it.second })
            .toSortedMap(compareBy { GROUP_ORDER.indexOf(it) })
            .map { (label, entries) -> FunctionLibraryGroup(label, entries) }
    }
}

internal fun functionValueType(type: String): String = when (type.lowercase(Locale.ROOT)) {
    "integer", "integer_enum" -> "整数"
    "number" -> "数值"
    "scalar" -> "标量值(JSON)"
    "boolean" -> "布尔"
    "string", "enum" -> "文本"
    "void" -> "无返回值"
    "function" -> "回调函数"
    "point" -> "坐标"
    "rect" -> "区域"
    "color" -> "颜色"
    "resource" -> "资源"
    "flow_reference" -> "插件"
    "flow_arguments" -> "调用参数"
    else -> type
}

private fun libraryChildBlockLabel(name: String): String = when (name) {
    "body" -> "主体"
    "then" -> "条件成立"
    "else" -> "否则"
    else -> name
}

private fun libraryChoiceLabel(path: String, value: String): String = when (path) {
    "operator" -> mapOf("equals" to "等于", "notEquals" to "不等于", "lessThan" to "小于", "lessOrEqual" to "小于等于",
        "greaterThan" to "大于", "greaterOrEqual" to "大于等于")[value] ?: value
    "unit", "durationUnit" -> mapOf("milliseconds" to "毫秒", "seconds" to "秒", "minutes" to "分钟")[value] ?: value
    "metric" -> mapOf("count" to "次数", "elapsed" to "耗时")[value] ?: value
    "successAction" -> mapOf("none" to "不执行动作", "tap" to "点击", "hold" to "长按", "tapWait" to "点击后等待", "pressRelease" to "按下后弹起")[value] ?: value
    else -> value
}

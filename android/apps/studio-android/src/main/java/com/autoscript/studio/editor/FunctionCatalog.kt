package com.autoscript.studio

import com.autoscript.studio.generated.BlockCategory
import java.util.Locale

internal data class FunctionParameterInfo(
    val name: String,
    val type: String,
    val required: Boolean,
    val description: String,
)

/** Visual blocks are insertable; generated API references are read-only. */
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
    val section: String = "",
) {
    val canInsert: Boolean get() = blockKind != null
}

internal data class FunctionLibraryGroup(
    val label: String,
    val entries: List<FunctionLibraryEntry>,
)

/** One catalogue for all visual editor entry points. */
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
            line.startsWith(CONDITION_CONFIG_HINT) -> configuredConditionInsertion(snippet)
                ?.takeUnless { it.elseIf }?.let { "control.if" }
            line.startsWith(VARIABLE_CALCULATION_HINT) -> "variable.calculate"
            line.startsWith(BLOCK_HINT_PREFIX) -> line.removePrefix(BLOCK_HINT_PREFIX)
                .trim().takeIf(String::isNotEmpty)
            line.startsWith(LOOP_HINT_PREFIX + "repeat:") -> "control.repeat"
            line.startsWith(LOOP_HINT_PREFIX + "forever") ||
                line.startsWith(LOOP_HINT_PREFIX + "timed:") -> "control.while"
            else -> null
        }
    }

    fun visualGroups(): List<FunctionLibraryGroup> = BlockCatalog.insertable
        .groupBy { functionGroup(it.kind, it.category) }
        .toSortedMap(compareBy<String> { FUNCTION_GROUP_ORDER.indexOf(it) }.thenBy { it })
        .map { (label, blocks) ->
            FunctionLibraryGroup(
                label = label,
                entries = blocks.sortedWith(compareBy({ functionSectionOrder(it.kind) }, { it.title }, { it.kind })).map { block ->
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
                            if (block.kind in textRuleKinds) {
                                add(textRegexResultHint(block.kind))
                                add("任意文本或文本变量均可作为来源。支持常用规则、自定义正则或变量规则；正则按原样填写，不需额外转义。不支持环视和反向引用；提取返回完整匹配，不单独返回捕获组。")
                            }
                            if (block.childBlocks.isNotEmpty()) add("包含子积木区：${block.childBlocks.joinToString(" / ") { libraryChildBlockLabel(it) }}。加入后可在子区继续编排。")
                            if (block.kind == "control.cycle") add("每轮让步1～1000毫秒，默认1毫秒；无次数上限，但停止、会话超时和资源保护仍生效。循环指标和continue作用于最近循环；限时在让步后再次检查。")
                            if (block.kind == "control.repeat") add("序号输出从1开始，耗时输出为毫秒。JSON列表遍历可用集合长度写入整数变量，再以该变量限次循环；序号输出转text(index)后作为读取JSON子结构的索引。集合仍为字符变量，不创建隐藏变量，也不自动执行。")
                            if (block.kind == "screen.loadimage") add("只加载项目已声明的图片，不截图、不识别。结果为当前任务的图像句柄，使用完请释放；不要把加载模板和找图误作同一操作。")
                            if (block.kind == "control.if") add("单比较或开启复合条件（与/或/非）。最多64项、8层、8192字节；与/或短路，主条件只求值一次。否则如果同样支持复合条件；只执行第一个成立分支，均不成立才执行否则。旧v1单比较无损升级；复合条件要求runtimeApi≥1.7。")
                            if (block.kind == "task.message") add("调试→运行输出→表达式。示例：\"进度：\" .. text(count)。仅解析计算白名单，结果须为字符；1～1024字节，最多128项、16层，结果不超过2048字节；未知变量和函数会阻断编译。弹出提示按项目样式自动消失，运行提示与开发日志分开。")
                            if (block.kind in setOf("flow.call.branch", "flow.status.return")) add("运行状态与返回参数独立。自然结束/返回上层在智构视为成功；显式返回失败才进入失败区。异常和取消中止，不执行失败分支；不代表旧工程默认状态已经等价。")
                            if (block.properties.any { it.editor.name == "FLOW_REFERENCE" }) add("目标插件需先创建；调用参数与目标插件的参数声明对应。")
                            if (block.properties.any { it.resourceKind != null }) add("资源需先加入项目声明；不能使用任意外部路径。")
                            if (block.kind in setOf("control.break", "control.continue", "control.loopmetric", "control.loopcheck")) add("只能在循环体内使用，作用于最近一层循环。")
                            if (block.kind in setOf("control.goto", "control.label")) add("标记与跳转位于当前插件根层级，不能跨越插件。")
                            if (block.kind.startsWith("input.pointer")) add("单指触点需由同一任务按下、移动、弹起；依赖 Root 单指输入能力。")
                            if (block.kind == "ui.get") add("读取用户在已设计页面填写的参数；按控件 ID 定位，不受当前页限制。结果是字符串：复选框为 true/false，整数可用计算积木 int/float 转换。启动配置 RunnerConfig 与运行中的当前值不同；此积木读当前值。")
                            if (block.kind == "ui.command") add("列表支持逐项增删改与清空，插入/修改分开填索引和文字；单选索引1起、非必选0取消，多选为JSON整数数组。get:itemCount/selectedIndex/selectedIndices及普通读取参数获取新状态。get:读取写入字符变量；数字/布尔可用计算转换。尺寸为设计像素，颜色#AARRGGBB；不改设计稿。")
                            if (block.kind == "ui.command") add("占位hint用于输入框，minimum/maximum为整数边界，step仅整数滑块；range一次设置上下界/步长，当前值不兼容时拒绝，不自动截断。window/get:config导出参数到字符变量，可接文件写入；window/restoreConfig从字符变量恢复，全部校验后应用，密码不包含，最多8192字节，不触发事件。文件保存另需storage.data。")
                            if (block.kind == "ui.command") add("backgroundImage设置声明图片，空值清除；窗口/界面浮球位置为屏幕像素JSON{x,y}，不会自动显示，读取返回x/y/width/height/visible。整组可见、启用、默认值恢复包含容器和后代，恢复先全部校验，不触发事件，不影响暂停/停止控制球。")
                            if (block.kind == "ui.confirm") add("结果写入字符变量：confirm确定、cancel取消、dismiss返回/窗外关闭、timeout到期。用判断积木比较confirm后继续；停止脚本立即关闭并取消等待。")
                            if (block.kind == "ui.dialog") add("复用已保存设计页面；布局按钮绑定close时只关对话框。填写值保留，用读取页面参数获取。结束恢复原主界面状态；停止时不恢复。")
                            if (block.kind.startsWith("data.")) add("集合/对象保存在字符变量中的JSON文本；索引从1开始。最多64KiB、16层、1024项；写操作返回新JSON，需要接收后才更新变量。标量字符串用解码JSON字符串读取。")
                            if (block.kind.startsWith("files.")) add("仅访问当前项目独立的私有data目录，不是插件或图片目录。需明确启用storage.data能力。文本单文件最多64KiB；二进制、Base64和ZIP采用各自契约限额（见用途说明），不能把64KiB套用到所有文件。写入创建父目录，删除进入私有回收区；不能访问绝对路径或上级目录。")
                            if (block.kind == "variable.calculate") add("可在同一计算面板选择字符/字节处理与整数运算。len/sub/find 按字符、起点1、未找到0；byteLen(字符)返回UTF-8字节数，byteFind(字符,查找串,起点)起点0、未找到−1；byteSub(字符,起点,长度)起点0、末尾截短，切断中文编码会报错。idiv(整,整)向零整除，irem(整,整)余数与被除数同号；除零与64位溢出报错。字符串最多4096字节。例如 byteLen(\"中文\")=6、idiv(-7,3)=-2、irem(-7,3)=-1。这些是计算表达式函数，不是独立Lua全局API。")
                            if ((block.kind.startsWith("vision.") && block.kind != "vision.result") || block.kind.startsWith("ocr.")) add("帧变量保存不可变图像句柄；先截图缓存，再在该帧上识别。请按对应配置面板的坐标说明设置区域，右/下边界不包含。")
                            if (block.kind == "vision.findallimages") add("保留1–64个结果，包括重叠；按模板选择顺序及现代搜索方向排序。结果存入字符变量，成功/失败区互斥。上限是保留数，不是找到总数；读取truncated判断是否还有结果。像素预算跨模板共享，超预算/取消/超时中止，不当作未找到。")
                            if (block.kind == "vision.findallimages") add("找到后动作默认为首项；全结果模式只对保留项执行。按下不弹起禁止全结果模式。按下后定时弹起/点击停顿1–5000ms，识别前后等待0–60000ms且可取消。动作发生前已释放临时帧；输入需input.basic，等待需core.task；异常/停止不进入结果分支。")
                            if (block.kind == "vision.result") add("读取多图结果字符变量：count/truncated用序号0；具体匹配用序号1～count。path接收字符变量；x/y/width/height/scorePermille/count/truncated接收整数变量。truncated为1/0。字段白名单，不是任意JSON执行。")
                            if (block.properties.any { it.path == "autoCapture" }) add("启用自动截图后，无需手动准备帧变量；临时截图使用后自动释放。")
                        }.joinToString("\n"),
                        keywords = block.searchTerms + FunctionApiReference.aliasesForKind(block.kind),
                        section = functionSection(block.kind),
                    )
                },
            )
        }

    fun apiGroups(): List<FunctionLibraryGroup> = FunctionApiReference.groups

    fun search(groups: List<FunctionLibraryGroup>, query: String, group: String? = null): List<Pair<String, FunctionLibraryEntry>> {
        val terms = query.trim().lowercase(Locale.ROOT).split(Regex("\\s+")).filter(String::isNotEmpty)
        val needle = query.trim().lowercase(Locale.ROOT)
        return groups.filter { group == null || it.label == group }.flatMap { candidate ->
            candidate.entries.filter { entry ->
                val text = listOf(candidate.label, entry.title, entry.detail, entry.apiName.orEmpty(), entry.blockKind.orEmpty(),
                    entry.parameters.joinToString(" "), entry.parameterInfo.joinToString(" ") { it.description },
                    entry.keywords.joinToString(" "), entry.section, entry.notes, entry.result, entry.snippet).joinToString(" ").lowercase(Locale.ROOT)
                terms.all(text::contains)
            }.map { candidate.label to it }
        }.sortedBy { (_, entry) ->
            if (needle.isNotEmpty() && (listOf(entry.title, entry.apiName, entry.blockKind) + entry.keywords)
                    .any { it?.lowercase(Locale.ROOT) == needle }) 0 else 1
        }
    }

    private val OUTPUT_VARIABLE_PATHS = setOf("resultVariable", "foundVariable", "xVariable", "yVariable", "countVariable",
        "textVariable", "coverageVariable", "scoreVariable", "templateVariable", "imageVariable", "targetVariable",
        "indexVariable", "iterationVariable", "elapsedVariable", "widthVariable", "heightVariable")

    fun categoryLabel(category: BlockCategory): String = when (category) {
        BlockCategory.FLOW -> "插件"
        BlockCategory.TASK -> "任务"
        BlockCategory.CONTROL -> "控制"
        BlockCategory.VARIABLE -> "变量"
        BlockCategory.SCREEN -> "屏幕"
        BlockCategory.VISION -> "图像"
        BlockCategory.OCR -> "文字"
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
    "resource_list" -> "模板列表"
    "flow_reference" -> "插件"
    "flow_arguments" -> "调用参数"
    else -> type
}

private fun libraryChildBlockLabel(name: String): String = when (name) {
    "body" -> "主体"
    "then" -> "条件成立"
    "else" -> "否则"
    "success" -> "运行成功"
    "failure" -> "运行失败"
    else -> name
}

private fun libraryChoiceLabel(path: String, value: String): String = when (path) {
    "operator" -> mapOf("equals" to "等于", "notEquals" to "不等于", "lessThan" to "小于", "lessOrEqual" to "小于等于",
        "greaterThan" to "大于", "greaterOrEqual" to "大于等于")[value] ?: value
    "unit", "durationUnit" -> mapOf("milliseconds" to "毫秒", "seconds" to "秒", "minutes" to "分钟")[value] ?: value
    "metric" -> mapOf("count" to "次数", "elapsed" to "耗时")[value] ?: value
    "successAction" -> mapOf("none" to "不执行动作", "tap" to "点击", "hold" to "按下不弹起", "tapWait" to "点击后等待", "pressRelease" to "按下后定时弹起")[value] ?: value
    else -> value
}

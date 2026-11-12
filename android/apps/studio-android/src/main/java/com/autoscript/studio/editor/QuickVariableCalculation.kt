package com.autoscript.studio

import com.autoscript.project.store.ProjectNames
import com.autoscript.project.store.ProjectVariableType
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive

internal enum class CalculationInputKind { SCALAR, NUMERIC, INTEGER, TEXT }
internal data class QuickCalculationInput(val label: String, val kind: CalculationInputKind, val initial: String = "")
internal data class QuickCalculationOperation(val id: String, val label: String, val group: String,
    val inputs: List<QuickCalculationInput>, val output: ProjectVariableType? = null, val hint: String = "")
internal data class CalculationOperand(val literal: String = "", val variable: String = "",
    val type: ProjectVariableType = ProjectVariableType.STRING)
internal data class QuickCalculationState(val operation: String = "assign", val operands: List<CalculationOperand> = listOf(CalculationOperand("0", type = ProjectVariableType.INTEGER)),
    val trim: Boolean = false, val skipEmpty: Boolean = false, val fallback: CalculationOperand? = null)
internal data class QuickCalculationResult(val expression: String? = null, val type: ProjectVariableType? = null, val error: String? = null)

internal val quickCalculationOperations: List<QuickCalculationOperation> = buildList {
    val scalar = QuickCalculationInput("来源", CalculationInputKind.SCALAR, "0")
    val number = QuickCalculationInput("数值", CalculationInputKind.NUMERIC, "0")
    val text = QuickCalculationInput("文本", CalculationInputKind.TEXT)
    fun operation(id: String, label: String, group: String, inputs: List<QuickCalculationInput>, output: ProjectVariableType? = null, hint: String = "") {
        add(QuickCalculationOperation(id, label, group, inputs, output, hint))
    }
    operation("assign", "直接赋值", "赋值 / 转换", listOf(scalar))
    operation("int", "转整数", "赋值 / 转换", listOf(scalar), ProjectVariableType.INTEGER, "支持 +190、首尾空白；小数向零取整。")
    operation("float", "转小数", "赋值 / 转换", listOf(scalar), ProjectVariableType.NUMBER)
    operation("text", "转文本", "赋值 / 转换", listOf(scalar), ProjectVariableType.STRING)
    listOf("+" to "相加", "-" to "相减", "*" to "相乘", "/" to "相除", "//" to "向下整除", "%" to "向下整除余数",
        "idiv" to "整数除法", "irem" to "整数余数", "min" to "取较小值", "max" to "取较大值").forEach { (id, label) ->
        val kind = if (id in setOf("idiv", "irem")) CalculationInputKind.INTEGER else CalculationInputKind.NUMERIC
        operation(id, label, if (id in setOf("//", "%", "idiv", "irem")) "取整 / 余数" else "四则 / 大小", listOf(QuickCalculationInput("数值一", kind, "0"), QuickCalculationInput("数值二", kind, "1")),
            if (id == "/") ProjectVariableType.NUMBER else null,
            when (id) { "idiv", "irem" -> "整数除法向零取整，余数与被除数同号；除数不能为0。"
                "//", "%" -> "向下整除；余数与除数同号。除数不能为0。"; "/" -> "结果为小数；除数不能为0。"; else -> "" })
    }
    listOf("abs" to "绝对值", "floor" to "向下取整", "ceil" to "向上取整", "round" to "四舍五入").forEach { (id, label) ->
        operation(id, label, "取整 / 余数", listOf(number), if (id == "abs") null else ProjectVariableType.INTEGER,
            if (id == "round") "取最近整数，正负数遇到一半均远离0。" else "")
    }
    operation("..", "拼接文本", "文本处理", listOf(text.copy(label = "文本一"), text.copy(label = "文本二")), ProjectVariableType.STRING)
    listOf("trim" to "去首尾空白", "lower" to "英文转小写", "upper" to "英文转大写", "len" to "文本字数").forEach { (id, label) ->
        operation(id, label, "文本处理", listOf(text), if (id == "len") ProjectVariableType.INTEGER else ProjectVariableType.STRING)
    }
    operation("sub", "截取文本", "文本处理", listOf(text, QuickCalculationInput("起点", CalculationInputKind.INTEGER, "1"),
        QuickCalculationInput("字数", CalculationInputKind.INTEGER, "1")), ProjectVariableType.STRING, "按完整字符截取，起点从1开始。")
    operation("find", "查找文本位置", "文本处理", listOf(text, text.copy(label = "查找")), ProjectVariableType.INTEGER, "位置从1开始，没找到返回0。")
    operation("replace", "替换全部文本", "文本处理", listOf(text, text.copy(label = "查找"), text.copy(label = "替换为")), ProjectVariableType.STRING)
    operation("split", "按分隔符拆分", "拆分文本", listOf(text, text.copy(label = "分隔符", initial = ",")), ProjectVariableType.STRING,
        "支持多字分隔符；空分隔符按完整字符拆分。结果为列表文本。")
    operation("splitAny", "按多个分隔字符拆分", "拆分文本", listOf(text, text.copy(label = "分隔字符", initial = ",，;； \n\t")), ProjectVariableType.STRING,
        "填写的每个字符都作为分隔符；多字整体分隔请选上一种。")
    operation("listLen", "列表项数 / 对象字段数", "列表 / 对象", listOf(text.copy(label = "列表/对象", initial = "[]")), ProjectVariableType.INTEGER)
    listOf("itemText" to ("文本" to ProjectVariableType.STRING), "itemInt" to ("整数" to ProjectVariableType.INTEGER),
        "itemFloat" to ("小数" to ProjectVariableType.NUMBER), "itemJson" to ("JSON子项" to ProjectVariableType.STRING)).forEach { (id, info) ->
        operation(id, "取一项 → ${info.first}", "列表 / 对象", listOf(text.copy(label = "列表/对象", initial = "[]"),
            QuickCalculationInput("序号/字段", CalculationInputKind.SCALAR, "1")), info.second,
            "列表用整数序号（从1开始），对象用文本字段名；子列表可继续取值。")
    }
}

internal fun calculationInputAccepts(kind: CalculationInputKind, type: ProjectVariableType): Boolean = when (kind) {
    CalculationInputKind.SCALAR -> type != ProjectVariableType.IMAGE
    CalculationInputKind.NUMERIC -> type == ProjectVariableType.INTEGER || type == ProjectVariableType.NUMBER
    CalculationInputKind.INTEGER -> type == ProjectVariableType.INTEGER
    CalculationInputKind.TEXT -> type == ProjectVariableType.STRING
}

internal fun newQuickCalculationState(id: String, previous: QuickCalculationState? = null,
    variables: List<CalculationVariable> = emptyList()): QuickCalculationState {
    val operation = quickCalculationOperations.single { it.id == id }
    return QuickCalculationState(id, operation.inputs.mapIndexed { index, input ->
        previous?.operands?.getOrNull(index)?.takeIf { operand ->
            val type = if (operand.variable.isEmpty() || operand.variable.startsWith("=")) operand.type else variables.firstOrNull { it.name == operand.variable }?.type
            type != null && calculationInputAccepts(input.kind, type)
        } ?: CalculationOperand(input.initial, type = when (input.kind) {
            CalculationInputKind.TEXT -> ProjectVariableType.STRING
            else -> ProjectVariableType.INTEGER
        })
    }, trim = previous?.trim ?: false, skipEmpty = previous?.skipEmpty ?: false)
}

internal fun quickCalculationResult(state: QuickCalculationState, variables: List<CalculationVariable>): QuickCalculationResult {
    val operation = quickCalculationOperations.firstOrNull { it.id == state.operation }
        ?: return QuickCalculationResult(error = "请选择计算操作")
    val types = mutableListOf<ProjectVariableType>()
    fun operand(value: CalculationOperand, kind: CalculationInputKind, label: String): String {
        if (value.variable.startsWith("=")) {
            require(calculationInputAccepts(kind, value.type)) { "${label}表达式类型不匹配，请重新选择或转换" }
            scalarSourceError(value.variable, variables, scalarValueTypes)?.let { error(it) }
            types += value.type
            return "(${value.variable.drop(1)})"
        }
        val variable = variables.firstOrNull { it.name == value.variable }
        val type = if (value.variable.isEmpty()) value.type else requireNotNull(variable) { "${label}变量不存在或不在当前插件作用域" }.type
        require(calculationInputAccepts(kind, type)) { "${label}类型不匹配，请重新选择" }
        types += type
        if (value.variable.isNotEmpty()) {
            require(value.variable.matches(ProjectNames.pattern)) { "变量名无效" }
            return value.variable
        }
        return when (type) {
            ProjectVariableType.STRING -> JsonPrimitive(value.literal).toString()
            ProjectVariableType.INTEGER -> value.literal.trim().toLongOrNull()?.toString() ?: error("${label}请输入64位范围内的整数")
            ProjectVariableType.NUMBER -> value.literal.trim().toDoubleOrNull()?.takeIf { it.isFinite() }?.toString() ?: error("${label}请输入有限小数")
            else -> error("图像不能参与变量计算")
        }
    }
    return runCatching {
        require(state.operands.size == operation.inputs.size) { "计算参数未填写完整" }
        val values = operation.inputs.mapIndexed { i, input -> operand(state.operands[i], input.kind, input.label) }.toMutableList()
        if (state.operation in setOf("find", "replace") && state.operands[1].variable.isEmpty())
            require(state.operands[1].literal.isNotEmpty()) { "查找文本不能为空" }
        if (state.operation in setOf("/", "//", "%", "idiv", "irem") && state.operands[1].variable.isEmpty())
            require(state.operands[1].literal.toDoubleOrNull() != 0.0) { "除数不能为0" }
        if (state.operation == "sub") {
            state.operands[1].takeIf { it.variable.isEmpty() }?.let { require(it.literal.toLong() >= 1) { "截取起点从1开始" } }
            state.operands[2].takeIf { it.variable.isEmpty() }?.let { require(it.literal.toLong() in 0..4096) { "截取字数须为0至4096" } }
        }
        if (state.operation == "splitAny" && state.operands[1].variable.isEmpty())
            require(state.operands[1].literal.isNotEmpty()) { "请选择至少一个分隔字符" }
        if (state.operation.startsWith("item")) {
            require(types[1] != ProjectVariableType.NUMBER) { "序号请选择整数，字段名请选择文本" }
            state.operands[1].takeIf { it.variable.isEmpty() && it.type == ProjectVariableType.INTEGER }?.let {
                require(it.literal.toLong() >= 1) { "列表序号从1开始" }
            }
        }
        val output = operation.output ?: if (types.contains(ProjectVariableType.NUMBER)) ProjectVariableType.NUMBER else types[0]
        var function = state.operation
        if (function in setOf("split", "splitAny")) values += ((if (state.trim) 1 else 0) + (if (state.skipEmpty) 2 else 0)).toString()
        state.fallback?.let { fallback ->
            require(function in setOf("int", "float", "itemText", "itemInt", "itemFloat")) { "该操作不支持默认值" }
            val kind = when (output) { ProjectVariableType.INTEGER -> CalculationInputKind.INTEGER
                ProjectVariableType.NUMBER -> CalculationInputKind.NUMERIC; else -> CalculationInputKind.TEXT }
            values += operand(fallback, kind, "默认值")
            if (function == "int") function = "intOr"
            if (function == "float") function = "floatOr"
        }
        val expression = when (function) {
            "assign" -> values.single()
            "+", "-", "*", "/", "//", "%", ".." -> "${values[0]} $function ${values[1]}"
            else -> "$function(${values.joinToString(",")})"
        }
        require(expression.toByteArray(Charsets.UTF_8).size in 1..1024) { "计算表达式超过1024字节，请改用来源变量" }
        QuickCalculationResult(expression, output)
    }.getOrElse { QuickCalculationResult(error = it.message ?: "计算参数无效") }
}

/** Decode only forms generated by the shortcuts; preserve every other expression in the full editor. */
internal fun decodeQuickCalculation(expression: String, variables: List<CalculationVariable>): QuickCalculationState? = runCatching {
    fun value(token: String): CalculationOperand {
        val text = token.trim()
        variables.firstOrNull { it.name == text }?.let { return CalculationOperand(variable = text, type = it.type) }
        if (text.startsWith('"')) {
            val parsed = JsonParser.parseString(text)
            require(parsed.isJsonPrimitive && parsed.asJsonPrimitive.isString)
            return CalculationOperand(parsed.asString)
        }
        if (text.toLongOrNull() != null) return CalculationOperand(text, type = ProjectVariableType.INTEGER)
        require(text.toDoubleOrNull()?.isFinite() == true)
        return CalculationOperand(text, type = ProjectVariableType.NUMBER)
    }
    val source = expression.trim()
    val open = source.indexOf('(')
    val call = open > 0 && source.endsWith(')') && source.substring(0, open).all { it.isLetter() }
    if (call) {
        var name = source.substring(0, open)
        if (name == "intOr") name = "int"
        if (name == "floatOr") name = "float"
        val operation = quickCalculationOperations.single { it.id == name }
        val tokens = splitCalculationTokens(source.substring(open + 1, source.length - 1), ",")
        val operands = tokens.take(operation.inputs.size).map(::value)
        val flags = if (name in setOf("split", "splitAny")) tokens.getOrNull(2)?.trim()?.toInt() ?: 0 else 0
        require(flags in 0..3)
        val fallback = if (name !in setOf("split", "splitAny")) tokens.getOrNull(operation.inputs.size)?.let(::value) else null
        require(tokens.size == operands.size + (if (fallback != null) 1 else 0) + (if (name in setOf("split", "splitAny") && tokens.size == 3) 1 else 0))
        QuickCalculationState(name, operands, flags % 2 == 1, flags >= 2, fallback)
    } else {
        val binary = listOf(" .. ", " + ", " - ", " * ", " / ", " // ", " % ").firstNotNullOfOrNull { separator ->
            val parts = splitCalculationTokens(source, separator)
            if (parts.size == 2) QuickCalculationState(separator.trim(), parts.map(::value)) else null
        }
        binary ?: QuickCalculationState(operands = listOf(value(source)))
    }.also { require(quickCalculationResult(it, variables).error == null) }
}.getOrNull()

private fun splitCalculationTokens(source: String, separator: String): List<String> {
    val result = mutableListOf<String>()
    var quoted = false; var escaped = false; var depth = 0; var start = 0; var i = 0
    while (i < source.length) {
        val ch = source[i]
        if (quoted) { if (escaped) escaped = false else if (ch == '\\') escaped = true else if (ch == '"') quoted = false }
        else if (ch == '"') quoted = true
        else if (ch == '(') depth++
        else if (ch == ')') depth--
        else if (depth == 0 && source.startsWith(separator, i)) {
            result += source.substring(start, i); i += separator.length; start = i; continue
        }
        i++
    }
    require(!quoted && depth == 0)
    result += source.substring(start)
    return result
}

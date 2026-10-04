package com.autoscript.studio.compiler

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import java.util.UUID

data class SourceContract(val kind: String, val version: Int, val children: List<String>, val title: String)
data class SourceLocation(val line: Int, val column: Int, val endLine: Int = line)
data class VisualSourceDocument(val text: String, val nodes: Map<String, SourceLocation>)
class VisualSourceException(val location: SourceLocation, detail: String) :
    IllegalArgumentException("第 ${location.line} 行，第 ${location.column} 列：$detail")

/** A Studio-only syntax adapter. Never loads or executes Lua; the existing Flow compiler is the authority. */
class VisualLuaSource(private val contracts: List<SourceContract>) {
    private val byKind = contracts.associateBy { it.kind }
    private val byName = contracts.associateBy { name(it.kind) }

    fun export(jsonl: String, root: String, checkCancelled: () -> Unit = {}): VisualSourceDocument {
        val originals = originals(jsonl)
        val members = originals.values.groupBy { it["blockId"].asString }
        val visited = mutableSetOf<String>()
        val locations = linkedMapOf<String, SourceLocation>()
        val output = StringBuilder("--! 智构插件源码 v1 · 变量声明在变量页面维护；@node 用于保留身份\n")
        var line = 2
        fun emit(text: String) { output.append(text).append('\n'); line++ }
        fun block(blockId: String, parent: String?, depth: Int) {
            checkCancelled()
            if (depth > 32) fail(1, 1, "积木嵌套超过 32 层")
            members[blockId].orEmpty().sortedBy { it["orderKey"].asString }.forEach { node ->
                checkCancelled()
                val id = node["nodeId"].asString
                if (!visited.add(id)) fail(1, 1, "积木存在重复或循环引用")
                if (node["parentId"] != (parent?.let(::JsonPrimitive) ?: JsonNull.INSTANCE))
                    fail(1, 1, "积木父子关系无效")
                val kind = node["kind"].asString
                val contract = byKind[kind] ?: fail(1, 1, "此积木尚无源码契约：$kind")
                if (node["nodeVersion"].asInt != contract.version) fail(1, 1, "积木版本需要先迁移：$kind")
                val args = node["args"].asJsonObject.deepCopy()
                val children = node["childBlocks"]?.asJsonObject ?: JsonObject()
                val indent = "  ".repeat(depth)
                val start = line
                val identity = JsonObject().apply {
                    addProperty("id", id)
                    addProperty("disabled", node["disabled"]?.asBoolean ?: false)
                }
                emit("$indent--@node $identity")
                fun child(slot: String) {
                    val childId = children[slot]?.asString ?: fail(start, 1, "积木缺少子区：$slot")
                    block(childId, id, depth + 1)
                }
                when (kind) {
                    "control.if" -> {
                        val branches = args.remove("elseIf")?.asJsonArray ?: JsonArray()
                        if (children.keySet() != (setOf("then", "else") + (0 until branches.size()).map { "elseIf$it" }))
                            fail(start, 1, "判断分支结构无效")
                        emit("${indent}if Condition(${literal(args)}) then")
                        child("then")
                        branches.forEachIndexed { index, branch ->
                            emit("${indent}elseif Condition(${literal(branch)}) then")
                            child("elseIf$index")
                        }
                        emit("${indent}else"); child("else"); emit("${indent}end")
                    }
                    "control.repeat" -> {
                        if (children.keySet() != setOf("body")) fail(start, 1, "循环子区无效")
                        emit("${indent}for _ = 1, Repeat(${literal(args)}) do")
                        child("body"); emit("${indent}end")
                    }
                    "control.while" -> {
                        if (children.keySet() != setOf("body")) fail(start, 1, "循环子区无效")
                        emit("${indent}while Condition(${literal(args)}) do")
                        child("body"); emit("${indent}end")
                    }
                    else -> {
                        if (children.size() == 0) emit("$indent${name(kind)}(${literal(args)})")
                        else {
                            emit("$indent${name(kind)}(${literal(args)}, {")
                            children.keySet().forEach { slot ->
                                emit("$indent  [${quote(slot)}] = function()")
                                child(slot)
                                emit("$indent  end,")
                            }
                            emit("$indent})")
                        }
                    }
                }
                locations[id] = SourceLocation(start, depth * 2 + 1, line - 1)
                if (output.length > MAX_BYTES) fail(start, 1, "源码超过 1 MiB")
            }
        }
        block(root, null, 0)
        if (visited.size != originals.size) fail(1, 1, "存在未归属或不可达积木，无法无损导出")
        if (output.toString().toByteArray(Charsets.UTF_8).size > MAX_BYTES) fail(1, 1, "源码超过 1 MiB")
        return VisualSourceDocument(output.toString(), locations)
    }

    fun parse(lua: String, baseline: String, root: String, variables: Set<String>,
        checkCancelled: () -> Unit = {}): VisualSourceDocument {
        if (lua.toByteArray(Charsets.UTF_8).size > MAX_BYTES) fail(1, 1, "源码超过 1 MiB")
        val originals = originals(baseline)
        val parsed = Parser(lua, variables, checkCancelled).program()
        val seen = mutableSetOf<String>()
        val output = mutableListOf<JsonObject>()
        val locations = linkedMapOf<String, SourceLocation>()
        fun build(nodes: List<SyntaxNode>, blockId: String, parent: String?, depth: Int) {
            checkCancelled()
            if (depth > 32) fail(1, 1, "积木嵌套超过 32 层")
            // Reuse order keys when membership/order is unchanged; otherwise assign bounded canonical keys.
            val oldIds = originals.values.filter { it["blockId"].asString == blockId }
                .sortedBy { it["orderKey"].asString }.map { it["nodeId"].asString }
            val unchangedOrder = nodes.map { it.id } == oldIds
            nodes.forEachIndexed { index, syntax ->
                checkCancelled()
                val contract = byKind[syntax.kind] ?: fail(syntax.location, "未注册积木类型")
                val previous = syntax.id?.let { originals[it] ?: fail(syntax.location, "身份不属于当前插件；新积木请删除 @node 行") }
                val id = syntax.id ?: "node-${UUID.randomUUID()}"
                if (!seen.add(id)) fail(syntax.location, "重复节点身份；复制源码时请删除复制段的 @node 行")
                if (previous != null && previous["kind"].asString != syntax.kind)
                    fail(syntax.location, "更改积木类型时请删除旧 @node 身份")
                val node = previous?.deepCopy() ?: JsonObject().apply {
                    addProperty("flowSchemaVersion", 1); addProperty("nodeVersion", contract.version)
                }
                node.addProperty("nodeId", id); node.addProperty("kind", syntax.kind)
                node.addProperty("blockId", blockId)
                node.add("parentId", parent?.let(::JsonPrimitive) ?: JsonNull.INSTANCE)
                node.addProperty("depth", depth)
                if (!unchangedOrder) node.addProperty("orderKey", "s${index.toString().padStart(6, '0')}")
                val args = syntax.args.deepCopy()
                if (syntax.kind == "control.if" && !args.has("elseIf") && previous?.getAsJsonObject("args")?.has("elseIf") == true)
                    args.add("elseIf", JsonArray())
                node.add("args", args)
                if (syntax.disabled || previous?.has("disabled") == true) node.addProperty("disabled", syntax.disabled)
                else node.remove("disabled")
                val expected = contract.children.toSet() + if (syntax.kind == "control.if")
                    (0 until (args["elseIf"]?.asJsonArray?.size() ?: 0)).map { "elseIf$it" } else emptyList()
                if (syntax.children.keys != expected) fail(syntax.location, "${contract.title} 需要子区：${expected.joinToString()}")
                val children = JsonObject()
                syntax.children.keys.forEach { slot ->
                    children.addProperty(slot, previous?.getAsJsonObject("childBlocks")?.get(slot)?.asString ?: "block-${UUID.randomUUID()}")
                }
                if (children.size() > 0 || previous?.has("childBlocks") == true) node.add("childBlocks", children)
                else node.remove("childBlocks")
                output.add(node); locations[id] = syntax.location
                if (output.size > MAX_NODES) fail(syntax.location, "积木数量超过 4096")
                syntax.children.forEach { (slot, body) -> build(body, children[slot].asString, id, depth + 1) }
            }
        }
        build(parsed, root, null, 0)
        val jsonl = output.joinToString("\n", postfix = if (output.isEmpty()) "" else "\n")
        if (jsonl.toByteArray(Charsets.UTF_8).size > MAX_BYTES) fail(1, 1, "生成积木超过 1 MiB")
        return VisualSourceDocument(jsonl, locations)
    }

    private fun originals(jsonl: String): Map<String, JsonObject> {
        if (jsonl.toByteArray(Charsets.UTF_8).size > MAX_BYTES) fail(1, 1, "插件超过源码编辑上限 1 MiB")
        val result = linkedMapOf<String, JsonObject>()
        jsonl.lineSequence().filter { it.isNotBlank() }.forEachIndexed { index, line ->
            if (result.size >= MAX_NODES) fail(index + 1, 1, "积木数量超过 4096")
            val node = try { JsonParser.parseString(line).asJsonObject } catch (_: Exception) { fail(index + 1, 1, "积木数据损坏") }
            checkJsonDepth(node, 32, SourceLocation(index + 1, 1))
            val id = try { node["nodeId"].asString } catch (_: Exception) { fail(index + 1, 1, "积木缺少身份") }
            if (result.put(id, node) != null) fail(index + 1, 1, "重复节点身份")
        }
        return result
    }

    private data class SyntaxNode(val kind: String, val args: JsonObject,
        val children: LinkedHashMap<String, List<SyntaxNode>>, val id: String?, val disabled: Boolean,
        val location: SourceLocation)

    private inner class Parser(source: String, private val variables: Set<String>, private val check: () -> Unit) {
        private val tokens = Lexer(source, check).tokens()
        private var pos = 0
        private var count = 0
        private var depth = 0
        private val current get() = tokens[pos]
        private fun error(message: String): Nothing = fail(current.location, message)
        private fun isToken(text: String) = current.kind == text || current.text == text && current.kind == "id"
        private fun take(text: String): Boolean { if (!isToken(text)) return false; pos++; return true }
        private fun expect(text: String) { newlines(); if (!take(text)) error("需要 '$text'") }
        private fun newlines() { while (take("nl")) Unit }
        private fun separators() { while (take("nl") || take(";")) Unit }
        private fun objectValue(): JsonObject { val result = value(); if (!result.isJsonObject) error("参数需要具名表，不是数组或标量"); return result.asJsonObject }
        fun program(): List<SyntaxNode> { val result = statements(setOf("eof")); if (!isToken("eof")) error("存在未支持语句"); return result }
        private fun statements(stops: Set<String>): List<SyntaxNode> {
            if (++depth > 32) error("源码结构嵌套超过 32 层")
            val result = mutableListOf<SyntaxNode>()
            separators()
            while (stops.none(::isToken)) {
                check()
                if (isToken("eof")) error("结构未结束")
                var id: String? = null
                var disabled = false
                var start = current.location
                if (current.kind == "meta") {
                    val metadata = try { JsonParser.parseString(current.text).asJsonObject } catch (_: Exception) { error("@node 身份标记损坏") }
                    if (metadata.keySet() != setOf("id", "disabled") || !metadata["id"].isJsonPrimitive || !metadata["id"].asJsonPrimitive.isString ||
                        !metadata["disabled"].isJsonPrimitive || !metadata["disabled"].asJsonPrimitive.isBoolean) error("@node 只接受 id 字符串和 disabled 布尔值")
                    id = metadata["id"].asString; disabled = metadata["disabled"].asBoolean; pos++; newlines()
                    if (stops.any(::isToken) || isToken("meta")) error("@node 后缺少积木语句")
                } else start = current.location
                val (kind, args, children) = statement()
                if (++count > MAX_NODES) error("积木数量超过 4096")
                result += SyntaxNode(kind, args, children, id, disabled, start.copy(endLine = tokens[(pos - 1).coerceAtLeast(0)].location.line))
                if (!isToken("nl") && !isToken(";") && current.kind != "comment" && stops.none(::isToken)) error("语句之间需要换行或分号；不能忽略未知代码")
                separators()
            }
            depth--
            return result
        }
        private fun statement(): Triple<String, JsonObject, LinkedHashMap<String, List<SyntaxNode>>> {
            val children = linkedMapOf<String, List<SyntaxNode>>()
            if (current.kind == "comment") {
                val args = JsonObject().apply { addProperty("message", current.text.trim()) }; pos++
                return Triple("task.comment", args, children)
            }
            if (take("if")) {
                val args = condition(); expect("then"); children["then"] = statements(setOf("elseif", "else", "end"))
                val branches = JsonArray()
                while (take("elseif")) {
                    branches.add(condition()); expect("then")
                    children["elseIf${branches.size() - 1}"] = statements(setOf("elseif", "else", "end"))
                    if (branches.size() > 32) error("否则如果分支超过 32 项")
                }
                if (branches.size() > 0) args.add("elseIf", branches)
                children["else"] = if (take("else")) statements(setOf("end")) else emptyList()
                expect("end"); return Triple("control.if", args, children)
            }
            if (take("while")) {
                val descriptor = isToken("Condition")
                val args = condition()
                if (!descriptor) args.addProperty("maxIterations", 10000)
                expect("do"); children["body"] = statements(setOf("end")); expect("end")
                return Triple("control.while", args, children)
            }
            if (take("for")) {
                expect("_"); expect("="); if (current.kind != "number" || current.text != "1") error("限次循环固定从 1 开始；循环次数输出在 Repeat 参数配置")
                pos++; expect(","); newlines()
                val args = if (take("Repeat")) { expect("("); objectValue().also { expect(")") } }
                else JsonObject().apply {
                    if (current.kind == "id" && current.text in variables) {
                        addProperty("times", 1); addProperty("timesVariable", current.text); pos++
                    } else add("times", value())
                }
                expect("do")
                children["body"] = statements(setOf("end")); expect("end")
                return Triple("control.repeat", args, children)
            }
            if (take("break")) return Triple("control.break", JsonObject(), children)
            if (take("goto")) {
                val label = identifier(); return Triple("control.goto", JsonObject().apply { addProperty("name", label) }, children)
            }
            if (take("::")) {
                val label = identifier(); expect("::")
                return Triple("control.label", JsonObject().apply { addProperty("name", label) }, children)
            }
            val first = identifier()
            if (take("=")) {
                if (first !in variables) error("赋值目标 '$first' 尚未声明；请先在变量页面维护")
                val args = JsonObject().apply { addProperty("name", first) }
                if (take("Calculate")) {
                    expect("("); val expression = string(); expect(")"); args.addProperty("expression", expression)
                    return Triple("variable.calculate", args, children)
                }
                val expr = mutableListOf<Token>()
                while (!isToken("nl") && !isToken(";") && !isToken("eof") && !isToken("end") && !isToken("else") && !isToken("elseif") && current.kind != "comment") {
                    if (expr.size >= 256) error("计算表达式过长")
                    expr += current; pos++
                }
                if (expr.isEmpty()) error("赋值缺少右值")
                if (expr.size == 1 && expr[0].kind == "id" && expr[0].text in variables) {
                    args.addProperty("sourceName", expr[0].text); return Triple("variable.copy", args, children)
                }
                if (expr.size == 1 && expr[0].kind in setOf("number", "string") ||
                    expr.size == 2 && expr[0].kind == "-" && expr[1].kind == "number") {
                    args.add("value", if (expr[0].kind == "string") JsonPrimitive(expr[0].text) else JsonParser.parseString(expr.joinToString("") { it.text }))
                    return Triple("variable.set", args, children)
                }
                if (expr.any { it.kind !in setOf("id", "number", "string", "+", "-", "*", "/", "//", "%", "..", "(", ")", ",") || it.kind == "id" && it.text in KEYWORDS }) error("未支持赋值表达式；函数积木请使用具名调用")
                args.addProperty("expression", expr.joinToString(" ") { if (it.kind == "string") JsonPrimitive(it.text).toString() else it.text })
                return Triple("variable.calculate", args, children)
            }
            var functionName = first
            expect("."); functionName += ".${identifier()}"
            while (take(".")) functionName += ".${identifier()}"
            val contract = byName[functionName] ?: error("未注册函数 $functionName；不能直接调用任意 Lua/API")
            expect("("); val args = objectValue()
            newlines()
            if (take(",")) {
                expect("{"); newlines()
                while (!take("}")) {
                    val slot = key(); expect("="); expect("function"); expect("("); expect(")")
                    if (children.containsKey(slot)) error("重复子区 '$slot'")
                    children[slot] = statements(setOf("end")); expect("end"); newlines()
                    if (!take(",") && !isToken("}")) error("子区之间需要逗号")
                    newlines()
                }
            }
            expect(")"); return Triple(contract.kind, args, children)
        }
        private fun condition(): JsonObject {
            // Explicit condition descriptors preserve legacy/compound comparisons and loop limits exactly.
            if (take("Condition")) {
                expect("("); val args = objectValue(); expect(")")
                if (args.has("elseIf")) error("否则如果请用 elseif 分支，不能重复存放在 Condition 中")
                return args
            }
            conditionNodes = 0
            val tree = booleanExpression(0)
            fun checkConditionDepth(value: JsonObject, depth: Int) {
                if (depth > 8) error("复合条件嵌套超过 8 层")
                when (value["type"].asString) {
                    "not" -> checkConditionDepth(value["condition"].asJsonObject, depth + 1)
                    "all", "any" -> value["conditions"].asJsonArray.forEach { checkConditionDepth(it.asJsonObject, depth + 1) }
                }
            }
            checkConditionDepth(tree, 1)
            fun firstCompare(value: JsonObject): JsonObject = when (value["type"].asString) {
                "compare" -> value
                "not" -> firstCompare(value["condition"].asJsonObject)
                else -> firstCompare(value["conditions"].asJsonArray[0].asJsonObject)
            }
            val args = firstCompare(tree).deepCopy().apply { remove("type") }
            if (tree["type"].asString != "compare") args.add("condition", tree)
            return args
        }
        private var conditionDepth = 0
        private var conditionNodes = 0
        private fun booleanExpression(minimum: Int): JsonObject {
            if (++conditionNodes > 64) error("复合条件超过 64 项")
            if (++conditionDepth > 8) error("复合条件嵌套超过 8 层")
            newlines()
            var left = when {
                take("not") -> {
                    newlines()
                    if (!isToken("(")) error("not 后请用括号包住比较，避免改变 Lua 运算优先级")
                    JsonObject().apply { addProperty("type", "not"); add("condition", booleanExpression(3)) }
                }
                take("(") -> booleanExpression(0).also { expect(")") }
                else -> {
                    val variable = identifier()
                    if (variable !in variables) error("条件变量 '$variable' 尚未声明")
                    val operators = mapOf("==" to "equals", "~=" to "notEquals", "<" to "lessThan", "<=" to "lessOrEqual", ">" to "greaterThan", ">=" to "greaterOrEqual")
                    val operator = operators[current.kind] ?: error("条件需要比较运算符")
                    pos++; newlines()
                    JsonObject().apply {
                        addProperty("type", "compare"); addProperty("variable", variable); addProperty("operator", operator)
                        if (current.kind == "id" && current.text in variables) {
                            addProperty("value", 0); addProperty("valueVariable", current.text); pos++
                        } else { val right = value(); if (right.isJsonArray || right.isJsonObject) error("比较右值必须是标量"); add("value", right) }
                    }
                }
            }
            while (isToken("and") || isToken("or")) {
                val and = isToken("and"); val priority = if (and) 2 else 1
                if (priority < minimum) break
                if (++conditionNodes > 64) error("复合条件超过 64 项")
                pos++
                val right = booleanExpression(priority + 1)
                left = JsonObject().apply {
                    addProperty("type", if (and) "all" else "any")
                    add("conditions", JsonArray().apply { add(left); add(right) })
                }
            }
            conditionDepth--; return left
        }
        private fun identifier(): String { if (current.kind != "id" || current.text in KEYWORDS) error("需要标识符；任意 Lua 声明/函数不支持"); return current.text.also { pos++ } }
        private fun string(): String { newlines(); if (current.kind != "string") error("需要字符串"); return current.text.also { pos++ } }
        private fun key(): String {
            newlines()
            if (take("[")) { val result = string(); expect("]"); return result }
            return identifier()
        }
        private var valueDepth = 0
        private fun value(): JsonElement {
            check(); newlines()
            if (++valueDepth > 32) error("参数嵌套超过 32 层")
            val result: JsonElement = when {
                take("-") -> { if (current.kind != "number") error("负号后需要数值"); JsonParser.parseString("-${current.text}").also { pos++ } }
                current.kind == "number" -> JsonParser.parseString(current.text).also { pos++ }
                current.kind == "string" -> JsonPrimitive(current.text).also { pos++ }
                take("true") -> JsonPrimitive(true)
                take("false") -> JsonPrimitive(false)
                take("Json") -> {
                    expect(".")
                    if (take("Null")) JsonNull.INSTANCE
                    else { expect("Array"); expect("("); expect("{"); val array = JsonArray(); newlines()
                        while (!take("}")) { array.add(value()); newlines(); if (!take(",") && !isToken("}")) error("数组项之间需要逗号"); newlines() }
                        expect(")"); array
                    }
                }
                take("{") -> {
                    val obj = JsonObject(); newlines()
                    while (!take("}")) {
                        val key = key(); expect("=")
                        if (obj.has(key)) error("重复参数 '$key'")
                        obj.add(key, value()); newlines()
                        if (!take(",") && !isToken("}")) error("具名参数之间需要逗号")
                        newlines()
                    }; obj
                }
                else -> error("参数只允许字面量、具名表、Json.Array 或 Json.Null；动态值请用积木的变量参数")
            }
            valueDepth--
            return result
        }
    }

    companion object {
        const val MAX_BYTES = 1_048_576
        const val MAX_NODES = 4096
        private val KEYWORDS = setOf("and", "break", "do", "else", "elseif", "end", "false", "for", "function", "goto", "if", "in", "local", "nil", "not", "or", "repeat", "return", "then", "true", "until", "while")
        fun name(kind: String): String = kind.split('.').joinToString(".") { it.replaceFirstChar(Char::uppercaseChar) }
        private fun fail(line: Int, column: Int, message: String): Nothing = throw VisualSourceException(SourceLocation(line, column), message)
        private fun fail(location: SourceLocation, message: String): Nothing = throw VisualSourceException(location, message)
        private fun checkJsonDepth(root: JsonElement, maximum: Int, location: SourceLocation) {
            val pending = java.util.ArrayDeque<Pair<JsonElement, Int>>()
            pending.add(root to 0)
            while (pending.isNotEmpty()) {
                val (value, depth) = pending.removeLast()
                if (depth > maximum) fail(location, "参数/条件嵌套超过 $maximum 层")
                if (value.isJsonObject) value.asJsonObject.entrySet().forEach { pending.add(it.value to depth + 1) }
                if (value.isJsonArray) value.asJsonArray.forEach { pending.add(it to depth + 1) }
            }
        }
        private fun quote(text: String): String = buildString {
            append('"')
            text.forEach { c -> when (c) {
                '"' -> append("\\\""); '\\' -> append("\\\\"); '\n' -> append("\\n"); '\r' -> append("\\r"); '\t' -> append("\\t")
                else -> if (c.code < 32 || c.code == 127) append("\\${c.code.toString().padStart(3, '0')}") else append(c)
            } }; append('"')
        }
        private fun literal(value: JsonElement, depth: Int = 0): String {
            if (depth > 32) fail(1, 1, "参数嵌套超过 32 层")
            return when {
                value.isJsonNull -> "Json.Null"
                value.isJsonArray -> "Json.Array({${value.asJsonArray.joinToString(", ") { literal(it, depth + 1) }}})"
                value.isJsonObject -> "{${value.asJsonObject.entrySet().joinToString(", ") { (key, v) -> "[${quote(key)}] = ${literal(v, depth + 1)}" }}}"
                value.asJsonPrimitive.isString -> quote(value.asString)
                else -> value.toString()
            }
        }
    }
}

private data class Token(val kind: String, val text: String, val location: SourceLocation)

/** Lexical scanning keeps physical positions, including escaped strings and comments. */
private class Lexer(private val source: String, private val check: () -> Unit) {
    private var pos = 0
    private var line = 1
    private var column = 1
    private fun advance(): Char {
        if (pos % 4096 == 0) check()
        return source[pos++].also { if (it == '\n') { line++; column = 1 } else column++ }
    }
    private fun error(location: SourceLocation, message: String): Nothing = throw VisualSourceException(location, message)
    fun tokens(): List<Token> {
        val result = mutableListOf<Token>()
        while (pos < source.length) {
            if (result.size % 256 == 0) check()
            if (result.size >= 200_000) error(SourceLocation(line, column), "词法项超过 200000")
            val location = SourceLocation(line, column)
            val c = source[pos]
            when {
                c == '\n' -> { advance(); result += Token("nl", "\n", location) }
                c.isWhitespace() -> advance()
                source.startsWith("--", pos) -> {
                    advance(); advance(); val start = pos
                    if (source.startsWith("[[", pos) || source.startsWith("[=", pos)) error(location, "长注释请改为逐行 -- 注释或 Task.Comment")
                    while (pos < source.length && source[pos] != '\n') advance()
                    val text = source.substring(start, pos).removeSuffix("\r")
                    if (!text.startsWith("!")) result += if (text.startsWith("@node ")) Token("meta", text.removePrefix("@node "), location)
                    else Token("comment", text, location)
                }
                c == '"' || c == '\'' -> {
                    advance(); val text = StringBuilder(); var closed = false
                    while (pos < source.length) {
                        val next = advance()
                        if (next == c) { closed = true; break }
                        if (next == '\n' || next == '\r') error(location, "字符串必须闭合；换行请使用 \\n")
                        if (next != '\\') text.append(next)
                        else {
                            if (pos == source.length) error(location, "字符串转义未完成")
                            val escaped = advance()
                            text.append(when (escaped) {
                                'n' -> '\n'; 'r' -> '\r'; 't' -> '\t'; 'a' -> '\u0007'; 'b' -> '\b'; 'f' -> '\u000c'; 'v' -> '\u000b'
                                '\\', '"', '\'' -> escaped
                                in '0'..'9' -> {
                                    val digits = StringBuilder().append(escaped)
                                    repeat(2) { if (pos < source.length && source[pos] in '0'..'9') digits.append(advance()) }
                                    val code = digits.toString().toInt(); if (code > 255) error(location, "十进制转义超出字节范围"); code.toChar()
                                }
                                else -> error(location, "未支持字符串转义")
                            })
                        }
                    }
                    if (!closed) error(location, "字符串未闭合")
                    result += Token("string", text.toString(), location)
                }
                c in '0'..'9' -> {
                    val start = pos
                    while (pos < source.length && source[pos] in '0'..'9') advance()
                    if (pos < source.length && source[pos] == '.' && !source.startsWith("..", pos)) {
                        advance(); while (pos < source.length && source[pos] in '0'..'9') advance()
                    }
                    if (pos < source.length && source[pos] in "eE") {
                        advance(); if (pos < source.length && source[pos] in "+-") advance()
                        val exponent = pos; while (pos < source.length && source[pos] in '0'..'9') advance()
                        if (exponent == pos) error(location, "数值指数缺少数字")
                    }
                    val number = source.substring(start, pos)
                    if (!number.matches(Regex("(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?"))) error(location, "数值请使用十进制整数或浮点数")
                    result += Token("number", number, location)
                }
                c == '_' || c in 'a'..'z' || c in 'A'..'Z' -> {
                    val start = pos
                    while (pos < source.length && (source[pos] == '_' || source[pos] in 'a'..'z' || source[pos] in 'A'..'Z' || source[pos] in '0'..'9')) advance()
                    result += Token("id", source.substring(start, pos), location)
                }
                else -> {
                    val pair = source.substring(pos, (pos + 2).coerceAtMost(source.length))
                    val symbol = if (pair in setOf("::", "//", "..", "==", "~=", "<=", ">=")) pair else c.toString()
                    if (symbol !in setOf("::", "//", "..", "==", "~=", "<=", ">=", "<", ">", "{", "}", "[", "]", "(", ")", "=", ",", ".", ";", "+", "-", "*", "/", "%")) error(location, "未支持的 Lua 符号")
                    repeat(symbol.length) { advance() }; result += Token(symbol, symbol, location)
                }
            }
        }
        result += Token("eof", "", SourceLocation(line, column)); return result
    }
}

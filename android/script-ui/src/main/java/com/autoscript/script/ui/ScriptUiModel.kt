package com.autoscript.script.ui

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser

enum class UiControl(val label: String, val valueKind: String) {
    INPUT("单行文本", "text"), TEXTAREA("多行文本", "text"), LABEL("标签", "text"),
    BUTTON("按钮", "text"), IMAGE("图片", "text"), CONTAINER("容器", "text"), NAVIGATION("分页导航", "text"),
    NUMBER("整数输入", "integer"), SLIDER("滑块", "integer"), PROGRESS("进度条", "integer"),
    CHECKBOX("复选框", "boolean"), RADIO("单选组", "choice"), SELECT("下拉框", "choice"), LIST("列表", "choice"),
}
data class UiPage(val id: String = "main", val title: String = "主页面", val width: Int = 720, val height: Int = 960, val background: String = "#FFFFFFFF")
data class UiPresentation(
    val control: String = "INPUT", val pageId: String = "main", val parentId: String? = null,
    val x: Int = 20, val y: Int = 20, val width: Int = 320, val height: Int = 72,
    val fontPx: Int = 28, val textColor: String = "#FF202938", val background: String = "#FFFFFFFF",
    val alignment: String = "center", val visible: Boolean = true, val enabled: Boolean = true,
    val binding: String? = null, val events: Map<String, String> = emptyMap(),
)
data class UiField(
    val id: String, val label: String, val kind: String, val required: Boolean,
    val initialValue: String, val minimum: Long? = null, val maximum: Long? = null,
    val options: List<String> = emptyList(), val ui: UiPresentation? = null,
) {
    val control: UiControl get() = ui?.control?.let(UiControl::valueOf) ?: when (kind) {
        "integer" -> UiControl.NUMBER; "boolean" -> UiControl.CHECKBOX; "choice" -> UiControl.SELECT; else -> UiControl.INPUT
    }
    val input: Boolean get() = control in setOf(UiControl.INPUT, UiControl.TEXTAREA, UiControl.NUMBER, UiControl.CHECKBOX, UiControl.RADIO, UiControl.SELECT, UiControl.LIST, UiControl.SLIDER)
}
data class ScriptUiDefinition(val description: String? = null, val fields: List<UiField>, val version: Int = 1, val pages: List<UiPage> = listOf(UiPage())) {
    fun initialValues() = fields.associate { it.id to it.initialValue }
    fun validateValues(values: Map<String, String>): Map<String, String> = fields.associate { f ->
        val value = values[f.id] ?: f.initialValue
        require(value.codePointCount(0, value.length) <= (if (version == 2) 2048 else 256) && '\u0000' !in value) { "${f.label} 内容过长" }
        when (f.kind) {
            "integer" -> require(value.toLongOrNull()?.let { it in (f.minimum ?: -1_000_000_000)..(f.maximum ?: 1_000_000_000) } == true) { "${f.label} 整数超出范围" }
            "boolean" -> require(value == "true" || value == "false") { "${f.label} 状态无效" }
            "choice" -> require(value in f.options) { "${f.label} 请选择有效选项" }
            else -> if (f.required && f.input) require(value.isNotBlank()) { "${f.label} 不能为空" }
        }
        f.id to value
    }
    fun validate() {
        require(version in 1..2 && fields.size in 1..128 && (version == 2 || fields.size <= 32)) { "界面版本或控件数量无效" }
        require(description == null || description.length <= 512 && description.none(Char::isISOControl)) { "界面说明无效" }
        require(pages.size in 1..16 && pages.map { it.id }.distinct().size == pages.size) { "页面无效或重复" }
        pages.forEach { require(ID.matches(it.id) && it.title.length in 1..64 && it.width in 100..4096 && it.height in 100..8192 && COLOR.matches(it.background)) { "页面属性无效" } }
        require(fields.map { it.id }.distinct().size == fields.size) { "控件 ID 重复" }
        val byId = fields.associateBy { it.id }
        fields.forEach { f ->
            require((version == 2) == (f.ui != null)) { "控件布局与界面版本不匹配" }
            require(ID.matches(f.id) && f.label.length in 1..64 && f.label.none(Char::isISOControl)) { "控件 ID 或标题无效" }
            require(f.kind in setOf("text", "integer", "boolean", "choice") && f.control.valueKind == f.kind) { "控件类型不匹配" }
            require(f.options.size <= (if (version == 2) 64 else 32) && f.options.distinct().size == f.options.size && f.options.all { it.length in 1..(if (version == 2) 128 else 64) && it.none(Char::isISOControl) }) { "选项无效" }
            require(f.kind == "choice" || f.options.isEmpty()) { "非选择控件不能设置选项" }
            require(f.kind == "integer" || f.minimum == null && f.maximum == null) { "非数值控件不能设置范围" }
            require(f.minimum == null || f.maximum == null || f.minimum <= f.maximum) { "范围倒置" }
            if (f.control in setOf(UiControl.SLIDER, UiControl.PROGRESS)) {
                val low = f.minimum ?: 0; val high = f.maximum ?: 100
                require(low in -1_000_000_000..1_000_000_000 && high in -1_000_000_000..1_000_000_000 && high >= low && high - low <= 1_000_000) { "滑块/进度范围过大" }
                if (f.control == UiControl.PROGRESS) require(low == 0L && high in 1..1_000_000) { "进度范围需从0开始" }
            }
            f.ui?.let { p ->
                require(version == 2 && p.pageId in pages.map { it.id }) { "控件页面不存在" }
                require(p.x in 0..4096 && p.y in 0..8192 && p.width in 20..4096 && p.height in 20..8192 && p.fontPx in 8..160) { "控件尺寸无效" }
                require(COLOR.matches(p.textColor) && COLOR.matches(p.background) && p.alignment in setOf("left", "center", "right")) { "样式无效" }
                require(p.binding == null || p.binding!!.length <= 196 && BINDING.matches(p.binding!!)) { "绑定格式无效" }
                require(p.events.keys.all { it in setOf("click", "longClick", "change", "selection") } && p.events.values.all { actionValid(it, pages) }) { "事件无效" }
                var parent = p.parentId; val seen = mutableSetOf(f.id)
                while (parent != null) {
                    require(seen.add(parent) && seen.size <= 9) { "容器循环或嵌套过深" }
                    val owner = byId[parent] ?: error("父容器不存在")
                    require(owner.control == UiControl.CONTAINER && owner.ui?.pageId == p.pageId) { "父容器类型或页面不匹配" }
                    parent = owner.ui?.parentId
                }
            }
        }
        validateValues(initialValues())
    }
    fun toJson(): JsonObject = JsonObject().apply {
        add("description", description?.let { com.google.gson.JsonPrimitive(it) } ?: JsonNull.INSTANCE)
        if (version == 2) { addProperty("version", 2); add("pages", Gson().toJsonTree(pages)) }
        add("fields", JsonArray().apply { fields.forEach { f -> add(JsonObject().apply {
            addProperty("id", f.id); addProperty("label", f.label); addProperty("kind", f.kind); addProperty("required", f.required)
            when (f.kind) { "integer" -> addProperty("initialValue", f.initialValue.toLong()); "boolean" -> addProperty("initialValue", f.initialValue.toBooleanStrict()); else -> addProperty("initialValue", f.initialValue) }
            add("minimum", f.minimum?.let { com.google.gson.JsonPrimitive(it) } ?: JsonNull.INSTANCE)
            add("maximum", f.maximum?.let { com.google.gson.JsonPrimitive(it) } ?: JsonNull.INSTANCE)
            add("options", Gson().toJsonTree(f.options)); f.ui?.let { add("ui", Gson().toJsonTree(it)) }
        }) } })
    }
    companion object {
        fun parse(source: String): ScriptUiDefinition {
            require(source.length <= 262144) { "界面配置过大" }
            val root = JsonParser.parseString(source).asJsonObject
            require(root.keySet().all { it in setOf("description", "fields", "version", "pages") } && root.has("description") && root.has("fields")) { "界面配置字段无效" }
            fun JsonObject.string(name: String): String { val e = get(name); require(e?.isJsonPrimitive == true && e.asJsonPrimitive.isString) { "$name 必须为字符串" }; return e.asString }
            fun JsonObject.number(name: String): Long { val e = get(name); require(e?.isJsonPrimitive == true && e.asJsonPrimitive.isNumber) { "$name 必须为整数" }; return e.asString.toLongOrNull() ?: error("$name 必须为整数") }
            fun JsonObject.int(name: String): Int { val n = number(name); require(n in Int.MIN_VALUE..Int.MAX_VALUE); return n.toInt() }
            fun JsonObject.boolean(name: String): Boolean { val e = get(name); require(e?.isJsonPrimitive == true && e.asJsonPrimitive.isBoolean) { "$name 必须为布尔值" }; return e.asBoolean }
            fun JsonObject.nullableString(name: String) = get(name)?.takeUnless { it.isJsonNull }?.let { string(name) }
            fun JsonObject.nullableNumber(name: String) = get(name)?.takeUnless { it.isJsonNull }?.let { number(name) }
            val version = if (root.has("version")) root.int("version").also { require(it == 2) } else 1
            require(if (version == 2) root.keySet() == setOf("description", "fields", "version", "pages") else root.keySet() == setOf("description", "fields")) { "界面字段不完整" }
            val pages = if (version == 2) root.getAsJsonArray("pages").map { e -> val p = e.asJsonObject
                require(p.keySet() == setOf("id", "title", "width", "height", "background"))
                UiPage(p.string("id"), p.string("title"), p.int("width"), p.int("height"), p.string("background"))
            } else listOf(UiPage())
            val fields = root.getAsJsonArray("fields").map { e -> val f = e.asJsonObject
                require(f.keySet().all { it in setOf("id", "label", "kind", "required", "initialValue", "minimum", "maximum", "options", "ui") }) { "控件含未知字段" }
                require(setOf("id", "label", "kind", "required", "initialValue", "minimum", "maximum", "options").all(f::has)) { "控件字段不完整" }
                val kind = f.string("kind")
                val initial = when (kind) { "integer" -> f.number("initialValue").toString(); "boolean" -> f.boolean("initialValue").toString(); else -> f.string("initialValue") }
                val style = f.get("ui")?.takeUnless { it.isJsonNull }?.asJsonObject?.let { p ->
                    val keys = setOf("control", "pageId", "parentId", "x", "y", "width", "height", "fontPx", "textColor", "background", "alignment", "visible", "enabled", "binding", "events")
                    require(p.keySet().all { it in keys } && (keys - setOf("parentId", "binding")).all(p::has)) { "布局字段不完整或未知" }
                    val events = p.getAsJsonObject("events").keySet().associateWith { p.getAsJsonObject("events").string(it) }
                    UiPresentation(p.string("control"), p.string("pageId"), p.nullableString("parentId"), p.int("x"), p.int("y"), p.int("width"), p.int("height"), p.int("fontPx"), p.string("textColor"), p.string("background"), p.string("alignment"), p.boolean("visible"), p.boolean("enabled"), p.nullableString("binding"), events)
                }
                UiField(f.string("id"), f.string("label"), kind, f.boolean("required"), initial, f.nullableNumber("minimum"), f.nullableNumber("maximum"),
                    f.getAsJsonArray("options").map { require(it.isJsonPrimitive && it.asJsonPrimitive.isString); it.asString }, style)
            }
            return ScriptUiDefinition(root.nullableString("description"), fields, version, pages).also { it.validate() }
        }
    }
}
private val ID = Regex("[A-Za-z_][A-Za-z0-9_]{0,63}")
private val COLOR = Regex("#[0-9a-fA-F]{8}")
private val BINDING = Regex("(global:[A-Za-z_][A-Za-z0-9_]{0,63}|param:[A-Za-z_][A-Za-z0-9_]{0,63}|flow:[A-Za-z0-9_-]{1,128}:[A-Za-z_][A-Za-z0-9_]{0,63})")
fun actionValid(action: String, pages: List<UiPage>): Boolean = action in setOf("run", "close", "minimize") || action.startsWith("page:") && pages.any { it.id == action.removePrefix("page:") } || Regex("flow:[A-Za-z0-9_-]{1,128}").matches(action)

/** Values remain typed data. This method is shared by Studio and the independently packaged Runner. */
fun ScriptUiDefinition.luaConfiguration(values: Map<String, String>): String {
    val validated = validateValues(values)
    fun literal(f: UiField, value: String) = when (f.kind) { "integer", "boolean" -> value; else -> luaString(value) }
    return buildString {
        append("RunnerConfig={")
        fields.forEachIndexed { i, f -> if (i > 0) append(','); append('[').append(luaString(f.id)).append("]=").append(literal(f, validated.getValue(f.id))) }
        append("}\n__ui_initial_globals={}\n__ui_initial_locals={}\n__ui_entry_args={}\n")
        fields.forEach { f -> f.ui?.binding?.let { binding ->
            val parts = binding.split(':'); val target = when (parts[0]) {
                "global" -> "__ui_initial_globals"; "param" -> "__ui_entry_args"; else -> {
                    append("__ui_initial_locals[").append(luaString(parts[1])).append("]=__ui_initial_locals[").append(luaString(parts[1])).append("] or {}\n")
                    "__ui_initial_locals[${luaString(parts[1])}]"
                }
            }
            val value = if (f.kind == "boolean" && parts[0] != "param") { if (validated.getValue(f.id) == "true") "1" else "0" } else literal(f, validated.getValue(f.id))
            append(target).append('[').append(luaString(parts.last())).append("]=").append(value).append('\n')
        } }
    }
}
fun luaString(text: String): String = buildString {
    append('"'); text.forEach { c -> when (c) { '\\' -> append("\\\\"); '"' -> append("\\\""); '\n' -> append("\\n"); '\r' -> append("\\r"); else -> if (c.code < 32 || c.code == 127) append("\\${c.code.toString().padStart(3, '0')}") else append(c) } }; append('"')
}
fun canonicalUiJson(element: com.google.gson.JsonElement): String = when {
    element.isJsonObject -> element.asJsonObject.keySet().sorted().joinToString(",", "{", "}") { canonicalUiString(it) + ":" + canonicalUiJson(element.asJsonObject.get(it)) }
    element.isJsonArray -> element.asJsonArray.joinToString(",", "[", "]") { canonicalUiJson(it) }
    element.isJsonPrimitive && element.asJsonPrimitive.isString -> canonicalUiString(element.asString)
    else -> element.toString()
}

/** Match serde_json escaping, including literal Unicode line separators, for release digests. */
private fun canonicalUiString(value: String): String = buildString {
    append('"')
    value.forEach { c -> when (c) {
        '"' -> append("\\\""); '\\' -> append("\\\\"); '\n' -> append("\\n"); '\r' -> append("\\r"); '\t' -> append("\\t")
        '\b' -> append("\\b"); '\u000c' -> append("\\f")
        else -> if (c.code < 32) append("\\u").append(c.code.toString(16).padStart(4, '0')) else append(c)
    } }
    append('"')
}

package com.autoscript.project.store

import com.google.gson.*
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.StringReader
import java.math.BigDecimal
import kotlin.math.abs

/** Portable authoring settings; operations copy this value and never resolve mutable presets at runtime. */
data class ProjectColorRule(val rgb: Int, val redTolerance: Int = 16, val greenTolerance: Int = 16,
    val blueTolerance: Int = 16, val exclude: Boolean = false, val space: String = "absolute") {
    init { require(rgb in 0..0xFFFFFF && listOf(redTolerance, greenTolerance, blueTolerance).all { it in 0..255 } && space in setOf("absolute", "relative")) { "滤色颜色、容差或方式无效" } }
    fun matches(pixel: Int, effectiveSpace: String): Boolean {
        val dr = (pixel ushr 16 and 255) - (rgb ushr 16 and 255)
        val dg = (pixel ushr 8 and 255) - (rgb ushr 8 and 255)
        val db = (pixel and 255) - (rgb and 255)
        return if (effectiveSpace == "relative") abs(dr-dg) <= redTolerance+greenTolerance &&
            abs(dr-db) <= redTolerance+blueTolerance && abs(dg-db) <= greenTolerance+blueTolerance
        else abs(dr) <= redTolerance && abs(dg) <= greenTolerance && abs(db) <= blueTolerance
    }
    fun json() = JsonObject().apply {
        addProperty("rgb",rgb); addProperty("redTolerance",redTolerance); addProperty("greenTolerance",greenTolerance)
        addProperty("blueTolerance",blueTolerance); addProperty("exclude",exclude); addProperty("space",space)
    }
}

data class ProjectImageFilter(val space: String = "absolute", val output: String = "binary", val invert: Boolean = false,
    val rules: List<ProjectColorRule> = listOf(ProjectColorRule(0xFFFFFF))) {
    init { require(space in setOf("absolute","relative","custom") && output in setOf("binary","keep") && rules.size in 1..32) { "滤色需1–32条有效规则" } }
    private val hasIncludes = rules.any { !it.exclude }
    fun validatePixels(pixels: Long) {
        require(pixels in 1..4_194_304L && pixels * rules.size <= 64_000_000L) { "滤色超过400万像素或6400万像素规则预算，请缩小图片或减少规则" }
    }
    fun pixel(pixel: Int): Int {
        val alpha = pixel ushr 24
        if (alpha == 0) return 0
        var selected = !hasIncludes
        for (rule in rules) if (rule.matches(pixel, if (space == "custom") rule.space else space)) {
            if (rule.exclude) return render(pixel, invert)
            selected = true
        }
        return render(pixel, selected xor invert)
    }
    private fun render(pixel: Int, selected: Boolean): Int = if (output == "keep") { if (selected) pixel else 0 }
        else (pixel and -0x1000000) or (if (selected) 0xFFFFFF else 0)
    fun json() = JsonObject().apply {
        addProperty("version",1); addProperty("space",space); addProperty("output",output); addProperty("invert",invert)
        add("rules",JsonArray().apply { rules.forEach { add(it.json()) } })
    }
    fun encode(): String = json().toString().also { require(it.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) }
    companion object {
        const val MAX_BYTES = 8192
        fun decode(text: String): ProjectImageFilter = fromJson(strictFilterJson(text, MAX_BYTES).asJsonObject)
        internal fun fromJson(value: JsonObject): ProjectImageFilter {
            require(value.keySet() == setOf("version","space","output","invert","rules") && value.int("version") == 1) { "滤色格式或版本无效" }
            require(value["rules"].isJsonArray && value["rules"].asJsonArray.size() in 1..32) { "滤色规则数量无效" }
            val rules = value["rules"].asJsonArray.map { element ->
                require(element.isJsonObject); val row = element.asJsonObject
                require(row.keySet() == setOf("rgb","redTolerance","greenTolerance","blueTolerance","exclude","space")) { "滤色规则字段无效" }
                ProjectColorRule(row.int("rgb"),row.int("redTolerance"),row.int("greenTolerance"),row.int("blueTolerance"),row.bool("exclude"),row.text("space"))
            }
            return ProjectImageFilter(value.text("space"),value.text("output"),value.bool("invert"),rules)
        }
    }
}

data class ProjectImageFilterPreset(val name: String, val filter: ProjectImageFilter) {
    init { require(name.isNotBlank() && name == name.trim() && name.codePointCount(0,name.length) <= 32 && name.none(Char::isISOControl) &&
        name.toByteArray(Charsets.UTF_8).toString(Charsets.UTF_8)==name) { "方案名称需1–32字且不能含控制字符或无效Unicode" } }
}

internal fun decodeImageFilterPresets(text: String): List<ProjectImageFilterPreset> {
    val document = strictFilterJson(text, MAX_IMAGE_FILTER_PRESET_BYTES).asJsonObject
    require(document.keySet() == setOf("version","presets") && document.int("version") == 1 && document["presets"].isJsonArray)
    require(document["presets"].asJsonArray.size() <= 32) { "最多32个滤色方案" }
    val presets = document["presets"].asJsonArray.map { element ->
        require(element.isJsonObject); val item = element.asJsonObject
        require(item.keySet() == setOf("name","filter") && item["filter"].isJsonObject)
        ProjectImageFilterPreset(item.text("name"),ProjectImageFilter.fromJson(item["filter"].asJsonObject))
    }
    require(presets.map { it.name }.distinct().size == presets.size) { "滤色方案名称重复" }
    return presets
}

internal fun encodeImageFilterPresets(presets: List<ProjectImageFilterPreset>): ByteArray {
    val document = JsonObject().apply {
        addProperty("version",1); add("presets",JsonArray().apply { presets.forEach { preset -> add(JsonObject().apply {
            addProperty("name",preset.name); add("filter",preset.filter.json())
        }) } })
    }.toString()
    decodeImageFilterPresets(document)
    return document.toByteArray(Charsets.UTF_8)
}

const val MAX_IMAGE_FILTER_PRESET_BYTES = 128 * 1024

private fun JsonObject.int(key: String): Int {
    val value = this[key]; require(value.isJsonPrimitive && value.asJsonPrimitive.isNumber && value.toString().matches(Regex("-?(0|[1-9][0-9]*)")))
    return value.toString().toInt()
}
private fun JsonObject.text(key: String): String { val value = this[key]; require(value.isJsonPrimitive && value.asJsonPrimitive.isString); return value.asString }
private fun JsonObject.bool(key: String): Boolean { val value = this[key]; require(value.isJsonPrimitive && value.asJsonPrimitive.isBoolean); return value.asBoolean }

/** Gson tree parsing alone accepts duplicate keys; reject them before publishing a rule set. */
internal fun strictFilterJson(text: String, maxBytes: Int, maxArray: Int = 32, maxDepth: Int = 8): JsonElement {
    require(text.toByteArray(Charsets.UTF_8).size in 1..maxBytes) { "滤色配置超过字节预算" }
    JsonReader(StringReader(text)).use { reader ->
        fun read(depth: Int): JsonElement {
            require(depth <= maxDepth) { "图像配置嵌套过深" }
            return when (reader.peek()) {
                JsonToken.BEGIN_OBJECT -> JsonObject().apply {
                    reader.beginObject(); val names = mutableSetOf<String>()
                    while (reader.hasNext()) { val name = reader.nextName(); require(names.add(name)) { "滤色配置字段重复" }; add(name,read(depth+1)) }; reader.endObject()
                }
                JsonToken.BEGIN_ARRAY -> JsonArray().apply { reader.beginArray(); while (reader.hasNext()) { require(size() < maxArray); add(read(depth+1)) }; reader.endArray() }
                JsonToken.STRING -> JsonPrimitive(reader.nextString())
                JsonToken.NUMBER -> {
                    val raw=reader.nextString();require(raw.matches(Regex("-?(0|[1-9][0-9]*)"))) { "滤色数值必须是整数字面量" }
                    JsonPrimitive(BigDecimal(raw))
                }
                JsonToken.BOOLEAN -> JsonPrimitive(reader.nextBoolean())
                else -> throw IllegalArgumentException("滤色JSON类型无效")
            }
        }
        val result = read(0); require(reader.peek() == JsonToken.END_DOCUMENT && result.isJsonObject)
        return result
    }
}

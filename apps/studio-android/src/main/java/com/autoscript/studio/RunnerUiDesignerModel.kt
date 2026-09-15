package com.autoscript.studio

import com.google.gson.JsonArray
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import java.util.Locale

internal enum class RunnerUiControlKind(val wireName: String, val label: String) {
    TEXT("text", "单行文本"),
    INTEGER("integer", "整数"),
    BOOLEAN("boolean", "复选项"),
    CHOICE("choice", "下拉选择"),
    ;

    companion object {
        fun fromWireName(value: String): RunnerUiControlKind =
            entries.singleOrNull { it.wireName == value } ?: TEXT
    }
}

internal data class RunnerUiFieldDraft(
    val id: String,
    val label: String,
    val kind: RunnerUiControlKind,
    val required: Boolean,
    val initialText: String,
    val minimum: String = "",
    val maximum: String = "",
    val optionsText: String = "",
)

internal data class RunnerUiDesignerDraft(
    val description: String = "",
    val fields: List<RunnerUiFieldDraft> = emptyList(),
) {
    fun add(kind: RunnerUiControlKind): RunnerUiDesignerDraft {
        require(fields.size < 32) { "界面字段不能超过 32 个" }
        val base = kind.wireName.replaceFirstChar { it.titlecase(Locale.ROOT) }
        var index = fields.size + 1
        var id = "field$base$index"
        while (fields.any { it.id == id }) {
            index += 1
            id = "field$base$index"
        }
        val field = when (kind) {
            RunnerUiControlKind.TEXT -> RunnerUiFieldDraft(id, "文本", kind, false, "")
            RunnerUiControlKind.INTEGER -> RunnerUiFieldDraft(id, "数值", kind, true, "0")
            RunnerUiControlKind.BOOLEAN -> RunnerUiFieldDraft(id, "开关", kind, false, "false")
            RunnerUiControlKind.CHOICE -> RunnerUiFieldDraft(id, "选项", kind, true, "选项1", optionsText = "选项1,选项2")
        }
        return copy(fields = fields + field)
    }

    fun update(index: Int, field: RunnerUiFieldDraft): RunnerUiDesignerDraft =
        copy(fields = fields.toMutableList().also { it[index] = field })

    fun remove(index: Int): RunnerUiDesignerDraft = copy(fields = fields.filterIndexed { i, _ -> i != index })

    fun move(index: Int, offset: Int): RunnerUiDesignerDraft {
        val destination = index + offset
        if (index !in fields.indices || destination !in fields.indices) return this
        return copy(fields = fields.toMutableList().also {
            val field = it.removeAt(index)
            it.add(destination, field)
        })
    }
}

internal fun runnerUiDesignerDraft(value: JsonObject?): RunnerUiDesignerDraft {
    if (value == null) return RunnerUiDesignerDraft()
    val fields = value.getAsJsonArray("fields")?.map { element ->
        val field = element.asJsonObject
        val kind = RunnerUiControlKind.fromWireName(field.get("kind").asString)
        val initial = field.get("initialValue")
        RunnerUiFieldDraft(
            id = field.get("id").asString,
            label = field.get("label").asString,
            kind = kind,
            required = field.get("required").asBoolean,
            initialText = initial.asString,
            minimum = field.get("minimum")?.takeUnless { it.isJsonNull }?.asString.orEmpty(),
            maximum = field.get("maximum")?.takeUnless { it.isJsonNull }?.asString.orEmpty(),
            optionsText = field.getAsJsonArray("options")?.joinToString(",") { it.asString }.orEmpty(),
        )
    }.orEmpty()
    return RunnerUiDesignerDraft(
        description = value.get("description")?.takeUnless { it.isJsonNull }?.asString.orEmpty(),
        fields = fields,
    )
}

internal fun RunnerUiDesignerDraft.toRunnerUiJson(): JsonObject? {
    if (fields.isEmpty()) return null
    val ids = mutableSetOf<String>()
    require(description.length <= 512) { "界面说明不能超过 512 个字符" }
    return JsonObject().apply {
        if (description.isBlank()) add("description", JsonNull.INSTANCE)
        else addProperty("description", description.trim())
        add("fields", JsonArray().also { array ->
            fields.forEach { field ->
                require(FIELD_ID.matches(field.id) && ids.add(field.id)) { "字段 ID 无效或重复：${field.id}" }
                require(field.label.isNotBlank()) { "字段标题不能为空" }
                val options = field.optionsText.split(',').map(String::trim).filter(String::isNotEmpty).distinct()
                val item = JsonObject().apply {
                    addProperty("id", field.id)
                    addProperty("label", field.label.trim())
                    addProperty("kind", field.kind.wireName)
                    addProperty("required", field.required)
                    when (field.kind) {
                        RunnerUiControlKind.TEXT -> addProperty("initialValue", field.initialText)
                        RunnerUiControlKind.INTEGER -> addProperty(
                            "initialValue",
                            field.initialText.toLongOrNull() ?: error("${field.label} 的默认值必须是整数"),
                        )
                        RunnerUiControlKind.BOOLEAN -> addProperty(
                            "initialValue",
                            field.initialText.toBooleanStrictOrNull() ?: error("${field.label} 的默认值必须是 true 或 false"),
                        )
                        RunnerUiControlKind.CHOICE -> {
                            require(options.isNotEmpty()) { "${field.label} 至少需要一个选项" }
                            require(field.initialText in options) { "${field.label} 的默认值必须属于选项" }
                            addProperty("initialValue", field.initialText)
                        }
                    }
                    if (field.kind == RunnerUiControlKind.INTEGER) {
                        val minimum = field.minimum.toLongOrNull()
                        val maximum = field.maximum.toLongOrNull()
                        if (minimum == null) add("minimum", JsonNull.INSTANCE) else addProperty("minimum", minimum)
                        if (maximum == null) add("maximum", JsonNull.INSTANCE) else addProperty("maximum", maximum)
                    } else {
                        add("minimum", JsonNull.INSTANCE)
                        add("maximum", JsonNull.INSTANCE)
                    }
                    add("options", JsonArray().also { optionArray ->
                        if (field.kind == RunnerUiControlKind.CHOICE) {
                            options.forEach { option -> optionArray.add(option) }
                        }
                    })
                }
                array.add(item)
            }
        })
    }
}

private val FIELD_ID = Regex("^[A-Za-z_][A-Za-z0-9_]{0,63}$")

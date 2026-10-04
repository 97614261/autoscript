package com.autoscript.script.ui

/** Persistent control state, independent of whether a page has mounted Android widgets. */
internal class ScriptUiCommandState(private val definition: ScriptUiDefinition, initial: Map<String, String>) {
    val values = initial.toMutableMap()
    val visibility = mutableMapOf<String, Boolean>()
    val enabled = mutableMapOf<String, Boolean>()
    val items = mutableMapOf<String, List<String>>()
    private val presentations = mutableMapOf<String, UiPresentation>()

    fun presentation(field: UiField): UiPresentation? = presentations[field.id] ?: field.ui

    fun read(id: String, property: ScriptUiRuntimeProperty): String {
        val field = definition.fields.singleOrNull { it.id == id } ?: error("界面控件不存在：$id")
        return when (property) {
            ScriptUiRuntimeProperty.VALUE, ScriptUiRuntimeProperty.TEXT -> values[id] ?: field.initialValue
            ScriptUiRuntimeProperty.VISIBLE -> (visibility[id] ?: field.ui?.visible ?: true).toString()
            ScriptUiRuntimeProperty.ENABLED -> (enabled[id] ?: field.ui?.enabled ?: true).toString()
            ScriptUiRuntimeProperty.ITEMS -> {
                require(field.kind == "choice") { "控件没有选项" }
                (items[id] ?: field.options).joinToString("\n")
            }
            ScriptUiRuntimeProperty.PROGRESS -> {
                require(field.control == UiControl.PROGRESS) { "控件不是进度条" }
                values[id] ?: field.initialValue
            }
            else -> requireNotNull(presentation(field)) { "旧表单没有设计像素属性" }.runtimeProperty(property)
        }
    }

    fun reset() {
        values.clear(); values.putAll(definition.initialValues())
        visibility.clear(); enabled.clear(); items.clear(); presentations.clear()
    }

    fun apply(id: String, operation: String, value: String): UiField {
        val field = definition.fields.singleOrNull { it.id == id } ?: error("界面控件不存在：$id")
        when (operation) {
            "value", "text" -> {
                when (field.kind) {
                    "integer" -> require(value.toLongOrNull()?.let { it in (field.minimum ?: -1_000_000_000)..(field.maximum ?: 1_000_000_000) } == true)
                    "boolean" -> require(value in setOf("true", "false"))
                    "choice" -> require(value in (items[id] ?: field.options))
                    else -> require(value.length <= 2048 && '\u0000' !in value)
                }
                values[id] = value
            }
            "visible", "enabled" -> {
                require(value in setOf("true", "false"))
                (if (operation == "visible") visibility else enabled)[id] = value == "true"
            }
            "items" -> {
                require(field.kind == "choice")
                val options = value.split('\n')
                require(options.size in 1..64 && options.distinct().size == options.size &&
                    options.all { it.length in 1..128 && it.none(Char::isISOControl) })
                items[id] = options
                if (values[id] !in options) values[id] = options.first()
            }
            "progress" -> {
                require(field.control == UiControl.PROGRESS)
                val progress = value.toIntOrNull() ?: error("进度需要整数")
                require(progress in 0..(field.maximum ?: 100).toInt())
                values[id] = value
            }
            else -> {
                val property = ScriptUiRuntimeProperty.fromOperation(operation)
                require(property.presentation) { "界面操作无效：$operation" }
                presentations[id] = requireNotNull(presentation(field)) { "旧表单没有设计像素属性" }
                    .withRuntimeProperty(property, value)
            }
        }
        return field
    }
}

package com.autoscript.script.ui

/** Persistent control state, independent of whether a page has mounted Android widgets. */
internal class ScriptUiCommandState(private val definition: ScriptUiDefinition, initial: Map<String, String>) {
    val values = initial.toMutableMap()
    val visibility = mutableMapOf<String, Boolean>()
    val enabled = mutableMapOf<String, Boolean>()
    val items = mutableMapOf<String, List<String>>()
    private val presentations = mutableMapOf<String, UiPresentation>()
    private val inputs=ScriptUiRuntimeInputs()
    fun effective(field: UiField)=inputs.effective(field).let {it.copy(options=items[it.id] ?: it.options)}
    // Android uses -1 for no selected row; script APIs expose the same state as index 0.
    fun selectedOptionIndex(field: UiField) = effective(field).options.indexOf(values[field.id] ?: field.initialValue)
    // Spinner always selects a row after layout. Reserve presentation-only row 0 for no selection;
    // the stored options and 1-based script indices do not contain this row.
    fun spinnerPosition(field: UiField) = selectedOptionIndex(field) + 1
    fun hint(field: UiField)=inputs.hint(field)
    fun step(field: UiField)=inputs.step(field)
    fun group(id: String): List<UiField> {
        val owner=definition.fields.singleOrNull {it.id==id} ?: error("容器不存在")
        require(owner.control==UiControl.CONTAINER) {"组操作请选择容器"}
        val ids=mutableSetOf(id)
        repeat(8) {definition.fields.filter {it.ui?.parentId in ids}.forEach {ids.add(it.id)}}
        return definition.fields.filter {it.id in ids}.map(::effective)
    }
    fun applyGroup(id: String, operation: String, value: String) {
        val fields=group(id)
        when(operation) {
            "groupVisible","groupEnabled" -> {
                val flag=value.toBooleanStrict()
                val target=if(operation=="groupVisible") visibility else enabled
                target.putAll(fields.associate {it.id to flag})
            }
            "groupReset" -> {
                require(value.isEmpty())
                fields.filter {it.input}.forEach {inputs.validateValue(it,it.initialValue)}
                values.putAll(fields.associate {it.id to it.initialValue})
            }
            else -> error("无效的组操作")
        }
    }
    private fun configFields()=ScriptUiConfigSnapshot.fields(definition.fields.map(::effective))
    fun exportConfig(): String {
        val fields=configFields()
        val current=fields.associate {f -> f.id to (values[f.id] ?: f.initialValue).also {inputs.validateValue(f,it)}}
        return ScriptUiConfigSnapshot.encode(ScriptUiConfigSnapshot.shape(fields,::step),current)
    }
    fun restoreConfig(text: String) {
        val fields=configFields()
        val restored=ScriptUiConfigSnapshot.decode(text,ScriptUiConfigSnapshot.shape(fields,::step),fields.map {it.id}.toSet())
        fields.forEach {inputs.validateValue(it,restored.getValue(it.id))}
        values.putAll(restored)
    }

    fun presentation(field: UiField): UiPresentation? = presentations[field.id] ?: field.ui

    fun read(id: String, property: ScriptUiRuntimeProperty): String {
        val field = definition.fields.singleOrNull { it.id == id }?.let(::effective) ?: error("界面控件不存在：$id")
        if(property.input) return inputs.read(field,property.operation)
        if(property.operation in ScriptUiListCommands.reads) return ScriptUiListCommands.read(field,items[id] ?: field.options,values[id] ?: field.initialValue,property.operation)
        return when (property) {
            ScriptUiRuntimeProperty.VALUE, ScriptUiRuntimeProperty.TEXT -> values[id] ?: field.initialValue
            ScriptUiRuntimeProperty.VISIBLE -> (visibility[id] ?: field.ui?.visible ?: true).toString()
            ScriptUiRuntimeProperty.ENABLED -> (enabled[id] ?: field.ui?.enabled ?: true).toString()
            ScriptUiRuntimeProperty.ITEMS -> {
                require(field.kind in setOf("choice", "multiChoice")) { "控件没有选项" }
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
        inputs.reset()
    }

    fun apply(id: String, operation: String, value: String): UiField {
        val field = definition.fields.singleOrNull { it.id == id }?.let(::effective) ?: error("界面控件不存在：$id")
        if(operation in ScriptUiRuntimeInputs.writes) {
            inputs.apply(field,operation,value,values[id] ?: field.initialValue)
            return effective(field)
        }
        if(operation in ScriptUiListCommands.writes) {
            val (options,selected)=ScriptUiListCommands.options(field,items[id] ?: field.options,values[id] ?: field.initialValue,operation,value)
            items[id]=options; values[id]=selected
            return field
        }
        when (operation) {
            "value", "text" -> {
                when (field.kind) {
                    "multiChoice" -> uiSelections(value, items[id] ?: field.options, field.required)
                    "number" -> uiDecimal(value, field.minimum, field.maximum)
                    "integer" -> require(value.toLongOrNull()?.let { it in (field.minimum ?: -1_000_000_000)..(field.maximum ?: 1_000_000_000) } == true)
                    "boolean" -> require(value in setOf("true", "false"))
                    "choice" -> require(value in (items[id] ?: field.options))
                    else -> require(value.length <= 2048 && '\u0000' !in value)
                }
                if(field.control==UiControl.SLIDER) inputs.validateValue(field,value)
                values[id] = value
            }
            "visible", "enabled" -> {
                require(value in setOf("true", "false"))
                (if (operation == "visible") visibility else enabled)[id] = value == "true"
            }
            "items" -> {
                require(field.kind in setOf("choice", "multiChoice"))
                val options = value.split('\n')
                require(options.size in 1..64 && options.distinct().size == options.size &&
                    options.all { it.length in 1..128 && it.none(Char::isISOControl) })
                val next = if (field.kind == "multiChoice") uiSelectionsJson(uiSelections(values[id] ?: field.initialValue, items[id] ?: field.options).filter { it in options }.let { if (field.required && it.isEmpty()) listOf(options.first()) else it }) else (values[id]?.takeIf { it in options } ?: options.first())
                items[id] = options
                values[id] = next
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

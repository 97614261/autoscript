package com.autoscript.script.ui

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.Strictness

/** Runtime-only inputs. Validate before publishing; never silently clamp values. */
internal class ScriptUiRuntimeInputs {
    private val fields=mutableMapOf<String,UiField>()
    private val hints=mutableMapOf<String,String>()
    private val steps=mutableMapOf<String,Long>()
    fun effective(field: UiField)=fields[field.id] ?: field
    fun hint(field: UiField)=hints[field.id] ?: field.label
    fun step(field: UiField)=steps[field.id] ?: 1L
    fun reset() {fields.clear(); hints.clear(); steps.clear()}
    fun low(field: UiField)=field.minimum ?: if(field.control in setOf(UiControl.SLIDER,UiControl.PROGRESS)) 0 else -1_000_000_000
    fun high(field: UiField)=field.maximum ?: if(field.control in setOf(UiControl.SLIDER,UiControl.PROGRESS)) 100 else 1_000_000_000
    fun read(field: UiField,property: String): String=when(property) {
        "hint" -> { require(editable(field)); hint(field) }
        "minimum" -> {require(numeric(field)); low(field).toString()}
        "maximum" -> {require(numeric(field)); high(field).toString()}
        "step" -> {require(field.control==UiControl.SLIDER); step(field).toString()}
        else -> error("输入属性无效")
    }
    fun apply(original: UiField,operation: String,text: String,currentValue: String) {
        val field=effective(original)
        if(operation=="hint") {
            require(editable(field) && text.codePointCount(0,text.length)<=256 && '\u0000' !in text &&
                text.toByteArray(Charsets.UTF_8).toString(Charsets.UTF_8)==text)
            hints[field.id]=text; return
        }
        require(numeric(field))
        fun integer(text: String)=text.toLongOrNull()?.also {require(it in -1_000_000_000..1_000_000_000)} ?: error("范围需为整数")
        var low=low(field); var high=high(field); var step=step(field)
        when(operation) {
            "minimum" -> low=integer(text)
            "maximum" -> high=integer(text)
            "step" -> {require(field.control==UiControl.SLIDER); step=integer(text)}
            "range" -> {
                require(text.length<=256 && text.none {it=='['})
                // Flat three-number object, bounded before the JSON parser.
                require(text.count {it=='{'}==1 && text.count {it=='}'}==1)
                val json=GsonBuilder().setStrictness(Strictness.STRICT).create().fromJson(text,JsonObject::class.java)
                require(json!=null && json.keySet().let {it==setOf("minimum","maximum") || it==setOf("minimum","maximum","step")})
                fun number(key: String): Long {val v=json[key]; require(v.isJsonPrimitive && v.asJsonPrimitive.isNumber); return integer(v.toString())}
                low=number("minimum"); high=number("maximum")
                if(json.has("step")) {require(field.control==UiControl.SLIDER); step=number("step")}
            }
            else -> error("输入操作无效")
        }
        require(low<=high)
        if(field.control==UiControl.PROGRESS) require(low==0L && high in 1..1_000_000)
        if(field.control==UiControl.SLIDER) require(high>low && step in 1..1_000_000 && (high-low)%step==0L && (high-low)/step<=1_000_000)
        val next=field.copy(minimum=low,maximum=high)
        validateValue(next,currentValue,step)
        fields[field.id]=next
        if(field.control==UiControl.SLIDER) steps[field.id]=step
    }
    fun validateValue(field: UiField,value: String,step: Long=step(field)) {
        if(field.kind=="choice" && !field.required && value.isEmpty()) return
        ScriptUiDefinition(fields=listOf(field),version=if(field.ui==null) 1 else 2).validateValues(mapOf(field.id to value))
        if(field.control==UiControl.SLIDER) {
            val number=value.toLongOrNull() ?: error("滑块值无效")
            require((number-low(field))%step==0L) {"滑块值不符合步长"}
        }
    }
    companion object {
        val writes=setOf("hint","minimum","maximum","step","range")
        val reads=setOf("hint","minimum","maximum","step")
        fun editable(field: UiField)=field.control in setOf(UiControl.INPUT,UiControl.PASSWORD,UiControl.TEXTAREA,UiControl.NUMBER,UiControl.DECIMAL)
        fun numeric(field: UiField)=field.kind in setOf("integer","number")
    }
}

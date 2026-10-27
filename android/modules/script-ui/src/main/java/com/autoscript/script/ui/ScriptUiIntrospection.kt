package com.autoscript.script.ui

import com.google.gson.JsonArray
import com.google.gson.JsonObject

/** Definition metadata only: never exports values, event bindings or password contents. */
internal object ScriptUiIntrospection {
    fun pages(definition:ScriptUiDefinition):String=JsonArray().apply {
        definition.pages.forEach {page->add(JsonObject().apply {addProperty("id",page.id);addProperty("title",page.title);addProperty("width",page.width);addProperty("height",page.height)})}
    }.toString().also(::bounded)
    fun controls(definition:ScriptUiDefinition,pageId:String):String {
        require(definition.pages.any {it.id==pageId}) {"UI_PAGE_NOT_FOUND"}
        return JsonObject().apply {
            addProperty("pageId",pageId)
            add("controls",JsonArray().apply {
                definition.fields.filter {(it.ui?.pageId ?: definition.pages.first().id)==pageId}.forEach {field->
                    add(JsonObject().apply {addProperty("id",field.id);addProperty("control",field.control.name);addProperty("input",field.input)})
                }
            })
        }.toString().also(::bounded)
    }
    private fun bounded(value:String) {require(value.toByteArray(Charsets.UTF_8).size<=32768) {"UI_METADATA_TOO_LARGE"}}
}

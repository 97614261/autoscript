package com.autoscript.script.ui

import com.google.gson.JsonObject
import com.google.gson.JsonArray
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.Strictness
import java.io.StringReader
import java.security.MessageDigest

/** Parameter data only. No passwords, event references, layout, or implicit filesystem access. */
internal object ScriptUiConfigSnapshot {
    const val MAX_BYTES=8192
    fun fields(fields: List<UiField>)=fields.filter {it.input && it.control!=UiControl.PASSWORD}.sortedBy {it.id}
    fun shape(fields: List<UiField>,step: (UiField)->Long): String {
        val digest=MessageDigest.getInstance("SHA-256")
        digest.update("zhigou-ui-config-v1".toByteArray(Charsets.US_ASCII))
        fields(fields).forEach {f ->
            val row=JsonObject().apply {
                addProperty("id",f.id); addProperty("kind",f.kind); addProperty("control",f.control.name); addProperty("required",f.required)
                add("options",JsonArray().apply {f.options.forEach {add(it)}})
                addProperty("minimum",f.minimum); addProperty("maximum",f.maximum); addProperty("step",step(f))
            }.toString().toByteArray(Charsets.UTF_8)
            digest.update(row.size.toString().toByteArray(Charsets.US_ASCII)); digest.update(':'.code.toByte()); digest.update(row)
        }
        return digest.digest().joinToString("") {"%02x".format(it)}
    }
    fun encode(hash: String,values: Map<String,String>): String = JsonObject().apply {
        addProperty("formatVersion",1); addProperty("schemaHash",hash)
        add("values",JsonObject().apply {values.toSortedMap().forEach { (id,value)->addProperty(id,value)}})
    }.toString().also {require(it.toByteArray(Charsets.UTF_8).size<=MAX_BYTES) {"页面参数超过8192字节，不保存截断配置"}}
    fun decode(text: String,hash: String,ids: Set<String>): Map<String,String> {
        require(text.toByteArray(Charsets.UTF_8).size<=MAX_BYTES && text.toByteArray(Charsets.UTF_8).toString(Charsets.UTF_8)==text)
        val values=linkedMapOf<String,String>()
        val seen=mutableSetOf<String>()
        JsonReader(StringReader(text)).use {reader ->
            reader.strictness=Strictness.STRICT
            require(reader.peek()==JsonToken.BEGIN_OBJECT); reader.beginObject()
            while(reader.hasNext()) {
                val key=reader.nextName(); require(seen.add(key))
                when(key) {
                    "formatVersion" -> {require(reader.peek()==JsonToken.NUMBER); require(reader.nextString()=="1")}
                    "schemaHash" -> {require(reader.peek()==JsonToken.STRING); require(reader.nextString()==hash) {"页面参数结构已变化，不能恢复此配置"}}
                    "values" -> {
                        require(reader.peek()==JsonToken.BEGIN_OBJECT); reader.beginObject()
                        while(reader.hasNext()) {
                            require(values.size<MAX_SCRIPT_UI_CONTROLS)
                            val id=reader.nextName(); require(id in ids && !values.containsKey(id))
                            require(reader.peek()==JsonToken.STRING)
                            values[id]=reader.nextString().also {require(it.toByteArray(Charsets.UTF_8).toString(Charsets.UTF_8)==it)}
                        }
                        reader.endObject()
                    }
                    else -> error("页面配置含未知字段")
                }
            }
            reader.endObject(); require(reader.peek()==JsonToken.END_DOCUMENT)
        }
        require(seen==setOf("formatVersion","schemaHash","values") && values.keys==ids)
        return values
    }
}

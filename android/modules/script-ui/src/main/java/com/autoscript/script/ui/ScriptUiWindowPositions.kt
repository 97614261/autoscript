package com.autoscript.script.ui

import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.StringReader

/** Actual screen-pixel coordinates, independent of saved design coordinates. */
class ScriptUiWindowPositions {
    private val positions=mutableMapOf<String,Pair<Int,Int>>()
    fun reset() {positions.clear()}
    fun locate(kind: String, bounds: UiScreenBounds, size: UiWindowSize): Pair<Int,Int> {
        require(kind in setOf("window","bubble"))
        val requested=positions[kind] ?: if(kind=="window") bounds.centered(size)
            else (bounds.left+bounds.width-size.width to bounds.top+bounds.height/2)
        return bounds.clamp(requested.first,requested.second,size).also {positions[kind]=it}
    }
    fun move(kind: String, x: Int, y: Int, bounds: UiScreenBounds, size: UiWindowSize): Pair<Int,Int> {
        require(kind in setOf("window","bubble"))
        return bounds.clamp(x,y,size).also {positions[kind]=it}
    }
    fun apply(kind: String, value: String, bounds: UiScreenBounds, size: UiWindowSize): Pair<Int,Int> {
        require(value.toByteArray(Charsets.UTF_8).size<=128)
        val result=mutableMapOf<String,Int>()
        JsonReader(StringReader(value)).use {reader ->
            reader.strictness=Strictness.STRICT; reader.beginObject()
            while(reader.hasNext()) {
                val key=reader.nextName()
                require(key in setOf("x","y") && key !in result && reader.peek()==JsonToken.NUMBER)
                val number=reader.nextString()
                require(Regex("0|[1-9][0-9]{0,4}").matches(number))
                result[key]=requireNotNull(number.toIntOrNull()).also {require(it in 0..32768)}
            }
            reader.endObject(); require(reader.peek()==JsonToken.END_DOCUMENT && result.keys==setOf("x","y"))
        }
        return move(kind,result.getValue("x"),result.getValue("y"),bounds,size)
    }
    fun snapshot(kind: String, bounds: UiScreenBounds, size: UiWindowSize, visible: Boolean): String {
        val (x,y)=locate(kind,bounds,size)
        return "{\"x\":$x,\"y\":$y,\"width\":${size.width},\"height\":${size.height},\"visible\":$visible}"
    }
}

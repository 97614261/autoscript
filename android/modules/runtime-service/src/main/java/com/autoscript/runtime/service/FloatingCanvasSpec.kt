package com.autoscript.runtime.service

import com.google.gson.JsonObject

internal data class FloatingShape(val kind:String,val coordinates:IntArray,val color:Int,val strokePx:Int=1)
internal data class FloatingCanvasSpec(val name:String,val x:Int,val y:Int,val width:Int,val height:Int,
    val text:String,val fontPx:Int,val textColor:Int,val background:Int,val imagePath:String,val shapes:List<FloatingShape>) {
    companion object {
        fun validName(name:String)=Regex("[A-Za-z_][A-Za-z0-9_]{0,31}").matches(name)
        fun color(value:String):Int {
            require(Regex("#[0-9a-fA-F]{6}([0-9a-fA-F]{2})?").matches(value))
            return (if(value.length==7) 0xff000000L or value.drop(1).toLong(16) else value.drop(1).toLong(16)).toInt()
        }
        fun parse(value:String):FloatingCanvasSpec {
            require(value.toByteArray(Charsets.UTF_8).size<=32768)
            val json=ProjectDataCommands.parse(value,32768).asJsonObject
            require(json.keySet()==setOf("name","x","y","width","height","text","fontPx","textColor","background","imagePath","shapesJson"))
            val name=text(json,"name",32); require(validName(name))
            val image=text(json,"imagePath",256)
            require(image.isEmpty() || (image.startsWith("assets/images/") && image.split('/').all {it.isNotEmpty() && !it.startsWith('.') && it.none {c->c.isISOControl() || c in "\\:*?\"<>|"}}))
            return FloatingCanvasSpec(name,number(json,"x",-1,8192),number(json,"y",-1,8192),number(json,"width",1,2048),number(json,"height",1,2048),text(json,"text",4096),number(json,"fontPx",8,96),color(text(json,"textColor",9)),color(text(json,"background",9)),image,shapes(text(json,"shapesJson",8192)))
        }
        private fun text(json:JsonObject,key:String,max:Int):String {
            val value=json[key]; require(value.isJsonPrimitive && value.asJsonPrimitive.isString)
            return value.asString.also {require(it.toByteArray(Charsets.UTF_8).size<=max)}
        }
        private fun number(json:JsonObject,key:String,min:Int,max:Int):Int {
            val value=json[key]; require(value.isJsonPrimitive && value.asJsonPrimitive.isNumber)
            return requireNotNull(value.toString().toIntOrNull()).also {require(it in min..max)}
        }
        private fun shapes(value:String):List<FloatingShape> {
            val rows=ProjectDataCommands.parse(value,8192).asJsonArray; require(rows.size()<=32)
            return rows.map {row ->
                val objectValue=row.asJsonObject
                val kind=text(objectValue,"kind",16)
                val keys=when(kind) {"line"->listOf("x1","y1","x2","y2","strokePx"); "rect"->listOf("x","y","width","height"); "circle"->listOf("x","y","radius"); else->error("unsupported shape")}
                require(objectValue.keySet()==(keys+listOf("kind","color")).toSet())
                val coordinates=keys.filter {it!="strokePx"}.map {number(objectValue,it,if(it in setOf("width","height","radius")) 1 else 0,4096)}.toIntArray()
                FloatingShape(kind,coordinates,color(text(objectValue,"color",9)),if(kind=="line") number(objectValue,"strokePx",1,32) else 1)
            }
        }
    }
}

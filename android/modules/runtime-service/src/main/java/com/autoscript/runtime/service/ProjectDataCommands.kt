package com.autoscript.runtime.service

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** Fixed private-data operations; no reflection, arbitrary root, or executable payloads. */
internal class ProjectDataCommands(private val store: ProjectDataStore, private val check: () -> Unit,
    private val commit: (() -> Unit) -> Unit) {
    fun execute(op: Int, path: String, payload: ByteArray): ByteArray {
        if (payload.size > MAX_WIRE_BYTES) fail()
        val args = parse(decode(payload), MAX_WIRE_BYTES).let { if (!it.isJsonArray) fail() else it.asJsonArray }
        val arity = when(op) {14110,14114,14115,14117,14119,14123,14124,14144,14145,14146 -> 1; 14118,14121,14122,14126 -> 2; in 14111..14113,14116,14120,14125,in 14140..14143 -> 0; else -> fail()}
        if(args.size()!=arity) fail()
        check()
        val result: JsonElement = when(op) {
            14140 -> JsonPrimitive(store.fileSize(path,check))
            14141 -> { store.fileSize(path,check); JsonPrimitive(path.substringAfterLast('/').substringAfterLast('.', "")) }
            14142 -> JsonPrimitive(store.directoryStats(path,check))
            14143 -> JsonPrimitive(store.toBase64(path,check))
            14144 -> { store.fromBase64(path,text(args,0),check,commit); JsonPrimitive(1) }
            14145 -> { store.zip(path,text(args,0,256),check,commit); JsonPrimitive(1) }
            14146 -> { store.unzip(path,text(args,0,256),check,commit); JsonPrimitive(1) }
            14110 -> { val old = if(store.exists(path,false,check)) read(path) else ""; write(path,old+text(args,0)); JsonPrimitive(1) }
            14111 -> JsonPrimitive(if(store.exists(path,false,check)) 1 else 0)
            14112 -> JsonPrimitive(if(store.exists(path,true,check)) 1 else 0)
            14113 -> {store.mkdir(path,check,commit); JsonPrimitive(1)}
            14114 -> {store.write(text(args,0,256),store.read(path,check),check,commit,exclusive=true); JsonPrimitive(1)}
            14115 -> {store.move(path,text(args,0,256),check,commit); JsonPrimitive(1)}
            14116 -> {store.rmdir(path,check,commit); JsonPrimitive(1)}
            in 14117..14121 -> line(op,path,args)
            in 14122..14126 -> kv(op,path,args)
            else -> fail()
        }
        check()
        if(result.isJsonPrimitive && result.asJsonPrimitive.isString && result.asString.toByteArray(Charsets.UTF_8).size>65_536) fail()
        val bytes=result.toString().toByteArray(Charsets.UTF_8)
        if(bytes.size>MAX_WIRE_BYTES) fail()
        return bytes
    }
    private fun read(path: String)=store.read(path,check).toString(Charsets.UTF_8)
    private fun write(path: String,text: String)=store.write(path,text.toByteArray(Charsets.UTF_8),check,commit)
    private fun text(args: JsonArray,index: Int,max: Int=65_536): String {
        val value=args[index]
        if(!value.isJsonPrimitive || !value.asJsonPrimitive.isString) fail()
        return value.asString.also {if(it.toByteArray(Charsets.UTF_8).size>max) fail()}
    }
    private fun integer(args: JsonArray,index: Int): Long {
        val value=args[index]
        if(!value.isJsonPrimitive || !value.asJsonPrimitive.isNumber) fail()
        return value.toString().toLongOrNull() ?: fail()
    }
    private fun line(op: Int,path: String,args: JsonArray): JsonElement {
        val lines=read(path).split('\n').map {it.removeSuffix("\r")}.toMutableList()
        if(lines.lastOrNull()=="") lines.removeAt(lines.lastIndex)
        if(op==14120) return JsonPrimitive(lines.size)
        val n=integer(args,0)
        val maximum=lines.size+(if(op==14118) 1 else 0)
        if(n !in 1..maximum.toLong()) fail()
        val index=(n-1).toInt()
        if(op==14117) return JsonPrimitive(lines[index])
        when(op) {
            14119 -> lines.removeAt(index)
            else -> {
                val value=text(args,1)
                if('\r' in value || '\n' in value) fail()
                if(op==14118) lines.add(index,value) else lines[index]=value
            }
        }
        // A terminal LF makes an inserted empty final line distinguishable from zero rows.
        write(path,if(lines.isEmpty()) "" else lines.joinToString("\n",postfix="\n"))
        return JsonPrimitive(1)
    }
    private fun kv(op: Int,path: String,args: JsonArray): JsonElement {
        val objectValue=if(store.exists(path,false,check)) parse(read(path)).let {if(!it.isJsonObject) fail() else it.asJsonObject} else JsonObject()
        if(op==14125) return JsonPrimitive(JsonArray().apply {objectValue.keySet().sorted().forEach {add(it)}}.toString())
        val key=text(args,0,128).also {if(it.isEmpty() || it.any(Char::isISOControl)) fail()}
        if(op==14123) return JsonPrimitive((objectValue[key] ?: JsonNull.INSTANCE).toString())
        val result=when(op) {
            14122 -> {objectValue.add(key,parse(text(args,1))); 1L}
            14124 -> if(objectValue.remove(key)!=null) 1L else 0L
            14126 -> {
                val current=objectValue[key]
                val number=if(current==null) 0L else {
                    if(!current.isJsonPrimitive || !current.asJsonPrimitive.isNumber) fail()
                    current.toString().toLongOrNull() ?: fail()
                }
                val delta=integer(args,1)
                if(delta !in -9_007_199_254_740_991..9_007_199_254_740_991) fail()
                val sum=try {Math.addExact(number,delta)} catch(_: ArithmeticException) {fail()}
                objectValue.addProperty(key,sum); sum
            }
            else -> fail()
        }
        validate(objectValue,0,intArrayOf(0))
        write(path,objectValue.toString())
        return JsonPrimitive(result)
    }
    private fun fail(): Nothing=throw ProjectDataFailure("FILE_REQUEST_INVALID")
    companion object {
        const val MAX_WIRE_BYTES=393_728 // bounded escaping of at most 64 KiB text plus fixed metadata
        private fun decode(bytes: ByteArray): String=try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        } catch(_: java.nio.charset.CharacterCodingException) {throw ProjectDataFailure("FILE_UTF8_INVALID")}
        internal fun parse(text: String,max: Int=65_536): JsonElement {
            if(text.toByteArray(Charsets.UTF_8).size>max) throw ProjectDataFailure("FILE_TOO_LARGE")
            var depth=0; var quoted=false; var escaped=false
            text.forEach {c ->
                if(quoted) {if(escaped) escaped=false else if(c=='\\') escaped=true else if(c=='"') quoted=false}
                else if(c=='"') quoted=true
                else if(c=='[' || c=='{') {depth++; if(depth>16) throw ProjectDataFailure("FILE_REQUEST_INVALID")}
                else if(c==']' || c=='}') {depth--; if(depth<0) throw ProjectDataFailure("FILE_REQUEST_INVALID")}
            }
            val value=try {com.google.gson.GsonBuilder().setStrictness(com.google.gson.Strictness.STRICT).create().fromJson(text,JsonElement::class.java)}
                catch(_: Exception) {throw ProjectDataFailure("FILE_REQUEST_INVALID")}
            if(value==null) throw ProjectDataFailure("FILE_REQUEST_INVALID")
            validate(value,0,intArrayOf(0))
            return value
        }
        private fun validate(value: JsonElement,depth: Int,count: IntArray) {
            if(depth>16) throw ProjectDataFailure("FILE_REQUEST_INVALID")
            val children=when {value.isJsonArray -> value.asJsonArray.toList(); value.isJsonObject -> {
                value.asJsonObject.keySet().forEach {if(it.isEmpty() || it.toByteArray(Charsets.UTF_8).size>128 || it.any(Char::isISOControl)) throw ProjectDataFailure("FILE_REQUEST_INVALID")}
                value.asJsonObject.entrySet().map {it.value}
            }; else -> emptyList()}
            count[0]+=children.size
            if(count[0]>1024) throw ProjectDataFailure("FILE_REQUEST_INVALID")
            children.forEach {validate(it,depth+1,count)}
        }
    }
}

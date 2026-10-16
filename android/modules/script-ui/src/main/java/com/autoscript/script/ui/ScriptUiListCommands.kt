package com.autoscript.script.ui

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.Strictness

/** Lists are data; indices are 1-based and independent from option text. */
object ScriptUiListCommands {
    val writes=setOf("itemAdd","itemInsert","itemSet","itemRemove","itemsClear","selectedIndex","selectedIndices")
    val reads=setOf("itemCount","selectedIndex","selectedIndices")
    fun options(field: UiField, current: List<String>, selection: String, operation: String, value: String): Pair<List<String>,String> {
        require(field.kind in setOf("choice","multiChoice")) { "控件没有选项" }
        require(value.toByteArray(Charsets.UTF_8).size<=8192 && '\u0000' !in value)
        val next=current.toMutableList()
        var selected=if(field.kind=="multiChoice") uiSelections(selection,current).toMutableList() else mutableListOf(selection).apply {removeAll {it !in current}}
        fun index(text: String,limit: Int): Int {
            val number=text.toIntOrNull() ?: error("选项索引需为整数")
            require(number.toString()==text && number in 1..limit)
            return number-1
        }
        fun item(text: String): String=text.also {require(it.length in 1..128 && it.none(Char::isISOControl) && it.toByteArray(Charsets.UTF_8).toString(Charsets.UTF_8)==it)}
        when(operation) {
            "itemAdd" -> next.add(item(value))
            "itemInsert","itemSet" -> {
                preflight(value)
                val json=GsonBuilder().setStrictness(Strictness.STRICT).create().fromJson(value,JsonObject::class.java)
                require(json!=null && json.keySet()==setOf("index","text") && json["index"].isJsonPrimitive && json["index"].asJsonPrimitive.isNumber &&
                    json["text"].isJsonPrimitive && json["text"].asJsonPrimitive.isString)
                val at=index(json["index"].toString(),current.size+if(operation=="itemInsert") 1 else 0)
                val text=item(json["text"].asString)
                if(operation=="itemInsert") next.add(at,text) else {
                    val old=next[at]; next[at]=text; selected=selected.map {if(it==old) text else it}.toMutableList()
                }
            }
            "itemRemove" -> next.removeAt(index(value,current.size))
            "itemsClear" -> {require(value.isEmpty()); next.clear()}
            "selectedIndex" -> {
                require(field.kind=="choice")
                selected=if(value=="0" && !field.required) mutableListOf() else mutableListOf(current[index(value,current.size)])
            }
            "selectedIndices" -> {
                require(field.kind=="multiChoice" && value.length<=512)
                preflight(value)
                val array=GsonBuilder().setStrictness(Strictness.STRICT).create().fromJson(value,JsonArray::class.java)
                require(array!=null && array.size()<=64 && array.all {it.isJsonPrimitive && it.asJsonPrimitive.isNumber})
                val indexes=array.map {index(it.toString(),current.size)}
                require(indexes.distinct().size==indexes.size)
                selected=indexes.sorted().map {current[it]}.toMutableList()
                require(!field.required || selected.isNotEmpty())
            }
            else -> error("选项操作无效")
        }
        require(next.size<=64 && next.distinct().size==next.size && (!field.required || next.isNotEmpty()))
        selected.removeAll {it !in next}
        if(field.required && selected.isEmpty()) selected.add(next.first())
        val text=if(field.kind=="multiChoice") uiSelectionsJson(next.filter {it in selected}) else selected.firstOrNull().orEmpty()
        return next to text
    }
    fun read(field: UiField,options: List<String>,selection: String,property: String): String {
        require(field.kind in setOf("choice","multiChoice"))
        return when(property) {
            "itemCount" -> options.size.toString()
            "selectedIndex" -> {require(field.kind=="choice"); (options.indexOf(selection)+1).toString()}
            "selectedIndices" -> {
                require(field.kind=="multiChoice")
                val selected=uiSelections(selection,options)
                JsonArray().apply {options.forEachIndexed {i,v->if(v in selected) add(i+1)}}.toString()
            }
            else -> error("选项属性无效")
        }
    }
    private fun preflight(value: String) {
        var depth=0; var quoted=false; var escaped=false
        value.forEach {c ->
            if(quoted) {if(escaped) escaped=false else if(c=='\\') escaped=true else if(c=='"') quoted=false}
            else if(c=='"') quoted=true
            else if(c=='[' || c=='{') {depth++; require(depth<=2) {"选项参数嵌套超出范围"}}
            else if(c==']' || c=='}') {depth--; require(depth>=0)}
        }
        require(depth==0 && !quoted)
    }
}

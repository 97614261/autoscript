package com.autoscript.studio

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autoscript.project.store.ProjectSnapshot
import com.autoscript.project.store.ProjectVariableType
import com.autoscript.studio.generated.BlockContract
import com.google.gson.JsonObject
import com.google.gson.JsonParser

internal val floatingKinds=setOf("floating.show","floating.hide","floating.close","floating.bounds")

@Composable
internal fun VisualFloatingDialog(contract:BlockContract,args:JsonObject,project:ProjectSnapshot,flow:String,
    onManageVariables:(()->Unit)?,onDismiss:()->Unit,onConfirm:(JsonObject)->Unit,confirmLabel:String) {
    val initial=remember(contract.kind,args) {blockPropertyTexts(contract,args)}
    var inputs by remember(initial) {mutableStateOf(initial)}
    var error by remember {mutableStateOf<String?>(null)}
    var discard by remember {mutableStateOf(false)}
    var fullScreen by remember {mutableStateOf(false)}
    val variables=calculationVariables(project.manifest.variables,flow,project.manifest.flows).filter {it.type==ProjectVariableType.STRING}
    fun dismiss() {if(inputs!=initial) discard=true else onDismiss()}
    fun set(key:String,value:String) {inputs=inputs+(key to value); error=null}
    StudioPanelDialog(contract.title,::dismiss,confirmLabel,onConfirm={
        error=floatingArgumentError(contract.kind,inputs,project.manifest.resources,variables.map {it.name}.toSet())
        if(error==null) {
            val parsed=parseBlockArguments(contract,inputs,null,emptyMap(),project.manifest.resources)
            error=parsed.second; parsed.first?.let(onConfirm)
        }
    },fullScreen=fullScreen,onToggleFullScreen={fullScreen=!fullScreen},compactHeader=true) {
        Column(Modifier.fillMaxWidth().heightIn(max=if(fullScreen) 900.dp else 400.dp).verticalScroll(rememberScrollState()).padding(10.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
            DesignerInput("名称",inputs["name"].orEmpty(),true,Modifier.fillMaxWidth()) {set("name",it)}
            if(contract.kind=="floating.show") {
                for(pair in listOf("x" to "y","width" to "height")) Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                    for(key in listOf(pair.first,pair.second)) DesignerInput(when(key){"x"->"X · -1居中";"y"->"Y · -1居中";"width"->"宽px";else->"高px"},inputs[key].orEmpty(),true,Modifier.weight(1f),numeric=true) {set(key,it)}
                }
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(3.dp)) {
                    listOf("240" to "80","320" to "120","480" to "180").forEach {(w,h)->TextButton(onClick={set("width",w);set("height",h)}) {Text("${w}×${h}",fontSize=10.sp)}}
                    TextButton(onClick={set("x","-1");set("y","-1")}) {Text("居中",fontSize=10.sp)}
                }
                DesignerInput("文字",inputs["text"].orEmpty(),true,Modifier.fillMaxWidth()) {set("text",it)}
                DesignerChoice("文字来源",inputs["textVariable"].orEmpty().ifEmpty {"固定文字"},listOf("" to "固定文字")+variables.map {it.name to it.name},true) {set("textVariable",it)}
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                    DesignerInput("字号px",inputs["fontPx"].orEmpty(),true,Modifier.weight(0.7f),numeric=true) {set("fontPx",it)}
                    DesignerInput("文字颜色",inputs["textColor"].orEmpty(),true,Modifier.weight(1f)) {set("textColor",it)}
                    DesignerInput("背景",inputs["background"].orEmpty(),true,Modifier.weight(1f)) {set("background",it)}
                }
                DesignerChoice("项目图片",inputs["imagePath"].orEmpty().ifEmpty {"不显示图片"},listOf("" to "不显示图片")+project.manifest.resources.filter {it.get("kind")?.asString=="image"}.map {val p=it.get("path").asString;p to p.substringAfterLast('/')},true) {set("imagePath",it)}
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(2.dp)) {
                    for(kind in listOf("line","rect","circle")) TextButton(onClick={
                        runCatching {
                            val rows=parseFloatingShapes(inputs["shapesJson"].orEmpty())
                            require(rows.size()<32)
                            val row=when(kind) {"line"->"{\"kind\":\"line\",\"x1\":10,\"y1\":10,\"x2\":100,\"y2\":60,\"strokePx\":2,\"color\":\"#FF66CCFF\"}";"rect"->"{\"kind\":\"rect\",\"x\":10,\"y\":10,\"width\":80,\"height\":40,\"color\":\"#FF66CCFF\"}";else->"{\"kind\":\"circle\",\"x\":50,\"y\":50,\"radius\":20,\"color\":\"#FF66CCFF\"}"}
                            rows.add(JsonParser.parseString(row)); set("shapesJson",rows.toString())
                        }.onFailure {error="先修正绘图JSON，最多32个图形"}
                    }) {Text(when(kind){"line"->"＋线段";"rect"->"＋矩形";else->"＋圆"},fontSize=10.sp)}
                    TextButton(onClick={set("shapesJson","[]")}) {Text("清空",fontSize=10.sp)}
                }
                DesignerInput("绘图JSON · 相对窗内像素",inputs["shapesJson"].orEmpty(),true,Modifier.fillMaxWidth()) {set("shapesJson",it)}
                Text("触摸穿透 · 最多8窗 · 图片contain显示；浮窗会进入截图，识图前可隐藏。",fontSize=10.sp)
            }
            if(contract.kind=="floating.bounds") DesignerChoice("结果字符变量",inputs["resultVariable"].orEmpty(),variables.map {it.name to it.name},true) {set("resultVariable",it)}
            onManageVariables?.let {TextButton(onClick=it) {Text("维护变量",fontSize=10.sp)}}
            error?.let {Text(it,fontSize=11.sp)}
        }
    }
    if(discard) StudioConfirmDialog(onDismissRequest={discard=false},title={Text("放弃未保存参数？")},text={Text("不会创建、显示或修改运行浮窗。")},actions=listOf(StudioConfirmAction("继续编辑",{discard=false}),StudioConfirmAction("放弃",{discard=false;onDismiss()},destructive=true)))
}

internal fun floatingArgumentError(kind:String,inputs:Map<String,String>,resources:List<JsonObject>,variables:Set<String>):String? = try {
    require(Regex("[A-Za-z_][A-Za-z0-9_]{0,31}").matches(inputs["name"].orEmpty())) {"名称使用字母/数字/下划线，最多32字"}
    if(kind=="floating.bounds") require(inputs["resultVariable"] in variables) {"请选择字符结果变量"}
    if(kind=="floating.show") {
        for((key,min,max) in listOf(Triple("x",-1,8192),Triple("y",-1,8192),Triple("width",1,2048),Triple("height",1,2048),Triple("fontPx",8,96))) require(inputs[key]?.toIntOrNull() in min..max) {"位置、尺寸或字号超出范围"}
        require(inputs["text"].orEmpty().toByteArray(Charsets.UTF_8).size<=4096) {"文字最多4096字节"}
        val source=inputs["textVariable"].orEmpty(); require(source.isEmpty() || source in variables) {"请选择字符来源变量"}
        for(key in listOf("textColor","background")) require(Regex("#[0-9a-fA-F]{6}([0-9a-fA-F]{2})?").matches(inputs[key].orEmpty())) {"颜色使用#RRGGBB或#AARRGGBB"}
        val image=inputs["imagePath"].orEmpty(); require(image.isEmpty() || resources.any {it.get("kind")?.asString=="image" && it.get("path")?.asString==image}) {"请选择已声明项目图片"}
        val text=inputs["shapesJson"].orEmpty(); require(text.toByteArray(Charsets.UTF_8).size<=8192) {"绘图数据最多8192字节"}
        val rows=parseFloatingShapes(text); require(rows.size()<=32) {"最多32个图形"}
        for(row in rows) {
            val obj=row.asJsonObject; val shape=obj.get("kind")?.asString
            val keys=when(shape){"line"->setOf("x1","y1","x2","y2","strokePx");"rect"->setOf("x","y","width","height");"circle"->setOf("x","y","radius");else->error("只支持线、矩形和圆")}
            require(obj.keySet()==keys+setOf("kind","color")) {"图形字段不完整"}
            require(Regex("#[0-9a-fA-F]{6}([0-9a-fA-F]{2})?").matches(obj.get("color").asString)) {"图形颜色无效"}
            for(key in keys) require(obj.get(key).toString().toIntOrNull() in (if(key in setOf("width","height","radius","strokePx")) 1 else 0)..(if(key=="strokePx") 32 else 4096)) {"图形坐标必须是有界整数"}
        }
    }
    null
} catch(error:Exception) {error.message ?: "浮窗参数无效"}

private fun parseFloatingShapes(text:String):com.google.gson.JsonArray {
    require(text.toByteArray(Charsets.UTF_8).size<=8192)
    var depth=0; var quoted=false; var escaped=false
    for(c in text) {
        if(quoted) {if(escaped) escaped=false else if(c=='\\') escaped=true else if(c=='\"') quoted=false}
        else if(c=='\"') quoted=true
        else if(c=='[' || c=='{') {depth++; require(depth<=16)}
        else if(c==']' || c=='}') {depth--; require(depth>=0)}
    }
    return JsonParser.parseString(text).asJsonArray
}

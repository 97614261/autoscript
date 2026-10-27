package com.autoscript.studio

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.gson.JsonParser
import com.autoscript.runtime.api.RecognitionPreviewReply as Reply

internal data class OcrPreviewGlyph(val text:String,val left:Int,val top:Int,val right:Int,val bottom:Int,val score:Int,val recognized:Boolean)
internal data class OcrPreviewDetails(val text:String,val coverage:Int,val score:Int,val glyphs:List<OcrPreviewGlyph>)

/** Narrow, strict preview decoder. Unknown shapes never become synthesized coordinates. */
internal fun decodeOcrPreviewDetails(text:String,width:Int,height:Int): OcrPreviewDetails? = runCatching {
    require(text.toByteArray(Charsets.UTF_8).size <= 65536 && width in 1..4096 && height in 1..4096)
    var depth=0;var quoted=false;var escaped=false
    for(c in text) { if(quoted) { if(escaped) escaped=false else if(c=='\\') escaped=true else if(c=='"') quoted=false }
        else if(c=='"') quoted=true else if(c=='{' || c=='[') { depth++;require(depth<=4) } else if(c=='}' || c==']') depth-- }
    val root=JsonParser.parseString(text).asJsonObject
    require(root.keySet()==setOf("text","coveragePermille","averageScorePermille","matches"))
    fun integer(o:com.google.gson.JsonObject,k:String,range:IntRange):Int { val p=o[k].asJsonPrimitive;require(p.isNumber && p.asString.matches(Regex("0|[1-9][0-9]*")));val n=requireNotNull(p.asString.toLongOrNull());require(n in range.first.toLong()..range.last.toLong());return n.toInt() }
    val resultText=root["text"].also { require(it.isJsonPrimitive && it.asJsonPrimitive.isString) }.asString
    val coverage=integer(root,"coveragePermille",0..1000);val score=integer(root,"averageScorePermille",0..1000)
    val matches=root["matches"].asJsonArray;require(matches.size()<=2048)
    OcrPreviewDetails(resultText,coverage,score,matches.map { value ->
        val item=value.asJsonObject;require(item.keySet()==setOf("text","left","top","right","bottom","score","recognized"))
        val label=item["text"].also { require(it.isJsonPrimitive && it.asJsonPrimitive.isString) }.asString.also { require(it.isNotEmpty() && it.toByteArray(Charsets.UTF_8).size<=256) }
        val l=integer(item,"left",0 until width);val t=integer(item,"top",0 until height)
        val r=integer(item,"right",1..width);val b=integer(item,"bottom",1..height);require(l<r && t<b)
        val recognized=item["recognized"].asJsonPrimitive.also { require(it.isBoolean) }.asBoolean
        OcrPreviewGlyph(label,l,t,r,b,integer(item,"score",0..1000),recognized)
    })
}.getOrNull()

internal fun recognitionReplyOperationMatches(expected:Int,actual:Int) = expected==actual || expected==Reply.GLYPH && actual==Reply.GLYPH_DETAILS

/** Same centered Fit transform as the image and boxes; letterbox space never selects a glyph. */
internal fun ocrGlyphAt(details: OcrPreviewDetails, width:Int,height:Int,viewWidth:Float,viewHeight:Float,x:Float,y:Float): Int {
    if(width<=0 || height<=0 || viewWidth<=0 || viewHeight<=0) return -1
    val scale=minOf(viewWidth/width,viewHeight/height)
    val px=(x-(viewWidth-width*scale)/2)/scale
    val py=(y-(viewHeight-height*scale)/2)/scale
    if(px<0 || py<0 || px>=width || py>=height) return -1
    return details.glyphs.indexOfFirst { px>=it.left && px<it.right && py>=it.top && py<it.bottom }
}

internal fun ocrPreviewReaderArguments(index:Int) = com.google.gson.JsonObject().apply {
    require(index in 1..2048)
    addProperty("shape","GLYPH_ITEM");addProperty("index",index);addProperty("field","text")
    addProperty("sourceVariable","");addProperty("resultVariable","")
}

@Composable
internal fun OcrDetailPreview(bitmap:Bitmap,details:OcrPreviewDetails,onReadResult:((Int)->Unit)?=null,compact:Boolean=false,
    lowScoreBelow:Int = 0, onResample:((OcrPreviewGlyph)->Unit)? = null) {
    var selected by remember(details) { mutableIntStateOf(-1) }
    val listState=rememberLazyListState()
    LaunchedEffect(selected,details) { if(selected in details.glyphs.indices) listState.animateScrollToItem(selected) }
    val ratio=bitmap.width.toFloat()/bitmap.height
    Box(Modifier.fillMaxWidth().heightIn(max=if(compact) 112.dp else 240.dp).aspectRatio(ratio,matchHeightConstraintsFirst=false)) {
        Image(bitmap.asImageBitmap(),"真实逐字位置",Modifier.fillMaxSize())
        Canvas(Modifier.fillMaxSize().pointerInput(details,bitmap.width,bitmap.height) {
            detectTapGestures { point -> selected=ocrGlyphAt(details,bitmap.width,bitmap.height,size.width.toFloat(),size.height.toFloat(),point.x,point.y) }
        }) {
            val scale=minOf(size.width/bitmap.width,size.height/bitmap.height)
            val ox=(size.width-bitmap.width*scale)/2;val oy=(size.height-bitmap.height*scale)/2
            details.glyphs.forEachIndexed { index,g -> drawRect(if(index==selected) Color(0xFF7657E8) else if(!g.recognized) Color(0xFFD45353) else if(g.score < lowScoreBelow) Color(0xFFD88A23) else Color(0xFF2CA674),
                Offset(ox+g.left*scale,oy+g.top*scale),Size((g.right-g.left)*scale,(g.bottom-g.top)*scale),style=Stroke(if(index==selected) 3f else 1f)) }
        }
    }
    SelectionContainer { Text("${details.glyphs.size}项 · 覆盖${details.coverage}‰ · 均分${details.score}‰\n${details.text}",fontSize=11.sp) }
    LazyColumn(Modifier.fillMaxWidth().heightIn(max=if(compact) 72.dp else 140.dp),state=listState) {
        itemsIndexed(details.glyphs) { index,g ->
            val rowText = "${if(index==selected) "▶" else ""}${index+1} ${g.text} · (${g.left},${g.top})–(${g.right},${g.bottom}) · ${g.score}‰ ${if(!g.recognized) "未识别" else if(g.score < lowScoreBelow) "低分" else "已识别"}"
            Box(Modifier.fillMaxWidth().clickable { selected=index }.semantics {
                contentDescription = "识别字形${index+1}"
                stateDescription = rowText
            }.padding(vertical=3.dp)) {
                SelectionContainer { Text(rowText, fontSize=11.sp) }
            }
        }
    }
    if(onReadResult!=null) DesignerAction("配置第${if(selected>=0) selected+1 else "—"}项结果读取",enabled=selected in details.glyphs.indices,onClick={onReadResult(selected+1)})
    if(onResample!=null) DesignerAction("回到取样并框选第${if(selected>=0) selected+1 else "—"}项",enabled=selected in details.glyphs.indices,onClick={onResample(details.glyphs[selected])})
    Text(if(onResample!=null) "红框未识别 · 橙框低分 · 点框或列表后可回到原图补样；不会猜标注。" else if(compact) "点字框或列表选中 · 原图坐标 · 读取序号不代表下一帧身份" else
        "点字框或列表联动选中；坐标可复制。快捷读取仅带入序号，运行时须先生成逐字结果；下一帧同序号不保证仍是同一字符，不会生成点击。",fontSize=10.sp)
}

package com.autoscript.studio

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autoscript.core.designsystem.AutoScriptPalette
import com.autoscript.project.store.*
import kotlin.math.max
import kotlin.math.min

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun ImageMaskDialog(bitmap:Bitmap?,initial:ProjectImageMask?=null,defaultWidth:Int=720,defaultHeight:Int=1280,
    onDismiss:()->Unit,onApply:(ProjectImageMask)->Unit) {
    var full by remember {mutableStateOf(false)}
    var width by remember {mutableStateOf((bitmap?.width ?: initial?.width ?: defaultWidth).toString())}
    var height by remember {mutableStateOf((bitmap?.height ?: initial?.height ?: defaultHeight).toString())}
    var versions by remember {mutableStateOf(listOf(initial?.strokes.orEmpty()))}
    var version by remember {mutableIntStateOf(0)}
    val strokes=versions[version]
    var mode by remember {mutableStateOf("ignore")}
    var radius by remember {mutableStateOf("4")}
    var left by remember {mutableStateOf("0")};var top by remember {mutableStateOf("0")}
    var right by remember {mutableStateOf("8")};var bottom by remember {mutableStateOf("8")}
    var original by remember {mutableStateOf(false)};var discard by remember {mutableStateOf(false)}
    var message by remember {mutableStateOf("透明格纹表示忽略；恢复只还原本次蒙版，不改变之前的处理。")}
    var viewport by remember {mutableStateOf<ImageToolViewport?>(null)}
    var drawing by remember {mutableStateOf(emptyList<ProjectMaskPoint>())}
    val candidate=runCatching {ProjectImageMask(width.toInt(),height.toInt(),strokes).also {it.encode()}}.getOrNull()
    val operation=candidate?.let {ImagePixelOperation("mask",mask=it)}
    val preview=bitmap?.let {rememberImageProcessingPreview(it,operation)}
    val dirty=strokes!=initial?.strokes.orEmpty() || width!=(bitmap?.width ?: initial?.width ?: defaultWidth).toString() || height!=(bitmap?.height ?: initial?.height ?: defaultHeight).toString()
    fun close() {if(dirty) discard=true else onDismiss()}
    fun publish(next:List<ProjectMaskStroke>) {
        try {
            if(next.isNotEmpty()) ProjectImageMask(width.toInt(),height.toInt(),next).encode()
            versions=(versions.take(version+1)+listOf(next.toList())).takeLast(33);version=versions.lastIndex
            message="已记录${next.size}笔 · 取消/多指手势不提交"
        } catch(_:Exception) {message="蒙版尺寸、笔画或计算预算无效；本次操作未提交"}
    }
    val latestPublish by rememberUpdatedState<(ProjectMaskStroke)->Unit>({publish(strokes+it)})
    val latestViewport by rememberUpdatedState(viewport)
    ProvideTextStyle(LocalTextStyle.current.copy(lineHeight=14.sp)) {
    StudioPanelDialog("模板蒙版 · 忽略动态区域",::close,fullScreen=full,onToggleFullScreen={full=!full},compactHeader=true,showFooter=false) {
        BoxWithConstraints(Modifier.fillMaxWidth().heightIn(max=if(full)1000.dp else 620.dp)) {
        val previewHeight=(maxHeight*.32f).coerceIn(48.dp,if(full)260.dp else 180.dp)
        val short = studioShortViewport(maxHeight.value)
        val previewContent: @Composable () -> Unit = {
            if(bitmap!=null) {
                val shown=if(original) bitmap else preview?.bitmap ?: bitmap
                val image=remember(shown) {shown.asImageBitmap()}
                FlowRow(Modifier.padding(horizontal=6.dp),horizontalArrangement=Arrangement.spacedBy(2.dp)) {
                    ImageToolCompactAction(if(original) "● 原图" else "原图",true) {original=true}
                    ImageToolCompactAction(if(!original) "● 蒙版图" else "蒙版图",true) {original=false}
                    ImageToolCompactAction("−",true) {viewport=viewport?.let {it.scaledBy(.5f,it.viewWidth/2,it.viewHeight/2)}}
                    ImageToolCompactAction("＋",true) {viewport=viewport?.let {it.scaledBy(2f,it.viewWidth/2,it.viewHeight/2)}}
                    ImageToolCompactAction("适应",true) {viewport=viewport?.let {glyphMakerFocus(ImageToolViewport.fit(bitmap.width,bitmap.height,it.viewWidth,it.viewHeight))}}
                }
                Canvas(Modifier.fillMaxWidth().height(previewHeight).clipToBounds().background(Color(0xff252c38))
                    .onSizeChanged { size -> if(size.width>0 && size.height>0) viewport=viewport?.resized(size.width.toFloat(),size.height.toFloat())
                        ?: glyphMakerFocus(ImageToolViewport.fit(bitmap.width,bitmap.height,size.width.toFloat(),size.height.toFloat())) }
                    .semantics {contentDescription="蒙版原像素画布"}
                    .pointerInput(bitmap,mode,radius) {
                        awaitEachGesture {
                            val down=awaitFirstDown(requireUnconsumed=false)
                            val view=latestViewport ?: return@awaitEachGesture
                            val first=view.toImage(down.position.x,down.position.y) ?: return@awaitEachGesture
                            val points=mutableListOf(ProjectMaskPoint(first.first,first.second));var currentView=view;var accepted=false
                            val r=radius.toIntOrNull()?.takeIf {it in 1..64} ?: return@awaitEachGesture
                            down.consume()
                            drawing=if(mode=="move") emptyList() else points.toList()
                            try {
                            while(true) {
                                val event=awaitPointerEvent();if(event.changes.size!=1) break
                                val change=event.changes.firstOrNull {it.id==down.id} ?: break
                                if(change.isConsumed) break
                                if(mode=="move") {val delta=change.position-change.previousPosition;currentView=currentView.panned(delta.x,delta.y);viewport=currentView}
                                else view.toImage(change.position.x,change.position.y)?.let { (x,y) ->
                                    val point=ProjectMaskPoint(x,y);if(points.last()!=point) points+=point
                                    drawing=points.toList()
                                }
                                change.consume();if(points.size>1024) break
                                if(!change.pressed) {accepted=true;break}
                            }
                            if(accepted && mode!="move") {
                                val stroke=if(mode=="rect") {
                                    val a=points.first();val b=points.last()
                                    ProjectMaskStroke("rect",false,0,listOf(ProjectMaskPoint(min(a.x,b.x),min(a.y,b.y)),
                                        ProjectMaskPoint(max(a.x,b.x)+1,max(a.y,b.y)+1)))
                                } else ProjectMaskStroke("brush",mode=="restore",r,points.toList())
                                latestPublish(stroke)
                            }
                            } finally {drawing=emptyList()}
                        }
                    }) {
                    val tile=16.dp.toPx()
                    for(y in 0..(size.height/tile).toInt()) for(x in 0..(size.width/tile).toInt())
                        if((x+y)%2==0) drawRect(Color(0xff3b4658),Offset(x*tile,y*tile),Size(tile,tile))
                    viewport?.let {v ->
                        drawImage(image,dstOffset=IntOffset(v.translateX.toInt(),v.translateY.toInt()),
                            dstSize=IntSize((bitmap.width*v.scale).toInt().coerceAtLeast(1),(bitmap.height*v.scale).toInt().coerceAtLeast(1)),filterQuality=FilterQuality.None)
                        fun center(p:ProjectMaskPoint)=Offset(v.translateX+(p.x+.5f)*v.scale,v.translateY+(p.y+.5f)*v.scale)
                        val tint=if(mode=="restore") Color(0x8848c4ff) else Color(0x88ff5d7d)
                        if(drawing.isNotEmpty()) {
                            if(mode=="rect") {
                                val a=drawing.first();val b=drawing.last()
                                drawRect(tint,Offset(v.translateX+min(a.x,b.x)*v.scale,v.translateY+min(a.y,b.y)*v.scale),
                                    Size((kotlin.math.abs(a.x-b.x)+1)*v.scale,(kotlin.math.abs(a.y-b.y)+1)*v.scale))
                            } else {
                                val size=(radius.toIntOrNull() ?: 1)*v.scale
                                drawing.zipWithNext().forEach {(a,b)->drawLine(tint,center(a),center(b),size*2)}
                                drawCircle(tint,size,center(drawing.last()))
                            }
                        }
                    }
                }
                Text(preview?.message.orEmpty(),fontSize=10.sp,modifier=Modifier.padding(6.dp))
            }
        }
        Column(Modifier.fillMaxSize()) {
            if (!short) previewContent()
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(8.dp),verticalArrangement=Arrangement.spacedBy(5.dp)) {
                if (short) previewContent()
                if(bitmap==null) {
                    Text("仅编辑规则，不把设计尺寸当设备全屏；运行时来源帧必须与规则尺寸一致。",fontSize=10.sp)
                    ImageToolNumberFields(listOf(Triple("width", "像素宽", width), Triple("height", "像素高", height)), strokes.isEmpty()) { key, value ->
                        if (key == "width") width = value else height = value
                    }
                }
                FlowRow(horizontalArrangement=Arrangement.spacedBy(2.dp)) {
                    listOf("ignore" to "忽略笔","restore" to "恢复笔","rect" to "忽略矩形","move" to "移动").forEach { (value,label) ->
                        ImageToolCompactAction(if(mode==value) "✓ $label" else label,bitmap!=null) {mode=value}
                    }
                }
                ImageToolCompactInput("笔半径 px",radius,true,Modifier.fillMaxWidth(),numeric=true) {radius=it.take(2)}
                ImageToolNumberFields(listOf(Triple("left", "左", left), Triple("top", "上", top), Triple("right", "右", right), Triple("bottom", "下", bottom)), true) { key, value ->
                    when (key) { "left" -> left = value.take(4); "top" -> top = value.take(4); "right" -> right = value.take(4); else -> bottom = value.take(4) }
                }
                FlowRow(horizontalArrangement=Arrangement.spacedBy(3.dp)) {
                    for(keep in listOf(false,true)) ImageToolCompactAction(if(keep) "恢复此矩形" else "忽略此矩形",true) {
                        try {publish(strokes+ProjectMaskStroke("rect",keep,0,listOf(ProjectMaskPoint(left.toInt(),top.toInt()),ProjectMaskPoint(right.toInt(),bottom.toInt()))))}
                        catch(_:Exception) {message="矩形需有效半开原像素范围；输入未应用"}
                    }
                    ImageToolCompactAction("撤销",version>0) {version--;message="已撤销一笔"}
                    ImageToolCompactAction("重做",version<versions.lastIndex) {version++;message="已重做一笔"}
                    ImageToolCompactAction("清除本次蒙版",strokes.isNotEmpty()) {publish(emptyList())}
                }
                Text("${strokes.size}/64笔 · ${strokes.sumOf {it.points.size}}/1024点 · ${width}×${height}px",fontSize=10.sp)
                Text("先放大再修细节，一笔一次撤销。全部透明不能应用；有效像素很少时容易误匹配，请保留有辨识度的图案。",fontSize=10.sp,color=AutoScriptPalette.TextSecondary)
                Text(message,fontSize=10.sp,color=AutoScriptPalette.TextSecondary)
            }
            HorizontalDivider()
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceEvenly) {
                ImageToolCompactAction("取消蒙版",true,onClick=::close)
                GlyphMakerPrimaryAction("应用蒙版",candidate!=null && (bitmap==null || preview?.ready==true)) {onApply(requireNotNull(candidate))}
            }
        }
        }
    }
    }
    if(discard) EditorPromptDialog("放弃本次蒙版？","不会改变原图、资源或处理历史。",{discard=false},{discard=false;onDismiss()},"继续编辑","放弃")
}

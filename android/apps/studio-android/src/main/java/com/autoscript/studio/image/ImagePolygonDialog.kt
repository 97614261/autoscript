package com.autoscript.studio

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autoscript.project.store.ProjectImageMask
import com.autoscript.project.store.ProjectMaskPoint
import com.autoscript.project.store.ProjectMaskStroke
import kotlin.math.hypot
import kotlin.math.roundToInt

internal fun polygonSelectionOperations(width:Int,height:Int,points:List<ProjectMaskPoint>,crop:Boolean,keep:Boolean):List<ImagePixelOperation> {
    val polygon=ProjectMaskStroke("polygon",keep,0,points.toList())
    val strokes=if(keep) listOf(ProjectMaskStroke("rect",false,0,listOf(ProjectMaskPoint(0,0),ProjectMaskPoint(width,height))),polygon) else listOf(polygon)
    val mask=ProjectImageMask(width,height,strokes).also {it.encode()}
    val operations=mutableListOf(ImagePixelOperation("mask",mask=mask))
    if(crop && keep) operations+=ImagePixelOperation("crop",roi=ImageToolCodeGen.Roi(points.minOf {it.x},points.minOf {it.y},points.maxOf {it.x},points.maxOf {it.y}))
    return operations
}

/** A polygon authors an explicit mask/crop, never a polygon-shaped search ROI. */
@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun ImagePolygonDialog(bitmap:Bitmap,onDismiss:()->Unit,onApply:(List<ImagePixelOperation>)->Unit) {
    var full by remember {mutableStateOf(false)}
    var history by remember(bitmap) {mutableStateOf(listOf(emptyList<ProjectMaskPoint>()))}
    var index by remember(bitmap) {mutableIntStateOf(0)}
    val points=history[index]
    var draft by remember(bitmap) {mutableStateOf<List<ProjectMaskPoint>?>(null)}
    var selected by remember(bitmap) {mutableIntStateOf(-1)}
    var pan by remember {mutableStateOf(false)}
    var crop by remember {mutableStateOf(true)}
    var keep by remember {mutableStateOf(true)}
    var discard by remember {mutableStateOf(false)}
    var message by remember {mutableStateOf("点图加顶点，拖顶点修形；双指缩放，移动模式拖图。")}
    var viewport by remember(bitmap) {mutableStateOf<ImageToolViewport?>(null)}
    val shown=draft ?: points
    val image=remember(bitmap) {bitmap.asImageBitmap()}
    val validation=remember(points,crop,keep) {runCatching {polygonSelectionOperations(bitmap.width,bitmap.height,points,crop,keep)}}
    val candidate=validation.getOrNull()
    fun close() {if(points.isNotEmpty()) discard=true else onDismiss()}
    fun publish(next:List<ProjectMaskPoint>) {
        if(next==points) return
        if(next.size>128 || next.distinct().size!=next.size) {message="顶点最多128个，不能重合";return}
        history=(history.take(index+1)+listOf(next.toList())).takeLast(33);index=history.lastIndex
        selected=selected.coerceAtMost(next.lastIndex)
    }
    val latestPoints by rememberUpdatedState(points)
    val latestViewport by rememberUpdatedState(viewport)
    val latestPublish by rememberUpdatedState<(List<ProjectMaskPoint>)->Unit>(::publish)
    StudioPanelDialog("多边形选区",::close,fullScreen=full,onToggleFullScreen={full=!full},compactHeader=true,showFooter=false) {
        BoxWithConstraints(Modifier.fillMaxWidth().heightIn(max=if(full)1000.dp else 600.dp)) {
            val controlsHeight=maxHeight*.30f
            val optionsHeight=maxHeight*.28f
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().heightIn(max=controlsHeight).verticalScroll(rememberScrollState()).padding(4.dp)) {
                    FlowRow(horizontalArrangement=Arrangement.spacedBy(3.dp)) {
                        ImageToolCompactAction(if(pan) "加点/修形" else "移动图片",true) {pan=!pan}
                        ImageToolCompactAction("−",true) {viewport=viewport?.let {it.scaledBy(.5f,it.viewWidth/2,it.viewHeight/2)}}
                        ImageToolCompactAction("＋",true) {viewport=viewport?.let {it.scaledBy(2f,it.viewWidth/2,it.viewHeight/2)}}
                        ImageToolCompactAction("适应",true) {viewport=viewport?.reset()}
                        ImageToolCompactAction("撤销",index>0) {index--;selected=-1}
                        ImageToolCompactAction("重做",index<history.lastIndex) {index++;selected=-1}
                        ImageToolCompactAction("删选中点",selected in points.indices) {publish(points.filterIndexed {i,_->i!=selected});selected=-1}
                        ImageToolCompactAction("清空",points.isNotEmpty()) {publish(emptyList())}
                    }
                }
                Canvas(Modifier.fillMaxWidth().weight(1f).clipToBounds().background(Color(0xff252c38)).semantics {contentDescription="多边形选区画布"}
                    .onSizeChanged {size -> if(size.width>0 && size.height>0) viewport=viewport?.resized(size.width.toFloat(),size.height.toFloat())
                        ?: ImageToolViewport.fit(bitmap.width,bitmap.height,size.width.toFloat(),size.height.toFloat())}
                    .pointerInput(bitmap,pan) {
                        awaitEachGesture {
                            val down=awaitFirstDown(requireUnconsumed=false)
                            val view=latestViewport ?: return@awaitEachGesture
                            val before=latestPoints
                            fun point(p:Offset)=ProjectMaskPoint(((p.x-view.translateX)/view.scale).roundToInt().coerceIn(0,bitmap.width),
                                ((p.y-view.translateY)/view.scale).roundToInt().coerceIn(0,bitmap.height))
                            val hit=before.indices.minByOrNull {i -> val p=before[i];val xy=view.toView(p.x,p.y);hypot(xy.first-down.position.x,xy.second-down.position.y)}
                                ?.takeIf {i -> val xy=view.toView(before[i].x,before[i].y);hypot(xy.first-down.position.x,xy.second-down.position.y)<=24.dp.toPx()}
                            var next=before;var accepted=false;var pinching=false
                            var centroid:Offset?=null;var distance=0f;var current=view
                            selected=hit ?: -1
                            down.consume()
                            try {
                                while(true) {
                                    val event=awaitPointerEvent()
                                    val pressed=event.changes.filter {it.pressed}
                                    if(pressed.size>=2) {
                                        draft=null;pinching=true
                                        val a=pressed[0].position;val b=pressed[1].position
                                        val centre=(a+b)/2f;val span=(a-b).getDistance()
                                        centroid?.let {old -> current=current.scaledBy(if(distance>0)span/distance else 1f,centre.x,centre.y).panned(centre.x-old.x,centre.y-old.y);viewport=current}
                                        centroid=centre;distance=span
                                        pressed.forEach {it.consume()};continue
                                    }
                                    if(pinching) {if(pressed.isEmpty())break else continue}
                                    val change=event.changes.firstOrNull {it.id==down.id} ?: break
                                    if(change.isConsumed)break
                                    if(pan) {val delta=change.position-change.previousPosition;current=current.panned(delta.x,delta.y);viewport=current}
                                    else if(hit!=null) {next=before.toMutableList().also {it[hit]=point(change.position)};draft=next}
                                    change.consume()
                                    if(!change.pressed) {
                                        if(!pan && hit==null) {
                                            // An outside-image tap is not silently clamped into a new edge vertex.
                                            val p=change.position
                                            if(p.x in view.translateX..(view.translateX+view.scaledWidth) && p.y in view.translateY..(view.translateY+view.scaledHeight)) next=before+point(p)
                                        }
                                        accepted=true;break
                                    }
                                }
                                if(accepted && !pan && !pinching) latestPublish(next)
                            } finally {draft=null}
                        }
                    }) {
                    viewport?.let {v ->
                        drawImage(image,dstOffset=IntOffset(v.translateX.roundToInt(),v.translateY.roundToInt()),
                            dstSize=IntSize(v.scaledWidth.roundToInt().coerceAtLeast(1),v.scaledHeight.roundToInt().coerceAtLeast(1)),filterQuality=FilterQuality.None)
                        val path=Path().apply {fillType=PathFillType.EvenOdd}
                        shown.forEachIndexed {i,p ->val xy=v.toView(p.x,p.y);if(i==0)path.moveTo(xy.first,xy.second) else path.lineTo(xy.first,xy.second)}
                        if(shown.size>=3) {path.close();drawPath(path,Color(0x3356cfff))}
                        drawPath(path,Color(0xff56cfff),style=Stroke(2.dp.toPx()))
                        shown.forEachIndexed {i,p ->val xy=v.toView(p.x,p.y);drawCircle(if(i==selected)Color.Yellow else Color.White,5.dp.toPx(),Offset(xy.first,xy.second))}
                    }
                }
                Column(Modifier.fillMaxWidth().heightIn(max=optionsHeight).verticalScroll(rememberScrollState()).padding(5.dp)) {
                    Text("${points.size}/128点 · ${if(keep)"选区外透明" else "忽略选区内"} · 原像素，不改变搜索ROI",fontSize=10.sp)
                    Text(message,fontSize=10.sp)
                    if(candidate==null) Text(if(points.size<3) "至少添加3个不同顶点" else "选区无效：请检查重合顶点、面积或图像预算",fontSize=10.sp,color=Color(0xffaa3f45))
                    FlowRow(horizontalArrangement=Arrangement.spacedBy(3.dp)) {
                        ImageToolCompactAction(if(keep)"✓ 保留选区" else "保留选区",true) {keep=true}
                        ImageToolCompactAction(if(!keep)"✓ 忽略选区" else "忽略选区",true) {keep=false}
                        ImageToolCompactAction(if(crop)"✓ 裁到外接框" else "保留原尺寸",keep) {crop=!crop}
                    }
                }
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceEvenly) {
                    ImageToolCompactAction("取消",true,onClick=::close)
                    GlyphMakerPrimaryAction("应用选区",candidate!=null) {onApply(requireNotNull(candidate))}
                }
            }
        }
    }
    if(discard) EditorPromptDialog("放弃多边形选区？","不会改变原图或资源。",{discard=false},{discard=false;onDismiss()},"继续编辑","放弃")
}

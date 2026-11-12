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

/** Shared vertex editor; search regions and destructive mask/crop operations remain separate. */
@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun ImagePolygonDialog(bitmap:Bitmap,onDismiss:()->Unit,onApply:(List<ImagePixelOperation>)->Unit = {},
    initialPoints:List<ProjectMaskPoint> = emptyList(),onSelect:((List<ProjectMaskPoint>)->Unit)? = null) {
    var full by remember {mutableStateOf(false)}
    var history by remember(bitmap) {mutableStateOf(listOf(initialPoints.toList()))}
    var index by remember(bitmap) {mutableIntStateOf(0)}
    val points=history[index]
    var selected by remember(bitmap) {mutableIntStateOf(-1)}
    var pan by remember {mutableStateOf(false)}
    var moveShape by remember {mutableStateOf(false)}
    var crop by remember {mutableStateOf(true)}
    var keep by remember {mutableStateOf(true)}
    var discard by remember {mutableStateOf(false)}
    var message by remember {mutableStateOf("点图加顶点，拖顶点修形；双指缩放，移动模式拖图。")}
    StudioSystemLogEffect("多边形选区", message, skipInitial = true)
    var viewport by rememberImageWorkspaceViewport(bitmap)
    val workspace = LocalImageWorkspace.current
    val candidate=remember(points,crop,keep,onSelect==null) {
        if(onSelect==null) runCatching {polygonSelectionOperations(bitmap.width,bitmap.height,points,crop,keep)}.getOrNull() else null
    }
    val pointsFit = points.all { it.x <= bitmap.width && it.y <= bitmap.height }
    val searchValid=remember(points,pointsFit) {pointsFit && runCatching {ImageSearchPolygon.validate(points)}.isSuccess}
    fun close() {if(points!=initialPoints) discard=true else onDismiss()}
    fun publish(next:List<ProjectMaskPoint>) {
        if(next==points) return
        if(next.size>128 || next.distinct().size!=next.size) {message="顶点最多128个，不能重合";return}
        history=(history.take(index+1)+listOf(next.toList())).takeLast(33);index=history.lastIndex
        selected=selected.coerceAtMost(next.lastIndex)
    }
    ImageWorkspaceDraftClear(if (points.isNotEmpty()) ({ publish(emptyList()); selected = -1 }) else null)
    val polygonCanvas: @Composable (Modifier) -> Unit = { bounds ->
        ImageWorkspaceCanvasSlot(bounds) { canvasBounds ->
            ImagePolygonCanvas(bitmap, viewport, points, selected, pan, moveShape, canvasBounds,
                onViewport={viewport=it},onSelected={selected=it},onPublish={publish(it)})
        }
    }
    ImageWorkspacePanel(if(onSelect!=null) "多边形范围" else "多边形选区",{ close() },fullScreen=full,onToggleFullScreen={full=!full},compactHeader=true,showFooter=false) {
        BoxWithConstraints(Modifier.fillMaxWidth().heightIn(max=if(full)1000.dp else 600.dp)) {
            val previewHeight=(maxHeight*.40f).coerceIn(100.dp,240.dp)
            Column(Modifier.fillMaxSize()) {
                Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(8.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                    ImageWorkspaceSection("绘制与调整") {
                        val editMode=if(pan) "pan" else if(moveShape) "shape" else "edit"
                        ImageToolChoice("操作",editMode,listOf("edit" to "加点修形")+
                            (if(onSelect!=null) listOf("shape" to "拖选区") else emptyList())+listOf("pan" to "移图片"),true) { value ->
                            if(value=="shape" && (points.isEmpty() || !pointsFit)) message="先添加有效顶点，再拖动整个选区"
                            else {pan=value=="pan";moveShape=value=="shape"}
                        }
                        FlowRow(horizontalArrangement=Arrangement.spacedBy(2.dp)) {
                            ImageToolCompactAction("缩小",true) {viewport=viewport?.let {it.scaledBy(.5f,it.viewWidth/2,it.viewHeight/2)}}
                            ImageToolCompactAction("放大",true) {viewport=viewport?.let {it.scaledBy(2f,it.viewWidth/2,it.viewHeight/2)}}
                            ImageToolCompactAction("适应",true) {viewport=viewport?.reset()}
                            ImageToolCompactAction("撤销",index>0) {index--;selected=-1}
                            ImageToolCompactAction("重做",index<history.lastIndex) {index++;selected=-1}
                        }
                        FlowRow(horizontalArrangement=Arrangement.spacedBy(2.dp)) {
                            ImageToolCompactAction("删除顶点",selected in points.indices) {publish(points.filterIndexed {i,_->i!=selected});selected=-1}
                            ImageToolCompactAction("清空",points.isNotEmpty()) {publish(emptyList())}
                            Text("${points.size}/128点"+if(selected in points.indices) " · 选中第${selected+1}点" else "",fontSize=10.sp,
                                color=androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(vertical=6.dp))
                        }
                    }
                    polygonCanvas(if(workspace==null) Modifier.fillMaxWidth().height(previewHeight) else Modifier.fillMaxWidth())
                    if(onSelect==null) ImageWorkspaceSection("应用方式") {
                        ImageToolChoice("选区",if(keep) "keep" else "ignore",listOf("keep" to "保留内部","ignore" to "忽略内部"),true) {keep=it=="keep"}
                        ImageToolToggle("裁到外接框",crop,keep) {crop=it}
                    }
                    if(onSelect!=null && !pointsFit) ImageWorkspaceHint("原选区超出当前截图，请清空后重新选取。",error=true)
                    if(if(onSelect!=null) !searchValid else candidate==null) ImageWorkspaceHint(
                        if(points.size<3) "至少添加3个不同顶点" else "选区无效：请检查重合顶点、面积或图像预算",error=true)
                    ImageWorkspaceHint(message)
                    ImageToolHelp(if(onSelect!=null) "点图加顶点，拖顶点修形；双指缩放。拖选区可移动整个多边形，移图片只调整视图。搜索只在选区内部进行，保留截图原坐标。"
                        else "点图加顶点，拖顶点修形；双指缩放。保留内部时选区外透明，忽略内部时选区内透明；处理使用原像素，不改变搜索ROI。")
                }
                androidx.compose.material3.HorizontalDivider()
                Row(Modifier.fillMaxWidth().padding(4.dp),horizontalArrangement=Arrangement.End) {
                    ImageToolCompactAction("取消",true,onClick=::close)
                    GlyphMakerPrimaryAction(if(onSelect!=null) "使用范围" else "应用选区",if(onSelect!=null) searchValid else candidate!=null) {
                        if(onSelect!=null) onSelect(points.toList()) else onApply(requireNotNull(candidate))
                    }
                }
            }
        }
    }
    if(discard) EditorPromptDialog("放弃多边形选区？","不会改变原图或资源。",{discard=false;workspace?.cancelNavigation?.invoke()},{discard=false;onDismiss()},"继续编辑","放弃")
}

/** The same original-pixel editor works directly under the toolbar and in standalone callers. */
@Composable
internal fun ImagePolygonCanvas(bitmap: Bitmap, viewport: ImageToolViewport?, points: List<ProjectMaskPoint>,
    selected: Int, pan: Boolean, moveShape: Boolean, canvasBounds: Modifier = Modifier.fillMaxSize(),
    onViewport: (ImageToolViewport) -> Unit, onSelected: (Int) -> Unit,
    onPublish: (List<ProjectMaskPoint>) -> Unit) {
    var draft by remember(bitmap, pan, moveShape) {mutableStateOf<List<ProjectMaskPoint>?>(null)}
    val shown=draft ?: points
    val image=remember(bitmap) {bitmap.asImageBitmap()}
    val latestPoints by rememberUpdatedState(points)
    val latestViewport by rememberUpdatedState(viewport)
    val latestPublish by rememberUpdatedState(onPublish)
    val latestOnViewport by rememberUpdatedState(onViewport)
    val latestOnSelected by rememberUpdatedState(onSelected)
    Canvas(canvasBounds.clipToBounds().background(Color(0xff252c38)).semantics {contentDescription="多边形选区画布"}
        .onSizeChanged {size -> if(size.width>0 && size.height>0) latestOnViewport(viewport?.resized(size.width.toFloat(),size.height.toFloat())
            ?: ImageToolViewport.fit(bitmap.width,bitmap.height,size.width.toFloat(),size.height.toFloat()))}
        .pointerInput(bitmap,pan,moveShape) {
            awaitEachGesture {
                val down=awaitFirstDown(requireUnconsumed=false)
                val view=latestViewport ?: return@awaitEachGesture
                val before=latestPoints
                val pointsFit=before.all {it.x in 0..bitmap.width && it.y in 0..bitmap.height}
                fun point(p:Offset)=ProjectMaskPoint(((p.x-view.translateX)/view.scale).roundToInt().coerceIn(0,bitmap.width),
                    ((p.y-view.translateY)/view.scale).roundToInt().coerceIn(0,bitmap.height))
                val hit=before.indices.minByOrNull {i -> val p=before[i];val xy=view.toView(p.x,p.y);hypot(xy.first-down.position.x,xy.second-down.position.y)}
                    ?.takeIf {i -> val xy=view.toView(before[i].x,before[i].y);hypot(xy.first-down.position.x,xy.second-down.position.y)<=24.dp.toPx()}
                var next=before;var accepted=false;var pinching=false
                var centroid:Offset?=null;var distance=0f;var current=view
                latestOnSelected(hit ?: -1)
                down.consume()
                try {
                    while(true) {
                        val event=awaitPointerEvent()
                        val pressed=event.changes.filter {it.pressed}
                        if(pressed.size>=2) {
                            draft=null;pinching=true
                            val a=pressed[0].position;val b=pressed[1].position
                            val centre=(a+b)/2f;val span=(a-b).getDistance()
                            centroid?.let {old -> current=current.scaledBy(if(distance>0)span/distance else 1f,centre.x,centre.y).panned(centre.x-old.x,centre.y-old.y);latestOnViewport(current)}
                            centroid=centre;distance=span
                            pressed.forEach {it.consume()};continue
                        }
                        if(pinching) {if(pressed.isEmpty())break else continue}
                        val change=event.changes.firstOrNull {it.id==down.id} ?: break
                        if(change.isConsumed)break
                        if(pan) {val delta=change.position-change.previousPosition;current=current.panned(delta.x,delta.y);latestOnViewport(current)}
                        else if(moveShape && before.isNotEmpty() && pointsFit) {
                            val dx=((change.position.x-down.position.x)/view.scale).roundToInt().coerceIn(-before.minOf {it.x},bitmap.width-before.maxOf {it.x})
                            val dy=((change.position.y-down.position.y)/view.scale).roundToInt().coerceIn(-before.minOf {it.y},bitmap.height-before.maxOf {it.y})
                            next=before.map {ProjectMaskPoint(it.x+dx,it.y+dy)};draft=next
                        }
                        else if(hit!=null) {next=before.toMutableList().also {it[hit]=point(change.position)};draft=next}
                        change.consume()
                        if(!change.pressed) {
                            if(!pan && !moveShape && hit==null) {
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
}

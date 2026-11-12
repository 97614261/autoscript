package com.autoscript.studio

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.*
import com.autoscript.project.store.ProjectSnapshot
import kotlinx.coroutines.*

/** Explicit local candidate inspection. No auto search, script insertion, image export or logging. */
@Composable
internal fun ImageDifferenceDialog(bitmap:Bitmap,snapshot:ProjectSnapshot,configuration:RecognitionPreviewConfiguration,
    initialX:Int,initialY:Int,initialScale:Int=1000,onDismiss:()->Unit) {
    var x by remember {mutableStateOf(initialX.toString())};var y by remember {mutableStateOf(initialY.toString())}
    var tolerance by remember {mutableStateOf(configuration.tolerance.toString())};var scale by remember {mutableStateOf(initialScale.toString())}
    var full by remember {mutableStateOf(false)};var message by remember {mutableStateOf("指定一个候选位置后分析；不代表最相似位置或引擎耗时。")}
    StudioSystemLogEffect("图像差异分析", message, skipInitial = true)
    var result by remember {mutableStateOf<Pair<Bitmap,ImageDifference>?>(null)}
    var differenceVisible by remember {mutableStateOf(false)}
    var templateSize by remember {mutableStateOf<Pair<Int,Int>?>(null)};var job by remember {mutableStateOf<Job?>(null)}
    var ticket by remember {mutableIntStateOf(0)};val scope=rememberCoroutineScope()
    val busy=job?.isActive==true
    fun clear() {ticket++;job?.cancel();job=null;result=null;templateSize=null;differenceVisible=false}
    DisposableEffect(result) {val owned=result?.first;onDispose {owned?.recycle()}}
    DisposableEffect(Unit) {onDispose {ticket++;job?.cancel()}}
    fun change(value:String,setter:(String)->Unit) {clear();setter(value.take(6));message="位置或参数改变，请重新分析"}
    ImageWorkspacePanel("差异分析",{clear();onDismiss()},fullScreen=full,onToggleFullScreen={full=!full},compactHeader=true,showFooter=false) {
        BoxWithConstraints(Modifier.fillMaxWidth().heightIn(max=if(full)1000.dp else 570.dp)) {
        val previewHeight=(maxHeight*.23f).coerceIn(40.dp,if(full)180.dp else 112.dp)
        val short = studioShortViewport(maxHeight.value)
        val previewContent: @Composable () -> Unit = {
            if(result!=null) ImageToolChoice("查看",if(differenceVisible) "difference" else "source",
                listOf("source" to "候选原图","difference" to "差异图"),true) {differenceVisible=it=="difference"}
            val difference=result?.first?.takeIf {differenceVisible}
            if(difference!=null) ImageProcessingPreviewCanvas(difference,previewHeight.value.toInt(),label="差异：红色不匹配，透明为模板忽略区")
            else ImageWorkspaceCanvasSlot(Modifier.fillMaxWidth().height(previewHeight)) { bounds ->
            Canvas(bounds.clipToBounds()) {
                val fit=minOf(size.width/bitmap.width,size.height/bitmap.height)
                val left=(size.width-bitmap.width*fit)/2;val top=(size.height-bitmap.height*fit)/2
                drawImage(bitmap.asImageBitmap(),dstOffset=IntOffset(left.toInt(),top.toInt()),dstSize=IntSize((bitmap.width*fit).toInt().coerceAtLeast(1),(bitmap.height*fit).toInt().coerceAtLeast(1)))
                val px=x.toIntOrNull();val py=y.toIntOrNull();val dimensions=templateSize
                if(px!=null && py!=null && dimensions!=null) drawRect(Color(0xffff4670),Offset(left+px*fit,top+py*fit),Size(dimensions.first*fit,dimensions.second*fit),style=Stroke(2.dp.toPx()))
            }
            }
        }
        Column(Modifier.fillMaxSize()) {
            if (!short) previewContent()
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(8.dp),verticalArrangement=Arrangement.spacedBy(5.dp)) {
                if (short) previewContent()
                ImageWorkspaceSection("候选位置") {
                ImageToolNumberFields(listOf(Triple("x", "左 X", x), Triple("y", "上 Y", y)), !busy) { key, value ->
                    change(value) { next -> when (key) { "x" -> x = next; "y" -> y = next; else -> tolerance = next } }
                }
                }
                ImageWorkspaceSection("比较参数") {
                ImageToolCompactInput("RGB容差",tolerance,!busy,Modifier.fillMaxWidth(),true) {change(it) {value->tolerance=value}}
                if(configuration.operation==com.autoscript.runtime.api.RecognitionPreviewReply.SCALED_TEMPLATE)
                    ImageToolCompactInput("比例 ‰",scale,!busy,Modifier.fillMaxWidth(),true) {change(it) {value->scale=value}}
                }
                ImageWorkspaceHint(message)
                if(configuration.operation==com.autoscript.runtime.api.RecognitionPreviewReply.GRAY_TEMPLATE) ImageWorkspaceHint("这里只比较灰度像素差，不等于OpenCV相关系数或最高分。")
                ImageToolHelp("局部分析≤100万像素、5秒；没有自动搜索最近候选，不保存屏幕或把图像内容写入日志。位置属于当前图，不是自动点击坐标。")
            }
            ImageWorkspaceFooter {
                ImageToolCompactAction(if(busy) "取消分析" else "返回",true,Modifier.weight(1f)) {if(busy) {clear();message="已取消分析"} else onDismiss()}
                ImageToolCompactAction("分析此位置",!busy,Modifier.weight(1f),primary=true) {
                    val px=x.toIntOrNull();val py=y.toIntOrNull();val t=tolerance.toIntOrNull();val s=scale.toIntOrNull()
                    if(px==null || py==null || t==null || s==null || px<0 || py<0 || t !in 0..255 || s !in 250..4000) {message="请输入有效位置、容差0–255和比例250–4000‰";return@ImageToolCompactAction}
                    clear();val id=ticket;message="正在读取模板…"
                    job=scope.launch {
                        var preview:Bitmap?=null;var template:Bitmap?=null
                        try {
                            template=withContext(Dispatchers.IO) {decodeWorkbenchBitmap(imageRecognitionFile(snapshot,configuration.template),1_048_576).also {template=it}}
                            ensureActive();val target=requireNotNull(template)
                            val w=target.width*s/1000;val h=target.height*s/1000
                            require(w>0 && h>0 && w.toLong()*h<=1_048_576 && px.toLong()+w<=bitmap.width && py.toLong()+h<=bitmap.height)
                            val sourcePixels=IntArray(w*h);bitmap.getPixels(sourcePixels,0,w,px,py,w,h)
                            val templatePixels=IntArray(target.width*target.height);target.getPixels(templatePixels,0,target.width,0,0,target.width,target.height)
                            templateSize=w to h
                            val started=System.nanoTime()
                            val analysis=withContext(Dispatchers.Default) {
                                val work=currentCoroutineContext();val deadline=started+5_000_000_000L
                                val checkpoint={work.ensureActive();require(System.nanoTime()<deadline)}
                                var a=sourcePixels;var b=if(s==1000) templatePixels else processImagePixels(templatePixels,target.width,target.height,ImagePixelOperation("resize",w,h),checkpoint).argb
                                if(configuration.operation==com.autoscript.runtime.api.RecognitionPreviewReply.GRAY_TEMPLATE) {
                                    a=processImagePixels(a,w,h,ImagePixelOperation("gray"),checkpoint).argb
                                    b=processImagePixels(b,w,h,ImagePixelOperation("gray"),checkpoint).argb
                                }
                                analyzeImageDifference(a,b,t,checkpoint)
                            }
                            ensureActive();preview=Bitmap.createBitmap(analysis.overlay,w,h,Bitmap.Config.ARGB_8888)
                            if(ticket==id) {result=requireNotNull(preview) to analysis;preview=null;differenceVisible=true;message="有效${analysis.compared} · 不匹配${analysis.mismatched} · 像素符合${analysis.scorePermille}‰\n最大通道差${analysis.maximumDelta} · 平均差${analysis.averageDelta} · 本地分析${(System.nanoTime()-started)/1_000_000}ms（不含截图/引擎）"}
                        } catch(cancelled:CancellationException) {throw cancelled}
                        catch(_:OutOfMemoryError) {if(ticket==id) message="分析内存不足；请缩小模板，原图和配置保留"}
                        catch(_:Exception) {if(ticket==id) message="模板、候选范围无效或超过分析预算；没有更改图像或设置"}
                        finally {preview?.recycle();template?.recycle();if(ticket==id) job=null}
                    }
                }
            }
        }
        }
    }
}

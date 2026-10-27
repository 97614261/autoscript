package com.autoscript.studio

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*

internal data class ImageProcessingPreview(val bitmap:Bitmap,val ready:Boolean,val message:String)

/** Each completed result owns a bitmap; closing, invalid edits and cancellation cannot publish old proof. */
@Composable
internal fun rememberImageProcessingPreview(source:Bitmap,operation:ImagePixelOperation?):ImageProcessingPreview {
    var result by remember(source) {mutableStateOf<Pair<ImagePixelOperation,Bitmap>?>(null)}
    var status by remember(source) {mutableStateOf("原图 · 尚未应用本次处理")}
    var ticket by remember(source) {mutableLongStateOf(0)}
    DisposableEffect(result) {val owned=result?.second;onDispose {owned?.recycle()}}
    LaunchedEffect(source,operation) {
        val request=++ticket
        if(operation==null) {status="原图 · 尚未应用本次处理";return@LaunchedEffect}
        status="正在生成原像素预览…";var frozen:Bitmap?=null;var created:Bitmap?=null
        try {
            delay(100);operation.dimensions(source.width,source.height)
            frozen=requireNotNull(source.copy(Bitmap.Config.ARGB_8888,false));val input=requireNotNull(frozen)
            val message=withContext(Dispatchers.Default) {
                val context=currentCoroutineContext();val start=System.nanoTime();val deadline=start+5_000_000_000L
                val check={context.ensureActive();require(System.nanoTime()<deadline) {"处理超过期限"}}
                val pixels=IntArray(input.width*input.height);input.getPixels(pixels,0,input.width,0,0,input.width,input.height)
                val processed=processImagePixels(pixels,input.width,input.height,operation,check);check()
                val count=processed.argb.count {pixel -> pixel ushr 24!=0 && (operation.cleanup==null ||
                    (pixel and 0xFFFFFF)==if(operation.cleanup.foreground=="light") 0xFFFFFF else 0)}
                created=Bitmap.createBitmap(processed.argb,processed.width,processed.height,Bitmap.Config.ARGB_8888)
                "${if(operation.mask!=null) "有效像素" else "前景"} $count/${pixels.size}px · 本地处理 ${(System.nanoTime()-start)/1_000_000}ms"
            }
            ensureActive();if(request==ticket) {result=operation to requireNotNull(created);created=null;status=message}
        } catch(cancelled:CancellationException) {throw cancelled}
        catch(_:OutOfMemoryError) {if(request==ticket) status="预览内存不足，原图保留；请缩小范围"}
        catch(_:Exception) {if(request==ticket) status="处理无效、清除全部像素或超过预算；原图与历史保留"}
        finally {created?.recycle();frozen?.recycle()}
    }
    val current=result?.takeIf {it.first==operation}
    return ImageProcessingPreview(current?.second ?: source,current!=null,
        if(operation==null) "未应用 · 显示原图" else status)
}

@Composable
internal fun ImageProcessingPreviewCanvas(bitmap:Bitmap,height:Int,modifier:Modifier=Modifier,label:String="图像处理预览") {
    val image=remember(bitmap) {bitmap.asImageBitmap()}
    Canvas(Modifier.fillMaxWidth().height(height.dp).then(modifier).clipToBounds().background(Color(0xff202632)).semantics {contentDescription=label}) {
        val tile=16.dp.toPx()
        for(y in 0..(size.height/tile).toInt()) for(x in 0..(size.width/tile).toInt())
            if((x+y)%2==0) drawRect(Color(0xff343c4c),Offset(x*tile,y*tile),Size(tile,tile))
        val viewport=glyphMakerFocus(ImageToolViewport.fit(bitmap.width,bitmap.height,size.width,size.height))
        drawImage(image,dstOffset=IntOffset(viewport.translateX.toInt(),viewport.translateY.toInt()),
            dstSize=IntSize((bitmap.width*viewport.scale).toInt().coerceAtLeast(1),(bitmap.height*viewport.scale).toInt().coerceAtLeast(1)),filterQuality=FilterQuality.None)
    }
}

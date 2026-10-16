package com.autoscript.runtime.service

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.RectF
import android.os.Build
import android.provider.Settings
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.ViewTreeObserver
import android.os.Handler
import android.os.Looper
import com.google.gson.JsonObject

/** Main-thread-only independent windows; no input interception or interactive controls. */
internal class RuntimeFloatingWindows(private val context:Context,private val screen:()->Point) {
    private data class Window(val view:CanvasView,var layout:WindowManager.LayoutParams,var visible:Boolean,var work:Long)
    private val manager=context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val windows=mutableMapOf<String,Window>()
    private val main=Handler(Looper.getMainLooper())
    fun show(spec:FloatingCanvasSpec,image:Bitmap?,work:Long) {
        check(Settings.canDrawOverlays(context)) {"floating permission required"}
        val size=screen(); require(size.x>0 && size.y>0)
        val width=minOf(spec.width,size.x); val height=minOf(spec.height,size.y)
        require(spec.name in windows || windows.size<8)
        val area=windows.filterKeys {it!=spec.name}.values.sumOf {it.layout.width.toLong()*it.layout.height}
        require(area+width.toLong()*height<=8_388_608)
        val layout=WindowManager.LayoutParams(width,height,
            if(Build.VERSION.SDK_INT>=26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT).apply {
                gravity=Gravity.TOP or Gravity.LEFT
                x=(if(spec.x==-1) (size.x-width)/2 else spec.x).coerceIn(0,size.x-width)
                y=(if(spec.y==-1) (size.y-height)/2 else spec.y).coerceIn(0,size.y-height)
                if(Build.VERSION.SDK_INT>=28) layoutInDisplayCutoutMode=WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        val old=windows[spec.name]
        if(old==null) {
            val view=CanvasView(context,spec,image)
            try {manager.addView(view,layout)} catch(error:Exception) {runCatching {manager.removeViewImmediate(view)}; throw error}
            windows[spec.name]=Window(view,layout,true,work)
        } else {
            manager.updateViewLayout(old.view,layout)
            old.layout=layout; old.work=work; old.view.spec=spec; old.view.image=image; old.view.visibility=View.VISIBLE; old.visible=true; old.view.invalidate()
        }
    }
    fun hide(name:String) {require(FloatingCanvasSpec.validName(name)); windows[name]?.let {it.view.visibility=View.GONE; it.visible=false}}
    fun close(name:String) {require(FloatingCanvasSpec.validName(name)); windows.remove(name)?.let {runCatching {manager.removeViewImmediate(it.view)}}}
    fun clear() {windows.keys.toList().forEach(::close)}
    fun cancel(work:Long) {windows.filterValues {it.work==work}.keys.toList().forEach(::close)}
    fun bounds(name:String):String {
        require(FloatingCanvasSpec.validName(name))
        val window=windows[name] ?: error("floating window not found")
        val location=if(window.visible && window.view.width>0) IntArray(2).also(window.view::getLocationOnScreen) else intArrayOf(window.layout.x,window.layout.y)
        return JsonObject().apply {addProperty("x",location[0]); addProperty("y",location[1]); addProperty("width",if(window.visible && window.view.width>0) window.view.width else window.layout.width); addProperty("height",if(window.visible && window.view.height>0) window.view.height else window.layout.height); addProperty("visible",window.visible)}.toString()
    }
    fun awaitBounds(name:String,valid:()->Boolean,complete:(Result<String>)->Unit) {
        val window=windows[name]
        if(window==null || !window.visible || window.view.width>0) {complete(runCatching {check(valid()); bounds(name)}); return}
        val observer=window.view.viewTreeObserver
        var done=false
        lateinit var listener:ViewTreeObserver.OnGlobalLayoutListener
        lateinit var timeout:Runnable
        fun finish(result:Result<String>) {
            if(done) return
            done=true; main.removeCallbacks(timeout)
            if(observer.isAlive) observer.removeOnGlobalLayoutListener(listener)
            complete(result)
        }
        listener=ViewTreeObserver.OnGlobalLayoutListener {
            if(window.view.width>0) finish(runCatching {check(valid() && windows[name]===window); bounds(name)})
        }
        timeout=Runnable {finish(Result.failure(IllegalStateException("floating layout timeout")))}
        observer.addOnGlobalLayoutListener(listener); main.postDelayed(timeout,1000)
    }
    private class CanvasView(context:Context,var spec:FloatingCanvasSpec,var image:Bitmap?):View(context) {
        private val paint=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val textPaint=TextPaint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(canvas:Canvas) {
            super.onDraw(canvas)
            canvas.drawColor(spec.background)
            image?.let {bitmap ->
                paint.color=android.graphics.Color.WHITE
                val scale=minOf(width.toFloat()/bitmap.width,height.toFloat()/bitmap.height)
                val w=bitmap.width*scale; val h=bitmap.height*scale
                canvas.drawBitmap(bitmap,null,RectF((width-w)/2,(height-h)/2,(width+w)/2,(height+h)/2),paint)
            }
            for(shape in spec.shapes) {
                paint.color=shape.color; paint.style=Paint.Style.FILL; paint.strokeWidth=shape.strokePx.toFloat()
                val c=shape.coordinates
                when(shape.kind) {"line"->canvas.drawLine(c[0].toFloat(),c[1].toFloat(),c[2].toFloat(),c[3].toFloat(),paint); "rect"->canvas.drawRect(c[0].toFloat(),c[1].toFloat(),(c[0]+c[2]).toFloat(),(c[1]+c[3]).toFloat(),paint); "circle"->canvas.drawCircle(c[0].toFloat(),c[1].toFloat(),c[2].toFloat(),paint)}
            }
            if(spec.text.isNotEmpty()) {
                textPaint.color=spec.textColor; textPaint.textSize=spec.fontPx.toFloat()
                val layout=StaticLayout.Builder.obtain(spec.text,0,spec.text.length,textPaint,maxOf(1,width-8)).setAlignment(Layout.Alignment.ALIGN_CENTER).setIncludePad(false).setMaxLines(8).setEllipsize(TextUtils.TruncateAt.END).build()
                canvas.save(); canvas.translate(4f,maxOf(0f,(height-layout.height)/2f)); layout.draw(canvas); canvas.restore()
            }
        }
    }
}

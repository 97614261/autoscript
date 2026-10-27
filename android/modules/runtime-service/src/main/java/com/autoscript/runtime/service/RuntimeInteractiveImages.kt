package com.autoscript.runtime.service

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Point
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.ImageView

/** Independent run-owned interactive windows; only image bounds consume touch. Main thread only. */
internal class RuntimeInteractiveImages(private val context:Context,private val screen:()->Point) {
    private data class Window(val view:ImageView,val layout:WindowManager.LayoutParams,val draggable:Boolean,val work:Long,
        val events:RuntimeImageEvents=RuntimeImageEvents())
    private data class Wait(val name:String,val work:Long,val valid:()->Boolean,val callback:(Result<String>)->Unit,val timeout:Runnable)
    private val manager=context.getSystemService(WindowManager::class.java)
    private val main=Handler(Looper.getMainLooper())
    private val windows=mutableMapOf<String,Window>()
    private var waiting:Wait?=null
    private fun onMain()=check(Looper.myLooper()==Looper.getMainLooper()) {"IMAGE_WINDOW_WRONG_THREAD"}
    fun show(value:String,image:Bitmap,work:Long) {
        onMain()
        check(Settings.canDrawOverlays(context))
        val spec=ProjectDataCommands.parse(value,2048).asJsonObject
        require(spec.keySet()==setOf("name","imagePath","x","y","width","height","draggable"))
        val name=spec["name"].asString;require(FloatingCanvasSpec.validName(name))
        fun number(key:String,min:Int,max:Int)=requireNotNull(spec[key].toString().toIntOrNull()).also {require(it in min..max)}
        val x=number("x",-1,8192);val y=number("y",-1,8192)
        val requestedWidth=number("width",1,2048);val requestedHeight=number("height",1,2048)
        val drag=number("draggable",0,1)==1
        val size=screen();require(size.x>0 && size.y>0)
        val width=minOf(requestedWidth,size.x);val height=minOf(requestedHeight,size.y)
        require(name in windows || windows.size<8)
        require(windows.filterKeys {it!=name}.values.sumOf {it.layout.width.toLong()*it.layout.height}+width.toLong()*height<=8_388_608)
        close(name)
        val layout=WindowManager.LayoutParams(width,height,
            if(Build.VERSION.SDK_INT>=26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT).apply {
            gravity=Gravity.TOP or Gravity.LEFT
            this.x=(if(x==-1) (size.x-width)/2 else x).coerceIn(0,size.x-width)
            this.y=(if(y==-1) (size.y-height)/2 else y).coerceIn(0,size.y-height)
            if(Build.VERSION.SDK_INT>=28) layoutInDisplayCutoutMode=WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        val view=ImageView(context).apply {scaleType=ImageView.ScaleType.FIT_CENTER;setImageBitmap(image);contentDescription="脚本交互图片"}
        val window=Window(view,layout,drag,work)
        bindTouch(name,window)
        try {manager.addView(view,layout);windows[name]=window}
        catch(error:Exception) {runCatching {manager.removeViewImmediate(view)};view.setImageDrawable(null);throw error}
    }
    private fun bindTouch(name:String,window:Window) {
        var downX=0f;var downY=0f;var initialX=0;var initialY=0;var active=false;var moved=false
        val slop=ViewConfiguration.get(context).scaledTouchSlop
        window.view.setOnTouchListener {view,event ->
            if(windows[name]!==window) return@setOnTouchListener false
            fun reset() {active=false;moved=false}
            if(event.pointerCount!=1 || event.actionMasked==MotionEvent.ACTION_CANCEL) {reset();return@setOnTouchListener true}
            when(event.actionMasked) {
                MotionEvent.ACTION_DOWN->{downX=event.rawX;downY=event.rawY;initialX=window.layout.x;initialY=window.layout.y;active=true;moved=false}
                MotionEvent.ACTION_MOVE,MotionEvent.ACTION_UP->if(active) {
                    val dx=event.rawX-downX;val dy=event.rawY-downY
                    if(dx*dx+dy*dy>slop.toFloat()*slop) moved=true
                    if(window.draggable && moved) {
                        val size=screen();window.layout.x=(initialX+dx.toInt()).coerceIn(0,maxOf(0,size.x-window.layout.width))
                        window.layout.y=(initialY+dy.toInt()).coerceIn(0,maxOf(0,size.y-window.layout.height))
                        if(runCatching {manager.updateViewLayout(view,window.layout)}.isFailure) {reset();close(name);return@setOnTouchListener true}
                    }
                    if(event.actionMasked==MotionEvent.ACTION_UP) {
                        if(!moved || window.draggable) {
                            val location=IntArray(2).also(view::getLocationOnScreen)
                            val localX=(event.rawX-location[0]).toInt().coerceIn(0,maxOf(0,view.width-1))
                            val localY=(event.rawY-location[1]).toInt().coerceIn(0,maxOf(0,view.height-1))
                            window.events.add(if(moved) "drag" else "click",location[0].coerceIn(0,32768),location[1].coerceIn(0,32768),localX,localY)
                            deliver(name,window)
                            if(!moved) view.performClick()
                        }
                        reset()
                    }
                }
            }
            true
        }
    }
    fun await(name:String,timeoutMs:Long,work:Long,valid:()->Boolean,callback:(Result<String>)->Unit) {
        onMain()
        require(FloatingCanvasSpec.validName(name) && timeoutMs in 0..60000);check(waiting==null);check(valid())
        val window=windows[name] ?: error("IMAGE_WINDOW_MISSING")
        window.events.read()?.let {callback(Result.success(it));return}
        if(timeoutMs==0L) {callback(Result.success(window.events.terminal("timeout")));return}
        lateinit var wait:Wait
        val timeout=Runnable {if(waiting===wait) {waiting=null;callback(runCatching {check(valid());window.events.terminal("timeout")})}}
        wait=Wait(name,work,valid,callback,timeout);waiting=wait;main.postDelayed(timeout,timeoutMs)
    }
    private fun deliver(name:String,window:Window) {
        val wait=waiting?.takeIf {it.name==name} ?: return
        waiting=null;main.removeCallbacks(wait.timeout)
        wait.callback(runCatching {check(wait.valid());requireNotNull(window.events.read())})
    }
    fun close(name:String) {
        onMain()
        require(FloatingCanvasSpec.validName(name))
        val window=windows.remove(name)
        waiting?.takeIf {it.name==name}?.let {wait->waiting=null;main.removeCallbacks(wait.timeout)
            wait.callback(runCatching {check(wait.valid());(window?.events ?: RuntimeImageEvents()).terminal("closed")})}
        window?.let {runCatching {manager.removeViewImmediate(it.view)};it.view.setOnTouchListener(null);it.view.setImageDrawable(null)}
    }
    fun cancel(work:Long) {
        onMain()
        waiting?.takeIf {it.work==work}?.let {waiting=null;main.removeCallbacks(it.timeout);it.callback(Result.failure(IllegalStateException("cancelled")))}
        windows.filterValues {it.work==work}.keys.toList().forEach(::close)
    }
    fun clear() {
        onMain()
        windows.keys.toList().forEach(::close)
        waiting?.let {waiting=null;main.removeCallbacks(it.timeout);it.callback(Result.failure(IllegalStateException("cancelled")))}
    }
}

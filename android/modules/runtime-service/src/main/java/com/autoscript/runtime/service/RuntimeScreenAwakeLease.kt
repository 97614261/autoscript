package com.autoscript.runtime.service

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager

/** Main-thread run-owned KEEP_SCREEN_ON; does not change system timeout or wake a locked device. */
internal class RuntimeScreenAwakeLease(private val context:Context) {
    private val manager=context.getSystemService(WindowManager::class.java)
    private var view:View?=null
    private var owner=0L
    fun set(enabled:Boolean,work:Long) {
        if(!enabled) {clear();return}
        check(Settings.canDrawOverlays(context)) {"OVERLAY_PERMISSION_REQUIRED"}
        if(view==null) {
            val candidate=View(context).apply {setBackgroundColor(android.graphics.Color.TRANSPARENT)}
            val layout=WindowManager.LayoutParams(1,1,
                if(Build.VERSION.SDK_INT>=26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                // Keep the window policy-visible; its pixels are transparent, not its window alpha.
                PixelFormat.TRANSLUCENT).apply {gravity=Gravity.TOP or Gravity.LEFT}
            try {manager.addView(candidate,layout)} catch(error:Exception) {runCatching {manager.removeViewImmediate(candidate)};throw error}
            view=candidate
        }
        owner=work
    }
    fun cancel(work:Long) {if(owner==work) clear()}
    fun clear() {view?.let {runCatching {manager.removeViewImmediate(it)}};view=null;owner=0L}
}

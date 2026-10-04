package com.autoscript.runtime.service

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/** Separate recording controls. The runtime ring retains its pause/continue/stop semantics. */
internal class RuntimeRecordingOverlay(private val context: Context, private val onFinish: (Boolean) -> Unit) {
    private val manager = context.getSystemService(WindowManager::class.java)
    private var root: LinearLayout? = null
    private var buttons = emptyList<TextView>()
    fun canShow() = Settings.canDrawOverlays(context)
    fun show(enabled: Boolean, onShown: (Rect?) -> Unit) {
        hide()
        if (!canShow()) { onShown(null); return }
        val density = context.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt().coerceAtLeast(1)
        val view = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8),dp(5),dp(8),dp(5))
            background = GradientDrawable().apply { setColor(Color.rgb(30,30,48)); cornerRadius = dp(14).toFloat() }
        }
        val image = ImageView(context).apply { setImageResource(R.drawable.zhigou_brand_mark) }
        view.addView(image,LinearLayout.LayoutParams(dp(30),dp(30)))
        view.addView(TextView(context).apply { text = "REC"; setTextColor(Color.rgb(255,112,130)); textSize=14f; setPadding(dp(6),0,dp(6),0) })
        buttons = listOf("停止" to false,"取消" to true).map { (label,cancel) ->
            TextView(context).apply {
                text=label; textSize=14f; setTextColor(Color.WHITE); gravity=Gravity.CENTER
                setPadding(dp(10),dp(10),dp(10),dp(10)); isEnabled=enabled
                setOnClickListener { if (isEnabled) { buttons.forEach { it.isEnabled=false }; onFinish(cancel) } }
            }.also { view.addView(it) }
        }
        val params = WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT,WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT).apply { gravity=Gravity.TOP or Gravity.END; x=dp(12); y=dp(64) }
        view.addOnLayoutChangeListener(object : android.view.View.OnLayoutChangeListener {
            override fun onLayoutChange(v: android.view.View, left: Int, top: Int, right: Int, bottom: Int, oldLeft: Int, oldTop: Int, oldRight: Int, oldBottom: Int) {
                if (v.width <= 0 || v.height <= 0) return
                v.removeOnLayoutChangeListener(this)
                val position = IntArray(2); v.getLocationOnScreen(position)
                onShown(Rect(position[0],position[1],position[0]+v.width,position[1]+v.height))
            }
        })
        runCatching {
            manager.addView(view,params); root=view
        }.onFailure { hide(); onShown(null) }
    }
    fun enable() { buttons.forEach { it.isEnabled=true } }
    fun hide() { root?.let { runCatching { manager.removeViewImmediate(it) } }; root=null; buttons=emptyList() }
}

package com.autoscript.runtime.service

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.autoscript.runtime.api.RuntimeProtocol
import kotlin.math.abs

internal class RuntimeOverlayController(
    context: Context,
    private val onPause: () -> Unit,
    private val onResume: () -> Unit,
    private val onStop: () -> Unit,
) {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val windowManager = appContext.getSystemService(WindowManager::class.java)
    private var root: LinearLayout? = null
    private var statusView: TextView? = null
    private var pauseView: TextView? = null
    private var stopView: TextView? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var expanded = false
    @Volatile private var runtimeState = RuntimeProtocol.STATE_IDLE

    fun canShow(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
        Settings.canDrawOverlays(appContext)

    fun show(state: Int) {
        runtimeState = state
        if (!canShow()) return
        mainHandler.post {
            if (!canShow()) return@post
            if (root == null) addOverlay()
            render()
        }
    }

    fun update(state: Int) {
        runtimeState = state
        mainHandler.post(::render)
    }

    fun hide() {
        mainHandler.post {
            root?.let { view -> runCatching { windowManager.removeView(view) } }
            root = null
            statusView = null
            pauseView = null
            stopView = null
            layoutParams = null
        }
    }

    private fun addOverlay() {
        val container = LinearLayout(appContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(7), dp(5), dp(7), dp(5))
            background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(Color.argb(230, 31, 41, 55))
                setStroke(dp(1), Color.argb(220, 104, 211, 145))
            }
            elevation = dp(6).toFloat()
        }
        val toggle = actionText("AS").apply {
            setTextColor(Color.rgb(104, 211, 145))
            setOnTouchListener(DragTouchListener())
        }
        statusView = actionText("")
        pauseView = actionText("暂停").apply {
            setOnClickListener {
                if (runtimeState == RuntimeProtocol.STATE_PAUSED) onResume() else onPause()
            }
        }
        stopView = actionText("停止").apply {
            setTextColor(Color.rgb(255, 138, 128))
            setOnClickListener { onStop() }
        }
        container.addView(toggle)
        container.addView(statusView)
        container.addView(pauseView)
        container.addView(stopView)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(12)
            y = dp(120)
        }
        runCatching { windowManager.addView(container, params) }
            .onSuccess {
                root = container
                layoutParams = params
            }
    }

    private fun render() {
        val visible = if (expanded) View.VISIBLE else View.GONE
        statusView?.visibility = visible
        pauseView?.visibility = visible
        stopView?.visibility = visible
        statusView?.text = when (runtimeState) {
            RuntimeProtocol.STATE_RUNNING -> "运行中"
            RuntimeProtocol.STATE_PAUSED -> "已暂停"
            RuntimeProtocol.STATE_STOPPING -> "停止中"
            else -> "准备中"
        }
        pauseView?.text = if (runtimeState == RuntimeProtocol.STATE_PAUSED) "继续" else "暂停"
        pauseView?.isEnabled = runtimeState == RuntimeProtocol.STATE_RUNNING ||
            runtimeState == RuntimeProtocol.STATE_PAUSED
        stopView?.isEnabled = runtimeState == RuntimeProtocol.STATE_RUNNING ||
            runtimeState == RuntimeProtocol.STATE_PAUSED ||
            runtimeState == RuntimeProtocol.STATE_STOPPING
    }

    private fun actionText(value: String) = TextView(appContext).apply {
        text = value
        setTextColor(Color.WHITE)
        textSize = 13f
        gravity = Gravity.CENTER
        setPadding(dp(7), dp(5), dp(7), dp(5))
    }

    private fun dp(value: Int): Int = (value * appContext.resources.displayMetrics.density).toInt()

    private inner class DragTouchListener : View.OnTouchListener {
        private var downRawX = 0f
        private var downRawY = 0f
        private var startX = 0
        private var startY = 0

        override fun onTouch(view: View, event: MotionEvent): Boolean {
            val params = layoutParams ?: return false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startX = params.x
                    startY = params.y
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val metrics = appContext.resources.displayMetrics
                    val maximumX = (metrics.widthPixels - (root?.width ?: 0)).coerceAtLeast(0)
                    val maximumY = (metrics.heightPixels - (root?.height ?: 0)).coerceAtLeast(0)
                    params.x = (startX + (event.rawX - downRawX).toInt()).coerceIn(0, maximumX)
                    params.y = (startY + (event.rawY - downRawY).toInt()).coerceIn(0, maximumY)
                    root?.let { runCatching { windowManager.updateViewLayout(it, params) } }
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    if (abs(event.rawX - downRawX) < dp(6).toFloat() &&
                        abs(event.rawY - downRawY) < dp(6).toFloat()
                    ) {
                        expanded = !expanded
                        render()
                    }
                    return true
                }
                MotionEvent.ACTION_CANCEL -> return true
            }
            return false
        }
    }
}

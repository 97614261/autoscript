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
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.autoscript.runtime.api.RuntimeProtocol
import kotlin.math.abs

internal class RuntimeOverlayController(
    context: Context,
    private val onPause: () -> Unit,
    private val onResume: () -> Unit,
    private val onStop: () -> Unit,
    private val onCapture: () -> Unit,
) {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val windowManager = appContext.getSystemService(WindowManager::class.java)
    private var root: LinearLayout? = null
    private var toggleView: TextView? = null
    private var statusView: TextView? = null
    private var pauseView: TextView? = null
    private var stopView: TextView? = null
    private var captureView: View? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var expanded = false
    private var captureOnly = false
    @Volatile private var runtimeState = RuntimeProtocol.STATE_IDLE

    fun canShow(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
        Settings.canDrawOverlays(appContext)

    fun show(state: Int) {
        show(state, captureOnly = false)
    }

    /** A one-shot screenshot session shows only the camera bubble, not script controls. */
    fun showCapture(state: Int) {
        show(state, captureOnly = true)
    }

    private fun show(state: Int, captureOnly: Boolean) {
        runtimeState = state
        val presentationChanged = this.captureOnly != captureOnly
        this.captureOnly = captureOnly
        if (captureOnly) expanded = false
        if (!canShow()) return
        mainHandler.post {
            if (!canShow()) return@post
            // ensureForegroundForRun() may have already created the full runner controls.
            // A capture-only session has a different shape, icon and touch target, so merely
            // hiding sibling views leaves the old camera TextView alive and visually misleading.
            if (presentationChanged && root != null) removeOverlayView()
            if (root == null) {
                // A previous run may have ended while the controls were expanded. Reusing that
                // state lets the next script's injected tap hit its own pause/stop buttons.
                expanded = false
                addOverlay()
            }
            render()
        }
    }

    fun update(state: Int) {
        runtimeState = state
        mainHandler.post(::render)
    }

    fun hide() {
        mainHandler.post {
            removeOverlayView()
            expanded = false
            captureOnly = false
        }
    }

    private fun removeOverlayView() {
        root?.let { view -> runCatching { windowManager.removeView(view) } }
        root = null
        toggleView = null
        statusView = null
        pauseView = null
        stopView = null
        captureView = null
        layoutParams = null
    }

    /** Shows capture progress/failures above the target app; Runner logs alone are invisible there. */
    fun showCaptureMessage(message: String) {
        mainHandler.post {
            Toast.makeText(appContext, message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun addOverlay() {
        val container = LinearLayout(appContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(
                if (captureOnly) 0 else dp(7),
                if (captureOnly) 0 else dp(5),
                if (captureOnly) 0 else dp(7),
                if (captureOnly) 0 else dp(5),
            )
            background = GradientDrawable().apply {
                if (captureOnly) {
                    shape = GradientDrawable.OVAL
                    setColor(Color.rgb(31, 94, 255))
                    setStroke(dp(1), Color.argb(210, 163, 190, 255))
                } else {
                    cornerRadius = dp(18).toFloat()
                    setColor(Color.argb(230, 31, 41, 55))
                    setStroke(dp(1), Color.argb(220, 104, 211, 145))
                }
            }
            elevation = dp(6).toFloat()
        }
        // 截图专用状态与 Lua 编辑器的相机浮球使用同一蓝色圆形和同一白色相机图标，
        // 不再用系统字体的 emoji，避免不同设备上图标形状不一致。
        val capture: View = if (captureOnly) {
            ImageView(appContext).apply {
                setImageResource(R.drawable.runtime_capture_camera_24)
                contentDescription = "截图"
                scaleType = ImageView.ScaleType.CENTER
                layoutParams = LinearLayout.LayoutParams(dp(35), dp(35))
                setOnClickListener { onCapture() }
                setOnTouchListener(DragTouchListener())
            }
        } else {
            actionText("📷").apply {
                textSize = 16f
                contentDescription = "截图"
                setTextColor(Color.rgb(142, 196, 255))
                setOnClickListener { onCapture() }
                setOnTouchListener(DragTouchListener())
            }
        }
        val toggle = actionText("AS").apply {
            setTextColor(Color.rgb(104, 211, 145))
            setOnTouchListener(DragTouchListener())
        }
        toggleView = toggle
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
        container.addView(capture)
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
                captureView = capture
                // A persistent capture control should start at the right edge so it does not
                // cover the target app's primary content. Dragging retains the user's position.
                container.post {
                    val current = layoutParams ?: return@post
                    val displayWidth = appContext.resources.displayMetrics.widthPixels
                    current.x = (displayWidth - container.width - dp(12)).coerceAtLeast(0)
                    runCatching { windowManager.updateViewLayout(container, current) }
                }
            }
    }

    private fun render() {
        toggleView?.visibility = if (captureOnly) View.GONE else View.VISIBLE
        val visible = if (expanded && !captureOnly) View.VISIBLE else View.GONE
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
        val canCapture = runtimeState == RuntimeProtocol.STATE_IDLE ||
            runtimeState == RuntimeProtocol.STATE_STOPPED ||
            runtimeState == RuntimeProtocol.STATE_FAILED
        // Capture mode must still receive a tap when a run is active: the service then tells
        // the user to stop the script instead of leaving a dimmed, apparently dead icon.
        captureView?.alpha = if (canCapture || captureOnly) 1f else 0.42f
        captureView?.isEnabled = canCapture || captureOnly
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
                        if (view === captureView) {
                            if (view.isEnabled) view.performClick()
                        } else {
                            expanded = !expanded
                            render()
                        }
                    }
                    return true
                }
                MotionEvent.ACTION_CANCEL -> return true
            }
            return false
        }
    }
}

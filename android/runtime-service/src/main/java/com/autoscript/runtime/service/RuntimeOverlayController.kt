package com.autoscript.runtime.service

import android.content.Context
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.autoscript.runtime.api.RuntimeProtocol
import org.json.JSONObject
import kotlin.math.abs

internal data class RuntimePopupStyle(
    val widthPx: Int,
    val heightPx: Int,
    val xPx: Int,
    val yPx: Int,
    val backgroundColor: Int,
    val textColor: Int,
    val fontPx: Int,
    val cornerPx: Int,
    val durationMs: Int,
    val textAlign: String,
) {
    companion object {
        fun parse(value: JSONObject): RuntimePopupStyle? = runCatching {
            val style = RuntimePopupStyle(
                value.getInt("widthPx"), value.getInt("heightPx"),
                value.getInt("xPx"), value.getInt("yPx"),
                Color.parseColor(value.getString("backgroundColor")),
                Color.parseColor(value.getString("textColor")),
                value.getInt("fontPx"), value.getInt("cornerPx"),
                value.getInt("durationMs"), value.getString("textAlign"),
            )
            style.takeIf {
                it.widthPx in 1..2160 && it.heightPx in 1..1200 &&
                    it.xPx in -1..10_000 && it.yPx in -1..10_000 &&
                    it.fontPx in 10..160 && it.cornerPx in 0..200 &&
                    it.durationMs in 500..30_000 && it.textAlign in setOf("left", "center", "right")
            }
        }.getOrNull()
    }
}

internal class RuntimeOverlayController(
    context: Context,
    private val onPause: () -> Unit,
    private val onResume: () -> Unit,
    private val onStop: () -> Unit,
    private val onCapture: () -> Unit,
    private val onPromptClosed: () -> Unit,
    private val onToastExpired: () -> Unit,
) {
    private val serviceContext = context
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val windowManager = appContext.getSystemService(WindowManager::class.java)
    private var root: LinearLayout? = null
    private var toggleView: View? = null
    private var statusView: TextView? = null
    private var pauseView: TextView? = null
    private var stopView: TextView? = null
    private var promptButton: TextView? = null
    private var captureView: View? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var promptView: TextView? = null
    private var promptLayoutParams: WindowManager.LayoutParams? = null
    private var promptHidden = false
    @Volatile private var promptReceiving = true
    private var latestPrompt: String? = null
    private var toastView: TextView? = null
    private var toastExpiry: Runnable? = null
    private var expanded = false
    private var dockRight = true
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
        removePromptView()
        removeToastView(notify = false)
        removeControlView()
    }

    /** Short-lived script tip; unlike the run-progress window it expires automatically. */
    fun showPopupToast(message: String, style: RuntimePopupStyle) {
        val text = message.trim().take(MAX_PROMPT_LENGTH)
        if (text.isEmpty()) return
        mainHandler.post {
            removeToastView(notify = false)
            if (!canShow()) {
                Toast.makeText(appContext, text, Toast.LENGTH_LONG).show()
                onToastExpired()
                return@post
            }
            val metrics = appContext.resources.displayMetrics
            val width = style.widthPx.coerceAtMost(metrics.widthPixels - dp(16))
            val height = style.heightPx.coerceAtMost(metrics.heightPixels - dp(16))
            val view = TextView(appContext).apply {
                this.text = text
                setTextSize(TypedValue.COMPLEX_UNIT_PX, style.fontPx.toFloat())
                setTextColor(style.textColor)
                gravity = Gravity.CENTER_VERTICAL or when (style.textAlign) {
                    "left" -> Gravity.START
                    "right" -> Gravity.END
                    else -> Gravity.CENTER_HORIZONTAL
                }
                maxLines = 10
                // Small pixel-sized tips must not lose their text area to fixed dp padding.
                val horizontalPadding = (width / 12).coerceIn(0, dp(12))
                val verticalPadding = (height / 12).coerceIn(0, dp(6))
                setPadding(horizontalPadding, verticalPadding, horizontalPadding, verticalPadding)
                contentDescription = "弹出提示：$text（点击关闭）"
                background = GradientDrawable().apply {
                    cornerRadius = style.cornerPx.toFloat()
                    setColor(style.backgroundColor)
                }
                setOnClickListener { removeToastView(notify = true) }
            }
            val params = WindowManager.LayoutParams(
                width, height,
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
                x = (if (style.xPx == -1) (metrics.widthPixels - width) / 2 else style.xPx)
                    .coerceIn(0, (metrics.widthPixels - width).coerceAtLeast(0))
                y = (if (style.yPx == -1) (metrics.heightPixels - height) / 2 else style.yPx)
                    .coerceIn(0, (metrics.heightPixels - height).coerceAtLeast(0))
            }
            runCatching { windowManager.addView(view, params) }
                .onSuccess {
                    toastView = view
                    toastExpiry = Runnable { removeToastView(notify = true) }.also {
                        mainHandler.postDelayed(it, style.durationMs.toLong())
                    }
                }
                .onFailure {
                    Toast.makeText(appContext, text, Toast.LENGTH_LONG).show()
                    onToastExpired()
                }
        }
    }

    private fun removeToastView(notify: Boolean) {
        toastExpiry?.let(mainHandler::removeCallbacks)
        toastExpiry = null
        val view = toastView ?: return
        toastView = null
        runCatching { windowManager.removeView(view) }
        if (notify) onToastExpired()
    }

    private fun removeControlView() {
        root?.let { view -> runCatching { windowManager.removeView(view) } }
        root = null
        toggleView = null
        statusView = null
        pauseView = null
        stopView = null
        promptButton = null
        captureView = null
        layoutParams = null
    }

    /** Shows capture progress/failures above the target app; Runner logs alone are invisible there. */
    fun showCaptureMessage(message: String) {
        mainHandler.post {
            Toast.makeText(appContext, message, Toast.LENGTH_SHORT).show()
        }
    }

    /** Displays the user-facing Prompt.show channel as a draggable floating message window. */
    fun showRunPrompt(message: String) {
        val text = message.trim().take(MAX_PROMPT_LENGTH)
        if (text.isEmpty()) return
        mainHandler.post {
            if (!promptReceiving) return@post
            latestPrompt = text
            if (!canShow()) {
                Toast.makeText(appContext, text, Toast.LENGTH_SHORT).show()
                return@post
            }
            if (promptHidden) {
                render()
                return@post
            }
            val existing = promptView
            if (existing != null) {
                existing.text = text
                existing.contentDescription = "运行提示：$text（点击设置，拖动移动）"
                return@post
            }
            val view = TextView(appContext).apply {
                this.text = text
                textSize = 14f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                maxLines = 5
                maxWidth = (appContext.resources.displayMetrics.widthPixels * 0.84f).toInt()
                minWidth = dp(132)
                setPadding(dp(14), dp(10), dp(14), dp(10))
                contentDescription = "运行提示：$text（点击设置，拖动移动）"
                background = GradientDrawable().apply {
                    cornerRadius = dp(8).toFloat()
                    setColor(Color.rgb(29, 92, 133))
                    setStroke(dp(1), Color.argb(220, 173, 224, 255))
                }
            }
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
                y = dp(180)
            }
            view.setOnTouchListener(PromptDragTouchListener(view, params))
            runCatching { windowManager.addView(view, params) }
                .onSuccess {
                    promptView = view
                    promptLayoutParams = params
                    view.post {
                        val metrics = appContext.resources.displayMetrics
                        params.x = ((metrics.widthPixels - view.width) / 2).coerceAtLeast(dp(8))
                        runCatching { windowManager.updateViewLayout(view, params) }
                    }
                }
                .onFailure {
                    // An overlay permission can disappear between the check and addView.
                    // Release the finished Runner session instead of retaining its foreground
                    // notification for a prompt that the user cannot close.
                    promptReceiving = false
                    latestPrompt = null
                    Toast.makeText(appContext, text, Toast.LENGTH_LONG).show()
                    onPromptClosed()
                }
        }
    }

    private fun removePromptView() {
        promptView?.let { view -> runCatching { windowManager.removeView(view) } }
        promptView = null
        promptLayoutParams = null
        promptHidden = false
        latestPrompt = null
        promptReceiving = true
    }

    fun resetRunPrompts() {
        promptReceiving = true
        mainHandler.post { removePromptView(); removeToastView(notify = false); render() }
    }

    fun acceptsRunPrompts(): Boolean = promptReceiving

    /** Keep the prompt and reopen control alive until the user explicitly closes the prompt. */
    fun finishWithRunPrompt() {
        runtimeState = RuntimeProtocol.STATE_STOPPED
        mainHandler.post(::render)
    }

    private fun reopenRunPrompt() {
        val view = promptView ?: return
        val params = promptLayoutParams ?: return
        if (!promptHidden) return
        runCatching { windowManager.addView(view, params) }.onSuccess {
            promptHidden = false
            view.text = latestPrompt.orEmpty()
            render()
        }
    }

    private fun promptActions() {
        val dialog = AlertDialog.Builder(serviceContext)
            .setTitle("运行提示")
            .setMessage("关闭后本次运行不再接收提示；后台隐藏后继续接收，可从运行浮球重新打开。")
            .setPositiveButton("关闭提示") { _, _ ->
                removePromptView()
                promptReceiving = false
                render()
                onPromptClosed()
            }
            .setNeutralButton("后台") { _, _ ->
                if (root == null) {
                    Toast.makeText(appContext, "运行浮球未启用，提示窗不能隐藏到后台", Toast.LENGTH_SHORT).show()
                    return@setNeutralButton
                }
                promptView?.let { runCatching { windowManager.removeView(it) } }
                promptHidden = true
                render()
            }
            .setNegativeButton("取消", null)
            .create()
        dialog.window?.setType(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        })
        runCatching { dialog.show() }
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
                    setColor(Color.WHITE)
                    setStroke(dp(1), Color.rgb(232, 232, 243))
                }
            }
            elevation = dp(6).toFloat()
        }
        // 截图专用状态与 Lua 编辑器的相机浮球使用同一蓝色圆形和同一白色相机图标，
        // 不再用系统字体的 emoji，避免不同设备上图标形状不一致。
        val capture: View? = if (captureOnly) {
            ImageView(appContext).apply {
                setImageResource(R.drawable.runtime_capture_camera_24)
                contentDescription = "截图"
                scaleType = ImageView.ScaleType.CENTER
                layoutParams = LinearLayout.LayoutParams(dp(35), dp(35))
                setOnClickListener { onCapture() }
                setOnTouchListener(DragTouchListener())
            }
        } else null
        val toggle = ImageView(appContext).apply {
            setImageResource(R.drawable.zhigou_brand_mark)
            layoutParams = LinearLayout.LayoutParams(dp(35), dp(35))
            setPadding(dp(2), dp(2), dp(2), dp(2))
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "智构 · 展开运行控制"
            setOnClickListener {
                expanded = !expanded
                render()
            }
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
        promptButton = actionText("提示").apply {
            setTextColor(Color.rgb(142, 196, 255))
            setOnClickListener { reopenRunPrompt() }
        }
        capture?.let(container::addView)
        container.addView(toggle)
        container.addView(statusView)
        container.addView(pauseView)
        container.addView(stopView)
        container.addView(promptButton)
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
        val controls = runtimeFloatingControls(runtimeState, captureOnly, expanded)
        root?.setPadding(
            if (controls.expanded) dp(7) else 0, if (controls.expanded) dp(5) else 0,
            if (controls.expanded) dp(7) else 0, if (controls.expanded) dp(5) else 0,
        )
        toggleView?.visibility = if (captureOnly) View.GONE else View.VISIBLE
        toggleView?.contentDescription = if (expanded) "智构 · 收起运行控制" else "智构 · 展开运行控制"
        captureView?.visibility = if (controls.showCamera) View.VISIBLE else View.GONE
        val visible = if (controls.expanded) View.VISIBLE else View.GONE
        statusView?.visibility = visible
        pauseView?.visibility = visible
        stopView?.visibility = visible
        promptButton?.visibility = if (expanded && promptHidden && latestPrompt != null && !captureOnly) View.VISIBLE else View.GONE
        statusView?.text = when (runtimeState) {
            RuntimeProtocol.STATE_RUNNING -> "运行中"
            RuntimeProtocol.STATE_PAUSED -> "已暂停"
            RuntimeProtocol.STATE_STOPPING -> "停止中"
            RuntimeProtocol.STATE_STOPPED -> "已结束"
            else -> "准备中"
        }
        pauseView?.text = controls.pauseLabel
        pauseView?.isEnabled = controls.canPauseResume
        pauseView?.setTextColor(if (controls.canPauseResume) Color.rgb(65, 105, 255) else Color.GRAY)
        stopView?.isEnabled = controls.canStop
        stopView?.alpha = if (controls.canStop) 1f else .42f
        // Reflow after expansion so the toolbar stays fully inside the screen.
        root?.post {
            val view = root ?: return@post
            val params = layoutParams ?: return@post
            val metrics = appContext.resources.displayMetrics
            params.x = if (dockRight) (metrics.widthPixels - view.width).coerceAtLeast(0) else 0
            params.y = params.y.coerceIn(0, (metrics.heightPixels - view.height).coerceAtLeast(0))
            runCatching { windowManager.updateViewLayout(view, params) }
        }
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
        setTextColor(Color.rgb(55, 65, 81))
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
                        if (view.isEnabled) view.performClick()
                    } else {
                        val maximumX = (appContext.resources.displayMetrics.widthPixels - (root?.width ?: 0)).coerceAtLeast(0)
                        dockRight = params.x >= maximumX / 2
                        params.x = if (dockRight) maximumX else 0
                        root?.let { runCatching { windowManager.updateViewLayout(it, params) } }
                    }
                    return true
                }
                MotionEvent.ACTION_CANCEL -> return true
            }
            return false
        }
    }

    private inner class PromptDragTouchListener(
        private val view: View,
        private val params: WindowManager.LayoutParams,
    ) : View.OnTouchListener {
        private var downRawX = 0f
        private var downRawY = 0f
        private var startX = 0
        private var startY = 0
        private var moved = false

        override fun onTouch(view: View, event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startX = params.x
                    startY = params.y
                    moved = false
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    moved = moved || abs(dx) > dp(3) || abs(dy) > dp(3)
                    params.x = (startX + dx.toInt()).coerceAtLeast(0)
                    params.y = (startY + dy.toInt()).coerceAtLeast(0)
                    runCatching { windowManager.updateViewLayout(this.view, params) }
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) promptActions()
                    return true
                }
                MotionEvent.ACTION_CANCEL -> return true
            }
            return false
        }
    }

    private companion object {
        const val MAX_PROMPT_LENGTH = 2_048
    }
}

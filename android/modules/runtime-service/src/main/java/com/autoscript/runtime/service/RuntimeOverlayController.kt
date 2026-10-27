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
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.autoscript.runtime.api.RuntimeProtocol
import com.autoscript.brand.ui.BrandState
import com.autoscript.brand.ui.ZhigouMarkView
import org.json.JSONObject
import kotlin.math.abs

internal data class RuntimePopupStyle(
    val widthPx: Int = 520,
    val heightPx: Int = 144,
    val xPx: Int = -1,
    val yPx: Int = -1,
    val backgroundColor: Int = 0xB3000000.toInt(),
    val textColor: Int = Color.WHITE,
    val fontPx: Int = 32,
    val cornerPx: Int = 16,
    val durationMs: Int = 3_000,
    val textAlign: String = "center",
) {
    fun hasSameGeometry(other: RuntimePopupStyle?): Boolean = other != null &&
        widthPx == other.widthPx && heightPx == other.heightPx && xPx == other.xPx && yPx == other.yPx

    fun hasSameAppearance(other: RuntimePopupStyle?): Boolean = other != null &&
        backgroundColor == other.backgroundColor && textColor == other.textColor &&
        fontPx == other.fontPx && cornerPx == other.cornerPx && textAlign == other.textAlign

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
    private val promptSession = RuntimePromptSession()
    private var latestPromptStyle: RuntimePopupStyle? = null
    private var promptDialog: AlertDialog? = null
    private var fallbackToast: Toast? = null
    private var latestPrompt: String? = null
    private var toastView: TextView? = null
    private var toastLayoutParams: WindowManager.LayoutParams? = null
    private var latestToastStyle: RuntimePopupStyle? = null
    private var toastExpiry: Runnable? = null
    private var expanded = false
    private var dockRight = true
    private var captureOnly = false
    @Volatile private var runtimeState = RuntimeProtocol.STATE_IDLE
    @Volatile private var normalCompletion = false
    @Volatile private var controlEnabled = true

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
        normalCompletion = false
        val presentationChanged = this.captureOnly != captureOnly
        this.captureOnly = captureOnly
        if (captureOnly) expanded = false
        if (!canShow()) return
        mainHandler.post {
            if (!canShow() || !captureOnly && !controlEnabled) return@post
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

    fun update(state: Int, completedNormally: Boolean = false) {
        runtimeState = state
        normalCompletion = completedNormally && state == RuntimeProtocol.STATE_STOPPED
        mainHandler.post(::render)
    }

    fun hide() {
        val ticket = promptSession.end()
        mainHandler.post {
            if (!promptSession.isCurrent(ticket)) return@post
            removeOverlayView()
            expanded = false
            captureOnly = false
        }
    }

    /** Changing the control-ball owner must not end or clear the script tips. */
    fun hideControl() {
        controlEnabled = false
        mainHandler.post {
            removeControlView()
            expanded = false
            captureOnly = false
        }
    }

    fun enableControl() { controlEnabled = true }

    private fun removeOverlayView() {
        removePromptView()
        removeToastView(notify = false)
        fallbackToast?.cancel()
        fallbackToast = null
        removeControlView()
    }

    /** Short-lived script tip; unlike the run-progress window it expires automatically. */
    fun showPopupToast(message: String, style: RuntimePopupStyle) {
        val text = message.trim().take(MAX_PROMPT_LENGTH)
        if (text.isEmpty()) return
        val session = promptSession.ticket()
        val ticket = promptSession.beginToast(session) ?: return
        mainHandler.post {
            if (!promptSession.acceptsToast(session, ticket)) return@post
            toastExpiry?.let(mainHandler::removeCallbacks)
            toastExpiry = null
            if (!canShow()) {
                removeToastView(notify = false)
                showFallbackToast(text)
                if (promptSession.endToast(session, ticket)) onToastExpired()
                return@post
            }
            val existing = toastView
            val geometryChanged = !style.hasSameGeometry(latestToastStyle)
            val params = toastLayoutParams ?: promptParams(style)
            if (existing != null && geometryChanged) updatePromptGeometry(params, style)
            val view = existing ?: TextView(appContext)
            updatePromptContent(view, text, style, latestToastStyle, params, "弹出提示：$text（点击关闭）")
            // A reused view must dismiss the newest message, not its original ticket.
            view.setOnClickListener { expireToast(ticket, session) }
            runCatching {
                if (existing == null) windowManager.addView(view, params)
                else if (geometryChanged) windowManager.updateViewLayout(view, params)
            }
                .onSuccess {
                    toastView = view
                    toastLayoutParams = params
                    latestToastStyle = style
                    updatePromptVisibility()
                    toastExpiry = Runnable { expireToast(ticket, session) }.also {
                        mainHandler.postDelayed(it, style.durationMs.toLong())
                    }
                }
                .onFailure {
                    removeToastView(notify = false)
                    showFallbackToast(text)
                    if (promptSession.endToast(session, ticket)) onToastExpired()
                }
        }
    }

    private fun removeToastView(notify: Boolean) {
        toastExpiry?.let(mainHandler::removeCallbacks)
        toastExpiry = null
        val view = toastView ?: return
        toastView = null
        toastLayoutParams = null
        latestToastStyle = null
        runCatching { windowManager.removeView(view) }
        updatePromptVisibility()
        if (notify) onToastExpired()
    }

    private fun expireToast(ticket: Long, session: Long) {
        if (promptSession.endToast(session, ticket)) {
            removeToastView(notify = true)
        }
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

    /** Persistent channel shares project presentation, but does not use the toast expiry. */
    fun showRunPrompt(message: String, style: RuntimePopupStyle = RuntimePopupStyle()) {
        val text = message.trim().take(MAX_PROMPT_LENGTH)
        if (text.isEmpty()) return
        val session = promptSession.ticket()
        mainHandler.post {
            if (!promptSession.acceptsWindow(session)) return@post
            val previousStyle = latestPromptStyle
            latestPrompt = text
            if (!canShow()) {
                showFallbackToast(text)
                return@post
            }
            latestPromptStyle = style
            val existing = promptView
            val geometryChanged = !style.hasSameGeometry(previousStyle)
            val params = promptLayoutParams ?: promptParams(style)
            if (existing != null && geometryChanged) updatePromptGeometry(params, style)
            val view = existing ?: TextView(appContext)
            updatePromptContent(view, text, style, if (existing == null) null else previousStyle,
                params, "运行提示：$text（点击设置，拖动移动）")
            if (existing != null) {
                if (!promptHidden && geometryChanged) runCatching { windowManager.updateViewLayout(view, params) }
                updatePromptVisibility()
                return@post
            }
            view.setOnTouchListener(PromptDragTouchListener(view, params))
            runCatching { windowManager.addView(view, params) }
                .onSuccess {
                    promptView = view
                    promptLayoutParams = params
                    updatePromptVisibility()
                }
                .onFailure {
                    promptSession.closeWindow(session)
                    latestPrompt = null
                    showFallbackToast(text)
                    onPromptClosed()
                }
        }
    }

    private fun showFallbackToast(text: String) {
        fallbackToast?.cancel()
        fallbackToast = Toast.makeText(appContext, text, Toast.LENGTH_LONG).also { it.show() }
    }

    private fun updatePromptContent(view: TextView, text: String, style: RuntimePopupStyle,
        previousStyle: RuntimePopupStyle?, params: WindowManager.LayoutParams, description: String) {
        if (!view.text.contentEquals(text)) view.text = text
        if (!view.contentDescription.contentEquals(description)) view.contentDescription = description
        if (!style.hasSameAppearance(previousStyle)) applyPromptStyle(view, style)
        updatePromptPadding(view, params.width, params.height)
    }

    private fun applyPromptStyle(view: TextView, style: RuntimePopupStyle) {
        view.setTextSize(TypedValue.COMPLEX_UNIT_PX, style.fontPx.toFloat())
        view.setTextColor(style.textColor)
        view.includeFontPadding = false
        view.gravity = Gravity.CENTER_VERTICAL or when (style.textAlign) {
            "left" -> Gravity.LEFT
            "right" -> Gravity.RIGHT
            else -> Gravity.CENTER_HORIZONTAL
        }
        view.maxLines = 10
        view.background = GradientDrawable().apply {
            cornerRadius = style.cornerPx.toFloat()
            setColor(style.backgroundColor)
        }
    }

    private fun updatePromptPadding(view: TextView, width: Int, height: Int) {
        val horizontal = (width / 12).coerceIn(0, dp(12))
        val vertical = (height / 12).coerceIn(0, dp(6))
        if (view.paddingLeft != horizontal || view.paddingRight != horizontal ||
            view.paddingTop != vertical || view.paddingBottom != vertical) {
            view.setPadding(horizontal, vertical, horizontal, vertical)
        }
    }

    private fun promptParams(style: RuntimePopupStyle) = WindowManager.LayoutParams(
        style.widthPx, style.heightPx,
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        },
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.LEFT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) setFitInsetsTypes(0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        updatePromptGeometry(this, style)
    }

    @Suppress("DEPRECATION")
    private fun updatePromptGeometry(params: WindowManager.LayoutParams, style: RuntimePopupStyle) {
        val metrics = DisplayMetrics()
        windowManager.defaultDisplay.getRealMetrics(metrics)
        val geometry = runtimePromptGeometry(metrics.widthPixels, metrics.heightPixels,
            style.widthPx, style.heightPx, style.xPx, style.yPx)
        params.width = geometry.width
        params.height = geometry.height
        params.x = geometry.x
        params.y = geometry.y
    }

    fun onDisplayChanged() {
        mainHandler.post {
            latestToastStyle?.let { style -> toastLayoutParams?.let { params ->
                updatePromptGeometry(params, style)
                toastView?.let {
                    updatePromptPadding(it, params.width, params.height)
                    runCatching { windowManager.updateViewLayout(it, params) }
                }
            } }
            val style = latestPromptStyle ?: return@post
            val view = promptView ?: return@post
            val params = promptLayoutParams ?: return@post
            updatePromptGeometry(params, style)
            updatePromptPadding(view, params.width, params.height)
            if (!promptHidden) runCatching { windowManager.updateViewLayout(view, params) }
            updatePromptVisibility()
        }
    }

    private fun updatePromptVisibility() {
        val persistent = promptLayoutParams ?: return
        val transient = toastLayoutParams
        val overlaps = transient != null && runtimePromptsOverlap(
            RuntimePromptGeometry(persistent.width, persistent.height, persistent.x, persistent.y),
            RuntimePromptGeometry(transient.width, transient.height, transient.x, transient.y))
        val visibility = if (overlaps) View.INVISIBLE else View.VISIBLE
        promptView?.let { if (it.visibility != visibility) it.visibility = visibility }
    }

    private fun removePromptView() {
        promptDialog?.dismiss()
        promptDialog = null
        if (!promptHidden) promptView?.let { view -> runCatching { windowManager.removeView(view) } }
        promptView = null
        promptLayoutParams = null
        promptHidden = false
        latestPrompt = null
        latestPromptStyle = null
    }

    fun resetRunPrompts() {
        val ticket = promptSession.begin()
        mainHandler.post {
            if (!promptSession.isCurrent(ticket)) return@post
            removePromptView()
            removeToastView(notify = false)
            fallbackToast?.cancel()
            fallbackToast = null
            render()
        }
    }

    /** Disable incoming persistent messages before queued UI cleanup can run. */
    fun closeRunPrompt() {
        val ticket = promptSession.closeWindow()
        mainHandler.post {
            if (!promptSession.isCurrent(ticket)) return@post
            removePromptView()
            render()
        }
    }

    /** Called on explicit stop as well as destruction; stale posted shows cannot resurrect it. */
    fun clearRunPrompts() {
        val ticket = promptSession.end()
        mainHandler.post {
            if (!promptSession.isCurrent(ticket)) return@post
            removePromptView()
            removeToastView(notify = false)
            fallbackToast?.cancel()
            fallbackToast = null
            render()
        }
    }

    fun acceptsRunPrompts(): Boolean = promptSession.acceptsWindow()
    fun hasPopupToast(): Boolean = promptSession.hasToast()

    private fun reopenRunPrompt() {
        if (!promptSession.acceptsWindow()) return
        val view = promptView ?: return
        val params = promptLayoutParams ?: return
        if (!promptHidden) return
        runCatching { windowManager.addView(view, params) }.onSuccess {
            promptHidden = false
            view.text = latestPrompt.orEmpty()
            updatePromptVisibility()
            render()
        }
    }

    private fun promptActions() {
        if (!promptSession.acceptsWindow()) return
        val session = promptSession.ticket()
        promptDialog?.dismiss()
        val dialog = AlertDialog.Builder(serviceContext)
            .setTitle("运行提示")
            .setMessage("关闭后本次运行不再接收提示；后台隐藏后继续接收，可从运行浮球重新打开。")
            .setPositiveButton("关闭提示") { _, _ ->
                if (!promptSession.acceptsWindow(session)) return@setPositiveButton
                promptSession.closeWindow(session)
                removePromptView()
                render()
                onPromptClosed()
            }
            .setNeutralButton("后台") { _, _ ->
                if (!promptSession.acceptsWindow(session)) return@setNeutralButton
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
        promptDialog = dialog
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
        val toggle = ZhigouMarkView(appContext).apply {
            layoutParams = LinearLayout.LayoutParams(dp(35), dp(35))
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
        (toggleView as? ZhigouMarkView)?.brandState = runtimeBrandState(runtimeState, normalCompletion)
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
                    updatePromptVisibility()
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

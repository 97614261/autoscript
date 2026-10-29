package com.autoscript.runtime.service

import android.content.Context
import android.app.AlertDialog
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.EditText
import android.widget.ScrollView
import android.text.InputType
import android.text.InputFilter
import com.autoscript.runtime.api.InputPointAction
import com.autoscript.runtime.api.InputPointPickReply
import kotlin.math.abs

/** Main-thread-only, input-free selection windows. No editor, Lua or Root implementation dependency. */
internal class RuntimeInputPointOverlay(
    private val context: Context,
    private val onFinished: (InputPointPickReply) -> Unit,
) {
    private val manager = context.getSystemService(WindowManager::class.java)
    private val windows = mutableListOf<View>()
    private var start: View? = null
    private var end: View? = null
    private var panel: LinearLayout? = null
    private var durationDialog: AlertDialog? = null
    private var coordinateText: TextView? = null
    private var requestId = 0L
    private var features = 0
    private var action = InputPointAction.TAP
    private var durationMs = 800
    private var screen = Point()
    private var rotation = 0
    private val preferences = context.getSharedPreferences("input-point-toolbar", Context.MODE_PRIVATE)
    private var panelLeft: Int? = null
    private var panelTop: Int? = null
    private val positionKey get() = if (screen.x > screen.y) "landscape" else "portrait"
    private val chrome = 0xE61E2531.toInt()
    private val blue = 0xFF3F6BFF.toInt()
    private val gray = 0xFF858D9B.toInt()

    fun show(id: Long, initialAction: InputPointAction, inputFeatures: Int) {
        hide()
        requestId = id
        action = initialAction
        features = inputFeatures
        screen = displaySize()
        panelLeft = preferences.getFloat("$positionKey-x", Float.NaN).takeIf { it.isFinite() }?.let { (it.coerceIn(0f, 1f) * screen.x).toInt() }
        panelTop = preferences.getFloat("$positionKey-y", Float.NaN).takeIf { it.isFinite() }?.let { (it.coerceIn(0f, 1f) * screen.y).toInt() }
        @Suppress("DEPRECATION")
        rotation = manager.defaultDisplay.rotation
        try {
            if (action != InputPointAction.UP) start = marker("起", blue, screen.x / 2, screen.y / 2)
            if (action.needsEnd) ensureEnd()
            renderPanel(expanded = false)
        } catch (_: Exception) {
            finish(InputPointPickReply.FAILED, "无法创建选点悬浮窗，请检查悬浮窗权限")
        }
    }

    fun hide() {
        durationDialog?.let { runCatching { it.dismiss() } }
        durationDialog = null
        windows.toList().forEach { runCatching { manager.removeView(it) } }
        windows.clear()
        start = null
        end = null
        panel = null
        coordinateText = null
        requestId = 0L
    }

    private fun displaySize(): Point = Point().also {
        @Suppress("DEPRECATION")
        manager.defaultDisplay.getRealSize(it)
    }

    private fun params(width: Int, height: Int, left: Int, top: Int) = WindowManager.LayoutParams(
        width, height,
        if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        },
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = left
        y = top
        if (Build.VERSION.SDK_INT >= 28) layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
    }

    private fun marker(label: String, color: Int, x: Int, y: Int): View {
        val size = dp(48)
        val view = InputPointReticleView(context, label, color)
        val layout = params(size, size, (x - size / 2).coerceIn(-size / 2, screen.x - size / 2 - 1),
            (y - size / 2).coerceIn(-size / 2, screen.y - size / 2 - 1))
        var rawX = 0f
        var rawY = 0f
        var left = 0
        var top = 0
        var moved = false
        val slop = ViewConfiguration.get(context).scaledTouchSlop
        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    rawX = event.rawX
                    rawY = event.rawY
                    left = layout.x
                    top = layout.y
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - rawX
                    val dy = event.rawY - rawY
                    if (abs(dx) > slop || abs(dy) > slop) moved = true
                    if (moved) {
                        // Each marker owns its own layout; moving the end never changes the start.
                        layout.x = (left + dx.toInt()).coerceIn(-size / 2, screen.x - size / 2 - 1)
                        layout.y = (top + dy.toInt()).coerceIn(-size / 2, screen.y - size / 2 - 1)
                        runCatching { manager.updateViewLayout(view, layout) }
                        updateCoordinateText()
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) view.performClick()
                    true
                }
                MotionEvent.ACTION_CANCEL -> true
                else -> false
            }
        }
        view.setOnClickListener { guardUi { renderPanel(expanded = true) } }
        view.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateCoordinateText() }
        add(view, layout)
        return view
    }

    private fun ensureEnd() {
        if (end == null) end = marker("终", 0xFFFF923D.toInt(), screen.x * 2 / 3, screen.y * 2 / 3)
    }

    private fun remove(view: View?) {
        if (view == null) return
        windows.remove(view)
        runCatching { manager.removeView(view) }
    }

    private fun center(view: View?): Point {
        if (view == null) return Point()
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        return Point((location[0] + view.width / 2).coerceIn(0, screen.x - 1),
            (location[1] + view.height / 2).coerceIn(0, screen.y - 1))
    }

    private fun renderPanel(expanded: Boolean) {
        remove(panel)
        coordinateText = null
        val width = minOf(dp(340), screen.x - dp(16)).coerceAtLeast(1)
        val controlHeight = dp((32 * context.resources.configuration.fontScale.coerceIn(1f, 2f)).toInt())
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
            background = shape(chrome, 0xFF394356.toInt(), dp(8).toFloat())
            elevation = dp(8).toFloat()
        }
        val toolbar = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        val handle = text("◎\n${action.title}", 10f, 0xFFFFFFFF.toInt()).apply {
            contentDescription = "${action.title}：点击切换设置，拖动工具条"
            background = shape(blue, blue, dp(4).toFloat())
            setOnClickListener { guardUi { renderPanel(!expanded) } }
        }
        toolbar.addView(handle, LinearLayout.LayoutParams(dp(42), controlHeight + dp(6)))
        coordinateText = text("", 10f, 0xFFD5DCE8.toInt())
        updateCoordinateText()
        toolbar.addView(coordinateText, LinearLayout.LayoutParams(0, -2, 1f))
        toolbar.addView(button(if (expanded) "收起" else "设置") { renderPanel(!expanded) }, LinearLayout.LayoutParams(dp(44), controlHeight))
        toolbar.addView(button("确定") { finish(InputPointPickReply.SUCCESS) }.apply {
            background = shape(0xFF1FA971.toInt(), 0xFF1FA971.toInt(), dp(4).toFloat())
        }, LinearLayout.LayoutParams(dp(48), controlHeight))
        toolbar.addView(button("×") { finish(InputPointPickReply.CANCELLED, "已取消选点") }.apply {
            contentDescription = "取消选点"
        }, LinearLayout.LayoutParams(dp(30), controlHeight))
        root.addView(toolbar)
        if (expanded) {
            val body = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            InputPointAction.entries.chunked(3).forEach { actions ->
                val row = LinearLayout(context)
                actions.forEach { selected ->
                    row.addView(button(selected.title, selected.available(features)) {
                        action = selected
                        if (selected == InputPointAction.UP) { remove(start); start = null }
                        else if (start == null) start = marker("起", blue, screen.x / 2, screen.y / 2)
                        if (selected.needsEnd) ensureEnd() else { remove(end); end = null }
                        renderPanel(true)
                    }.apply { if (selected == action) background = shape(blue, blue, dp(3).toFloat()) },
                        LinearLayout.LayoutParams(0, controlHeight, 1f))
                }
                body.addView(row)
            }
            if (action.needsDuration) {
                val row = LinearLayout(context)
                row.addView(button("−100") { durationMs = (durationMs - 100).coerceAtLeast(100); renderPanel(true) }, LinearLayout.LayoutParams(0, controlHeight, 1f))
                row.addView(button("${durationMs}ms ✎") { editDuration() }, LinearLayout.LayoutParams(0, controlHeight, 1f))
                row.addView(button("+100") { durationMs = (durationMs + 100).coerceAtMost(5000); renderPanel(true) }, LinearLayout.LayoutParams(0, controlHeight, 1f))
                body.addView(row)
            }
            // The action row stays visible while only additional settings scroll.
            body.measure(View.MeasureSpec.makeMeasureSpec((width - dp(8)).coerceAtLeast(1), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            val bodyHeight = inputPointPanelBodyHeight(screen.y, dp(16), controlHeight + dp(14), body.measuredHeight)
            root.addView(ScrollView(context).apply { isFillViewport = false; addView(body) }, LinearLayout.LayoutParams(-1, bodyHeight))
        }
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec((screen.y - dp(16)).coerceAtLeast(1), View.MeasureSpec.AT_MOST))
        panel = root
        val position = inputPointPanelPosition(panelLeft ?: (screen.x - width) / 2,
            panelTop ?: screen.y / 3, width, root.measuredHeight, screen.x, screen.y)
        val layout = params(width, -2, position.first, position.second)
        add(root, layout)
        installPanelDrag(handle, root, layout)
    }

    private fun installPanelDrag(handle: View, root: View, layout: WindowManager.LayoutParams) {
        var rawX = 0f
        var rawY = 0f
        var left = 0
        var top = 0
        var moved = false
        val slop = ViewConfiguration.get(context).scaledTouchSlop
        handle.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    rawX = event.rawX; rawY = event.rawY
                    left = layout.x; top = layout.y; moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - rawX
                    val dy = event.rawY - rawY
                    if (abs(dx) > slop || abs(dy) > slop) moved = true
                    if (moved) {
                        val position = inputPointPanelPosition(left + dx.toInt(), top + dy.toInt(),
                            root.width, root.height, screen.x, screen.y)
                        layout.x = position.first; layout.y = position.second
                        panelLeft = layout.x; panelTop = layout.y
                        guardUi { manager.updateViewLayout(root, layout) }
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (moved) preferences.edit().putFloat("$positionKey-x", layout.x.toFloat() / screen.x.coerceAtLeast(1))
                        .putFloat("$positionKey-y", layout.y.toFloat() / screen.y.coerceAtLeast(1)).apply()
                    else if (event.actionMasked == MotionEvent.ACTION_UP) handle.performClick()
                    true
                }
                else -> false
            }
        }
    }

    private fun updateCoordinateText() {
        val first = center(start)
        val last = center(end)
        coordinateText?.text = if (action == InputPointAction.UP) "仅释放本任务持有的单指"
            else "起点 ${first.x},${first.y}" + if (action.needsEnd) " → 终点 ${last.x},${last.y}" else ""
    }

    private fun finish(status: Int, message: String = "") {
        if (requestId == 0L) return
        @Suppress("DEPRECATION")
        val changed = displaySize() != screen || manager.defaultDisplay.rotation != rotation
        val first = center(start)
        val last = center(end)
        val missingPoint = status == InputPointPickReply.SUCCESS &&
            ((action != InputPointAction.UP && (start == null || start?.width == 0)) || (action.needsEnd && (end == null || end?.width == 0)))
        val reply = InputPointPickReply(requestId, if (changed) InputPointPickReply.CANCELLED else if (missingPoint) InputPointPickReply.FAILED else status,
            action.wire, screen.x, screen.y, first.x, first.y, last.x, last.y, durationMs,
            if (changed) "屏幕方向或尺寸已变化，请重新选点" else if (missingPoint) "点位尚未就绪，请重新选择" else message)
        hide()
        onFinished(reply)
    }

    private fun add(view: View, layout: WindowManager.LayoutParams) {
        manager.addView(view, layout)
        windows += view
    }

    private fun button(label: String, enabled: Boolean = true, click: () -> Unit): TextView = text(label, 11f, if (enabled) 0xFFFFFFFF.toInt() else gray).apply {
        background = shape(0xFF3A4252.toInt(), chrome, dp(4).toFloat())
        isEnabled = enabled
        setOnClickListener { guardUi(click) }
    }

    private fun guardUi(operation: () -> Unit) {
        try { operation() } catch (_: Exception) { finish(InputPointPickReply.FAILED, "悬浮窗口不可用，请重新选择") }
    }

    private fun editDuration() {
        durationDialog?.dismiss()
        val input = EditText(context).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            filters = arrayOf(InputFilter.LengthFilter(4))
            setText(durationMs.toString())
            selectAll()
        }
        val dialog = AlertDialog.Builder(context).setTitle("时长（100–5000ms）").setView(input)
            .setNegativeButton("取消", null).setPositiveButton("确定", null).create()
        dialog.window?.setType(if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else {
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        })
        durationDialog = dialog
        dialog.setOnDismissListener { if (durationDialog === dialog) durationDialog = null }
        dialog.show()
        dialog.window?.setLayout(minOf(dp(320), screen.x - dp(16)).coerceAtLeast(1), WindowManager.LayoutParams.WRAP_CONTENT)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val value = input.text.toString().toIntOrNull()
            if (value == null || value !in 100..5000) input.error = "请输入100–5000毫秒"
            else guardUi { durationMs = value; dialog.dismiss(); renderPanel(true) }
        }
    }

    private fun text(label: String, size: Float, color: Int) = TextView(context).apply {
        text = label
        textSize = size
        setTextColor(color)
        gravity = Gravity.CENTER
        includeFontPadding = false
    }
    private fun shape(fill: Int, stroke: Int, radius: Float) = GradientDrawable().apply {
        setColor(fill)
        setStroke(dp(1), stroke)
        cornerRadius = radius
    }
    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()
}

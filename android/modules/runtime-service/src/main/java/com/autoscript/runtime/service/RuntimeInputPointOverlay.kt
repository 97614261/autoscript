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
    private var start: TextView? = null
    private var end: TextView? = null
    private var panel: LinearLayout? = null
    private var durationDialog: AlertDialog? = null
    private var requestId = 0L
    private var features = 0
    private var action = InputPointAction.TAP
    private var durationMs = 800
    private var screen = Point()
    private var rotation = 0
    private val blue = 0xFF3F6BFF.toInt()
    private val gray = 0xFF858D9B.toInt()

    fun show(id: Long, initialAction: InputPointAction, inputFeatures: Int) {
        hide()
        requestId = id
        action = initialAction
        features = inputFeatures
        screen = displaySize()
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

    private fun marker(label: String, color: Int, x: Int, y: Int): TextView {
        val size = dp(48)
        val view = text("☝\n$label", 16f, color).apply {
            background = shape(0xEEFFFFFF.toInt(), color, dp(24).toFloat())
            contentDescription = "$label 点位；拖动定位，点击设置；不会点击目标应用"
        }
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
        val width = minOf(dp(320), screen.x - dp(16)).coerceAtLeast(1)
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(6), dp(8), dp(6))
            background = shape(0xFFFFFFFF.toInt(), 0xFFD9DEE7.toInt(), dp(5).toFloat())
            elevation = dp(8).toFloat()
        }
        root.addView(text("${action.title} · 拖手指定位，点击手指设置", 12f, blue).apply {
            setOnClickListener { guardUi { renderPanel(!expanded) } }
        }, LinearLayout.LayoutParams(-1, dp(34)))
        if (expanded) {
            InputPointAction.entries.chunked(3).forEach { actions ->
                val row = LinearLayout(context)
                actions.forEach { selected ->
                    row.addView(button(selected.title, selected.available(features)) {
                        action = selected
                        if (selected == InputPointAction.UP) { remove(start); start = null }
                        else if (start == null) start = marker("起", blue, screen.x / 2, screen.y / 2)
                        if (selected.needsEnd) ensureEnd() else { remove(end); end = null }
                        renderPanel(true)
                    }.apply { if (selected == action) background = shape(0xFFEFF3FF.toInt(), blue, dp(3).toFloat()) },
                        LinearLayout.LayoutParams(0, dp(38), 1f))
                }
                root.addView(row)
            }
            val first = center(start)
            val last = center(end)
            root.addView(text(if (action == InputPointAction.UP) "仅释放本任务持有的单指"
                else "起点 ${first.x},${first.y}" + if (action.needsEnd) "  →  终点 ${last.x},${last.y}" else "",
                11f, gray), LinearLayout.LayoutParams(-1, dp(32)))
            if (action.needsDuration) {
                val row = LinearLayout(context)
                row.addView(button("−100") { durationMs = (durationMs - 100).coerceAtLeast(100); renderPanel(true) }, LinearLayout.LayoutParams(0, dp(38), 1f))
                row.addView(button("${durationMs}ms ✎") { editDuration() }, LinearLayout.LayoutParams(0, dp(38), 1f))
                row.addView(button("+100") { durationMs = (durationMs + 100).coerceAtMost(5000); renderPanel(true) }, LinearLayout.LayoutParams(0, dp(38), 1f))
                root.addView(row)
            }
        }
        val footer = LinearLayout(context)
        footer.addView(button("取消") { finish(InputPointPickReply.CANCELLED, "已取消选点") }, LinearLayout.LayoutParams(0, dp(40), 1f))
        footer.addView(button(if (expanded) "确认点位" else "设置动作") {
            if (expanded) finish(InputPointPickReply.SUCCESS) else renderPanel(true)
        }, LinearLayout.LayoutParams(0, dp(40), 1f))
        root.addView(footer)
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(screen.y - dp(16), View.MeasureSpec.AT_MOST))
        panel = root
        add(root, params(width, -2, (screen.x - width) / 2,
            (screen.y - root.measuredHeight - dp(32)).coerceAtLeast(0)))
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

    private fun button(label: String, enabled: Boolean = true, click: () -> Unit): TextView = text(label, 13f, if (enabled) blue else gray).apply {
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

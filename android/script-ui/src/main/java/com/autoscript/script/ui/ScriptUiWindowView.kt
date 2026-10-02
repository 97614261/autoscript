package com.autoscript.script.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import java.io.File
import kotlin.math.roundToInt

/** Shared native window chrome for Studio, standalone Runner and runtime overlays. */
class ScriptUiWindowView(
    context: Context,
    definition: ScriptUiDefinition,
    initial: Map<String, String> = definition.initialValues(),
    image: (String) -> File? = { null },
    title: String = "脚本界面",
    private val onEvent: (String, String, String, String?) -> Unit,
) : LinearLayout(context) {
    val renderer = ScriptUiView(context, definition, initial, image, onEvent)
    var onViewportChanged: (() -> Unit)? = null
    var onDrag: ((Float, Float) -> Unit)? = null
    private var running = false
    private var busy = false
    private val error = TextView(context)

    init {
        orientation = VERTICAL
        background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = dp(8).toFloat() }
        clipToOutline = true
        val header = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL; setBackgroundColor(Color.rgb(243, 246, 251)) }
        val titleView = TextView(context).apply {
            text = title; textSize = 12f; setTextColor(Color.rgb(56, 106, 255)); gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), 0, dp(4), 0); maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            var x = 0f; var y = 0f
            setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { x = event.rawX; y = event.rawY; true }
                    MotionEvent.ACTION_MOVE -> { onDrag?.invoke(event.rawX - x, event.rawY - y); x = event.rawX; y = event.rawY; true }
                    MotionEvent.ACTION_UP -> { performClick(); true }
                    MotionEvent.ACTION_CANCEL -> true
                    else -> false
                }
            }
        }
        header.addView(titleView, LayoutParams(0, dp(32), 1f))
        header.addView(button("⋯", "显示方式和恢复默认") { anchor ->
            PopupMenu(context, anchor).apply {
                menu.add(0, 1, 0, "整页适配").isChecked = renderer.displayMode == UiDisplayMode.FIT_PAGE
                menu.add(0, 2, 1, "按宽显示 · 纵向滚动").isChecked = renderer.displayMode == UiDisplayMode.WIDTH_SCROLL
                menu.setGroupCheckable(0, true, true)
                if (!running) menu.add(1, 3, 2, "恢复默认").isEnabled = !busy
                setOnMenuItemClickListener { item ->
                    when (item.itemId) {
                        1 -> renderer.setDisplayMode(UiDisplayMode.FIT_PAGE)
                        2 -> renderer.setDisplayMode(UiDisplayMode.WIDTH_SCROLL)
                        3 -> { renderer.resetDefaults(); error(null) }
                    }
                    true
                }
                show()
            }
        }, LayoutParams(dp(32), dp(32)))
        header.addView(button("−", "最小化") { if (!busy) onEvent("window", "click", "", "minimize") }, LayoutParams(dp(32), dp(32)))
        header.addView(button("×", "关闭") { if (!busy) onEvent("window", "click", "", "close") }, LayoutParams(dp(32), dp(32)))
        addView(header, LayoutParams(LayoutParams.MATCH_PARENT, dp(32)))
        error.apply { textSize = 11f; setTextColor(Color.rgb(220, 50, 50)); setPadding(dp(8), 0, dp(8), 0); maxLines = 2; visibility = View.GONE }
        addView(error, LayoutParams(LayoutParams.MATCH_PARENT, dp(28)))
        addView(renderer, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        renderer.onPageChanged = { onViewportChanged?.invoke() }
        setOnApplyWindowInsetsListener { _, insets -> onViewportChanged?.invoke(); insets }
        viewTreeObserver.addOnGlobalLayoutListener { onViewportChanged?.invoke() }
    }

    fun values() = renderer.values()
    fun setRuntime(value: Boolean) { running = value; renderer.setRuntime(value) }
    fun setBusy(value: Boolean) { busy = value; renderer.setInteractionEnabled(!value) }
    fun error(message: String?) {
        error.text = message.orEmpty(); error.visibility = if (message.isNullOrBlank()) View.GONE else View.VISIBLE
        onViewportChanged?.invoke()
    }
    fun desiredSize(bounds: UiScreenBounds): UiWindowSize {
        val model = renderer.definition
        val chrome = dp(64 + (if (model.description.isNullOrBlank()) 0 else 28) + (if (model.pages.size > 1) 32 else 0) + (if (error.visibility == View.VISIBLE) 28 else 0))
        return ScriptUiGeometry.window(renderer.currentPage(), bounds, resources.displayMetrics.density, chrome,
            if (model.version == 1) dp(model.fields.size * 56) else null)
    }
    private fun button(label: String, description: String, action: (View) -> Unit) = Button(context).apply {
        text = label; textSize = 14f; contentDescription = description; minWidth = 0; minHeight = 0; minimumWidth = 0; minimumHeight = 0
        includeFontPadding = false; setPadding(0, 0, 0, 0); setBackgroundColor(Color.TRANSPARENT); setTextColor(Color.rgb(65, 85, 115))
        setOnClickListener { action(it) }
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()
}

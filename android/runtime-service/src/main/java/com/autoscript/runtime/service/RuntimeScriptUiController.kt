package com.autoscript.runtime.service

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.Toast
import com.autoscript.script.ui.ScriptUiDefinition
import com.autoscript.script.ui.ScriptUiWindowView
import com.autoscript.script.ui.ScriptUiDisplay
import com.autoscript.script.ui.UiScreenBounds
import com.autoscript.script.ui.UiWindowSize
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean

/** Owns Views on the main thread; callbacks carry an epoch, never a native pointer. */
internal class RuntimeScriptUiController(
    private val context: Context,
    private val onEvent: (Long, String, String, String, Boolean) -> Unit,
    private val onStop: () -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private val windows = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val epoch = AtomicLong()
    private val pendingCommands = AtomicInteger()
    private val commandsOverflowed = AtomicBoolean()
    private var definition: ScriptUiDefinition? = null
    private var values = emptyMap<String, String>()
    private var images = emptyMap<String, File>()
    private var root: View? = null
    private var renderer: ScriptUiWindowView? = null
    private var bubble: Button? = null
    private var active = false
    @Volatile var initialValuesJson: String = "{}"
        private set
    private var params: WindowManager.LayoutParams? = null
    private var lastBounds: UiScreenBounds? = null

    fun accepts(token: Long) = token == epoch.get()
    fun clear() {
        epoch.incrementAndGet()
        main.post { removeWindows(); definition = null; values = emptyMap(); images = emptyMap(); active = false }
    }

    fun prepare(source: String, rawValues: String, paths: Array<out String>, files: Array<out String>): Boolean {
        if (!Settings.canDrawOverlays(context) || paths.size != files.size || paths.size > 256 || rawValues.length > 262144) return false
        val parsed = runCatching {
            val model = ScriptUiDefinition.parse(source)
            require(source.toByteArray(Charsets.UTF_8).size <= 262144)
            val objectValues = JSONObject(rawValues)
            require(objectValues.length() <= 128)
            val supplied = objectValues.keys().asSequence().associateWith { objectValues.getString(it) }
            require(supplied.keys.all { id -> model.fields.any { it.id == id } })
            val validated = model.validateValues(supplied)
            val privateRoot = context.filesDir.canonicalFile
            val loadedImages = paths.indices.associate { i ->
                require(paths[i].startsWith("assets/images/") && !paths[i].contains(".."))
                val file = File(files[i]).canonicalFile
                require(file.path.startsWith(privateRoot.path + File.separator) && file.isFile)
                paths[i] to file
            }
            Triple(model, validated, loadedImages)
        }.getOrNull() ?: return false
        val done = CountDownLatch(1)
        val token = epoch.incrementAndGet()
        main.post {
            try {
                if (accepts(token)) {
                    removeWindows(); definition = parsed.first; values = parsed.second; images = parsed.third; active = false
                    commandsOverflowed.set(false)
                    initialValuesJson = JSONObject(values).toString()
                }
            } finally { done.countDown() }
        }
        val accepted = done.await(2, TimeUnit.SECONDS) && accepts(token)
        if (!accepted) clear()
        return accepted
    }

    /** Configuration was already confirmed in Studio/Runner; starting must not reopen it. */
    fun activateRuntime() {
        val token = epoch.get()
        main.post {
            if (accepts(token) && definition != null) {
                active = true
                // Keep hidden controls alive so UI.setValue/page commands are not dropped.
                runtimeView(token)
            }
        }
    }

    fun command(command: JSONObject) {
        if (commandsOverflowed.get()) return
        val token = epoch.get()
        if (pendingCommands.incrementAndGet() > 128) {
            pendingCommands.decrementAndGet()
            if (commandsOverflowed.compareAndSet(false, true)) main.post { if (accepts(token) && active) { Toast.makeText(context, "界面更新过快，已停止脚本", Toast.LENGTH_SHORT).show(); onStop() } }
            return
        }
        main.post {
            try {
            if (!accepts(token) || !active) return@post
            when (val operation = command.optString("operation")) {
                "show" -> show(token)
                "hide", "minimize" -> minimize(token)
                else -> {
                    val id = command.optString("id")
                    if (operation != "page" && definition?.fields?.none { it.id == id } != false) { onStop(); return@post }
                    runCatching { renderer?.renderer?.applyCommand(id, operation, command.optString("value")) }
                        .onFailure { Toast.makeText(context, "界面控件更新无效，已停止脚本", Toast.LENGTH_SHORT).show(); onStop() }
                }
            }
            } finally { pendingCommands.decrementAndGet() }
        }
    }

    private fun runtimeView(token: Long): ScriptUiWindowView? {
        val model = definition ?: return null
        return renderer ?: ScriptUiWindowView(context, model, values, { images[it] }) { id, event, value, action ->
                if (accepts(token) && active) {
                    if (id != "window") onEvent(token, id, event, value,
                        action?.startsWith("flow:") == true || model.fields.find { it.id == id }?.ui?.binding?.let { !it.startsWith("param:") } == true)
                    when {
                    action == "close" -> onStop()
                    action == "minimize" -> minimize(token)
                    action?.startsWith("page:") == true -> renderer?.renderer?.switchPage(action.removePrefix("page:"))
                    }
                }
        }.apply { setRuntime(true) }.also { renderer = it }
    }

    private fun show(token: Long) {
        if (root != null) return
        bubble?.let { runCatching { windows.removeViewImmediate(it) } }; bubble = null
        val view = runtimeView(token) ?: return
        (view.parent as? android.view.ViewGroup)?.removeView(view)
        val bounds = ScriptUiDisplay.bounds(context)
        val size = view.desiredSize(bounds)
        val lp = params ?: windowParams(size.width, size.height, false).apply {
            gravity = Gravity.TOP or Gravity.START
            val origin = bounds.centered(size); x = origin.first; y = origin.second
        }
        val position = bounds.clamp(lp.x, lp.y, size)
        lp.width = size.width; lp.height = size.height; lp.x = position.first; lp.y = position.second
        view.onViewportChanged = { if (accepts(token)) resize() }
        view.onDrag = { dx, dy ->
            if (accepts(token) && root === view) {
                val screen = ScriptUiDisplay.bounds(context, view)
                val target = screen.clamp(lp.x + dx.toInt(), lp.y + dy.toInt(), UiWindowSize(lp.width, lp.height))
                lp.x = target.first; lp.y = target.second
                runCatching { windows.updateViewLayout(view, lp) }
            }
        }
        runCatching {
            windows.addView(view, lp); root = view; params = lp; lastBounds = bounds
            view.requestApplyInsets()
        }.onFailure { onStop() }
    }

    fun onConfigurationChanged() {
        val token = epoch.get()
        main.post { if (accepts(token)) resize() }
    }

    private fun resize() {
        val view = renderer ?: return
        val screen = ScriptUiDisplay.bounds(context, if (root != null) view else null)
        val size = view.desiredSize(screen)
        val lp = params ?: return
        if (screen == lastBounds && size.width == lp.width && size.height == lp.height) return
        val old = lastBounds
        val target = if (old != null && (old.width != screen.width || old.height != screen.height)) screen.centered(size)
            else screen.clamp(lp.x, lp.y, size)
        lp.width = size.width; lp.height = size.height; lp.x = target.first; lp.y = target.second; lastBounds = screen
        root?.let { runCatching { windows.updateViewLayout(it, lp) }.onFailure { if (active) onStop() } }
        bubble?.let { button ->
            val bubbleParams = button.layoutParams as? WindowManager.LayoutParams ?: return@let
            val origin = screen.clamp(screen.left + screen.width - bubbleParams.width, screen.top + screen.height / 2, UiWindowSize(bubbleParams.width, bubbleParams.height))
            bubbleParams.x = origin.first; bubbleParams.y = origin.second
            runCatching { windows.updateViewLayout(button, bubbleParams) }
        }
    }

    private fun minimize(token: Long) {
        val previous = root; root = null
        previous?.let { runCatching { windows.removeViewImmediate(it) } }
        if (bubble != null) return
        val button = Button(context).apply { text = "界面"; textSize = 12f; setOnClickListener { if (accepts(token)) show(token) } }
        val bounds = ScriptUiDisplay.bounds(context)
        val size = UiWindowSize(dp(64).coerceAtMost(bounds.width), dp(36).coerceAtMost(bounds.height))
        val lp = windowParams(size.width, size.height, true).apply {
            gravity = Gravity.TOP or Gravity.START
            val origin = bounds.clamp(bounds.left + bounds.width - size.width, bounds.top + bounds.height / 2, size)
            x = origin.first; y = origin.second
        }
        runCatching { windows.addView(button, lp); bubble = button }.onFailure { onStop() }
    }
    private fun removeWindows() {
        renderer?.onViewportChanged = null; renderer?.onDrag = null
        root?.let { runCatching { windows.removeViewImmediate(it) } }
        bubble?.let { runCatching { windows.removeViewImmediate(it) } }
        root = null; bubble = null; renderer = null; params = null; lastBounds = null
    }
    private fun windowParams(width: Int, height: Int, floating: Boolean) = WindowManager.LayoutParams(
        width, height, if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or (if (floating) WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE else 0), android.graphics.PixelFormat.TRANSLUCENT,
    ).apply { softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE }
    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
}

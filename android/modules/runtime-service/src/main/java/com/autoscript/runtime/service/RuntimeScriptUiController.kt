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
import com.autoscript.script.ui.MAX_SCRIPT_UI_CONTROLS
import com.autoscript.script.ui.ScriptUiWindowView
import com.autoscript.script.ui.ScriptUiDisplay
import com.autoscript.script.ui.ScriptUiRuntimeProperty
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
    private val dialogs = RuntimeInteractionDialogs(context, main)
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
    private val positions=com.autoscript.script.ui.ScriptUiWindowPositions()

    fun accepts(token: Long) = token == epoch.get()
    fun currentToken(): Long = epoch.get()
    fun clear() {
        val token = epoch.incrementAndGet()
        main.post {
            if (accepts(token)) {
                removeWindows(); definition = null; values = emptyMap(); images = emptyMap(); active = false
            }
        }
    }

    fun prepare(source: String, rawValues: String, paths: Array<out String>, files: Array<out String>): Boolean {
        if (!Settings.canDrawOverlays(context) || paths.size != files.size || paths.size > 256 || rawValues.length > 262144) return false
        val parsed = runCatching {
            val model = ScriptUiDefinition.parse(source)
            require(source.toByteArray(Charsets.UTF_8).size <= 262144)
            val objectValues = JSONObject(rawValues)
            require(objectValues.length() <= MAX_SCRIPT_UI_CONTROLS)
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
    fun restartRuntime(rawValues: String, expectedToken: Long): Long {
        val done = CountDownLatch(1)
        val succeeded = AtomicBoolean(false)
        val token = expectedToken + 1
        if (!epoch.compareAndSet(expectedToken, token)) return 0L
        main.post {
            try {
                if (accepts(token)) {
                    val objectValues = JSONObject(rawValues)
                    val supplied = objectValues.keys().asSequence().associateWith { objectValues.getString(it) }
                    val validated = definition?.validateValues(supplied) ?: emptyMap()
                    removeWindows()
                    positions.reset()
                    values = validated
                    initialValuesJson = rawValues
                    commandsOverflowed.set(false)
                    active = false
                    succeeded.set(true)
                }
            } catch (_: Exception) {
                succeeded.set(false)
            } finally { done.countDown() }
        }
        return if (done.await(2, TimeUnit.SECONDS) && succeeded.get() && accepts(token)) token else 0L
    }

    fun activateRuntime() {
        val token = epoch.get()
        main.post {
            if (accepts(token)) {
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
                "hide" -> hide()
                "minimize" -> minimize(token)
                "dialogClose" -> dialogs.close()
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

    /** Host completion follows this main-thread operation, including when no page is mounted. */
    fun requestProperty(token: Long, id: String, operation: String, value: String,
        cancelled: () -> Boolean, completion: (Result<String>) -> Unit) {
        if (operation in setOf("dialogConfirm", "dialogShow")) {
            val posted = main.post {
                runCatching {
                    check(accepts(token) && active && !cancelled()) { "UI_SESSION_STALE" }
                    val request = com.autoscript.script.ui.ScriptDialogRequest.parse(id, operation, value)
                    dialogs.open(token, request, cancelled, { accepts(token) && active },
                        if (request.pageId != null) runtimeView(token) else null,
                        beforeLayout = {
                            val visible = root != null
                            val floating = bubble != null
                            val view = requireNotNull(renderer)
                            val page = view.renderer.currentPage().id
                            hide()
                            view.onDrag = null
                            ({
                                (view.parent as? android.view.ViewGroup)?.removeView(view)
                                view.renderer.setDialogMode(false)
                                view.setDialogTitle(null)
                                view.renderer.switchPage(page)
                                view.onViewportChanged = { if (accepts(token)) resize() }
                                if (accepts(token) && active) { if (visible) show(token) else if (floating) minimize(token) }
                            })
                        }, complete = completion)
                }.onFailure { completion(Result.failure(it)) }
            }
            if (!posted) completion(Result.failure(IllegalStateException("UI_UNAVAILABLE")))
            return
        }
        if (pendingCommands.incrementAndGet() > 128) {
            pendingCommands.decrementAndGet()
            completion(Result.failure(IllegalStateException("UI_QUEUE_FULL")))
            return
        }
        val posted = main.post {
            try {
                completion(runCatching {
                    check(accepts(token) && active && !cancelled()) { "UI_SESSION_STALE" }
                    val view = requireNotNull(runtimeView(token)).renderer
                    if(operation in setOf("get:pages","get:controls")) {
                        require(id=="window")
                        return@runCatching if(operation=="get:pages") {require(value.isEmpty());view.describePages()} else view.describeControls(value)
                    }
                    if(operation in setOf("windowPosition","bubblePosition","get:windowPosition","get:bubblePosition")) {
                        require(id=="window" && !dialogs.isOpen) {"窗口位置当前不可修改或查询"}
                        return@runCatching positionCommand(operation,value)
                    }
                    if(operation=="get:config" || operation=="restoreConfig") {
                        require(id=="window")
                        return@runCatching if(operation=="get:config") {require(value.isEmpty()); view.exportConfig()}
                            else {view.restoreConfig(value); ""}
                    }
                    if (operation.startsWith("get:")) {
                        require(value.isEmpty())
                        if(operation in setOf("get:backgroundImage","get:groupCount")) view.readExtension(id,operation)
                        else view.readProperty(id, ScriptUiRuntimeProperty.fromOperation(operation.removePrefix("get:")))
                    } else {
                        require(operation in setOf("backgroundImage","groupVisible","groupEnabled","groupReset","hint","minimum","maximum","step","range") || operation in com.autoscript.script.ui.ScriptUiListCommands.writes || ScriptUiRuntimeProperty.fromOperation(operation).presentation)
                        view.applyCommand(id, operation, value)
                        if(operation=="groupReset") view.values().forEach {(control,current)->onEvent(token,control,"change",current,false)}
                        if(operation in com.autoscript.script.ui.ScriptUiListCommands.writes) {
                            onEvent(token,id,"change",view.readProperty(id,ScriptUiRuntimeProperty.VALUE),false)
                        }
                        ""
                    }
                })
            } finally { pendingCommands.decrementAndGet() }
        }
        if (!posted) {
            pendingCommands.decrementAndGet()
            completion(Result.failure(IllegalStateException("UI_UNAVAILABLE")))
        }
    }
    fun cancelDialogRequest(token: Long) { main.post { dialogs.cancelIfRequested(token) } }

    private fun positionCommand(operation: String, value: String): String {
        resize()
        val screen=ScriptUiDisplay.bounds(context,root)
        val kind=if(operation.endsWith("bubblePosition")) "bubble" else "window"
        val size=if(kind=="window") requireNotNull(renderer).desiredSize(screen) else bubbleSize(screen)
        if(operation.startsWith("get:")) {
            require(value.isEmpty())
            return positions.snapshot(kind,screen,size,if(kind=="window") root!=null else bubble!=null)
        }
        val origin=positions.apply(kind,value,screen,size)
        val target=if(kind=="window") root else bubble
        val lp=if(kind=="window") params else bubble?.layoutParams as? WindowManager.LayoutParams
        lp?.let {
            it.x=origin.first; it.y=origin.second; it.width=size.width; it.height=size.height
            target?.let {view->windows.updateViewLayout(view,it)}
        }
        return ""
    }
    private fun bubbleSize(bounds: UiScreenBounds)=UiWindowSize(dp(64).coerceAtMost(bounds.width),dp(36).coerceAtMost(bounds.height))

    private fun runtimeView(token: Long): ScriptUiWindowView? {
        val model = definition ?: return null
        return renderer ?: ScriptUiWindowView(context, model, values, { images[it] }) { id, event, value, action ->
                if (accepts(token) && active) {
                    if (id != "window") onEvent(token, id, event, value,
                        action?.startsWith("flow:") == true || model.fields.find { it.id == id }?.ui?.binding?.let { !it.startsWith("param:") } == true)
                    when {
                    action == "close" -> if (dialogs.isLayout) dialogs.close() else onStop()
                    action == "minimize" -> if (dialogs.isLayout) dialogs.close() else minimize(token)
                    action?.startsWith("page:") == true -> renderer?.renderer?.switchPage(action.removePrefix("page:"))
                    }
                }
        }.apply { setRuntime(true) }.also { renderer = it }
    }

    private fun show(token: Long) {
        if (dialogs.isOpen) return
        if (root != null) return
        bubble?.let { runCatching { windows.removeViewImmediate(it) } }; bubble = null
        val view = runtimeView(token) ?: return
        (view.parent as? android.view.ViewGroup)?.removeView(view)
        val bounds = ScriptUiDisplay.bounds(context)
        val size = view.desiredSize(bounds)
        val lp = params ?: windowParams(size.width, size.height, false).apply {
            gravity = Gravity.TOP or Gravity.START
            val origin = positions.locate("window",bounds,size); x = origin.first; y = origin.second
        }
        val position = positions.locate("window",bounds,size)
        lp.width = size.width; lp.height = size.height; lp.x = position.first; lp.y = position.second
        view.onViewportChanged = { if (accepts(token)) resize() }
        view.onDrag = { dx, dy ->
            if (accepts(token) && root === view) {
                val screen = ScriptUiDisplay.bounds(context, view)
                val target = positions.move("window",lp.x + dx.toInt(), lp.y + dy.toInt(),screen,UiWindowSize(lp.width, lp.height))
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
        main.post { if (accepts(token)) { if (dialogs.isOpen) dialogs.resize() else resize() } }
    }

    private fun resize() {
        val view = renderer ?: return
        val screen = ScriptUiDisplay.bounds(context, if (root != null) view else null)
        val size = view.desiredSize(screen)
        params?.let {lp ->
            val target=positions.locate("window",screen,size)
            if(screen!=lastBounds || lp.width!=size.width || lp.height!=size.height || lp.x!=target.first || lp.y!=target.second) {
                lp.width=size.width; lp.height=size.height; lp.x=target.first; lp.y=target.second
                root?.let {runCatching {windows.updateViewLayout(it,lp)}.onFailure {if(active) onStop()}}
            }
        }
        lastBounds=screen
        bubble?.let { button ->
            val bubbleParams = button.layoutParams as? WindowManager.LayoutParams ?: return@let
            val bubbleSize=bubbleSize(screen)
            val origin = positions.locate("bubble",screen,bubbleSize)
            bubbleParams.width=bubbleSize.width; bubbleParams.height=bubbleSize.height
            bubbleParams.x = origin.first; bubbleParams.y = origin.second
            runCatching { windows.updateViewLayout(button, bubbleParams) }
        }
    }

    /** Unmount only: preserve controls and values for an explicit later show. */
    private fun hide() {
        root?.let { runCatching { windows.removeViewImmediate(it) } }; root = null
        bubble?.let { runCatching { windows.removeViewImmediate(it) } }; bubble = null
    }

    private fun minimize(token: Long) {
        if (dialogs.isOpen) return
        val previous = root; root = null
        previous?.let { runCatching { windows.removeViewImmediate(it) } }
        if (bubble != null) return
        val button = Button(context).apply { text = "界面"; textSize = 12f; setOnClickListener { if (accepts(token)) show(token) } }
        val bounds = ScriptUiDisplay.bounds(context)
        val size = bubbleSize(bounds)
        val lp = windowParams(size.width, size.height, true).apply {
            gravity = Gravity.TOP or Gravity.START
            val origin = positions.locate("bubble",bounds,size)
            x = origin.first; y = origin.second
        }
        runCatching { windows.addView(button, lp); bubble = button }.onFailure { onStop() }
    }
    private fun removeWindows() {
        dialogs.clear()
        renderer?.onViewportChanged = null; renderer?.onDrag = null
        root?.let { runCatching { windows.removeViewImmediate(it) } }
        bubble?.let { runCatching { windows.removeViewImmediate(it) } }
        root = null; bubble = null; renderer = null; params = null; lastBounds = null
        positions.reset()
    }
    private fun windowParams(width: Int, height: Int, floating: Boolean) = WindowManager.LayoutParams(
        width, height, if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or (if (floating) WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE else 0), android.graphics.PixelFormat.TRANSLUCENT,
    ).apply { softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE }
    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
}

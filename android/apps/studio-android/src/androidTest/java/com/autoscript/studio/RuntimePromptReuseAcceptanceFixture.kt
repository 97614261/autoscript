package com.autoscript.studio

import android.app.Activity
import android.app.AlertDialog
import android.app.Instrumentation
import android.os.SystemClock
import android.text.Editable
import android.text.TextWatcher
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import com.autoscript.runtime.api.RuntimeProtocol
import java.util.concurrent.Callable
import java.util.concurrent.FutureTask

/** Exercise the actual shared runtime controller without exposing its internals to Studio. */
internal object RuntimePromptReuseAcceptanceFixture {
    fun run(test: Instrumentation, activity: Activity, record: (String, () -> Unit) -> Unit) {
        fun <T> main(block: () -> T): T = FutureTask(Callable(block)).also(test::runOnMainSync).get()
        val type = Class.forName("com.autoscript.runtime.service.RuntimeOverlayController")
        val styleType = Class.forName("com.autoscript.runtime.service.RuntimePopupStyle")
        var expired = 0
        val nothing: () -> Unit = {}
        val onExpired: () -> Unit = { expired++ }
        val controller = main {
            type.constructors.single { it.parameterCount == 7 }.newInstance(
                activity, nothing, nothing, nothing, nothing, nothing, onExpired)
        }
        fun call(name: String, vararg args: Any): Any? =
            type.methods.single { it.name == name && it.parameterCount == args.size }.invoke(controller, *args)
        fun field(name: String): Any? = type.getDeclaredField(name).apply { isAccessible = true }.get(controller)
        fun privateCall(name: String) = type.getDeclaredMethod(name).apply { isAccessible = true }.invoke(controller)
        fun style(duration: Int = 30_000, x: Int = 40, y: Int = 80, width: Int = 220, font: Int = 24): Any =
            styleType.constructors.single { it.parameterCount == 10 }.newInstance(
                width, 80, x, y, 0xB3000000.toInt(), -1, font, 12, duration, "center")
        fun flush() = test.waitForIdleSync()
        fun reset() { main { call("clearRunPrompts"); call("resetRunPrompts") }; flush() }
        fun showToast(text: String, style: Any) { main { call("showPopupToast", text, style) }; flush() }
        fun showPrompt(text: String, style: Any) { main { call("showRunPrompt", text, style) }; flush() }
        fun view(name: String): TextView = main { requireNotNull(field(name)) as TextView }
        fun watcher(changed: () -> Unit) = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = changed()
            override fun afterTextChanged(s: Editable?) = Unit
        }
        check(main { call("canShow") == true }) { "QA_OVERLAY_PERMISSION_REQUIRED" }
        try {
            record("toast-reuses-attached-view-restarts-expiry-and-rejects-old-timer") {
                reset()
                showToast("QA_FIRST", style(duration = 500))
                val first = view("toastView")
                val token = main { first.windowToken }
                val background = main { first.background }
                val oldExpiry = main { field("toastExpiry") as Runnable }
                SystemClock.sleep(150)
                showToast("QA_LATEST", style(duration = 1_500))
                main {
                    check(field("toastView") === first && first.isAttachedToWindow && first.windowToken === token)
                    check(first.text.toString() == "QA_LATEST" && first.background === background)
                    check(field("toastExpiry") !== oldExpiry)
                    oldExpiry.run()
                    check(field("toastView") === first && call("hasPopupToast") == true)
                }
                SystemClock.sleep(550)
                main { check(field("toastView") === first && first.isAttachedToWindow) }
                val until = SystemClock.elapsedRealtime() + 2_000
                while (main { field("toastView") != null } && SystemClock.elapsedRealtime() < until) SystemClock.sleep(30)
                main { check(field("toastView") == null && !first.isAttachedToWindow) }
            }
            record("identical-toast-renews-timeout-without-text-or-background-reassignment") {
                reset()
                val same = style(duration = 600)
                showToast("QA_SAME", same)
                val first = view("toastView")
                var changes = 0
                val background = main { first.addTextChangedListener(watcher { changes++ }); first.background }
                SystemClock.sleep(250)
                showToast("QA_SAME", same)
                SystemClock.sleep(400)
                main {
                    check(field("toastView") === first && first.isAttachedToWindow)
                    check(first.background === background && changes == 0)
                }
            }
            record("toast-geometry-and-style-update-in-place-and-click-closes-latest-ticket") {
                reset()
                showToast("QA_BEFORE", style())
                val first = view("toastView")
                val before = expired
                showToast("QA_AFTER", style(x = 10, y = 20, width = 180, font = 20))
                main {
                    val params = field("toastLayoutParams") as WindowManager.LayoutParams
                    check(field("toastView") === first && params.x == 10 && params.y == 20 && params.width == 180)
                    check(first.textSize == 20f && first.contentDescription.toString().contains("QA_AFTER"))
                    first.performClick()
                    check(field("toastView") == null && call("hasPopupToast") == false && expired == before + 1)
                }
            }
            record("persistent-text-and-appearance-updates-retain-dragged-position-and-window") {
                reset()
                val original = style()
                showPrompt("QA_PROMPT", original)
                val first = view("promptView")
                var changes = 0
                main {
                    first.addTextChangedListener(watcher { changes++ })
                    val time = SystemClock.uptimeMillis()
                    listOf(MotionEvent.ACTION_DOWN to 10f, MotionEvent.ACTION_MOVE to 60f,
                        MotionEvent.ACTION_UP to 60f).forEachIndexed { index, (action, position) ->
                        val event = MotionEvent.obtain(time, time + index * 10L, action, position, position, 0)
                        try { first.dispatchTouchEvent(event) } finally { event.recycle() }
                    }
                }
                val dragged = main { (field("promptLayoutParams") as WindowManager.LayoutParams).let { it.x to it.y } }
                check(dragged == (90 to 130))
                val background = main { first.background }
                showPrompt("QA_CHANGED", original)
                showPrompt("QA_CHANGED", style(duration = 500))
                main {
                    check(field("promptView") === first && first.background === background && changes == 1)
                    val params = field("promptLayoutParams") as WindowManager.LayoutParams
                    check(params.x == dragged.first && params.y == dragged.second)
                }
                showPrompt("QA_COLORED", style(font = 20))
                main {
                    val params = field("promptLayoutParams") as WindowManager.LayoutParams
                    check(field("promptView") === first && first.textSize == 20f)
                    check(params.x == dragged.first && params.y == dragged.second)
                }
            }
            record("overlapping-toast-replacement-hides-and-restores-updated-persistent-prompt") {
                reset()
                val same = style()
                showPrompt("QA_PERSISTENT", same)
                val persistent = view("promptView")
                showToast("QA_OVERLAP", same)
                val transient = view("toastView")
                showPrompt("QA_HIDDEN_UPDATE", same)
                main { check(persistent.visibility == View.INVISIBLE && persistent.text.toString() == "QA_HIDDEN_UPDATE") }
                showToast("QA_SEPARATE", style(y = 300))
                main { check(field("toastView") === transient && persistent.visibility == View.VISIBLE) }
                showToast("QA_OVERLAP_AGAIN", same)
                main {
                    check(persistent.visibility == View.INVISIBLE)
                    transient.performClick()
                    check(persistent.visibility == View.VISIBLE && field("promptView") === persistent)
                }
            }
            record("background-hidden-prompt-updates-without-reopening-and-close-rejects-new-text") {
                reset()
                main { call("show", RuntimeProtocol.STATE_RUNNING) }; flush()
                showPrompt("QA_HIDE", style())
                val first = view("promptView")
                main { privateCall("promptActions") }; flush()
                main { (field("promptDialog") as AlertDialog).getButton(AlertDialog.BUTTON_NEUTRAL).performClick() }; flush()
                main { check(field("promptHidden") == true && !first.isAttachedToWindow) }
                showPrompt("QA_BACKGROUND_TEXT", style())
                main { check(field("promptView") === first && !first.isAttachedToWindow && first.text.toString() == "QA_BACKGROUND_TEXT") }
                main { privateCall("reopenRunPrompt") }; flush()
                main { check(first.isAttachedToWindow && field("promptHidden") == false); call("closeRunPrompt") }; flush()
                showPrompt("QA_REJECTED", style())
                main { check(field("promptView") == null) }
            }
            record("stop-and-restart-reject-queued-shows-and-old-expiry-without-resurrection") {
                reset()
                showToast("QA_OLD_RUN", style())
                val oldExpiry = main { field("toastExpiry") as Runnable }
                main {
                    call("showRunPrompt", "QA_QUEUED", style())
                    call("showPopupToast", "QA_QUEUED", style())
                    call("clearRunPrompts")
                }; flush()
                main { check(field("promptView") == null && field("toastView") == null && field("toastExpiry") == null) }
                reset()
                showToast("QA_NEW_RUN", style())
                val current = view("toastView")
                main { oldExpiry.run(); check(field("toastView") === current && call("hasPopupToast") == true) }
                main { call("closeRunPrompt") }; flush()
                main { check(field("toastView") === current); current.performClick(); check(field("toastView") == null) }
            }
        } finally {
            main { call("hide") }; flush()
        }
    }
}

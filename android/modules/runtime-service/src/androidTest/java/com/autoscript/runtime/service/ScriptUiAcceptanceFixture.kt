package com.autoscript.runtime.service

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ListView
import android.widget.ListAdapter
import android.widget.Spinner
import com.autoscript.script.ui.ScriptUiDefinition
import com.autoscript.script.ui.ScriptUiRuntimeProperty
import com.autoscript.script.ui.ScriptUiView
import com.autoscript.script.ui.UiControl
import com.autoscript.script.ui.UiDisplayMode
import com.autoscript.script.ui.UiField
import com.autoscript.script.ui.UiPage
import com.autoscript.script.ui.UiPresentation
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Exists only in the separate test APK; never opens a Studio project or binds a runtime service. */
class ScriptUiAcceptanceActivity : Activity()

internal object ScriptUiAcceptanceFixture {
    fun run(instrumentation: Instrumentation): JSONObject {
        val report = JSONObject().put("scope", "synthetic shared Android View tests; not Studio overlay/Root acceptance")
        val cases = JSONArray()
        report.put("cases", cases)
        val deadline = SystemClock.elapsedRealtime() + 45_000
        val handler = Handler(Looper.getMainLooper())
        fun main(action: () -> Unit) {
            val active = AtomicBoolean(true)
            val done = CountDownLatch(1)
            val error = AtomicReference<Throwable?>()
            check(handler.post {
                try { if (active.get()) action() } catch (failure: Throwable) { error.set(failure) }
                finally { done.countDown() }
            }) { "UI_QUEUE_CLOSED" }
            val remaining = (deadline - SystemClock.elapsedRealtime()).coerceIn(0, 5_000)
            if (!done.await(remaining, TimeUnit.MILLISECONDS)) {
                active.set(false)
                error("UI_MAIN_TIMEOUT")
            }
            error.get()?.let { throw it }
        }
        val monitor = instrumentation.addMonitor(ScriptUiAcceptanceActivity::class.java.name, null, false)
        var activity: Activity? = null
        var root: ScriptUiView? = null
        var success = true
        val events = mutableListOf<String>()
        try {
            instrumentation.targetContext.startActivity(Intent(instrumentation.targetContext, ScriptUiAcceptanceActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            activity = monitor.waitForActivityWithTimeout(5_000) ?: error("UI_ACTIVITY_TIMEOUT")
            val owner = requireNotNull(activity)
            fun mount(definition: ScriptUiDefinition, width: Int = 360, height: Int = 640) {
                definition.validate()
                main {
                    events.clear()
                    root = ScriptUiView(owner, definition) { id, event, value, action -> events.add("$id:$event:$value:${action.orEmpty()}") }
                    owner.setContentView(FrameLayout(owner).apply {
                        addView(root, FrameLayout.LayoutParams(width, height))
                    })
                }
                settle(width, height, root, ::main)
            }
            fun case(name: String, action: () -> Unit) {
                check(SystemClock.elapsedRealtime() < deadline) { "UI_SUITE_TIMEOUT" }
                try {
                    action()
                    cases.put(JSONObject().put("case", name).put("pass", true))
                } catch (failure: Throwable) {
                    success = false
                    cases.put(JSONObject().put("case", name).put("pass", false)
                        .put("error", failure.javaClass.simpleName)
                        .put("code", failure.message?.takeIf { it.matches(Regex("[A-Z_]{1,80}")) } ?: "UI_ASSERTION_FAILED"))
                }
            }
            case("all-controls-portrait-and-landscape-viewport") {
                val definition = allControls()
                for ((width, height) in listOf(360 to 640, 640 to 360)) {
                    mount(definition, width, height)
                    main {
                        val view = requireNotNull(root)
                        check(view.values() == definition.initialValues()) { "UI_INITIAL_VALUES_CHANGED" }
                        check(events.isEmpty()) { "UI_MOUNT_EMITTED_EVENT" }
                        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                        try { view.draw(Canvas(bitmap)) } finally { bitmap.recycle() }
                        check(view.width == width && view.height == height) { "UI_VIEWPORT_SIZE" }
                    }
                }
            }
            case("isolated-large-font-and-small-viewport-do-not-mutate-design") {
                val definition = allControls()
                val original = definition.toJson().toString()
                for (fontScale in listOf(1f, 1.5f, 2f)) for ((width, height) in listOf(280 to 420, 640 to 360)) {
                    main {
                        val configuration = android.content.res.Configuration(owner.resources.configuration).apply { this.fontScale = fontScale }
                        val isolated = owner.createConfigurationContext(configuration)
                        root = ScriptUiView(isolated, definition) { _, _, _, _ -> error("UI_FONT_SYNTHETIC_EVENT") }
                        owner.setContentView(FrameLayout(owner).apply { addView(root, FrameLayout.LayoutParams(width, height)) })
                    }
                    settle(width, height, root, ::main)
                    main {
                        val view = requireNotNull(root)
                        check(view.values() == definition.initialValues()) { "UI_FONT_VALUES_CHANGED" }
                        val drawing = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                        try { view.draw(Canvas(drawing)) } finally { drawing.recycle() }
                        // Configuration mode has Cancel + Run; Close is the separate dialog mode.
                        val buttons = descendants(view).filterIsInstance<Button>().filter { it.text.toString() in setOf("运行", "取消") }
                        check(buttons.size == 2 && buttons.all { it.width > 0 && it.height > 0 }) { "UI_FONT_ACTION_LOST" }
                    }
                }
                check(definition.toJson().toString() == original) { "UI_FONT_DESIGN_CHANGED" }
            }
            val choices = ScriptUiDefinition(version = 2, fields = listOf(
                field("select", UiControl.SELECT, "A", options = listOf("A", "B")),
                field("list", UiControl.LIST, "A", y = 180, options = listOf("A", "B")),
            ))
            case("cleared-selection-remains-empty-after-native-layout") {
                mount(choices)
                main {
                    requireNotNull(root).apply {
                        applyCommand("select", "selectedIndex", "0")
                        applyCommand("list", "selectedIndex", "0")
                    }
                }
                settle(360, 640, root, ::main)
                main {
                    val view = requireNotNull(root)
                    check(view.readProperty("select", ScriptUiRuntimeProperty.SELECTED_INDEX) == "0") { "UI_SELECT_CLEAR_LOST" }
                    val spinner = descendants(view).filterIsInstance<Spinner>().single()
                    check(spinner.selectedItemPosition == 0 && !(spinner.adapter as ListAdapter).isEnabled(0) && spinner.selectedItem == "未选择") { "UI_SPINNER_FIRST_ROW_FORCED" }
                    check(view.readProperty("select", ScriptUiRuntimeProperty.ITEM_COUNT) == "2") { "UI_PLACEHOLDER_LEAKED_TO_DATA" }
                    check(descendants(view).filterIsInstance<ListView>().single().checkedItemPosition == -1) { "UI_LIST_FIRST_ROW_FORCED" }
                    check(events.isEmpty()) { "UI_PROGRAMMATIC_EVENT" }
                }
            }
            case("removed-selection-keeps-empty-and-recovers-explicitly") {
                mount(choices)
                main {
                    requireNotNull(root).apply {
                        applyCommand("select", "itemRemove", "1")
                        applyCommand("list", "itemRemove", "1")
                    }
                }
                settle(360, 640, root, ::main)
                main {
                    val view = requireNotNull(root)
                    check(view.values().values.all(String::isEmpty)) { "UI_REMOVED_SELECTION_CHANGED" }
                    val spinner = descendants(view).filterIsInstance<Spinner>().single()
                    check(spinner.selectedItemPosition == 0 && !(spinner.adapter as ListAdapter).isEnabled(0)) { "UI_SPINNER_REMOVED_FORCED" }
                    view.applyCommand("select", "selectedIndex", "1")
                    view.applyCommand("list", "selectedIndex", "1")
                }
                settle(360, 640, root, ::main)
                main {
                    check(requireNotNull(root).values().values.all { it == "B" }) { "UI_SELECTION_RECOVERY_FAILED" }
                    check(events.isEmpty()) { "UI_PROGRAMMATIC_EVENT" }
                }
            }
            case("legacy-form-empty-choice-and-popup-row-reuse") {
                mount(ScriptUiDefinition(fields = listOf(UiField("select", "Choice", "choice", false, "A", options = listOf("A", "B")))))
                main { requireNotNull(root).applyCommand("select", "selectedIndex", "0") }
                settle(360, 640, root, ::main)
                main {
                    val view = requireNotNull(root)
                    val spinner = descendants(view).filterIsInstance<Spinner>().single()
                    check(view.values()["select"] == "" && spinner.selectedItemPosition == 0) { "UI_LEGACY_SELECTION_CHANGED" }
                    val parent = FrameLayout(owner)
                    val placeholder = spinner.adapter.getDropDownView(0, null, parent)
                    val first = spinner.adapter.getDropDownView(1, placeholder, parent)
                    check(placeholder.layoutParams.height == 0 && first.layoutParams?.height != 0) { "UI_POPUP_ROW_REUSE" }
                    check((first as android.widget.TextView).text.toString() == "A") { "UI_POPUP_FIRST_OPTION" }
                    check(events.isEmpty()) { "UI_PROGRAMMATIC_EVENT" }
                }
            }
            case("off-page-updates-config-restore-and-no-synthetic-events") {
                val definition = ScriptUiDefinition(version = 2, pages = listOf(UiPage(), UiPage("other", "Other")), fields = listOf(
                    field("first", UiControl.INPUT, "first"),
                    field("second", UiControl.INPUT, "second").let { it.copy(ui = it.ui?.copy(pageId = "other")) },
                ))
                mount(definition)
                main {
                    val view = requireNotNull(root)
                    view.applyCommand("second", "value", "updated")
                    val config = view.exportConfig()
                    view.applyCommand("second", "value", "temporary")
                    view.restoreConfig(config)
                    view.switchPage("other")
                    view.setDisplayMode(UiDisplayMode.WIDTH_SCROLL)
                }
                settle(360, 640, root, ::main)
                main {
                    val view = requireNotNull(root)
                    check(descendants(view).filterIsInstance<EditText>().single().text.toString() == "updated") { "UI_OFF_PAGE_VALUE_LOST" }
                    check(events.isEmpty()) { "UI_PROGRAMMATIC_EVENT" }
                    check(definition.fields[1].initialValue == "second") { "UI_DESIGN_MUTATED" }
                    view.resetDefaults()
                    check(view.values() == definition.initialValues()) { "UI_RESET_FAILED" }
                }
            }
            case("native-edit-and-first-choice-after-clear") {
                mount(ScriptUiDefinition(version = 2, fields = listOf(
                    field("input", UiControl.INPUT, "initial"),
                    field("select", UiControl.SELECT, "A", y = 180, options = listOf("A", "B")),
                )))
                main { requireNotNull(root).applyCommand("select", "selectedIndex", "0") }
                settle(360, 640, root, ::main)
                main {
                    val view = requireNotNull(root)
                    check(events.isEmpty()) { "UI_PROGRAMMATIC_EVENT" }
                    descendants(view).filterIsInstance<EditText>().single().setText("typed")
                    descendants(view).filterIsInstance<Spinner>().single().setSelection(1)
                }
                settle(360, 640, root, ::main)
                main {
                    val view = requireNotNull(root)
                    check(view.values()["input"] == "typed" && view.values()["select"] == "A") { "UI_EDIT_NOT_SYNCHRONIZED" }
                    check(events.count { it == "input:change:typed:" } == 1 &&
                        events.count { it == "select:selection:A:" } == 1 && events.size == 2) { "UI_NATIVE_EVENTS_CHANGED" }
                }
            }
            case("edit-text-focus-input-connection-and-keyboard-window") {
                val definition = ScriptUiDefinition(version = 2, pages = listOf(UiPage("main", "Main", 720, 2400)), fields = listOf(
                    field("input", UiControl.INPUT, "initial", y = 2000, width = 620, height = 96),
                ))
                var window: com.autoscript.script.ui.ScriptUiWindowView? = null
                var dialog: com.autoscript.script.ui.ScriptUiDialog? = null
                var imeObserved = false
                var imeInsetBottom = 0
                var keyboardRequested = false
                var initialEdit: EditText? = null
                var sameEdit = false
                try {
                    main {
                        window = com.autoscript.script.ui.ScriptUiWindowView(owner, definition) { _, _, _, _ -> }
                        dialog = com.autoscript.script.ui.ScriptUiDialog(owner, requireNotNull(window)) { }
                        dialog?.show()
                    }
                    instrumentation.waitForIdleSync()
                    val focusedWindow = CountDownLatch(1)
                    var focusListener: android.view.ViewTreeObserver.OnWindowFocusChangeListener? = null
                    main {
                        val view = requireNotNull(window)
                        if (view.hasWindowFocus()) focusedWindow.countDown() else {
                            focusListener = android.view.ViewTreeObserver.OnWindowFocusChangeListener { focused -> if (focused) focusedWindow.countDown() }
                            view.viewTreeObserver.addOnWindowFocusChangeListener(requireNotNull(focusListener))
                        }
                    }
                    check(focusedWindow.await(3, TimeUnit.SECONDS)) { "UI_KEYBOARD_WINDOW_NOT_FOCUSED" }
                    main {
                        focusListener?.let { window?.viewTreeObserver?.removeOnWindowFocusChangeListener(it) }
                        val edit = descendants(requireNotNull(window)).filterIsInstance<EditText>().single()
                        initialEdit = edit
                        edit.requestFocus(); edit.setSelection(edit.length())
                        keyboardRequested = owner.getSystemService(InputMethodManager::class.java)?.showSoftInput(edit, InputMethodManager.SHOW_IMPLICIT) == true
                    }
                    SystemClock.sleep(900) // Bounded wait for the platform IME animation, no settings changed.
                    instrumentation.waitForIdleSync()
                    main {
                        val view = requireNotNull(window)
                        val edit = descendants(view).filterIsInstance<EditText>().single()
                        sameEdit = edit === initialEdit
                        check(edit.hasFocus()) { "UI_KEYBOARD_FOCUS_LOST" }
                        val connection = requireNotNull(edit.onCreateInputConnection(android.view.inputmethod.EditorInfo()))
                        edit.selectAll(); check(connection.commitText("typed-with-ime", 1)) { "UI_INPUT_CONNECTION_REJECTED" }
                        if (android.os.Build.VERSION.SDK_INT >= 30) {
                            val insets = view.rootWindowInsets
                            imeInsetBottom = insets?.getInsets(android.view.WindowInsets.Type.ime())?.bottom ?: 0
                            imeObserved = insets?.isVisible(android.view.WindowInsets.Type.ime()) == true && imeInsetBottom > 0
                        }
                        val visible = Rect().also { edit.getWindowVisibleDisplayFrame(it) }
                        val actions = descendants(view).filterIsInstance<Button>().filter { it.text.toString() in setOf("运行", "取消") }
                        check(actions.size == 2 && actions.all { it.isShown && it.height > 0 }) { "UI_KEYBOARD_ACTIONS_HIDDEN" }
                        if (imeObserved) {
                            check(actions.all {
                                val local = Rect()
                                val screen = IntArray(2).also(it::getLocationOnScreen)
                                it.getLocalVisibleRect(local) && local.height() == it.height && screen[1] + it.height <= visible.bottom
                            }) { "UI_KEYBOARD_COVERS_ACTIONS" }
                        }
                        check(view.values()["input"] == "typed-with-ime") { "UI_KEYBOARD_VALUE_LOST" }
                        dialog?.hide(); dialog?.show()
                    }
                    instrumentation.waitForIdleSync()
                    main {
                        val edit = descendants(requireNotNull(window)).filterIsInstance<EditText>().single()
                        check(edit.hasFocus() && edit.text.toString() == "typed-with-ime") { "UI_KEYBOARD_RESTORE_FAILED" }
                    }
                } finally {
                    report.put("keyboard", JSONObject().put("imeObserved", imeObserved)
                        .put("imeInsetBottom", imeInsetBottom)
                        .put("requestAccepted", keyboardRequested).put("sameEditorAfterResize", sameEdit)
                        .put("occlusionCoverage", if (imeObserved) "checked" else "not_observed"))
                    main { dialog?.close() }
                }
            }
            case("disabled-ancestor-blocks-child-callback") {
                val definition = ScriptUiDefinition(version = 2, fields = listOf(
                    field("container", UiControl.CONTAINER, "", width = 600, height = 300),
                    field("button", UiControl.BUTTON, "synthetic-button").let { it.copy(ui = it.ui?.copy(parentId = "container")) },
                ))
                mount(definition)
                main { requireNotNull(root).applyCommand("container", "enabled", "false") }
                settle(360, 640, root, ::main)
                main {
                    val button = descendants(requireNotNull(root)).filterIsInstance<Button>().single { it.text.toString() == "synthetic-button" }
                    check(!button.isEnabled) { "UI_ANCESTOR_ENABLED_LEAK" }
                    button.performClick()
                    check(events.isEmpty()) { "UI_DISABLED_EVENT" }
                }
            }
            case("runtime-and-dialog-actions-only-notify-test-host") {
                mount(ScriptUiDefinition(version = 2, fields = listOf(field("label", UiControl.LABEL, "label"))))
                main {
                    val view = requireNotNull(root)
                    view.setRuntime(true)
                    descendants(view).filterIsInstance<Button>().single { it.text.toString() == "停止" }.performClick()
                    check(events == listOf("window:click::close")) { "UI_RUNTIME_ACTION" }
                    events.clear()
                    view.setDialogMode(true)
                    val buttons = descendants(view).filterIsInstance<Button>()
                    check(buttons.size == 1 && buttons.single().text.toString() == "关闭") { "UI_DIALOG_ACTIONS" }
                    buttons.single().performClick()
                    check(events == listOf("window:click::close")) { "UI_DIALOG_CALLBACK" }
                }
            }
        } catch (failure: Throwable) {
            success = false
            report.put("error", failure.javaClass.simpleName)
        } finally {
            instrumentation.removeMonitor(monitor)
            main { root?.setInteractionEnabled(false); activity?.finish() }
        }
        return report.put("pass", success)
    }

    private fun settle(width: Int, height: Int, root: ScriptUiView?, main: (() -> Unit) -> Unit) {
        // Drain layout-triggered renders and their deferred selection callbacks, not fixed-interval polling.
        repeat(3) {
            main {
                requireNotNull(root).apply {
                    measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                    layout(0, 0, width, height)
                }
            }
        }
    }

    private fun descendants(root: View): List<View> {
        val result = mutableListOf<View>()
        fun visit(view: View) {
            check(result.size < 32_768) { "UI_VIEW_BUDGET" }
            result.add(view)
            if (view is ViewGroup) for (index in 0 until view.childCount) visit(view.getChildAt(index))
        }
        visit(root)
        return result
    }

    private fun field(id: String, control: UiControl, initial: String, y: Int = 20, options: List<String> = emptyList(),
        width: Int = 300, height: Int = 120) = UiField(id, id, control.valueKind, false, initial,
        minimum = if (control.valueKind in setOf("integer", "number")) 0 else null,
        maximum = if (control.valueKind in setOf("integer", "number")) 100 else null,
        options = options, ui = UiPresentation(control = control.name, x = 20, y = y, width = width, height = height, fontPx = 20))

    private fun allControls() = ScriptUiDefinition(version = 2, fields = UiControl.entries.mapIndexed { index, control ->
        val initial = when (control.valueKind) {
            "integer", "number" -> "5"
            "boolean" -> "false"
            "choice" -> "A"
            "multiChoice" -> "[\"A\"]"
            else -> control.name
        }
        field("field_$index", control, initial, y = 20 + index / 3 * 140,
            options = if (control.valueKind in setOf("choice", "multiChoice")) listOf("A", "B") else emptyList(), width = 210)
            .let { it.copy(ui = it.ui?.copy(x = 10 + index % 3 * 230)) }
    })
}

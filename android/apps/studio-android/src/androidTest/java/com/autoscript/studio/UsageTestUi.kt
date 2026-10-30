package com.autoscript.studio

import android.app.Instrumentation
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.MotionEvent
import android.view.InputDevice
import android.view.accessibility.AccessibilityNodeInfo
import java.io.File

/** Actual controls of the owned test window; never inspect another package's text. */
internal class UsageTestUi(private val test: Instrumentation) {
    private fun progress(action: String) = test.sendStatus(1, Bundle().apply {
        putString("stream", "QA_USAGE_ACTION:$action\n")
    })
    fun find(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        var budget = 4096
        fun visit(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (--budget < 0 || !node.refresh()) { node.recycle(); return null }
            if (node.packageName?.toString() != test.targetContext.packageName) { node.recycle(); return null }
            if (node.isVisibleToUser && predicate(node)) return node
            for (index in 0 until node.childCount.coerceAtMost(128)) {
                node.getChild(index)?.let(::visit)?.let { node.recycle(); return it }
            }
            node.recycle()
            return null
        }
        for (window in test.uiAutomation.windows.sortedByDescending { it.layer }) {
            window.root?.let(::visit)?.let { return it }
        }
        return null
    }

    fun label(text: String): AccessibilityNodeInfo? = find {
        it.text?.toString() == text || it.contentDescription?.toString() == text ||
            it.actionList.any { action -> action.label?.toString() == text }
    }

    fun exists(text: String): Boolean = label(text)?.let { it.recycle(); true } ?: false

    fun await(description: String, predicate: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + 10_000
        while (SystemClock.elapsedRealtime() < until) {
            if (predicate()) return
            SystemClock.sleep(60)
        }
        test.sendStatus(1, Bundle().apply { putString("stream", "QA_USAGE_WAIT:$description\n") })
        error("QA_USAGE_UI_TIMEOUT")
    }

    fun wait(text: String) = await(text) { exists(text) }
    fun absent(text: String) = await("absent:$text") { !exists(text) }

    fun click(text: String) {
        progress("click:$text")
        wait(text)
        val node = requireNotNull(label(text))
        val rect = try { Rect().also(node::getBoundsInScreen) } finally { node.recycle() }
        tap(rect)
    }

    fun clickButton(text: String) {
        progress("button:$text")
        fun button() = find {
            if (it.text?.toString() != text && it.contentDescription?.toString() != text) false
            else {
                var current: AccessibilityNodeInfo? = AccessibilityNodeInfo.obtain(it)
                var clickable = false
                repeat(5) {
                    val candidate = current
                    if (candidate != null) {
                        if (candidate.isClickable && candidate.isEnabled) clickable = true
                        current = if (candidate.isClickable) null else candidate.parent
                        candidate.recycle()
                    }
                }
                current?.recycle()
                clickable
            }
        }
        await("clickable:$text") { button()?.let { it.recycle(); true } == true }
        val node = requireNotNull(button())
        val rect = try { Rect().also(node::getBoundsInScreen) } finally { node.recycle() }
        tap(rect)
    }

    fun tap(rect: Rect) {
        check(!rect.isEmpty) { "QA_USAGE_EMPTY_TARGET" }
        val start = SystemClock.uptimeMillis()
        val inject = runCatching { android.app.UiAutomation::class.java.getMethod("injectInputEvent",
            android.view.InputEvent::class.java, Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType) }.getOrNull()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(start, SystemClock.uptimeMillis(), action, rect.exactCenterX(), rect.exactCenterY(), 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            try { check((inject?.invoke(test.uiAutomation, event, true, false) as? Boolean
                ?: test.uiAutomation.injectInputEvent(event, true))) { "QA_USAGE_INPUT_REJECTED" } }
            finally { event.recycle() }
            SystemClock.sleep(if (action == MotionEvent.ACTION_DOWN) 40 else 100)
        }
    }

    fun replaceFirst(text: String) {
        progress("replace-editable")
        await("editable") { find { it.isEditable }?.let { it.recycle(); true } == true }
        val node = requireNotNull(find { it.isEditable })
        try {
            check(node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            })) { "QA_USAGE_SET_TEXT_REJECTED" }
        } finally { node.recycle() }
        SystemClock.sleep(100)
    }

    fun reveal(text: String) {
        await("form ready for:$text") { exists(text) || find { it.isScrollable }?.let { it.recycle(); true } == true }
        repeat(12) {
            if (exists(text)) return
            val scroll = find { it.isScrollable }
            check(scroll != null) { "QA_USAGE_SCROLL_MISSING" }
            try { check(scroll.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) { "QA_USAGE_SCROLL_END" } }
            finally { scroll.recycle() }
            SystemClock.sleep(100)
        }
        wait(text)
    }

    fun rowAction(row: String, action: String) {
        reveal(row)
        var node = requireNotNull(label(row))
        fun descendant(parent: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (parent.text?.toString() == action || parent.contentDescription?.toString() == action) return AccessibilityNodeInfo.obtain(parent)
            for (index in 0 until parent.childCount) parent.getChild(index)?.let { child ->
                try { descendant(child)?.let { return it } } finally { child.recycle() }
            }
            return null
        }
        try {
            repeat(5) {
                descendant(node)?.let { match ->
                    val rect = try { Rect().also(match::getBoundsInScreen) } finally { match.recycle() }
                    tap(rect)
                    return
                }
                val parent = node.parent ?: error("QA_USAGE_ROW_ACTION_MISSING")
                node.recycle(); node = parent
            }
            error("QA_USAGE_ROW_ACTION_MISSING")
        } finally { node.recycle() }
    }

    fun screenshot(name: String) {
        require(name.matches(Regex("[a-z0-9-]{1,64}")))
        progress("screenshot:$name")
        val capture = java.util.concurrent.FutureTask { test.uiAutomation.takeScreenshot() }
        Thread(capture, "qa-screenshot").apply { isDaemon = true; start() }
        val image = requireNotNull(capture.get(8, java.util.concurrent.TimeUnit.SECONDS))
        try {
            val directory = File(test.targetContext.cacheDir, "function-usage")
            check(directory.isDirectory || directory.mkdirs())
            File(directory, "$name.png").outputStream().use { check(image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { image.recycle() }
    }
}

package com.autoscript.script.ui

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class ScriptUiKeyboardWiringTest {
    @Test fun insetCallbackPublishesNewInsetsBeforeRequestingResize() {
        val source = File("src/main/java/com/autoscript/script/ui/ScriptUiWindowView.kt").readText()
        val listener = source.substringAfter("setOnApplyWindowInsetsListener").substringBefore("viewTreeObserver.addOnGlobalLayoutListener")
        assertTrue(listener.indexOf("appliedWindowInsets = insets") < listener.indexOf("onViewportChanged?.invoke()"))
        assertTrue(source.contains("appliedWindowInsets = null"))
        val display = File("src/main/java/com/autoscript/script/ui/ScriptUiDisplay.kt").readText()
        assertTrue(display.contains("(view as? ScriptUiWindowView)?.appliedWindowInsets ?: view?.rootWindowInsets"))
        assertTrue(display.contains("metrics.windowInsets.getInsets(WindowInsets.Type.ime()).bottom"))
        val dialog = File("src/main/java/com/autoscript/script/ui/ScriptUiDialog.kt").readText()
        assertTrue(dialog.contains("addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR)"))
        assertTrue(dialog.contains("includeKeyboard = Build.VERSION.SDK_INT >= 30"))
        assertTrue(dialog.contains("val bounds = screenBounds()"))
    }

    @Test fun viewportOnlyChangesKeepExistingInputWidgets() {
        val source = File("src/main/java/com/autoscript/script/ui/ScriptUiView.kt").readText()
        assertTrue(source.contains("renderPage(reuseWidgets = true)"))
        assertTrue(source.contains("if (!reuse) { pageBody.removeAllViews(); widgets.clear() }"))
        assertTrue(source.contains("if (reuse) widgets.getValue(f.id)"))
        assertTrue(source.contains("if (widget.parent === parent) widget.layoutParams = params else parent.addView(widget, params)"))
    }
}

package com.autoscript.runtime.service

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Wiring checks supplement, but do not replace, native-window testing on a device. */
class RuntimeScriptUiStartupTest {
    private val source = File("src/main/java/com/autoscript/runtime/service/RuntimeScriptUiController.kt").readText()

    @Test fun delayedClearOnlyAppliesToItsOwnGeneration() {
        val clear = source.substringAfter("fun clear()").substringBefore("fun prepare(")
        assertTrue(clear.contains("val token = epoch.incrementAndGet()"))
        assertTrue(clear.contains("if (accepts(token))"))
        assertTrue(clear.indexOf("if (accepts(token))") < clear.indexOf("removeWindows()"))
    }

    @Test fun startupActivatesWithoutAddingAnotherWindow() {
        val activation = source.substringAfter("fun activateRuntime()").substringBefore("fun command(")
        assertTrue(activation.contains("active = true"))
        assertTrue(activation.contains("runtimeView(token)"))
        assertFalse(activation.contains("show(token)"))
        assertFalse(activation.contains("addView("))
        val service = File("src/main/java/com/autoscript/runtime/service/AutomationRuntimeService.kt").readText()
        assertTrue(service.contains("scriptUiController.activateRuntime()"))
        assertFalse(service.contains("scriptUiController.showRuntime()"))
    }

    @Test fun explicitShowReusesTheHiddenRendererAndCommandsStillUpdateIt() {
        assertTrue(source.contains("\"show\" -> show(token)"))
        val factory = source.substringAfter("private fun runtimeView(").substringBefore("private fun show(")
        assertTrue(factory.contains("return renderer ?: ScriptUiWindowView"))
        assertTrue(factory.contains("also { renderer = it }"))
        assertFalse(factory.contains("addView("))
        val show = source.substringAfter("private fun show(").substringBefore("fun onConfigurationChanged()")
        assertTrue(show.contains("val view = runtimeView(token) ?: return"))
        assertTrue(show.contains("windows.addView(view, lp)"))
        assertTrue(source.contains("renderer?.renderer?.applyCommand"))
    }

    @Test fun layoutDialogsMeasureAgainstTheAttachedViewForImeInsets() {
        val dialogs = File("src/main/java/com/autoscript/runtime/service/RuntimeInteractionDialogs.kt").readText()
        assertTrue(dialogs.contains("ScriptUiDisplay.bounds(context, session.view ?: session.dialog.window?.decorView,"))
        assertTrue(dialogs.contains("includeKeyboard = Build.VERSION.SDK_INT >= 30"))
        assertTrue(dialogs.contains("window.setDecorFitsSystemWindows(false)"))
        assertTrue(dialogs.contains("window.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR)"))
        assertTrue(dialogs.contains("val target = bounds.centered(size)"))
        assertTrue(dialogs.contains("gravity = Gravity.TOP or Gravity.START"))
    }
}

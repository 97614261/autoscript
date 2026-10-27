package com.autoscript.runtime.service

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeControlPromptSeparationTest {
    // Wiring regression; actual WindowManager behavior is covered by device acceptance.
    @Test fun changingControlOwnerDoesNotEndScriptPrompts() {
        val controller = File("src/main/java/com/autoscript/runtime/service/RuntimeOverlayController.kt").readText()
        val hide = controller.substringAfter("fun hideControl() {").substringBefore("private fun removeOverlayView()")
        assertTrue(hide.contains("removeControlView()"))
        assertTrue(hide.contains("controlEnabled = false"))
        assertTrue(controller.contains("!captureOnly && !controlEnabled"))
        assertFalse(hide.contains("promptSession.end()"))
        assertFalse(hide.contains("removePromptView()"))
        assertFalse(hide.contains("removeToastView("))
        val service = File("src/main/java/com/autoscript/runtime/service/AutomationRuntimeService.kt").readText()
        val setter = service.substringAfter("override fun setFloatingControlEnabled(").substringBefore("override fun setCaptureOverlayEnabled(")
        assertTrue(setter.contains("overlayController.hideControl()"))
        assertFalse(setter.contains("overlayController.hide()"))
    }
}

package com.autoscript.runtime.service

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QaInputMethodIsolationTest {
    @Test fun keyboardExistsOnlyInOptInTestApkAndNeverReadsInput() {
        val source = File("src/androidTest/java/com/autoscript/runtime/service/QaInputMethodService.kt").readText()
        val testManifest = File("src/androidTest/AndroidManifest.xml").readText()
        val mainManifest = File("src/main/AndroidManifest.xml").readText()
        assertTrue(testManifest.contains("QaInputMethodService") && testManifest.contains("android.permission.BIND_INPUT_METHOD"))
        assertFalse(mainManifest.contains("QaInputMethodService"))
        assertTrue(source.contains("currentInputConnection?.commitText(\"QA\", 1)"))
        for (forbidden in listOf("getTextBeforeCursor", "getTextAfterCursor", "getExtractedText", "getSelectedText", "java.io.", "java.net.", "ClipboardManager")) {
            assertFalse(forbidden, source.contains(forbidden))
        }
        assertTrue(source.contains("if (!allowed) requestHideSelf(0)"))
    }
}

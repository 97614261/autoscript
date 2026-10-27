package com.autoscript.studio

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class StudioDialogOrderTest {
    @Test fun attachedBackdropsRemainButNewPanelsWaitUntilEveryPromptCloses() {
        val order = StudioDialogOrder<Any>()
        val panel = Any(); val prompt = Any(); val latePanel = Any(); val nestedPrompt = Any()
        order.register(panel); order.presented(panel)
        order.register(prompt, prompt = true); order.presented(prompt)
        order.register(latePanel)
        assertTrue(order.canPresent(panel))
        assertTrue(order.canPresent(prompt))
        assertFalse(order.canPresent(latePanel))
        order.register(nestedPrompt, prompt = true)
        assertTrue(order.canPresent(nestedPrompt))
        order.remove(prompt)
        assertFalse(order.canPresent(latePanel))
        order.remove(nestedPrompt)
        assertTrue(order.canPresent(latePanel))
        assertEquals(listOf(panel, latePanel), order.windows)
    }

    @Test fun firstFramePromptDefersPanelsAndItsOwnDescendantIsAllowed() {
        val order = StudioDialogOrder<Any>()
        val prompt = Any(); val panel = Any(); val childPicker = Any()
        order.register(prompt, prompt = true)
        order.register(panel)
        order.register(childPicker, prompt = true)
        assertTrue(order.canPresent(prompt))
        assertFalse(order.canPresent(panel))
        assertTrue(order.canPresent(childPicker))
        assertFalse(order.canPresent(Any()))
        order.clear()
        assertFalse(order.hasPrompt)
        assertEquals(0, order.size)
        assertFalse(order.canPresent(childPicker))
    }

    @Test fun identitiesAndRegistrationOrderAreStable() {
        data class Window(val name: String)
        val order = StudioDialogOrder<Window>()
        val first = Window("same"); val other = Window("same")
        order.register(first, prompt = true)
        order.register(first)
        order.register(other)
        assertEquals(2, order.size)
        assertSame(first, order.windows.first())
        assertFalse(order.canPresent(other))
        order.remove(other)
        assertTrue(order.hasPrompt)
        assertTrue(order.contains(first))
    }

    @Test fun allComposableDialogsUseTheSharedHostAndEditorErrorComesLast() {
        val root = File("src/main/java/com/autoscript/studio")
        val bypasses = root.walkTopDown().filter { it.extension == "kt" && it.name != "StudioDialog.kt" }
            .filter { file -> file.readText().lineSequence().any { it.trim() == "import androidx.compose.ui.window.Dialog" } }
            .map { it.name }.toList()
        assertEquals("A raw Compose Dialog cannot host above the service editor", emptyList<String>(), bypasses)
        val editor = File(root, "editor/VisualProjectScreen.kt").readText()
        assertTrue(editor.lastIndexOf("EditorProblemDialog(message,") > editor.lastIndexOf("ImageTemplateSaveDialog("))
        assertTrue(File(root, "editor/EditorProblemDialog.kt").readText().contains("StudioConfirmDialog("))
        assertTrue(File(root, "ui/StudioConfirmDialog.kt").readText().contains("prompt: Boolean = true"))
        assertTrue(File(root, "image/ImagePipelineInsertDialog.kt").readText().contains("prompt = false"))
    }
}

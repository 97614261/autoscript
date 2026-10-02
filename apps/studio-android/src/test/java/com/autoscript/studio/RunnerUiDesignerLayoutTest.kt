package com.autoscript.studio

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RunnerUiDesignerLayoutTest {
    @Test fun `narrow screens show one panel and retain a usable canvas`() {
        listOf(360f, 480f, 559f).forEach { width ->
            val layout = designerWorkspaceLayout(width, tools = true, inspector = true)
            assertFalse(layout.showTools)
            assertTrue(layout.showInspector)
            assertTrue(width - layout.inspectorWidth - 12 >= 180)
            val toolsOnly = designerWorkspaceLayout(width, tools = true, inspector = false)
            assertTrue(toolsOnly.showTools)
            assertFalse(toolsOnly.showInspector)
        }
    }

    @Test fun `wide screens keep both panels and focus mode hides both`() {
        listOf(560f, 640f, 800f, 1200f).forEach { width ->
            val layout = designerWorkspaceLayout(width, tools = true, inspector = true)
            assertTrue(layout.showTools && layout.showInspector)
            assertTrue(width - layout.toolboxWidth - layout.inspectorWidth - 16 >= 240)
            val focus = designerWorkspaceLayout(width, tools = false, inspector = false)
            assertFalse(focus.showTools || focus.showInspector)
        }
    }
}

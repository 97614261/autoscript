package com.autoscript.studio

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection

class RunnerUiDesignerLayoutTest {
    @Test fun `bottom menus stay exactly above the trigger and follow its movement`() {
        val positioner = DesignerMenuPositionProvider(marginPx = 8, gapPx = 3)
        val window = IntSize(1000, 600)
        val popup = IntSize(152, 248)
        val anchor = IntRect(300, 560, 340, 582)
        val first = positioner.calculatePosition(anchor, window, LayoutDirection.Ltr, popup)
        assertEquals(IntOffset(300, 309), first)
        assertEquals(3, anchor.top - first.y - popup.height)
        val moved = IntRect(420, 540, 460, 562)
        assertEquals(IntOffset(420, 289), positioner.calculatePosition(moved, window, LayoutDirection.Ltr, popup))
    }

    @Test fun `top menus open below and right edge menus stay inside window`() {
        val positioner = DesignerMenuPositionProvider(8, 3)
        val window = IntSize(1000, 600)
        val popup = IntSize(152, 100)
        val top = IntRect(20, 12, 60, 34)
        assertEquals(IntOffset(20, 37), positioner.calculatePosition(top, window, LayoutDirection.Ltr, popup))
        val right = IntRect(950, 560, 990, 582)
        assertEquals(IntOffset(840, 457), positioner.calculatePosition(right, window, LayoutDirection.Ltr, popup))
        assertEquals(IntOffset(838, 457), positioner.calculatePosition(right, window, LayoutDirection.Rtl, popup))
    }

    @Test fun `large font menus scroll on the available side without detaching`() {
        val anchor = IntRect(120, 190, 160, 212)
        val height = designerMenuMaxHeight(anchor, 240, 8, 3, 400)
        assertEquals(179, height)
        val point = DesignerMenuPositionProvider(8, 3).calculatePosition(anchor, IntSize(400, 240), LayoutDirection.Ltr, IntSize(152, height))
        assertEquals(8, point.y)
        assertEquals(3, anchor.top - point.y - height)
        assertEquals(248, designerMenuMaxHeight(IntRect(120, 560, 160, 582), 600, 8, 3, 248))
    }

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

package com.autoscript.studio

import com.autoscript.script.ui.UiPage
import com.autoscript.script.ui.UiPresentation
import org.junit.Assert.*
import org.junit.Test

class UiDesignerOrganizationTest {
    private fun field(id: String, x: Int, y: Int, width: Int = 80, height: Int = 40, parent: String? = null, container: Boolean = false) = RunnerUiFieldDraft(
        id, id, if (container) RunnerUiControlKind.CONTAINER else RunnerUiControlKind.TEXT, false, "",
        ui = UiPresentation(control = if (container) "CONTAINER" else "INPUT", x = x, y = y, width = width, height = height,
            fontPx = 20, parentId = parent, events = mapOf("click" to "run")),
    )
    private fun draft(vararg fields: RunnerUiFieldDraft) = RunnerUiDesignerDraft(fields = fields.toList())

    @Test fun pageSupportsOneTwoAndThreeColumnsWithoutChangingOtherPagesOrChildren() {
        val box = field("box", 8, 200, 180, 60, container = true)
        val child = field("child", 5, 6, 60, 20, parent = "box")
        val other = field("other", 20, 20).copy(ui = field("other", 20, 20).ui.copy(pageId = "other"))
        val source = draft(field("a", 10, 10), field("b", 110, 10), field("c", 210, 10), field("d", 310, 10), box, child, other)
            .copy(pages = listOf(UiPage(), UiPage(id = "other")))
        (1..3).forEach { columns ->
            val next = source.compactPage("main", columns)
            val roots = next.fields.take(5)
            assertEquals(8, roots.first().ui.x)
            assertEquals(8, roots.first().ui.y)
            assertTrue(roots.all { it.ui.x + it.ui.width <= 712 && it.ui.y + it.ui.height <= 952 })
            roots.forEachIndexed { i, f ->
                assertEquals(8 + i % columns * (f.ui.width + 8), f.ui.x)
                if (i > 0 && i % columns != 0) assertEquals(roots[i - 1].ui.y, f.ui.y)
            }
            assertEquals(child, next.fields[5])
            assertEquals(other, next.fields[6])
            assertEquals(source.pages, next.pages)
            assertEquals(next, runnerUiDesignerDraft(next.toRunnerUiJson()))
        }
    }

    @Test fun horizontalAndVerticalStacksUseNaturalSizesAndEightPixelGaps() {
        val source = draft(field("a", 8, 12, 80, 40), field("b", 160, 80, 100, 60), field("c", 320, 160, 60, 30))
        val ids = setOf("a", "b", "c")
        val horizontal = source.arrangeSelection(ids, true)
        assertEquals(listOf(8, 96, 204), horizontal.fields.map { it.ui.x })
        assertTrue(horizontal.fields.all { it.ui.y == 12 })
        val vertical = source.arrangeSelection(ids, false)
        assertEquals(listOf(12, 60, 128), vertical.fields.map { it.ui.y })
        assertTrue(vertical.fields.all { it.ui.x == 8 })
        assertEquals(source.fields.map { it.ui.width to it.ui.height }, horizontal.fields.map { it.ui.width to it.ui.height })
        assertEquals(source.fields.map { it.ui.events }, vertical.fields.map { it.ui.events })
    }

    @Test fun containersMoveAsRootsWithoutMovingSelectedChildrenTwice() {
        val box = field("box", 8, 12, 120, 80, container = true)
        val child = field("child", 5, 4, 20, 20, parent = "box")
        val source = draft(box, child, field("b", 200, 120))
        val next = source.arrangeSelection(setOf("box", "child", "b"), false)
        assertEquals(100, next.fields.last().ui.y)
        assertEquals(child, next.fields[1])
    }

    @Test fun uniformWidthHeightOrBothUseFirstSelectedAndPreserveFontAndInteractions() {
        val a = field("a", 8, 8)
        val b = field("b", 250, 8, 120, 64).copy(ui = field("b", 250, 8, 120, 64).ui.copy(fontPx = 26))
        val other = field("other", 8, 300)
        val source = draft(a, b, other)
        DesignerUniformSize.entries.forEach { dimension ->
            val next = source.uniformSize(linkedSetOf("b", "a"), dimension)
            assertEquals(if (dimension == DesignerUniformSize.HEIGHT) 80 else 120, next.fields.first().ui.width)
            assertEquals(if (dimension == DesignerUniformSize.WIDTH) 40 else 64, next.fields.first().ui.height)
            assertEquals(20, next.fields.first().ui.fontPx)
            assertEquals(b, next.fields[1])
            assertEquals(other, next.fields[2])
            assertEquals(a.ui.events, next.fields.first().ui.events)
        }
    }

    @Test fun snapUsesEightPixelGridAndPreservesRelativeChildGeometry() {
        val box = field("box", 13, 21, 120, 80, container = true)
        val child = field("child", 5, 4, 20, 20, parent = "box")
        val source = draft(box, child)
        val next = source.snapSelection(setOf("box", "child"))
        assertEquals(16 to 24, next.fields.first().ui.x to next.fields.first().ui.y)
        assertEquals(child, next.fields.last())
        assertEquals(source.fields.first().ui.width, next.fields.first().ui.width)
        val edge = draft(field("edge", 604, 8, 115))
        assertThrows(IllegalArgumentException::class.java) { edge.snapSelection(setOf("edge")) }
        assertEquals(604, edge.fields.single().ui.x)
    }

    @Test fun overflowNewOverlapAndContainerCroppingRejectEntireOperation() {
        val full = draft(field("a", 8, 8, 400), field("b", 8, 100, 400))
        assertThrows(IllegalArgumentException::class.java) { full.arrangeSelection(setOf("a", "b"), true) }
        val blocked = draft(field("a", 8, 8), field("b", 200, 100), field("obstacle", 96, 8))
        assertThrows(IllegalArgumentException::class.java) { blocked.arrangeSelection(setOf("a", "b"), true) }
        assertEquals(200 to 100, blocked.fields[1].ui.x to blocked.fields[1].ui.y)
        val box = field("box", 8, 8, 200, 100, container = true)
        val child = field("child", 150, 4, 40, 40, parent = "box")
        val small = field("small", 300, 8, 80, 40)
        assertThrows(IllegalArgumentException::class.java) { draft(box, child, small).uniformSize(linkedSetOf("small", "box"), DesignerUniformSize.BOTH) }
        val short = draft(field("a", 8, 8), field("b", 200, 8)).copy(pages = listOf(UiPage(height = 100)))
        assertThrows(IllegalArgumentException::class.java) { short.compactPage("main", 1) }
    }

    @Test fun invalidSelectionsAndColumnCountsCannotProduceChanges() {
        val source = draft(field("a", 8, 8), field("b", 200, 8))
        assertThrows(IllegalArgumentException::class.java) { source.compactPage("main", 0) }
        assertThrows(IllegalArgumentException::class.java) { source.compactPage("main", 4) }
        assertThrows(IllegalArgumentException::class.java) { source.arrangeSelection(setOf("a"), true) }
        assertThrows(IllegalArgumentException::class.java) { source.arrangeSelection(setOf("a", "missing"), true) }
        assertThrows(IllegalArgumentException::class.java) { source.uniformSize(emptySet(), DesignerUniformSize.BOTH) }
        assertThrows(IllegalArgumentException::class.java) { source.snapSelection(emptySet()) }
        val box = field("box", 8, 100, 200, 100, container = true)
        val child = field("child", 8, 8, parent = "box")
        assertThrows(IllegalArgumentException::class.java) { draft(box, child, source.fields.first()).arrangeSelection(setOf("a", "child"), true) }
    }
}

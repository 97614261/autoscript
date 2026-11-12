package com.autoscript.studio

import com.autoscript.script.ui.*
import org.junit.Assert.*
import org.junit.Test

class DesignerCombinationsTest {
    @Test fun allCombinationsAreCompleteAlignedAndRoundTripWithoutChangingNeighbors() {
        for (combination in DesignerCombination.entries) {
            val before = RunnerUiDesignerDraft().add(RunnerUiControlKind.BUTTON)
            val after = before.addCombination(combination, "main")
            assertEquals(before.fields.first(), after.fields.first())
            val (left, right) = after.fields.takeLast(2)
            assertEquals(left.ui.y, right.ui.y)
            assertEquals(left.ui.x + left.ui.width + 8, right.ui.x)
            assertEquals(left.ui.height, right.ui.height)
            assertTrue(after.fields.map { it.id }.distinct().size == after.fields.size)
            assertEquals(after, runnerUiDesignerDraft(after.toRunnerUiJson()))
            assertEquals(1, before.fields.size)
        }
    }

    @Test fun explicitConfirmationTemplateHasActionsAndOrdinaryButtonsStayInert() {
        val draft = RunnerUiDesignerDraft().addCombination(DesignerCombination.CONFIRM_CANCEL, "main")
        assertEquals("run", draft.fields.first().ui.events["click"])
        assertEquals("close", draft.fields.last().ui.events["click"])
        assertEquals(DesignerAppearance.OUTLINE.let { draft.fields.last().ui.withDesignerAppearance(RunnerUiControlKind.BUTTON, it) }, draft.fields.last().ui)
        assertTrue(draft.add(RunnerUiControlKind.BUTTON).fields.last().ui.events.isEmpty())
    }

    @Test fun noRoomOrInsufficientControlCapacityNeverAddsHalfACombination() {
        val base = RunnerUiDesignerDraft(pages = listOf(UiPage(width = 100, height = 100)))
        val one = base.addCombination(DesignerCombination.TEXT_INPUT, "main")
        assertThrows(IllegalStateException::class.java) { one.addCombination(DesignerCombination.LABEL_SWITCH, "main") }
        assertEquals(2, one.fields.size)
        val field = one.fields.first()
        val full = one.copy(fields = (1..255).map { field.copy(id = "control$it") })
        assertThrows(IllegalArgumentException::class.java) { full.addCombination(DesignerCombination.CONFIRM_CANCEL, "main") }
        assertEquals(255, full.fields.size)
    }

    @Test fun insertionUsesRequestedPageAndSkipsIdHolesWithoutMovingOtherPages() {
        val base = RunnerUiDesignerDraft(pages = listOf(UiPage(), UiPage(id = "second", title = "second")))
            .add(RunnerUiControlKind.LABEL)
        val withHole = base.copy(fields = base.fields + base.fields.single().copy(id = "control3"))
        val next = withHole.addCombination(DesignerCombination.LABEL_SWITCH, "second")
        assertEquals(withHole.fields, next.fields.take(2))
        assertTrue(next.fields.takeLast(2).all { it.ui.pageId == "second" })
        assertEquals(next.fields.size, next.fields.map { it.id }.distinct().size)
        assertEquals(listOf("control4", "control5"), next.fields.takeLast(2).map { it.id })
    }
}

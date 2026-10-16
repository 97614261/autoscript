package com.autoscript.studio

import com.autoscript.script.ui.UiPage
import com.autoscript.script.ui.UiPresentation
import org.junit.Assert.*
import org.junit.Test

class UiDesignerContentFitTest {
    private val measure: (String) -> Float = { it.length * 10f }
    private fun field(kind: RunnerUiControlKind = RunnerUiControlKind.MULTISELECT, options: List<String> = (1..8).map { "选项$it" }) =
        RunnerUiFieldDraft("choice", "标题", kind, false, if (kind == RunnerUiControlKind.MULTISELECT) "[]" else options.first(), optionsText = designerOptionsText(options),
            ui = UiPresentation(control = kind.control.name, x = 8, y = 8, width = 100, height = 40, fontPx = 20))
    private fun draft(vararg f: RunnerUiFieldDraft) = RunnerUiDesignerDraft(fields = f.toList(), pages = listOf(UiPage(width = 720, height = 640)))

    @Test fun optionsGrowHeightWithRoundTripAndDoNotAlterOriginal() {
        val source = draft(field()); val fit = source.fitContent("choice", measure)
        assertEquals(288, fit.draft.fields.single().ui.height)
        assertEquals(40, source.fields.single().ui.height)
        assertEquals(fit.draft, runnerUiDesignerDraft(fit.draft.toRunnerUiJson()))
    }
    @Test fun radioFitsActualTextRatherThanEqualWeights() {
        val f = field(RunnerUiControlKind.RADIO, listOf("ABCDEFGHIJKLMN", "OPQRSTUVWXYZ"))
        val fit = draft(f).fitContent(f.id, measure)
        assertEquals(320, fit.draft.fields.single().ui.width); assertEquals(40, fit.draft.fields.single().ui.height)
    }
    @Test fun growthAvoidsBothNeighborsIncludingDiagonal() {
        val f = field(options = (1..20).map { "long option $it" })
        val right = f.copy(id = "right", ui = f.ui.copy(x = 160))
        val below = f.copy(id = "below", ui = f.ui.copy(x = 120, y = 100))
        val fit = draft(f, right, below).fitContent(f.id, measure)
        assertEquals(148, fit.draft.fields.first().ui.width); assertEquals(88, fit.draft.fields.first().ui.height)
        assertTrue(fit.limited); assertEquals(right, fit.draft.fields[1]); assertEquals(below, fit.draft.fields[2])
    }
    @Test fun boundsOverlapAndManualSizeRemainProtected() {
        val f = field()
        val bounded = draft(f).copy(pages = listOf(UiPage(width = 120, height = 100))).fitContent(f.id, measure)
        assertEquals(92, bounded.draft.fields.single().ui.height); assertTrue(bounded.limited)
        val overlap = draft(f, f.copy(id = "other")); assertEquals(overlap, overlap.fitContent(f.id, measure).draft)
        val large = f.copy(ui = f.ui.copy(width = 400, height = 400)); assertEquals(large, draft(large).fitContent(large.id, measure).draft.fields.single())
    }
    @Test fun onlyDisplayChangesTriggerFitAndMeasurementsAreBounded() {
        val f = field(RunnerUiControlKind.RADIO, listOf("A", "B"))
        assertFalse(f.copy(initialText = "B").contentSizeChanged(f)); assertFalse(f.copy(ui = f.ui.copy(width = 200)).contentSizeChanged(f))
        assertTrue(f.copy(optionsText = designerOptionsText(listOf("C", "D"))).contentSizeChanged(f))
        assertThrows(IllegalArgumentException::class.java) { draft(f).fitContent(f.id, { Float.NaN }) }
        val huge = field(options = listOf("a".repeat(129))); assertThrows(IllegalArgumentException::class.java) { draft(huge).fitContent(huge.id, measure) }
    }
    @Test fun exactLineMetricsAndContainerBoundsAreRespected() {
        val f = field(options = listOf("A", "B"))
        assertEquals(120, draft(f).fitContent(f.id, measure) { _, _, _ -> 60 }.draft.fields.single().ui.height)
        val box = f.copy(id = "box", kind = RunnerUiControlKind.CONTAINER, ui = f.ui.copy(control = "CONTAINER", width = 180, height = 100))
        val child = field().copy(ui = f.ui.copy(parentId = "box"))
        assertEquals(92, draft(box, child).fitContent(child.id, measure).draft.fields[1].ui.height)
    }

    @Test fun switchUsesSameCompactTextReservationAsPreviewAndRenderer() {
        val f = field(RunnerUiControlKind.SWITCH).copy(label = "abcdefghijklmnopqrst")
        val fit = draft(f).fitContent(f.id, measure)
        assertEquals(240, fit.draft.fields.single().ui.width)
        assertEquals(40, fit.draft.fields.single().ui.height)
        assertFalse(fit.limited)
    }
}

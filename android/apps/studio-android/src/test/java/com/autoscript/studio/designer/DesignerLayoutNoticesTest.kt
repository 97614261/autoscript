package com.autoscript.studio

import com.autoscript.script.ui.*
import kotlin.math.ceil
import org.junit.Assert.*
import org.junit.Test

class DesignerLayoutNoticesTest {
    private val metrics = object : DesignerTextMeasure {
        override fun width(text: String) = text.length * 10f
        override fun rowHeight(text: String, width: Int, reserved: Int) = ceil(text.length * 10f / (width - reserved).coerceAtLeast(1)).toInt().coerceAtLeast(1) * 24 + 8
    }
    private fun field(kind: RunnerUiControlKind, text: String = "ABC") =
        newDesignerControl(kind, "control1", "main", 8, 8, 100, 40).copy(initialText = text)
    private fun notices(vararg fields: RunnerUiFieldDraft) = RunnerUiDesignerDraft(fields = fields.toList()).layoutNotices { metrics }

    @Test fun longInputAndPlaceholderWarnWithoutChangingOrRejectingValidSavedValues() {
        val field = field(RunnerUiControlKind.TEXT, "long text value")
        assertEquals(DesignerNoticeKind.CONTENT, notices(field).single().kind)
        val draft = RunnerUiDesignerDraft(fields = listOf(field))
        assertNull(draft.firstValidationIssue())
        assertEquals(draft, runnerUiDesignerDraft(draft.toRunnerUiJson()))
        val placeholder = field.copy(label = "long placeholder value", initialText = "")
        assertEquals(DesignerNoticeKind.CONTENT, notices(placeholder).single().kind)
    }

    @Test fun tallWrappedTextAndChosenOptionCanBeTruncatedButNormalTextIsQuiet() {
        assertTrue(notices(field(RunnerUiControlKind.TEXT)).isEmpty())
        assertEquals(DesignerNoticeKind.CONTENT, notices(field(RunnerUiControlKind.LABEL, "long wrapped label content")).single().kind)
        val choice = field(RunnerUiControlKind.CHOICE, "A").copy(optionsText = designerOptionsText(listOf("A", "longer choice label")))
        assertEquals(DesignerNoticeKind.CONTENT, notices(choice).single().kind)
    }

    @Test fun listOverflowIsInformationalScrollingRatherThanAClippingError() {
        val list = field(RunnerUiControlKind.MULTISELECT, "[]").copy(optionsText = designerOptionsText(listOf("A", "B", "C")))
        assertEquals(DesignerNoticeKind.SCROLL, notices(list).single().kind)
        assertNull(RunnerUiDesignerDraft(fields = listOf(list)).firstValidationIssue())
    }

    @Test fun allAncestorAndPageBoundsAreCheckedWithoutTreatingRoundedCornersAsAnError() {
        val container = field(RunnerUiControlKind.CONTAINER, "").copy(id = "container", ui = UiPresentation(control = "CONTAINER", x = 680, y = 8, width = 80, height = 80, cornerPx = 30))
        val child = field(RunnerUiControlKind.LABEL).copy(ui = UiPresentation(control = "LABEL", parentId = "container", x = 10, y = 0, width = 50, height = 40))
        val bounds = notices(container, child).filter { it.kind == DesignerNoticeKind.BOUNDS }
        assertEquals(setOf("container", "control1"), bounds.map { it.controlId }.toSet())
        val moved = container.copy(ui = container.ui.copy(x = 8))
        assertTrue(notices(moved, child).none { it.kind == DesignerNoticeKind.BOUNDS })
    }

    @Test fun invalidOptionDraftDoesNotCrashOrSuppressOtherControlsNotices() {
        val invalid = field(RunnerUiControlKind.MULTISELECT, "[]").copy(optionsText = "[unfinished")
        val long = field(RunnerUiControlKind.TEXT, "long long long long").copy(id = "other")
        assertEquals("other", notices(invalid, long).single().controlId)
    }
}

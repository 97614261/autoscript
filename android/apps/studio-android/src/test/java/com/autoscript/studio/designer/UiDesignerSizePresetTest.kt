package com.autoscript.studio

import com.autoscript.script.ui.UiPage
import com.autoscript.script.ui.UiPresentation
import org.junit.Assert.*
import org.junit.Test

class UiDesignerSizePresetTest {
    private fun field(id: String = "a", x: Int = 8, y: Int = 8) = RunnerUiFieldDraft(
        id, "标题", RunnerUiControlKind.TEXT, false, "内容",
        ui = UiPresentation(control = "INPUT", x = x, y = y, width = 100, height = 40, fontPx = 20,
            background = "#FF123456", alignment = "right", binding = "global:name", events = mapOf("change" to "run")),
    )
    private fun draft(vararg fields: RunnerUiFieldDraft) = RunnerUiDesignerDraft(fields = fields.toList())

    @Test fun presetsChangeOnlyWidthHeightAndFontAndRoundTrip() {
        val source = draft(field())
        DesignerSizePreset.entries.forEach { preset ->
            val next = source.withSizePreset(setOf("a"), preset)
            val actual = next.fields.single()
            assertEquals(source.fields.single().copy(ui = source.fields.single().ui.copy(width = preset.width, height = preset.height, fontPx = preset.fontPx)), actual)
            assertTrue(preset.matches(actual.ui))
            assertEquals(next, runnerUiDesignerDraft(next.toRunnerUiJson()))
            assertEquals(100, source.fields.single().ui.width)
        }
        assertEquals(208, DesignerSizePreset.MEDIUM.width)
        assertEquals(40, DesignerSizePreset.MEDIUM.height)
        assertEquals(20, DesignerSizePreset.MEDIUM.fontPx)
        assertFalse(DesignerSizePreset.MEDIUM.matches(field().ui))
    }

    @Test fun multipleSelectionIsOneChangeAndOtherControlsStayUntouched() {
        val other = field("other", y = 200)
        val source = draft(field(), field("b", x = 360), other)
        val next = source.withSizePreset(setOf("a", "b"), DesignerSizePreset.LARGE)
        assertTrue(next.fields.take(2).all { DesignerSizePreset.LARGE.matches(it.ui) })
        assertEquals(other, next.fields.last())
        assertEquals(source.fields.map { it.id }, next.fields.map { it.id })
        assertEquals(listOf(8 to 8, 360 to 8, 8 to 200), next.fields.map { it.ui.x to it.ui.y })
    }

    @Test fun pageAndContainerOverflowRejectWithoutPartialChange() {
        val a = field()
        val b = field("b", x = 200)
        val source = draft(a, b).copy(pages = listOf(UiPage(width = 400, height = 400)))
        assertThrows(IllegalArgumentException::class.java) { source.withSizePreset(setOf("a", "b"), DesignerSizePreset.LARGE) }
        assertEquals(listOf(a, b), source.fields)
        val box = field("box").copy(kind = RunnerUiControlKind.CONTAINER, ui = a.ui.copy(control = "CONTAINER", width = 200, height = 100))
        val child = a.copy(ui = a.ui.copy(parentId = "box"))
        assertThrows(IllegalArgumentException::class.java) { draft(box, child).withSizePreset(setOf("a"), DesignerSizePreset.MEDIUM) }
        assertTrue(DesignerSizePreset.SMALL.matches(draft(box, child).withSizePreset(setOf("a"), DesignerSizePreset.SMALL).fields.last().ui))
    }

    @Test fun growingCannotIntroduceNeighborOverlapOrCropContainerChildren() {
        val source = draft(field(), field("b", x = 180))
        assertThrows(IllegalArgumentException::class.java) { source.withSizePreset(setOf("a"), DesignerSizePreset.MEDIUM) }
        assertTrue(DesignerSizePreset.SMALL.matches(source.withSizePreset(setOf("a"), DesignerSizePreset.SMALL).fields.first().ui))
        val box = field("box").copy(kind = RunnerUiControlKind.CONTAINER, ui = field().ui.copy(control = "CONTAINER", width = 300, height = 100))
        val child = field().copy(ui = field().ui.copy(parentId = "box"))
        assertThrows(IllegalArgumentException::class.java) { draft(box, child).withSizePreset(setOf("box"), DesignerSizePreset.SMALL) }
        assertEquals(child, draft(box, child).withSizePreset(setOf("box"), DesignerSizePreset.LARGE).fields.last())
    }

    @Test fun explicitSizeDoesNotAutomaticallyGrowForLongOptionsOrChangeSelections() {
        val f = field().copy(kind = RunnerUiControlKind.MULTISELECT, initialText = "[\"选项1\"]", optionsText = designerOptionsText((1..8).map { "选项$it" }), ui = field().ui.copy(control = "MULTISELECT"))
        val next = draft(f).withSizePreset(setOf("a"), DesignerSizePreset.SMALL)
        assertEquals(32, next.fields.single().ui.height)
        assertEquals(f.optionsText, next.fields.single().optionsText)
        assertEquals(f.initialText, next.fields.single().initialText)
        assertThrows(IllegalArgumentException::class.java) { draft(f).withSizePreset(emptySet(), DesignerSizePreset.SMALL) }
        assertThrows(IllegalArgumentException::class.java) { draft(f).withSizePreset(setOf("missing"), DesignerSizePreset.SMALL) }
    }
}

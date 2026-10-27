package com.autoscript.studio

import org.junit.Assert.*
import org.junit.Test

class GlyphMakerWorkflowTest {
    @Test fun coverageAndExpectedFeedbackNeverGuessOrRelabelGlyphs() {
        val samples = listOf(sample("1"), sample("A", 0x40), sample("a", 0x20), sample("😀", 0x10))
        assertEquals("023456789", glyphMakerMissing(samples, GlyphMakerAlphabet.DIGITS.characters))
        assertEquals(25, glyphMakerMissing(samples, GlyphMakerAlphabet.UPPER.characters).length)
        assertEquals(25, glyphMakerMissing(samples, GlyphMakerAlphabet.LOWER.characters).length)
        assertEquals("B", glyphMakerMissing(samples, "A😀BBA\n"))
        assertTrue(glyphMakerExpectedFeedback("A😀B", "A😀?", samples).contains("第3字"))
        assertTrue(glyphMakerExpectedFeedback("A😀B", "A😀?", samples).contains("尚未采样：B"))
        assertTrue(glyphMakerExpectedFeedback("A1", "A", samples).contains("第2字"))
        assertEquals("预期核对：完全一致", glyphMakerExpectedFeedback("A😀", "A😀", samples))
        assertTrue(glyphMakerExpectedFeedback("1", null, samples).startsWith("未获得"))
        assertEquals(listOf("1", "A", "a", "😀"), samples.map { it.label })
    }
    private fun sample(label: String = "1", value: Int = 0x80) = MakerGlyph(label, 2, 2, byteArrayOf(value.toByte()))

    @Test fun smallPicturesAndSelectedRegionsEnlargeOnlyTheView() {
        val viewport = ImageToolViewport.fit(40, 24, 600f, 320f)
        val focused = glyphMakerFocus(viewport)
        assertEquals(12f, focused.scale, .001f)
        assertEquals(40, focused.imageWidth)
        assertEquals(24, focused.imageHeight)
        assertEquals(20 to 12, focused.toImage(300f, 160f))
        val large = ImageToolViewport.fit(720, 1280, 600f, 320f)
        val roi = ImageToolCodeGen.Roi(200, 400, 300, 440)
        val zoom = glyphMakerFocus(large, roi)
        assertEquals(5.4f, zoom.scale, .001f)
        assertEquals(250 to 420, zoom.toImage(300f, 160f))
        assertEquals(roi, ImageToolCodeGen.Roi(200, 400, 300, 440))
        assertEquals(16f, glyphMakerFocus(viewport, ImageToolCodeGen.Roi(0, 0, 1, 1)).scale, 0f)
    }

    @Test fun viewFitRejectsInvalidGeometryAndThumbnailsDoNotCollapse() {
        assertThrows(IllegalArgumentException::class.java) {
            glyphMakerFocus(ImageToolViewport.fit(40, 24, 600f, 320f), ImageToolCodeGen.Roi(-1, 0, 2, 3))
        }
        assertEquals(34f / 256, glyphMakerMaskScale(256, 256, 34, 34), 0f)
        assertEquals(34f, 256 * glyphMakerMaskScale(256, 256, 34, 34), 0f)
        assertEquals(.5f, glyphMakerMaskScale(200, 50, 100, 100), 0f)
    }

    @Test fun labelFeedbackCountsUnicodeAndRejectsUnfinishedOrWhitespaceLabels() {
        assertTrue(glyphMakerLabelsReady(3, "1.1"))
        assertTrue(glyphMakerLabelsReady(1, "😀"))
        assertEquals(listOf("1", "😀"), glyphMakerLabelUnits("1😀"))
        for (text in listOf("", "12", "1.12", "1 1", "1\n1", "1\uD8001")) assertFalse(glyphMakerLabelsReady(3, text))
        assertEquals("023456789", glyphMakerMissingDigits(listOf(sample(), sample("."))))
    }

    @Test fun undoRedoRestoreTheSavedIdentityAndSelectionWithoutNewEdits() {
        val history = GlyphMakerHistory()
        val first = history.push(listOf(sample(), sample(".", 0x40)), 0)
        val second = history.push(listOf(sample(), sample(".", 0x60)), 1)
        assertEquals(first.id, history.undo(1).id)
        assertEquals(1, history.current.selected)
        assertTrue(history.canRedo)
        assertEquals(second.id, history.redo(0).id)
        assertEquals(1, history.current.selected)
        history.undo(1)
        history.push(listOf(sample(), sample(".", 0x20)), 1)
        assertFalse(history.canRedo)
        assertTrue(history.current.id > second.id)
    }

    @Test fun identicalEditsDoNotConsumeHistoryOrDiscardRedoAndInvalidEditsAreAtomic() {
        val history = GlyphMakerHistory()
        val first = history.push(listOf(sample()), 0)
        history.push(listOf(sample(value = 0x40)), 0)
        history.undo(0)
        assertEquals(first.id, history.push(listOf(sample()), 0).id)
        assertTrue(history.canRedo)
        assertThrows(IllegalArgumentException::class.java) { history.push(listOf(sample(), sample("2")), 0) }
        assertEquals(first.id, history.current.id)
        assertTrue(history.canRedo)
    }

    @Test fun zoomButtonsPreserveTheViewCenterAndCanShrinkSmallImages() {
        val fitted = glyphMakerFocus(ImageToolViewport.fit(40, 24, 600f, 320f))
        val smaller = glyphMakerZoom(fitted, .5f)
        assertEquals(fitted.scale / 2, smaller.scale, 0f)
        assertEquals(20 to 12, smaller.toImage(300f, 160f))
        assertEquals(fitted.scale, glyphMakerZoom(smaller, 2f).scale, 0f)
        assertEquals(16f, glyphMakerZoom(fitted, 100f).scale, 0f)
        assertThrows(IllegalArgumentException::class.java) { glyphMakerZoom(fitted, Float.NaN) }
    }

    @Test fun historyIsBoundedByStepsAndBytesAndCanDeleteLastSample() {
        val history = GlyphMakerHistory()
        repeat(40) { history.push(listOf(sample(it.toString())), 0) }
        assertEquals(GlyphMakerHistory.MAX_STATES, history.retainedStates)
        // 256 distinct labels with distinct 256x256 masks: exactly 2MiB per state.
        val large = (0..255).map { index -> MakerGlyph(index.toString(), 256, 256, ByteArray(8192).apply {
            this[0] = 0x80.toByte(); this[1] = index.toByte()
        }) }
        repeat(8) { iteration -> history.push(large.map { it.copy(label = "${it.label}-$iteration") }, 0) }
        assertTrue(history.retainedBytes <= GlyphMakerHistory.MAX_BYTES)
        assertTrue(history.retainedStates <= GlyphMakerHistory.MAX_STATES)
        val empty = history.push(emptyList(), 0)
        assertTrue(empty.samples.isEmpty())
        assertFalse(history.undo(0).samples.isEmpty())
        val reset = history.reset(listOf(sample()))
        assertFalse(history.canUndo); assertFalse(history.canRedo)
        assertTrue(reset.id > empty.id)
    }

    @Test fun strokesInterpolateAndCommitAsOneUndoWithoutMutatingTheirSource() {
        val original = MakerGlyph("1", 8, 8, ByteArray(8).apply { this[0] = 0x80.toByte() })
        val history = GlyphMakerHistory(); val first = history.push(listOf(original), 0)
        val stroke = GlyphMakerStroke(original, GlyphMakerBrush.INK, 1, 1)
        stroke.drawTo(7, 7)
        val result = stroke.result()
        for (i in 1..7) assertTrue(result.foreground(i, i))
        assertFalse(original.foreground(1, 1))
        history.push(listOf(result), 0)
        assertEquals(first.id, history.undo(0).id)
        assertTrue(history.current.samples.single().sameShape(original))
        assertThrows(IllegalArgumentException::class.java) { stroke.drawTo(-1, 2) }
    }

    @Test fun automaticStrokeUsesOnePolarityAndEraserCannotCommitAnEmptyOrAmbiguousGlyph() {
        val original = MakerGlyph("1", 4, 1, byteArrayOf(0xa0.toByte()))
        val auto = GlyphMakerStroke(original, GlyphMakerBrush.AUTO, 0, 0)
        auto.drawTo(2, 0); auto.drawTo(0, 0)
        assertEquals(0, auto.result().bits[0].toInt()) // Revisited points stay erased, not toggled back.
        assertEquals(0xa0.toByte(), original.bits[0])
        val history = GlyphMakerHistory(); val first = history.push(listOf(original), 0)
        assertThrows(IllegalArgumentException::class.java) { history.push(listOf(auto.result()), 0) }
        assertEquals(first.id, history.current.id)
        val paint = GlyphMakerStroke(original, GlyphMakerBrush.INK, 1, 0).result()
        assertTrue(paint.foreground(1, 0))
        assertThrows(IllegalArgumentException::class.java) { history.push(listOf(paint, paint.copy(label = "2")), 0) }
        assertEquals(first.id, history.current.id)
        assertThrows(IllegalArgumentException::class.java) { GlyphMakerStroke(original, GlyphMakerBrush.MOVE, 0, 0) }
    }

    @Test fun correctionReplacesOneCodePointAndSearchPreservesOriginalSampleIndices() {
        assertEquals("1.😀", glyphMakerReplaceLabel("1?😀", 1, "."))
        assertEquals("1😀1", glyphMakerReplaceLabel("1.1", 1, "😀"))
        for (text in listOf("", " ", "12", "\uD800")) assertThrows(IllegalArgumentException::class.java) {
            glyphMakerReplaceLabel("1.1", 1, text)
        }
        assertThrows(IllegalArgumentException::class.java) { glyphMakerReplaceLabel("1", 1, ".") }
        val list = listOf(sample(), sample("2", 0x40), sample("1", 0x60))
        assertEquals(listOf(0, 2), glyphMakerSampleIndices(list, "1"))
        assertEquals(listOf(0, 1, 2), glyphMakerSampleIndices(list, ""))
        assertTrue(glyphMakerSampleIndices(list, "8").isEmpty())
    }
}

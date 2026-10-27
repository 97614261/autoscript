package com.autoscript.studio

import org.junit.Assert.*
import org.junit.Test

class GlyphMakerPendingBatchTest {
    private fun glyph(label: String = "", mask: Int = 0x80) = MakerGlyph(label, 2, 2, byteArrayOf(mask.toByte()))

    @Test fun arbitraryOrderLabellingKeepsEmptySlotsAndAdvancesToUnlabelledSamples() {
        val original = GlyphMakerPendingBatch(listOf(glyph(), glyph(mask = 0x40), glyph(mask = 0x20)))
        val third = original.label(2, ")")
        assertEquals(listOf("", "", ")"), third.samples.map { it.label })
        assertEquals(1, third.labelledCount)
        assertFalse(third.ready)
        assertEquals(0, third.nextUnlabelled(2))
        val first = third.label(0, "😀")
        assertEquals(1, first.nextUnlabelled(0))
        val complete = first.label(1, "+")
        assertTrue(complete.ready)
        assertEquals("😀+)", complete.text)
        assertEquals(-1, complete.nextUnlabelled(1))
        assertEquals(listOf("", "", ""), original.samples.map { it.label })
    }

    @Test fun removingNoiseKeepsLabelsAttachedToTheirOriginalShapes() {
        val original = GlyphMakerPendingBatch(listOf(glyph("1"), glyph("", 0x40), glyph("+", 0x20)))
        val removed = original.remove(1)
        assertEquals(listOf("1", "+"), removed.samples.map { it.label })
        assertTrue(removed.samples[0].sameShape(original.samples[0]))
        assertTrue(removed.samples[1].sameShape(original.samples[2]))
        assertTrue(removed.ready)
        assertEquals(3, original.samples.size)
        val empty = removed.remove(1).remove(0)
        assertFalse(empty.ready)
        assertEquals(-1, empty.nextUnlabelled(-1))
    }

    @Test fun bulkAssignmentRequiresExactUnicodeCharacterCountAndNeverAcceptsWhitespace() {
        val original = GlyphMakerPendingBatch(listOf(glyph(), glyph(mask = 0x40)))
        assertEquals("(+", original.labelAll("(+").text)
        assertEquals("😀1", original.labelAll("😀1").text)
        for (invalid in listOf("", "1", "123", "1 ", "1\n", "1\uD800")) {
            assertThrows(IllegalArgumentException::class.java) { original.labelAll(invalid) }
        }
        assertFalse(original.ready)
        assertEquals(listOf("", ""), original.samples.map { it.label })
    }

    @Test fun invalidSingleEditsAndIndicesCannotChangeTheBatch() {
        val original = GlyphMakerPendingBatch(listOf(glyph()))
        for (invalid in listOf("", "12", " ", "\uD800"))
            assertThrows(IllegalArgumentException::class.java) { original.label(0, invalid) }
        assertThrows(IllegalArgumentException::class.java) { original.label(1, "+") }
        assertThrows(IllegalArgumentException::class.java) { original.remove(-1) }
        assertThrows(IllegalArgumentException::class.java) { original.nextUnlabelled(1) }
        assertThrows(IllegalArgumentException::class.java) { GlyphMakerPendingBatch(listOf(glyph("12"))) }
        assertEquals("", original.samples.single().label)
    }

    @Test fun targetsAreExplicitBoundedAndDeduplicatedWithoutSplittingUnicode() {
        assertEquals("01()+-😀", glyphMakerTargetCharacters("001()+-😀😀"))
        assertEquals("a", glyphMakerTargetCharacters("a".repeat(128)))
        for (invalid in listOf("", "1 2", "1\n", "\uD800", "a".repeat(129)))
            assertThrows(IllegalArgumentException::class.java) { glyphMakerTargetCharacters(invalid) }
        assertTrue(GlyphMakerCharacterSet.NUMERIC.characters.contains("()+-"))
        assertEquals("()+-", glyphMakerMissing(listOf(glyph("0"), glyph("1", 0x40)), "001()+-"))
    }

    @Test fun shortcutsRequireMatchingCountsAndDoNotAppendSymbolsToThe62CharacterSequence() {
        val existing = listOf(glyph("0"), glyph("1", 0x40))
        assertEquals(listOf("234"), glyphMakerLabelShortcuts(3, "01234", existing).map { it.second })
        val count62 = glyphMakerLabelShortcuts(62, "01234", existing)
        assertEquals(listOf("0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"), count62.map { it.second })
        assertTrue(glyphMakerLabelShortcuts(0, "", emptyList()).isEmpty())
        assertTrue(glyphMakerLabelShortcuts(2, "012345", existing).isEmpty())
        val count10 = glyphMakerLabelShortcuts(10, "0123456789", emptyList())
        assertEquals(1, count10.size)
        assertEquals("0123456789", count10.single().second)
    }

    @Test fun preparedBatchStillUsesDictionaryValidationAndRoundTripsBeforeSaving() {
        val pending = GlyphMakerPendingBatch(listOf(glyph(), glyph(mask = 0x40), glyph(mask = 0x20))).labelAll("(+)")
        val samples = GlyphDictionaryMaker.append(emptyList(), pending.samples, pending.text)
        assertEquals(listOf("(", "+", ")"), GlyphDictionaryMaker.decode(GlyphDictionaryMaker.encode(samples)).map { it.label })
        assertEquals(samples.size, GlyphDictionaryMaker.append(samples, pending.samples, pending.text).size)
        val ambiguous = GlyphMakerPendingBatch(listOf(glyph(), glyph())).labelAll("01")
        assertThrows(IllegalArgumentException::class.java) { GlyphDictionaryMaker.append(emptyList(), ambiguous.samples, ambiguous.text) }
    }

}

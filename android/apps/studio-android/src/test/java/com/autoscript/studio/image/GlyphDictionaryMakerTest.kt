package com.autoscript.studio

import org.junit.Assert.*
import org.junit.Test

class GlyphDictionaryMakerTest {
    private val sample = MakerGlyph("A", 3, 3, byteArrayOf(0x57, 0x80.toByte()))
    private fun rejects(block: () -> Unit) { try { block(); fail("must reject") } catch (_: IllegalArgumentException) { } }

    @Test fun exactRustFormatFixtureAndRoundTrip() {
        val bytes = GlyphDictionaryMaker.encode(listOf(sample))
        // Also decoded in glyph-ocr's studio_authoring_fixture test.
        assertEquals("4153474c595048000100000001000000010003000300000002000000415780",
            bytes.joinToString("") { "%02x".format(it.toInt() and 255) })
        val decoded = GlyphDictionaryMaker.decode(bytes).single()
        assertEquals("A", decoded.label)
        assertTrue(sample.sameShape(decoded))
        assertArrayEquals(bytes, GlyphDictionaryMaker.encode(listOf(decoded)))
    }

    @Test fun malformedFilesFailWithoutPartialDecode() {
        val good = GlyphDictionaryMaker.encode(listOf(sample))
        for (length in 0 until good.size) rejects { GlyphDictionaryMaker.decode(good.copyOf(length)) }
        rejects { GlyphDictionaryMaker.decode(good + byteArrayOf(0)) }
        for (offset in listOf(0, 8, 10, 12, 16, 18, 20, 22, 24)) {
            rejects { GlyphDictionaryMaker.decode(good.copyOf().apply { this[offset] = 127 }) }
        }
        rejects { GlyphDictionaryMaker.decode(good.copyOf().apply { this[lastIndex] = 0x81.toByte() }) }
        rejects { GlyphDictionaryMaker.decode(good.copyOf().apply { this[28] = 0xff.toByte() }) }
    }

    @Test fun rejectsUnlabelledEmptyAndConflictingMasksButAllowsVariants() {
        rejects { GlyphDictionaryMaker.encode(listOf(sample.copy(label = ""))) }
        rejects { GlyphDictionaryMaker.encode(listOf(sample.copy(label = "\n"))) }
        rejects { GlyphDictionaryMaker.encode(listOf(sample.copy(label = "\uD800"))) }
        rejects { GlyphDictionaryMaker.encode(listOf(sample.copy(bits = ByteArray(2)))) }
        rejects { GlyphDictionaryMaker.encode(listOf(sample, sample.copy(label = "B"))) }
        assertEquals(2, GlyphDictionaryMaker.decode(GlyphDictionaryMaker.encode(listOf(sample,
            sample.copy(bits = byteArrayOf(0x77, 0x80.toByte()))))).size)
        rejects { GlyphDictionaryMaker.encode(List(257) { sample }) }
    }

    @Test fun colorToleranceIsPerChannelAndOpaqueOnly() {
        val glyph = GlyphDictionaryMaker.sample(intArrayOf(0xff112233.toInt(), 0xff132435.toInt(), 0xff142233.toInt(), 0x00112233),
            4, 1, 0, 0, 4, 1, 0x112233, 2, false).single()
        assertEquals(2, glyph.width)
        assertEquals(1, glyph.height)
        assertTrue(glyph.foreground(0, 0) && glyph.foreground(1, 0))
    }

    @Test fun batchSamplesMaintainLineExtentIncludingDecimalBaseline() {
        // 1 . 1, no occupied row gap; decimal keeps the digit line height, as runtime does.
        val pixels = listOf("#...#", "#...#", "#.#.#").flatMap { row -> row.map { if (it == '#') -1 else 0xff000000.toInt() } }.toIntArray()
        val glyphs = GlyphDictionaryMaker.sample(pixels, 5, 3, 0, 0, 5, 3, 0xffffff, 0, true)
        assertEquals(3, glyphs.size)
        assertEquals(3, glyphs[1].height)
        assertFalse(glyphs[1].foreground(0, 0))
        assertTrue(glyphs[1].foreground(0, 2))
        assertEquals(listOf("1", ".", "1"), GlyphDictionaryMaker.assign(glyphs, "1.1").map { it.label })
        val labelled = GlyphDictionaryMaker.assign(glyphs, "1.1")
        assertEquals("4153474c59504800010000000200000001000100030000000100000031e00100010003000000010000002e20",
            GlyphDictionaryMaker.encode(labelled.take(2)).joinToString("") { "%02x".format(it.toInt() and 255) })
        rejects { GlyphDictionaryMaker.assign(glyphs, "01") }
        rejects { GlyphDictionaryMaker.assign(glyphs, "1 1") }
    }

    @Test fun boundsBudgetsAndCancellationAreNotSilentlyClamped() {
        rejects { GlyphDictionaryMaker.sample(intArrayOf(-1), 1, 1, -1, 0, 1, 1, 0xffffff, 0, false) }
        rejects { GlyphDictionaryMaker.sample(intArrayOf(-1), 1, 1, Int.MAX_VALUE, 0, 1, 1, 0xffffff, 0, false) }
        rejects { GlyphDictionaryMaker.sample(intArrayOf(-1), 1, 1, 0, 0, 1, 1, 0xffffff, 256, false) }
        rejects { GlyphDictionaryMaker.sample(intArrayOf(0xff000000.toInt()), 1, 1, 0, 0, 1, 1, 0xffffff, 0, false) }
        rejects { GlyphDictionaryMaker.sample(IntArray(257) { -1 }, 257, 1, 0, 0, 257, 1, 0xffffff, 0, false) }
        rejects { GlyphDictionaryMaker.sample(intArrayOf(-1, 0xff000000.toInt(), -1), 3, 1, 0, 0, 3, 1, 0xffffff, 0, false) }
        var checkpoints = 0
        try {
            GlyphDictionaryMaker.sample(IntArray(9) { -1 }, 3, 3, 0, 0, 3, 3, 0xffffff, 0, true) {
                checkpoints++; throw IllegalStateException("cancel")
            }
            fail("cancelled")
        } catch (_: IllegalStateException) { assertEquals(1, checkpoints) }
    }

    @Test fun allTenPublicFixedDigitsCanBeBatchedLabelledAndSaved() {
        val digits = listOf(
            listOf("###", "#.#", "#.#", "#.#", "###"),
            listOf(".#.", "##.", ".#.", ".#.", "###"),
            listOf("###", "..#", "###", "#..", "###"),
            listOf("###", "..#", "###", "..#", "###"),
            listOf("#.#", "#.#", "###", "..#", "..#"),
            listOf("###", "#..", "###", "..#", "###"),
            listOf("###", "#..", "###", "#.#", "###"),
            listOf("###", "..#", "..#", "..#", "..#"),
            listOf("###", "#.#", "###", "#.#", "###"),
            listOf("###", "#.#", "###", "..#", "###"))
        val rows = (0..4).map { y -> digits.joinToString(".") { it[y] } }
        val pixels = rows.flatMap { row -> row.map { if (it == '#') -1 else 0xff000000.toInt() } }.toIntArray()
        val sampled = GlyphDictionaryMaker.sample(pixels, 39, 5, 0, 0, 39, 5, 0xffffff, 0, true)
        assertEquals(10, sampled.size)
        val labelled = GlyphDictionaryMaker.assign(sampled, "0123456789")
        val restored = GlyphDictionaryMaker.decode(GlyphDictionaryMaker.encode(labelled))
        assertEquals("0123456789", restored.joinToString("") { it.label })
        assertTrue(restored.all { it.width == 3 && it.height == 5 })
    }

    @Test fun appendRetainsOrderAndVariantsButDuplicatesDoNotCreateEdits() {
        val original = listOf(sample)
        assertSame(original, GlyphDictionaryMaker.append(original, listOf(sample.copy(label = "")), "A"))
        val variant = sample.copy(label = "", bits = byteArrayOf(0x77, 0x80.toByte()))
        val merged = GlyphDictionaryMaker.append(original, listOf(variant, variant), "AA")
        assertEquals(2, merged.size)
        assertSame(sample, merged.first())
        assertTrue(variant.sameShape(merged.last()))
        rejects { GlyphDictionaryMaker.append(original, listOf(sample), "B") }
        assertEquals(1, original.size)
        rejects { GlyphDictionaryMaker.append(original, listOf(variant), "AB") }
    }
}

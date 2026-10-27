package com.autoscript.studio

import com.autoscript.project.store.ProjectGlyphColor
import org.junit.Assert.*
import org.junit.Test

class GlyphMakerPrecisionTest {
    @Test fun foregroundPreviewUsesExactlySampleMaskAndOriginalCoordinates() {
        val pixels = intArrayOf(0, 0, 0, 0, 0, -1, 0xfff8f8f8.toInt(), 0, 0, 0, 0, 0)
        val colors = listOf(ProjectGlyphColor(0xffffff, 7))
        val mask = GlyphDictionaryMaker.foregroundMask(pixels, 4, 3, 1, 1, 3, 2, colors)
        assertArrayEquals(booleanArrayOf(true, true), mask)
        val glyph = GlyphDictionaryMaker.sample(pixels, 4, 3, 1, 1, 3, 2, colors, false).single()
        assertEquals(2, glyph.width)
        mask.forEachIndexed { x, value -> assertEquals(value, glyph.foreground(x, 0)) }
        assertEquals(-1, pixels[5])
    }

    @Test fun alphaAndMultiColorToleranceMatchPreview() {
        val rules = listOf(ProjectGlyphColor(0xff0000, 0), ProjectGlyphColor(0xffffff, 7))
        val pixels = intArrayOf(0xffff0000.toInt(), 0x80ffffff.toInt(), 0xfff8f8f8.toInt(), 0xfff7ffff.toInt())
        assertArrayEquals(booleanArrayOf(true, false, true, false),
            GlyphDictionaryMaker.foregroundMask(pixels, 4, 1, 0, 0, 4, 1, rules))
    }

    @Test fun emptyForegroundCanBePreviewedButCannotBeSampled() {
        val pixels = intArrayOf(0xff000000.toInt())
        val rules = listOf(ProjectGlyphColor(0xffffff, 0))
        assertFalse(GlyphDictionaryMaker.foregroundMask(pixels, 1, 1, 0, 0, 1, 1, rules)[0])
        assertThrows(IllegalArgumentException::class.java) { GlyphDictionaryMaker.sample(pixels, 1, 1, 0, 0, 1, 1, rules, false) }
    }

    @Test fun foregroundPreviewHonorsCancellation() {
        assertThrows(InterruptedException::class.java) {
            GlyphDictionaryMaker.foregroundMask(intArrayOf(-1), 1, 1, 0, 0, 1, 1, listOf(ProjectGlyphColor(0xffffff, 0))) {
                throw InterruptedException()
            }
        }
    }

    @Test fun regionInputRejectsOverflowAndOutOfImageWithoutClamping() {
        assertEquals(ImageToolCodeGen.Roi(2, 3, 6, 8), glyphMakerRegion("2", "3", "4", "5", 10, 10))
        for (values in listOf(listOf("-1", "0", "1", "1"), listOf("0", "0", "0", "1"),
            listOf("0", "0", "11", "1"), listOf("2147483647", "0", "2147483647", "1"), listOf("", "0", "1", "1")))
            assertNull(glyphMakerRegion(values[0], values[1], values[2], values[3], 10, 10))
    }

    @Test fun movingAtImageEdgePreservesSizeAndNeverLeavesImage() {
        val roi = ImageToolCodeGen.Roi(2, 3, 6, 8)
        assertEquals(ImageToolCodeGen.Roi(0, 0, 4, 5), glyphMakerMoveRegion(roi, -100, -100, 10, 10))
        assertEquals(ImageToolCodeGen.Roi(6, 5, 10, 10), glyphMakerMoveRegion(roi, Int.MAX_VALUE, Int.MAX_VALUE, 10, 10))
        assertEquals(ImageToolCodeGen.Roi(1, 3, 5, 8), glyphMakerMoveRegion(roi, -1, 0, 10, 10))
    }
}

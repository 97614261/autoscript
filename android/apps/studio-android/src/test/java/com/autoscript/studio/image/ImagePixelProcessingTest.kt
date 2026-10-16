package com.autoscript.studio

import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

class ImagePixelProcessingTest {
    private fun process(mode: String, width: Int = 0, height: Int = 0, roi: ImageToolCodeGen.Roi? = null) =
        processImagePixels(intArrayOf(1, 2, 3, 4, 5, 6), 2, 3, ImagePixelOperation(mode, width, height, roi)) {}

    @Test fun rotationsAndMirrorsHaveExplicitPixelOrder() {
        assertArrayEquals(intArrayOf(5, 3, 1, 6, 4, 2), process("rotate90").argb)
        assertArrayEquals(intArrayOf(2, 4, 6, 1, 3, 5), process("rotate270").argb)
        assertArrayEquals(intArrayOf(6, 5, 4, 3, 2, 1), process("rotate180").argb)
        assertArrayEquals(intArrayOf(2, 1, 4, 3, 6, 5), process("flipX").argb)
        assertArrayEquals(intArrayOf(5, 6, 3, 4, 1, 2), process("flipY").argb)
        assertEquals(3 to 2, process("rotate90").let { it.width to it.height })
    }

    @Test fun grayscaleMatchesRustIntegerWeightsAndPreservesAlphaAndSource() {
        val source = intArrayOf(0x280A141E, 0x80323C46.toInt(), 0xFFFF0000.toInt(), 0x1200FF00)
        val copy = source.copyOf()
        val out = processImagePixels(source, 4, 1, ImagePixelOperation("gray")) {}
        assertArrayEquals(intArrayOf(0x28121212, 0x803A3A3A.toInt(), 0xFF4D4D4D.toInt(), 0x12959595), out.argb)
        assertArrayEquals(copy, source)
    }

    @Test fun nearestResizeAndHalfOpenCropDoNotFilterPixels() {
        assertArrayEquals(intArrayOf(1, 1, 2, 2), process("resize", 4, 1).argb)
        assertArrayEquals(intArrayOf(3), process("crop", roi = ImageToolCodeGen.Roi(0, 1, 1, 2)).argb)
        assertArrayEquals(intArrayOf(1), process("resize", 1, 1).argb)
    }

    @Test fun thresholdMatchesRustWeightsBoundaryAndPreservesAlphaAndSource() {
        val source = intArrayOf(0x407F7F7F, 0x80808080.toInt(), 0xFFFFFFFF.toInt(), 0xFF000000.toInt(), 0x1200FF00)
        val original = source.copyOf()
        fun threshold(value: Int) = processImagePixels(source, 5, 1, ImagePixelOperation("threshold", threshold = value)) {}.argb
        assertArrayEquals(intArrayOf(0x40000000, 0x80FFFFFF.toInt(), 0xFFFFFFFF.toInt(), 0xFF000000.toInt(), 0x12FFFFFF), threshold(128))
        assertArrayEquals(source.map { (it and -0x1000000) or 0xFFFFFF }.toIntArray(), threshold(0))
        assertArrayEquals(intArrayOf(0x40000000, 0x80000000.toInt(), 0xFFFFFFFF.toInt(), 0xFF000000.toInt(), 0x12000000), threshold(255))
        assertArrayEquals(original, source)
        assertEquals(5 to 1, ImagePixelOperation("threshold").dimensions(5, 1))
        listOf(-1, 256).forEach { value ->
            assertThrows(IllegalArgumentException::class.java) { threshold(value) }
        }
    }

    @Test fun badDimensionsModesAndCancellationDoNotYieldPartialResults() {
        listOf(ImagePixelOperation("resize", 0, 1), ImagePixelOperation("resize", 4096, 4096),
            ImagePixelOperation("rotate90", 1, 1), ImagePixelOperation("unknown"),
            ImagePixelOperation("crop", roi = ImageToolCodeGen.Roi(-1, 0, 1, 1))).forEach {
            assertThrows(Exception::class.java) { it.dimensions(2, 3) }
        }
        var calls = 0
        assertThrows(CancellationException::class.java) {
            processImagePixels(intArrayOf(1, 2, 3, 4), 2, 2, ImagePixelOperation("flipX")) {
                if (++calls == 2) throw CancellationException()
            }
        }
    }
}

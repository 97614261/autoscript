package com.autoscript.studio

import com.autoscript.studio.ImageToolCodeGen.Roi
import org.junit.Assert.*
import org.junit.Test

class ImageTemplatePixelsTest {
    @Test fun `normal crops preserve exact original dimensions`() {
        assertEquals(100 to 30, templateCropDimensions(1080, 2400, Roi(50, 100, 150, 130)))
        assertEquals(1080 to 2400, templateCropDimensions(1080, 2400, Roi(0, 0, 1080, 2400)))
        assertEquals(2048 to 2048, templateCropDimensions(4096, 4096, Roi(0, 0, 2048, 2048)))
    }

    @Test fun `oversized crops reject instead of silently resizing`() {
        assertThrows(IllegalArgumentException::class.java) { templateCropDimensions(1440, 3200, Roi(0, 0, 1440, 3200)) }
        assertThrows(IllegalArgumentException::class.java) { templateCropDimensions(5000, 100, Roi(0, 0, 5000, 100)) }
    }

    @Test fun `invalid crops reject instead of clamping into different pixels`() {
        listOf(Roi(-1, 0, 10, 10), Roi(0, 0, 0, 10), Roi(10, 10, 5, 5), Roi(0, 0, 101, 30)).forEach { roi ->
            assertThrows(IllegalArgumentException::class.java) { templateCropDimensions(100, 100, roi) }
        }
    }
}

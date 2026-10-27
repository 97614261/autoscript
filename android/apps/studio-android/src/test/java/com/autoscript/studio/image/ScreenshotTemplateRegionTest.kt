package com.autoscript.studio

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream
import java.util.zip.InflaterInputStream
import org.junit.Assert.*
import org.junit.Test

class ScreenshotTemplateRegionTest {
    private val origin = ScreenshotTemplateRegion(720, 1280, ImageToolCodeGen.Roi(149, 407, 269, 487))

    private fun chunk(type: String, payload: ByteArray): ByteArray = ByteArrayOutputStream().also { bytes ->
        DataOutputStream(bytes).apply {
            writeInt(payload.size); writeBytes(type); write(payload)
            writeInt(CRC32().apply { update(type.toByteArray()); update(payload) }.value.toInt())
        }
    }.toByteArray()

    /** Real, lossless RGBA PNG with filter-0 rows, using only the JVM/Android shared APIs. */
    private fun image(): ByteArray {
        val header = ByteArrayOutputStream().also { DataOutputStream(it).apply {
            writeInt(120); writeInt(80); write(byteArrayOf(8, 6, 0, 0, 0))
        } }.toByteArray()
        val pixels = ByteArrayOutputStream().also { rows ->
            for (y in 0 until 80) {
                rows.write(0)
                for (x in 0 until 120) rows.write(byteArrayOf(x.toByte(), y.toByte(), 0x45, (x * 17).toByte()))
            }
        }.toByteArray()
        val data = ByteArrayOutputStream().also { DeflaterOutputStream(it).use { compressor -> compressor.write(pixels) } }.toByteArray()
        return byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10) + chunk("IHDR", header) + chunk("IDAT", data) + chunk("IEND", byteArrayOf())
    }

    private fun pixels(png: ByteArray): ByteArray {
        val input = DataInputStream(png.inputStream())
        input.skipBytes(8)
        while (input.available() > 0) {
            val length = input.readInt()
            val type = ByteArray(4).also(input::readFully).toString(Charsets.US_ASCII)
            val payload = ByteArray(length).also(input::readFully)
            input.readInt()
            if (type == "IDAT") return InflaterInputStream(payload.inputStream()).use { it.readBytes() }
        }
        error("Missing pixels in test fixture")
    }

    private fun encoded(png: ByteArray = image(), region: ScreenshotTemplateRegion = origin, fragmented: Boolean = false): ByteArray =
        ByteArrayOutputStream().also { output ->
            val writer = ScreenshotTemplatePng.withRegion(output, region)
            if (fragmented) png.forEach { writer.write(it.toInt()) } else writer.write(png)
            writer.flush()
        }.toByteArray()

    private fun read(bytes: ByteArray) = ScreenshotTemplatePng.read(bytes.inputStream())

    @Test fun actualPngRetainsEveryPixelAndOriginalScreenNotCropLocalCoordinates() {
        val original = image()
        val saved = encoded(original)
        assertEquals(origin, read(saved))
        assertEquals("149,407,269,487", requireNotNull(read(saved)).regionText())
        assertArrayEquals(pixels(original), pixels(saved))
        assertArrayEquals(original.copyOfRange(0, 33), saved.copyOfRange(0, 33))
        val hintSize = saved.size - original.size
        assertArrayEquals(original.copyOfRange(33, original.size), saved.copyOfRange(33 + hintSize, saved.size))
        assertTrue(hintSize < 160)
    }

    @Test fun arbitraryEncoderWriteBoundariesDoNotDuplicateOrDropBytes() {
        assertArrayEquals(encoded(), encoded(fragmented = true))
        val png = image()
        for (split in listOf(0, 1, 8, 32, 33, 34, png.size)) {
            val output = ByteArrayOutputStream()
            val writer = ScreenshotTemplatePng.withRegion(output, origin)
            writer.write(png, 0, split); writer.write(png, split, png.size - split)
            assertArrayEquals(encoded(png), output.toByteArray())
        }
    }

    @Test fun importedOldAndReencodedProcessedImagesDoNotInventScreenProvenance() {
        assertNull(read(image()))
        assertNull(read(byteArrayOf(1, 2, 3)))
        // A normal bitmap encoder writes no hint unless explicitly wrapped.
        assertNull(read(image()))
    }

    private fun withHint(text: String, width: Int = 120, height: Int = 80, duplicate: Boolean = false): ByteArray {
        val png = image()
        val header = png.copyOfRange(0, 33)
        val fields = java.nio.ByteBuffer.wrap(header)
        fields.putInt(16, width); fields.putInt(20, height)
        fields.putInt(29, CRC32().apply { update(header, 12, 17) }.value.toInt())
        val chunk = ByteArrayOutputStream().also { bytes ->
            val payload = ("Zhigou.TemplateRegion\u0000$text").toByteArray(Charsets.US_ASCII)
            DataOutputStream(bytes).apply {
                writeInt(payload.size); writeBytes("tEXt"); write(payload)
                writeInt(CRC32().apply { update("tEXt".toByteArray()); update(payload) }.value.toInt())
            }
        }.toByteArray()
        return header + chunk + (if (duplicate) chunk else byteArrayOf()) + png.copyOfRange(33, png.size)
    }

    @Test fun invalidVersionGeometryIntegersDuplicatesAndDimensionsAreIgnored() {
        for (text in listOf("2,720,1280,149,407,269,487", "1,720,1280,-1,407,119,487",
            "1,720,1280,149,407,149,487", "1,720,1280,149,1240,269,1320", "1,720,1280,a,407,269,487",
            "1,2147483648,1280,149,407,269,487", "1,720,1280,149,407,269,487,0", "x".repeat(300))) {
            assertNull(text, read(withHint(text)))
        }
        assertNull(read(withHint("1,720,1280,149,407,269,487", width = 121)))
        assertNull(read(withHint("1,720,1280,149,407,269,487", duplicate = true)))
        assertThrows(IllegalArgumentException::class.java) { encoded(region = origin.copy(frameWidth = 100)) }
        assertThrows(IllegalArgumentException::class.java) { encoded(region = origin.copy(roi = ImageToolCodeGen.Roi(0, 0, 30, 40))) }
    }

    @Test fun corruptedCrcAndTruncationNeverYieldAHint() {
        val png = encoded()
        val textLength = DataInputStream(png.copyOfRange(33, 37).inputStream()).readInt()
        val bad = png.copyOf().apply { this[41 + textLength] = (this[41 + textLength].toInt() xor 1).toByte() }
        assertNull(read(bad))
        for (length in listOf(0, 8, 32, 33, 41, 41 + textLength)) assertNull(read(png.copyOf(length)))
    }

    @Test fun readerDoesNotScanPixelsOrUnboundedAncillaryData() {
        val png = encoded()
        val dataOffset = 33 + (png.size - image().size)
        var bytesRead = 0
        val input = object : java.io.FilterInputStream(png.inputStream()) {
            override fun read(): Int { bytesRead++; return super.read() }
            override fun read(b: ByteArray, off: Int, len: Int): Int = `in`.read(b, off, len).also { if (it > 0) bytesRead += it }
        }
        assertEquals(origin, ScreenshotTemplatePng.read(input))
        assertEquals(dataOffset + 8, bytesRead)
        val oversized = png.copyOfRange(0, 33) + byteArrayOf(0x7f, -1, -1, -1) + "tEXt".toByteArray()
        assertNull(read(oversized))
    }

    @Test fun multipleTemplatesUseUnionOnlyForCompleteSameFrameHints() {
        val second = origin.copy(roi = ImageToolCodeGen.Roi(10, 20, 30, 60))
        assertEquals(ImageToolCodeGen.Roi(10, 20, 269, 487), combinedTemplateRegion(listOf(origin, second))?.roi)
        assertNull(combinedTemplateRegion(listOf(origin, null)))
        assertNull(combinedTemplateRegion(listOf(origin, second.copy(frameWidth = 1280, frameHeight = 720))))
        assertNull(combinedTemplateRegion(emptyList()))
        assertNull(combinedTemplateRegion(List(65) { origin }))
    }

    @Test fun defaultsNeverOverwriteManualOrVariableRegionsOrOtherRecognitionParameters() {
        val initial = mapOf("region" to "0,0,720,1280", "similarityPermille" to "950", "foundVariable" to "结果")
        val changed = recognitionTemplateRegionInputs(initial, origin, true)
        assertEquals(origin.regionText(), changed["region"])
        assertEquals("950", changed["similarityPermille"])
        assertEquals("结果", changed["foundVariable"])
        assertEquals(initial, recognitionTemplateRegionInputs(initial, origin, false))
        assertEquals(initial, recognitionTemplateRegionInputs(initial, null, true))
        val dynamic = initial + ("leftVariable" to "范围左")
        assertEquals(dynamic, recognitionTemplateRegionInputs(dynamic, origin, true))
        assertEquals(emptyMap<String, String>(), recognitionTemplateRegionInputs(emptyMap(), origin, true))
    }
}

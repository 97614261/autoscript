package com.autoscript.studio

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.CRC32

/** Optional original-screen coordinates, never coordinates of the cropped bitmap. */
internal data class ScreenshotTemplateRegion(
    val frameWidth: Int,
    val frameHeight: Int,
    val roi: ImageToolCodeGen.Roi,
) {
    fun valid(): Boolean = frameWidth in 1..16384 && frameHeight in 1..16384 &&
        roi.left >= 0 && roi.top >= 0 && roi.right > roi.left && roi.bottom > roi.top &&
        roi.right <= frameWidth && roi.bottom <= frameHeight &&
        roi.width <= 4096 && roi.height <= 4096 && roi.width.toLong() * roi.height <= 4_194_304

    fun regionText(): String = "${roi.left},${roi.top},${roi.right},${roi.bottom}"
}

/** PNG ancillary metadata only; the image decoder and runtime need no special handling. */
internal object ScreenshotTemplatePng {
    private val signature = byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10)
    private val textType = "tEXt".toByteArray(Charsets.US_ASCII)
    private const val KEY = "Zhigou.TemplateRegion"
    private const val MAX_HEADER_BYTES = 65_536

    fun withRegion(output: OutputStream, region: ScreenshotTemplateRegion): OutputStream {
        require(region.valid()) { "模板原屏范围无效" }
        val payload = ("$KEY\u00001,${region.frameWidth},${region.frameHeight},${region.regionText()}")
            .toByteArray(Charsets.US_ASCII)
        val chunk = ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).apply {
                writeInt(payload.size); write(textType); write(payload)
                writeInt(crc(textType, payload))
            }
        }.toByteArray()
        // Bitmap.compress writes a normal IHDR first. Buffer only those 33 bytes,
        // then stream every pixel-data byte directly to the original output.
        return object : OutputStream() {
            val header = ByteArray(33)
            var used = 0
            override fun write(value: Int) = write(byteArrayOf(value.toByte()), 0, 1)
            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                require(offset >= 0 && length >= 0 && offset <= bytes.size - length)
                val prefix = minOf(header.size - used, length)
                if (prefix > 0) {
                    bytes.copyInto(header, used, offset, offset + prefix)
                    used += prefix
                    if (used == header.size) {
                        val dimensions = readHeader(DataInputStream(header.inputStream()))
                        require(dimensions == (region.roi.width to region.roi.height)) { "模板尺寸与原屏范围不一致" }
                        output.write(header)
                        output.write(chunk)
                    }
                }
                if (length > prefix) output.write(bytes, offset + prefix, length - prefix)
            }
            override fun flush() = output.flush()
        }
    }

    /** Read a bounded prefix, stopping before image data. Invalid hints never block old images. */
    fun read(input: InputStream): ScreenshotTemplateRegion? = try {
        val data = DataInputStream(input)
        val dimensions = readHeader(data)
        var remaining = MAX_HEADER_BYTES - 33
        var result: ScreenshotTemplateRegion? = null
        var finished = false
        repeat(64) {
            if (!finished) {
                require(remaining >= 12)
                val length = data.readInt()
                val type = ByteArray(4).also(data::readFully)
                val name = type.toString(Charsets.US_ASCII)
                if (name == "IDAT" || name == "IEND") {
                    finished = true
                } else {
                    require(length in 0..(remaining - 12))
                    val bytes = ByteArray(length).also(data::readFully)
                    val checksum = data.readInt()
                    remaining -= length + 12
                    if (name == "tEXt" && bytes.take(KEY.length + 1).toByteArray()
                            .contentEquals((KEY + '\u0000').toByteArray(Charsets.US_ASCII))) {
                        require(result == null && length <= 256 && checksum == crc(type, bytes))
                        require(bytes.all { it.toInt() in 0..127 })
                        val values = bytes.toString(Charsets.US_ASCII).substring(KEY.length + 1).split(',')
                        require(values.size == 7 && values.first() == "1")
                        val numbers = values.drop(1).map { requireNotNull(it.toIntOrNull()) }
                        val region = ScreenshotTemplateRegion(numbers[0], numbers[1],
                            ImageToolCodeGen.Roi(numbers[2], numbers[3], numbers[4], numbers[5]))
                        require(region.valid() && dimensions == (region.roi.width to region.roi.height))
                        result = region
                    }
                }
            }
        }
        if (finished) result else null
    } catch (_: IOException) { null } catch (_: IllegalArgumentException) { null }

    private fun readHeader(data: DataInputStream): Pair<Int, Int> {
        require(ByteArray(8).also(data::readFully).contentEquals(signature))
        require(data.readInt() == 13)
        val type = ByteArray(4).also(data::readFully)
        require(type.toString(Charsets.US_ASCII) == "IHDR")
        val bytes = ByteArray(13).also(data::readFully)
        require(data.readInt() == crc(type, bytes))
        val fields = DataInputStream(bytes.inputStream())
        return fields.readInt() to fields.readInt()
    }

    private fun crc(type: ByteArray, bytes: ByteArray): Int = CRC32().apply {
        update(type); update(bytes)
    }.value.toInt()
}

internal fun combinedTemplateRegion(regions: List<ScreenshotTemplateRegion?>): ScreenshotTemplateRegion? {
    if (regions.size !in 1..64 || regions.any { it == null || !it.valid() }) return null
    val known = regions.filterNotNull()
    val first = known.first()
    if (known.any { it.frameWidth != first.frameWidth || it.frameHeight != first.frameHeight }) return null
    // The union is a search rectangle, not a new template (so its area may exceed a template's budget).
    return first.copy(roi = ImageToolCodeGen.Roi(known.minOf { it.roi.left }, known.minOf { it.roi.top },
        known.maxOf { it.roi.right }, known.maxOf { it.roi.bottom }))
}

internal fun recognitionTemplateRegionInputs(
    inputs: Map<String, String>, region: ScreenshotTemplateRegion?, followTemplate: Boolean,
): Map<String, String> = if (!followTemplate || region == null || "region" !in inputs ||
    listOf("leftVariable", "topVariable", "rightVariable", "bottomVariable").any { !inputs[it].isNullOrBlank() }) inputs
    else inputs + ("region" to region.regionText())

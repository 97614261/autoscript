package com.autoscript.studio

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CodingErrorAction
import kotlin.math.abs
import com.autoscript.project.store.ProjectGlyphColor

/** Studio authoring only; recognition stays in Rust. Bits use canonical MSB-first ASGLYPH v1. */
internal data class MakerGlyph(val label: String, val width: Int, val height: Int, val bits: ByteArray) {
    fun foreground(x: Int, y: Int): Boolean {
        val bit = y * width + x
        return bits[bit / 8].toInt() and (0x80 ushr (bit % 8)) != 0
    }
    fun sameShape(other: MakerGlyph) = width == other.width && height == other.height && bits.contentEquals(other.bits)
}

internal object GlyphDictionaryMaker {
    const val MAX_SAMPLES = 256
    const val MAX_BITMAP_BYTES = 2 * 1024 * 1024
    const val MAX_IMAGE_PIXELS = 4_194_304
    private val magic = byteArrayOf(65, 83, 71, 76, 89, 80, 72, 0)

    fun validLabel(label: String) = label.isNotBlank() && label.toByteArray(Charsets.UTF_8).size <= 32 &&
        label.none { Character.isISOControl(it) } && hasValidSurrogates(label)

    private fun hasValidSurrogates(text: String): Boolean {
        var index = 0
        while (index < text.length) {
            val c = text[index++]
            if (Character.isHighSurrogate(c)) {
                if (index >= text.length || !Character.isLowSurrogate(text[index++])) return false
            } else if (Character.isLowSurrogate(c)) return false
        }
        return true
    }

    private fun validate(glyph: MakerGlyph, requireLabel: Boolean = true) {
        require(!requireLabel || validLabel(glyph.label)) { "每个样本都需标注字符（UTF-8最多32字节，无空白/控制字符）" }
        require(glyph.width in 1..256 && glyph.height in 1..256) { "单个字形尺寸须为1–256px，请缩小选区" }
        val count = glyph.width * glyph.height
        require(glyph.bits.size == (count + 7) / 8) { "字形位图长度无效" }
        val padding = glyph.bits.size * 8 - count
        require(padding == 0 || glyph.bits.last().toInt() and ((1 shl padding) - 1) == 0) { "字形填充位无效" }
        require(glyph.bits.any { it != 0.toByte() }) { "字形为空，请调整颜色或选区" }
    }

    fun validateSamples(glyphs: List<MakerGlyph>, requireLabels: Boolean = true) {
        require(glyphs.isNotEmpty() && glyphs.size <= MAX_SAMPLES) { "字库需包含1–256个样本" }
        require(glyphs.sumOf { it.bits.size.toLong() } <= MAX_BITMAP_BYTES) { "字形位图超过2MiB制作预算" }
        glyphs.forEach { validate(it, requireLabels) }
        if (requireLabels) glyphs.forEachIndexed { index, glyph ->
            require(glyphs.take(index).none { it.label != glyph.label && it.sameShape(glyph) }) {
                "相同字形标注了不同字符，请删除或修正歧义样本"
            }
        }
    }

    fun encode(glyphs: List<MakerGlyph>): ByteArray {
        validateSamples(glyphs)
        val labels = glyphs.map { it.label.toByteArray(Charsets.UTF_8) }
        val size = 16 + glyphs.indices.sumOf { 12 + labels[it].size + glyphs[it].bits.size }
        val buffer = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(magic).putShort(1).putShort(0).putInt(glyphs.size)
        glyphs.forEachIndexed { index, glyph ->
            buffer.putShort(labels[index].size.toShort()).putShort(glyph.width.toShort())
                .putShort(glyph.height.toShort()).putShort(0).putInt(glyph.bits.size)
                .put(labels[index]).put(glyph.bits)
        }
        return buffer.array()
    }

    fun decode(bytes: ByteArray): List<MakerGlyph> {
        require(bytes.size in 16..(MAX_BITMAP_BYTES + MAX_SAMPLES * 44 + 16)) { "字库为空或超过制作预算" }
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(ByteArray(8).also { buffer.get(it) }.contentEquals(magic)) { "不是ASGLYPH字库" }
        require(buffer.short.toInt() == 1 && buffer.short.toInt() == 0) { "字库版本或保留字段无效" }
        val count = buffer.int
        require(count in 1..MAX_SAMPLES) { "制作窗支持最多256个样本，原文件未改动" }
        val glyphs = ArrayList<MakerGlyph>(count)
        repeat(count) {
            require(buffer.remaining() >= 12) { "字库不完整" }
            val labelSize = buffer.short.toInt() and 65535
            val width = buffer.short.toInt() and 65535
            val height = buffer.short.toInt() and 65535
            require(buffer.short.toInt() == 0) { "字库保留字段无效" }
            val length = buffer.int
            require(labelSize in 1..32 && width in 1..256 && height in 1..256 &&
                length == (width * height + 7) / 8 && buffer.remaining() >= labelSize + length) { "字形结构无效" }
            val labelBytes = ByteArray(labelSize).also { buffer.get(it) }
            val label = runCatching { Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(labelBytes)).toString() }
                .getOrElse { throw IllegalArgumentException("字库字符不是有效UTF-8") }
            glyphs += MakerGlyph(label, width, height, ByteArray(length).also { buffer.get(it) })
        }
        require(!buffer.hasRemaining()) { "字库含多余数据" }
        validateSamples(glyphs)
        return glyphs
    }

    fun assign(glyphs: List<MakerGlyph>, labels: String): List<MakerGlyph> {
        val units = labels.codePoints().toArray().map { String(Character.toChars(it)) }
        require(units.size == glyphs.size && units.all(::validLabel)) { "标注字符数必须与样本数相同，按从上到下、从左到右填写" }
        return glyphs.mapIndexed { index, glyph -> glyph.copy(label = units[index]) }
    }

    /** Preserve order and variants; a duplicate is not an edit or an extra undo step. */
    fun append(existing: List<MakerGlyph>, pending: List<MakerGlyph>, labels: String): List<MakerGlyph> {
        val merged = assign(pending, labels).fold(existing) { list, glyph ->
            if (list.any { it.label == glyph.label && it.sameShape(glyph) }) list else list + glyph
        }
        validateSamples(merged)
        return merged
    }

    /** Offline sampling mirrors runtime color membership and row/column segmentation, not matching. */
    fun sample(pixels: IntArray, width: Int, height: Int, left: Int, top: Int, right: Int, bottom: Int,
        rgb: Int, tolerance: Int, batch: Boolean, checkpoint: () -> Unit = {}): List<MakerGlyph> {
        return sample(pixels, width, height, left, top, right, bottom, listOf(ProjectGlyphColor(rgb, tolerance)), batch, checkpoint)
    }
    fun sample(pixels: IntArray, width: Int, height: Int, left: Int, top: Int, right: Int, bottom: Int,
        colors: List<ProjectGlyphColor>, batch: Boolean, checkpoint: () -> Unit = {}): List<MakerGlyph> {
        val mask = foregroundMask(pixels, width, height, left, top, right, bottom, colors, checkpoint)
        val w = right - left; val h = bottom - top
        return splitMask(mask, w, h, batch, checkpoint)
    }

    /** Preview and extraction share exactly the same alpha, color and comparison budget. */
    fun foregroundMask(pixels: IntArray, width: Int, height: Int, left: Int, top: Int, right: Int, bottom: Int,
        colors: List<ProjectGlyphColor>, checkpoint: () -> Unit = {}): BooleanArray {
        require(width in 1..4096 && height in 1..4096 && width.toLong() * height <= MAX_IMAGE_PIXELS &&
            pixels.size == width * height) { "图片超过4096边长/400万像素预算" }
        require(left in 0 until width && top in 0 until height && right > left && right <= width && bottom > top && bottom <= height &&
            colors.size in 1..ProjectGlyphColor.MAX_COLORS && colors.map { it.rgb }.distinct().size == colors.size) { "范围、颜色或容差无效" }
        val w = right - left; val h = bottom - top
        require(w.toLong()*h*colors.size <= ProjectGlyphColor.MAX_COMPARISONS) { "超过6400万次颜色比较预算，请缩小选区或减少颜色" }
        val mask = BooleanArray(w * h)
        for (y in 0 until h) {
            checkpoint()
            for (x in 0 until w) {
                val color = pixels[(top + y) * width + left + x]
                mask[y * w + x] = color ushr 24 == 255 && colors.any { rule ->
                    abs((color ushr 16 and 255) - (rule.rgb ushr 16 and 255)) <= rule.tolerance &&
                    abs((color ushr 8 and 255) - (rule.rgb ushr 8 and 255)) <= rule.tolerance &&
                    abs((color and 255) - (rule.rgb and 255)) <= rule.tolerance
                }
            }
        }
        return mask
    }

    private fun splitMask(mask: BooleanArray, w: Int, h: Int, batch: Boolean, checkpoint: () -> Unit): List<MakerGlyph> {
        val lines = runs(h) { y -> (0 until w).any { x -> mask[y * w + x] } }
        require(lines.isNotEmpty()) { "选区没有目标颜色，请取色或调整容差" }
        require(batch || lines.size == 1) { "单字取样含多个分段，请重新框选；多字请用批量切分" }
        val output = ArrayList<MakerGlyph>()
        val regions = if (batch) lines else listOf(lines.first().first to lines.last().second)
        for ((y0, y1) in regions) {
            checkpoint()
            val columns = runs(w) { x -> (y0 until y1).any { y -> mask[y * w + x] } }
            require(batch || columns.size == 1) { "单字取样含多个字符或噪点，请缩小选区或使用批量切分" }
            val segments = if (batch) columns else listOf(columns.first().first to columns.last().second)
            for ((x0, x1) in segments) {
                checkpoint()
                val gw = x1 - x0; val gh = y1 - y0
                require(gw in 1..256 && gh in 1..256) { "字形超过256px，请分别框选或缩小范围" }
                require(output.size < MAX_SAMPLES) { "一次切分最多256个样本，请缩小范围" }
                val bits = ByteArray((gw * gh + 7) / 8)
                for (y in 0 until gh) for (x in 0 until gw) if (mask[(y0 + y) * w + x0 + x]) {
                    val index = y * gw + x
                    bits[index / 8] = (bits[index / 8].toInt() or (0x80 ushr (index % 8))).toByte()
                }
                output += MakerGlyph("", gw, gh, bits)
            }
        }
        validateSamples(output, requireLabels = false)
        return output
    }

    private fun runs(length: Int, occupied: (Int) -> Boolean): List<Pair<Int, Int>> {
        val result = ArrayList<Pair<Int, Int>>()
        var start = -1
        for (i in 0 until length) if (occupied(i)) { if (start < 0) start = i }
        else if (start >= 0) { result += start to i; start = -1 }
        if (start >= 0) result += start to length
        return result
    }
}

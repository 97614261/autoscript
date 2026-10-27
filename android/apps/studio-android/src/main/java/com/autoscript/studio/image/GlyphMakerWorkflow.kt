package com.autoscript.studio

import kotlin.math.min
import kotlin.math.abs

/** Immutable-by-convention sample lists: masks are replaced, never modified in place. */
internal data class GlyphMakerVersion(val id: Long, val samples: List<MakerGlyph>, val selected: Int)

/** The current version, undo and redo together stay within the packed-mask budget. */
internal class GlyphMakerHistory {
    private val before = ArrayDeque<GlyphMakerVersion>()
    private val after = ArrayDeque<GlyphMakerVersion>()
    private var nextId = 0L
    var current = GlyphMakerVersion(nextId, emptyList(), 0)
        private set
    val canUndo get() = before.isNotEmpty()
    val canRedo get() = after.isNotEmpty()
    val retainedStates get() = before.size + after.size + 1
    val retainedBytes get() = (before.toList() + current + after.toList()).sumOf { version ->
        version.samples.sumOf { it.bits.size.toLong() }
    }

    fun push(samples: List<MakerGlyph>, selected: Int): GlyphMakerVersion {
        if (samples.isNotEmpty()) GlyphDictionaryMaker.validateSamples(samples)
        if (sameSamples(current.samples, samples)) {
            current = current.copy(selected = selected.coerceIn(0, (samples.size - 1).coerceAtLeast(0)))
            return current
        }
        before.addLast(current.copy(selected = selected.coerceIn(0, (current.samples.size - 1).coerceAtLeast(0))))
        after.clear()
        current = version(samples, selected)
        while (before.isNotEmpty() && (retainedStates > MAX_STATES || retainedBytes > MAX_BYTES)) before.removeFirst()
        return current
    }

    fun reset(samples: List<MakerGlyph>): GlyphMakerVersion {
        if (samples.isNotEmpty()) GlyphDictionaryMaker.validateSamples(samples)
        before.clear(); after.clear(); current = version(samples, 0)
        return current
    }

    fun undo(selected: Int): GlyphMakerVersion {
        if (before.isNotEmpty()) {
            after.addLast(current.copy(selected = selected.coerceIn(0, (current.samples.size - 1).coerceAtLeast(0))))
            current = before.removeLast()
        }
        return current
    }

    fun redo(selected: Int): GlyphMakerVersion {
        if (after.isNotEmpty()) {
            before.addLast(current.copy(selected = selected.coerceIn(0, (current.samples.size - 1).coerceAtLeast(0))))
            current = after.removeLast()
        }
        return current
    }

    private fun version(samples: List<MakerGlyph>, selected: Int) = GlyphMakerVersion(++nextId, samples.toList(),
        selected.coerceIn(0, (samples.size - 1).coerceAtLeast(0)))

    companion object {
        const val MAX_STATES = 13 // Current plus at most 12 edits.
        const val MAX_BYTES = 8L * 1024 * 1024
        private fun sameSamples(a: List<MakerGlyph>, b: List<MakerGlyph>) = a.size == b.size &&
            a.indices.all { a[it].label == b[it].label && a[it].sameShape(b[it]) }
    }
}

/** Display-only enlargement, leaving the bitmap, glyph sizes and original-pixel ROI untouched. */
internal fun glyphMakerFocus(viewport: ImageToolViewport, region: ImageToolCodeGen.Roi? = null): ImageToolViewport {
    val roi = region ?: ImageToolCodeGen.Roi(0, 0, viewport.imageWidth, viewport.imageHeight)
    require(roi.left >= 0 && roi.top >= 0 && roi.right <= viewport.imageWidth && roi.bottom <= viewport.imageHeight &&
        roi.width > 0 && roi.height > 0)
    val scale = min(viewport.viewWidth * .90f / roi.width, viewport.viewHeight * .90f / roi.height)
        .coerceAtMost(ImageToolViewport.MAX_SCALE)
    return viewport.copy(scale = scale,
        translateX = viewport.viewWidth / 2 - (roi.left + roi.width / 2f) * scale,
        translateY = viewport.viewHeight / 2 - (roi.top + roi.height / 2f) * scale).clamped()
}

internal fun glyphMakerZoom(viewport: ImageToolViewport, factor: Float): ImageToolViewport {
    require(factor.isFinite() && factor > 0)
    val centerX = viewport.viewWidth / 2; val centerY = viewport.viewHeight / 2
    val nextScale = (viewport.scale * factor).coerceIn(viewport.fitScale * .25f, ImageToolViewport.MAX_SCALE)
    return viewport.copy(scale = nextScale,
        translateX = centerX - (centerX - viewport.translateX) / viewport.scale * nextScale,
        translateY = centerY - (centerY - viewport.translateY) / viewport.scale * nextScale).clamped()
}

internal fun glyphMakerLabelUnits(labels: String): List<String> = labels.codePoints().toArray().map { String(Character.toChars(it)) }
internal fun glyphMakerLabelsReady(count: Int, labels: String): Boolean = glyphMakerLabelUnits(labels).let {
    count > 0 && it.size == count && it.all(GlyphDictionaryMaker::validLabel)
}
internal fun glyphMakerMissingDigits(samples: List<MakerGlyph>): String = ('0'..'9').filter { digit ->
    samples.none { it.label == digit.toString() }
}.joinToString("")

internal enum class GlyphMakerAlphabet(val title: String, val characters: String) {
    DIGITS("数字", "0123456789"),
    UPPER("大写", "ABCDEFGHIJKLMNOPQRSTUVWXYZ"),
    LOWER("小写", "abcdefghijklmnopqrstuvwxyz"),
}

internal fun glyphMakerMissing(samples: List<MakerGlyph>, characters: String): String {
    val available = samples.map { it.label }.toSet()
    return glyphMakerLabelUnits(characters).distinct().filter { it.isNotBlank() && it !in available }.joinToString("")
}

/** Scoring only: expected text never selects samples or changes the recognizer. */
internal fun glyphMakerExpectedFeedback(expected: String, actual: String?, samples: List<MakerGlyph>): String {
    if (actual == null) return "未获得有效文字，不能核对预期"
    if (actual == expected) return "预期核对：完全一致"
    val wanted = glyphMakerLabelUnits(expected)
    val observed = glyphMakerLabelUnits(actual)
    val first = (0 until maxOf(wanted.size, observed.size)).first { wanted.getOrNull(it) != observed.getOrNull(it) }
    val missing = glyphMakerMissing(samples, expected).take(64)
    return "预期${wanted.size}字 / 实际${observed.size}字 · 首处差异第${first + 1}字" +
        if (missing.isNotEmpty()) "\n尚未采样：$missing；请从实际图片补样并核对标注" else "\n请检查该字形、同行高度及色组；同字符可补不同字号/明暗样本"
}

/** Float division matters when an original 256px glyph is shown in a 34px cell. */
internal fun glyphMakerMaskScale(width: Int, height: Int, viewWidth: Int, viewHeight: Int): Float =
    min(viewWidth.toFloat() / width, viewHeight.toFloat() / height)

internal enum class GlyphMakerBrush { AUTO, INK, ERASE, MOVE }

/** One pointer stroke owns one small mask, not a history entry for every motion event. */
internal class GlyphMakerStroke(private val original: MakerGlyph, brush: GlyphMakerBrush, x: Int, y: Int) {
    private val bits = original.bits.copyOf()
    private var lastX = x
    private var lastY = y
    private val ink: Boolean

    init {
        require(brush != GlyphMakerBrush.MOVE)
        GlyphDictionaryMaker.validateSamples(listOf(original))
        require(x in 0 until original.width && y in 0 until original.height)
        ink = when (brush) {
            GlyphMakerBrush.INK -> true
            GlyphMakerBrush.ERASE -> false
            else -> !original.foreground(x, y)
        }
        mark(x, y)
    }

    fun drawTo(x: Int, y: Int, onPixel: (Int, Int, Boolean) -> Unit = { _, _, _ -> }) {
        require(x in 0 until original.width && y in 0 until original.height)
        var px = lastX; var py = lastY
        val dx = abs(x - px); val dy = -abs(y - py)
        val sx = if (px < x) 1 else -1; val sy = if (py < y) 1 else -1
        var error = dx + dy
        while (true) {
            mark(px, py); onPixel(px, py, ink)
            if (px == x && py == y) break
            val twice = error * 2
            if (twice >= dy) { error += dy; px += sx }
            if (twice <= dx) { error += dx; py += sy }
        }
        lastX = x; lastY = y
    }

    fun result(): MakerGlyph = original.copy(bits = bits.copyOf())

    private fun mark(x: Int, y: Int) {
        val index = y * original.width + x
        val mask = 0x80 ushr (index % 8)
        bits[index / 8] = (if (ink) bits[index / 8].toInt() or mask else bits[index / 8].toInt() and mask.inv()).toByte()
    }
}

/** Replace exactly one Unicode label without shifting any other glyph's label. */
internal fun glyphMakerReplaceLabel(labels: String, index: Int, replacement: String): String {
    val units = glyphMakerLabelUnits(labels).toMutableList()
    require(index in units.indices && glyphMakerLabelsReady(1, replacement)) { "单个字形请填写一个有效字符" }
    units[index] = replacement
    return units.joinToString("")
}

internal fun glyphMakerSampleIndices(samples: List<MakerGlyph>, query: String): List<Int> =
    samples.indices.filter { query.isEmpty() || samples[it].label.contains(query) }

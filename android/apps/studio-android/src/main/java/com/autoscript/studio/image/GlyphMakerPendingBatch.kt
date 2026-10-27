package com.autoscript.studio

/** Labels stay attached to samples even when an earlier item is still unlabelled. */
internal data class GlyphMakerPendingBatch(val samples: List<MakerGlyph> = emptyList()) {
    init {
        if (samples.isNotEmpty()) GlyphDictionaryMaker.validateSamples(samples, requireLabels = false)
        require(samples.all { it.label.isEmpty() || glyphMakerLabelsReady(1, it.label) })
    }

    val labelledCount get() = samples.count { it.label.isNotEmpty() }
    val ready get() = samples.isNotEmpty() && labelledCount == samples.size
    val text get() = samples.joinToString("") { it.label }

    fun label(index: Int, character: String): GlyphMakerPendingBatch {
        require(index in samples.indices && glyphMakerLabelsReady(1, character)) { "请填写一个有效字符，不能含空白" }
        return copy(samples = samples.mapIndexed { i, glyph -> if (i == index) glyph.copy(label = character) else glyph })
    }

    fun labelAll(characters: String) = copy(samples = GlyphDictionaryMaker.assign(samples, characters))

    fun remove(index: Int): GlyphMakerPendingBatch {
        require(index in samples.indices) { "待加入字形已变化，请重新选择" }
        return copy(samples = samples.filterIndexed { i, _ -> i != index })
    }

    fun nextUnlabelled(after: Int): Int {
        if (samples.isEmpty()) return -1
        require(after in -1..samples.lastIndex)
        return (1..samples.size).map { (after + it) % samples.size }.firstOrNull { samples[it].label.isEmpty() } ?: -1
    }
}

internal enum class GlyphMakerCharacterSet(val title: String, val characters: String) {
    DIGITS("数字", "0123456789"),
    NUMERIC("数字符号", "0123456789()+-./%"),
    UPPER("大写", "ABCDEFGHIJKLMNOPQRSTUVWXYZ"),
    LOWER("小写", "abcdefghijklmnopqrstuvwxyz"),
    SYMBOLS("符号", "()+-./%:[]=<>"),
    CUSTOM("自定义", ""),
}

internal fun glyphMakerTargetCharacters(text: String): String {
    val units = glyphMakerLabelUnits(text)
    require(units.size in 1..128 && units.all { glyphMakerLabelsReady(1, it) }) { "目标需为1–128个字符，不能含空白" }
    return units.distinct().joinToString("")
}

/** Shortcuts are explicit sequences, never inferred from the picture or its sample count. */
internal fun glyphMakerLabelShortcuts(count: Int, target: String, samples: List<MakerGlyph>): List<Pair<String, String>> {
    val choices = listOf("填0–9" to GlyphMakerAlphabet.DIGITS.characters, "填1–9" to "123456789",
        "填大写" to GlyphMakerAlphabet.UPPER.characters, "填小写" to GlyphMakerAlphabet.LOWER.characters,
        "填0–9/A–Z/a–z" to GlyphMakerAlphabet.entries.joinToString("") { it.characters }) +
        listOf("填目标顺序" to target, "填目标缺字" to glyphMakerMissing(samples, target))
    return choices.filter { glyphMakerLabelsReady(count, it.second) }.distinctBy { it.second }
}

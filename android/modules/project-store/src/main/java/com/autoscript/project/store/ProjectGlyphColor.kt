package com.autoscript.project.store

/** Explicit recognition settings, never embedded in ASGLYPH glyph mask files. */
data class ProjectGlyphColor(val rgb: Int, val tolerance: Int) {
    init { require(rgb in 0..0xFFFFFF && tolerance in 0..255) { "颜色或容差无效" } }
    companion object {
        const val MAX_COLORS = 16
        const val MAX_ADDITIONAL_LENGTH = 164
        const val MAX_COMPARISONS = 64_000_000L
        fun decodeAdditional(value: String, primary: Int? = null): List<ProjectGlyphColor> {
            require(value.length <= MAX_ADDITIONAL_LENGTH) { "最多16种颜色" }
            if (value.isEmpty()) return emptyList()
            val entries = value.split(';')
            require(entries.size < MAX_COLORS) { "最多16种颜色" }
            val result = entries.map {
                require(it.matches(Regex("[0-9A-Fa-f]{6}:[0-9]{1,3}"))) { "附加色格式应为RRGGBB:容差，以分号分隔" }
                ProjectGlyphColor(it.substringBefore(':').toInt(16), it.substringAfter(':').toInt())
            }
            require(result.map { it.rgb }.distinct().size == result.size && result.none { it.rgb == primary }) { "颜色不能重复" }
            return result
        }
        fun encodeAdditional(colors: List<ProjectGlyphColor>): String {
            require(colors.size < MAX_COLORS && colors.map { it.rgb }.distinct().size == colors.size)
            return colors.joinToString(";") { "%06X:%d".format(java.util.Locale.ROOT, it.rgb, it.tolerance) }
        }
    }
}

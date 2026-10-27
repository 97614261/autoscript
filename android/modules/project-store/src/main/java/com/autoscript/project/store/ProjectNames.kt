package com.autoscript.project.store

/** User keys, not generated IDs or executable Lua identifiers. Keep in sync with flow-ir. */
object ProjectNames {
    const val START = "A-Za-z_\u3400-\u4DBF\u4E00-\u9FFF"
    const val PART = "${START}0-9"
    val pattern = Regex("[$START][$PART]{0,63}")
    fun valid(value: String): Boolean = pattern.matches(value)
    fun isStart(value: Char): Boolean = value in 'A'..'Z' || value in 'a'..'z' || value == '_' ||
        value in '\u3400'..'\u4DBF' || value in '\u4E00'..'\u9FFF'
    fun isPart(value: Char): Boolean = isStart(value) || value in '0'..'9'
}

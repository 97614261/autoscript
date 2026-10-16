package com.autoscript.script.ui

/** Decimal syntax is shared by persisted defaults, configuration and runtime writes. */
fun uiDecimal(value: String, minimum: Long? = null, maximum: Long? = null): Double {
    require((minimum ?: -1_000_000_000) in -1_000_000_000..1_000_000_000 && (maximum ?: 1_000_000_000) in -1_000_000_000..1_000_000_000) { "小数范围超出预算" }
    require(value.length <= 64 && Regex("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?").matches(value)) { "小数格式无效" }
    val number = value.toDoubleOrNull() ?: error("小数格式无效")
    require(number.isFinite() && number >= (minimum ?: -1_000_000_000).toDouble() && number <= (maximum ?: 1_000_000_000).toDouble()) { "小数超出范围" }
    return number
}

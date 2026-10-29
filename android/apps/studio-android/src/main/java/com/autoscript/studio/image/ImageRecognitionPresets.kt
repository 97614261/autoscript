package com.autoscript.studio

internal fun recognitionLimitRange(kind: String): IntRange = when (kind) {
    "vision.countcolor" -> 0..64_000_000
    "vision.findallcolor" -> 1..2048
    "vision.findallimages", "vision.resultaction" -> 1..64
    else -> 1..256
}

internal fun recognitionValuePresets(field: String, kind: String = ""): List<String> = when (field) {
    "tolerance" -> listOf("0", "8", "16", "32", "64")
    "similarityPermille" -> listOf("800", "900", "950", "990", "1000")
    "minimumConfidencePermille" -> listOf("300", "500", "700", "900", "950")
    "frequency" -> listOf("1", "2", "5", "10", "30")
    "limit" -> when (kind) {
        "vision.countcolor" -> listOf("0", "100", "1000", "10000", "100000", "1000000")
        "vision.findallcolor" -> listOf("64", "256", "512", "1024", "2048")
        "vision.components", "vision.findallmulticolor" -> listOf("16", "64", "128", "256")
        else -> listOf("1", "8", "16", "32", "64")
    }
    else -> emptyList()
}

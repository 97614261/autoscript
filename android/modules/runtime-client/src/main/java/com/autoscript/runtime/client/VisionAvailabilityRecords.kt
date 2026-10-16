package com.autoscript.runtime.client

/** Only fixed families and bounded values; duplicate/unknown records invalidate the snapshot. */
internal fun parseVisionAvailabilityRecords(records: Array<String>): Map<String, String> {
    if (records.size != 2 || records.any { it.length !in 1..144 }) return emptyMap()
    val result = linkedMapOf<String, String>()
    for (record in records) {
        val parts = record.split('=', limit = 2)
        if (parts.size != 2 || parts[0] !in setOf("opencv", "onnx") || parts[1].isBlank() || result.put(parts[0], parts[1]) != null) return emptyMap()
    }
    return result
}

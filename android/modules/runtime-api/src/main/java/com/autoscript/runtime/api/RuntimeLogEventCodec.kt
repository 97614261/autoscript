package com.autoscript.runtime.api

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/** File-only compression; the JNI observation protocol and all numeric units remain unchanged. */
object RuntimeLogEventCodec {
    const val MAX_CONDITIONS = 256
    const val CONDITION_BYTES = 262_144
    private val arrays = linkedMapOf(
        "a" to ("captureStagesUs" to listOf("acquisition", "transferVerified", "framePublish", "systemCapture", "softwareReadback", "pixelRead", "pixelEncode", "bridgeWrite", "bridgeReceive")),
        "x" to ("captureTransferUs" to listOf("sendEncode", "sendWrite", "receiveRead", "verify", "append", "packets")),
        "m" to ("matchStagesUs" to listOf("setup", "prefilterBuild", "searchCompare")),
        "w" to ("matchWork" to listOf("templatesVisited", "preparedCacheHits", "templatesPrepared", "prefiltersBuilt", "candidatesVisited", "prefilterRejected", "comparisonPixels", "prefilterWork")),
    )
    private val variable = setOf("elapsedUs", "status", "receivedAtElapsedMs") + arrays.values.map { it.first }
    data class Encoded(val sample: String, val condition: String? = null)

    class Encoder {
        private val conditions = linkedMapOf<String, Int>()
        private var bytes = 0

        fun encode(text: String): Encoded = runCatching {
            val original = JsonParser.parseString(text).asJsonObject
            if (original.get("kind")?.asString != "vision" || original.get("version")?.asInt != 1) return Encoded(text)
            val fixed = JsonObject()
            original.entrySet().sortedBy { it.key }.filter { it.key !in variable }.forEach { fixed.add(it.key, it.value) }
            val sample = JsonObject().apply {
                addProperty("v", 2)
                add("s", requireNotNull(original.get("status")))
                add("u", requireNotNull(original.get("elapsedUs")))
                original.get("receivedAtElapsedMs")?.let { add("t", it) }
            }
            arrays.forEach { (short, spec) ->
                original.get(spec.first)?.let { value ->
                    val objectValue = value.asJsonObject
                    require(objectValue.keySet().all { it in spec.second })
                    // Preserve absent versus explicitly null fields with a bounded presence bitmap.
                    var mask = 0
                    val packed = JsonArray()
                    spec.second.forEachIndexed { index, name ->
                        if (objectValue.has(name)) { mask = mask or (1 shl index); packed.add(objectValue.get(name)) }
                    }
                    packed.add(mask)
                    sample.add(short, packed)
                }
            }
            val key = fixed.toString()
            val existing = conditions[key]
            val id = existing ?: conditions.size
            val definition = JsonObject().apply {
                addProperty("v", 2); addProperty("kind", "condition"); addProperty("id", id); add("data", fixed)
            }.toString()
            val size = definition.toByteArray(Charsets.UTF_8).size + 1
            if (existing == null && (size > 8_192 || conditions.size >= MAX_CONDITIONS || bytes + size > CONDITION_BYTES)) return Encoded(text)
            sample.addProperty("c", id)
            if (existing == null) { conditions[key] = id; bytes += size }
            Encoded(sample.toString(), if (existing == null) definition else null)
        }.getOrElse { Encoded(text) }
    }

    class Decoder {
        private val conditions = mutableMapOf<Int, JsonObject>()
        private val invalid = mutableSetOf<Int>()
        private var bytes = 0

        /** Null is a valid condition definition, not a sample. Invalid references throw. */
        fun expand(text: String): String? {
            require(text.toByteArray(Charsets.UTF_8).size <= 8_192)
            val record = JsonParser.parseString(text).asJsonObject
            if (record.has("version") || !record.has("v")) return text
            require(integer(record.get("v")) == 2)
            if (record.get("kind")?.asString == "condition") {
                require(record.keySet() == setOf("v", "kind", "id", "data"))
                val id = integer(record.get("id"))
                val data = record.getAsJsonObject("data")
                require(id in 0 until MAX_CONDITIONS && data.get("version")?.asInt == 1 && data.get("kind")?.asString == "vision")
                require(data.keySet().none { it in variable })
                val previous = conditions[id]
                if (previous != null && previous != data) { conditions.remove(id); invalid.add(id) }
                require(id !in invalid)
                if (previous == null) {
                    bytes += text.toByteArray(Charsets.UTF_8).size + 1
                    require(bytes <= CONDITION_BYTES)
                    conditions[id] = data.deepCopy()
                }
                return null
            }
            require(record.keySet().all { it in setOf("v", "c", "s", "u", "t") || it in arrays })
            val result = requireNotNull(conditions[integer(record.get("c"))]).deepCopy()
            result.add("status", requireNotNull(record.get("s")))
            result.add("elapsedUs", requireNotNull(record.get("u")))
            record.get("t")?.let { result.add("receivedAtElapsedMs", it) }
            arrays.forEach { (short, spec) ->
                record.get(short)?.let { value ->
                    val packed = value.asJsonArray
                    require(packed.size() in 1..spec.second.size + 1)
                    val mask = integer(packed[packed.size() - 1])
                    require(mask in 0 until (1 shl spec.second.size) && Integer.bitCount(mask) == packed.size() - 1)
                    val expanded = JsonObject()
                    var position = 0
                    spec.second.forEachIndexed { index, name ->
                        if (mask and (1 shl index) != 0) expanded.add(name, packed[position++])
                    }
                    result.add(spec.first, expanded)
                }
            }
            return result.toString()
        }

        private fun integer(value: JsonElement?): Int {
            val primitive = requireNotNull(value).asJsonPrimitive
            require(primitive.isNumber && primitive.asString.matches(Regex("0|[1-9][0-9]{0,9}")))
            return primitive.asString.toInt()
        }
    }

    /** Counts all accepted observations before detail rotation, not each derived metric row. */
    internal fun summary(text: String): Pair<String, Long>? = runCatching {
        val event = JsonParser.parseString(text).asJsonObject
        if (event.get("kind")?.asString == "dropped") {
            return "visionEngineDropped" to event.get("count").asLong.coerceIn(0, 1_000_000_000)
        }
        if (event.get("version")?.asInt != 1 || event.get("kind")?.asString != "vision") return null
        val elapsed = event.get("elapsedUs").asLong
        require(elapsed in 0..3_600_000_000L)
        val algorithm = event.get("algorithm").asString
        if ((algorithm.startsWith("capture-") && algorithm != "capture-host-roundtrip") || algorithm.startsWith("match-")) return null
        val status = event.get("status").asString
        val key = when {
            algorithm == "capture-host-roundtrip" && status == "completed" -> "visionCaptures"
            status == "found" -> "visionFound"
            status == "not_found" -> "visionNotFound"
            status in setOf("error", "failed", "timeout", "budget_exceeded") -> "visionFailed"
            status == "cancelled" -> "visionCancelled"
            else -> return null
        }
        key to 1L
    }.getOrNull()

    fun preview(lines: String): String {
        val decoder = Decoder()
        return lines.lineSequence().filter(String::isNotBlank).mapNotNull { line ->
            runCatching {
                decoder.expand(line)?.let { expanded ->
                    val event = JsonParser.parseString(expanded).asJsonObject
                    if (event.get("kind")?.asString != "vision") expanded else {
                        val algorithm = event.get("algorithm")?.asString
                        val label = when (algorithm) {
                            "capture-host-roundtrip" -> "截图"
                            "rust-rgba-first" -> "找图"
                            "rust-rgba-single" -> "单图"
                            "rust-rgba-all" -> "多图"
                            "opencv-gray-host-roundtrip" -> "灰度找图"
                            "onnx-ocr-host-roundtrip" -> "文字识别"
                            else -> algorithm
                        }
                        val status = event.get("status")?.asString
                        val state = when (status) {
                            "completed" -> "完成"; "found" -> "找到"; "not_found" -> "未找到"
                            "error", "failed" -> "失败"; "timeout" -> "超时"; "cancelled" -> "取消"
                            "budget_exceeded" -> "预算超限"; else -> status
                        }
                        val time = String.format(java.util.Locale.ROOT, "%.2f", event.get("elapsedUs").asLong / 1000.0)
                        "${event.get("receivedAtElapsedMs")?.asLong ?: "?"}ms · $label $state · ${time}ms · ${event.get("nodeId")?.asString?.take(12) ?: "-"}"
                    }
                }
            }.getOrElse { "无效观测记录" }
        }.joinToString("\n")
    }
}

package com.autoscript.studio

import com.google.gson.JsonParser
import com.autoscript.runtime.api.RuntimeLogEventCodec
import kotlin.math.ceil

/** Same algorithm, geometry, threshold and outcome only; nearest-rank, no mixed averages. */
internal object VisionRunStatistics {
    fun displayAlgorithm(algorithm: String): String = when (algorithm) {
        "capture-host-roundtrip" -> "截图总耗时（宿主往返）"
        "capture-acquisition" -> "截图采集（含桥读取）"
        "capture-transferVerified" -> "帧传输 / 鉴权"
        "capture-framePublish" -> "帧入池"
        "rust-rgba-first" -> "Rust 找图 · 首结果"
        "rust-rgba-single" -> "Rust 找图 · 单模板"
        "rust-rgba-all" -> "Rust 找图 · 多结果"
        else -> algorithm
    }
    data class Group(val algorithm: String, val configuration: String, val status: String,
        val count: Int, val p50Ms: Double, val p95Ms: Double, val p99Ms: Double,
        val parameters: Map<String, String> = emptyMap(), val unit: String = "ms")
    data class Report(val groups: List<Group>, val dropped: Long, val malformed: Int, val traceCount: Int)

    fun parse(lines: String): Report {
        val samples = linkedMapOf<Triple<String, String, String>, MutableList<Long>>()
        val configurations = mutableMapOf<String, Map<String, String>>()
        var dropped = 0L; var malformed = 0; var trace = 0
        val decoder = RuntimeLogEventCodec.Decoder()
        lines.lineSequence().filter(String::isNotBlank).forEach { line ->
            runCatching {
                require(line.length <= 8_192)
                val expanded = decoder.expand(line) ?: return@forEach
                val event = JsonParser.parseString(expanded).asJsonObject
                when (event.get("kind")?.asString) {
                    "trace" -> trace++
                    "dropped" -> dropped += event.get("count").asLong.coerceIn(0, 1_000_000_000)
                    "vision" -> {
                        require(event.get("version")?.asInt == 1)
                        val elapsed = event.get("elapsedUs").asLong
                        require(elapsed in 0..3_600_000_000L)
                        val fields = listOf("flowId", "nodeId", "frame", "roi", "templateCount", "templateSizes", "templateFingerprintVersion", "templateFingerprints", "tolerance", "similarity", "direction", "limit", "comparisonBudget", "timeoutMs", "captureBackend", "captureTransport")
                        val configuration = fields
                            .joinToString(" · ") { "$it=${event.get(it) ?: "-"}" }
                        val key = Triple(event.get("algorithm").asString, configuration, event.get("status").asString)
                        val stages = event.getAsJsonObject("captureStagesUs")
                        val values = if (key.first == "capture-host-roundtrip" && key.third == "completed" && stages != null) {
                            listOf("acquisition", "transferVerified", "framePublish").map { name ->
                                val value = requireNotNull(stages.get(name)).asLong
                                require(value in 0..3_600_000_000L)
                                name to value
                            } + listOf("systemCapture", "softwareReadback", "pixelRead", "pixelEncode", "bridgeWrite", "bridgeReceive").mapNotNull { name ->
                                stages.get(name)?.takeUnless { it.isJsonNull }?.let {
                                    val value = it.asLong
                                    require(value in 0..10_000_000L)
                                    name to value
                                }
                            }
                        } else emptyList()
                        val transfer = event.getAsJsonObject("captureTransferUs")
                        val transferValues = if (key.first == "capture-host-roundtrip" && key.third == "completed" && transfer != null) {
                            val packets = requireNotNull(transfer.get("packets")).asLong
                            require(packets in 1..1_093)
                            listOf("sendEncode", "sendWrite", "receiveRead", "verify", "append").map { name ->
                                val value = requireNotNull(transfer.get(name)).asLong
                                require(value in 0..10_000_000L)
                                "capture-transfer-$name" to value
                            } + ("capture-transfer-packets" to packets)
                        } else emptyList()
                        val matchStages = event.getAsJsonObject("matchStagesUs")
                        val matchWork = event.getAsJsonObject("matchWork")
                        val matchValues = if (key.first in listOf("rust-rgba-first", "rust-rgba-single", "rust-rgba-all") && (matchStages != null || matchWork != null)) {
                            requireNotNull(matchStages); requireNotNull(matchWork)
                            val visited = requireNotNull(matchWork.get("candidatesVisited")).asLong
                            val rejected = requireNotNull(matchWork.get("prefilterRejected")).asLong
                            require(visited in 0..1_000_000_000_000L && rejected in 0..visited)
                            listOf("setup", "prefilterBuild", "searchCompare").map { name ->
                                val value = requireNotNull(matchStages.get(name)).asLong
                                require(value in 0..3_600_000_000L)
                                "match-$name" to value
                            } + listOf("templatesVisited", "preparedCacheHits", "templatesPrepared", "prefiltersBuilt", "candidatesVisited", "prefilterRejected", "comparisonPixels", "prefilterWork").map { name ->
                                val value = requireNotNull(matchWork.get(name)).asLong
                                require(value in 0..1_000_000_000_000L)
                                "match-work-$name" to value
                            } + if (visited == 0L) emptyList() else listOf("match-work-prefilterRejectPpm" to rejected * 1_000_000L / visited)
                        } else emptyList()
                        configurations[configuration] = fields.mapNotNull { name ->
                            event.get(name)?.takeUnless { it.isJsonNull }?.let { value ->
                                name to if (value.isJsonPrimitive && value.asJsonPrimitive.isString) value.asString else value.toString()
                            }
                        }.toMap()
                        samples.getOrPut(key) { mutableListOf() }.add(elapsed)
                        values.forEach { (name, value) ->
                            samples.getOrPut(Triple("capture-$name", configuration, key.third)) { mutableListOf() }.add(value)
                        }
                        transferValues.forEach { (name, value) -> samples.getOrPut(Triple(name, configuration, key.third)) { mutableListOf() }.add(value) }
                        matchValues.forEach { (name, value) -> samples.getOrPut(Triple(name, configuration, key.third)) { mutableListOf() }.add(value) }
                    }
                }
            }.onFailure { malformed++ }
        }
        val groups = samples.map { (key, times) ->
            val sorted = times.sorted()
            val work = key.first.startsWith("match-work-") || key.first == "capture-transfer-packets"
            val ratio = key.first == "match-work-prefilterRejectPpm"
            fun percentile(p: Double) = sorted[(ceil(sorted.size * p).toInt() - 1).coerceIn(0, sorted.lastIndex)] / if (ratio) 10_000.0 else if (work) 1.0 else 1_000.0
            Group(key.first, key.second, key.third, sorted.size, percentile(.5), percentile(.95), percentile(.99), configurations[key.second].orEmpty(), if (ratio) "%" else if (work) "count" else "ms")
        }
        return Report(groups, dropped, malformed, trace)
    }
}

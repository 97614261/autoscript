package com.autoscript.studio

import com.google.gson.JsonParser

/** Project-scoped numeric high-water marks. Undo/cancel never returns an allocated identity. */
internal class VisualProjectIds(sources: Collection<String> = emptyList(), roots: Collection<String> = emptyList()) {
    private val highWater = mutableMapOf("node" to 0L, "block" to 0L)

    init { reserveSources(sources); reserveIds(roots) }

    @Synchronized fun reserveIds(ids: Iterable<String>) {
        ids.forEach { id ->
            val match = NUMERIC.matchEntire(id) ?: return@forEach
            val value = match.groupValues[2].toLongOrNull() ?: return@forEach
            val prefix = match.groupValues[1]
            highWater[prefix] = maxOf(highWater.getValue(prefix), value)
        }
    }

    @Synchronized fun reserveSource(source: String) {
        source.lineSequence().filter(String::isNotBlank).forEach { line ->
            // Invalid/read-only sources still require the full compiler gate. Never repair them here.
            val node = runCatching { JsonParser.parseString(line).asJsonObject }.getOrNull() ?: return@forEach
            val ids = mutableListOf<String>()
            for (key in listOf("nodeId", "blockId")) node[key]?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
                ?.asString?.let(ids::add)
            node["childBlocks"]?.takeIf { it.isJsonObject }?.asJsonObject?.entrySet()?.forEach { (_, value) ->
                value.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString?.let(ids::add)
            }
            reserveIds(ids)
        }
    }

    fun reserveSources(sources: Collection<String>) { sources.forEach(::reserveSource) }

    @Synchronized fun allocate(prefix: String, localIds: Set<String>): String {
        require(prefix in highWater) { "不支持的可视化身份类型" }
        reserveIds(localIds)
        val previous = highWater.getValue(prefix)
        require(previous < Int.MAX_VALUE.toLong()) { "$prefix 数字 ID 已达到上限" }
        val next = previous + 1
        highWater[prefix] = next
        return "$prefix-$next"
    }

    private companion object { val NUMERIC = Regex("^(node|block)-([0-9]+)$") }
}

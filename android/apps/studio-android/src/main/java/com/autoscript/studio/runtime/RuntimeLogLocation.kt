package com.autoscript.studio

import com.autoscript.runtime.api.RuntimeLogEventCodec
import com.google.gson.JsonParser

/** Structural identities only. Never interpret script output or Lua stack text as navigation. */
internal data class RuntimeLogLocation(val flowId: String, val nodeId: String) {
    companion object {
        private val identity = Regex("[A-Za-z0-9_.-]{1,128}")
        fun from(parameters: Map<String, String>): RuntimeLogLocation? {
            val flow = parameters["flowId"] ?: return null
            val node = parameters["nodeId"] ?: return null
            return if (identity.matches(flow) && identity.matches(node)) RuntimeLogLocation(flow, node) else null
        }

        /** Conditions may precede samples; a malformed record must not give a false location. */
        fun failures(events: String): List<RuntimeLogLocation> {
            val decoder = RuntimeLogEventCodec.Decoder()
            val recent = linkedSetOf<RuntimeLogLocation>()
            events.lineSequence().filter(String::isNotBlank).forEach { line ->
                runCatching {
                    val expanded = decoder.expand(line) ?: return@runCatching
                    val record = JsonParser.parseString(expanded).asJsonObject
                    if (record.get("version")?.asInt != 1 || record.get("kind")?.asString !in setOf("task", "vision") ||
                        record.get("status")?.asString !in setOf("failed", "error", "timeout", "budget_exceeded")) return@runCatching
                    val fields = listOf("flowId", "nodeId").associateWith { key ->
                        val value = record.get(key)?.asJsonPrimitive ?: return@runCatching
                        if (!value.isString) return@runCatching
                        value.asString
                    }
                    from(fields)?.let { location ->
                        recent.remove(location); recent.add(location)
                        if (recent.size > 16) recent.remove(recent.first())
                    }
                }
            }
            return recent.toList().asReversed()
        }
    }
}

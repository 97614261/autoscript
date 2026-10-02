package com.autoscript.studio

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive

/** Typed editor-only hints. Existing pipe-separated jump hints remain readable. */
internal object JumpPanelCode {
    private val namePattern = Regex("[A-Za-z_][A-Za-z0-9_]{0,63}")
    private val channelKinds = setOf("flow.argument.set", "flow.return.set")
    private val readKinds = setOf("flow.argument.get", "flow.return.get")

    fun validName(name: String): Boolean = namePattern.matches(name)

    fun nextLabel(existing: Collection<String>): String = (1..100_000)
        .asSequence().map { "mark_$it" }.first { it !in existing }

    fun fixedValue(text: String): JsonElement? {
        if (text.isBlank() || text.toByteArray(Charsets.UTF_8).size > 2_048) return null
        val parsed = runCatching { JsonParser.parseString(text) }.getOrNull() ?: return JsonPrimitive(text)
        if (!parsed.isJsonPrimitive) return null
        val value = parsed.asJsonPrimitive
        if (value.isNumber && !runCatching { value.asDouble.isFinite() }.getOrDefault(false)) return null
        return value
    }

    fun callSnippet(targetFlowId: String): String? = targetFlowId.takeIf(String::isNotBlank)?.let {
        hint("flow.call", JsonObject().apply { addProperty("targetFlowId", it) })
    }

    fun callTargetId(snippet: String): String? {
        if (!snippet.startsWith(FunctionCatalog.BLOCK_HINT_PREFIX + "flow.call\n")) return null
        return snippet.lineSequence().drop(1).firstOrNull()?.let { encoded ->
            runCatching { JsonParser.parseString(encoded).asJsonObject.get("targetFlowId")?.asString }.getOrNull()
        }
    }

    fun setSnippet(kind: String, index: Int, text: String, variable: String?): String? {
        if (kind !in channelKinds || index !in 1..99) return null
        val args = JsonObject().apply { addProperty("index", index) }
        if (variable != null) {
            if (!validName(variable)) return null
            args.addProperty("valueVariable", variable)
        } else {
            args.add("value", fixedValue(text) ?: return null)
        }
        return hint(kind, args)
    }

    fun getSnippet(kind: String, index: Int, targetVariable: String): String? {
        if (kind !in readKinds || index !in 1..99 || !validName(targetVariable)) return null
        return hint(kind, JsonObject().apply {
            addProperty("index", index)
            addProperty("targetVariable", targetVariable)
        })
    }

    private fun hint(kind: String, arguments: JsonObject): String =
        "${FunctionCatalog.BLOCK_HINT_PREFIX}$kind\n$arguments\n"
}

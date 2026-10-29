package com.autoscript.studio

import com.google.gson.JsonObject
import com.google.gson.JsonParser

internal const val CONDITION_CONFIG_HINT = "--@autoscript-condition:"

internal data class ConfiguredConditionInsertion(val arguments: JsonObject, val elseIf: Boolean)

/** A new condition carries only its comparison/tree, never existing branch structure. */
internal fun validConfiguredConditionArguments(arguments: JsonObject): Boolean = runCatching {
    require(arguments.keySet().all { it in setOf("variable", "operator", "value", "valueVariable", "condition") })
    val comparison = arguments.deepCopy().apply {
        remove("condition")
        addProperty("type", "compare")
    }
    require(parseCompoundCondition(comparison.toString()) != null)
    require(!arguments.has("condition") || parseCompoundCondition(arguments["condition"].toString()) != null)
    true
}.getOrDefault(false)

internal fun configuredConditionHint(arguments: JsonObject, elseIf: Boolean = false): String {
    require(validConfiguredConditionArguments(arguments))
    val payload = JsonObject().apply {
        addProperty("operation", if (elseIf) "elseIf" else "if")
        add("arguments", arguments.deepCopy())
    }
    return "$CONDITION_CONFIG_HINT$payload\n"
}

internal fun configuredConditionInsertion(hint: String): ConfiguredConditionInsertion? = runCatching {
    require(hint.startsWith(CONDITION_CONFIG_HINT))
    require(hint.toByteArray(Charsets.UTF_8).size <= 17408)
    val payload = JsonParser.parseString(hint.removePrefix(CONDITION_CONFIG_HINT)).asJsonObject
    require(payload.keySet() == setOf("operation", "arguments"))
    val operation = payload["operation"].asJsonPrimitive
    require(operation.isString && operation.asString in setOf("if", "elseIf"))
    val arguments = payload["arguments"].asJsonObject
    require(validConfiguredConditionArguments(arguments))
    ConfiguredConditionInsertion(arguments.deepCopy(), operation.asString == "elseIf")
}.getOrNull()

internal fun isConditionBranchInsertion(hint: String): Boolean =
    configuredConditionInsertion(hint)?.elseIf == true ||
        hint.lineSequence().firstOrNull()?.trim() in setOf("--@autoscript-control:else", "--@autoscript-control:elseIf")

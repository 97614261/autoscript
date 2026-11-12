package com.autoscript.runtime.api

import com.google.gson.JsonObject
import com.google.gson.JsonParser

data class RuntimeBranchOutcome(val flowId: String, val nodeId: String, val slot: String) {
    init {
        RuntimeNodePosition(flowId, nodeId)
        require(slot in setOf("success", "failure", "then", "else") || slot.matches(Regex("elseIf[0-9]{1,3}")))
    }
}

/** Bounded structural evidence for an editor, independent of user-facing log filters. */
data class RuntimeExecutionFeedback(val sessionGeneration: Long, val runRevision: Long, val projectId: String,
    val sequence: Long, val phase: String, val position: RuntimeNodePosition?, val failure: RuntimeNodePosition?,
    val branches: List<RuntimeBranchOutcome>, val incomplete: Boolean = false) {
    init {
        require(sessionGeneration != 0L && runRevision > 0 && sequence > 0)
        RuntimeNodePosition(projectId, projectId)
        require(phase in setOf("running", "paused", "ended", "stopped", "failed", "stopping"))
        require(branches.size <= 32)
    }

    fun encode(): String = JsonObject().apply {
        addProperty("sequence", sequence); addProperty("phase", phase); addProperty("incomplete", incomplete)
        fun point(value: RuntimeNodePosition?) = value?.let { JsonObject().apply {
            addProperty("flowId", it.flowId); addProperty("nodeId", it.nodeId)
        } }
        point(position)?.let { add("position", it) }; point(failure)?.let { add("failure", it) }
        add("branches", com.google.gson.JsonArray().apply { branches.forEach { value -> add(JsonObject().apply {
            addProperty("flowId", value.flowId); addProperty("nodeId", value.nodeId); addProperty("slot", value.slot)
        }) } })
    }.toString()

    companion object {
        fun decode(generation: Long, revision: Long, project: String, payload: String): RuntimeExecutionFeedback? = runCatching {
            require(payload.toByteArray(Charsets.UTF_8).size <= 32_768)
            val data = JsonParser.parseString(payload).asJsonObject
            fun point(key: String): RuntimeNodePosition? = data.getAsJsonObject(key)?.let {
                RuntimeNodePosition(it.feedbackText("flowId"), it.feedbackText("nodeId"))
            }
            val rows = data.getAsJsonArray("branches").also { require(it.size() <= 32) }
            val sequence = data.get("sequence").asJsonPrimitive.also { require(it.isNumber) }.asString.toLong()
            val incomplete = data.get("incomplete").asJsonPrimitive.also { require(it.isBoolean) }.asBoolean
            RuntimeExecutionFeedback(generation, revision, project, sequence, data.feedbackText("phase"),
                point("position"), point("failure"), rows.map { row -> row.asJsonObject.let {
                    RuntimeBranchOutcome(it.feedbackText("flowId"), it.feedbackText("nodeId"), it.feedbackText("slot"))
                } }, incomplete)
        }.getOrNull()
    }
}

/** One frozen run only. Retain latest branch per owner, never variables, paths or error text. */
class RuntimeExecutionFeedbackBuffer {
    private var position: RuntimeNodePosition? = null
    private var failure: RuntimeNodePosition? = null
    private val branches = linkedMapOf<RuntimeNodePosition, RuntimeBranchOutcome>()
    private var incomplete = false
    private var sequence = 0L

    fun observe(payload: String): Boolean = runCatching {
        require(payload.toByteArray(Charsets.UTF_8).size <= 8_192)
        val event = JsonParser.parseString(payload).asJsonObject
        require(event.get("version").asInt == 1)
        val kind = event.feedbackText("kind")
        if (kind == "dropped") {
            if ((event.get("eventCount")?.asLong ?: event.get("count").asLong) > 0) incomplete = true
            return true
        }
        if (kind !in setOf("step", "trace", "task", "branch")) return false
        val point = RuntimeNodePosition(event.feedbackText("flowId"), event.feedbackText("nodeId"))
        when {
            kind == "branch" -> {
                val outcome = RuntimeBranchOutcome(point.flowId, point.nodeId, event.feedbackText("slot"))
                branches.remove(point); branches[point] = outcome
                position = point
                if (branches.size > 32) branches.remove(branches.keys.first())
            }
            kind == "task" && event.get("status")?.asString == "failed" -> failure = point
            kind in setOf("step", "trace") && event.get("phase")?.asString == "enter" -> {
                position = point
                branches.remove(point)
            }
            else -> return false
        }
        true
    }.getOrDefault(false)

    fun snapshot(generation: Long, revision: Long, project: String, phase: String): RuntimeExecutionFeedback =
        RuntimeExecutionFeedback(generation, revision, project, ++sequence, phase, position, failure, branches.values.toList(), incomplete)
}

private fun JsonObject.feedbackText(key: String): String = get(key).asJsonPrimitive.also { require(it.isString) }.asString

package com.autoscript.runtime.api

/** Structural UI observation only; no source, variable values or resource paths. */
data class RuntimeExecutionPosition(val sessionGeneration: Long, val runRevision: Long,
    val projectId: String, val flowId: String, val nodeId: String,
    val sampleSequence: Long = 0, val recent: List<RuntimeNodePosition> = emptyList()) {
    init {
        // Service generations are opaque nonzero signed random identities, not counters.
        require(sessionGeneration != 0L && runRevision > 0)
        require(sampleSequence >= 0 && recent.size <= 32)
        require(listOf(projectId, flowId, nodeId).all { it.toByteArray(Charsets.UTF_8).size in 1..128 && it.none(Char::isISOControl) })
    }
}

data class RuntimeNodePosition(val flowId: String, val nodeId: String) {
    init { require(listOf(flowId, nodeId).all { it.toByteArray(Charsets.UTF_8).size in 1..128 && it.none(Char::isISOControl) }) }
}

/** Merge only within the same frozen run; bound pending evidence independently of callback rate. */
fun mergeExecutionPositions(previous: RuntimeExecutionPosition?, next: RuntimeExecutionPosition): RuntimeExecutionPosition {
    if (previous == null || previous.sessionGeneration != next.sessionGeneration ||
        previous.runRevision != next.runRevision || previous.projectId != next.projectId) return next
    if (previous.sampleSequence > next.sampleSequence) return previous
    val rows = ArrayList<RuntimeNodePosition>(64)
    for (row in previous.recent + next.recent) if (rows.lastOrNull() != row) rows.add(row)
    return next.copy(recent = rows.takeLast(32))
}

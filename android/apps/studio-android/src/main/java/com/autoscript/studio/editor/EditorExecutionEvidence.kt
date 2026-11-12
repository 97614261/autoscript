package com.autoscript.studio

import com.autoscript.runtime.api.RuntimeBranchOutcome
import com.autoscript.runtime.api.RuntimeExecutionFeedback

/** Once edited, evidence from that frozen run must not reappear even after undo. */
internal class EditorExecutionEvidenceLease(sources: Map<String, String>) {
    private val baseline = sources.toMap()
    private var invalidated = false
    fun accepts(sources: Map<String, String>): Boolean {
        if (sources != baseline) invalidated = true
        return !invalidated
    }
}

internal class EditorExecutionEvidenceTracker {
    private var revision = -1L
    private var lease: EditorExecutionEvidenceLease? = null
    fun accepts(run: Long, sources: Map<String, String>): Boolean {
        if (run < revision) return false
        if (run > revision) { revision = run; lease = EditorExecutionEvidenceLease(sources) }
        return lease?.accepts(sources) == true
    }
}

internal fun editorFeedbackPosition(report: RuntimeExecutionFeedback): VisualDebugPosition? {
    if (report.incomplete && report.phase in setOf("ended", "stopped")) return null
    val point = if (report.phase == "failed") report.failure else report.position
    return point?.let { VisualDebugPosition(it.flowId, it.nodeId, recent = report.phase !in setOf("running", "paused")) }
}

internal fun editorBranchOutcomeLabel(kind: String, outcome: RuntimeBranchOutcome): String = when {
    kind in setOf("vision.findimage", "vision.findgray", "vision.findallimages") ->
        if (outcome.slot == "success") "找到" else "未找到"
    outcome.slot == "success" -> "成功"
    outcome.slot == "failure" -> "失败"
    else -> compactProgramBranchLabel(childBlockLabel(outcome.slot)) ?: childBlockLabel(outcome.slot)
}

internal data class EditorBranchExecution(val ownerId: String, val slot: String, val label: String) {
    val key: String get() = editorBranchKey(ownerId, slot)
}

/** Outcomes arrive prefiltered to the displayed plugin; validate against its complete tree. */
internal fun editorBranchExecutions(nodes: List<EditorTreeNode>, outcomes: List<RuntimeBranchOutcome>): List<EditorBranchExecution> {
    val owners = nodes.associateBy { it.id }
    if (owners.size != nodes.size) return emptyList()
    val results = linkedMapOf<String, EditorBranchExecution>()
    outcomes.forEach { outcome ->
        val owner = owners[outcome.nodeId] ?: return@forEach
        if (outcome.slot !in editorBranchSlots(owner.code, owner.childSlots)) return@forEach
        results.remove(owner.id)
        results[owner.id] = EditorBranchExecution(owner.id, outcome.slot, editorBranchOutcomeLabel(owner.code, outcome))
    }
    return results.values.toList()
}

internal fun editorFeedbackBranch(report: RuntimeExecutionFeedback?, position: VisualDebugPosition?): RuntimeBranchOutcome? {
    if (report == null || position == null || report.phase in setOf("failed", "stopping") ||
        report.position?.let { it.flowId != position.flowId || it.nodeId != position.nodeId } != false) return null
    return report.branches.lastOrNull()?.takeIf { it.flowId == position.flowId && it.nodeId == position.nodeId }
}

internal data class EditorExecutionTarget(val nodeId: String, val slot: String? = null) {
    val key: String get() = slot?.let { editorBranchKey(nodeId, it) } ?: "node:$nodeId"
}

internal fun editorExecutionMarker(nodes: List<EditorTreeNode>, target: EditorExecutionTarget?): EditorExecutionTarget? {
    if (target == null || nodes.map { it.id }.distinct().size != nodes.size) return null
    val owner = nodes.firstOrNull { it.id == target.nodeId } ?: return null
    return target.takeIf { it.slot == null || it.slot in editorBranchSlots(owner.code, owner.childSlots) }
}

/** Reveal a newly completed result once, then follow the next real checkpoint normally. */
internal class EditorExecutionFollowTracker {
    private var previousNode: String? = null
    private var previousResults = emptyMap<String, EditorBranchExecution>()
    private var previousLocate = 0
    private var target: EditorExecutionTarget? = null

    fun update(nodeId: String?, results: List<EditorBranchExecution>, cursor: EditorBranchExecution?,
        locateRevision: Int, failed: Boolean = false): EditorExecutionTarget? {
        val current = nodeId?.let { EditorExecutionTarget(it, cursor?.takeIf { result -> result.ownerId == it }?.slot) }
        val changed = results.lastOrNull { previousResults[it.ownerId] != it }
        val resultsByOwner = results.associateBy { it.ownerId }
        target = when {
            current == null || failed -> current
            locateRevision != previousLocate -> current
            changed != null -> EditorExecutionTarget(changed.ownerId, changed.slot)
            nodeId != previousNode -> current
            target?.slot != null && resultsByOwner[target?.nodeId]?.slot != target?.slot -> current
            else -> target ?: current
        }
        previousNode = nodeId; previousResults = resultsByOwner; previousLocate = locateRevision
        return target
    }
}

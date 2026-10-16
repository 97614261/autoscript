package com.autoscript.studio

import com.autoscript.runtime.api.RuntimeExecutionPosition
import com.autoscript.runtime.api.RuntimeNodePosition
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Main-thread display buffer, never a script scheduler or log recorder. */
internal class EditorExecutionTrail {
    private val pending = ArrayDeque<RuntimeNodePosition>()
    private var latest: RuntimeNodePosition? = null
    private var run: Pair<Long, Long>? = null
    private var sequence = -1L
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val shown = MutableStateFlow<VisualDebugPosition?>(null)
    val position = shown.asStateFlow()
    val pendingCount get() = pending.size

    fun offer(sample: RuntimeExecutionPosition) {
        val identity = sample.sessionGeneration to sample.runRevision
        if (run != identity) { clear(); run = identity }
        if (sample.sampleSequence <= sequence) return
        sequence = sample.sampleSequence
        latest = RuntimeNodePosition(sample.flowId, sample.nodeId)
        for (row in sample.recent + requireNotNull(latest)) {
            if (pending.contains(row)) continue
            if (pending.size == 32) pending.removeFirst()
            pending.addLast(row)
        }
        shown.value?.let { shown.value = it.copy(recent = RuntimeNodePosition(it.flowId, it.nodeId) != latest) }
        wake.trySend(Unit)
    }

    fun next(): VisualDebugPosition? {
        val row = if (pending.isEmpty()) latest else pending.removeFirst()
        return row?.let { VisualDebugPosition(it.flowId, it.nodeId, recent = it != latest) }.also { shown.value = it }
    }

    fun clear() {
        pending.clear(); latest = null; run = null; sequence = -1L; shown.value = null
        while (wake.tryReceive().isSuccess) Unit
    }

    suspend fun follow() {
        try {
            while (true) {
                wake.receive()
                while (pending.isNotEmpty()) { next(); delay(75) }
                next()
            }
        } finally { clear() }
    }
}

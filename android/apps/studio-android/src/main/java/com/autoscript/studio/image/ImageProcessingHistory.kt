package com.autoscript.studio

/** Bounded owned edits over one borrowed original. UI state reads on its owning thread. */
internal class ImageProcessingHistory<T : Any>(
    val original: T,
    private val bytes: (T) -> Long,
    private val release: (T) -> Unit,
    private val maxEdits: Int = 8,
    private val maxBytes: Long = 32L * 1024 * 1024,
) : AutoCloseable {
    private val edits = mutableListOf<T>()
    private var index = -1
    private var closed = false
    private var originalReachable = true
    var revision = 0L
        private set
    val current: T get() = if (index < 0) original else edits[index]
    val canUndo get() = !closed && (index > 0 || index == 0 && originalReachable)
    val canRedo get() = !closed && index + 1 < edits.size
    val editCount get() = edits.size

    init { require(maxEdits in 1..8 && maxBytes > 0 && bytes(original) in 1..maxBytes) }

    /** Reject before allocating a result; the original and result must fit without resizing. */
    fun validateResultBytes(resultBytes: Long) {
        check(!closed)
        require(resultBytes in 1..(maxBytes - bytes(original))) { "处理结果超过预览内存预算，请缩小明确的输出尺寸" }
    }

    /** Ownership is consumed even on stale completion. No borrowed original may be published. */
    fun publish(value: T, expectedRevision: Long): Boolean {
        require(value !== original && edits.none { it === value })
        if (closed || expectedRevision != revision) { release(value); return false }
        try { validateResultBytes(bytes(value)) } catch (error: Throwable) { release(value); throw error }
        while (edits.size > index + 1) release(edits.removeAt(edits.lastIndex))
        edits.add(value)
        index = edits.lastIndex
        while (edits.size > maxEdits || bytes(original) + edits.sumOf(bytes) > maxBytes) {
            release(edits.removeAt(0))
            index--
            originalReachable = false // Comparison remains available, but undo must not jump over evicted edits.
        }
        revision++
        return true
    }

    fun undo(): Boolean {
        if (!canUndo) return false
        index--; revision++
        return true
    }

    fun redo(): Boolean {
        if (!canRedo) return false
        index++; revision++
        return true
    }

    override fun close() {
        if (closed) return
        closed = true
        revision++
        edits.forEach(release)
        edits.clear(); index = -1
    }
}

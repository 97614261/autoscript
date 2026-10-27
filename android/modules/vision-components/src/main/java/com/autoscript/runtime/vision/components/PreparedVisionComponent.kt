package com.autoscript.runtime.vision.components

import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** Private owned frozen bytes; no script-supplied paths/classes. Not an IPC object. */
class PreparedVisionComponent internal constructor(
    val verified: VerifiedVisionComponent,
    val processAbi: String,
    private val directory: File,
    val apk: File,
    val nativeDirectory: File,
    val model: File?,
    val candidateModel: File?,
    private val digests: Map<File, Pair<Long, String>>,
    private val ownership: VisionSnapshotOwnership,
    private val cleanup: () -> Unit,
) : AutoCloseable {
    private var closed = false
    private var cleaned = false
    private var leaseCount = 0

    /** Keeps frozen files alive while a provider may still load native code or a model. */
    fun retain(): AutoCloseable = synchronized(this) {
        check(!closed) { "VISION_COMPONENT_CLOSED" }
        ownership.verify()
        leaseCount++
        val released = AtomicBoolean(false)
        AutoCloseable { if (released.compareAndSet(false, true)) releaseLease() }
    }

    @Synchronized fun verify(checkpoint: () -> Unit) {
        check(!closed) { "VISION_COMPONENT_CLOSED" }
        verifyFrozen(checkpoint)
    }

    /** Host loader only: recheck pinned bytes after the original prepared owner closed. */
    @Synchronized fun verifyRetained(checkpoint: () -> Unit) {
        check(!cleaned && leaseCount > 0) { "VISION_COMPONENT_LEASE_CLOSED" }
        verifyFrozen(checkpoint)
    }

    private fun verifyFrozen(checkpoint: () -> Unit) {
        ownership.verify()
        require(directory.canonicalFile == directory.absoluteFile)
        for ((file, expected) in digests) {
            checkpoint()
            require(file.canonicalFile == file.absoluteFile && file.isFile && !file.canWrite())
            require(file.path.startsWith(directory.path + File.separator))
            require(file.inputStream().use { VisionComponentValidator.hash(it, expected.first, checkpoint) } == expected)
        }
    }

    /** Only before loading, or after its owner proves no native/classloader use remains. */
    @Synchronized override fun close() {
        if (cleaned) return
        closed = true
        cleanupIfReady()
    }

    @Synchronized private fun releaseLease() {
        check(leaseCount > 0) { "VISION_COMPONENT_LEASE_MISMATCH" }
        leaseCount--
        cleanupIfReady()
    }

    private fun cleanupIfReady() {
        if (!closed || cleaned || leaseCount != 0) return
        cleanup()
        cleaned = true
    }
}

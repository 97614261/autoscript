package com.autoscript.runtime.vision.components

import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException

/** Nonblocking cross-process ownership. This empty, non-executable file is never a DEX. */
internal class VisionSnapshotOwnership private constructor(
    val file: File,
    private val handle: RandomAccessFile,
    private val lease: FileLock,
) : AutoCloseable {
    @Synchronized fun isHeld(): Boolean = lease.isValid

    @Synchronized fun verify() {
        check(lease.isValid) { "VISION_COMPONENT_LEASE_CLOSED" }
        require(file.canonicalFile == file.absoluteFile && file.isFile && file.length() == 0L)
    }

    @Synchronized override fun close() {
        try { if (lease.isValid) lease.release() } finally { handle.close() }
    }

    companion object {
        const val FILE_NAME = "owner.lock"

        fun create(snapshot: File): VisionSnapshotOwnership {
            val file = File(snapshot, FILE_NAME)
            require(file.canonicalFile == file.absoluteFile && file.createNewFile())
            return requireNotNull(acquire(file)) { "VISION_COMPONENT_BUSY" }
        }

        /** Caller holds the registry lock. A null result means another owner is still alive. */
        fun acquire(file: File): VisionSnapshotOwnership? {
            require(file.canonicalFile == file.absoluteFile && file.isFile && file.length() == 0L)
            val handle = RandomAccessFile(file, "rw")
            try {
                val lock = try { handle.channel.tryLock() } catch (_: OverlappingFileLockException) { null }
                if (lock != null) return VisionSnapshotOwnership(file, handle, lock)
                handle.close()
                return null
            } catch (error: Throwable) {
                runCatching { handle.close() }.onFailure { error.addSuppressed(it) }
                throw error
            }
        }
    }
}

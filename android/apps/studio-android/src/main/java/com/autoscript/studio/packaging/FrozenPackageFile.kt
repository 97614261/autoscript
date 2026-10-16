package com.autoscript.studio

import java.io.File
import java.security.MessageDigest

/** Verify the exact captured bytes, not mtime/length alone; bounded and cooperatively cancellable. */
internal data class FrozenPackageFile(val size: Long, val sha256: ByteArray) {
    fun matches(file: File, checkpoint: () -> Unit): Boolean {
        require(size in 0..67_108_864L && sha256.size == 32)
        checkpoint()
        if (!file.isFile || file.length() != size) return false
        val digest = MessageDigest.getInstance("SHA-256")
        var read = 0L
        file.inputStream().use { input ->
            val buffer = ByteArray(65_536)
            while (true) {
                checkpoint()
                val count = input.read(buffer)
                if (count < 0) break
                read += count
                if (read > size) return false
                digest.update(buffer, 0, count)
            }
        }
        checkpoint()
        return read == size && file.length() == size && digest.digest().contentEquals(sha256)
    }
}

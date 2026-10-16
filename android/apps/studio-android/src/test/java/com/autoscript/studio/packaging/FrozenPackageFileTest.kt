package com.autoscript.studio

import java.nio.file.Files
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test

class FrozenPackageFileTest {
    @Test fun sameSizedModificationCannotPassFreezeVerification() {
        val file = Files.createTempFile("package-freeze", ".bin").toFile()
        try {
            val content = byteArrayOf(1, 2, 3, 4)
            file.writeBytes(content)
            val identity = FrozenPackageFile(4, MessageDigest.getInstance("SHA-256").digest(content))
            assertTrue(identity.matches(file) {})
            file.writeBytes(byteArrayOf(4, 3, 2, 1))
            assertFalse(identity.matches(file) {})
            assertThrows(CancellationException::class.java) { identity.matches(file) { throw CancellationException() } }
            file.writeBytes(ByteArray(8))
            assertFalse(identity.matches(file) {})
        } finally { file.delete() }
    }
}

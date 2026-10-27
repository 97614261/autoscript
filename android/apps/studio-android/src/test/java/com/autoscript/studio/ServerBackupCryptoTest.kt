package com.autoscript.studio

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.*
import org.junit.Test

class ServerBackupCryptoTest {
    private val key = "ab".repeat(32)
    private val id = "12".repeat(16)
    private fun encrypt(bytes: ByteArray): ByteArray = ByteArrayOutputStream().also {
        ServerBackupCrypto.encrypt(bytes.inputStream(), it, key, id)
    }.toByteArray()
    private fun decrypt(bytes: ByteArray, useKey: String = key, useId: String = id): ByteArray = ByteArrayOutputStream().also {
        ServerBackupCrypto.decrypt(bytes.inputStream(), it, useKey, useId)
    }.toByteArray()
    private fun rejected(action: () -> Unit) {
        try { action(); fail("must reject") } catch (expected: Exception) { /* Expected authentication/format failure. */ }
    }
    @Test fun emptySmallAndMultipleChunkRoundtrip() {
        for (size in listOf(0, 1, 262144, 262149, 600000)) {
            val original = ByteArray(size) { (it % 251).toByte() }
            assertArrayEquals(original, decrypt(encrypt(original)))
        }
    }
    @Test fun randomNonceDoesNotRepeatEnvelope() {
        assertFalse(encrypt(byteArrayOf(1)).contentEquals(encrypt(byteArrayOf(1))))
    }
    @Test fun wrongKeyAndIdentityReject() {
        val data = encrypt(byteArrayOf(1, 2, 3))
        rejected { decrypt(data, "cd".repeat(32)) }
        rejected { decrypt(data, useId = "34".repeat(16)) }
    }
    @Test fun tamperedHeaderNonceBodyAndFinalTagReject() {
        val data = encrypt(ByteArray(400) { 7 })
        for (index in listOf(0, 8, 19, 25, data.size - 1)) {
            val changed = data.copyOf().also { it[index] = (it[index].toInt() xor 1).toByte() }
            rejected { decrypt(changed) }
        }
    }
    @Test fun truncationMissingEndAndTrailingDataReject() {
        val data = encrypt(ByteArray(100) { 3 })
        for (cut in listOf(0, 7, 17, data.size - 1, data.size - 20)) rejected { decrypt(data.copyOf(cut)) }
        rejected { decrypt(data + byteArrayOf(0)) }
    }
    @Test fun reorderedChunksReject() {
        val data = encrypt(ByteArray(2 * 262144) { 5 })
        val record = 262144 + 20
        val changed = data.copyOfRange(0, 16) + data.copyOfRange(16 + record, 16 + record * 2) +
            data.copyOfRange(16, 16 + record) + data.copyOfRange(16 + record * 2, data.size)
        rejected { decrypt(changed) }
    }
    @Test fun nameIsEncryptedAndIdentityBound() {
        val label = ServerBackupCrypto.encryptName("测试项目123", key, id)
        assertFalse(label.contains("测试"))
        assertEquals("测试项目123", ServerBackupCrypto.decryptName(label, key, id))
        rejected { ServerBackupCrypto.decryptName(label, key, "34".repeat(16)) }
        rejected { ServerBackupCrypto.decryptName(label, "cd".repeat(32), id) }
    }
    @Test fun hmacMatchesPythonWireVector() {
        assertEquals("f510c05e8b22154ebcaac5a0d1bbf8e38ebe50e80e0b0adb24b67388b9977ea6",
            ServerBackupCrypto.mac(key, "public", "fixture"))
    }
    @Test fun boundedOutputRejectsOversizeAndChecksCancellation() {
        val output = ByteArrayOutputStream()
        val limited = BackupBoundedOutput(output, 3) {}
        limited.write(byteArrayOf(1, 2)); limited.write(3)
        rejected { limited.write(4) }
        assertArrayEquals(byteArrayOf(1, 2, 3), output.toByteArray())
        rejected { BackupBoundedOutput(output, 99) { error("cancelled") }.write(byteArrayOf(1)) }
    }
    @Test fun cancellationIsCheckedBetweenChunks() {
        var calls = 0
        rejected { ServerBackupCrypto.encrypt(ByteArrayInputStream(ByteArray(300000)), ByteArrayOutputStream(), key, id) {
            if (++calls == 3) error("cancelled")
        } }
        assertEquals(3, calls)
    }
}

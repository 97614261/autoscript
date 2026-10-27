package com.autoscript.studio

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

internal object ServerBackupCrypto {
    const val MAX_ZIP = 128L * 1024 * 1024
    const val MAX_ENCRYPTED = 129L * 1024 * 1024
    private const val CHUNK = 256 * 1024
    private val magic = "ZGBK0001".toByteArray(Charsets.US_ASCII)
    private val random = SecureRandom()

    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
    fun id(): String = hex(ByteArray(16).also(random::nextBytes))
    fun validKey(key: String): Boolean = Regex("[a-f0-9]{64}").matches(key)
    fun mac(key: String, vararg parts: String): String = hex(hmac(key, (parts.joinToString("\n") + "\n").toByteArray(Charsets.US_ASCII)))
    fun sha(bytes: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(bytes))
    private fun hmac(key: String, bytes: ByteArray): ByteArray = Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(key.toByteArray(Charsets.US_ASCII), "HmacSHA256")); doFinal(bytes)
    }
    private fun aes(key: String, id: String, purpose: String) = SecretKeySpec(
        hmac(key, "zhigou-backup-aes-v1\n$purpose\n$id\n".toByteArray(Charsets.US_ASCII)), "AES")
    private fun cipher(mode: Int, key: String, id: String, purpose: String, nonce: ByteArray, index: Int, size: Int): Cipher {
        require(validKey(key) && Regex("[a-f0-9]{32}").matches(id)) { "BACKUP_CONFIG_INVALID" }
        return Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, aes(key, id, purpose), GCMParameterSpec(128, nonce))
            updateAAD("ZGBK0001\n$purpose\n$id\n$index\n$size\n".toByteArray(Charsets.US_ASCII))
        }
    }
    private fun nonce(prefix: ByteArray, index: Int) = prefix + byteArrayOf(
        (index ushr 24).toByte(), (index ushr 16).toByte(), (index ushr 8).toByte(), index.toByte())

    // Each bounded chunk is authenticated before writing. A final empty record authenticates EOF.
    fun encrypt(input: InputStream, output: OutputStream, key: String, id: String, check: () -> Unit = {}) {
        val prefix = ByteArray(8).also(random::nextBytes)
        val data = DataOutputStream(output)
        data.write(magic); data.write(prefix)
        val buffer = ByteArray(CHUNK)
        var total = 0L
        var index = 0
        while (true) {
            check()
            var size = 0
            while (size < buffer.size) {
                check()
                val count = input.read(buffer, size, buffer.size - size)
                if (count < 0) break
                require(count > 0) { "BACKUP_INCOMPLETE" }
                size += count
            }
            total += size
            require(total <= MAX_ZIP) { "BACKUP_TOO_LARGE" }
            data.writeInt(size)
            data.write(cipher(Cipher.ENCRYPT_MODE, key, id, "archive", nonce(prefix, index), index, size).doFinal(buffer, 0, size))
            index++
            if (size == 0) break
        }
        data.flush()
    }

    fun decrypt(input: InputStream, output: OutputStream, key: String, id: String, check: () -> Unit = {}) {
        val data = DataInputStream(input)
        val header = ByteArray(8).also(data::readFully)
        require(header.contentEquals(magic)) { "BACKUP_FORMAT_INVALID" }
        val prefix = ByteArray(8).also(data::readFully)
        var index = 0
        var total = 0L
        while (true) {
            check()
            val size = data.readInt()
            require(size in 0..CHUNK && index <= (MAX_ZIP / CHUNK).toInt()) { "BACKUP_FORMAT_INVALID" }
            total += size
            require(total <= MAX_ZIP) { "BACKUP_TOO_LARGE" }
            val bytes = ByteArray(size + 16).also(data::readFully)
            val plain = cipher(Cipher.DECRYPT_MODE, key, id, "archive", nonce(prefix, index), index, size).doFinal(bytes)
            index++
            if (size == 0) {
                require(data.read() == -1) { "BACKUP_TRAILING_DATA" }
                break
            }
            output.write(plain)
        }
        output.flush()
    }

    @OptIn(ExperimentalEncodingApi::class)
    fun base64(bytes: ByteArray): String = Base64.encode(bytes)
    @OptIn(ExperimentalEncodingApi::class)
    fun unbase64(text: String): ByteArray = Base64.decode(text)
    fun encryptName(name: String, key: String, id: String): String {
        val plain = name.toByteArray(Charsets.UTF_8)
        require(plain.size in 1..1024) { "BACKUP_NAME_INVALID" }
        val nonce = ByteArray(12).also(random::nextBytes)
        return base64(nonce + cipher(Cipher.ENCRYPT_MODE, key, id, "name", nonce, 0, -1).doFinal(plain))
    }
    fun decryptName(label: String, key: String, id: String): String {
        require(label.length <= 2800)
        val bytes = unbase64(label)
        require(bytes.size in 29..1052)
        return cipher(Cipher.DECRYPT_MODE, key, id, "name", bytes.copyOfRange(0, 12), 0, -1)
            .doFinal(bytes, 12, bytes.size - 12).toString(Charsets.UTF_8)
    }
}

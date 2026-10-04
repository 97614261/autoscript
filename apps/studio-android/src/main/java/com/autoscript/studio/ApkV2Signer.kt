package com.autoscript.studio

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

internal data class ApkSigningIdentity(val privateKey: PrivateKey, val certificate: X509Certificate)
internal data class ApkSignatureReport(val certificateSha256: String)

/** AOSP APK Signature Scheme v2, RSA PKCS#1/SHA-256, supported from Android 7/API24. */
internal object ApkV2Signer {
    private const val V2_ID = 0x7109871a
    private const val RSA_SHA256 = 0x0103
    private const val CHUNK = 1_048_576
    private val MAGIC = "APK Sig Block 42".toByteArray(Charsets.US_ASCII)
    private data class Layout(val directory: Long, val end: Long, val eocd: ByteArray)

    fun sign(unsigned: File, target: File, identity: ApkSigningIdentity, checkpoint: () -> Unit) {
        require(!target.exists()) { "不能覆盖已有 APK" }
        var created = false
        try {
            RandomAccessFile(unsigned, "r").use { input ->
                val layout = layout(input)
                val digest = digest(input, layout.directory, layout.directory, layout.end, layout.eocd, checkpoint)
                val digestRecord = lp(int(RSA_SHA256) + lp(digest))
                val signedData = lp(digestRecord) + lp(lp(identity.certificate.encoded)) + lp(byteArrayOf())
                checkpoint()
                val signature = Signature.getInstance("SHA256withRSA").run {
                    initSign(identity.privateKey); update(signedData); sign()
                }
                val signatureRecord = lp(int(RSA_SHA256) + lp(signature))
                val signer = lp(signedData) + lp(signatureRecord) + lp(identity.certificate.publicKey.encoded)
                val value = lp(lp(signer))
                val pair = long(4L + value.size) + int(V2_ID) + value
                val size = pair.size.toLong() + 24L
                val block = long(size) + pair + long(size) + MAGIC
                val eocd = layout.eocd.copyOf()
                putInt(eocd, 16, Math.addExact(layout.directory, block.size.toLong()).also { require(it <= 0xFFFFFFFFL) }.toInt())
                checkpoint()
                check(target.createNewFile()) { "不能覆盖已有 APK" }
                created = true
                FileOutputStream(target).use { output ->
                    copy(input, output, 0, layout.directory, checkpoint)
                    output.write(block)
                    copy(input, output, layout.directory, layout.end - layout.directory, checkpoint)
                    output.write(eocd)
                    output.flush(); output.fd.sync()
                }
            }
            verify(target, checkpoint)
        } catch (error: Throwable) {
            if (created) target.delete()
            throw error
        }
    }

    /** Recomputes protected content and verifies the certificate's public-key signature. */
    fun verify(file: File, checkpoint: () -> Unit = {}): ApkSignatureReport = RandomAccessFile(file, "r").use { input ->
        val layout = layout(input)
        require(layout.directory >= 32)
        input.seek(layout.directory - 24)
        val footer = ByteArray(24).also(input::readFully)
        require(footer.copyOfRange(8, 24).contentEquals(MAGIC)) { "缺少 APK v2 签名块" }
        val blockSize = buffer(footer).long
        require(blockSize in 24..1_048_576 && layout.directory >= blockSize + 8)
        val start = layout.directory - blockSize - 8
        input.seek(start)
        val block = ByteArray((blockSize + 8).toInt()).also(input::readFully)
        require(buffer(block).long == blockSize)
        val pairs = buffer(block.copyOfRange(8, block.size - 24))
        var v2: ByteArray? = null
        while (pairs.hasRemaining()) {
            require(pairs.remaining() >= 12)
            val size = pairs.long
            require(size in 4..pairs.remaining().toLong())
            val id = pairs.int
            val value = ByteArray((size - 4).toInt()).also(pairs::get)
            if (id == V2_ID) { require(v2 == null); v2 = value }
        }
        val outer = buffer(requireNotNull(v2))
        val signers = buffer(readLp(outer)); require(!outer.hasRemaining())
        val signer = buffer(readLp(signers)); require(!signers.hasRemaining())
        val signedData = readLp(signer)
        val signatures = buffer(readLp(signer))
        val publicKey = readLp(signer); require(!signer.hasRemaining())
        val signatureRecord = buffer(readLp(signatures)); require(!signatures.hasRemaining())
        require(signatureRecord.int == RSA_SHA256)
        val signature = readLp(signatureRecord); require(!signatureRecord.hasRemaining())
        val data = buffer(signedData)
        val digests = buffer(readLp(data))
        val certificates = buffer(readLp(data))
        val attributes = readLp(data); require(attributes.isEmpty() && !data.hasRemaining())
        val digestRecord = buffer(readLp(digests)); require(!digests.hasRemaining())
        require(digestRecord.int == RSA_SHA256)
        val expected = readLp(digestRecord); require(expected.size == 32 && !digestRecord.hasRemaining())
        val certificate = CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(readLp(certificates))) as X509Certificate
        require(!certificates.hasRemaining() && certificate.publicKey.encoded.contentEquals(publicKey))
        require(Signature.getInstance("SHA256withRSA").run { initVerify(certificate.publicKey); update(signedData); verify(signature) }) { "APK 签名校验失败" }
        val eocd = layout.eocd.copyOf().also { putInt(it, 16, start.toInt()) }
        val actual = digest(input, start, layout.directory, layout.end, eocd, checkpoint)
        require(MessageDigest.isEqual(expected, actual)) { "APK 内容摘要校验失败" }
        ApkSignatureReport(MessageDigest.getInstance("SHA-256").digest(certificate.encoded).hex())
    }

    private fun layout(input: RandomAccessFile): Layout {
        require(input.length() in 22..1_073_741_824L) { "APK 大小超出预算" }
        input.seek(input.length() - 22)
        val eocd = ByteArray(22).also(input::readFully)
        val data = buffer(eocd)
        require(data.int == 0x06054b50 && data.short.toInt() == 0 && data.short.toInt() == 0)
        val entries = data.short.toInt() and 0xFFFF
        require(entries > 0 && entries == (data.short.toInt() and 0xFFFF) && entries < 65535)
        val size = data.int.toLong() and 0xFFFFFFFFL
        val directory = data.int.toLong() and 0xFFFFFFFFL
        require(data.short.toInt() == 0 && directory + size == input.length() - 22 && directory > 0)
        return Layout(directory, input.length() - 22, eocd)
    }

    private fun digest(input: RandomAccessFile, prefixEnd: Long, directory: Long, end: Long,
        eocd: ByteArray, checkpoint: () -> Unit): ByteArray {
        val chunks = ByteArrayOutputStream()
        var count = 0
        val bytes = ByteArray(CHUNK)
        fun append(size: Int) {
            val hash = MessageDigest.getInstance("SHA-256")
            hash.update(0xA5.toByte()); hash.update(int(size)); hash.update(bytes, 0, size)
            chunks.write(hash.digest()); count++
        }
        for ((offset, length) in listOf(0L to prefixEnd, directory to end - directory)) {
            input.seek(offset)
            var remaining = length
            while (remaining > 0) {
                checkpoint()
                val size = minOf(CHUNK.toLong(), remaining).toInt()
                input.readFully(bytes, 0, size); append(size); remaining -= size
            }
        }
        checkpoint()
        eocd.copyInto(bytes); append(eocd.size)
        return MessageDigest.getInstance("SHA-256").run { update(0x5A.toByte()); update(int(count)); digest(chunks.toByteArray()) }
    }

    private fun copy(input: RandomAccessFile, output: FileOutputStream, offset: Long, length: Long, checkpoint: () -> Unit) {
        input.seek(offset)
        val bytes = ByteArray(65_536)
        var remaining = length
        while (remaining > 0) {
            checkpoint()
            val size = minOf(bytes.size.toLong(), remaining).toInt()
            input.readFully(bytes, 0, size); output.write(bytes, 0, size); remaining -= size
        }
    }
    private fun readLp(buffer: ByteBuffer): ByteArray {
        require(buffer.remaining() >= 4)
        val size = buffer.int
        require(size in 0..buffer.remaining())
        return ByteArray(size).also(buffer::get)
    }
    private fun lp(bytes: ByteArray) = int(bytes.size) + bytes
    private fun int(value: Int) = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()
    private fun long(value: Long) = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(value).array()
    private fun buffer(bytes: ByteArray) = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    private fun putInt(bytes: ByteArray, offset: Int, value: Int) { buffer(bytes).putInt(offset, value) }
}

internal fun ByteArray.hex(): String = joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }

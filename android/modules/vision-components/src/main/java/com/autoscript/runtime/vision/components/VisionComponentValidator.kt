package com.autoscript.runtime.vision.components

import com.autoscript.runtime.service.NativeVisionSpi
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.zip.ZipFile

data class VerifiedVisionComponent(val descriptor: VisionComponentDescriptor, val sha256: String, val bytes: Long)

/** No class/native loading. Call on IO with an owned frozen copy and a deadline/cancel checkpoint. */
object VisionComponentValidator {
    const val MAX_APK_BYTES = 268_435_456L
    const val MAX_MANIFEST_BYTES = 65_536L

    fun validate(
        apk: File,
        trustedPublisherCertificates: Set<String>,
        manifestReader: (File) -> VisionComponentManifest,
        checkpoint: () -> Unit,
    ): VerifiedVisionComponent {
        checkpoint()
        require(apk.isFile && apk.length() in 1..MAX_APK_BYTES)
        require(trustedPublisherCertificates.isNotEmpty() && trustedPublisherCertificates.all { it.matches(Regex("[0-9a-f]{64}")) })
        val before = apk.inputStream().use { hash(it, MAX_APK_BYTES, checkpoint) }
        require(ApkV2Verifier.verify(apk, checkpoint).certificateSha256 in trustedPublisherCertificates)
        val descriptor = ZipFile(apk).use { zip ->
            val entries = zip.entries().asSequence().take(10_001).toList()
            require(entries.size in 1..10_000 && entries.map { it.name }.distinct().size == entries.size)
            var inflated = 0L
            entries.forEach { entry ->
                checkpoint()
                val path = entry.name.removeSuffix("/").split('/')
                require(entry.name.length in 1..1024 && '\\' !in entry.name && '\u0000' !in entry.name &&
                    path.all { it.isNotEmpty() && it != "." && it != ".." && ':' !in it })
                require(entry.size in 0..MAX_APK_BYTES)
                inflated += entry.size
                require(inflated <= 536_870_912L)
            }
            val descEntry = requireNotNull(zip.getEntry(VisionComponentDescriptor.PATH))
            require(descEntry.size in 1..VisionComponentDescriptor.MAX_BYTES.toLong())
            val descriptor = VisionComponentDescriptor.parse(zip.getInputStream(descEntry).use {
                readBounded(it, VisionComponentDescriptor.MAX_BYTES, checkpoint).also { bytes -> require(bytes.size.toLong() == descEntry.size) }
            })
            val names = entries.filterNot { it.isDirectory }.map { it.name }.toSet()
            val expectedNative = descriptor.abis.flatMap { abi -> descriptor.kind.nativeLibraries.map { "lib/$abi/$it" } }.toSet()
            require(names.filter { it.startsWith("lib/") }.toSet() == expectedNative)
            val expectedAssets = descriptor.kind.licenses + VisionComponentDescriptor.PATH +
                descriptor.models.map { it.path }.toSet()
            require(names.filter { it.startsWith("assets/") }.toSet() == expectedAssets)
            require("classes.dex" in names && "AndroidManifest.xml" in names)
            require(requireNotNull(zip.getEntry("AndroidManifest.xml")).size in 1..MAX_MANIFEST_BYTES) { "VISION_COMPONENT_MANIFEST_BUDGET" }
            // Detect embedded project artifacts and host/Root libraries before extraction.
            require(names.none { it.contains("autoscript-release") || it.contains("studio_compiler") || it.contains("engine_jni") })
            expectedNative.forEach { name ->
                checkpoint()
                val entry = requireNotNull(zip.getEntry(name))
                require(entry.size >= 20)
                val header = ByteArray(20)
                zip.getInputStream(entry).use { input ->
                    var offset = 0
                    while (offset < header.size) { checkpoint(); val n = input.read(header, offset, header.size - offset); require(n > 0); offset += n }
                }
                require(header.take(6) == listOf(0x7f.toByte(), 69.toByte(), 76.toByte(), 70.toByte(), 2.toByte(), 1.toByte()))
                val machine = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).getShort(18).toInt() and 0xffff
                require(machine == if (name.startsWith("lib/arm64-v8a/")) 183 else 62)
            }
            descriptor.kind.licenses.forEach { name ->
                val entry = requireNotNull(zip.getEntry(name))
                require(entry.size in 1..1_048_576L)
                require(zip.getInputStream(entry).use { hash(it, 1_048_576L, checkpoint) }.first == entry.size)
            }
            for (fixed in descriptor.models) {
                val model = requireNotNull(zip.getEntry(fixed.path))
                require(model.size == fixed.bytes.toLong())
                val checked = zip.getInputStream(model).use { hash(it, fixed.bytes.toLong(), checkpoint) }
                require(checked.first == model.size && checked.second == fixed.sha256)
            }
            descriptor
        }
        checkpoint()
        manifestReader(apk).check(descriptor)
        val after = apk.inputStream().use { hash(it, MAX_APK_BYTES, checkpoint) }
        require(after == before && after.first == apk.length())
        checkpoint()
        return VerifiedVisionComponent(descriptor, after.second, after.first)
    }

    internal fun readBounded(input: InputStream, max: Int, checkpoint: () -> Unit): ByteArray = ByteArrayOutputStream().use { out ->
        val buffer = ByteArray(1024)
        while (true) {
            checkpoint()
            val count = input.read(buffer)
            if (count < 0) break
            require(out.size().toLong() + count <= max)
            out.write(buffer, 0, count)
        }
        out.toByteArray()
    }

    internal fun hash(input: InputStream, max: Long, checkpoint: () -> Unit): Pair<Long, String> {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(65_536)
        var count = 0L
        while (true) {
            checkpoint()
            val n = input.read(buffer)
            if (n < 0) break
            count += n
            require(count <= max)
            digest.update(buffer, 0, n)
        }
        return count to digest.digest().hex()
    }
}

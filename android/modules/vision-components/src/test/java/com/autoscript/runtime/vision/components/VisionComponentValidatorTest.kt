package com.autoscript.runtime.vision.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class VisionComponentValidatorTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun apk(kind: String) = File(requireNotNull(System.getProperty("vision.components.$kind"))).also { require(it.isFile) }
    // Trust comes from the configured publisher key store, not the APK under test.
    private fun trust(): Set<String> {
        val store = KeyStore.getInstance("JKS")
        File(requireNotNull(System.getProperty("vision.publisher.keystore"))).inputStream().use { store.load(it, "android".toCharArray()) }
        val certificate = requireNotNull(store.getCertificate("androiddebugkey"))
        return setOf(MessageDigest.getInstance("SHA-256").digest(certificate.encoded).hex())
    }

    private fun manifest(kind: VisionComponentKind) = VisionComponentManifest(kind.applicationId, 2, kind.sdkVersion, 24, true, 0, 0)
    private fun validate(file: File, kind: VisionComponentKind) =
        VisionComponentValidator.validate(file, trust(), { manifest(kind) }, {})

    @Test fun realSignedExportsPassArchiveGatesForBothKinds() {
        // Manifest values are a JVM fixture. Actual Android PackageManager read
        // and absence of executable components still require device acceptance.
        for (kind in VisionComponentKind.entries) {
            val checked = validate(apk(kind.id), kind)
            assertEquals(kind, checked.descriptor.kind)
            assertEquals(apk(kind.id).length(), checked.bytes)
            assertEquals(64, checked.sha256.length)
        }
    }

    @Test fun wrongPublisherAndCancellationFailBeforeAnyLoading() {
        assertThrows(IllegalArgumentException::class.java) {
            VisionComponentValidator.validate(apk("opencv"), setOf("0".repeat(64)), { manifest(VisionComponentKind.OPENCV) }, {})
        }
        var called = false
        assertThrows(CancellationException::class.java) {
            VisionComponentValidator.validate(apk("opencv"), trust(), { called = true; manifest(VisionComponentKind.OPENCV) }) {
                throw CancellationException()
            }
        }
        assertTrue(!called)
    }

    @Test fun nativeAllowlistRejectsExtraSignedLibrary() {
        val changed = rewrite("opencv", mapOf("lib/arm64-v8a/libunknown.so" to ByteArray(20)))
        assertThrows(IllegalArgumentException::class.java) { validate(changed, VisionComponentKind.OPENCV) }
    }

    @Test fun declaredAbiMustMatchActualElf() {
        val abi = ZipFile(apk("opencv")).use { zip ->
            zip.getInputStream(requireNotNull(zip.getEntry(VisionComponentDescriptor.PATH))).use {
                VisionComponentDescriptor.parse(it.readBytes()).abis.first()
            }
        }
        val header = ByteArray(20).apply {
            byteArrayOf(0x7f, 69, 76, 70, 2, 1).copyInto(this)
            this[18] = (if (abi == "arm64-v8a") 62 else 183).toByte()
        }
        val changed = rewrite("opencv", mapOf("lib/$abi/libopencv_java4.so" to header))
        assertThrows(IllegalArgumentException::class.java) { validate(changed, VisionComponentKind.OPENCV) }
    }

    @Test fun signedModelWithWrongPixelsIsRejectedEvenWhenDescriptorClaimsCorrectHash() {
        for (path in listOf(VisionComponentDescriptor.MODEL_PATH, VisionComponentDescriptor.V5_PATH)) {
        val model = ZipFile(apk("onnx")).use { zip ->
            zip.getInputStream(requireNotNull(zip.getEntry(path))).use { it.readBytes() }
        }
        model[0] = (model[0].toInt() xor 1).toByte()
        val changed = rewrite("onnx", mapOf(path to model))
        assertThrows(IllegalArgumentException::class.java) { validate(changed, VisionComponentKind.ONNX) }
        }
    }

    @Test fun invalidActualManifestIsNotOverriddenBySignedDescriptor() {
        assertThrows(IllegalArgumentException::class.java) {
            VisionComponentValidator.validate(apk("opencv"), trust(), { manifest(VisionComponentKind.OPENCV).copy(componentCount = 1) }, {})
        }
    }

    @Test fun signedOversizedManifestIsRejectedBeforeAndroidParsing() {
        val changed = rewrite("opencv", mapOf("AndroidManifest.xml" to ByteArray(65_537)))
        val error = assertThrows(IllegalArgumentException::class.java) { validate(changed, VisionComponentKind.OPENCV) }
        assertEquals("VISION_COMPONENT_MANIFEST_BUDGET", error.message)
    }

    @Test fun signedExtraAssetOrModelIsNotAccepted() {
        val changed = rewrite("opencv", mapOf("assets/custom-model.onnx" to byteArrayOf(1)))
        assertThrows(IllegalArgumentException::class.java) { validate(changed, VisionComponentKind.OPENCV) }
    }

    @Test fun tamperingAfterSigningFailsCryptographicContentCheck() {
        val target = temporary.newFile("tampered.apk")
        apk("opencv").inputStream().use { input -> target.outputStream().use { input.copyTo(it) } }
        java.io.RandomAccessFile(target, "rw").use { file -> file.seek(10); val byte = file.read(); file.seek(10); file.write(byte xor 1) }
        assertThrows(Exception::class.java) { validate(target, VisionComponentKind.OPENCV) }
    }

    /** Preserve real export entries and re-sign mutations with the public debug fixture identity. */
    private fun rewrite(kind: String, replacements: Map<String, ByteArray>): File {
        val unsigned = temporary.newFile()
        val added = replacements.toMutableMap()
        ZipOutputStream(unsigned.outputStream()).use { output ->
            ZipFile(apk(kind)).use { zip ->
                zip.entries().asSequence().forEach { old ->
                    val replacement = added.remove(old.name)
                    val next = ZipEntry(old.name).apply {
                        method = old.method
                        if (method == ZipEntry.STORED) {
                            size = replacement?.size?.toLong() ?: old.size
                            crc = replacement?.let { CRC32().apply { update(it) }.value } ?: old.crc
                        }
                    }
                    output.putNextEntry(next)
                    if (replacement != null) output.write(replacement)
                    else zip.getInputStream(old).use { it.copyTo(output) }
                    output.closeEntry()
                }
            }
            added.forEach { (name, bytes) -> output.putNextEntry(ZipEntry(name)); output.write(bytes); output.closeEntry() }
        }
        val signed = File(temporary.root, "signed-${System.nanoTime()}.apk")
        val launcher = File(System.getProperty("java.home"), "bin/${if (System.getProperty("os.name").orEmpty().startsWith("Windows")) "java.exe" else "java"}")
        val builder = ProcessBuilder(launcher.path, "-jar", requireNotNull(System.getProperty("vision.apksignerJar")), "sign",
            "--ks", requireNotNull(System.getProperty("vision.publisher.keystore")), "--ks-key-alias", "androiddebugkey",
            "--ks-pass", "env:VISION_TEST_PASSWORD", "--key-pass", "env:VISION_TEST_PASSWORD", "--min-sdk-version", "24",
            "--v1-signing-enabled", "false", "--v2-signing-enabled", "true", "--v3-signing-enabled", "false",
            "--v4-signing-enabled", "false", "--out", signed.path, unsigned.path).redirectErrorStream(true)
        builder.environment()["VISION_TEST_PASSWORD"] = "android"
        val process = builder.start()
        try {
            assertTrue("Fixture signing timed out", process.waitFor(30, TimeUnit.SECONDS))
            assertEquals("Fixture signing failed", 0, process.exitValue())
        } finally { if (process.isAlive) process.destroyForcibly().waitFor() }
        assertTrue("Mutation must have a valid trusted signature before testing its content gates",
            ApkV2Verifier.verify(signed).certificateSha256 in trust())
        return signed
    }
}

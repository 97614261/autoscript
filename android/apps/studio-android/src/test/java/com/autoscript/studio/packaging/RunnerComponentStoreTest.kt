package com.autoscript.studio

import org.junit.Assert.*
import org.junit.Test
import org.junit.Assume.assumeTrue
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class RunnerComponentStoreTest {
    private val descriptor = """{"formatVersion":1,"kind":"runner-template","componentVersion":${RunnerComponentDescriptor.COMPONENT_VERSION},"runtimeProtocol":${com.autoscript.runtime.api.RuntimeProtocol.VERSION},"runtimeApi":"1.8","family":"base","applicationId":"com.zhigou.yunxingban","versionCode":1,"versionName":"0.1.0","minSdk":24,"abis":["arm64-v8a"],"dependencies":{}}"""
    private val manifest = RunnerComponentIdentity("com.zhigou.yunxingban", 1, "0.1.0", 24)

    @Test fun signedArtifactImportsDeduplicatesAndFreezesWithoutOverwriting() = fixture { work, signed, trust ->
        val store = store(work, trust)
        val imported = store.import(signed::inputStream) {}
        assertEquals(imported, store.import(signed::inputStream) {})
        assertEquals(1, store.inventory {}.size)
        assertEquals(imported, store.freeze(imported.sha256, RunnerTemplateFamily.BASE, File(work, "frozen.apk")) {})
        assertThrows(IllegalArgumentException::class.java) { store.freeze(imported.sha256, RunnerTemplateFamily.BASE, File(work, "frozen.apk")) {} }
        assertThrows(IllegalArgumentException::class.java) { store.freeze(imported.sha256, RunnerTemplateFamily.FULL, File(work, "wrong.apk")) {} }
        assertFalse(File(work, "wrong.apk").exists())
    }

    @Test fun cancellationCleansOnlyOwnedStagingAndKeepsExistingUnknownFile() = fixture { work, signed, trust ->
        val directory = File(work, "components").also { it.mkdir() }
        File(directory, "keep.bin").writeText("unrelated")
        var calls = 0
        assertThrows(CancellationException::class.java) { store(work, trust).import(signed::inputStream) {
            if (++calls >= 3) throw CancellationException("fixture cancel")
        } }
        assertEquals(listOf("keep.bin"), directory.listFiles()!!.map { it.name })
        assertEquals("unrelated", File(directory, "keep.bin").readText())
    }

    @Test fun wrongPublisherAndActualManifestIdentityAreRejectedWithoutCommit() = fixture { work, signed, trust ->
        assertThrows(IllegalArgumentException::class.java) { store(work, setOf("0".repeat(64))).import(signed::inputStream) {} }
        val badManifest = RunnerComponentStore(File(work, "components"), trust) { manifest.copy(minSdk = 23) }
        assertThrows(IllegalArgumentException::class.java) { badManifest.import(signed::inputStream) {} }
        assertTrue(File(work, "components").listFiles()!!.isEmpty())
    }

    @Test fun tamperedAndCorruptArtifactsAreNotSilentlyReplaced() = fixture { work, signed, trust ->
        val store = store(work, trust)
        val imported = store.import(signed::inputStream) {}
        val saved = File(work, "components/${imported.sha256}.apk")
        java.io.RandomAccessFile(saved, "rw").use { it.seek(50); it.writeByte(0xFF) }
        val entry = store.inventory {}.single()
        assertNull(entry.verified)
        assertNotNull(entry.problem)
        assertThrows(Exception::class.java) { store.import(signed::inputStream) {} }
        assertTrue(saved.exists())
        assertEquals(1, File(work, "components").listFiles()!!.size)
    }

    @Test fun compiledFullRunnerHasPinnedModelLicensesAndRealElfContent() = fixture { work, _, trust ->
        val compiled = File(requireNotNull(System.getProperty("autoscript.runnerTemplate")))
        val signed = File(work, "compiled-signed.apk")
        ApkV2Signer.sign(compiled, signed, identity(work)) {}
        val verified = RunnerComponentValidator.validate(signed, trust, { manifest }) {}
        assertEquals(RunnerTemplateFamily.FULL, verified.descriptor.family)
        assertEquals(requireNotNull(System.getProperty("autoscript.expectedAbis")).split(',').toSet(), verified.descriptor.abis)
    }

    @Test fun exportedMatrixHasSamePublisherAndExactFamilies() {
        assumeTrue("Explicit offline component matrix build required", System.getProperty("autoscript.componentMatrix") == "true")
        val root = File(requireNotNull(System.getProperty("autoscript.projectRoot")))
        val full = File(root, "android/apps/runner-template-android/build/outputs/components/runner-full-development.apk")
        val trust = setOf(ApkV2Signer.verify(full).certificateSha256)
        for (family in RunnerTemplateFamily.entries) {
            val name = family.name.lowercase()
            val file = if (family == RunnerTemplateFamily.FULL) full else File(root,
                "build/profiles/components/development/$name/apps/runner-template-android/outputs/components/runner-$name-development.apk")
            val result = RunnerComponentValidator.validate(file, trust, { manifest }) {}
            assertEquals(family, result.descriptor.family)
            assertEquals(setOf("arm64-v8a", "x86_64"), result.descriptor.abis)
        }
    }

    @Test fun unknownFilesCountAgainstSlotBudgetWithoutDeletion() = fixture { work, signed, trust ->
        val directory = File(work, "components").also { it.mkdir() }
        repeat(8) { File(directory, "keep-$it.bin").writeText("keep") }
        assertThrows(IllegalArgumentException::class.java) { store(work, trust).import(signed::inputStream) {} }
        assertEquals(8, directory.listFiles()!!.size)
    }

    @Test fun explicitRemovalTouchesOnlySelectedContentAddressedArtifact() = fixture { work, signed, trust ->
        val store = store(work, trust)
        val imported = store.import(signed::inputStream) {}
        File(work, "components/unknown.bin").writeText("keep")
        assertThrows(IllegalArgumentException::class.java) { store.remove("../unknown.bin") {} }
        store.remove(imported.sha256) {}
        assertTrue(store.inventory {}.isEmpty())
        assertEquals("keep", File(work, "components/unknown.bin").readText())
        assertThrows(IllegalArgumentException::class.java) { store.remove(imported.sha256) {} }
    }

    private fun store(work: File, trust: Set<String>) = RunnerComponentStore(File(work, "components"), trust) { manifest }

    private fun fixture(action: (File, File, Set<String>) -> Unit) {
        val work = Files.createTempDirectory("runner-component-fixture-").toFile().canonicalFile
        try {
            val identity = identity(work)
            val source = File(work, "source.apk")
            ZipOutputStream(source.outputStream()).use { zip ->
                fun entry(name: String, bytes: ByteArray) { zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry() }
                entry("AndroidManifest.xml", byteArrayOf(1)); entry("resources.arsc", byteArrayOf(1)); entry("classes.dex", byteArrayOf(1))
                entry(RunnerComponentDescriptor.PATH, descriptor.toByteArray())
                val elf = ByteArray(20)
                byteArrayOf(0x7F, 69, 76, 70, 2, 1).copyInto(elf)
                ByteBuffer.wrap(elf).order(ByteOrder.LITTLE_ENDIAN).putShort(18, 183)
                entry("lib/arm64-v8a/libengine_jni.so", elf); entry("lib/arm64-v8a/libautoscript_root_daemon.so", elf)
            }
            val signed = File(work, "signed.apk")
            ApkV2Signer.sign(source, signed, identity) {}
            val trust = setOf(MessageDigest.getInstance("SHA-256").digest(identity.certificate.encoded).hex())
            action(work, signed, trust)
        } finally { work.deleteRecursively() }
    }

    private fun identity(work: File): ApkSigningIdentity {
        val file = File(work, "fixture.p12")
        val password = "local-fixture-only"
        if (!file.exists()) {
            val suffix = if (requireNotNull(System.getProperty("os.name")).startsWith("Windows")) ".exe" else ""
            val keytool = File(System.getProperty("java.home"), "bin/keytool$suffix")
            val process = ProcessBuilder(keytool.path, "-genkeypair", "-alias", "fixture", "-keyalg", "RSA", "-keysize", "2048",
                "-validity", "30", "-dname", "CN=Public Component Fixture", "-storetype", "PKCS12", "-keystore", file.path,
                "-storepass", password, "-keypass", password, "-noprompt").redirectErrorStream(true).redirectOutput(File(work, "keytool.log")).start()
            if (!process.waitFor(30, TimeUnit.SECONDS)) { process.destroyForcibly(); error("Fixture key generation timed out") }
            check(process.exitValue() == 0)
        }
        val store = KeyStore.getInstance("PKCS12")
        file.inputStream().use { store.load(it, password.toCharArray()) }
        return ApkSigningIdentity(store.getKey("fixture", password.toCharArray()) as PrivateKey, store.getCertificate("fixture") as X509Certificate)
    }
}

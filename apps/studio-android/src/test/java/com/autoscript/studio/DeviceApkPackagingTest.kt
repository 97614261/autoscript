package com.autoscript.studio

import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.UUID
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

/** Uses a real compiled Runner and independent Android SDK apksig verifier. */
class DeviceApkPackagingTest {
    @Test fun publicWorkflowFreezesIntoIndependentRunnerWithoutEditorSources() {
        val root = File(requireNotNull(System.getProperty("autoscript.projectRoot")))
        val template = File(requireNotNull(System.getProperty("autoscript.runnerTemplate")))
        val apksigner = File(requireNotNull(System.getProperty("autoscript.apksignerJar")))
        val source = root.resolve("examples/native-workflow-project")
        val work = Files.createTempDirectory("public-workflow-runner-").toFile()
        try {
            val project = File(work, "project")
            for (path in listOf("project.json", "visual/flows/main.jsonl", "visual/flows/worker.jsonl")) {
                val target = project.resolve(path); val parent = requireNotNull(target.parentFile)
                check(parent.mkdirs() || parent.isDirectory)
                source.resolve(path).copyTo(target)
            }
            val image = project.resolve("assets/images/tile.png")
            check(requireNotNull(image.parentFile).mkdirs())
            image.writeBytes(java.util.Base64.getDecoder().decode(source.resolve("assets/images/tile.png.base64").readText().trim()))
            val release = File(work, "release")
            run(hostTool(root, "release-packager"), "prepare", project.path, release.path, "com.autoscript.runner", "1", "0.1.0")
            run(hostTool(root, "release-packager"), "verify", release.path)
            val unsigned = File(work, "unsigned.apk")
            RunnerApkArchive.rebuild(template, release, unsigned) {}
            val signed = File(work, "public-workflow.apk")
            ApkV2Signer.sign(unsigned, signed, identity(work)) {}
            RunnerApkArchive.verify(signed)
            val verified = run(javaTool("java"), "-jar", apksigner.path, "verify", "--verbose", "--min-sdk-version", "24", signed.path)
            assertTrue(verified.contains("Verified using v2 scheme (APK Signature Scheme v2): true"))
            val probe = probeRunner(root, signed)
            java.util.zip.ZipFile(signed).use { apk ->
                val entries = apk.entries().asSequence().map { it.name }.toList()
                assertFalse(entries.any { it.startsWith("visual/") || it.contains("studio_compiler") || it.contains("project.json") })
                val expected = requireNotNull(System.getProperty("autoscript.expectedAbis")).split(',').toSet()
                assertEquals(expected, entries.filter { it.startsWith("lib/") }.map { it.split('/')[1] }.toSet())
                expected.forEach { assertTrue(entries.contains("lib/$it/libengine_jni.so")) }
            }
            val artifacts = File(requireNotNull(System.getProperty("autoscript.artifactRoot"))).also { check(it.mkdirs() || it.isDirectory) }
            signed.copyTo(File(artifacts, "native-workflow-runner.apk"), overwrite = true)
            File(artifacts, "runner-verification.txt").writeText("Public fixture only; disposable test signing identity; not installed.\n$verified\n$probe\n")
        } finally { work.deleteRecursively() }
    }
    @Test fun compiledRunnerWithFrozenReleaseGetsValidV2SignatureAndAlignedAbis() {
        val root = File(requireNotNull(System.getProperty("autoscript.projectRoot")))
        val template = File(requireNotNull(System.getProperty("autoscript.runnerTemplate")))
        val apksigner = File(requireNotNull(System.getProperty("autoscript.apksignerJar")))
        assertTrue("Compiled Runner template is required", template.isFile)
        assertTrue("Independent Android SDK verifier is required", apksigner.isFile)
        val work = Files.createTempDirectory("autoscript-apk-sign-test-").toFile()
        try {
            val release = File(work, "release")
            run(hostTool(root, "release-packager"), "prepare", root.resolve("examples/hello-project").path,
                release.path, "com.autoscript.runner", "1", "0.1.0")
            val unsigned = File(work, "unsigned.apk")
            RunnerApkArchive.rebuild(template, release, unsigned) {}
            val identity = identity(work)
            val signed = File(work, "signed.apk")
            ApkV2Signer.sign(unsigned, signed, identity) {}
            RunnerApkArchive.verify(signed)
            assertEquals(64, ApkV2Signer.verify(signed).certificateSha256.length)
            val java = javaTool("java")
            val independent = run(java, "-jar", apksigner.path, "verify", "--verbose", "--min-sdk-version", "24", signed.path)
            assertTrue(independent.contains("Verified using v2 scheme (APK Signature Scheme v2): true"))
            val buildTools = requireNotNull(requireNotNull(apksigner.parentFile).parentFile)
            val badging = run(File(buildTools, "aapt2$executableSuffix"), "dump", "badging", signed.path)
            assertTrue(badging.contains("package: name='com.autoscript.runner' versionCode='1' versionName='0.1.0'"))
            assertTrue("Compiled template must keep minSdk24", badging.lineSequence().any { it == "minSdkVersion:'24'" || it == "sdkVersion:'24'" })
            val probe = probeRunner(root, signed)
            val second = File(work, "second.apk")
            ApkV2Signer.sign(unsigned, second, identity) {}
            assertEquals(ApkV2Signer.verify(signed).certificateSha256, ApkV2Signer.verify(second).certificateSha256)
            assertThrows(IllegalArgumentException::class.java) { ApkV2Signer.sign(unsigned, signed, identity) {} }
            val tampered = File(work, "tampered.apk").also { signed.copyTo(it) }
            RandomAccessFile(tampered, "rw").use { file -> file.seek(100); val byte = file.read(); file.seek(100); file.write(byte xor 1) }
            assertThrows(IllegalArgumentException::class.java) { ApkV2Signer.verify(tampered) }
            assertThrows(IllegalStateException::class.java) { run(java, "-jar", apksigner.path, "verify", "--min-sdk-version", "24", tampered.path) }
            val artifactRoot = requireNotNull(File(requireNotNull(System.getProperty("autoscript.artifactRoot"))).parentFile)
            val artifacts = artifactRoot.resolve("device-packaging-verification").also { check(it.mkdirs() || it.isDirectory) }
            val artifact = signed.copyTo(File(artifacts, "hello-runner-${UUID.randomUUID()}.apk"))
            File(artifacts, artifact.name + ".verification.txt").writeText("Template bytes: ${template.length()}\nAPK bytes: ${signed.length()}\nCertificate SHA-256: ${ApkV2Signer.verify(signed).certificateSha256}\n$independent\n$probe\n${badging.lineSequence().filter { it.startsWith("package:") || it.startsWith("minSdkVersion:") || it.startsWith("sdkVersion:") }.joinToString("\n")}\n")
            println("Verified Runner APK: ${artifact.absolutePath}")
        } finally { work.deleteRecursively() }
    }

    @Test fun signingCheckpointCancelsBeforeCommittingOutput() {
        val work = Files.createTempDirectory("autoscript-apk-cancel-test-").toFile()
        try {
            val zip = File(work, "unsigned.apk")
            val payload = ByteArray(2_000_000)
            java.util.zip.ZipOutputStream(zip.outputStream()).use { output ->
                output.putNextEntry(java.util.zip.ZipEntry("payload").apply {
                    method = java.util.zip.ZipEntry.STORED; size = payload.size.toLong(); compressedSize = size
                    crc = java.util.zip.CRC32().apply { update(payload) }.value
                }); output.write(payload); output.closeEntry()
            }
            val identity = identity(work)
            for (failAt in listOf(2, 7)) {
                val signed = File(work, "cancelled-$failAt.apk")
                var checks = 0
                assertThrows(IllegalStateException::class.java) { ApkV2Signer.sign(zip, signed, identity) {
                    checks++; check(checks < failAt) { "cancelled" }
                } }
                assertEquals(failAt, checks)
                assertFalse(signed.exists())
            }
        } finally { work.deleteRecursively() }
    }

    private fun identity(work: File): ApkSigningIdentity {
        val password = UUID.randomUUID().toString()
        val key = File(work, "generated-test-key.p12")
        run(javaTool("keytool"), "-genkeypair", "-alias", "generated-test", "-keyalg", "RSA", "-keysize", "2048",
            "-validity", "3650", "-dname", "CN=Generated Packaging Test", "-storetype", "PKCS12", "-keystore", key.path,
            "-storepass", password, "-keypass", password, "-noprompt")
        val store = KeyStore.getInstance("PKCS12")
        FileInputStream(key).use { store.load(it, password.toCharArray()) }
        return ApkSigningIdentity(store.getKey("generated-test", password.toCharArray()) as PrivateKey, store.getCertificate("generated-test") as X509Certificate)
    }
    private val executableSuffix get() = if (requireNotNull(System.getProperty("os.name")).startsWith("Windows")) ".exe" else ""
    private fun probeRunner(root: File, apk: File): String {
        val flags = if (System.getProperty("autoscript.expectedAbis") == "arm64-v8a") listOf("--arm64-only") else emptyList()
        return run(hostTool(root, "package-probe"), *(flags + listOf("--require-embedded-release", apk.path)).toTypedArray())
    }
    private fun javaTool(name: String) = File(requireNotNull(System.getProperty("java.home")), "bin/$name$executableSuffix")
    private fun hostTool(root: File, name: String) = root.resolve("target/debug/$name$executableSuffix")
    private fun run(executable: File, vararg arguments: String): String {
        val log = Files.createTempFile("autoscript-packaging-verify-", ".log").toFile()
        try {
            val process = ProcessBuilder(listOf(executable.path) + arguments).redirectErrorStream(true).redirectOutput(log).start()
            if (!process.waitFor(120, TimeUnit.SECONDS)) { process.destroyForcibly(); error("Packaging verification timed out") }
            val output = log.readText()
            check(process.exitValue() == 0) { "Packaging verification failed: $output" }
            return output
        } finally { log.delete() }
    }
}

package com.autoscript.studio

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.security.MessageDigest

/** Keep reproducible verifier evidence, not another full APK after each test. */
internal object PackagingTestEvidence {
    private const val HEADER = "AS_PACKAGING_TEST_REPORT 1\n"
    private const val MAX_BYTES = 32 * 1024
    private val slots = setOf("public-workflow", "compiled-runner")

    fun write(root: File, slot: String, apk: File, details: String): File {
        require(slot in slots) { "Unknown packaging evidence slot" }
        val digest = MessageDigest.getInstance("SHA-256")
        apk.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        val sha = digest.digest().joinToString("") { "%02x".format(it) }
        val prefix = "${HEADER}Slot: $slot\n"
        val report = "${prefix}APK retained: false\nAPK bytes: ${apk.length()}\nAPK SHA-256: $sha\n$details\n"
        val bytes = report.toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_BYTES) { "Packaging evidence exceeds budget" }
        val directory = root.toPath().toAbsolutePath().normalize()
        var ancestor: java.nio.file.Path? = directory
        while (ancestor != null) {
            if (Files.exists(ancestor, NOFOLLOW_LINKS)) {
                require(ancestor.toRealPath() == ancestor) { "Redirected packaging evidence path" }
            }
            ancestor = ancestor.parent
        }
        Files.createDirectories(directory)
        val target = directory.resolve("$slot.txt")
        if (Files.exists(target, NOFOLLOW_LINKS)) {
            require(Files.isRegularFile(target, NOFOLLOW_LINKS) && !Files.isSymbolicLink(target) && Files.size(target) <= MAX_BYTES) {
                "Unexpected packaging evidence target"
            }
            require(target.toFile().readText().startsWith(prefix)) { "Unrecognized packaging evidence target" }
        }
        val temporary = Files.createTempFile(directory, ".$slot-", ".tmp")
        try {
            Files.write(temporary, bytes)
            Files.move(temporary, target, ATOMIC_MOVE, REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temporary)
        }
        println(report)
        return target.toFile()
    }
}

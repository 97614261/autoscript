package com.autoscript.studio

import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class PackagingTestEvidenceTest {
    private fun fixture(block: (File, File) -> Unit) {
        val work = Files.createTempDirectory("packaging-evidence-test-").toFile()
        try {
            val apk = work.resolve("owned-test.apk").apply { writeText("abc") }
            block(work.resolve("reports"), apk)
        } finally { work.deleteRecursively() }
    }

    @Test fun repeatedRunsKeepTwoReportsAndNoCopiedApks() = fixture { root, apk ->
        repeat(4) { PackagingTestEvidence.write(root, "compiled-runner", apk, "Verified run $it") }
        PackagingTestEvidence.write(root, "public-workflow", apk, "Public fixture verified")
        assertEquals(setOf("compiled-runner.txt", "public-workflow.txt"), root.listFiles()!!.map { it.name }.toSet())
        val report = root.resolve("compiled-runner.txt").readText()
        assertTrue(report.contains("APK retained: false\nAPK bytes: 3\n"))
        assertTrue(report.contains("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"))
        assertTrue(report.contains("Verified run 3"))
        assertFalse(report.contains("Verified run 2"))
    }

    @Test fun unknownExistingFileIsPreserved() = fixture { root, apk ->
        assertTrue(root.mkdirs())
        val unknown = root.resolve("compiled-runner.txt").apply { writeText("User-owned contents") }
        assertThrows(IllegalArgumentException::class.java) { PackagingTestEvidence.write(root, "compiled-runner", apk, "Verified") }
        assertEquals("User-owned contents", unknown.readText())
        assertEquals(1, root.listFiles()!!.size)
    }

    @Test fun oversizeEvidenceDoesNotReplacePreviousReport() = fixture { root, apk ->
        val report = PackagingTestEvidence.write(root, "compiled-runner", apk, "Original")
        val original = report.readText()
        assertThrows(IllegalArgumentException::class.java) { PackagingTestEvidence.write(root, "compiled-runner", apk, "x".repeat(32 * 1024)) }
        assertEquals(original, report.readText())
    }

    @Test fun directoryAndOtherSlotContentsAreNotOverwritten() = fixture { root, apk ->
        assertTrue(root.resolve("compiled-runner.txt").mkdirs())
        assertThrows(IllegalArgumentException::class.java) { PackagingTestEvidence.write(root, "compiled-runner", apk, "Verified") }
        assertTrue(root.resolve("compiled-runner.txt").isDirectory)
        root.resolve("public-workflow.txt").writeText("AS_PACKAGING_TEST_REPORT 1\nSlot: compiled-runner\n")
        assertThrows(IllegalArgumentException::class.java) { PackagingTestEvidence.write(root, "public-workflow", apk, "Verified") }
    }

    @Test fun unrecognizedSlotDoesNotCreateOutput() = fixture { root, apk ->
        assertThrows(IllegalArgumentException::class.java) { PackagingTestEvidence.write(root, "../other", apk, "Verified") }
        assertFalse(root.exists())
    }
}

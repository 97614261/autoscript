package com.autoscript.studio

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectFilePathsTest {
    @Test
    fun `canonical containment rejects traversal and sibling prefix without requiring API 26`() {
        val root = Files.createTempDirectory("project-file-containment").toFile()
        try {
            assertEquals(root.resolve("assets/button.png").canonicalFile, projectFileWithinRoot(root, "assets/button.png"))
            assertTrue(runCatching { projectFileWithinRoot(root, "../outside.png") }.isFailure)
            assertTrue(runCatching { projectFileWithinRoot(root, "../${root.name}-outside/button.png") }.isFailure)
            assertTrue(runCatching { projectFileWithinRoot(root, ".") }.isFailure)
        } finally {
            root.deleteRecursively()
        }
    }
}

package com.autoscript.studio

import com.autoscript.project.store.ProjectFlow
import com.autoscript.project.store.ProjectManifestDocument
import com.autoscript.project.store.ProjectSnapshot
import com.autoscript.project.store.ProjectSourceMode
import com.google.gson.JsonObject
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectFileCatalogTest {
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

    @Test
    fun `catalog contains only authoritative source and resource files`() {
        val root = Files.createTempDirectory("project-file-catalog").toFile()
        try {
            root.resolve("project.json").writeText("{}")
            root.resolve("flows").mkdirs()
            root.resolve("flows/main.jsonl").writeText("{}\n")
            root.resolve("assets/images").mkdirs()
            root.resolve("assets/images/target.png").writeBytes(byteArrayOf(1, 2, 3))
            root.resolve("generated").mkdirs()
            root.resolve("generated/main.lua").writeText("ignored")
            val image = JsonObject().apply {
                addProperty("kind", "image")
                addProperty("path", "assets/images/target.png")
            }
            val snapshot = ProjectSnapshot(
                directory = root,
                manifest = ProjectManifestDocument(
                    projectId = "project-test",
                    name = "test",
                    sourceMode = ProjectSourceMode.VISUAL,
                    entryFlowId = "main",
                    flows = listOf(ProjectFlow("main", "flows/main.jsonl", "root")),
                    resources = listOf(image),
                ),
                luaSource = null,
                flowSources = mapOf("main" to "{}\n"),
            )

            val files = projectFileCatalog(snapshot)

            assertEquals(
                listOf("project.json", "flows/main.jsonl", "assets/images/target.png"),
                files.map(StudioProjectFile::path),
            )
            assertTrue(files.single { it.kind == StudioProjectFileKind.FLOW }.opensEditor)
            assertFalse(files.any { it.path.startsWith("generated/") })
        } finally {
            root.deleteRecursively()
        }
    }
}

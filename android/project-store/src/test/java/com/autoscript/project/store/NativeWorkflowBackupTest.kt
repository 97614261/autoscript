package com.autoscript.project.store

import com.autoscript.script.ui.ScriptUiDefinition
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Test

class NativeWorkflowBackupTest {
    @Test fun publicExampleImportsSavesAndRestoresWithoutLosingTypedConfiguration() {
        val source = File(requireNotNull(System.getProperty("autoscript.projectRoot")), "examples/native-workflow-project")
        val png = java.util.Base64.getDecoder().decode(source.resolve("assets/images/tile.png.base64").readText().trim())
        val root = Files.createTempDirectory("public-workflow-backup-").toFile()
        try {
            val store = ProjectStore(root, idFactory = { "public-example-copy" })
            val zip = ByteArrayOutputStream()
            ZipOutputStream(zip).use { out ->
                for (path in listOf("project.json", "visual/flows/main.jsonl", "visual/flows/worker.jsonl", "assets/images/tile.png")) {
                    out.putNextEntry(ZipEntry(path)); out.write(if (path.endsWith(".png")) png else source.resolve(path).readBytes()); out.closeEntry()
                }
            }
            val imported = store.importProjectBackup(ByteArrayInputStream(zip.toByteArray()))
            assertEquals(2, imported.manifest.flows.size)
            assertEquals(6, imported.manifest.variables.size)
            val definition = ScriptUiDefinition.parse(requireNotNull(imported.manifest.runnerUi).toString())
            assertEquals("3", definition.initialValues()["repeatCount"])
            assertEquals("global:repeatCount", definition.fields.first { it.id == "repeatCount" }.ui?.binding)
            val original = imported.manifest.flows.first()
            val bytes = imported.directory.resolve(original.path).readBytes()
            store.saveFlow(imported.manifest.projectId, original.flowId, bytes.toString(Charsets.UTF_8), imported.flowSources.getValue(original.flowId))
            val saved = store.openProject(imported.manifest.projectId)
            val backup = ByteArrayOutputStream()
            store.exportProjectBackup(imported.manifest.projectId, backup)
            // Separate storage emulates a fresh installation without altering the first copy.
            val restored = ProjectStore(File(root, "restored")).importProjectBackup(ByteArrayInputStream(backup.toByteArray()))
            assertEquals(saved.manifest.variables, restored.manifest.variables)
            assertEquals(saved.manifest.runnerUi, restored.manifest.runnerUi)
            assertEquals(saved.manifest.debugSettings, restored.manifest.debugSettings)
            assertEquals(saved.manifest.capabilities, restored.manifest.capabilities)
            assertArrayEquals(bytes, restored.directory.resolve(original.path).readBytes())
            assertArrayEquals(png, restored.directory.resolve("assets/images/tile.png").readBytes())
        } finally { root.deleteRecursively() }
    }
}

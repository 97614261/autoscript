package com.autoscript.project.store

import java.io.File
import java.nio.file.Files
import com.google.gson.JsonParser
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class ProjectRecoveryTest {
    private val root = Files.createTempDirectory("project-recovery-").toFile()
    private val store = ProjectStore(root)
    @After fun close() { root.deleteRecursively() }

    @Test fun readOnlyListingDoesNotDiscardTransactionFiles() {
        val project = store.createProject("恢复", ProjectSourceMode.VISUAL)
        val temp = File(project.directory, "project.json.tmp").apply { writeText("draft") }
        val previous = File(project.directory, "project.json.previous").apply { writeText("previous") }
        assertEquals(1, store.listProjects().size)
        assertEquals("draft", temp.readText())
        assertEquals("previous", previous.readText())
    }

    @Test fun missingFlowRestoresOnlyCommittedPreviousAndInvalidatesGeneration() {
        val project = store.createProject("恢复", ProjectSourceMode.VISUAL)
        val flow = project.manifest.flows.single()
        val target = File(project.directory, flow.path)
        val previous = File(target.parentFile, "${target.name}.previous")
        assertTrue(target.renameTo(previous))
        val generation = File(project.directory, "generated/generation.json").apply { requireNotNull(parentFile).mkdirs(); writeText("old") }
        assertEquals(project.flowSources, store.openProject(project.manifest.projectId).flowSources)
        assertTrue(previous.isFile)
        assertFalse(generation.exists())
    }

    @Test fun uncommittedTempOrOldBackupIsNotSilentlyPromoted() {
        val project = store.createProject("恢复", ProjectSourceMode.VISUAL)
        val target = File(project.directory, project.manifest.flows.single().path)
        val temp = File(target.parentFile, "${target.name}.tmp").apply { writeText(target.readText()) }
        assertTrue(target.delete())
        assertThrows(IllegalArgumentException::class.java) { store.openProject(project.manifest.projectId) }
        assertTrue(temp.isFile)
        assertFalse(target.exists())
        val previous = File(target.parentFile, "${target.name}.previous").apply { writeText(temp.readText()) }
        assertThrows(IllegalArgumentException::class.java) { store.openProject(project.manifest.projectId) }
        assertTrue(previous.isFile)
        assertEquals(project.flowSources.values.single(), temp.readText())
    }

    @Test fun wrongRootIdentityCannotRecoverIntoAnotherFlow() {
        val project = store.createProject("恢复", ProjectSourceMode.VISUAL)
        val flow = project.manifest.flows.single()
        val target = File(project.directory, flow.path)
        val previous = File(target.parentFile, "${target.name}.previous")
        previous.writeText(target.readText().replace(flow.rootBlockId, "wrong-root"))
        assertTrue(target.delete())
        assertThrows(IllegalArgumentException::class.java) { store.openProject(project.manifest.projectId) }
        assertFalse(target.exists())
        assertTrue(previous.isFile)
    }

    @Test fun apiPromotionCommitsManifestAndSourceTogether() {
        val project = store.createProject("恢复", ProjectSourceMode.VISUAL)
        val manifest = File(project.directory, "project.json")
        val json = JsonParser.parseString(manifest.readText()).asJsonObject.apply { addProperty("runtimeApi", "1.6") }
        manifest.writeText(json.toString())
        val old = store.openProject(project.manifest.projectId)
        val flow = old.manifest.flows.single()
        val source = old.flowSources.getValue(flow.flowId).replace("task.noop", "vision.findgray")
        val saved = store.saveFlow(old.manifest.projectId, flow.flowId, source, old.flowSources.getValue(flow.flowId))
        assertEquals("1.7", saved.manifest.runtimeApi)
        assertEquals(source, saved.flowSources[flow.flowId])
        assertFalse(File(project.directory, ".studio/variable-transaction.json").exists())
    }
}

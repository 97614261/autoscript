package com.autoscript.project.store

import com.google.gson.JsonParser
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class ProjectFlowBatchSaveTest {
    private val root = Files.createTempDirectory("flow-batch-").toFile()
    private val store = ProjectStore(root)
    @After fun cleanup() { root.deleteRecursively() }

    private fun project(): ProjectSnapshot {
        val created = store.createProject("Batch", ProjectSourceMode.VISUAL)
        return store.createFlow(created.manifest.projectId, "child", setOf("main"))
    }

    @Test fun batchCommitsAllSourcesAndInvalidatesGenerationTogether() {
        val original = project()
        val generation = File(original.directory, "generated/generation.json").apply { parentFile!!.mkdirs(); writeText("old") }
        val sources = original.flowSources.mapValues { it.value + "\n" }
        val saved = store.saveFlows(original, sources)
        assertEquals(sources, saved.flowSources)
        assertFalse(generation.exists())
        assertFalse(File(original.directory, ".studio/variable-transaction.json").exists())
        original.manifest.flows.forEach { flow ->
            assertEquals(original.flowSources[flow.flowId], File(original.directory, ".studio/flows/${flow.flowId}.jsonl.bak").readText())
        }
    }

    @Test fun staleOtherPluginOrManifestRejectsBeforeAnyBatchWrite() {
        val original = project()
        val id = original.manifest.projectId
        val child = original.flowSources.getValue("child") + "\n"
        val external = store.saveFlow(id, "child", child, original.flowSources.getValue("child"))
        val generation = File(original.directory, "generated/generation.json").apply { parentFile!!.mkdirs(); writeText("untouched") }
        assertThrows(ProjectManifestConflictException::class.java) {
            store.saveFlows(original, mapOf("main" to original.flowSources.getValue("main") + "\n"))
        }
        assertEquals(external.flowSources, store.openProject(id).flowSources)
        assertEquals("untouched", generation.readText())
        val renamed = store.renameProject(id, "Changed")
        assertThrows(ProjectManifestConflictException::class.java) { store.saveFlows(external, external.flowSources) }
        assertEquals(renamed.manifest, store.openProject(id).manifest)
    }

    @Test fun unknownFlowRejectsWholeBatchAndNoChangeDoesNotInvalidateGeneration() {
        val original = project()
        val generation = File(original.directory, "generated/generation.json").apply { parentFile!!.mkdirs(); writeText("untouched") }
        assertThrows(IllegalArgumentException::class.java) {
            store.saveFlows(original, original.flowSources + ("missing" to "invalid"))
        }
        assertEquals(original.flowSources, store.openProject(original.manifest.projectId).flowSources)
        store.saveFlows(original, original.flowSources)
        assertEquals("untouched", generation.readText())
    }

    @Test fun apiPromotionUsesHighestRequirementInWholeBatch() {
        val original = project()
        val file = File(original.directory, "project.json")
        file.writeText(JsonParser.parseString(file.readText()).asJsonObject.apply { addProperty("runtimeApi", "1.6") }.toString())
        val expected = store.openProject(original.manifest.projectId)
        val sources = expected.flowSources.mapValues { (id, source) -> source.replace("task.noop", if (id == "main") "vision.findgray" else "floating.close") }
        val saved = store.saveFlows(expected, sources)
        assertEquals("1.8", saved.manifest.runtimeApi)
        assertEquals(sources, saved.flowSources)
    }

    @Test fun interruptedExistingTransactionIsRolledBackBeforeNewBatchConflictCheck() {
        val original = project()
        val mainPath = original.manifest.flows.single { it.flowId == "main" }.path
        val before = com.google.gson.JsonObject().apply {
            addProperty("project.json", File(original.directory, "project.json").readText())
            addProperty(mainPath, original.flowSources.getValue("main"))
        }
        File(original.directory, ".studio/variable-transaction.json").apply {
            parentFile!!.mkdirs()
            writeText(com.google.gson.JsonObject().apply { addProperty("version", 1); add("before", before) }.toString())
        }
        File(original.directory, mainPath).writeText("incomplete")
        val saved = store.saveFlows(original, original.flowSources.mapValues { it.value + "\n" })
        assertEquals(original.flowSources.mapValues { it.value + "\n" }, saved.flowSources)
        assertFalse(File(original.directory, ".studio/variable-transaction.json").exists())
    }
}

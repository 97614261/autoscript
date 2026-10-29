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
        val created = store.createFixtureProject("Batch", ProjectSourceMode.VISUAL)
        val child=store.createFlow(created.manifest.projectId, "child", setOf("main"))
        val root=child.manifest.flows.single {it.flowId=="child"}.rootBlockId
        val source="""{"flowSchemaVersion":1,"nodeId":"node-2","blockId":"$root","parentId":null,"orderKey":"a0","kind":"task.noop","nodeVersion":1,"depth":0,"args":{}}"""+"\n"
        return store.saveFlow(created.manifest.projectId,"child",source,"")
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

    @Test fun numericExtractionAndSymbolOcrPromoteBothSavePaths() {
        for ((kind, args, required) in listOf(
            Triple("text.numberat", "{}", "1.8"),
            Triple("vision.countcolor", "{\"limit\":0}", "1.8"),
            Triple("vision.countcolor", "{\"limit\":1000000}", "1.8"),
            Triple("vision.findallcolor", "{\"limit\":2048}", "1.8"),
            Triple("vision.countcolor", "{\"limit\":256}", "1.7"),
            Triple("vision.findcolor", "{\"additionalColors\":\"0000FF:8\"}", "1.8"),
            Triple("vision.comparecolor", "{\"additionalColors\":\"0000FF:8\"}", "1.8"),
            Triple("vision.countcolor", "{\"additionalColors\":\"0000FF:8\"}", "1.8"),
            Triple("vision.findallcolor", "{\"additionalColors\":\"0000FF:8\"}", "1.8"),
            Triple("vision.findcolor", "{\"additionalColors\":\"\"}", "1.7"),
            Triple("ocr.alphanumeric", "{\"includeSymbols\":true}", "1.8"),
            Triple("ocr.alphanumeric", "{\"includeSymbols\":false}", "1.7"),
            Triple("input.tap", "{\"x\":1200,\"y\":300,\"screenPixels\":true}", "1.8"),
            Triple("input.swipe", "{\"screenPixels\":true}", "1.8"),
            Triple("input.pointerdown", "{\"screenPixels\":true}", "1.8"),
            Triple("input.pointermove", "{\"screenPixels\":true}", "1.8"),
            Triple("input.tap", "{\"x\":1200,\"y\":300,\"screenPixels\":false}", "1.7"),
        )) {
            for (batch in listOf(false, true)) {
                val original = project()
                val id = original.manifest.projectId
                val file = File(original.directory, "project.json")
                file.writeText(JsonParser.parseString(file.readText()).asJsonObject.apply { addProperty("runtimeApi", "1.7") }.toString())
                val before = store.openProject(id)
                val node = JsonParser.parseString(before.flowSources.getValue("child").trim()).asJsonObject.apply {
                    addProperty("kind", kind)
                    add("args", JsonParser.parseString(args))
                }
                val source = "$node\n"
                val saved = if (batch) store.saveFlows(before, mapOf("child" to source + "\n"))
                    else store.saveFlow(id, "child", source, before.flowSources.getValue("child"))
                assertEquals("$kind batch=$batch", required, saved.manifest.runtimeApi)
            }
        }
    }

    private fun storedTextTool(kind: String, args: String, api: String = "1.7"): ProjectSnapshot {
        val original = project()
        val file = File(original.directory, "project.json")
        file.writeText(JsonParser.parseString(file.readText()).asJsonObject.apply { addProperty("runtimeApi", api) }.toString())
        val child = original.manifest.flows.single { it.flowId == "child" }
        val node = JsonParser.parseString(original.flowSources.getValue("child").trim()).asJsonObject.apply {
            addProperty("kind", kind)
            add("args", JsonParser.parseString(args))
        }
        val source = "  $node\r\n\r\n"
        File(original.directory, child.path).writeText(source)
        return original.copy(manifest = original.manifest.copy(runtimeApi = api),
            flowSources = original.flowSources + ("child" to source))
    }

    @Test fun reopeningStoredTextToolsRepairsOnlyManifestAndPreservesExactSources() {
        for ((kind, args) in listOf("text.numberat" to "{}", "ocr.alphanumeric" to "{\"includeSymbols\":true}",
            "legacy.getrectcolornum" to "{\"autoCapture\":true}")) {
            val original = storedTextTool(kind, args)
            val file = File(original.directory, "project.json")
            val before = file.readBytes()
            val generation = File(original.directory, "generated/generation.json").apply { parentFile!!.mkdirs(); writeText("old") }
            val reopened = store.openProject(original.manifest.projectId)
            assertEquals(original.manifest.copy(runtimeApi = "1.8"), reopened.manifest)
            assertEquals(original.flowSources, reopened.flowSources)
            original.manifest.flows.forEach { flow ->
                assertArrayEquals(original.flowSources.getValue(flow.flowId).toByteArray(Charsets.UTF_8),
                    File(original.directory, flow.path).readBytes())
            }
            assertArrayEquals(before, File(original.directory, ".studio/project.json.bak").readBytes())
            assertFalse(generation.exists())
            assertFalse(File(original.directory, ".studio/variable-transaction.json").exists())
            val promoted = file.readBytes()
            generation.writeText("new")
            assertEquals(reopened.manifest, store.openProject(original.manifest.projectId).manifest)
            assertArrayEquals(promoted, file.readBytes())
            assertArrayEquals(before, File(original.directory, ".studio/project.json.bak").readBytes())
            assertEquals("new", generation.readText())
        }
    }

    @Test fun ordinaryOcrAndNewerManifestAreNotMigratedOnOpen() {
        for ((kind, args, api) in listOf(
            Triple("ocr.alphanumeric", "{}", "1.7"),
            Triple("ocr.alphanumeric", "{\"includeSymbols\":false}", "1.7"),
            Triple("text.numberat", "{}", "1.9"),
        )) {
            val original = storedTextTool(kind, args, api)
            val file = File(original.directory, "project.json")
            val before = file.readBytes()
            val generation = File(original.directory, "generated/generation.json").apply { parentFile!!.mkdirs(); writeText("untouched") }
            assertEquals(original.manifest, store.openProject(original.manifest.projectId).manifest)
            assertArrayEquals(before, file.readBytes())
            assertEquals("untouched", generation.readText())
        }
    }

    @Test fun readOnlySnapshotGuardDoesNotRepairStoredTextTool() {
        val original = storedTextTool("text.numberat", "{}")
        val file = File(original.directory, "project.json")
        val before = file.readBytes()
        val generation = File(original.directory, "generated/generation.json").apply { parentFile!!.mkdirs(); writeText("untouched") }
        assertTrue(store.matchesManifestSnapshot(original, before))
        assertArrayEquals(before, file.readBytes())
        assertEquals("untouched", generation.readText())
        assertFalse(File(original.directory, ".studio/variable-transaction.json").exists())
    }
}

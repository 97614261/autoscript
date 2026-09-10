package com.autoscript.project.store

import java.io.File
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.util.Base64
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ProjectStoreTest {
    private lateinit var root: File
    private var now = 1_000L
    private var idCounter = 0
    private lateinit var store: ProjectStore

    @Before
    fun setUp() {
        root = Files.createTempDirectory("autoscript-projects-").toFile()
        store = newStore()
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun createsLuaProjectAndPersistsAcrossStoreInstances() {
        val created = store.createProject("Lua 示例", ProjectSourceMode.LUA)

        assertEquals("main.lua", created.manifest.entryPoint)
        assertTrue(File(created.directory, "main.lua").readText().contains("Task.sleep(1)"))
        assertEquals(ProjectSourceMode.LUA, newStore().listProjects().single().sourceMode)
    }

    @Test
    fun createsVisualProjectWithInitialFlow() {
        val created = store.createProject("积木示例", ProjectSourceMode.VISUAL)

        assertEquals("main", created.manifest.entryFlowId)
        val flow = File(created.directory, "visual/flows/main.jsonl")
        assertTrue(flow.isFile)
        assertTrue(flow.readText().contains("\"kind\":\"task.noop\""))
    }

    @Test
    fun renamesAndDeletesOnlyTheSelectedProject() {
        val first = store.createProject("第一个", ProjectSourceMode.LUA)
        val second = store.createProject("第二个", ProjectSourceMode.VISUAL)

        assertEquals("改名后", store.renameProject(first.manifest.projectId, " 改名后 ").manifest.name)
        store.deleteProject(first.manifest.projectId)

        assertFalse(first.directory.exists())
        assertTrue(second.directory.exists())
        assertEquals(second.manifest.projectId, store.listProjects().single().projectId)
    }

    @Test
    fun recoversCorruptManifestFromBackup() {
        val created = store.createProject("原名", ProjectSourceMode.LUA)
        store.renameProject(created.manifest.projectId, "新名")
        File(created.directory, "project.json").writeText("{broken")

        val recovered = newStore().openProject(created.manifest.projectId)

        assertEquals("原名", recovered.manifest.name)
        assertTrue(File(created.directory, "project.json").readText().contains("\"name\": \"原名\""))
    }

    @Test
    fun migratesLegacyVisualManifestAndKeepsOriginal() {
        val created = store.createProject("旧积木", ProjectSourceMode.VISUAL)
        val manifestFile = File(created.directory, "project.json")
        val legacy = manifestFile.readText()
            .replace("\"formatVersion\": 2", "\"formatVersion\": 1")
            .replace(Regex("\\s*\"sourceMode\": \"visual\",\\n"), "\n")
        manifestFile.writeText(legacy)

        val migrated = newStore().openProject(created.manifest.projectId)

        assertEquals(CURRENT_PROJECT_FORMAT_VERSION, migrated.manifest.formatVersion)
        assertEquals(ProjectSourceMode.VISUAL, migrated.manifest.sourceMode)
        val migrationBackup = File(created.directory, ".studio/migration/project.v1.json")
        assertTrue(migrationBackup.isFile)
        assertTrue(migrationBackup.readText().contains("\"formatVersion\": 1"))
        assertTrue(manifestFile.readText().contains("\"sourceMode\": \"visual\""))
    }

    @Test
    fun rejectsInvalidNamesAndIds() {
        assertThrows(IllegalArgumentException::class.java) {
            store.createProject("  ", ProjectSourceMode.LUA)
        }
        val invalidIdStore = ProjectStore(root, idFactory = { "../escape" })
        assertThrows(IllegalArgumentException::class.java) {
            invalidIdStore.createProject("越界", ProjectSourceMode.LUA)
        }
        assertFalse(File(root.parentFile, "escape").exists())
    }

    @Test
    fun opensBothSourceModesWithTheirDeclaredSources() {
        val lua = store.createProject("Lua", ProjectSourceMode.LUA)
        val visual = store.createProject("Visual", ProjectSourceMode.VISUAL)

        assertNotNull(store.openProject(lua.manifest.projectId).luaSource)
        assertEquals(setOf("main"), store.openProject(visual.manifest.projectId).flowSources.keys)
    }

    @Test
    fun recoversManifestFromCompletedTemporaryWrite() {
        val created = store.createProject("临时恢复", ProjectSourceMode.LUA)
        val manifest = File(created.directory, "project.json")
        manifest.copyTo(File(created.directory, "project.json.tmp"))
        assertTrue(manifest.delete())

        val recovered = newStore().openProject(created.manifest.projectId)

        assertEquals("临时恢复", recovered.manifest.name)
        assertTrue(manifest.isFile)
    }

    @Test
    fun savesLuaAndRecoversMissingEntryFromBackup() {
        val created = store.createProject("保存 Lua", ProjectSourceMode.LUA)
        store.saveLua(created.manifest.projectId, "return function()\n  Task.sleep(9)\nend\n")
        val entry = File(created.directory, "main.lua")
        assertTrue(entry.delete())

        val recovered = newStore().openProject(created.manifest.projectId)

        assertTrue(recovered.luaSource!!.contains("Task.sleep(1)"))
        assertTrue(entry.isFile)
    }

    @Test
    fun rejectsSavingOverAConcurrentLuaChange() {
        val created = store.createProject("冲突", ProjectSourceMode.LUA)
        val original = created.luaSource!!
        store.saveLua(created.manifest.projectId, "return function() return 1 end\n", original)

        assertThrows(ProjectWriteConflictException::class.java) {
            store.saveLua(created.manifest.projectId, "return function() return 2 end\n", original)
        }
        assertTrue(store.openProject(created.manifest.projectId).luaSource!!.contains("return 1"))
    }

    @Test
    fun savesFlowAtomicallyAndInvalidatesGeneration() {
        val created = store.createProject("保存 Flow", ProjectSourceMode.VISUAL)
        val original = created.flowSources.getValue("main")
        val generated = File(created.directory, "generated").apply { mkdirs() }
        File(generated, "generation.json").writeText("{\"generationId\":\"old\"}\n")
        val changed = original.replace("\"orderKey\":\"a0\"", "\"orderKey\":\"b0\"")

        val saved = store.saveFlow(created.manifest.projectId, "main", changed, original)

        assertEquals(changed, saved.flowSources.getValue("main"))
        assertFalse(File(generated, "generation.json").exists())
        assertTrue(File(generated, "generation.stale.json").readText().contains("old"))
    }

    @Test
    fun rejectsSavingOverAConcurrentFlowChangeWithoutInvalidatingGeneration() {
        val created = store.createProject("Flow 冲突", ProjectSourceMode.VISUAL)
        val original = created.flowSources.getValue("main")
        val first = original.replace("\"orderKey\":\"a0\"", "\"orderKey\":\"b0\"")
        store.saveFlow(created.manifest.projectId, "main", first, original)
        val generation = File(created.directory, "generated/generation.json")
        requireNotNull(generation.parentFile).mkdirs()
        generation.writeText("current")

        assertThrows(FlowWriteConflictException::class.java) {
            store.saveFlow(created.manifest.projectId, "main", original, original)
        }
        assertEquals(first, store.openProject(created.manifest.projectId).flowSources.getValue("main"))
        assertTrue(generation.isFile)
    }

    @Test
    fun createsAndSafelyDeletesAdditionalFlow() {
        val created = store.createProject("多 Flow", ProjectSourceMode.VISUAL)
        val withChild = store.createFlow(created.manifest.projectId, "child", setOf("main"))

        assertEquals(setOf("main", "child"), withChild.flowSources.keys)
        assertTrue(File(created.directory, "visual/flows/child.jsonl").isFile)

        val deleted = store.deleteFlow(
            created.manifest.projectId,
            "child",
            setOf("main", "child"),
        )
        assertEquals(setOf("main"), deleted.flowSources.keys)
        assertFalse(File(created.directory, "visual/flows/child.jsonl").exists())
    }

    @Test
    fun refusesDeletingEntryReferencedOrConcurrentlyChangedFlow() {
        val created = store.createProject("安全删除", ProjectSourceMode.VISUAL)
        val withChild = store.createFlow(created.manifest.projectId, "child", setOf("main"))
        assertThrows(IllegalArgumentException::class.java) {
            store.deleteFlow(created.manifest.projectId, "main", setOf("main", "child"))
        }
        assertThrows(ProjectManifestConflictException::class.java) {
            store.deleteFlow(created.manifest.projectId, "child", setOf("main"))
        }
        val main = withChild.flowSources.getValue("main").replace(
            "\"kind\":\"task.noop\",\"nodeVersion\":1,\"depth\":0,\"args\":{}",
            "\"kind\":\"flow.call\",\"nodeVersion\":1,\"depth\":0,\"args\":{\"targetFlowId\":\"child\",\"arguments\":{}}",
        )
        store.saveFlow(created.manifest.projectId, "main", main, withChild.flowSources.getValue("main"))
        assertThrows(IllegalArgumentException::class.java) {
            store.deleteFlow(created.manifest.projectId, "child", setOf("main", "child"))
        }
        assertTrue(File(created.directory, "visual/flows/child.jsonl").isFile)
    }

    @Test
    fun importsTypedResourcesWithStableUniqueNamesAndPersistsManifest() {
        val created = store.createProject("资源", ProjectSourceMode.VISUAL)
        File(created.directory, "generated").mkdirs()
        File(created.directory, "generated/generation.json").writeText("old-generation")
        val withImage = store.importResource(
            created.manifest.projectId,
            ProjectResourceKind.IMAGE,
            "按钮 副本.tmp",
            ByteArrayInputStream(PNG_1X1),
            emptySet(),
        )
        val imagePath = withImage.manifest.resources.single().get("path").asString
        assertEquals("assets/images/按钮_副本.png", imagePath)
        assertTrue(File(created.directory, imagePath).isFile)
        assertFalse(File(created.directory, "generated/generation.json").exists())
        assertEquals(
            "old-generation",
            File(created.directory, "generated/generation.stale.json").readText(),
        )

        val withDuplicate = store.importResource(
            created.manifest.projectId,
            ProjectResourceKind.IMAGE,
            "按钮 副本.png",
            ByteArrayInputStream(PNG_1X1),
            setOf(imagePath),
        )
        val duplicatePath = withDuplicate.manifest.resources.last().get("path").asString
        assertEquals("assets/images/按钮_副本-2.png", duplicatePath)

        val withDictionary = store.importResource(
            created.manifest.projectId,
            ProjectResourceKind.GLYPH_DICTIONARY,
            "main.anything",
            ByteArrayInputStream(GLYPH_DICTIONARY),
            setOf(imagePath, duplicatePath),
        )
        assertEquals(
            listOf("image", "image", "glyphDictionary"),
            withDictionary.manifest.resources.map { it.get("kind").asString },
        )
        assertTrue(File(created.directory, "dictionaries/main.asglyph").isFile)
    }

    @Test
    fun resourceDeletionIsConflictCheckedAndRefusesReferencedPaths() {
        val created = store.createProject("资源删除", ProjectSourceMode.VISUAL)
        val imported = store.importResource(
            created.manifest.projectId,
            ProjectResourceKind.IMAGE,
            "target.png",
            ByteArrayInputStream(PNG_1X1),
            emptySet(),
        )
        val path = imported.manifest.resources.single().get("path").asString
        assertThrows(ProjectManifestConflictException::class.java) {
            store.deleteResource(created.manifest.projectId, path, emptySet())
        }
        val original = imported.flowSources.getValue("main")
        val referenced = original.replace(
            "\"kind\":\"task.noop\",\"nodeVersion\":1,\"depth\":0,\"args\":{}",
            "\"kind\":\"vision.findimage\",\"nodeVersion\":1,\"depth\":0," +
                "\"args\":{\"imagePath\":\"$path\"}",
        )
        store.saveFlow(created.manifest.projectId, "main", referenced, original)
        assertThrows(IllegalArgumentException::class.java) {
            store.deleteResource(created.manifest.projectId, path, setOf(path))
        }
        assertTrue(File(created.directory, path).isFile)
    }

    @Test
    fun deletesUnreferencedResourceAndUpdatesCapabilitiesWithConflictProtection() {
        val created = store.createProject("设置", ProjectSourceMode.VISUAL)
        val imported = store.importResource(
            created.manifest.projectId,
            ProjectResourceKind.GLYPH_DICTIONARY,
            "main.asglyph",
            ByteArrayInputStream(GLYPH_DICTIONARY),
            emptySet(),
        )
        val path = imported.manifest.resources.single().get("path").asString
        val deleted = store.deleteResource(created.manifest.projectId, path, setOf(path))
        assertTrue(deleted.manifest.resources.isEmpty())
        assertFalse(File(created.directory, path).exists())

        val updated = store.updateCapabilities(
            created.manifest.projectId,
            setOf("core.task", "screen.capture", "ocr.glyph"),
            setOf("core.task"),
        )
        assertEquals(
            listOf("core.task", "ocr.glyph", "screen.capture"),
            updated.manifest.capabilities,
        )
        assertThrows(ProjectManifestConflictException::class.java) {
            store.updateCapabilities(
                created.manifest.projectId,
                setOf("core.task"),
                setOf("core.task"),
            )
        }
    }

    @Test
    fun refusesDeletingResourceReferencedByLuaSource() {
        val created = store.createProject("Lua 资源", ProjectSourceMode.LUA)
        val imported = store.importResource(
            created.manifest.projectId,
            ProjectResourceKind.IMAGE,
            "target.png",
            ByteArrayInputStream(PNG_1X1),
            emptySet(),
        )
        val path = imported.manifest.resources.single().get("path").asString
        store.saveLua(
            created.manifest.projectId,
            "return function() return Screen.loadImage(\"$path\") end\n",
            imported.luaSource,
        )
        assertThrows(IllegalArgumentException::class.java) {
            store.deleteResource(created.manifest.projectId, path, setOf(path))
        }
        assertTrue(File(created.directory, path).isFile)
    }

    @Test
    fun rejectsEmptyUnknownOrMalformedResourceFiles() {
        val created = store.createProject("坏资源", ProjectSourceMode.LUA)
        assertThrows(IllegalArgumentException::class.java) {
            store.importResource(
                created.manifest.projectId,
                ProjectResourceKind.IMAGE,
                "bad.png",
                ByteArrayInputStream("not an image".toByteArray()),
                emptySet(),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            store.importResource(
                created.manifest.projectId,
                ProjectResourceKind.GLYPH_DICTIONARY,
                "bad.asglyph",
                ByteArrayInputStream(ByteArray(16)),
                emptySet(),
            )
        }
        assertTrue(store.openProject(created.manifest.projectId).manifest.resources.isEmpty())
    }

    private fun newStore(): ProjectStore = ProjectStore(
        rootDirectory = root,
        clock = { ++now },
        idFactory = { "project-${++idCounter}" },
    )

    private companion object {
        val PNG_1X1: ByteArray = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Y9Z7ZgAAAAASUVORK5CYII=",
        )
        val GLYPH_DICTIONARY: ByteArray = byteArrayOf(
            0x41, 0x53, 0x47, 0x4C, 0x59, 0x50, 0x48, 0x00,
            0x01, 0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00,
            0x01, 0x00, 0x01, 0x00, 0x01, 0x00, 0x00, 0x00,
            0x01, 0x00, 0x00, 0x00, 0x41, 0x80.toByte(),
        )
    }
}

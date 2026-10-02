package com.autoscript.project.store

import java.io.File
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import com.google.gson.JsonParser
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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
    fun designedInterfacePersistsWithCapabilitiesAndRejectsStaleOrMissingBindings() {
        val created = store.createProject("脚本界面", ProjectSourceMode.VISUAL)
        val model = com.autoscript.script.ui.ScriptUiDefinition(version = 2, fields = listOf(
            com.autoscript.script.ui.UiField("input", "输入", "text", false, "", ui = com.autoscript.script.ui.UiPresentation()),
        ))
        val saved = store.updateRunnerUi(created.manifest.projectId, model.toJson(), null)
        assertEquals(model.toJson(), newStore().openProject(created.manifest.projectId).manifest.runnerUi)
        assertTrue("ui.control" in saved.manifest.capabilities)
        assertTrue("core.task" in saved.manifest.capabilities)
        assertTrue(saved.manifest.runtimeApi.substringAfter('.').toInt() >= 7)
        assertThrows(ProjectManifestConflictException::class.java) {
            store.updateRunnerUi(created.manifest.projectId, null, null)
        }
        val bad = model.copy(fields = model.fields.map { it.copy(ui = it.ui!!.copy(binding = "global:missing")) })
        assertThrows(IllegalArgumentException::class.java) {
            store.updateRunnerUi(created.manifest.projectId, bad.toJson(), model.toJson())
        }
        assertEquals(model.toJson(), store.openProject(created.manifest.projectId).manifest.runnerUi)
    }

    @Test
    fun createsLuaProjectAndPersistsAcrossStoreInstances() {
        val created = store.createProject("Lua 示例", ProjectSourceMode.LUA)

        assertEquals("main.lua", created.manifest.entryPoint)
        assertTrue(File(created.directory, "main.lua").readText().contains("Task.sleep(1)"))
        val summary = newStore().listProjects().single()
        assertEquals(ProjectSourceMode.LUA, summary.sourceMode)
        assertEquals(720, summary.designWidth)
        assertEquals(1280, summary.designHeight)
        assertEquals(1_001L, summary.createdAt)
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
    fun migratesLegacyPopupDpAndPercentSettingsToPixels() {
        val created = store.createProject("旧弹窗样式", ProjectSourceMode.VISUAL)
        val manifestFile = File(created.directory, "project.json")
        val manifest = JsonParser.parseString(manifestFile.readText()).asJsonObject
        manifest.getAsJsonObject("debugSettings").add("popupStyle", JsonParser.parseString(
            """{"widthDp":260,"heightDp":72,"xPercent":50,"yPercent":40,"backgroundColor":"#B3000000","textColor":"#FFFFFFFF","fontSp":14,"cornerDp":8,"durationMs":3000,"textAlign":"center"}""",
        ))
        manifestFile.writeText(manifest.toString())

        val reopened = newStore().openProject(created.manifest.projectId)
        val style = reopened.manifest.debugSettings.popupStyle
        assertTrue(style.widthPx >= 260)
        assertTrue(style.heightPx >= 72)
        assertTrue(style.fontPx >= 14)
        assertEquals(3_000, style.durationMs)
        assertEquals("#B3000000", style.backgroundColor)
    }

    @Test
    fun savesSmallPopupDimensionsInPixels() {
        val created = store.createProject("小弹窗", ProjectSourceMode.VISUAL)
        val settings = created.manifest.debugSettings.copy(
            popupStyle = created.manifest.debugSettings.popupStyle.copy(widthPx = 100, heightPx = 30),
        )

        store.updateDebugSettings(created.manifest.projectId, settings, created.manifest.debugSettings)

        val reopened = newStore().openProject(created.manifest.projectId)
        assertEquals(100, reopened.manifest.debugSettings.popupStyle.widthPx)
        assertEquals(30, reopened.manifest.debugSettings.popupStyle.heightPx)
    }

    @Test
    fun createsProjectWithSelectedBaselineResolution() {
        val created = store.createProject("1080 项目", ProjectSourceMode.VISUAL, 1080, 1920)

        assertEquals(1080, created.manifest.design.width)
        assertEquals(1920, created.manifest.design.height)
        val summary = newStore().listProjects().single()
        assertEquals(1080, summary.designWidth)
        assertEquals(1920, summary.designHeight)
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
    fun exportsAuthoritativeFilesAndImportsAnIndependentProjectCopy() {
        val created = store.createProject("备份示例", ProjectSourceMode.LUA)
        val saved = store.saveLua(
            created.manifest.projectId,
            "return function()\n  Task.sleep(7)\nend\n",
            created.luaSource,
        )
        val withImage = store.importResource(
            saved.manifest.projectId,
            ProjectResourceKind.IMAGE,
            "target.png",
            ByteArrayInputStream(PNG_1X1),
            emptySet(),
        )
        File(withImage.directory, "generated").mkdirs()
        File(withImage.directory, "generated/main.lua").writeText("stale")

        val output = ByteArrayOutputStream()
        store.exportProjectBackup(withImage.manifest.projectId, output)
        val archiveEntries = mutableSetOf<String>()
        ZipInputStream(ByteArrayInputStream(output.toByteArray())).use { archive ->
            while (true) {
                val entry = archive.nextEntry ?: break
                archiveEntries += entry.name
            }
        }
        assertEquals(setOf("project.json", "main.lua", "assets/images/target.png"), archiveEntries)

        val imported = store.importProjectBackup(ByteArrayInputStream(output.toByteArray()))

        assertTrue(imported.manifest.projectId != withImage.manifest.projectId)
        assertEquals("备份示例", imported.manifest.name)
        assertEquals(withImage.luaSource, imported.luaSource)
        assertTrue(File(imported.directory, "assets/images/target.png").readBytes().contentEquals(PNG_1X1))
        assertFalse(File(imported.directory, "generated").exists())
        assertEquals(2, store.listProjects().size)
    }

    @Test
    fun createsLuaFoldersAndModulesThatRoundTripThroughBackup() {
        val created = store.createProject("Lua 树", ProjectSourceMode.LUA)
        val folder = store.createLuaDirectory(created.manifest.projectId, "lua/tools")
        val module = store.createLuaFile(folder.manifest.projectId, "lua/tools/math.lua", "return { answer = 42 }\n")

        assertEquals(listOf("lua", "lua/tools"), module.luaDirectories)
        assertEquals(listOf("lua/tools/math.lua", "main.lua"), module.manifest.luaFiles)
        assertEquals("return { answer = 42 }\n", module.luaSources.getValue("lua/tools/math.lua"))

        val archive = ByteArrayOutputStream().also { store.exportProjectBackup(module.manifest.projectId, it) }.toByteArray()
        val imported = store.importProjectBackup(ByteArrayInputStream(archive))
        assertEquals(module.manifest.luaFiles, imported.manifest.luaFiles)
        assertEquals(module.luaDirectories, imported.luaDirectories)
        assertEquals("return { answer = 42 }\n", imported.luaSources.getValue("lua/tools/math.lua"))
        assertTrue(File(imported.directory, "lua/tools").isDirectory)
    }

    @Test
    fun rejectsLuaPathsOutsideTheDedicatedModuleDirectory() {
        val created = store.createProject("Lua 路径", ProjectSourceMode.LUA)

        assertThrows(IllegalArgumentException::class.java) {
            store.createLuaFile(created.manifest.projectId, "../escape.lua")
        }
        assertThrows(IllegalArgumentException::class.java) {
            store.createLuaDirectory(created.manifest.projectId, "assets/scripts")
        }
        assertFalse(File(created.directory.parentFile, "escape.lua").exists())
    }

    @Test
    fun backupImportRejectsTraversalAndRemovesItsStagingDirectory() {
        val archive = zipOf("../escape" to "bad".toByteArray())

        assertThrows(IllegalArgumentException::class.java) {
            store.importProjectBackup(ByteArrayInputStream(archive))
        }

        assertFalse(File(root.parentFile, "escape").exists())
        assertTrue(root.listFiles().orEmpty().none { it.name.startsWith(".project-import-") })
    }

    @Test
    fun backupImportRejectsFilesNotDeclaredByTheManifest() {
        val created = store.createProject("严格清单", ProjectSourceMode.LUA)
        val archive = zipOf(
            "project.json" to File(created.directory, "project.json").readBytes(),
            "main.lua" to File(created.directory, "main.lua").readBytes(),
            "assets/images/hidden.png" to PNG_1X1,
        )

        val error = assertThrows(IllegalArgumentException::class.java) {
            store.importProjectBackup(ByteArrayInputStream(archive))
        }

        assertTrue(error.message!!.contains("未声明文件"))
        assertEquals(1, store.listProjects().size)
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
    fun importsScreenshotIntoChosenImageFolder() {
        val created = store.createProject("截图文件夹", ProjectSourceMode.VISUAL)
        val imported = store.importResource(
            created.manifest.projectId, ProjectResourceKind.IMAGE, "按钮.png",
            ByteArrayInputStream(PNG_1X1), emptySet(), imageDirectory = "界面/首页",
        )
        val path = imported.manifest.resources.single().get("path").asString
        assertEquals("assets/images/界面/首页/按钮.png", path)
        assertTrue(File(created.directory, path).isFile)
        assertEquals(path, store.openProject(created.manifest.projectId).manifest.resources.single().get("path").asString)
        val duplicate = store.importResource(
            created.manifest.projectId, ProjectResourceKind.IMAGE, "按钮.png",
            ByteArrayInputStream(PNG_1X1), setOf(path), imageDirectory = "界面/首页",
        )
        val duplicatePath = duplicate.manifest.resources.last().get("path").asString
        assertEquals("assets/images/界面/首页/按钮-2.png", duplicatePath)
        val archive = ByteArrayOutputStream().also {
            store.exportProjectBackup(duplicate.manifest.projectId, it)
        }.toByteArray()
        val restored = store.importProjectBackup(ByteArrayInputStream(archive))
        assertTrue(File(restored.directory, path).isFile)
        assertTrue(File(restored.directory, duplicatePath).isFile)
        assertEquals(listOf(path, duplicatePath), restored.manifest.resources.map { it.get("path").asString })
        assertThrows(IllegalArgumentException::class.java) {
            store.importResource(created.manifest.projectId, ProjectResourceKind.IMAGE, "bad.png",
                ByteArrayInputStream(PNG_1X1), setOf(path, duplicatePath), imageDirectory = "../other")
        }
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

    @Test
    fun createsFlowWithChineseDisplayNameAndRenamesWithoutBreakingReferences() {
        val created = store.createProject("源文件", ProjectSourceMode.VISUAL)
        val projectId = created.manifest.projectId
        val withChild = store.createFlow(projectId, "flow-a", setOf("main"), fileName = "默认名称1")
        val child = withChild.manifest.flows.single { it.flowId == "flow-a" }
        assertEquals("visual/flows/默认名称1.jsonl", child.path)
        assertTrue(File(created.directory, child.path).isFile)

        val main = withChild.flowSources.getValue("main").replace(
            "\"kind\":\"task.noop\",\"nodeVersion\":1,\"depth\":0,\"args\":{}",
            "\"kind\":\"flow.call\",\"nodeVersion\":1,\"depth\":0,\"args\":{\"targetFlowId\":\"flow-a\",\"arguments\":{}}",
        )
        store.saveFlow(projectId, "main", main, withChild.flowSources.getValue("main"))
        File(created.directory, "generated").mkdirs()
        File(created.directory, "generated/generation.json").writeText("old-generation")

        val renamed = store.renameFlow(projectId, "flow-a", "主流程 v2.jsonl", setOf("main", "flow-a"))
        assertEquals("visual/flows/主流程 v2.jsonl", renamed.manifest.flows.single { it.flowId == "flow-a" }.path)
        assertFalse(File(created.directory, "visual/flows/默认名称1.jsonl").exists())
        assertTrue(File(created.directory, "visual/flows/主流程 v2.jsonl").isFile)
        assertEquals(withChild.flowSources.getValue("flow-a"), renamed.flowSources.getValue("flow-a"))
        assertFalse(File(created.directory, "generated/generation.json").exists())
        assertThrows(IllegalArgumentException::class.java) {
            store.deleteFlow(projectId, "flow-a", setOf("main", "flow-a"))
        }
        assertEquals(setOf("main", "flow-a"), newStore().openProject(projectId).flowSources.keys)
    }

    @Test
    fun rejectsIllegalFlowNamesAndConflicts() {
        val created = store.createProject("非法名", ProjectSourceMode.VISUAL)
        val projectId = created.manifest.projectId
        // "名字 .jsonl" 去掉扩展名后名字真的以空格结尾，trim 拿不掉，必须拒绝；"   " 全空白等价于空名。
        listOf("分组/文件", ".hidden", "尾点.", "名字 .jsonl", "a:b", "", "   ", "カタカナ", "长".repeat(65))
            .forEach { name ->
                assertThrows("name=$name", IllegalArgumentException::class.java) {
                    store.createFlow(projectId, "flow-x", setOf("main"), fileName = name)
                }
            }
        assertEquals(setOf("main"), store.openProject(projectId).flowSources.keys)
        assertFalse(File(created.directory, "visual/flows").listFiles().orEmpty().any { it.name != "main.jsonl" })

        // 首尾空白是输入规范化，不是非法名：UI 会 trim，Store 再 trim 一次，落盘的是规范名。
        val trimmed = store.createFlow(projectId, "flow-trim", setOf("main"), fileName = "  带空格  ")
        assertEquals("visual/flows/带空格.jsonl", trimmed.manifest.flows.single { it.flowId == "flow-trim" }.path)

        store.createFlow(projectId, "flow-a", setOf("main", "flow-trim"), fileName = "同名")
        assertThrows(IllegalArgumentException::class.java) {
            store.createFlow(projectId, "flow-b", setOf("main", "flow-trim", "flow-a"), fileName = "同名.jsonl")
        }
        assertThrows(ProjectManifestConflictException::class.java) {
            store.renameFlow(projectId, "flow-a", "改名", setOf("main"))
        }
        assertEquals(setOf("main", "flow-trim", "flow-a"), store.openProject(projectId).flowSources.keys)
    }

    @Test
    fun copiesFlowWithFreshIdentitiesAndKeepsStructure() {
        val created = store.createProject("另存为", ProjectSourceMode.VISUAL)
        val projectId = created.manifest.projectId
        File(created.directory, "generated").mkdirs()
        File(created.directory, "generated/generation.json").writeText("old-generation")

        val copied = store.copyFlow(projectId, "main", "flow-copy", "main_副本", setOf("main"))

        val original = copied.manifest.flows.single { it.flowId == "main" }
        val duplicate = copied.manifest.flows.single { it.flowId == "flow-copy" }
        assertEquals("visual/flows/main_副本.jsonl", duplicate.path)
        assertNotEquals(original.rootBlockId, duplicate.rootBlockId)
        val originalSource = copied.flowSources.getValue("main")
        val copySource = copied.flowSources.getValue("flow-copy")
        assertNotEquals(originalSource, copySource)
        assertEquals(originalSource.lines().size, copySource.lines().size)
        assertTrue(copySource.contains("\"blockId\":\"${duplicate.rootBlockId}\""))
        assertFalse(copySource.contains(original.rootBlockId))
        assertTrue(copySource.contains("\"kind\":\"task.noop\""))
        assertFalse(File(created.directory, "generated/generation.json").exists())
        assertThrows(IllegalArgumentException::class.java) {
            store.copyFlow(projectId, "main", "flow-copy", "另一个", setOf("main", "flow-copy"))
        }
    }

    @Test
    fun sourceGroupsAreVirtualValidatedAndPrunedWithFlows() {
        val created = store.createProject("分组", ProjectSourceMode.VISUAL)
        val projectId = created.manifest.projectId
        store.createFlow(projectId, "flow-a", setOf("main"), fileName = "甲")
        store.createFlow(projectId, "flow-b", setOf("main", "flow-a"), fileName = "乙")
        assertTrue(store.readSourceGroups(projectId).isEmpty())

        val written = store.writeSourceGroups(
            projectId,
            listOf(SourceGroup(" 新建分组1 ", listOf("flow-a", "flow-b")), SourceGroup("空分组")),
        )
        assertEquals(listOf("新建分组1", "空分组"), written.map(SourceGroup::name))
        assertEquals(written, newStore().readSourceGroups(projectId))
        assertFalse(File(created.directory, "project.json").readText().contains("新建分组1"))
        val backup = ByteArrayOutputStream().also { store.exportProjectBackup(projectId, it) }.toByteArray()
        val entries = ZipInputStream(ByteArrayInputStream(backup)).use { zip ->
            generateSequence { zip.nextEntry }.map { it.name }.toList()
        }
        assertTrue(entries.none { it.contains("source-groups") })

        assertThrows(IllegalArgumentException::class.java) {
            store.writeSourceGroups(projectId, listOf(SourceGroup("a/b", listOf("flow-a"))))
        }
        assertThrows(IllegalArgumentException::class.java) {
            store.writeSourceGroups(
                projectId,
                listOf(SourceGroup("甲组", listOf("flow-a")), SourceGroup("乙组", listOf("flow-a"))),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            store.writeSourceGroups(projectId, listOf(SourceGroup("甲组", listOf("missing"))))
        }
        assertEquals(written, store.readSourceGroups(projectId))

        store.deleteFlow(projectId, "flow-b", setOf("main", "flow-a", "flow-b"))
        assertEquals(
            listOf(SourceGroup("新建分组1", listOf("flow-a")), SourceGroup("空分组")),
            store.readSourceGroups(projectId),
        )
    }

    @Test
    fun backupSlotsPersistRestoreAsNewProjectAndNeverLeakIntoProjectList() {
        val created = store.createProject("槽位", ProjectSourceMode.VISUAL)
        val projectId = created.manifest.projectId
        assertTrue(store.listBackupSlots(projectId).none(BackupSlot::occupied))
        assertTrue(store.listBackedUpProjects().isEmpty())
        assertThrows(IllegalArgumentException::class.java) { store.backupToSlot(projectId, 4) }

        val first = store.backupToSlot(projectId, 1, " 第一次 ")
        assertTrue(first.occupied)
        assertEquals("第一次", first.remark)
        assertEquals("槽位", first.projectName)
        assertTrue(first.bytes!! > 0)
        assertTrue(File(root, ".backups/$projectId/slot-1.asproject").isFile)
        assertEquals(listOf(true, false, false), newStore().listBackupSlots(projectId).map(BackupSlot::occupied))
        assertEquals(1, store.listProjects().size)

        val summary = store.listBackedUpProjects().single()
        assertEquals(projectId, summary.projectId)
        assertEquals("槽位", summary.projectName)
        assertTrue(summary.localProjectExists)
        assertEquals(first.createdAt, summary.latestBackupAt)

        val restored = store.restoreBackupSlot(projectId, 1)
        assertNotEquals(projectId, restored.manifest.projectId)
        assertEquals("槽位", restored.manifest.name)
        assertEquals(2, store.listProjects().size)

        store.deleteProject(projectId)
        assertFalse(store.listBackedUpProjects().single().localProjectExists)
        store.deleteBackupSlot(projectId, 1)
        assertTrue(store.listBackedUpProjects().isEmpty())
        assertFalse(File(root, ".backups/$projectId").exists())
        assertThrows(IllegalArgumentException::class.java) { store.restoreBackupSlot(projectId, 1) }
    }

    private fun newStore(): ProjectStore = ProjectStore(
        rootDirectory = root,
        clock = { ++now },
        idFactory = { "project-${++idCounter}" },
    )

    private fun zipOf(vararg entries: Pair<String, ByteArray>): ByteArray =
        ByteArrayOutputStream().also { output ->
            ZipOutputStream(output).use { archive ->
                entries.forEach { (path, bytes) ->
                    archive.putNextEntry(ZipEntry(path))
                    archive.write(bytes)
                    archive.closeEntry()
                }
            }
        }.toByteArray()

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

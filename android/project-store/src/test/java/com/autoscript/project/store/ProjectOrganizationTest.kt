package com.autoscript.project.store

import com.autoscript.script.ui.ScriptUiDefinition
import com.autoscript.script.ui.UiField
import com.autoscript.script.ui.UiPresentation
import com.google.gson.JsonParser
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ProjectOrganizationTest {
    private lateinit var root: File
    private lateinit var store: ProjectStore
    private var counter = 0

    @Before fun setup() {
        root = Files.createTempDirectory("project-organization-").toFile()
        store = ProjectStore(root, idFactory = { "project-${++counter}" })
    }

    @After fun cleanup() { root.deleteRecursively() }

    private fun project(): ProjectSnapshot {
        val created = store.createProject("项目", ProjectSourceMode.VISUAL)
        return store.createFlow(created.manifest.projectId, "child", setOf("main"), "登录")
    }

    @Test fun variableCountAccepts512AndRejects513WithoutWriting() {
        val created = store.createProject("边界测试", ProjectSourceMode.VISUAL)
        val variables = (0 until 512).map {
            ProjectVariable("v$it", ProjectVariableScope.GLOBAL, type = ProjectVariableType.INTEGER)
        }
        store.updateVariables(created.manifest.projectId, variables, emptyList())
        assertEquals(512, store.openProject(created.manifest.projectId).manifest.variables.size)
        assertThrows(IllegalArgumentException::class.java) {
            store.updateVariables(created.manifest.projectId, variables +
                ProjectVariable("overflow", ProjectVariableScope.GLOBAL, type = ProjectVariableType.INTEGER), variables)
        }
        assertEquals(512, store.openProject(created.manifest.projectId).manifest.variables.size)
    }

    @Test fun entryChangePersistsAndRenameKeepsItsIdentity() {
        val base = project()
        val id = base.manifest.projectId
        File(base.directory, "generated").mkdirs()
        File(base.directory, "generated/generation.json").writeText("old")
        val changed = store.setEntryFlow(id, "child", "main", setOf("main", "child"))
        assertEquals("child", changed.manifest.entryFlowId)
        assertFalse(File(base.directory, "generated/generation.json").exists())
        assertThrows(ProjectManifestConflictException::class.java) {
            store.setEntryFlow(id, "main", "main", setOf("main", "child"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            store.setEntryFlow(id, "missing", "child", setOf("main", "child"))
        }
        val renamed = store.renameFlow(id, "child", "新主程序", setOf("main", "child"))
        assertEquals("child", renamed.manifest.entryFlowId)
        assertEquals("visual/flows/新主程序.jsonl", renamed.manifest.flows.last().path)
        assertThrows(IllegalArgumentException::class.java) { store.deleteFlow(id, "child", setOf("main", "child")) }
        val restored = store.importProjectBackup(ByteArrayInputStream(backup(id)))
        assertEquals("child", restored.manifest.entryFlowId)
    }

    @Test fun changingEntryRejectsIncompatibleUiParameterBindingsWithoutWriting() {
        val base = project()
        val file = File(base.directory, "project.json")
        val manifest = JsonParser.parseString(file.readText()).asJsonObject
        manifest.getAsJsonArray("flows").first { it.asJsonObject.get("flowId").asString == "main" }.asJsonObject.add("params", JsonParser.parseString(
            """[{"name":"count","type":"integer","required":false}]"""))
        file.writeText(manifest.toString())
        val ui = ScriptUiDefinition(version = 2, fields = listOf(
            UiField("count", "次数", "integer", false, "3", ui = UiPresentation(control = "NUMBER", binding = "param:count")),
        ))
        store.updateRunnerUi(base.manifest.projectId, ui.toJson(), null)
        val before = file.readText()
        assertThrows(IllegalArgumentException::class.java) {
            store.setEntryFlow(base.manifest.projectId, "child", "main", setOf("main", "child"))
        }
        assertEquals(before, file.readText())
        assertEquals("main", store.openProject(base.manifest.projectId).manifest.entryFlowId)
    }

    @Test fun copyCarriesLocalVariablesAndDeleteOnlyRemovesItsOwnScope() {
        val base = project()
        val id = base.manifest.projectId
        val variables = listOf(
            ProjectVariable("globalCount", ProjectVariableScope.GLOBAL, type = ProjectVariableType.INTEGER),
            ProjectVariable("count", ProjectVariableScope.FLOW, "child", ProjectVariableType.INTEGER),
        )
        store.updateVariables(id, variables, emptyList())
        val copied = store.copyFlow(id, "child", "duplicate", "登录副本", setOf("main", "child"))
        assertEquals(3, copied.manifest.variables.size)
        assertTrue(variables.last().copy(flowId = "duplicate") in copied.manifest.variables)
        assertNotEquals(copied.manifest.flows[1].rootBlockId, copied.manifest.flows[2].rootBlockId)
        val deleted = store.deleteFlow(id, "child", setOf("main", "child", "duplicate"))
        assertEquals(listOf(variables.first(), variables.last().copy(flowId = "duplicate")), deleted.manifest.variables)
        assertEquals(deleted.manifest.variables, store.openProject(id).manifest.variables)
    }

    @Test fun uiEventsAndVariableBindingsPreventDeletionAndVariableRemoval() {
        val base = project()
        val id = base.manifest.projectId
        val local = ProjectVariable("count", ProjectVariableScope.FLOW, "child", ProjectVariableType.INTEGER)
        store.updateVariables(id, listOf(local), emptyList())
        val ui = ScriptUiDefinition(version = 2, fields = listOf(
            UiField("count", "次数", "integer", false, "3", ui = UiPresentation(control = "NUMBER", binding = "flow:child:count")),
            UiField("run", "执行登录", "text", false, "", ui = UiPresentation(control = "BUTTON", events = mapOf("click" to "flow:child"))),
        ))
        val configured = store.updateRunnerUi(id, ui.toJson(), null)
        assertThrows(IllegalArgumentException::class.java) { store.deleteFlow(id, "child", setOf("main", "child")) }
        assertThrows(IllegalArgumentException::class.java) { store.updateVariables(id, emptyList(), listOf(local)) }
        val renamed = store.renameFlow(id, "child", "账号登录", setOf("main", "child"))
        assertEquals(configured.manifest.runnerUi, renamed.manifest.runnerUi)
        assertEquals(listOf(local), renamed.manifest.variables)
        assertTrue(File(base.directory, "visual/flows/账号登录.jsonl").isFile)
    }

    @Test fun backupRoundTripPreservesGroupsUiVariablesAndEmptyImageFolders() {
        val base = project()
        val id = base.manifest.projectId
        val groups = listOf(SourceGroup("账号", listOf("child")), SourceGroup("空分组"))
        store.writeSourceGroups(id, groups)
        val local = ProjectVariable("count", ProjectVariableScope.FLOW, "child", ProjectVariableType.INTEGER)
        store.updateVariables(id, listOf(local), emptyList())
        val ui = ScriptUiDefinition(version = 2, fields = listOf(
            UiField("run", "执行登录", "text", false, "", ui = UiPresentation(control = "BUTTON", events = mapOf("click" to "flow:child"))),
        ))
        store.updateRunnerUi(id, ui.toJson(), null)
        store.createImageDirectory(id, "账号/按钮")
        val first = store.backupToSlot(id, 1, "完整工程")
        assertTrue(first.occupied)
        val restored = store.restoreBackupSlot(id, 1)
        assertNotEquals(id, restored.manifest.projectId)
        assertEquals(groups, store.readSourceGroups(restored.manifest.projectId))
        assertEquals(listOf("账号", "账号/按钮"), store.readImageDirectories(restored.manifest.projectId))
        assertTrue(File(restored.directory, "assets/images/账号/按钮").isDirectory)
        assertEquals(listOf(local), restored.manifest.variables)
        assertEquals(ui.toJson(), restored.manifest.runnerUi)
        assertEquals(base.flowSources, restored.flowSources)
    }

    @Test fun legacyBackupsRemainSupportedAndUnknownStudioStateIsRejected() {
        val base = project()
        val old = backup(base.manifest.projectId)
        assertEquals(emptyList<SourceGroup>(), store.readSourceGroups(store.importProjectBackup(ByteArrayInputStream(old)).manifest.projectId))
        assertThrows(IllegalArgumentException::class.java) {
            store.importProjectBackup(ByteArrayInputStream(replaceEntry(old, ".studio/state.properties", "x=y")))
        }
        assertEquals(2, store.listProjects().size)
        assertTrue(root.listFiles().orEmpty().none { it.name.startsWith(".project-import-") })
    }

    @Test fun invalidPortableOrganizationIsRejectedWithoutCommittingAProject() {
        val base = project()
        val old = backup(base.manifest.projectId)
        val invalid = listOf(
            ".studio/source-groups.json" to """{"version":2,"groups":[]}""",
            ".studio/source-groups.json" to """{"version":1,"groups":[{"name":"组","flowIds":["missing"]}]}""",
            ".studio/source-groups.json" to """{"version":1,"groups":[{"name":"组","flowIds":["child","child"]}]}""",
            ".studio/image-directories.json" to """{"version":1,"directories":["../逃逸"]}""",
            ".studio/image-directories.json" to """{"version":1,"directories":["甲/乙"]}""",
            ".studio/image-directories.json" to """{"version":1,"directories":["甲","甲"]}""",
            ".studio/source-groups.json" to """{"version":1,"groups":{}}""",
            ".studio/source-groups.json" to """{"version":1,"groups":[{"name":"组","flowIds":null}]}""",
            ".studio/image-directories.json" to """{"version":1,"directories":null}""",
            ".studio/image-directories.json" to "[]",
            ".studio/image-directories.json" to "{invalid",
        )
        invalid.forEach { (path, content) ->
            assertThrows(IllegalArgumentException::class.java) {
                store.importProjectBackup(ByteArrayInputStream(replaceEntry(old, path, content)))
            }
        }
        assertEquals(1, store.listProjects().size)
        assertTrue(root.listFiles().orEmpty().none { it.name.startsWith(".project-import-") })
    }

    @Test fun folderCreationRejectsTraversalAndDiscoversExistingDirectories() {
        val base = project()
        File(base.directory, "assets/images/旧目录/子目录").mkdirs()
        assertEquals(listOf("旧目录", "旧目录/子目录"), store.readImageDirectories(base.manifest.projectId))
        listOf("../escape", "/absolute", "a/b/c/d/e", "a\\b", "a//b", ".", "").forEach { path ->
            assertThrows(IllegalArgumentException::class.java) { store.createImageDirectory(base.manifest.projectId, path) }
        }
        store.createImageDirectory(base.manifest.projectId, "新目录")
        assertThrows(IllegalArgumentException::class.java) { store.createImageDirectory(base.manifest.projectId, "新目录") }
        assertFalse(File(base.directory.parentFile, "escape").exists())
    }

    @Test fun failedMkdirDoesNotPersistAClaimedFolderAndInvalidGroupVersionDoesNotCrashList() {
        val base = project()
        File(base.directory, "assets/images").mkdirs()
        File(base.directory, "assets/images/blocked").writeText("file")
        assertThrows(IllegalArgumentException::class.java) {
            store.createImageDirectory(base.manifest.projectId, "blocked/child")
        }
        assertTrue(store.readImageDirectories(base.manifest.projectId).isEmpty())
        assertFalse(File(base.directory, ".studio/image-directories.json").exists())
        File(base.directory, ".studio/source-groups.json").writeText("""{"version":"bad","groups":[]}""")
        assertTrue(store.readSourceGroups(base.manifest.projectId).isEmpty())
    }

    @Test fun copyingVariablesOverLimitLeavesManifestAndFileTreeUntouched() {
        val base = project()
        val variables = (1..512).map { index ->
            ProjectVariable("value$index", ProjectVariableScope.FLOW, "child", ProjectVariableType.INTEGER)
        }
        store.updateVariables(base.manifest.projectId, variables, emptyList())
        val before = File(base.directory, "project.json").readText()
        assertThrows(IllegalArgumentException::class.java) {
            store.copyFlow(base.manifest.projectId, "child", "duplicate", "副本", setOf("main", "child"))
        }
        assertEquals(before, File(base.directory, "project.json").readText())
        assertFalse(File(base.directory, "visual/flows/副本.jsonl").exists())
        assertEquals(512, store.openProject(base.manifest.projectId).manifest.variables.size)
    }

    private fun backup(id: String): ByteArray = ByteArrayOutputStream().also { store.exportProjectBackup(id, it) }.toByteArray()

    private fun replaceEntry(source: ByteArray, path: String, content: String): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { target ->
            ZipInputStream(ByteArrayInputStream(source)).use { original ->
                while (true) {
                    val entry = original.nextEntry ?: break
                    if (entry.name != path) {
                        target.putNextEntry(ZipEntry(entry.name))
                        original.copyTo(target)
                        target.closeEntry()
                    }
                }
            }
            target.putNextEntry(ZipEntry(path))
            target.write(content.toByteArray(Charsets.UTF_8))
            target.closeEntry()
        }
        return output.toByteArray()
    }
}

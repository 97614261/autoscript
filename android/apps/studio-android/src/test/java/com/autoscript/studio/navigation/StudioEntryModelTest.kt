package com.autoscript.studio

import com.autoscript.core.model.RuntimeConnectionPhase
import com.autoscript.core.model.RuntimeConnectionState
import com.autoscript.core.model.RuntimeEngineState
import com.autoscript.core.model.RuntimeRootState
import com.autoscript.project.store.ProjectSourceMode
import com.autoscript.project.store.ProjectSummary
import org.junit.Assert.*
import org.junit.Test

class StudioEntryModelTest {
    private val profileStudioEntries get() = studioProfileSections.flatMap { it.entries }
    @Test
    fun `compact project ids are display hints and leave full identities unchanged`() {
        val fullId = "project-01234567-89ab-cdef-0123-456789abcdef"
        assertEquals("0123…cdef", compactProjectId(fullId))
        assertEquals("abc123", compactProjectId("project-abc123"))
        assertEquals("1234567890", compactProjectId("1234567890"))
        assertEquals("1234…8901", compactProjectId("12345678901"))
        assertEquals("cust…3456", compactProjectId("custom-key-123456"))
        assertEquals("project-01234567-89ab-cdef-0123-456789abcdef", fullId)
    }

    private fun project(id: String, updated: Long = 10, opened: Long? = null,
        name: String = id, mode: ProjectSourceMode = ProjectSourceMode.VISUAL) =
        ProjectSummary(id, name, mode, 720, 1280, 1, updated, opened)

    @Test
    fun `project search trims query ignores case and cannot expose historical editor entry`() {
        val projects = listOf(project("a", name = "Test A"), project("b", name = "测试项目"),
            project("hidden", name = "Test", mode = ProjectSourceMode.LUA))
        assertEquals(listOf("a"), searchStudioProjects(projects, "  TEST ").map { it.projectId })
        assertEquals(listOf("b"), searchStudioProjects(projects, "测试").map { it.projectId })
        assertEquals(listOf("a", "b"), searchStudioProjects(projects, " ").map { it.projectId })
        assertTrue(searchStudioProjects(projects, "missing").isEmpty())
    }

    @Test
    fun `project list sorts matching visual projects without changing input or access time`() {
        val entries = listOf(project("b", 20, name = "Beta"), project("a", 20, name = "alpha"),
            project("z", 30, name = "Zulu"), project("legacy", 100, name = "Alpha", mode = ProjectSourceMode.LUA))
        val original = entries.toList()
        assertEquals(listOf("z", "a", "b"), orderedStudioProjects(entries, "", ProjectListOrder.UPDATED).map { it.projectId })
        assertEquals(listOf("a", "b", "z"), orderedStudioProjects(entries, "", ProjectListOrder.NAME).map { it.projectId })
        assertEquals(listOf("a"), orderedStudioProjects(entries, " ALP ", ProjectListOrder.NAME).map { it.projectId })
        assertEquals(original, entries)
        assertTrue(entries.all { it.lastOpenedAt == null })
        assertTrue(orderedStudioProjects(entries, "不存在", ProjectListOrder.UPDATED).isEmpty())
    }

    @Test
    fun `project name sorting has stable ties and handles an empty library`() {
        val matching = listOf(project("b", name = "TEST"), project("a", name = "test"))
        assertEquals(listOf("a", "b"), orderedStudioProjects(matching, "", ProjectListOrder.NAME).map { it.projectId })
        for (order in ProjectListOrder.entries) assertTrue(orderedStudioProjects(emptyList(), "", order).isEmpty())
    }

    @Test
    fun `entry gate rejects duplicate stale and invalid requests`() {
        val gate = WorkspaceEntryGate()
        assertFalse(gate.claim(WorkspaceEntryRequest(0, WorkspaceEntryAction.IMPORT)))
        assertTrue(gate.claim(WorkspaceEntryRequest(2, WorkspaceEntryAction.CREATE)))
        assertFalse(gate.claim(WorkspaceEntryRequest(2, WorkspaceEntryAction.CREATE)))
        assertFalse(gate.claim(WorkspaceEntryRequest(1, WorkspaceEntryAction.EDIT, "p")))
        assertTrue(gate.claim(WorkspaceEntryRequest(3, WorkspaceEntryAction.EDIT, "p")))
    }

    @Test
    fun `profile exposes real local entries and home only draws its theme`() {
        assertEquals(5, profileStudioEntries.toSet().size)
        assertTrue(profileStudioEntries.containsAll(listOf(StudioEntry.PERMISSIONS, StudioEntry.ENVIRONMENT, StudioEntry.BACKUPS, StudioEntry.DOCS)))
        assertTrue(profileStudioEntries.contains(StudioEntry.ABOUT))
        assertFalse(StudioEntry.entries.any { it.title == "使用指南" })
        assertTrue(StudioEntry.entries.all { it.title.isNotBlank() && it.detail.isNotBlank() })
        val source = java.io.File("src/main/java/com/autoscript/studio/navigation/StudioLandingScreens.kt").readText()
        val home = source.substringAfter("internal fun StudioHomePage(").substringBefore("@Composable")
        assertTrue(home.contains("StudioLaunchCanvas(modifier, spaceState)"))
        assertFalse(home.contains("ProjectStore"))
        assertFalse(home.contains("RuntimeConnectionState"))
        assertFalse(home.contains("onEntry"))
        assertFalse(source.contains("private fun LandingRuntimeCard"))
    }

    @Test
    fun `profile sections contain each real settings entry exactly once`() {
        assertEquals(listOf("设备与运行", "项目与数据", "帮助与应用"), studioProfileSections.map { it.title })
        assertEquals(5, profileStudioEntries.size)
        assertEquals(5, profileStudioEntries.toSet().size)
        assertEquals(listOf(StudioEntry.PERMISSIONS, StudioEntry.ENVIRONMENT), studioProfileSections.first().entries)
        assertFalse(profileStudioEntries.any { it in listOf(StudioEntry.PROJECTS, StudioEntry.CREATE, StudioEntry.IMPORT) })
        assertTrue(profileStudioEntries.containsAll(listOf(StudioEntry.BACKUPS, StudioEntry.DOCS, StudioEntry.ABOUT)))
        assertEquals("版本号与应用更新", StudioEntry.ABOUT.detail)
    }

    @Test
    fun `about contains installed version and updater without old profile subpages`() {
        val source = java.io.File("src/main/java/com/autoscript/studio/navigation/StudioAboutScreen.kt").readText()
        assertTrue(source.contains("getPackageInfo(context.packageName, 0).versionName"))
        assertTrue(source.contains("BuildConfig.VERSION_CODE"))
        assertTrue(source.contains("StudioUpdateCard()"))
        for (removed in listOf("技术架构", "运行边界", "本地存储", "协议与隐私说明", "函数文档", "使用指南"))
            assertFalse(removed, source.contains(removed))
        assertFalse(java.io.File("src/main/java/com/autoscript/studio/navigation/ProfileScreens.kt").exists())
        val navigation = java.io.File("src/main/java/com/autoscript/studio/navigation/MainActivity.kt").readText()
        assertFalse(navigation.contains("ProfileSubpage"))
        assertTrue(navigation.contains("StudioAboutScreen(onBack = { aboutVisible = false }"))
    }

    @Test
    fun `connected runner does not imply root readiness`() {
        val labels = studioRuntimeLabels(RuntimeConnectionState(phase = RuntimeConnectionPhase.CONNECTED,
            rootState = RuntimeRootState.STOPPED, engineState = RuntimeEngineState.IDLE))
        assertEquals(StudioRuntimeLabels("已连接", "未启动", "空闲"), labels)
    }

    @Test
    fun `unknown failed and starting states remain honest`() {
        assertEquals(StudioRuntimeLabels("未连接", "待检查", "待检查"), studioRuntimeLabels(RuntimeConnectionState()))
        assertEquals(StudioRuntimeLabels("连接失败", "启动失败", "运行失败"), studioRuntimeLabels(RuntimeConnectionState(
            phase = RuntimeConnectionPhase.ERROR, rootState = RuntimeRootState.FAILED, engineState = RuntimeEngineState.FAILED)))
        assertEquals("启动中", studioRuntimeLabels(RuntimeConnectionState(rootState = RuntimeRootState.STARTING)).root)
    }

    @Test
    fun `execution distinguishes running paused and stopping`() {
        listOf(RuntimeEngineState.RUNNING to "运行中", RuntimeEngineState.PAUSED to "已暂停", RuntimeEngineState.STOPPING to "停止清理中").forEach { (state, label) ->
            assertEquals(label, studioRuntimeLabels(RuntimeConnectionState(phase = RuntimeConnectionPhase.CONNECTED, engineState = state)).execution)
        }
    }
}

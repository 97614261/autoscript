package com.autoscript.studio

import com.autoscript.project.store.ProjectSourceMode
import com.autoscript.project.store.ProjectStore
import com.autoscript.project.store.SourceGroup
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ProjectSourceFilesTest {
    private lateinit var root: File
    private lateinit var store: ProjectStore
    private lateinit var files: ProjectSourceFiles
    private var flowCounter = 0

    @Before
    fun setUp() {
        root = Files.createTempDirectory("autoscript-source-files-").toFile()
        store = ProjectStore(root, clock = { 1_000L }, idFactory = { "project-1" })
        files = ProjectSourceFiles(store, idFactory = { "flow-${++flowCounter}" })
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun `tree lists flows with display names and virtual groups`() {
        var snapshot = store.createProject("源文件", ProjectSourceMode.VISUAL)
        snapshot = files.createFlow(snapshot, "默认名称1")
        snapshot = files.createFlow(snapshot, "默认名称2", group = "新建分组1")

        val tree = files.load(snapshot)

        assertEquals(listOf("main", "默认名称1", "默认名称2"), tree.entries.map(SourceFileEntry::name))
        assertEquals(listOf("新建分组1"), tree.groups.map(SourceGroup::name))
        assertEquals(listOf("默认名称2"), tree.entriesIn("新建分组1").map(SourceFileEntry::name))
        assertEquals(listOf("main", "默认名称1"), tree.entriesIn(null).map(SourceFileEntry::name))
        assertTrue(tree.entry("main")!!.isEntry)
        assertTrue(File(snapshot.directory, "visual/flows/默认名称2.jsonl").isFile)
    }

    @Test
    fun `groups move members without touching files and dissolve on delete`() {
        var snapshot = store.createProject("分组", ProjectSourceMode.VISUAL)
        snapshot = files.createFlow(snapshot, "甲")
        snapshot = files.createFlow(snapshot, "乙")
        files.createGroup(snapshot, "空分组")
        files.addToGroup(snapshot, "甲组", listOf("flow-1", "flow-2"))
        files.addToGroup(snapshot, "乙组", listOf("flow-2"))

        var tree = files.load(snapshot)
        assertEquals(listOf("空分组", "甲组", "乙组"), tree.groups.map(SourceGroup::name))
        assertEquals("甲组", tree.entry("flow-1")!!.group)
        assertEquals("乙组", tree.entry("flow-2")!!.group)

        files.renameGroup(snapshot, "乙组", "丙组")
        files.removeFromGroup(snapshot, listOf("flow-1"))
        files.deleteGroups(snapshot, listOf("空分组"))
        tree = files.load(snapshot)
        assertEquals(listOf("甲组", "丙组"), tree.groups.map(SourceGroup::name))
        assertNull(tree.entry("flow-1")!!.group)
        assertEquals("丙组", tree.entry("flow-2")!!.group)
        assertTrue(File(snapshot.directory, "visual/flows/甲.jsonl").isFile)
        assertTrue(File(snapshot.directory, "visual/flows/乙.jsonl").isFile)
    }

    @Test
    fun `delete reports entry and referenced flows as failures`() {
        var snapshot = store.createProject("删除", ProjectSourceMode.VISUAL)
        snapshot = files.createFlow(snapshot, "被调用")
        snapshot = files.createFlow(snapshot, "闲置")
        val main = snapshot.flowSources.getValue("main").replace(
            "\"kind\":\"task.noop\",\"nodeVersion\":1,\"depth\":0,\"args\":{}",
            "\"kind\":\"flow.call\",\"nodeVersion\":1,\"depth\":0,\"args\":{\"targetFlowId\":\"flow-1\",\"arguments\":{}}",
        )
        snapshot = store.saveFlow(snapshot.manifest.projectId, "main", main, snapshot.flowSources.getValue("main"))

        assertEquals(listOf("flow-2"), files.unreferencedFlows(snapshot).map { it.flowId })

        val result = files.deleteFlows(snapshot, listOf("main", "flow-1", "flow-2"))

        assertEquals(listOf("flow-2"), result.deleted)
        assertEquals(2, result.failures.size)
        assertTrue(result.failures[0].startsWith("main："))
        assertTrue(result.failures[1].startsWith("被调用："))
        assertEquals(setOf("main", "flow-1"), result.snapshot.flowSources.keys)
    }

    @Test
    fun `copy and rename keep identities apart`() {
        var snapshot = store.createProject("副本", ProjectSourceMode.VISUAL)
        snapshot = files.copyFlow(snapshot, "main", "main_副本")
        snapshot = files.renameFlow(snapshot, "flow-1", "主流程副本")

        val tree = files.load(snapshot)
        assertEquals(listOf("main", "主流程副本"), tree.entries.map(SourceFileEntry::name))
        assertEquals(listOf("main", "flow-1"), tree.entries.map(SourceFileEntry::flowId))
    }
}

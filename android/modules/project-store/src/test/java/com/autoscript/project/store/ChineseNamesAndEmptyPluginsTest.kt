package com.autoscript.project.store

import java.io.ByteArrayOutputStream
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class ChineseNamesAndEmptyPluginsTest {
    @Test fun namesAreExactBoundedKeysAndRenamePreservesStrings() {
        for(name in listOf("剩余次数","目标图_1","㐀","字".repeat(64))) assertTrue(ProjectNames.valid(name))
        for(name in listOf("","2次数","变 量","a.b","😀","a\u200b","字".repeat(65))) assertFalse(ProjectNames.valid(name))
        assertEquals("剩余次数 - 1 + len(\"次数\")",rewriteCalculationNames("次数 - 1 + len(\"次数\")",mapOf("次数" to "剩余次数")))
    }
    @Test fun newMainAndAdditionalPluginsStayEmptyAcrossCopyBackupRestoreAndRecovery() {
        val root=Files.createTempDirectory("empty-plugins-").toFile()
        try {
            val store=ProjectStore(root)
            var project=store.createProject("空项目",ProjectSourceMode.VISUAL)
            val id=project.manifest.projectId
            assertEquals("",project.flowSources.getValue("main"))
            project=store.createFlow(id,"child",setOf("main"),fileName="中文插件")
            assertTrue(project.flowSources.values.all {it.isEmpty()})
            val variable=ProjectVariable("剩余次数",ProjectVariableScope.GLOBAL,type=ProjectVariableType.INTEGER)
            project=store.updateVariables(id,listOf(variable),emptyList())
            assertEquals(variable,store.openProject(id).manifest.variables.single())
            project=store.copyFlow(id,"child","copy","副本",project.flowSources.keys)
            assertTrue(project.flowSources.values.all {it.isEmpty()})
            assertEquals(3,project.manifest.flows.map {it.rootBlockId}.toSet().size)
            val backup=ByteArrayOutputStream();store.exportProjectBackup(id,backup)
            val restored=store.importProjectBackup(backup.toByteArray().inputStream())
            assertEquals(project.flowSources,restored.flowSources)
            val file=project.directory.resolve(project.manifest.flows.first().path)
            assertTrue(file.renameTo(file.resolveSibling(file.name+".previous")))
            assertEquals(project.flowSources,store.openProject(id).flowSources)
        } finally {root.deleteRecursively()}
    }
}

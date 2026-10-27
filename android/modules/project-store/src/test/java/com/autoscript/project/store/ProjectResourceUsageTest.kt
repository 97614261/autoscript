package com.autoscript.project.store

import com.autoscript.script.ui.ScriptUiDefinition
import com.autoscript.script.ui.UiField
import com.autoscript.script.ui.UiPresentation
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.CancellationException

class ProjectResourceUsageTest {
    private val path = "assets/images/templates/目标.png"
    private fun node(args: String, id: String = "node-1") =
        """{"args":$args,"nodeId":"$id","kind":"vision.findimage"}"""
    private fun snapshot(source: String) = ProjectSnapshot(File("."), ProjectManifestDocument(
        projectId = "project-usage", name = "引用检查", sourceMode = ProjectSourceMode.VISUAL,
        flows = listOf(ProjectFlow("main", "visual/flows/主插件.jsonl", "block-1"))),
        luaSource = null, flowSources = mapOf("main" to source))

    @Test fun savedAndChangedDraftKeepLineIdentityAndParameterLocations() {
        val source = "\n" + node("""{"templates":["$path"],"nested":{"imageDirectory":"assets/images/templates/"}}""")
        val snapshot = snapshot(source)
        val report = scanResourceUsages(snapshot, path, mapOf("main" to node("""{"template":"$path"}""", "node-2")), emptyList())
        assertEquals(3, report.totalCount)
        assertEquals(listOf(ResourceUsageSource.SAVED_FLOW, ResourceUsageSource.SAVED_FLOW, ResourceUsageSource.DRAFT_FLOW), report.usages.map { it.source })
        assertEquals(2, report.usages.first().line)
        assertEquals("node-1", report.usages.first().nodeId)
        assertEquals("args.templates[0]", report.usages.first().parameter)
        assertTrue(report.usages[1].viaDirectory)
        assertEquals("node-2", report.usages.last().nodeId)
        assertEquals(2, scanResourceUsages(snapshot, path, mapOf("main" to source), emptyList()).totalCount)
        assertEquals(2, scanResourceUsages(snapshot, path, mapOf("main" to ""), emptyList()).totalCount)
    }

    @Test fun directoryBoundaryAndNonArgumentTextDoNotCountAsUses() {
        val source = node("""{"imageDirectory":"assets/images/template/","name":"$path.bak"}""")
            .dropLast(1) + """, "display":{"alias":"$path"}}"""
        assertEquals(0, scanResourceUsages(snapshot(source), path, emptyMap(), emptyList()).totalCount)
    }

    @Test fun interfaceAndRecipeReferencesDescribeRealOwners() {
        val ui = ScriptUiDefinition(version = 2, fields = listOf(UiField("logo", "提示图片", "text", false, path,
            ui = UiPresentation(control = "IMAGE"))))
        val snapshot = snapshot("").let { it.copy(manifest = it.manifest.copy(runnerUi = ui.toJson())) }
        val recipe = ProjectImageRecipe("战斗图标", 10, 10, emptyList(), ProjectImageRegion(0, 0, 10, 10),
            ProjectImageRecipeTail("vision.findimage", """{"template":"$path","tolerance":0,"similarityPermille":900,"direction":0}"""))
        val report = scanResourceUsages(snapshot, path, emptyMap(), listOf(recipe))
        assertEquals(2, report.totalCount)
        assertEquals(ResourceUsageSource.INTERFACE, report.usages.first().source)
        assertTrue(report.usages.first().location.contains("提示图片（logo）"))
        assertEquals("战斗图标", report.usages.last().location)
        assertEquals(ResourceUsageSource.IMAGE_RECIPE, report.usages.last().source)
    }

    @Test fun historicalLuaIsConservativeAndLabelsActualFileAndLine() {
        val snapshot = snapshot("").let { it.copy(manifest = it.manifest.copy(sourceMode = ProjectSourceMode.LUA),
            flowSources = emptyMap(), luaSources = mapOf("main.lua" to "-- $path\nprint('$path')")) }
        val report = scanResourceUsages(snapshot, path, emptyMap(), emptyList())
        assertEquals(2, report.totalCount)
        assertEquals(listOf(1, 2), report.usages.map { it.line })
        assertEquals(ResourceUsageSource.LUA, report.usages.first().source)
    }

    @Test fun invalidOrOverBudgetInputNeverBecomesAnUnusedReport() {
        for (source in listOf("{broken", "[]", "{}", node("{}") + "\n{broken", node("[]"),
            node("""{"template":"$path"}""") + " trailing", node("[".repeat(65) + "0" + "]".repeat(65)),
            node("""{"${"x".repeat(2048)}":"$path"}"""),
            node("{}", "x".repeat(129)),
            node("""{"text":"${"x".repeat(1024 * 1024)}"}"""))) {
            assertThrows(Exception::class.java) { scanResourceUsages(snapshot(source), path, emptyMap(), emptyList()) }
        }
        assertThrows(IllegalArgumentException::class.java) { scanResourceUsages(snapshot(""), path, mapOf("removed" to ""), emptyList()) }
    }

    @Test fun truncatedReportStillCountsAllReferencesAndCanBeCancelled() {
        val source = List(520) { node("""{"template":"$path"}""", "node-$it") }.joinToString("\n")
        val report = scanResourceUsages(snapshot(source), path, emptyMap(), emptyList())
        assertEquals(520, report.totalCount)
        assertEquals(512, report.usages.size)
        assertTrue(report.truncated)
        val singleNode = node("""{"templates":[${List(600) { "\"$path\"" }.joinToString(",") }]}""")
        val repeated = scanResourceUsages(snapshot(singleNode), path, emptyMap(), emptyList())
        assertEquals(600, repeated.totalCount)
        assertEquals(512, repeated.usages.size)
        assertThrows(CancellationException::class.java) {
            scanResourceUsages(snapshot(source), path, emptyMap(), emptyList()) { throw CancellationException() }
        }
    }
}

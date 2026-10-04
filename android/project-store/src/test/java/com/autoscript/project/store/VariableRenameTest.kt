package com.autoscript.project.store

import com.autoscript.script.ui.ScriptUiDefinition
import com.autoscript.script.ui.UiField
import com.autoscript.script.ui.UiPresentation
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class VariableRenameTest {
    private lateinit var root: File
    private lateinit var store: ProjectStore
    @Before fun setup() { root = Files.createTempDirectory("variable-rename-").toFile(); store = ProjectStore(root, idFactory = { "project-rename" }) }
    @After fun cleanup() { root.deleteRecursively() }
    private fun global(name: String) = ProjectVariable(name, ProjectVariableScope.GLOBAL, type = ProjectVariableType.INTEGER)
    private fun local(name: String, flow: String = "main") = global(name).copy(scope = ProjectVariableScope.FLOW, flowId = flow)
    private fun create(vars: List<ProjectVariable>): ProjectSnapshot {
        val base = store.createProject("改名", ProjectSourceMode.VISUAL)
        return store.updateVariables(base.manifest.projectId, vars, emptyList())
    }
    private fun source(snapshot: ProjectSnapshot, args: JsonObject, kind: String = "variable.calculate"): String =
        JsonParser.parseString(snapshot.flowSources.getValue("main").trim()).asJsonObject.apply {
            addProperty("kind", kind); add("args", args)
        }.toString() + "\n"
    private fun calculate(snapshot: ProjectSnapshot, name: String, expression: String) = source(snapshot, JsonObject().apply {
        addProperty("name", name); addProperty("expression", expression)
    })

    @Test fun renamesStructuredReferencesWithoutChangingLiteralsOrFunctionCalls() {
        val old = local("count")
        var base = create(listOf(old))
        val text = calculate(base, "count", "count + len(\"count\")")
        base = store.saveFlow(base.manifest.projectId, "main", text, base.flowSources.getValue("main"))
        val updated = store.updateVariables(base.manifest.projectId, listOf(old.copy(name = "total")), listOf(old), listOf(ProjectVariableRename(old,"total")), expectedFlowSources = base.flowSources)
        val args = JsonParser.parseString(updated.flowSources.getValue("main").trim()).asJsonObject.getAsJsonObject("args")
        assertEquals("total", args.get("name").asString)
        assertEquals("total + len(\"count\")", args.get("expression").asString)
        assertFalse(File(base.directory, ".studio/variable-transaction.json").exists())
        assertEquals(updated.flowSources, store.openProject(base.manifest.projectId).flowSources)
    }

    @Test fun dynamicMessageRenamePreservesLiteralsFunctionsAndSavedChannel() {
        val old = local("count")
        var base = create(listOf(old))
        val draft = source(base, JsonObject().apply {
            addProperty("expression", "\"count:\" .. text(count)")
            addProperty("channel", "popup")
        }, "task.message")
        base = store.saveFlow(base.manifest.projectId, "main", draft, base.flowSources.getValue("main"))
        val updated = store.updateVariables(base.manifest.projectId, listOf(old.copy(name = "total")), listOf(old),
            listOf(ProjectVariableRename(old, "total")), expectedFlowSources = base.flowSources)
        val args = JsonParser.parseString(updated.flowSources.getValue("main").trim()).asJsonObject.getAsJsonObject("args")
        assertEquals("\"count:\" .. text(total)", args.get("expression").asString)
        assertEquals("popup", args.get("channel").asString)
        assertEquals(updated.flowSources, store.openProject(base.manifest.projectId).flowSources)
        val shadowed = rewriteFlowVariableReferences(draft, emptyMap())
        assertEquals(draft, shadowed)
    }

    @Test fun globalRenameSkipsShadowedPluginAndKeepsLocalDeclaration() {
        val vars = listOf(global("count"),local("count"))
        var base = create(vars)
        val text = calculate(base, "count", "count + 1")
        base = store.saveFlow(base.manifest.projectId, "main", text, base.flowSources.getValue("main"))
        val updated = store.updateVariables(base.manifest.projectId,listOf(global("total"),local("count")),vars,listOf(ProjectVariableRename(vars[0],"total")))
        assertEquals(text, updated.flowSources.getValue("main"))
    }

    @Test fun rejectsNewNameCaptureBeforeChangingAnyFiles() {
        val vars = listOf(global("count"),local("total"))
        val base = create(vars)
        val original = File(base.directory,"project.json").readText()
        assertThrows(IllegalArgumentException::class.java) {
            store.updateVariables(base.manifest.projectId,listOf(global("total"),local("total")),vars,listOf(ProjectVariableRename(vars[0],"total")))
        }
        assertEquals(original,File(base.directory,"project.json").readText())
        assertEquals(base.flowSources, store.openProject(base.manifest.projectId).flowSources)
    }

    @Test fun updatesUiBindingAndSubmittedDraftWithoutLosingItsOtherChanges() {
        val old = local("count")
        var base = create(listOf(old))
        val ui = ScriptUiDefinition(version = 2,fields = listOf(UiField("field","次数","integer",false,"3",ui = UiPresentation(control="NUMBER",binding="flow:main:count"))))
        base = store.updateRunnerUi(base.manifest.projectId,ui.toJson(),null)
        val draft = calculate(base,"count","count + 99")
        val updated = store.updateVariables(base.manifest.projectId,listOf(old.copy(name="total")),listOf(old),listOf(ProjectVariableRename(old,"total")),mapOf("main" to draft),base.flowSources)
        assertTrue(updated.flowSources.getValue("main").contains("total + 99"))
        assertTrue(updated.manifest.runnerUi.toString().contains("flow:main:total"))
        assertFalse(updated.manifest.runnerUi.toString().contains("flow:main:count"))
    }

    @Test fun stalePluginVersionCannotCommitRename() {
        val old = local("count")
        val base = create(listOf(old))
        store.saveFlow(base.manifest.projectId,"main",calculate(base,"count","count + 1"),base.flowSources.getValue("main"))
        assertThrows(ProjectManifestConflictException::class.java) {
            store.updateVariables(base.manifest.projectId,listOf(old.copy(name="total")),listOf(old),listOf(ProjectVariableRename(old,"total")),expectedFlowSources=base.flowSources)
        }
        assertEquals(listOf(old),store.openProject(base.manifest.projectId).manifest.variables)
    }

    @Test fun recoversInterruptedTransactionBeforeOpeningMixedDeclarations() {
        val old = local("count")
        val base = create(listOf(old))
        val before = JsonObject().apply {
            addProperty("project.json",File(base.directory,"project.json").readText())
            addProperty("visual/flows/main.jsonl",base.flowSources.getValue("main"))
        }
        val journal=File(base.directory,".studio/variable-transaction.json")
        requireNotNull(journal.parentFile).mkdirs()
        journal.writeText(JsonObject().apply { addProperty("version",1); add("before",before) }.toString())
        File(base.directory,"visual/flows/main.jsonl").writeText(calculate(base,"total","total+1"))
        val restored=store.openProject(base.manifest.projectId)
        assertEquals(base.flowSources,restored.flowSources)
        assertEquals(listOf(old),restored.manifest.variables)
        assertFalse(journal.exists())
    }

    @Test fun nestedReferencesAndExpressionStringEscapesRemainExact() {
        val args = JsonObject().apply {
            addProperty("message","count")
            add("left",JsonObject().apply { addProperty("variable","count") })
            addProperty("frameVariable","count")
        }
        val line=JsonObject().apply { addProperty("kind","control.if"); add("args",args) }.toString()
        val rewritten=JsonParser.parseString(rewriteFlowVariableReferences(line,mapOf("count" to "total"))).asJsonObject.getAsJsonObject("args")
        assertEquals("count",rewritten.get("message").asString)
        assertEquals("total",rewritten.getAsJsonObject("left").get("variable").asString)
        assertEquals("total",rewritten.get("frameVariable").asString)
        assertEquals("len(total) + \"count\\\"count\"",rewriteCalculationNames("len(count) + \"count\\\"count\"",mapOf("count" to "total","len" to "length")))
    }
}

package com.autoscript.studio

import com.autoscript.project.store.*
import com.autoscript.runtime.api.RuntimeDebugValue
import com.google.gson.JsonObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class EditorBatchTwoTest {
    @Test
    fun recognitionCreatesCaptureMatchReleaseWithSameFrameAndExactParameters() {
        val args = recognitionBlockArguments("assets/images/button.png", 8, 950, 1080, 2400)
        val blocks = recognitionBlockSequence(args)
        assertEquals(listOf("screen.capture", "vision.findimage", "screen.release"), blocks.map { it.first.kind })
        assertEquals("frame", blocks.first().second.get("resultVariable").asString)
        assertEquals("frame", blocks.last().second.get("frameVariable").asString)
        assertEquals(2400, args.getAsJsonObject("region").get("bottom").asInt)
        assertEquals(950, blocks[1].second.get("similarityPermille").asInt)
        args.addProperty("tolerance", 0)
        assertEquals(8, blocks[1].second.get("tolerance").asInt)
    }

    @Test
    fun backfillChecksTypeAndRemovesExistingVariableBindingWithoutChangingOriginal() {
        val log = requireNotNull(BlockCatalog.find("task.log"))
        val field = backfillFields(log).single { it.path == "message" }
        val args = JsonObject().apply { addProperty("message", "old"); addProperty("valueVariable", "oldVar") }
        val filled = backfillArguments(args, field, RuntimeDebugValue("local", "text", "string", "new"))
        assertEquals("new", filled.get("message").asString)
        assertFalse(filled.has("valueVariable"))
        assertTrue(args.has("valueVariable"))
        val x = backfillFields(BlockCatalog.find("input.tap")).single { it.path == "x" }
        assertTrue(runCatching { backfillArguments(args, x, RuntimeDebugValue("local", "x", "string", "1")) }.isFailure)
        assertTrue(runCatching { backfillArguments(args, field, RuntimeDebugValue("local", "text", "string", "partial", true)) }.isFailure)
        assertEquals(12L, backfillArguments(JsonObject(), x, RuntimeDebugValue("local", "x", "integer", "12")).get("x").asLong)
    }

    @Test
    fun warningsFindImplicitReferencesAndWritesDoNotInitializeSiblingBranches() {
        fun node(id: String, block: String, order: String, kind: String, args: String, parent: String? = null, children: String = "") =
            """{"flowSchemaVersion":1,"nodeId":"$id","blockId":"$block","parentId":${parent?.let { "\"$it\"" } ?: "null"},"orderKey":"$order","kind":"$kind","nodeVersion":1,$children"args":$args}"""
        val source = listOf(
            node("if", "root", "a0", "control.if", """{"variable":"flag","operator":"equals","value":1}""", children = "\"childBlocks\":{\"then\":\"then\",\"else\":\"else\"},"),
            node("write", "then", "a0", "variable.set", """{"name":"score","value":10}""", "if"),
            node("read", "else", "a0", "task.log", """{"message":"score","valueVariable":"score","level":"info"}""", "if"),
        ).joinToString("\n")
        val snapshot = ProjectSnapshot(directory = File("."), manifest = ProjectManifestDocument(projectId = "test", name = "test", sourceMode = ProjectSourceMode.VISUAL,
            entryFlowId = "main", flows = listOf(ProjectFlow("main", "visual/flows/main.jsonl", "root"))), luaSource = null, flowSources = mapOf("main" to source))
        val warnings = visualVariableWarnings(VisualEditorState.create(source, "root"), snapshot, "main")
        assertTrue(warnings.any { it.contains("read · score：使用前可能未赋值") })
        assertTrue(warnings.any { it.contains("flag：未维护到变量表") })
    }

    @Test
    fun singleStepOverridesNormalDebugModeRatherThanBeingOverriddenByItsPrefix() {
        val plan = RuntimeProjectPlan("__autoscript_debug_capture = true\n__autoscript_debug_step = false\nreturn function() end".toByteArray(), emptyList(), emptyList(), 720, 1280, 0)
        val source = plan.forSingleStep().luaSource.toString(Charsets.UTF_8)
        assertTrue(source.contains("__autoscript_debug_step = true"))
        assertFalse(source.contains("__autoscript_debug_step = false"))
    }
}

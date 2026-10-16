package com.autoscript.studio

import com.autoscript.studio.compiler.VisualLuaSource
import com.autoscript.studio.compiler.VisualSourceException
import com.google.gson.JsonArray
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class VisualLuaRoundTripTest {
    private val tool get() = pluginSourceToolchain
    private fun node(kind: String, args: JsonObject = JsonObject(), id: String = "test", block: String = "root",
        parent: String? = null, children: Map<String, String> = emptyMap(), depth: Int = 0): JsonObject = JsonObject().apply {
        addProperty("flowSchemaVersion", 1); addProperty("nodeId", id); addProperty("blockId", block)
        add("parentId", parent?.let { com.google.gson.JsonPrimitive(it) } ?: JsonNull.INSTANCE)
        addProperty("orderKey", "A"); addProperty("kind", kind)
        addProperty("nodeVersion", BlockCatalog.find(kind)!!.nodeVersion); addProperty("depth", depth)
        add("args", args)
        if (children.isNotEmpty()) add("childBlocks", JsonObject().apply { children.forEach { (k, v) -> addProperty(k, v) } })
    }
    private fun jsonl(vararg nodes: JsonObject) = nodes.joinToString("\n", postfix = "\n")
    private fun objects(text: String) = text.lineSequence().filter { it.isNotBlank() }.map { JsonParser.parseString(it) }.toList()
    private fun failure(text: String, baseline: String = ""): VisualSourceException {
        try { tool.parse(text, baseline, "root", setOf("x", "y", "count")); fail("Expected source rejection") }
        catch (problem: VisualSourceException) { return problem }
        error("unreachable")
    }

    @Test fun everyRegisteredContractHasAnExactReversibleRepresentation() {
        assertTrue(BlockCatalog.all.size > 50)
        val names = BlockCatalog.all.map { VisualLuaSource.name(it.kind) }
        assertEquals(names.size, names.distinct().size)
        BlockCatalog.all.forEach { contract ->
            val args = JsonObject().apply {
                contract.properties.forEach { p -> p.defaultValue?.let { addProperty(p.path, it) } }
                // Preserve opaque nested argument values as well as all known properties.
                add("fixture", JsonParser.parseString("{\"emptyArray\":[],\"emptyObject\":{},\"null\":null,\"values\":[true,false,1,-2,0.5,\"汉字\\n\\u0001\\\"\\\\\"]}"))
            }
            val original = node(contract.kind, args, children = contract.childBlocks.associateWith { "child-$it" })
            original.addProperty("disabled", true)
            val baseline = jsonl(original)
            val exported = tool.export(baseline, "root")
            val imported = tool.parse(exported.text, baseline, "root", emptySet())
            assertEquals(contract.kind, objects(baseline), objects(imported.text))
            assertEquals(setOf("test"), imported.nodes.keys)
            assertTrue(imported.nodes.getValue("test").endLine >= imported.nodes.getValue("test").line)
        }
    }

    @Test fun nestedBranchesLoopParametersAndIdentitySurviveRoundTrip() {
        val conditional = node("control.if", JsonParser.parseString("""{"variable":"x","operator":"equals","value":1,"condition":{"type":"not","condition":{"type":"compare","variable":"x","operator":"equals","value":0}},"elseIf":[{"variable":"y","operator":"equals","value":2}]}""").asJsonObject,
            id = "if", children = mapOf("then" to "yes", "elseIf0" to "alternate", "else" to "no"))
        val repeat = node("control.repeat", JsonParser.parseString("""{"times":3,"indexVariable":"count","elapsedVariable":"y"}""").asJsonObject,
            id = "repeat", block = "yes", parent = "if", children = mapOf("body" to "loop"), depth = 1)
        val inside = node("input.tap", JsonParser.parseString("""{"x":10,"y":20}""").asJsonObject, "tap", "loop", "repeat", depth = 2)
        val alternate = node("task.comment", JsonParser.parseString("""{"message":"otherwise"}""").asJsonObject, "comment", "alternate", "if", depth = 1)
        val baseline = jsonl(conditional, repeat, inside, alternate)
        val exported = tool.export(baseline, "root")
        assertTrue(exported.text.contains("elseif Condition("))
        assertTrue(exported.text.contains("for _ = 1, Repeat("))
        val parsed = tool.parse(exported.text, baseline, "root", setOf("count", "x", "y"))
        assertEquals(objects(baseline), objects(parsed.text))
        assertEquals(2, JsonParser.parseString(parsed.text.lineSequence().elementAt(2)).asJsonObject["depth"].asInt)
    }

    @Test fun modifyingSourceActuallyModifiesTypedArgumentsAndIsUndoable() {
        val original = node("input.tap", JsonParser.parseString("""{"x":10,"y":20}""").asJsonObject)
        val baseline = jsonl(original)
        val lua = tool.export(baseline, "root").text.replace("[\"x\"] = 10", "[\"x\"] = 42")
        val converted = tool.parse(lua, baseline, "root", emptySet())
        assertEquals(42, JsonParser.parseString(converted.text.trim()).asJsonObject["args"].asJsonObject["x"].asInt)
        val editor = VisualEditorState.create(baseline, "root")
        assertNull(editor.replaceRawSource(baseline, converted.text))
        assertTrue(editor.undo()); assertEquals(baseline, editor.currentSource)
        assertTrue(editor.redo()); assertEquals(converted.text, editor.currentSource)
    }

    @Test fun nativeAssignmentConditionsLoopsAndJumpMapWithoutExecutingLua() {
        val source = """
            -- a real comment block
            x = 3
            y = x
            count = count + 1
            if x >= y and not (count == 0) then
              for _ = 1, 3 do
                Input.Tap({x=10,y=20})
                break
              end
            elseif x == 0 then
              Task.Comment({message="zero"})
            else
              while x < 10 do
                x = x + 1
              end
            end
            ::done::
            goto done
        """.trimIndent()
        val parsed = tool.parse(source, "", "root", setOf("x", "y", "count"))
        val rows = objects(parsed.text).map { it.asJsonObject }
        assertEquals(listOf("task.comment", "variable.set", "variable.copy", "variable.calculate", "control.if", "control.repeat", "input.tap", "control.break", "task.comment", "control.while", "variable.calculate", "control.label", "control.goto"), rows.map { it["kind"].asString })
        assertEquals("all", rows[4]["args"].asJsonObject["condition"].asJsonObject["type"].asString)
        assertEquals(10000, rows[9]["args"].asJsonObject["maxIterations"].asInt)
        assertEquals("done", rows.last()["args"].asJsonObject["name"].asString)
        val exported = tool.export(parsed.text, "root")
        assertEquals(objects(parsed.text), objects(tool.parse(exported.text, parsed.text, "root", setOf("x", "y", "count")).text))
    }

    @Test fun dynamicClosureUnknownFunctionsAndSilentLossAreRejectedWithLocation() {
        listOf("os.execute({})", "Input.Tap({x=os.time(),y=1})", "local x = 1", "function test() end", "return 1",
            "Input.Tap({x=1,x=2})", "Input.Tap({}) unknown()", "for i = 1, 3 do end", "if x then end", "x = nil",
            "Task.Comment({message=\"unterminated})", "Input.Tap({})\nrequire(\"hidden\")").forEach { text ->
            val problem = failure(text)
            assertTrue(text, problem.location.line >= 1)
            assertTrue(text, problem.location.column >= 1)
        }
        assertEquals(2, failure("Input.Tap({})\nos.execute({})").location.line)
        assertTrue(failure("undeclared = 3").message!!.contains("尚未声明"))
    }

    @Test fun duplicateForeignAndChangedIdentityCannotDiscardMetadata() {
        val baseline = jsonl(node("input.tap"))
        val source = tool.export(baseline, "root").text
        assertTrue(failure(source + source, baseline).message!!.contains("重复"))
        assertTrue(failure(source.replace("\"test\"", "\"foreign\""), baseline).message!!.contains("不属于"))
        assertTrue(failure(source.replace("Input.Tap", "Task.Comment"), baseline).message!!.contains("类型"))
        assertTrue(failure("Input.Tap({}, {[\"body\"]=function() end})").message!!.contains("子区"))
    }

    @Test fun emptyProgramAndExplicitEmptyBranchArrayRemainReversible() {
        assertEquals("", tool.parse(tool.export("", "root").text, "", "root", emptySet()).text)
        val baseline = jsonl(node("control.if", JsonParser.parseString("""{"variable":"x","operator":"equals","value":0,"elseIf":[]}""").asJsonObject,
            children = mapOf("then" to "a", "else" to "b")))
        assertEquals(objects(baseline), objects(tool.parse(tool.export(baseline, "root").text, baseline, "root", setOf("x")).text))
    }

    @Test fun resourceLimitsAndCancellationAreExplicit() {
        assertTrue(failure("x".repeat(VisualLuaSource.MAX_BYTES + 1)).message!!.contains("1 MiB"))
        assertTrue(failure("Task.Comment({message=" + "{[\"a\"]=".repeat(40) + "1" + "}".repeat(40) + "})").message!!.contains("32"))
        assertTrue(failure("Task.Comment({message=\"a\"})\n".repeat(4097)).message!!.contains("4096"))
        assertTrue(failure("if " + List(100) { "x == 1" }.joinToString(" and ") + " then end").message!!.contains("超过"))
        assertTrue(failure("x = 01").message!!.contains("十进制"))
        assertTrue(failure("x = 1.").message!!.contains("十进制"))
        var checks = 0
        try {
            tool.parse("Task.Comment({message=\"a\"})\n".repeat(100), "", "root", emptySet()) {
                if (++checks > 2) throw java.util.concurrent.CancellationException()
            }
            fail("Cancellation should propagate")
        } catch (_: java.util.concurrent.CancellationException) { assertTrue(checks > 2) }
    }

    @Test fun inlineCommentsArePreservedAsCommentBlocks() {
        val result = objects(tool.parse("x = 1 -- assigned\nInput.Tap({x=1,y=2}) -- click\n", "", "root", setOf("x")).text)
        assertEquals(listOf("variable.set", "task.comment", "input.tap", "task.comment"), result.map { it.asJsonObject["kind"].asString })
        assertEquals("click", result.last().asJsonObject["args"].asJsonObject["message"].asString)
    }

    @Test fun removingAndReorderingStatementsRebuildsTreeNotOriginalSource() {
        val a = node("task.comment", JsonObject().apply { addProperty("message", "a") }, "a")
        val b = node("task.comment", JsonObject().apply { addProperty("message", "b") }, "b").apply { addProperty("orderKey", "B") }
        val baseline = jsonl(a, b)
        val source = tool.export(baseline, "root").text.lines()
        val reverse = listOf(source[3], source[4], source[1], source[2]).joinToString("\n")
        val result = objects(tool.parse(reverse, baseline, "root", emptySet()).text)
        assertEquals(listOf("b", "a"), result.map { it.asJsonObject["nodeId"].asString })
        assertEquals("", tool.parse("--! empty\n", baseline, "root", emptySet()).text)
    }
}

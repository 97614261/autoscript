package com.autoscript.studio

import com.autoscript.studio.compiler.NodeDisplaySettings
import com.autoscript.studio.compiler.VisualSourceException
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class NodeDisplayTest {
    private fun nodes(source: String) = source.lineSequence().filter(String::isNotBlank)
        .map { JsonParser.parseString(it).asJsonObject }.toList()
    private fun editor() = VisualEditorState.create("", "root")
    private fun add(editor: VisualEditorState, kind: String = "task.comment", slot: String? = null): String =
        requireNotNull(editor.insertBlock(requireNotNull(BlockCatalog.find(kind)), JsonObject().apply {
            if (kind == "control.repeat") addProperty("times", 2) else addProperty("message", "正在准备")
        }, intoChildBlockName = slot))

    @Test fun validatesTypesControlsAndUnicodeLimit() {
        assertTrue(NodeDisplaySettings("😀".repeat(80)).isValid())
        assertFalse(NodeDisplaySettings("😀".repeat(81)).isValid())
        assertFalse(NodeDisplaySettings("换\n行").isValid())
        assertFalse(NodeDisplaySettings("\u0085").isValid())
        assertFalse(NodeDisplaySettings("\uD800").isValid())
        listOf("null", "{}", "{\"alias\":2,\"showParameters\":true}",
            "{\"alias\":\"ok\",\"showParameters\":\"true\"}",
            "{\"alias\":\"ok\",\"showParameters\":true,\"extra\":0}").forEach {
            assertTrue(runCatching { NodeDisplaySettings.parse(JsonParser.parseString(it)) }.isFailure)
        }
        val settings = NodeDisplaySettings("开始准备", false)
        assertEquals(settings, NodeDisplaySettings.parse(settings.toJson()))
    }

    @Test fun displayIsUndoablePersistedCopiedAndIndependentOfArguments() {
        val editor = editor()
        val id = add(editor)
        val baseline = editor.currentSource
        val original = nodes(baseline).single()
        val settings = NodeDisplaySettings("我的准备步骤", false)
        assertTrue(editor.updateNodeDisplay(id, settings))
        val changed = editor.currentSource
        assertEquals(original, nodes(changed).single().deepCopy().apply { remove("display") })
        assertTrue(editor.undo()); assertEquals(baseline, editor.currentSource)
        assertTrue(editor.redo()); assertEquals(changed, editor.currentSource)
        assertEquals(settings, VisualEditorState.create(changed, "root").nodeDisplay(id))
        editor.selectOnly(id)
        val copied = requireNotNull(editor.pasteSubtree(requireNotNull(editor.copySelectedSubtree())))
        assertNotEquals(id, copied)
        assertEquals(settings, editor.nodeDisplay(copied))
        assertTrue(editor.updateNodeDisplay(copied, NodeDisplaySettings()))
        assertFalse(nodes(editor.currentSource).first { it["nodeId"].asString == copied }.has("display"))
        assertEquals(settings, editor.nodeDisplay(id))
    }

    @Test fun overBudgetDisplayEditIsAtomicAndInvalidMetadataRemainsReadOnly() {
        val records = (0 until 480).map { index -> JsonObject().apply {
            addProperty("flowSchemaVersion", 1); addProperty("nodeId", "node-$index")
            addProperty("blockId", "root"); add("parentId", com.google.gson.JsonNull.INSTANCE)
            addProperty("orderKey", "A" + index.toString().padStart(4, '0'))
            addProperty("kind", "task.comment"); addProperty("nodeVersion", 1); addProperty("depth", 0)
            add("args", JsonObject().apply { addProperty("message", "m") })
        } }
        fun serialize() = records.joinToString("\n", postfix = "\n")
        val maximum = com.autoscript.studio.compiler.VisualLuaSource.MAX_BYTES
        var remaining = maximum - 40 - serialize().toByteArray(Charsets.UTF_8).size
        records.forEach { node ->
            val extra = remaining.coerceIn(0, 2047)
            node["args"].asJsonObject.addProperty("message", "m".repeat(extra + 1))
            remaining -= extra
        }
        assertEquals(0, remaining)
        val baseline = serialize()
        val editor = VisualEditorState.create(baseline, "root")
        assertFalse(editor.isReadOnly)
        assertFalse(editor.updateNodeDisplay("node-0", NodeDisplaySettings("a".repeat(80))))
        assertEquals(baseline, editor.currentSource)
        val invalid = records.first().deepCopy().apply { add("display", JsonParser.parseString("{}")) }.toString() + "\n"
        val readOnly = VisualEditorState.create(invalid, "root")
        assertTrue(readOnly.isReadOnly)
        assertFalse(readOnly.updateNodeDisplay("node-0", NodeDisplaySettings("不能覆盖")))
        assertEquals(invalid, readOnly.currentSource)
    }

    @Test fun restoreParentPreservesIndependentlyCommentedChildrenAndMultiSelectionRoots() {
        val editor = editor()
        val parent = add(editor, "control.repeat")
        val child = add(editor, slot = "body")
        editor.selectOnly(child); assertTrue(editor.toggleDisabledSelection())
        editor.selectOnly(parent); assertTrue(editor.toggleDisabledSelection())
        val sibling = add(editor, slot = "body")
        assertTrue(editor.isNodeDisabled(sibling))
        assertFalse(editor.isNodeDisabled(sibling, inherited = false))
        val presentation = editor.programDisplayNodes().first { it.nodeId == sibling }
        assertTrue(presentation.inheritedDisabled)
        assertTrue(requireNotNull(presentation.displayRow).disabled)
        editor.selectOnly(parent); editor.toggleSelection(child)
        assertEquals(listOf(parent), editor.selectedRoots())
        assertTrue(editor.toggleDisabledSelection())
        assertFalse(editor.isNodeDisabled(parent))
        assertTrue(editor.isNodeDisabled(child, inherited = false))
        assertFalse(editor.isNodeDisabled(sibling))
        assertFalse(requireNotNull(editor.programDisplayNodes().first { it.nodeId == sibling }.displayRow).disabled)
        assertTrue(editor.undo()); assertTrue(editor.isNodeDisabled(sibling))
    }

    @Test fun sourceRoundTripPreservesDisplayAndAllowsExplicitReset() {
        val editor = editor(); val id = add(editor)
        val settings = NodeDisplaySettings("准备", false)
        assertTrue(editor.updateNodeDisplay(id, settings))
        val baseline = editor.currentSource
        val exported = pluginSourceToolchain.export(baseline, "root").text
        val roundTrip = pluginSourceToolchain.parse(exported, baseline, "root", emptySet()).text
        assertEquals(nodes(baseline), nodes(roundTrip))
        val withoutMetadata = exported.lineSequence().map { line ->
            if (!line.trimStart().startsWith("--@node")) line else {
                val identity = JsonParser.parseString(line.substringAfter("--@node ")).asJsonObject
                identity.remove("display"); "--@node $identity"
            }
        }.joinToString("\n")
        assertEquals(settings.toJson(), nodes(pluginSourceToolchain.parse(withoutMetadata, baseline, "root", emptySet()).text).single()["display"])
        val reset = exported.replace("\"display\":${settings.toJson()}", "\"display\":null")
        assertFalse(nodes(pluginSourceToolchain.parse(reset, baseline, "root", emptySet()).text).single().has("display"))
        val selected = SelectedNodeSource.create(baseline, id)
        val selectedResult = selected.parse(selected.text.replace("正在准备", "已准备"), emptySet()).text
        assertEquals(settings.toJson(), nodes(selectedResult).single()["display"])
        assertEquals("已准备", nodes(selectedResult).single()["args"].asJsonObject["message"].asString)
        assertEquals("准备", pluginSourceRows(baseline).single().label)
        assertTrue(runCatching {
            pluginSourceToolchain.parse(exported.replace("\"showParameters\":false", "\"showParameters\":2"), baseline, "root", emptySet())
        }.exceptionOrNull() is VisualSourceException)
    }

    @Test fun presentationNeverInterpretsAliasAsExecutionOrBranchState() {
        val settings = NodeDisplaySettings("[已禁用] [分支] · 自定义", false)
        val row = displayProgramRowText("提示 · 准备 · 持续显示", settings, "循环体", false, false)
        assertEquals(settings.alias, row.title); assertEquals("", row.details)
        assertEquals("循环体", row.branch); assertFalse(row.disabled)
        val inherited = displayProgramRowText("提示 · 参数", settings, null, false, true)
        assertTrue(inherited.disabled); assertTrue(inherited.inheritedDisabled)
        assertFalse(displayProgramRowText("提示", settings, null, true, true).inheritedDisabled)
    }

    @Test fun runtimePromptShowsLiteralOrVariableAndDisplayMode() {
        fun label(json: String) = compactProgramLabel(BlockCatalog.find("task.runprompt"), JsonParser.parseString(json).asJsonObject, "task.runprompt")
        val literal = label("{\"message\":\"正在找图\"}")
        assertTrue(literal.contains("正在找图")); assertTrue(literal.contains("持续显示"))
        val variable = label("{\"message\":\"unused\",\"valueVariable\":\"status\"}")
        assertTrue(variable.contains("status")); assertFalse(variable.contains("unused"))
        assertEquals("提示步骤", applyProgramDisplayLabel(variable, NodeDisplaySettings("提示步骤", false)))
        assertTrue(applyProgramDisplayLabel(variable, NodeDisplaySettings("提示步骤", true)).contains("status"))
    }
}

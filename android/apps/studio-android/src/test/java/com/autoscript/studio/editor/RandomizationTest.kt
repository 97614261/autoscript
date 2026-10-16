package com.autoscript.studio

import com.autoscript.project.store.*
import com.autoscript.studio.compiler.NodeRandomizationSettings
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class RandomizationTest {
    @Test fun nodeExactIntegerValidationPreservesBoundariesAndRejectsOverflow() {
        val baseline = randomizationNodeJson("custom", ProjectRandomization())
        for (number in listOf("0", "5.000", "1e3", "4096")) {
            val value = baseline.deepCopy()
            value.getAsJsonObject("position").add("xPx", JsonParser.parseString(number))
            NodeRandomizationSettings.validate(value)
        }
        for (number in listOf("0.5", "-1", "4097", "2147483648", "-2147483649", "1e200", "1e-200", "\"5\"", "true")) {
            val value = baseline.deepCopy()
            value.getAsJsonObject("position").add("xPx", JsonParser.parseString(number))
            assertThrows(number, IllegalArgumentException::class.java) { NodeRandomizationSettings.validate(value) }
        }
        val boundary = baseline.deepCopy()
        boundary.getAsJsonObject("wait").addProperty("min", -60000)
        boundary.getAsJsonObject("wait").addProperty("max", 60000)
        NodeRandomizationSettings.validate(boundary)
    }

    private fun node(source:String) = JsonParser.parseString(source.lineSequence().first(String::isNotBlank)).asJsonObject
    @Test fun customPolicyIsUndoableAndSourceRoundTripsWithoutChangingArguments() {
        val editor=VisualEditorState.create("","root")
        val id=requireNotNull(editor.insertBlock(requireNotNull(BlockCatalog.find("input.tap")),JsonObject().apply {addProperty("x",100);addProperty("y",200)}))
        val original=editor.currentSource
        val settings=randomizationNodeJson("custom",ProjectRandomization(touch=PositionRandomization(true,5,7)))
        assertTrue(editor.updateNodeRandomization(id,settings)); val edited=editor.currentSource
        assertEquals(node(original)["args"],node(edited)["args"])
        val exported=pluginSourceToolchain.export(edited,"root").text
        assertEquals(node(edited),node(pluginSourceToolchain.parse(exported,edited,"root",emptySet()).text))
        assertTrue(editor.programDisplayNodes().single().label.contains("偏移±5/7px"))
        assertTrue(editor.undo());assertEquals(original,editor.currentSource)
        assertTrue(editor.redo());assertEquals(edited,editor.currentSource)
        val copied=requireNotNull(editor.pasteSubtree(requireNotNull(editor.copySelectedSubtree())))
        val records=editor.currentSource.lineSequence().filter(String::isNotBlank).map {JsonParser.parseString(it).asJsonObject}.toList()
        assertEquals(settings,records.first {it["nodeId"].asString==copied}["randomization"])
        assertTrue(editor.updateNodeRandomization(copied,null))
    }
    @Test fun strictPolicyRejectsUnknownFractionalAndOutOfRangeValues() {
        val baseline=randomizationNodeJson("custom",ProjectRandomization())
        for (edit in listOf<(JsonObject)->Unit>({it.addProperty("mode","unknown")},{it.getAsJsonObject("position").addProperty("xPx",1.5)},
            {it.getAsJsonObject("position").addProperty("xPx",4097)},{it.getAsJsonObject("wait").addProperty("unit","seconds")},
            {it.getAsJsonObject("wait").addProperty("min",2)},{it.addProperty("extra",1)})) {
            val bad=baseline.deepCopy();edit(bad)
            assertThrows(IllegalArgumentException::class.java) {NodeRandomizationSettings.validate(bad)}
        }
        assertThrows(IllegalArgumentException::class.java) {NodeRandomizationSettings.validate(com.google.gson.JsonNull.INSTANCE)}
        assertNull(RandomizationForm.from(ProjectRandomization()).copy(touchX="").parse())
        assertNull(RandomizationForm.from(ProjectRandomization()).copy(min="5",max="2").parse())
    }
    @Test fun generatedGestureWaitsCarryExplicitExactTimingThroughInsertion() {
        val mapping=ImageToolCodeGen.DesignMapping(720,1280,720,1280)
        val p=ImageToolCodeGen.PickedPoint(100,200,0);val q=ImageToolCodeGen.PickedPoint(200,300,0)
        for (snippet in listOf(ImageToolCodeGen.longPress(p,500,mapping),ImageToolCodeGen.drag(p,q,500,mapping))) {
            val waits=prepareInputBlocks(snippet,setOf("input.basic","core.task")).filter {it.first.kind=="task.sleep"}
            assertTrue(waits.isNotEmpty());assertTrue(waits.all {it.second["exactTiming"].asBoolean})
        }
    }
    @Test fun singleRowSourceChangesOnlyArgumentsAndPreservesRandomPolicy() {
        val editor = VisualEditorState.create("", "root")
        val id = requireNotNull(editor.insertBlock(requireNotNull(BlockCatalog.find("input.tap")), JsonObject().apply {
            addProperty("x", 100); addProperty("y", 200)
        }))
        val policy = randomizationNodeJson("custom", ProjectRandomization(touch = PositionRandomization(true, 5, 7)))
        assertTrue(editor.updateNodeRandomization(id, policy))
        val descriptor = SelectedNodeSource.create(editor.currentSource, id)
        val changed = descriptor.parse(descriptor.text.replace("[\"x\"] = 100", "[\"x\"] = 110"), emptySet()).text
        assertEquals(110, node(changed).getAsJsonObject("args")["x"].asInt)
        assertEquals(policy, node(changed)["randomization"])
        assertTrue(pluginSourceRows(changed).single().label.contains("偏移±5/7px"))
        val unsupported = node(changed).apply { addProperty("kind", "task.noop"); add("args", JsonObject()) }
        assertTrue(VisualEditorState.create("$unsupported\n", "root").isReadOnly)
    }
}

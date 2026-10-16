package com.autoscript.studio

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class VisualInputRequirementsTest {
    private fun node(id: String, kind: String, parent: String? = null, disabled: Boolean = false,
        action: String = "none") = JsonObject().apply {
        addProperty("nodeId", id); addProperty("kind", kind); addProperty("parentId", parent)
        addProperty("disabled", disabled)
        add("args", JsonObject().apply { addProperty("successAction", action) })
    }.toString()
    private fun map(vararg nodes: Pair<String, String>) = JsonObject().apply {
        add("entries", com.google.gson.JsonArray().apply {
            nodes.forEach { (flow, id) -> add(JsonObject().apply {
                addProperty("flowId", flow); addProperty("nodeId", id)
            }) }
        })
    }

    @Test fun emittedIdentityExcludesDisabledSubtreeAndOtherSingleStepNodes() {
        val source = listOf(node("off", "control.repeat", disabled = true),
            node("pointer", "input.pointerdown", parent = "off"), node("selected", "task.prompt"),
            node("other", "input.pointerup")).joinToString("\n")
        assertFalse(visualPointerInputRequired(mapOf("main" to source), map("main" to "selected")))
        assertFalse(visualPointerInputRequired(mapOf("main" to source), map()))
        assertTrue(visualPointerInputRequired(mapOf("main" to source), map("main" to "other")))
    }

    @Test fun sameNodeNameInAnotherPluginDoesNotChangeDemand() {
        val sources = mapOf("main" to node("same", "task.prompt"), "child" to node("same", "input.pointermove"))
        assertFalse(visualPointerInputRequired(sources, map("main" to "same")))
        assertTrue(visualPointerInputRequired(sources, map("child" to "same")))
    }

    @Test fun pointerOperationsAndImageHoldDemandTheFeatureButTapDoesNot() {
        for (kind in listOf("input.pointerdown", "input.pointermove", "input.pointerup"))
            assertTrue(visualPointerInputRequired(mapOf("main" to node("id", kind)), map("main" to "id")))
        for (action in listOf("hold", "pressRelease"))
            assertTrue(visualPointerInputRequired(mapOf("main" to node("id", "vision.findimage", action = action)), map("main" to "id")))
        for (kind in listOf("input.tap", "input.swipe", "input.longpress"))
            assertFalse(visualPointerInputRequired(mapOf("main" to node("id", kind)), map("main" to "id")))
        assertFalse(visualPointerInputRequired(mapOf("main" to node("id", "vision.findimage", action = "tap")), map("main" to "id")))
    }

    @Test fun historicalMapsRemainConservativeButHonorAncestorDisable() {
        val disabled = listOf(node("off", "control.repeat", disabled = true),
            node("nested", "control.repeat", "off"), node("input", "input.pointerdown", "nested")).joinToString("\n")
        assertFalse(visualPointerInputRequired(mapOf("main" to disabled), JsonObject()))
        assertTrue(visualPointerInputRequired(mapOf("main" to disabled,
            "other" to node("input", "input.pointerup")), JsonObject()))
    }
    @Test fun multiImageAndResultActionsKeepContinuousPointerPreflight() {
        for(action in listOf("hold","pressRelease")) {
            assertTrue(visualPointerInputRequired(mapOf("main" to node("id","vision.findallimages",action=action)),map("main" to "id")))
            val result=JsonParser.parseString(node("id","vision.resultaction")).asJsonObject.apply { getAsJsonObject("args").addProperty("action",action) }
            assertTrue(visualPointerInputRequired(mapOf("main" to result.toString()),map("main" to "id")))
        }
        val tap=JsonParser.parseString(node("id","vision.resultaction")).asJsonObject.apply { getAsJsonObject("args").addProperty("action","tap") }
        assertFalse(visualPointerInputRequired(mapOf("main" to tap.toString()),map("main" to "id")))
    }

    @Test fun corruptPositionAndHierarchyFailRatherThanSilentlySkipPreflight() {
        val sources = mapOf("main" to node("id", "input.pointerup"))
        for (encoded in listOf("{\"entries\":null}", "{\"entries\":{}}", "{\"entries\":[{}]}",
            "{\"entries\":[{\"flowId\":\"main\",\"nodeId\":\"missing\"}]}")) {
            assertThrows(IllegalArgumentException::class.java) {
                visualPointerInputRequired(sources, JsonParser.parseString(encoded).asJsonObject)
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            visualPointerInputRequired(mapOf("main" to node("cycle", "input.pointerdown", "cycle")), JsonObject())
        }
    }
}

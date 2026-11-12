package com.autoscript.studio

import com.autoscript.project.store.ProjectManifestDocument
import com.autoscript.project.store.ProjectSnapshot
import com.autoscript.project.store.ProjectSourceMode
import com.google.gson.JsonObject
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class RecognitionActionFieldsTest {
    private val resource = JsonObject().apply { addProperty("kind", "image"); addProperty("path", "assets/images/a.png") }
    private val snapshot = ProjectSnapshot(File("."), ProjectManifestDocument(
        projectId = "public-test", name = "Test", sourceMode = ProjectSourceMode.VISUAL, resources = listOf(resource),
    ), luaSource = null, flowSources = emptyMap())

    @Test fun newFindBlocksDefaultToCenterButOldArgumentsKeepTheirOrigin() {
        for (kind in listOf("vision.findimage", "vision.findallimages")) {
            val contract = requireNotNull(BlockCatalog.find(kind))
            assertEquals("center", initialRecognitionArguments(contract, snapshot)["actionAnchor"].asString)
            assertEquals("topLeft", recognitionPropertyTexts(contract, JsonObject())["actionAnchor"])
            assertEquals("follow", recognitionPropertyTexts(contract, JsonObject())["actionRandom"])
        }
    }

    @Test fun choicesSurviveFullParsingWithoutChangingMatchCoordinatesOrNames() {
        val contract = requireNotNull(BlockCatalog.find("vision.findimage"))
        val inputs = recognitionPropertyTexts(contract, initialRecognitionArguments(contract, snapshot)) + mapOf(
            "successAction" to "tap", "actionAnchor" to "center", "actionRandom" to "offset",
            "actionRandomX" to "8", "actionRandomY" to "12", "offsetX" to "-2", "offsetY" to "3",
        )
        val parsed = requireNotNull(parseRecognitionArguments(contract, inputs, listOf(resource)).first)
        assertEquals("center", parsed["actionAnchor"].asString)
        assertEquals("offset", parsed["actionRandom"].asString)
        assertEquals(8, parsed["actionRandomX"].asInt)
        assertEquals(12, parsed["actionRandomY"].asInt)
        assertEquals(-2, parsed["offsetX"].asInt)
        assertEquals(inputs["xVariable"], parsed["xVariable"].asString)
        assertEquals(inputs["foundVariable"], parsed["foundVariable"].asString)
        val hidden = inputs + mapOf("successAction" to "none", "actionRandom" to "inside")
        assertEquals("8", hidden["actionRandomX"])
        assertNotNull(parseRecognitionArguments(contract, hidden, listOf(resource)).first)
    }

    @Test fun invalidRandomOptionsAreRejectedBeforeSaving() {
        for ((key, value) in listOf("actionRandomX" to "-1", "actionRandomX" to "4097",
            "actionRandomY" to "", "actionRandom" to "bad", "actionAnchor" to "bad")) {
            assertNotNull(recognitionActionError(mapOf(key to value)))
        }
        assertNull(recognitionActionError(mapOf("actionRandomX" to "0", "actionRandomY" to "4096")))
    }
}

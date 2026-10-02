package com.autoscript.studio

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.autoscript.project.store.ProjectManifestDocument
import com.autoscript.project.store.ProjectSnapshot
import com.autoscript.project.store.ProjectSourceMode
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class VisualImageRecognitionDialogTest {
    @Test fun nativeKindsUseSharedCompactEditorAndOcrDoesNotNeedTemplateResource() {
        val ocr=requireNotNull(BlockCatalog.find("ocr.alphanumeric"))
        val args=requireNotNull(initialBlockArguments(ocr,emptyList(),emptyList(),"",allowMissingResources=true))
        val inputs=recognitionPropertyTexts(ocr,args)
        assertNotNull(parseRecognitionArguments(ocr,inputs,emptyList()).first)
        assertNull(parseRecognitionArguments(ocr,inputs+("minimumConfidencePermille" to "1001"),emptyList()).first)
        assertEquals(setOf("ocr.onnx","screen.capture"),ocr.requiredCapabilities)
        assertEquals(setOf("vision.opencv","vision.template","screen.capture"),requireNotNull(BlockCatalog.find("vision.findgray")).requiredCapabilities)
        assertTrue(visualRecognitionKinds.containsAll(listOf("ocr.alphanumeric","vision.findgray")))
    }
    @Test
    fun directionAndRegionVariablesSurviveEditingAndCaptureClearsOverrides() {
        val contract = requireNotNull(BlockCatalog.find("vision.findimage"))
        val input = blockPropertyTexts(contract, arguments()) + mapOf("direction" to "3", "leftVariable" to "roiLeft", "topVariable" to "roiTop")
        val parsed = requireNotNull(parseRecognitionArguments(contract, input, listOf(resource)).first)
        assertEquals(3, parsed.get("direction").asInt)
        assertEquals("roiLeft", parsed.get("leftVariable").asString)
        val selected = recognitionSelectionInputs(input, ImageToolCodeGen.VisualSelection(ImageToolMode.REGION,
            ImageToolCodeGen.Roi(0, 0, 50, 50), emptyList(), 0, 720, 1280))
        assertEquals("", selected["leftVariable"])
        assertEquals("3", selected["direction"])
        assertNull(parseRecognitionArguments(contract, input + ("direction" to "5"), listOf(resource)).first)
        assertNull(parseRecognitionArguments(contract, input + ("leftVariable" to "found"), listOf(resource)).first)
    }
    private val resource = JsonObject().apply {
        addProperty("kind", "image")
        addProperty("path", "assets/images/button.png")
    }

    @Test
    fun directoryFrequencyActionAndImageOutputSurviveEditing() {
        val contract=requireNotNull(BlockCatalog.find("vision.findimage"))
        val input=blockPropertyTexts(contract,arguments()) + mapOf("imageDirectory" to "assets/images/", "frequency" to "3", "successAction" to "pressRelease", "actionDurationMs" to "250", "imageVariable" to "matchedImage", "autoCapture" to "true")
        val result=requireNotNull(parseRecognitionArguments(contract,input,listOf(resource)).first)
        assertEquals("assets/images/",result.get("imageDirectory").asString)
        assertEquals("pressRelease",result.get("successAction").asString)
        assertEquals("matchedImage",result.get("imageVariable").asString)
        assertEquals(3,result.get("frequency").asInt)
        assertTrue(result.get("autoCapture").asBoolean)
        for (bad in listOf("0","31","abc","999999999999")) assertNull(parseRecognitionArguments(contract,input + ("frequency" to bad),listOf(resource)).first)
        assertNull(parseRecognitionArguments(contract,input + ("actionDurationMs" to "-1"),listOf(resource)).first)
        assertNull(parseRecognitionArguments(contract,input + ("imageVariable" to "frame"),listOf(resource)).first)
    }
    private fun arguments() = JsonParser.parseString("""{
        "frameVariable":"frame", "imagePath":"assets/images/button.png", "tolerance":8,
        "similarityPermille":950, "region":{"left":10,"top":20,"right":110,"bottom":220},
        "foundVariable":"found", "xVariable":"foundX", "yVariable":"foundY"
    }""").asJsonObject

    @Test
    fun explicitTemplateListIsPreservedAndInvalidListsCannotBeSaved() {
        val contract = requireNotNull(BlockCatalog.find("vision.findimage"))
        val original = arguments().apply { add("imagePaths", JsonParser.parseString("[\"assets/images/button.png\"]")) }
        val inputs = recognitionPropertyTexts(contract, original)
        val edited = requireNotNull(parseRecognitionArguments(contract, inputs + ("tolerance" to "12"), listOf(resource)).first)
        assertEquals(original.get("imagePaths"), edited.get("imagePaths"))
        assertEquals(12, edited.get("tolerance").asInt)
        for (invalid in listOf("[]", "[1]", "[\"missing.png\"]", "[\"assets/images/button.png\",\"assets/images/button.png\"]", "{}")) {
            assertNull(parseRecognitionArguments(contract, inputs + ("imagePaths" to invalid), listOf(resource)).first)
        }
        assertNull(parseRecognitionArguments(contract, inputs + ("imageDirectory" to "assets/images/"), listOf(resource)).first)
    }

    @Test
    fun existingFindImageRoundTripsWithoutChangingPixelCoordinatesOrOriginalArguments() {
        val original = arguments()
        val form = ImageRecognitionForm.from(original)
        assertEquals(listOf("10", "20", "100", "200"), form.bounds)
        assertEquals(original, form.arguments(listOf(resource)).first)
        val edited = form.copy(bounds = listOf("30", "40", "50", "60"), tolerance = "12")
        val saved = requireNotNull(edited.arguments(listOf(resource)).first)
        assertEquals(80, saved.getAsJsonObject("region").get("right").asInt)
        assertEquals(100, saved.getAsJsonObject("region").get("bottom").asInt)
        assertEquals(8, original.get("tolerance").asInt)
    }

    @Test
    fun saveRejectsUndeclaredTemplatesInvalidThresholdsAndOverlappingOutputNames() {
        val form = ImageRecognitionForm.from(arguments())
        assertNull(form.arguments(emptyList()).first)
        assertNull(form.copy(imagePath = "../outside.png").arguments(listOf(resource)).first)
        assertNull(form.copy(tolerance = "256").arguments(listOf(resource)).first)
        assertNull(form.copy(similarity = "1001").arguments(listOf(resource)).first)
        assertNull(form.copy(xVariable = "found").arguments(listOf(resource)).first)
        assertNull(form.copy(frameVariable = "x".repeat(65)).arguments(listOf(resource)).first)
        assertNull(form.copy(yVariable = "1invalid").arguments(listOf(resource)).first)
        assertNotNull(form.copy(tolerance = "255", similarity = "1000").arguments(listOf(resource)).first)
    }

    @Test
    fun rectangleRejectsNegativeEmptyAndOverflowDimensions() {
        assertNull(imageRecognitionBounds(listOf("-1", "0", "10", "10")))
        assertNull(imageRecognitionBounds(listOf("0", "0", "0", "10")))
        assertNull(imageRecognitionBounds(listOf("1", "0", Int.MAX_VALUE.toString(), "10")))
        assertNull(imageRecognitionBounds(listOf("0", "0", Long.MAX_VALUE.toString(), "10")))
        assertNull(imageRecognitionBounds(listOf("0", "0", "10")))
        assertNotNull(imageRecognitionBounds(listOf("0", "0", "1", "1")))
    }

    @Test
    fun reverseDragClampsToOriginalScreenshotAndPreservesHalfOpenBounds() {
        assertEquals(listOf("10", "20", "101", "201"), imageRecognitionDragRegion(110.2f, 220.2f, 10.7f, 20.7f, 720, 1280))
        assertEquals(listOf("0", "0", "720", "1280"), imageRecognitionDragRegion(-10f, -10f, 800f, 1500f, 720, 1280))
        assertEquals(listOf("719", "1279", "1", "1"), imageRecognitionDragRegion(900f, 1400f, 900f, 1400f, 720, 1280))
        assertNull(imageRecognitionDragRegion(Float.NaN, 0f, 10f, 10f, 720, 1280))
    }

    @Test
    fun emptyResourceProjectCanOpenDraftButCannotCommitUndeclaredTemplate() {
        val snapshot = ProjectSnapshot(directory = File("."), manifest = ProjectManifestDocument(
            projectId = "test", name = "test", sourceMode = ProjectSourceMode.VISUAL,
        ), luaSource = null, flowSources = emptyMap())
        val contract = requireNotNull(BlockCatalog.find("vision.findimage"))
        val draft = initialRecognitionArguments(contract, snapshot)
        assertEquals("", draft.get("imagePath").asString)
        assertEquals(snapshot.manifest.design.width, draft.getAsJsonObject("region").get("right").asInt)
        assertNull(parseRecognitionArguments(contract, blockPropertyTexts(contract, draft), emptyList()).first)
        assertNull(initialBlockArguments(contract, emptyList(), emptyList(), "main"))
    }

    @Test
    fun screenRegionBackfillPreservesUnsavedThresholdsTemplateAndOutputNames() {
        val contract = requireNotNull(BlockCatalog.find("vision.findimage"))
        val original = blockPropertyTexts(contract, arguments()) + ("foundVariable" to "customFound")
        val selection = ImageToolCodeGen.VisualSelection(ImageToolMode.REGION,
            ImageToolCodeGen.Roi(30, 40, 80, 100), emptyList(), 0, 1080, 1920)
        val updated = recognitionSelectionInputs(original, selection)
        assertEquals("30,40,80,100", updated["region"])
        assertEquals("8", updated["tolerance"])
        assertEquals("950", updated["similarityPermille"])
        assertEquals("customFound", updated["foundVariable"])
        assertEquals("assets/images/button.png", updated["imagePath"])
        assertEquals("10,20,110,220", original["region"])
    }

    @Test
    fun colorBackfillDoesNotResetEditedToleranceOrVariables() {
        val input = mapOf("rgb" to "#FFFFFF", "tolerance" to "18", "foundVariable" to "custom", "region" to "0,0,720,1280")
        val updated = recognitionSelectionInputs(input, ImageToolCodeGen.VisualSelection(ImageToolMode.COLOR,
            ImageToolCodeGen.Roi(10, 20, 110, 220), listOf(ImageToolCodeGen.PickedPoint(30, 40, 0x123456)), 0, 1080, 1920))
        assertEquals("#123456", updated["rgb"])
        assertEquals("18", updated["tolerance"])
        assertEquals("custom", updated["foundVariable"])
    }

    @Test
    fun pointOnlyBackfillDoesNotReplaceRoiAndCanSetOcrForegroundColor() {
        val input = mapOf("point" to "0,0", "region" to "10,20,110,220", "foregroundRgb" to "#FFFFFF")
        val updated = recognitionSelectionInputs(input, ImageToolCodeGen.VisualSelection(ImageToolMode.COLOR,
            null, listOf(ImageToolCodeGen.PickedPoint(30, 40, 0x123456)), 0, 1080, 1920))
        assertEquals("10,20,110,220", updated["region"])
        assertEquals("30,40", updated["point"])
        assertEquals("#123456", updated["foregroundRgb"])
    }

    @Test
    fun allRecognitionContractsAreRealAndRejectVariableOverwrite() {
        assertEquals(10, visualRecognitionKinds.distinct().size)
        visualRecognitionKinds.forEach { kind ->
            val contract = requireNotNull(BlockCatalog.find(kind))
            assertTrue(contract.properties.any { it.path == "frameVariable" })
        }
        val contract = requireNotNull(BlockCatalog.find("vision.findcolor"))
        val input = mapOf("frameVariable" to "frame", "foundVariable" to "frame", "xVariable" to "x", "yVariable" to "y")
        assertNull(parseRecognitionArguments(contract, input, emptyList()).first)
    }

    @Test
    fun functionInsertionHasNoImplicitCaptureAndOneUndoRestoresSource() {
        val contract = requireNotNull(BlockCatalog.find("vision.findimage"))
        val editor = VisualEditorState.create("", "root")
        val request = RecognitionInsertRequest(contract, arguments())
        assertTrue(insertConfiguredRecognition(editor, request, arguments()))
        assertEquals(listOf("vision.findimage"), editor.rows.map { it.kind })
        editor.undo()
        assertEquals("", editor.currentSource)
    }
}

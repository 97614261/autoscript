package com.autoscript.studio

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class ImageRecognitionWorkflowTest {
    private val paths = listOf("assets/images/a.png", "assets/images/group/b.png")
    private val resources = paths.map { path -> JsonObject().apply { addProperty("kind", "image"); addProperty("path", path) } }
    private val contract = requireNotNull(BlockCatalog.find("vision.findimage"))
    private val baseline = blockPropertyTexts(contract, JsonParser.parseString("""{
        "imagePath":"assets/images/a.png", "frameVariable":"frame", "foundVariable":"found",
        "xVariable":"foundX", "yVariable":"foundY", "tolerance":8, "similarityPermille":950,
        "region":{"left":0,"top":0,"right":720,"bottom":1280}
    }""").asJsonObject)

    @Test fun choosingMultipleTemplatesClearsDirectoryAndSetsValidPrimaryTemplate() {
        val inputs = recognitionSelectTemplates(baseline + ("imageDirectory" to "assets/images/"), paths.reversed())
        assertEquals(RecognitionTemplateSource.LIST, recognitionTemplateSource(inputs))
        assertEquals(paths.last(), inputs["imagePath"])
        assertEquals("", inputs["imageDirectory"])
        val args = requireNotNull(parseRecognitionArguments(contract, inputs, resources).first)
        assertEquals(paths.reversed(), recognitionTemplatePaths(args["imagePaths"].toString()))
        assertEquals("8", inputs["tolerance"])
        assertEquals(baseline["foundVariable"], inputs["foundVariable"])
    }

    @Test fun directoryAndSingleModesAreMutuallyExclusiveAndPreserveOtherSettings() {
        val directory = recognitionSelectTemplateDirectory(recognitionSelectTemplates(baseline, paths), "assets/images/group/", paths)
        assertEquals(RecognitionTemplateSource.DIRECTORY, recognitionTemplateSource(directory))
        assertEquals(paths.last(), directory["imagePath"])
        assertEquals("", directory["imagePaths"])
        assertNotNull(parseRecognitionArguments(contract, directory, resources).first)
        val single = recognitionSelectSingleTemplate(directory, paths.first())
        assertEquals(RecognitionTemplateSource.SINGLE, recognitionTemplateSource(single))
        assertEquals("", single["imageDirectory"])
        assertEquals("950", single["similarityPermille"])
    }

    @Test fun capturedTemplateKeepsPriorityOrderAndThresholdsWithoutSilentOverflow() {
        val inputs = recognitionSelectTemplates(baseline, paths.take(1))
        val captured = recognitionCapturedTemplate(inputs, contract.kind, paths.last())
        assertEquals(paths, recognitionTemplatePaths(captured["imagePaths"].orEmpty()))
        assertEquals(paths.first(), captured["imagePath"])
        assertEquals(inputs["region"], captured["region"])
        assertEquals(captured, recognitionCapturedTemplate(captured, contract.kind, paths.last()))
        val full = recognitionSelectTemplates(baseline, (1..64).map { "assets/images/$it.png" })
        assertThrows(IllegalArgumentException::class.java) { recognitionCapturedTemplate(full, contract.kind, "assets/images/new.png") }
        assertEquals(64, recognitionTemplatePaths(full["imagePaths"].orEmpty()).size)
    }

    @Test fun savedCaptureChoosesSingleInsteadOfKeepingAnUnrelatedFolder() {
        val inputs = recognitionSelectTemplateDirectory(baseline, "assets/images/group/", paths)
        val captured = recognitionCapturedTemplate(inputs, contract.kind, paths.first())
        assertEquals(RecognitionTemplateSource.SINGLE, recognitionTemplateSource(captured))
        assertEquals(paths.first(), captured["imagePath"])
        val all = recognitionCapturedTemplate(mapOf("imagePaths" to "[]", "resultVariable" to "matches"), "vision.findallimages", paths.first())
        assertEquals(paths.take(1), recognitionTemplatePaths(all["imagePaths"].orEmpty()))
        assertEquals("matches", all["resultVariable"])
    }

    @Test fun emptyOrUnregisteredTemplateSelectionCannotPassFinalValidation() {
        assertNull(parseRecognitionArguments(contract, recognitionSelectTemplates(baseline, emptyList()), resources).first)
        assertNull(parseRecognitionArguments(contract, recognitionSelectTemplates(baseline, listOf("assets/images/missing.png")), resources).first)
        assertNull(parseRecognitionArguments(contract, recognitionSelectTemplateDirectory(baseline, "assets/images/missing/", paths), resources).first)
    }

    @Test fun screenToolsMatchTheChosenOperationInsteadOfAlwaysCropping() {
        assertEquals(ImageToolMode.CROP, recognitionScreenPickMode("vision.findgray"))
        assertEquals(ImageToolMode.MULTI_COLOR, recognitionScreenPickMode("vision.findmulticolor"))
        assertEquals(ImageToolMode.COLOR, recognitionScreenPickMode("vision.getcolor"))
        assertEquals(ImageToolMode.REGION, recognitionScreenPickMode("ocr.alphanumeric"))
        assertEquals(ImageToolMode.REGION, recognitionScreenPickMode("ocr.glyph"))
    }

    @Test fun actionRowsCanHideWithoutDeletingTheirDraftValues() {
        val inputs = mapOf("successAction" to "none", "offsetX" to "25", "actionDurationMs" to "250", "beforeWaitMs" to "100")
        assertFalse(recognitionPropertyVisible("offsetX", inputs))
        assertFalse(recognitionPropertyVisible("actionDurationMs", inputs))
        assertTrue(recognitionPropertyVisible("beforeWaitMs", inputs))
        assertTrue(recognitionPropertyVisible("offsetX", inputs + ("successAction" to "tap")))
        assertTrue(recognitionPropertyVisible("actionDurationMs", inputs + ("successAction" to "pressRelease")))
        assertTrue(recognitionPropertyVisible("actionDurationMs", inputs + ("actionDurationMs" to "")))
        assertTrue(recognitionPropertyVisible("actionDurationMs", inputs + mapOf("allMatches" to "false", "actionDurationMs" to "5001")))
        assertEquals("25", inputs["offsetX"])
    }

    @Test fun imageEntryOnlyCapturesForOperationsThatActuallyReadPixels() {
        val result = requireNotNull(BlockCatalog.find("vision.result"))
        val args = JsonObject().apply { addProperty("sourceVariable", "matches") }
        val read = recognitionImageEntryBlocks(result, args)
        assertEquals(listOf("vision.result"), read.map { it.first.kind })
        assertEquals(args, read.single().second)
        assertFalse(read.flatMap { it.first.requiredCapabilities }.contains("screen.capture"))
        val find = recognitionImageEntryBlocks(contract, JsonObject().apply { addProperty("autoCapture", false) })
        assertEquals(listOf("vision.findimage"), find.map { it.first.kind })
        assertTrue(find.single().second["autoCapture"].asBoolean)
        val color = recognitionImageEntryBlocks(requireNotNull(BlockCatalog.find("vision.findcolor")), JsonObject().apply { addProperty("frameVariable", "customFrame") })
        assertEquals(listOf("screen.capture", "vision.findcolor", "screen.release"), color.map { it.first.kind })
        assertEquals("customFrame", color.first().second["resultVariable"].asString)
        assertEquals("customFrame", color.last().second["frameVariable"].asString)
    }

    @Test fun manualFixedRegionClearsOldVariableOverridesWithoutResettingTheDraft() {
        val inputs = baseline + mapOf("leftVariable" to "oldLeft", "bottomVariable" to "oldBottom")
        val changed = recognitionFixedRegionInputs(inputs, "10,20,100,200")
        assertEquals("", changed["leftVariable"])
        assertEquals("", changed["bottomVariable"])
        assertEquals("10,20,100,200", changed["region"])
        assertEquals(inputs["tolerance"], changed["tolerance"])
        assertEquals("oldLeft", inputs["leftVariable"])
    }

    @Test fun sixtyFourSamplesRemainEditableAndSourceSummaryDoesNotClaimCaptureSuccess() {
        val multi = requireNotNull(BlockCatalog.find("vision.findmulticolor"))
        val inputs = mapOf("samples" to (1..64).joinToString(";") { "$it,0,#FFFFFF,0" },
            "frameVariable" to "frame", "anchorRgb" to "#FFFFFF", "anchorTolerance" to "0", "region" to "0,0,720,1280",
            "foundVariable" to "found", "xVariable" to "x", "yVariable" to "y")
        assertTrue(inputs.getValue("samples").length > 128)
        assertEquals(64, requireNotNull(parseRecognitionArguments(multi, inputs, emptyList()).first)["samples"].asJsonArray.size())
        assertEquals("读取已有帧：frame", recognitionFrameSummary(inputs, false))
        assertEquals("运行时自动截图 · 用完释放", recognitionFrameSummary(inputs, true))
        assertEquals("读取已保存的识别结果", recognitionFrameSummary(emptyMap(), false))
        imageEntryGroups.flatMap { it.second }.forEach { assertNotEquals("配置识别积木", imageActionHint(it.kind)) }
    }
}

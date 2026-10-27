package com.autoscript.studio

import com.autoscript.project.store.*
import com.autoscript.runtime.api.RecognitionPreviewReply as Reply
import com.google.gson.JsonObject
import org.junit.Assert.*
import org.junit.Test

class CapabilityVisionWorkflowTest {
    private val roi=ImageToolCodeGen.Roi(0,0,128,64)
    private val resources=listOf(JsonObject().apply {addProperty("kind","image");addProperty("path","assets/images/template.png")})
    @Test fun featurePreviewRecipeAndInsertedBlockKeepExactAlgorithmAndPixelRegion() {
        val settings=RecognitionPreviewConfiguration(Reply.FEATURE_TEMPLATE,"assets/images/template.png",0,650,128,64,roi)
        val recipe=ProjectImageRecipe("特征",128,64,emptyList(),roi.recipeRegion(),matchRecipeTail(settings))
        assertEquals(settings,recipe.matchSettings());assertEquals("vision.findfeatures",recipe.tail!!.kind)
        val variables=listOf(CalculationVariable("frame",ProjectVariableType.IMAGE,"global"),CalculationVariable("result",ProjectVariableType.STRING,"global"))
        val (contract,args)=ImagePipelineRecognitionTail.Match(settings,roi).block("frame",128,64,mapOf("resultVariable" to "result"),variables,resources)
        assertEquals("vision.findfeatures",contract.kind);assertEquals("assets/images/template.png",args["imagePath"].asString)
        assertFalse(args.has("autoCapture"));assertFalse(args.has("templateVariable"));assertEquals(650,args["minimumScorePermille"].asInt)
        assertThrows(IllegalArgumentException::class.java) {settings.copy(tolerance=1)}
    }
    @Test fun v5SelectionIsExplicitAndRoundtripsWithoutChangingV4OrCreatingHiddenVariables() {
        val settings=ImageToolOcrConfig(Reply.ALPHANUMERIC_V5,tolerance=0,threshold=700,spaceGapColumns=0)
        val recipe=ProjectImageRecipe("候选",128,64,emptyList(),roi.recipeRegion(),ocrRecipeTail(settings))
        assertEquals(settings,recipe.ocrSettings());assertEquals("ocr.alphanumericv5",settings.kind)
        val args=settings.arguments(roi);assertTrue(args.has("resultVariable"));assertFalse(args.has("textVariable"));assertFalse(args.has("autoCapture"))
        assertEquals(listOf("screen.capture","ocr.alphanumericv5","screen.release"),ocrRecognitionSequence(settings.kind,args).map {it.first.kind})
        assertEquals("ocr.alphanumeric",ImageToolOcrConfig().kind)
        val variables=listOf(CalculationVariable("frame",ProjectVariableType.IMAGE,"global"),CalculationVariable("result",ProjectVariableType.STRING,"global"))
        val block=ImagePipelineRecognitionTail.Ocr(settings,roi).block("frame",128,64,mapOf("resultVariable" to "result"),variables,emptyList())
        assertEquals("ocr.alphanumericv5",block.first.kind);assertEquals("result",block.second["resultVariable"].asString)
    }
}

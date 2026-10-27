package com.autoscript.studio

import com.autoscript.project.store.*
import com.autoscript.runtime.api.RecognitionPreviewReply as Reply
import org.junit.Assert.*
import org.junit.Test

class ImageRecipeBridgeTest {
    @Test fun symbolsSurviveRecipeSaveAndReloadWhileOldRecipesKeepOldMode() {
        val recipe = ProjectImageRecipe("数字", 320, 48, emptyList(), ProjectImageRegion(0,0,320,48),
            ocrRecipeTail(ImageToolOcrConfig()))
        assertEquals(Reply.LATIN_TEXT, recipe.ocrSettings()?.operation)
        val old = recipe.copy(tail = ProjectImageRecipeTail("ocr.alphanumeric", "{\"similarityPermille\":500}"))
        assertEquals(Reply.ALPHANUMERIC, old.ocrSettings()?.operation)
        assertThrows(IllegalArgumentException::class.java) {
            ProjectImageRecipeTail("ocr.alphanumeric", "{\"similarityPermille\":500,\"includeSymbols\":\"true\"}")
        }
    }
    @Test fun operationsAndRecognitionRoundTripAsConfigurationNotTestProof() {
        val operations=listOf(ImagePixelOperation("crop",roi=ImageToolCodeGen.Roi(1,2,9,8)),ImagePixelOperation("gray"),
            ImagePixelOperation("mask",mask=ProjectImageMask(8,6,listOf(ProjectMaskStroke("rect",false,0,listOf(ProjectMaskPoint(0,0),ProjectMaskPoint(1,1)))))))
        assertEquals(operations,operations.map {it.recipeStep().pixelOperation()})
        val region=ImageToolCodeGen.Roi(0,0,8,6)
        val settings=RecognitionPreviewConfiguration(Reply.TEMPLATE,"assets/images/a.png",4,900,8,6,region,2)
        val recipe=ProjectImageRecipe("方案",10,8,operations.map {it.recipeStep()},region.recipeRegion(),matchRecipeTail(settings))
        assertEquals(settings,recipe.matchSettings());assertNull(recipe.ocrSettings())
        val ocr=ImageToolOcrConfig(Reply.GLYPH,"dictionaries/a.glyph",0xFFFFFF,6,800,3)
        val textRecipe=recipe.copy(tail=ocrRecipeTail(ocr))
        assertEquals(ocr,textRecipe.ocrSettings());assertNull(textRecipe.matchSettings())
        assertFalse(recipe.json().toString().contains("Variable"))
    }
}

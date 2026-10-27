package com.autoscript.studio

import com.autoscript.project.store.*
import com.autoscript.runtime.api.RecognitionPreviewReply as Reply
import org.junit.Assert.*
import org.junit.Test

class GlyphColorPaletteTest {
    @Test fun continuousPickingReplacesInitialThenAppendsAndDeduplicatesWithoutChangingOtherTolerances() {
        val first = GlyphPaletteDraft.from(0xffffff, 8).pick(0xff0000)
        assertEquals(listOf(ProjectGlyphColor(0xff0000,8)),first.parsed())
        val next = first.pick(0x00ff00).edit(tolerance="16")
        assertEquals(listOf(ProjectGlyphColor(0xff0000,8),ProjectGlyphColor(0x00ff00,16)),next.parsed())
        assertEquals(2,next.pick(0xff0000).colors.size)
        val replaced = next.copy(selected=0,replaceOnPick=true).pick(0x0000ff)
        assertEquals(0x0000ff,replaced.parsed()!!.first().rgb)
        assertEquals(16,replaced.parsed()!![1].tolerance)
        assertEquals(1,replaced.remove().colors.size)
    }
    @Test fun invalidDraftsAndDuplicateEntriesCannotProduceARecognitionConfiguration() {
        val draft=GlyphPaletteDraft.from(0xffffff,8).add()
        assertNull(draft.parsed())
        assertNull(draft.edit(rgb="FFFFFF").parsed())
        assertNull(draft.edit(rgb="FF0000",tolerance="256").parsed())
        var full=GlyphPaletteDraft.from(0,0).pick(0)
        for(color in 1..15) full=full.pick(color)
        assertEquals(16,full.colors.size);assertEquals(full,full.pick(16));assertEquals(full,full.add())
    }
    @Test fun samplingUnionsIndependentColorsAndLeavesUnselectedOrTransparentPixelsOut() {
        val red=0xffff0000.toInt();val green=0xff00f700.toInt()
        val colors=listOf(ProjectGlyphColor(0xff0000,0),ProjectGlyphColor(0x00ff00,8))
        val result=GlyphDictionaryMaker.sample(intArrayOf(red,green,red),1,3,0,0,1,3,colors,false).single()
        assertEquals(3,result.height);assertTrue((0..2).all {result.foreground(0,it)})
        assertThrows(IllegalArgumentException::class.java) {GlyphDictionaryMaker.sample(intArrayOf(red,green,red),1,3,0,0,1,3,listOf(colors.first()),false)}
        val transparent=GlyphDictionaryMaker.sample(intArrayOf(0x00ff0000,green,red),1,3,0,0,1,3,colors,false).single()
        assertEquals(2,transparent.height)
    }
    @Test fun paletteSurvivesPreviewLuaBackfillAndImageRecipeWithoutChangingTheDictionaryFormat() {
        val area=ImageToolCodeGen.Roi(0,0,20,30)
        val config=ImageToolOcrConfig(Reply.GLYPH,"dictionaries/main.asglyph",0xff0000,0,1000,0,"00FF00:8;0000FF:16")
        assertEquals(12,config.options(area).size)
        assertTrue(Reply.validOptions(Reply.GLYPH,config.options(area)))
        assertNull(config.snippet(area,20,30).rejection)
        assertTrue(config.snippet(area,20,30).code.contains("00FF00:8;0000FF:16"))
        val code=config.snippet(area,20,30).code
        assertTrue(code,code.indexOf("glyphPaletteVersion") < code.indexOf("Ocr.loadDictionary"))
        val args=config.arguments(area)
        assertEquals(config.additionalColors,config.applyGlyphSettings(args)["additionalColors"].asString)
        val recipe=ProjectImageRecipe("palette",20,30,emptyList(),area.recipeRegion(),ocrRecipeTail(config))
        assertEquals(config,recipe.ocrSettings())
        assertFalse(config.copy(additionalColors="").applyGlyphSettings(args).has("additionalColors"))
    }
}

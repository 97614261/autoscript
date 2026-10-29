package com.autoscript.studio

import com.autoscript.project.store.ProjectGlyphColor
import org.junit.Assert.*
import org.junit.Test

class ColorGroupRecognitionTest {
    @Test fun allFourColorEditorsRoundTripGroupsAndRejectInvalidColors() {
        for (kind in recognitionColorGroupKinds) {
            val contract = requireNotNull(BlockCatalog.find(kind))
            val defaults = requireNotNull(initialBlockArguments(contract, emptyList(), emptyList(), "main"))
            val inputs = recognitionPropertyTexts(contract, defaults) + mapOf("rgb" to "#FF0000", "additionalColors" to "0000FF:8;00FF00:16")
            val parsed = requireNotNull(parseRecognitionArguments(contract, inputs, emptyList()).first)
            assertEquals("0000FF:8;00FF00:16", parsed["additionalColors"].asString)
            assertEquals(parsed, parseRecognitionArguments(contract, recognitionPropertyTexts(contract, parsed), emptyList()).first)
            for (bad in listOf("FF0000:0", "0000FF:256", "0000FF:0;0000ff:8", "0000FF:0;", "x".repeat(165))) {
                val result = parseRecognitionArguments(contract, inputs + ("additionalColors" to bad), emptyList())
                assertNull("$kind $bad", result.first)
                assertNotNull(result.second)
            }
            val summary = compactProgramLabel(contract, parsed, kind)
            assertTrue(summary, summary.contains("3 色 · 任意命中"))
            assertFalse(summary, summary.contains("0000FF:8"))
        }
    }

    @Test fun paletteScreenPickReplacesSelectedColorAndKeepsOtherTolerances() {
        val draft = GlyphPaletteDraft.from(0xff0000, 8, "0000FF:16").copy(selected = 1, replaceOnPick = false)
        val selection = ImageToolCodeGen.VisualSelection(ImageToolMode.COLOR, null,
            listOf(ImageToolCodeGen.PickedPoint(500, 200, 0x00ff00)), 0, 1280, 720)
        val result = recognitionPaletteSelection(draft, selection)
        assertEquals(listOf(ProjectGlyphColor(0xff0000, 8), ProjectGlyphColor(0x00ff00, 16)), result.parsed())
        assertEquals(draft, recognitionPaletteSelection(draft, selection.copy(points = emptyList())))
        assertEquals(2, recognitionPaletteSelection(draft, selection.copy(points = listOf(ImageToolCodeGen.PickedPoint(0,0,0xff0000)))).colors.size)
    }

    @Test fun normalScreenPickPreservesColorGroupRegionOutputsAndIndependentTolerances() {
        val contract = requireNotNull(BlockCatalog.find("vision.findcolor"))
        val defaults = requireNotNull(initialBlockArguments(contract, emptyList(), emptyList(), "main"))
        val original = recognitionPropertyTexts(contract, defaults) + mapOf("rgb" to "#FF0000", "additionalColors" to "0000FF:16", "tolerance" to "8")
        val selection = ImageToolCodeGen.VisualSelection(ImageToolMode.COLOR, null,
            listOf(ImageToolCodeGen.PickedPoint(500,200,0x00ff00)), 0,1280,720)
        val result = recognitionSelectionInputs(original, selection)
        assertEquals("0000FF:16", result["additionalColors"])
        assertEquals("8", result["tolerance"])
        assertEquals(original["region"], result["region"])
        assertEquals(original["foundVariable"], result["foundVariable"])
        assertNotNull(parseRecognitionArguments(contract, result, emptyList()).first)
    }
}

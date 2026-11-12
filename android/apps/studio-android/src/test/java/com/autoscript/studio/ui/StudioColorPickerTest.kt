package com.autoscript.studio

import com.autoscript.studio.generated.BlockPropertyEditor
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class StudioColorPickerTest {
    @Test fun bareHexAndDecimalHaveDifferentMeanings() {
        assertEquals(0xFF123456L, studioParseColor("123456", StudioColorFormat.RGB_BARE))
        assertEquals(0xFF01E240L, studioParseColor("123456", StudioColorFormat.RGB_DECIMAL))
        assertEquals(0xFF123456L, studioParseColor("#123456", StudioColorFormat.RGB_HEX))
        assertEquals(0xFF123456L, studioParseColor("0x123456", StudioColorFormat.RGB_HEX))
    }

    @Test fun argbUsesLeadingAlphaIncludingTransparentWhite() {
        assertEquals(0x00112233L, studioParseColor("#00112233", StudioColorFormat.ARGB_HEX))
        assertEquals(0xFFFFFFFFL, studioParseColor("#ffffffff", StudioColorFormat.ARGB_HEX))
        assertNull(studioParseColor("#FFFFFF", StudioColorFormat.ARGB_HEX))
        assertNull(studioParseColor("00112233", StudioColorFormat.ARGB_HEX))
    }

    @Test fun invalidAndOutOfRangeValuesAreNeverSilentlyAccepted() {
        for (format in StudioColorFormat.entries) {
            for (bad in listOf("", "#", "#GG1122", "#12345", "#123456789", "-1", "4294967296"))
                assertNull("$format: $bad", studioParseColor(bad, format))
        }
        for (format in listOf(StudioColorFormat.RGB_BARE, StudioColorFormat.RGB_HEX, StudioColorFormat.RGB_DECIMAL)) {
            assertNull(studioParseColor("#80112233", format))
        }
        assertNull(studioParseColor("16777216", StudioColorFormat.RGB_DECIMAL))
    }

    @Test fun formattingMatchesEachExistingConsumer() {
        val argb = 0x80112233L
        assertEquals("#80112233", studioFormatColor(argb, StudioColorFormat.ARGB_HEX))
        assertEquals("#112233", studioFormatColor(argb, StudioColorFormat.RGB_HEX))
        assertEquals("112233", studioFormatColor(argb, StudioColorFormat.RGB_BARE))
        assertEquals("1122867", studioFormatColor(argb, StudioColorFormat.RGB_DECIMAL))
        assertEquals("#80112233", studioFormatColor(argb, StudioColorFormat.RGB_OR_ARGB))
        assertEquals("#112233", studioFormatColor(0xFF112233L, StudioColorFormat.RGB_OR_ARGB))
        assertEquals(0x112233, parseColor(studioFormatColor(argb, StudioColorFormat.RGB_HEX)))
    }

    @Test fun hsvRoundTripPreservesAllChannelsAndAlpha() {
        val colors = listOf(0L, 0x00FFFFFFL, 0xFFFFFFFFL, 0xFF000000L, 0xFFFF0000L, 0xFF00FF00L, 0xFF0000FFL,
            0x80010203L, 0x7FFEFD01L) + List(2048) { (it.toLong() * 2654435761L) and 0xFFFFFFFFL }
        colors.forEach { assertEquals("color=%08X".format(it), it, StudioColorSelection.from(it).argb()) }
    }

    @Test fun hueWrapAndSaturationBrightnessEdgesStayInRgbRange() {
        assertEquals(0x80FF0000L, StudioColorSelection(0f, 1f, 1f, 128).argb())
        assertEquals(0x80FF0000L, StudioColorSelection(360f, 1f, 1f, 128).argb())
        assertEquals(0x800000FFL, StudioColorSelection(-120f, 1f, 1f, 128).argb())
        assertEquals(0x80FFFFFFL, StudioColorSelection(180f, 0f, 1f, 128).argb())
        assertEquals(0x80000000L, StudioColorSelection(180f, 1f, 0f, 128).argb())
    }

    @Test fun colorPropertyRoutingIncludesIntegerColorApisButNotTolerancesOrVariables() {
        val colors = BlockCatalog.all.flatMap { block -> block.properties.mapNotNull { property ->
            studioBlockColorFormat(property.path, property.editor)?.let { block.kind to (property.path to it) }
        } }
        assertEquals(13, colors.size)
        assertTrue(colors.any { it.first == "ocr.glyphdetails" && it.second.second == StudioColorFormat.RGB_DECIMAL })
        assertTrue(colors.any { it.first == "vision.findcolor" && it.second.second == StudioColorFormat.RGB_HEX })
        assertTrue(colors.any { it.first == "floating.show" && it.second.second == StudioColorFormat.RGB_OR_ARGB })
        assertNull(studioBlockColorFormat("rgbVariable", BlockPropertyEditor.STRING))
        assertNull(studioBlockColorFormat("tolerance", BlockPropertyEditor.INTEGER))
        assertNull(studioBlockColorFormat("red", BlockPropertyEditor.INTEGER))
    }

    @Test fun pickingOnePaletteColorKeepsOtherRowsAndTolerancesAndChecksDuplicates() {
        val draft = GlyphPaletteDraft.from(0x123456, 7, "ABCDEF:9").copy(selected = 1)
        val next = draft.edit(rgb = studioFormatColor(0xFF112233L, StudioColorFormat.RGB_BARE))
        assertEquals(2, next.colors.size)
        assertEquals(draft.colors[0], next.colors[0])
        assertEquals("9", next.current.tolerance)
        assertEquals(0x112233, requireNotNull(next.parsed())[1].rgb)
        assertNull(draft.edit(rgb = "123456").parsed())
    }

    @Test fun pickingSampleColorDoesNotRewriteCoordinatesTolerancesOrMalformedOtherRows() {
        val value = " 0, 0,#FFFFFF,7;bad,row;12,-9,#123456,3;"
        assertEquals(" 0, 0,#FFFFFF,7;bad,row;12,-9,#AABBCC,3;", studioReplaceSampleColor(value, 2, 2, "#AABBCC"))
        assertEquals("#112233,1,2,3;#000000,4,5,6", studioReplaceSampleColor("#FFFFFF,1,2,3;#000000,4,5,6", 0, 0, "#112233"))
    }

    @Test fun sampleTableUsesNumericRgbAndKeepsOtherFields() {
        val value = "[{\"dx\":-3,\"dy\":9,\"rgb\":0,\"tolerance\":8}]"
        val next = updateVisionSample(value, 0, "rgb", studioFormatColor(0xFFABCDEF, StudioColorFormat.RGB_DECIMAL))
        assertNull(visionSampleError(next))
        val row = JsonParser.parseString(next).asJsonArray[0].asJsonObject
        assertEquals(0xABCDEF, row["rgb"].asInt)
        assertEquals(-3, row["dx"].asInt)
        assertEquals(9, row["dy"].asInt)
        assertEquals(8, row["tolerance"].asInt)
    }

    @Test fun floatingShapeColorPreservesGeometryAndAllowsIncompleteManualDrafts() {
        val text = "[{\"kind\":\"circle\",\"x\":10,\"y\":20,\"radius\":3,\"color\":\"#FFFFFF\"}]"
        val next = updateFloatingShapeColor(text, 0, "#80112233")
        val original = JsonParser.parseString(text).asJsonArray[0].asJsonObject
        val changed = JsonParser.parseString(next).asJsonArray[0].asJsonObject
        assertEquals("#80112233", changed["color"].asString)
        changed.add("color", original["color"])
        assertEquals(original, changed)
        assertEquals("#", JsonParser.parseString(updateFloatingShapeColor(text, 0, "#")).asJsonArray[0].asJsonObject["color"].asString)
    }
}

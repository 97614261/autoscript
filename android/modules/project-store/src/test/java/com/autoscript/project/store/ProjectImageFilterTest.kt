package com.autoscript.project.store

import org.junit.Assert.*
import org.junit.Test

class ProjectImageFilterTest {
    @Test fun absoluteRelativeCustomAlphaAndExcludeMatchRustGolden() {
        var filter=ProjectImageFilter(rules=listOf(ProjectColorRule(0xff0000,0,0,0)))
        assertEquals(0x80ffffff.toInt(),filter.pixel(0x80ff0000.toInt()))
        assertEquals(0xff000000.toInt(),filter.pixel(0xfffe0000.toInt()))
        assertEquals(0,filter.pixel(0x00ff0000))
        filter=filter.copy(space="relative",rules=listOf(ProjectColorRule(0x806040,0,0,0)))
        assertEquals(-1,filter.pixel(0xffa08060.toInt()))
        assertEquals(0xff000000.toInt(),filter.pixel(0xffa08160.toInt()))
        filter=filter.copy(space="custom",rules=listOf(ProjectColorRule(0x806040,0,0,0,space="relative"),ProjectColorRule(0xa08060,0,0,0,exclude=true)))
        assertEquals(0xff000000.toInt(),filter.pixel(0xffa08060.toInt()))
        assertEquals(-1,filter.copy(invert=true).pixel(0xffa08060.toInt()))
        assertEquals(0x80a08060.toInt(),filter.copy(invert=true,output="keep").pixel(0x80a08060.toInt()))
        filter=filter.copy(output="keep",rules=listOf(ProjectColorRule(0,0,0,0,exclude=true)))
        assertEquals(0xff141e28.toInt(),filter.pixel(0xff141e28.toInt()))
        assertEquals(0,filter.pixel(0xff000000.toInt()))
    }

    @Test fun codecRejectsDuplicateUnknownFractionalAndInvalidFields() {
        val filter=ProjectImageFilter();val text=filter.encode()
        assertEquals(filter,ProjectImageFilter.decode(text))
        for (invalid in listOf(text.replace("\"version\":1","\"version\":1,\"version\":1"),
            text.replace("\"version\":1","\"extra\":1,\"version\":1"),
            text.replace("\"version\":1","\"version\":1.0"),
            text.replace("\"version\":1","\"version\":1e0"),
            text.replace("16777215","16777216"),text.replace("\"redTolerance\":16","\"redTolerance\":256"),
            text.replace("\"invert\":false","\"invert\":\"false\""),text+" {}","[]","null","x".repeat(8193))) {
            assertThrows(invalid.take(60),Exception::class.java) { ProjectImageFilter.decode(invalid) }
        }
        assertThrows(IllegalArgumentException::class.java) { ProjectImageFilter(rules=emptyList()) }
        assertThrows(IllegalArgumentException::class.java) { ProjectColorRule(0,space="custom") }
    }

    @Test fun budgetsAndPresetRoundTripAreStrictAndBounded() {
        val large=ProjectImageFilter(rules=List(32) { ProjectColorRule(it) })
        large.validatePixels(2_000_000)
        assertThrows(IllegalArgumentException::class.java) { large.validatePixels(4_194_304) }
        assertThrows(IllegalArgumentException::class.java) { large.validatePixels(0) }
        val presets=listOf(ProjectImageFilterPreset("白字",ProjectImageFilter()))
        assertEquals(presets,decodeImageFilterPresets(encodeImageFilterPresets(presets).toString(Charsets.UTF_8)))
        assertThrows(IllegalArgumentException::class.java) { encodeImageFilterPresets(presets+presets) }
        assertThrows(IllegalArgumentException::class.java) { encodeImageFilterPresets(List(33) { ProjectImageFilterPreset("f$it",ProjectImageFilter()) }) }
    }
}

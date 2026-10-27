package com.autoscript.project.store

import org.junit.Assert.*
import org.junit.Test

class ProjectGlyphColorTest {
    @Test fun paletteCodecIsBoundedAndDoesNotAcceptPartialDuplicateOrInvalidRules() {
        val colors=ProjectGlyphColor.decodeAdditional("FF0000:8;00ff00:255",0xffffff)
        assertEquals("FF0000:8;00FF00:255",ProjectGlyphColor.encodeAdditional(colors))
        assertTrue(ProjectGlyphColor.decodeAdditional("").isEmpty())
        for(bad in listOf("#FF0000:8","FF0000:256","FF0000:-1","FF0000:","FF0000:8;","FF0000:0;ff0000:2"))
            assertThrows(IllegalArgumentException::class.java) {ProjectGlyphColor.decodeAdditional(bad)}
        assertThrows(IllegalArgumentException::class.java) {ProjectGlyphColor.decodeAdditional("FF0000:0",0xff0000)}
        assertThrows(IllegalArgumentException::class.java) {ProjectGlyphColor.decodeAdditional("x".repeat(165))}
    }
}

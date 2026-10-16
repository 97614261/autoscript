package com.autoscript.studio

import com.google.gson.JsonObject
import org.junit.Assert.*
import org.junit.Test

class VisualFloatingDialogTest {
    @Test fun defaultWindowAndQuickShapeAreBoundedAndSourceIsDeclared() {
        val contract=requireNotNull(BlockCatalog.find("floating.show"))
        val inputs=blockPropertyTexts(contract,JsonObject())
        assertNull(floatingArgumentError("floating.show",inputs,emptyList(),setOf("text")))
        assertNotNull(floatingArgumentError("floating.show",inputs+("fontPx" to "97"),emptyList(),emptySet()))
        assertNotNull(floatingArgumentError("floating.show",inputs+("textVariable" to "missing"),emptyList(),emptySet()))
        assertNotNull(floatingArgumentError("floating.show",inputs+("imagePath" to "assets/images/undeclared.png"),emptyList(),emptySet()))
        assertNotNull(floatingArgumentError("floating.show",inputs+("shapesJson" to "[".repeat(200)+"0"+"]".repeat(200)),emptyList(),emptySet()))
        assertNull(floatingArgumentError("floating.bounds",mapOf("name" to "panel","resultVariable" to "text"),emptyList(),setOf("text")))
    }
}

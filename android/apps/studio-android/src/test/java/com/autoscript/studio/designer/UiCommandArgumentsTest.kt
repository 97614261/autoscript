package com.autoscript.studio

import org.junit.Assert.*
import org.junit.Test

class UiCommandArgumentsTest {
    @Test fun propertyConfigurationKeepsDraftAcrossFullscreenAndProtectsAllClosePaths() {
        val ui = java.io.File("src/main/java/com/autoscript/studio/designer/VisualUiCommandDialog.kt").readText()
        assertTrue(ui.contains("StudioPanelDialog(contract.title, ::requestClose"))
        assertTrue(ui.contains("onToggleFullScreen = { fullScreen = !fullScreen }"))
        assertTrue(ui.contains("if (inputs != initial) discard = true"))
        assertTrue(ui.contains("if (discard) StudioConfirmDialog"))
    }
    private val contract = requireNotNull(BlockCatalog.find("ui.command"))
    private fun parse(operation: String, result: String = "result") = parseBlockArguments(
        contract,
        mapOf("controlId" to "label", "operation" to operation, "value" to "stale value",
            "valueVariable" to "staleSource", "resultVariable" to result),
        null, emptyMap(), emptyList(),
    )

    @Test fun readsClearWriteOnlyOperandsAndRequireResult() {
        val args = requireNotNull(parse("get:alignment").first)
        assertEquals("", args["value"].asString)
        assertFalse(args.has("valueVariable"))
        assertEquals("result", args["resultVariable"].asString)
        assertNull(parse("get:x", "").first)
        assertNull(parse("get:x", "bad-name").first)
    }

    @Test fun writesDoNotKeepStaleReadResult() {
        val args = requireNotNull(parse("text").first)
        assertFalse(args.has("resultVariable"))
        assertEquals("staleSource", args["valueVariable"].asString)
    }
    @Test fun allNewStylePropertiesHaveNativeWriteAndReadChoices() {
        for (property in listOf("fontBold", "textUnderline", "borderColor", "borderWidthPx", "cornerPx")) {
            assertNotNull(parse(property).first)
            assertNotNull(parse("get:$property").first)
        }
    }
    @Test fun runtimeInputsAndConfigCommandsHaveRealNativeChoices() {
        for(property in listOf("hint","minimum","maximum","step")) {
            assertNotNull(parse(property).first); assertNotNull(parse("get:$property").first)
        }
        assertNotNull(parse("range").first)
        assertNotNull(parse("get:config").first); assertNotNull(parse("restoreConfig").first)
        assertNull(uiCommandTargetError(null,"ui.command","window","get:config"))
        assertNotNull(uiCommandTargetError(null,"ui.command","name","restoreConfig"))
    }
}

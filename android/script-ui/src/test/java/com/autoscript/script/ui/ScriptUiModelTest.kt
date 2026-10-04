package com.autoscript.script.ui

import org.junit.Assert.*
import org.junit.Test

class ScriptUiModelTest {
    @Test fun optionalStylesRoundTripAndOldLayoutsUseUnchangedDefaults() {
        val base = ScriptUiDefinition(fields = listOf(field(UiControl.LABEL)), version = 2)
        val saved = base.toJson()
        val p = saved.getAsJsonArray("fields")[0].asJsonObject.getAsJsonObject("ui")
        listOf("fontBold", "textUnderline", "borderColor", "borderWidthPx", "cornerPx").forEach(p::remove)
        assertEquals(base, ScriptUiDefinition.parse(saved.toString()))
        val styled = base.copy(fields = base.fields.map { it.copy(ui = it.ui!!.copy(fontBold = true, textUnderline = true, borderColor = "#80aBcD12", borderWidthPx = 32, cornerPx = 512)) })
        assertEquals(styled, ScriptUiDefinition.parse(styled.toJson().toString()))
        listOf("fontBold" to com.google.gson.JsonPrimitive("true"), "textUnderline" to com.google.gson.JsonPrimitive(1),
            "borderColor" to com.google.gson.JsonPrimitive("#FFFFFF"), "borderWidthPx" to com.google.gson.JsonPrimitive(33), "cornerPx" to com.google.gson.JsonNull.INSTANCE).forEach { (key, value) ->
            val invalid = styled.toJson()
            invalid.getAsJsonArray("fields")[0].asJsonObject.getAsJsonObject("ui").add(key, value)
            assertThrows(IllegalArgumentException::class.java) { ScriptUiDefinition.parse(invalid.toString()) }
        }
    }
    @Test fun expandedVersionTwoBudgetIsBoundedAndOldBudgetStays() {
        for (size in listOf(167, 256)) {
            val ui = ScriptUiDefinition(fields = (0 until size).map { field(UiControl.INPUT, "c$it") }, version = 2)
            assertEquals(size, ScriptUiDefinition.parse(ui.toJson().toString()).fields.size)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ScriptUiDefinition(fields = (0..256).map { field(UiControl.INPUT, "c$it") }, version = 2).validate()
        }
        assertThrows(IllegalArgumentException::class.java) {
            ScriptUiDefinition(fields = (0..32).map { field(UiControl.INPUT, "c$it").copy(ui = null) }).validate()
        }
    }
    private fun field(control: UiControl, id: String = "value") = UiField(id, control.label, control.valueKind, false,
        when (control.valueKind) { "integer" -> "3"; "boolean" -> "false"; "choice" -> "A"; else -> "" },
        options = if (control.valueKind == "choice") listOf("A", "B") else emptyList(), ui = UiPresentation(control = control.name))
    @Test fun allControlsRoundTrip() {
        val ui = ScriptUiDefinition(fields = UiControl.entries.mapIndexed { i, control -> field(control,"c$i") }, version = 2)
        ui.validate()
        assertEquals(ui, ScriptUiDefinition.parse(ui.toJson().toString()))
    }
    @Test fun oldFourFieldsKeepVersionOne() {
        val ui = ScriptUiDefinition(fields = listOf(field(UiControl.INPUT).copy(ui = null)))
        assertFalse(ui.toJson().has("version")); assertEquals(ui, ScriptUiDefinition.parse(ui.toJson().toString()))
    }
    @Test fun textAlignmentSurvivesSaveAndReload() {
        val ui = ScriptUiDefinition(fields = listOf("left", "center", "right").map { alignment ->
            field(UiControl.BUTTON, alignment).let { it.copy(ui = it.ui!!.copy(alignment = alignment)) }
        }, version = 2)
        ui.validate()
        val loaded = ScriptUiDefinition.parse(ui.toJson().toString())
        assertEquals(listOf("left", "center", "right"), loaded.fields.map { it.ui!!.alignment })
    }
    @Test fun jsonDoesNotCoerceTypesOrIgnoreUnknownStyles() {
        val ui = ScriptUiDefinition(fields = listOf(field(UiControl.NUMBER)), version = 2).toJson()
        val f = ui.getAsJsonArray("fields")[0].asJsonObject
        f.addProperty("initialValue", "3")
        assertThrows(IllegalArgumentException::class.java) { ScriptUiDefinition.parse(ui.toString()) }
        f.addProperty("initialValue",3); f.getAsJsonObject("ui").addProperty("script", "arbitrary")
        assertThrows(IllegalArgumentException::class.java) { ScriptUiDefinition.parse(ui.toString()) }
    }
    @Test fun cyclesAndForeignPagesAreRejected() {
        val c = field(UiControl.CONTAINER).let { it.copy(ui = it.ui!!.copy(parentId = it.id)) }
        assertThrows(IllegalArgumentException::class.java) { ScriptUiDefinition(fields = listOf(c), version = 2).validate() }
        assertThrows(IllegalArgumentException::class.java) { ScriptUiDefinition(fields = listOf(c.copy(ui = c.ui!!.copy(parentId = null,pageId = "absent"))), version = 2).validate() }
    }
    @Test fun bindingDataCannotInjectLua() {
        val text = field(UiControl.INPUT).copy(initialValue = "\"; error('bad'); --", ui = UiPresentation(binding = "global:username"))
        val checkbox = field(UiControl.CHECKBOX,"flag").copy(initialValue = "true", ui = UiPresentation(control = "CHECKBOX", binding = "flow:main:enabled"))
        val model = ScriptUiDefinition(fields = listOf(text,checkbox),version = 2)
        val lua = model.luaConfiguration(model.initialValues())
        assertTrue(lua.contains("\\\"; error('bad'); --"))
        assertTrue(lua.contains("[\"enabled\"]=1")); assertTrue(lua.contains("[\"flag\"]=true"))
    }
    @Test fun sliderAndProgressRangesCannotOverflowAndroidWidgets() {
        val progress = field(UiControl.PROGRESS).copy(minimum = -1, maximum = 10)
        assertThrows(IllegalArgumentException::class.java) { ScriptUiDefinition(fields = listOf(progress), version = 2).validate() }
        val slider = field(UiControl.SLIDER).copy(minimum = -1_000_000_000, maximum = 1_000_000_000)
        assertThrows(IllegalArgumentException::class.java) { ScriptUiDefinition(fields = listOf(slider), version = 2).validate() }
    }
    @Test fun canonicalDigestMatchesRustStringEscaping() {
        val json = com.google.gson.JsonObject().apply {
            addProperty("z", "中文<>&=\u2028\\u2028")
            add("a", com.google.gson.JsonNull.INSTANCE)
        }
        assertEquals("{\"a\":null,\"z\":\"中文<>&=\u2028\\\\u2028\"}", canonicalUiJson(json))
    }
}

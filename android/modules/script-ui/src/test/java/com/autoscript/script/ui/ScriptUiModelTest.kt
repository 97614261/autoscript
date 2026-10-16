package com.autoscript.script.ui

import org.junit.Assert.*
import org.junit.Test

class ScriptUiModelTest {
    @Test fun decimalIsFiniteTypedAndNeverTruncated() {
        val ui = ScriptUiDefinition(version = 2, fields = listOf(UiField("decimal", "小数", "number", true, "1.25", -2, 2, ui = UiPresentation(control = "DECIMAL", binding = "global:decimal"))))
        assertEquals(ui, ScriptUiDefinition.parse(ui.toJson().toString()))
        assertTrue(ui.luaConfiguration(mapOf("decimal" to "-0.125")).contains("[\"decimal\"]=-0.125"))
        val state = ScriptUiCommandState(ui, ui.initialValues())
        state.apply("decimal", "value", "1e-3")
        assertEquals("1e-3", state.values["decimal"])
        for (invalid in listOf("NaN", "Infinity", "1e999", "0x1", "2.1", " 1", "")) {
            assertThrows(IllegalArgumentException::class.java) { state.apply("decimal", "value", invalid) }
            assertEquals("1e-3", state.values["decimal"])
        }
    }

    @Test fun multiSelectionPreservesPunctuationAndRejectsInvalidValues() {
        val options = listOf("a,b", "引号\"", " 空格 ")
        val initial = uiSelectionsJson(options.take(2))
        val ui = ScriptUiDefinition(version = 2, fields = listOf(UiField("multi", "多选", "multiChoice", true, initial, options = options, ui = UiPresentation(control = "MULTISELECT", binding = "global:selection"))))
        assertEquals(ui, ScriptUiDefinition.parse(ui.toJson().toString()))
        assertTrue(ui.toJson().getAsJsonArray("fields")[0].asJsonObject.get("initialValue").isJsonArray)
        val state = ScriptUiCommandState(ui, ui.initialValues())
        for (invalid in listOf("a,b", "[]", "[1]", "['a,b']", "[\"a,b\"] trailing", "[\"missing\"]", "[\"a,b\",\"a,b\"]")) {
            assertThrows(IllegalArgumentException::class.java) { state.apply("multi", "value", invalid) }
            assertEquals(initial, state.values["multi"])
        }
        state.apply("multi", "items", " 空格 \nnew")
        assertEquals(listOf(" 空格 "), uiSelections(state.values.getValue("multi"), listOf(" 空格 ", "new"), true))
        assertTrue(ui.luaConfiguration(ui.initialValues()).contains("__ui_initial_globals"))
    }
    @Test fun switchAndPasswordPreserveTypedValuesAndCommands() {
        val ui = ScriptUiDefinition(fields = listOf(field(UiControl.SWITCH, "toggle"), field(UiControl.PASSWORD, "secret")), version = 2)
        val restored = ScriptUiDefinition.parse(ui.toJson().toString())
        assertEquals(ui, restored)
        assertTrue(restored.fields.all { it.input })
        val state = ScriptUiCommandState(restored, restored.initialValues())
        state.apply("toggle", "value", "true")
        state.apply("secret", "text", "test-only-value")
        assertEquals("true", state.read("toggle", ScriptUiRuntimeProperty.VALUE))
        assertEquals("test-only-value", state.read("secret", ScriptUiRuntimeProperty.VALUE))
        assertThrows(IllegalArgumentException::class.java) { state.apply("toggle", "value", "1") }
        assertTrue(restored.luaConfiguration(state.values).contains("[\"toggle\"]=true"))
    }
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
            val supplied = ui.fields.associate { it.id to "configured" }
            assertEquals(supplied, ui.validateValues(supplied))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ScriptUiDefinition(fields = (0..256).map { field(UiControl.INPUT, "c$it") }, version = 2).validate()
        }
        assertThrows(IllegalArgumentException::class.java) {
            ScriptUiDefinition(fields = (0..32).map { field(UiControl.INPUT, "c$it").copy(ui = null) }).validate()
        }
    }
    private fun field(control: UiControl, id: String = "value") = UiField(id, control.label, control.valueKind, false,
        when (control.valueKind) { "number" -> "3.0"; "multiChoice" -> "[]"; "integer" -> "3"; "boolean" -> "false"; "choice" -> "A"; else -> "" },
        options = if (control.valueKind in setOf("choice", "multiChoice")) listOf("A", "B") else emptyList(), ui = UiPresentation(control = control.name))
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

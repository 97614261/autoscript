package com.autoscript.studio

import com.autoscript.project.store.ProjectVariableType
import com.autoscript.script.ui.*
import org.junit.Assert.*
import org.junit.Test

class VisualUiParameterTest {
    private val model = ScriptUiDefinition(version = 2, pages = listOf(UiPage(), UiPage("second", "第二页")), fields = listOf(
        UiField("name", "姓名", "text", false, "", ui = UiPresentation()),
        UiField("other", "姓名", "text", false, "", ui = UiPresentation(pageId = "second")),
    ))
    private val targets = listOf(CalculationVariable("result", ProjectVariableType.STRING, "局部"), CalculationVariable("count", ProjectVariableType.INTEGER, "局部"))

    @Test fun `choices disambiguate same label across pages using stable ids`() {
        val choices = uiParameterChoices(model)
        assertEquals(listOf("name", "other"), choices.map { it.first })
        assertTrue(choices[1].second.contains("第二页"))
        assertTrue(choices[1].second.contains("other"))
        assertNull(uiParameterReadError(model, "other", "result", targets))
    }

    @Test fun `reader refuses missing controls untyped outputs and invalid names`() {
        assertNotNull(uiParameterReadError(null, "name", "result", targets))
        assertNotNull(uiParameterReadError(model, "deleted", "result", targets))
        assertNotNull(uiParameterReadError(model, "name", "unknown", targets))
        assertNotNull(uiParameterReadError(model, "name", "count", targets))
        assertNotNull(uiParameterReadError(model, "name", "bad.name", targets))
        assertNotNull(uiParameterReadError(model, "name", "", targets))
    }
}

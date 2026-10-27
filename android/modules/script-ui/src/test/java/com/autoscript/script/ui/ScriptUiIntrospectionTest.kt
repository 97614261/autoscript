package com.autoscript.script.ui

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class ScriptUiIntrospectionTest {
    @Test fun metadataPreservesPageIdsAndNeverIncludesValuesOrCallbacks() {
        val model=ScriptUiDefinition(version=2,pages=listOf(UiPage("first","首页"),UiPage("second","第二页")),fields=listOf(
            UiField("secret","密码","text",false,"PRIVATE_VALUE",ui=UiPresentation(control="PASSWORD",pageId="second",events=mapOf("click" to "privateFlow"))),
            UiField("label","文字","text",false,"PRIVATE_LABEL",ui=UiPresentation(control="LABEL",pageId="first"))))
        val pages=JsonParser.parseString(ScriptUiIntrospection.pages(model)).asJsonArray
        assertEquals(listOf("first","second"),pages.map {it.asJsonObject["id"].asString})
        val result=ScriptUiIntrospection.controls(model,"second")
        val controls=JsonParser.parseString(result).asJsonObject["controls"].asJsonArray
        assertEquals(1,controls.size());assertEquals("secret",controls[0].asJsonObject["id"].asString)
        for(forbidden in listOf("PRIVATE_VALUE","PRIVATE_LABEL","privateFlow","events","initialValue")) assertFalse(result.contains(forbidden))
        assertTrue(runCatching {ScriptUiIntrospection.controls(model,"missing")}.isFailure)
    }
}

package com.autoscript.script.ui

import org.junit.Assert.*
import org.junit.Test

class ScriptUiGroupsAndPositionsTest {
    private val model=ScriptUiDefinition(version=2,fields=listOf(
        UiField("group","组","text",false,"",ui=UiPresentation(control="CONTAINER")),
        UiField("nested","内组","text",false,"",ui=UiPresentation(control="CONTAINER",parentId="group")),
        UiField("number","数字","integer",false,"5",minimum=0,maximum=100,ui=UiPresentation(control="NUMBER",parentId="nested")),
        UiField("outside","外部","text",false,"original",ui=UiPresentation(control="INPUT")),
    ))
    @Test fun groupsIncludeNestedControlsButNotOtherControlsAndKeepDesign() {
        val state=ScriptUiCommandState(model,model.initialValues())
        assertEquals(setOf("group","nested","number"),state.group("group").map {it.id}.toSet())
        state.applyGroup("group","groupVisible","false")
        state.applyGroup("group","groupEnabled","false")
        assertEquals("false",state.read("number",ScriptUiRuntimeProperty.VISIBLE))
        assertEquals("false",state.read("nested",ScriptUiRuntimeProperty.ENABLED))
        assertEquals("true",state.read("outside",ScriptUiRuntimeProperty.VISIBLE))
        assertTrue(requireNotNull(model.fields.first().ui).visible)
        assertThrows(IllegalArgumentException::class.java) {state.applyGroup("outside","groupVisible","false")}
    }
    @Test fun resettingGroupValidatesAllDefaultsBeforePublishing() {
        val state=ScriptUiCommandState(model,model.initialValues())
        state.apply("number","value","20")
        state.apply("group","text","changed")
        state.apply("number","minimum","10")
        assertThrows(IllegalArgumentException::class.java) {state.applyGroup("group","groupReset","")}
        assertEquals("changed",state.values["group"])
        assertEquals("20",state.values["number"])
        state.apply("number","minimum","0")
        state.applyGroup("group","groupReset","")
        assertEquals("",state.values["group"])
        assertEquals("5",state.values["number"])
        assertEquals("original",state.values["outside"])
    }
    @Test fun positionsPersistClampAndRejectMalformedRequestsAtomically() {
        val positions=ScriptUiWindowPositions()
        val screen=UiScreenBounds(0,24,720,1256)
        val size=UiWindowSize(320,400)
        assertEquals(200 to 452,positions.locate("window",screen,size))
        assertEquals(400 to 880,positions.apply("window","{\"x\":999,\"y\":999}",screen,size))
        for(raw in listOf("{\"x\":1,\"x\":2,\"y\":3}","{\"x\":-1,\"y\":0}","{\"x\":1.0,\"y\":0}","{\"x\":0,\"y\":0,\"shell\":0}","{\"x\":0}","{\"x\":32769,\"y\":0}")) {
            assertThrows(Exception::class.java) {positions.apply("window",raw,screen,size)}
            assertEquals(400 to 880,positions.locate("window",screen,size))
        }
        assertTrue(positions.snapshot("window",screen,size,false).contains("\"visible\":false"))
        assertEquals(380 to 300,positions.locate("window",UiScreenBounds(0,0,700,700),size))
        positions.apply("bubble","{\"x\":0,\"y\":30}",screen,UiWindowSize(64,36))
        assertEquals(0 to 30,positions.locate("bubble",screen,UiWindowSize(64,36)))
        positions.reset()
        assertEquals(656 to 652,positions.locate("bubble",screen,UiWindowSize(64,36)))
    }
}

package com.autoscript.script.ui

import org.junit.Assert.*
import org.junit.Test

class ScriptUiRuntimeInputsTest {
    private val number=UiField("number","数量","integer",false,"3",minimum=0,maximum=10,ui=UiPresentation(control="NUMBER"))
    private val slider=UiField("slider","滑块","integer",false,"10",minimum=0,maximum=100,ui=UiPresentation(control="SLIDER"))
    private val password=UiField("secret","密码","text",false,"private",ui=UiPresentation(control="PASSWORD"))
    private val text=UiField("text","文本","text",true,"中文",ui=UiPresentation(control="INPUT",pageId="second",visible=false))
    private val model=ScriptUiDefinition(version=2,fields=listOf(number,slider,password,text),pages=listOf(UiPage(),UiPage("second","另一页")))
    @Test fun hintRangesAndStepsReadBackWithoutChangingOriginalDesign() {
        val state=ScriptUiCommandState(model,model.initialValues())
        state.apply("text","hint","请输入中文")
        assertEquals("请输入中文",state.read("text",ScriptUiRuntimeProperty.HINT))
        assertEquals("中文",state.values["text"])
        state.apply("number","range","{\"minimum\":2,\"maximum\":12}")
        assertEquals("2",state.read("number",ScriptUiRuntimeProperty.MINIMUM))
        assertEquals("12",state.read("number",ScriptUiRuntimeProperty.MAXIMUM))
        assertEquals(0L,number.minimum)
        state.apply("slider","step","5")
        assertEquals("5",state.read("slider",ScriptUiRuntimeProperty.STEP))
        state.apply("slider","range","{\"minimum\":-10,\"maximum\":30,\"step\":5}")
        assertEquals(-10L,state.effective(slider).minimum)
        assertEquals("10",state.values["slider"])
        state.reset()
        assertEquals("数量",state.read("number",ScriptUiRuntimeProperty.HINT))
        assertEquals("0",state.read("number",ScriptUiRuntimeProperty.MINIMUM))
        assertEquals("1",state.read("slider",ScriptUiRuntimeProperty.STEP))
    }
    @Test fun invalidRangesStepValuesAndKindsAreRejectedAtomically() {
        val state=ScriptUiCommandState(model,model.initialValues())
        listOf("0","3","101").forEach {step->assertThrows(IllegalArgumentException::class.java) {state.apply("slider","step",step)}}
        assertEquals("1",state.read("slider",ScriptUiRuntimeProperty.STEP))
        state.apply("slider","step","5")
        assertThrows(IllegalArgumentException::class.java) {state.apply("slider","value","12")}
        assertEquals("10",state.values["slider"])
        assertThrows(IllegalArgumentException::class.java) {state.apply("number","minimum","4")}
        assertThrows(IllegalArgumentException::class.java) {state.apply("number","range","{\"minimum\":8,\"maximum\":2}")}
        assertThrows(IllegalArgumentException::class.java) {state.apply("number","step","2")}
        assertThrows(IllegalArgumentException::class.java) {state.apply("text","minimum","0")}
        assertThrows(IllegalArgumentException::class.java) {state.apply("text","hint","x".repeat(257))}
        assertEquals(0L,state.effective(number).minimum)
    }
    @Test fun configRoundTripsCurrentValuesAndKeepsPasswordAndLayoutUntouched() {
        val state=ScriptUiCommandState(model,model.initialValues())
        state.apply("number","value","7")
        val snapshot=state.exportConfig()
        assertFalse(snapshot.contains("private")); assertFalse(snapshot.contains("secret"))
        state.apply("number","value","9")
        state.apply("secret","value","new-password")
        state.restoreConfig(snapshot)
        assertEquals("7",state.values["number"])
        assertEquals("new-password",state.values["secret"])
        assertEquals("false",state.read("text",ScriptUiRuntimeProperty.VISIBLE))
        assertEquals("3",number.initialValue)
    }
    @Test fun staleShapeMissingKeysWrongTypesAndInvalidValuesDoNotPartiallyRestore() {
        val state=ScriptUiCommandState(model,model.initialValues())
        val snapshot=state.exportConfig()
        state.apply("number","value","8")
        val before=state.values.toMap()
        assertThrows(IllegalArgumentException::class.java) {state.restoreConfig(snapshot.replace("\"3\"","\"999\""))}
        assertEquals(before,state.values)
        assertThrows(IllegalArgumentException::class.java) {state.restoreConfig(snapshot.replace("\"中文\"","42"))}
        assertEquals(before,state.values)
        assertThrows(IllegalArgumentException::class.java) {state.restoreConfig(snapshot.replace("\"number\":\"3\",",""))}
        state.apply("number","maximum","20")
        assertThrows(IllegalArgumentException::class.java) {state.restoreConfig(snapshot)}
        assertEquals("8",state.values["number"])
    }
    @Test fun boundedSnapshotsRejectDeepJsonAndDuplicateKeys() {
        val state=ScriptUiCommandState(model,model.initialValues())
        val snapshot=state.exportConfig()
        assertThrows(IllegalArgumentException::class.java) {state.restoreConfig(snapshot.replace("\"number\":\"3\"","\"number\":\"3\",\"number\":\"4\""))}
        assertThrows(IllegalArgumentException::class.java) {state.restoreConfig(snapshot.replace("\"中文\"","[".repeat(200)+"0"+"]".repeat(200)))}
        val fields=(1..8).map {text.copy(id="text$it",initialValue="x".repeat(2048))}
        val large=ScriptUiDefinition(version=2,fields=fields)
        assertThrows(IllegalArgumentException::class.java) {ScriptUiCommandState(large,large.initialValues()).exportConfig()}
    }
}

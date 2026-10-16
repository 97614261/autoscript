package com.autoscript.script.ui

import org.junit.Assert.*
import org.junit.Test

class ScriptUiCommandStateTest {
    private val model = ScriptUiDefinition(version = 2, fields = listOf(
        UiField("text", "文字", "text", false, "old", ui = UiPresentation(control = "INPUT")),
        UiField("choice", "选项", "choice", false, "A", options = listOf("A", "B"), ui = UiPresentation(control = "SELECT")),
        UiField("number", "数值", "integer", false, "3", minimum = 0, maximum = 10, ui = UiPresentation(control = "NUMBER")),
        UiField("flag", "开关", "boolean", false, "false", ui = UiPresentation(control = "CHECKBOX")),
        UiField("progress", "进度", "integer", false, "0", minimum = 0, maximum = 100, ui = UiPresentation(control = "PROGRESS")),
    ))

    @Test fun removedSelectionDoesNotMountAsFirstRowOnAnotherPageOrAfterRestore() {
        val state = ScriptUiCommandState(model, model.initialValues())
        val choice = model.fields.single { it.id == "choice" }
        assertEquals(0, state.selectedOptionIndex(choice))
        assertEquals(1, state.spinnerPosition(choice))
        state.apply("choice", "itemRemove", "1")
        assertEquals(-1, state.selectedOptionIndex(choice))
        assertEquals(0, state.spinnerPosition(choice))
        assertEquals("0", state.read("choice", ScriptUiRuntimeProperty.SELECTED_INDEX))
        assertEquals("", state.read("choice", ScriptUiRuntimeProperty.VALUE))
        state.apply("choice", "selectedIndex", "1")
        assertEquals(0, state.selectedOptionIndex(choice))
        assertEquals(1, state.spinnerPosition(choice))
        assertEquals("B", state.values["choice"])
        state.apply("choice", "itemsClear", "")
        assertEquals(-1, state.selectedOptionIndex(choice))
        state.reset()
        assertEquals(0, state.selectedOptionIndex(choice))
        assertEquals(listOf("A", "B"), choice.options)
    }

    @Test fun hiddenOrOffPageControlsRetainUpdatesWithoutWidgets() {
        val state = ScriptUiCommandState(model, model.initialValues())
        state.apply("text", "value", "new")
        state.apply("flag", "value", "true")
        state.apply("progress", "progress", "80")
        state.apply("text", "visible", "false")
        state.apply("text", "enabled", "false")
        assertEquals("new", state.values["text"])
        assertEquals("true", state.values["flag"])
        assertEquals("80", state.values["progress"])
        assertEquals(false, state.visibility["text"])
        assertEquals(false, state.enabled["text"])
    }

    @Test fun replacingOptionsPreservesSelectionOrFallsBackAndAcceptsNewValues() {
        val state = ScriptUiCommandState(model, model.initialValues())
        state.apply("choice", "items", "B\nA")
        assertEquals("A", state.values["choice"])
        state.apply("choice", "items", "C\nD")
        assertEquals("C", state.values["choice"])
        state.apply("choice", "value", "D")
        assertEquals("D", state.values["choice"])
        assertThrows(IllegalArgumentException::class.java) { state.apply("choice", "value", "A") }
    }

    @Test fun invalidUpdatesDoNotPartiallyMutateState() {
        val state = ScriptUiCommandState(model, model.initialValues())
        listOf("A\nA", "", "A\nB\u0000", "A\nB\t").forEach { options ->
            assertThrows(IllegalArgumentException::class.java) { state.apply("choice", "items", options) }
        }
        assertEquals("A", state.values["choice"])
        assertTrue(state.items.isEmpty())
        assertThrows(IllegalArgumentException::class.java) { state.apply("number", "value", "11") }
        assertThrows(IllegalArgumentException::class.java) { state.apply("flag", "value", "1") }
        assertThrows(IllegalArgumentException::class.java) { state.apply("progress", "progress", "101") }
        assertEquals("3", state.values["number"])
    }

    @Test fun resetDropsRuntimeOverridesButPreservesStateMapIdentity() {
        val state = ScriptUiCommandState(model, model.initialValues())
        val values = state.values
        state.apply("choice", "items", "C\nD")
        state.apply("text", "enabled", "false")
        state.reset()
        assertSame(values, state.values)
        assertEquals(model.initialValues(), values)
        assertTrue(state.items.isEmpty()); assertTrue(state.enabled.isEmpty())
    }
    @Test fun itemCommandsRetainIndexesRenameSelectionAndRejectPartialMutations() {
        val state=ScriptUiCommandState(model,model.initialValues())
        state.apply("choice","itemInsert","{\"index\":2,\"text\":\"中文\"}")
        assertEquals(listOf("A","中文","B"),state.items["choice"])
        state.apply("choice","selectedIndex","2")
        assertEquals("中文",state.values["choice"])
        assertEquals("2",state.read("choice",ScriptUiRuntimeProperty.SELECTED_INDEX))
        state.apply("choice","itemSet","{\"index\":2,\"text\":\"新名\"}")
        assertEquals("新名",state.values["choice"])
        assertThrows(IllegalArgumentException::class.java) {state.apply("choice","itemAdd","A")}
        assertThrows(IllegalArgumentException::class.java) {state.apply("choice","itemSet","{\"index\":1,\"text\":\"\\ud800\"}")}
        assertThrows(IllegalArgumentException::class.java) {state.apply("choice","itemSet","{\"index\":1,\"text\":"+ "[".repeat(200)+"0"+"]".repeat(200)+"}")}
        assertEquals(listOf("A","新名","B"),state.items["choice"])
        state.apply("choice","itemRemove","2")
        assertEquals("",state.values["choice"])
        assertEquals("0",state.read("choice",ScriptUiRuntimeProperty.SELECTED_INDEX))
        state.apply("choice","itemsClear","")
        assertEquals("0",state.read("choice",ScriptUiRuntimeProperty.ITEM_COUNT))
        state.apply("choice","itemAdd","恢复")
        state.apply("choice","selectedIndex","1")
        assertEquals("恢复",state.values["choice"])
        assertEquals(listOf("A","B"),model.fields[1].options)
    }
    @Test fun multiSelectionIndexesAreCanonicalAndRequiredControlsCannotBeCleared() {
        val field=UiField("multi","多选","multiChoice",true,"[\"甲\"]",options=listOf("甲","乙","丙"),ui=UiPresentation(control="MULTISELECT"))
        val definition=ScriptUiDefinition(version=2,fields=listOf(field))
        val state=ScriptUiCommandState(definition,definition.initialValues())
        state.apply("multi","selectedIndices","[3,1]")
        assertEquals("[\"甲\",\"丙\"]",state.values["multi"])
        assertEquals("[1,3]",state.read("multi",ScriptUiRuntimeProperty.SELECTED_INDICES))
        assertThrows(IllegalArgumentException::class.java) {state.apply("multi","selectedIndices","[1,1]")}
        assertThrows(IllegalArgumentException::class.java) {state.apply("multi","itemsClear","")}
        assertThrows(IllegalArgumentException::class.java) {state.apply("multi","selectedIndices","[]")}
        state.apply("multi","itemRemove","1")
        assertEquals("[\"丙\"]",state.values["multi"])
        assertEquals("[2]",state.read("multi",ScriptUiRuntimeProperty.SELECTED_INDICES))
    }
}

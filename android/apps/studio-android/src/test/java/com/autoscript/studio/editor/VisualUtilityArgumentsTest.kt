package com.autoscript.studio

import com.autoscript.project.store.ProjectVariableType
import com.google.gson.JsonObject
import org.junit.Assert.*
import org.junit.Test

class VisualUtilityArgumentsTest {
    @Test fun numberReaderUsesDeclaredTextAndIntegerIndexAndReturnsText() {
        val contract = requireNotNull(BlockCatalog.find("text.numberat"))
        val inputs = blockPropertyTexts(contract,JsonObject()) + mapOf("textVariable" to "s", "resultVariable" to "s", "index" to "2")
        assertNull(utilityArgumentError(contract,inputs,variables))
        assertEquals(ProjectVariableType.STRING,utilityResultType(contract.kind))
        assertNotNull(utilityArgumentError(contract,inputs + ("index" to "65"),variables))
        assertNotNull(utilityArgumentError(contract,inputs + ("textVariable" to "i"),variables))
        assertNotNull(utilityArgumentError(contract,inputs + ("resultVariable" to "i"),variables))
    }
    @Test fun pureUtilitiesUseFixedChoicesTypedOutputsAndByteBudgets() {
        val variables=listOf(CalculationVariable("i",ProjectVariableType.INTEGER,"global"),CalculationVariable("n",ProjectVariableType.NUMBER,"global"),CalculationVariable("s",ProjectVariableType.STRING,"global"))
        pureUtilityChoices.forEach { (key, choices) ->
            val kind=key.substringBefore(':'); val path=key.substringAfter(':')
            val contract=requireNotNull(BlockCatalog.find(kind))
            val output=if(utilityResultType(kind)==ProjectVariableType.STRING) "s" else "n"
            val inputs=blockPropertyTexts(contract,JsonObject())+mapOf("resultVariable" to output)
            choices.forEach { (value,_) -> assertNull("$kind:$value",utilityArgumentError(contract,inputs+(path to value),variables)) }
            assertNotNull(utilityArgumentError(contract,inputs+(path to "unknown"),variables))
        }
        val number=requireNotNull(BlockCatalog.find("text.formatnumber"))
        val inputs=blockPropertyTexts(number,JsonObject())+mapOf("resultVariable" to "s")
        assertNull(utilityArgumentError(number,inputs+mapOf("valueVariable" to "n","digitsVariable" to "i"),variables))
        for((key,value) in listOf("digits" to "10","minIntegerDigits" to "33","value" to "Infinity"))
            assertNotNull(utilityArgumentError(number,inputs+(key to value),variables))
        assertNotNull(utilityArgumentError(number,inputs+mapOf("digitsVariable" to "n"),variables))
        val regex=requireNotNull(BlockCatalog.find("text.regexreplace"))
        assertNotNull(utilityArgumentError(regex,blockPropertyTexts(regex,JsonObject())+mapOf("resultVariable" to "s","replacement" to "甲".repeat(1366)),variables))
    }
    private val variables=listOf(CalculationVariable("i",ProjectVariableType.INTEGER,"global"),CalculationVariable("n",ProjectVariableType.NUMBER,"global"),CalculationVariable("s",ProjectVariableType.STRING,"global"),CalculationVariable("w",ProjectVariableType.INTEGER,"global"),CalculationVariable("h",ProjectVariableType.INTEGER,"global"))
    @Test fun allUtilityBlocksHaveTypedConfigurationAndSearchableRealFunctions() {
        utilityApiKinds.forEach { (api,kind) ->
            val contract=requireNotNull(BlockCatalog.find(kind))
            val inputs=blockPropertyTexts(contract,JsonObject()).toMutableMap()
            if(kind=="system.screensize") {inputs["widthVariable"]="w";inputs["heightVariable"]="h"}
            else inputs["resultVariable"]=when(utilityResultType(kind)) {ProjectVariableType.INTEGER->"i";ProjectVariableType.NUMBER->"n";else->"s"}
            assertNull(kind,utilityArgumentError(contract,inputs,variables))
            val parsed=parseBlockArguments(contract,inputs,null,emptyMap(),emptyList())
            assertNotNull(kind,parsed.first)
            assertTrue(FunctionCatalog.visualGroups().flatMap {it.entries}.any {it.blockKind==kind})
            val entry=FunctionCatalog.search(FunctionCatalog.visualGroups(),api).single {it.second.blockKind==kind}.second
            assertTrue(entry.canInsert)
            assertFalse(entry.title.contains('.'))
        }
    }
    @Test fun wrongTypesBoundsAndSameScreenOutputsCannotBeSaved() {
        val contract=requireNotNull(BlockCatalog.find("math.randint"))
        val inputs=blockPropertyTexts(contract,JsonObject())+("resultVariable" to "i")
        assertNotNull(utilityArgumentError(contract,inputs+("minVariable" to "n"),variables))
        assertNotNull(utilityArgumentError(contract,inputs+("resultVariable" to "missing"),variables))
        assertNotNull(utilityArgumentError(contract,inputs+("min" to "101"),variables))
        assertNotNull(utilityArgumentError(contract,inputs+("min" to "NaN"),variables))
        assertNotNull(utilityArgumentError(contract,inputs+("min" to "1.5"),variables))
        val screen=requireNotNull(BlockCatalog.find("system.screensize"))
        assertNotNull(utilityArgumentError(screen,mapOf("widthVariable" to "w","heightVariable" to "w"),variables))
        val text=requireNotNull(BlockCatalog.find("math.randstr"))
        assertNotNull(utilityArgumentError(text,blockPropertyTexts(text,JsonObject())+mapOf("resultVariable" to "s","length" to "1025"),variables))
    }
    @Test fun characterSourcesAndFilePathsStayTypedAndBounded() {
        val text=requireNotNull(BlockCatalog.find("text.contains"))
        val args=blockPropertyTexts(text,JsonObject())+mapOf("resultVariable" to "i")
        assertNull(utilityArgumentError(text,args+mapOf("textVariable" to "s"),variables))
        assertNotNull(utilityArgumentError(text,args+mapOf("textVariable" to "i"),variables))
        assertNotNull(utilityArgumentError(text,args+mapOf("needle" to "甲".repeat(1400)),variables))
        val file=requireNotNull(BlockCatalog.find("files.copy"))
        val inputs=blockPropertyTexts(file,JsonObject())+mapOf("resultVariable" to "i")
        assertNotNull(utilityArgumentError(file,inputs+mapOf("destination" to "../outside"),variables))
        assertNotNull(utilityArgumentError(file,inputs+mapOf("path" to "C:/outside"),variables))
        assertNull(utilityArgumentError(file,inputs+mapOf("destinationVariable" to "s"),variables))
        val date=requireNotNull(BlockCatalog.find("date.format"))
        assertNotNull(utilityArgumentError(date,blockPropertyTexts(date,JsonObject())+mapOf("resultVariable" to "s","format" to "invalid"),variables))
    }
}

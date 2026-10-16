package com.autoscript.studio

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import org.junit.Assert.*
import org.junit.Test

class CompoundConditionEditorTest {
    @Test fun structuredTreesPreserveVariableOperandsAndEnforceLimits() {
        val leaf = conditionSeed("count", "greaterThan", "0", "limit")
        assertNotNull(parseCompoundCondition(leaf.toString()))
        val tree=JsonObject().apply { addProperty("type","all"); add("conditions",JsonArray().apply { add(leaf);add(JsonObject().apply { addProperty("type","not");add("condition",conditionSeed()) }) }) }
        assertEquals(tree,parseCompoundCondition(tree.toString()))
        assertNull(parseCompoundCondition(tree.deepCopy().apply { addProperty("unknown",true) }.toString()))
        assertNull(parseCompoundCondition(conditionSeed("bad;exit()").toString()))
        assertNull(parseCompoundCondition(conditionSeed("count","lessThan","false").toString()))
        assertNull(parseCompoundCondition("{"))
        assertNull(parseCompoundCondition("""{"type":"all","conditions":[]}"""))
        var deep=conditionSeed()
        repeat(7) {deep=JsonObject().apply {addProperty("type","not");add("condition",deep)}}
        assertNotNull(parseCompoundCondition(deep.toString()))
        assertNull(parseCompoundCondition(JsonObject().apply {addProperty("type","not");add("condition",deep)}.toString()))
        val many=JsonObject().apply { addProperty("type","all");add("conditions",JsonArray().apply { repeat(64) { add(conditionSeed()) } }) }
        assertNull(parseCompoundCondition(many.toString()))
    }
    @Test fun templateListsRoundTripWithoutExpressionsOrSilentResourceSubstitution() {
        val selected=listOf("assets/images/b.png","assets/images/a.png")
        assertEquals(selected,recognitionTemplatePaths(recognitionTemplateJson(selected)))
        assertTrue(recognitionTemplatePaths("not a list").isEmpty())
        val resource=com.google.gson.JsonObject().apply {addProperty("kind","image");addProperty("path",selected[0])}
        val contract=requireNotNull(BlockCatalog.find("vision.findallimages"))
        val args=requireNotNull(initialBlockArguments(contract,emptyList(),listOf(resource),""))
        val inputs=blockPropertyTexts(contract,args)
        assertNotNull(parseRecognitionArguments(contract,inputs,listOf(resource)).first)
        assertNull(parseRecognitionArguments(contract,inputs+("limit" to "65"),listOf(resource)).first)
        assertNull(parseRecognitionArguments(contract,inputs+mapOf("successAction" to "hold","allMatches" to "true"),listOf(resource)).first)
        assertNull(parseRecognitionArguments(contract,inputs+("imagePaths" to recognitionTemplateJson(selected)),listOf(resource)).first)
    }
}

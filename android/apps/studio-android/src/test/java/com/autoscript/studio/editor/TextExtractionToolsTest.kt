package com.autoscript.studio

import com.autoscript.project.store.ProjectVariableType
import org.junit.Assert.*
import org.junit.Test

class TextExtractionToolsTest {
    @Test fun presetsKeepArbitrarySourceAndOutputBindings() {
        val original = mapOf("text" to "custom input", "textVariable" to "文件内容", "pattern" to "old",
            "patternVariable" to "旧规则", "resultVariable" to "全部结果")
        for (rule in textExtractionRules) {
            val changed = applyTextExtractionRule(original, rule.id)
            assertEquals(rule.pattern, changed["pattern"])
            assertEquals("", changed["patternVariable"])
            assertEquals(original["text"], changed["text"])
            assertEquals(original["textVariable"], changed["textVariable"])
            assertEquals(original["resultVariable"], changed["resultVariable"])
        }
        assertEquals("旧规则", original["patternVariable"])
    }

    @Test fun customRulePreservesDraftPatternAndDoesNotModifyOtherInputs() {
        val original = mapOf("pattern" to "订单[0-9]+", "patternVariable" to "rule", "textVariable" to "接口结果")
        assertEquals(original + ("patternVariable" to ""), applyTextExtractionRule(original, "custom"))
        assertThrows(IllegalArgumentException::class.java) { applyTextExtractionRule(original, "unknown") }
    }

    @Test fun genericMenuReusesTypedExistingFunctionsWithoutImplicitOcrVariables() {
        assertEquals(textExtractionTools.size, textExtractionKinds.size)
        for (kind in textExtractionKinds) {
            val contract = requireNotNull(BlockCatalog.find(kind))
            assertTrue(kind in visualUtilityKinds)
            val resultType = if (kind == "text.regexmatch") ProjectVariableType.INTEGER else ProjectVariableType.STRING
            assertEquals(resultType, utilityResultType(kind))
            val inputs = blockPropertyTexts(contract, com.google.gson.JsonObject()) + ("resultVariable" to "结果")
            val variables = listOf(CalculationVariable("任意文本", ProjectVariableType.STRING, "全局"),
                CalculationVariable("结果", resultType, "局部"))
            assertNull(kind, utilityArgumentError(contract, inputs + ("textVariable" to "任意文本"), variables))
        }
        assertTrue(textExtractionKinds.containsAll(setOf("text.regexall", "text.regexextract", "text.split", "text.betweennth")))
    }

    @Test fun allRegexActionsAreInsertableInTextLibraryWithTypedResultsAndRawPatterns() {
        val groups = FunctionCatalog.visualGroups()
        val found = FunctionCatalog.search(groups, "正则", "文本").map { it.second }
        assertEquals(setOf("text.regexall", "text.regexextract", "text.regexmatch", "text.regexreplace"), textRuleKinds)
        assertTrue(textExtractionKinds.containsAll(textRuleKinds))
        textRuleKinds.forEach { kind ->
            val entry = found.single { it.blockKind == kind }
            assertTrue(entry.canInsert)
            assertEquals(kind, FunctionCatalog.blockKindOf(entry.snippet))
            assertEquals("正则", entry.section)
            assertTrue(entry.notes.contains(textRegexResultHint(kind)))
            val contract = requireNotNull(BlockCatalog.find(kind))
            val raw = "\\p{Han}+|\\d+"
            val inputs = blockPropertyTexts(contract, com.google.gson.JsonObject()) +
                mapOf("pattern" to raw, "resultVariable" to "结果")
            val parsed = parseBlockArguments(contract, inputs, null, emptyMap(), emptyList())
            assertNull(parsed.second)
            assertEquals(raw, requireNotNull(parsed.first).get("pattern").asString)
        }
    }
}

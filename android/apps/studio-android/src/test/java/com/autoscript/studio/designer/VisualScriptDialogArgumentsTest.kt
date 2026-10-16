package com.autoscript.studio

import org.junit.Assert.*
import org.junit.Test

class VisualScriptDialogArgumentsTest {
    @Test fun confirmationHasTypedResultAndExactReversibleArguments() {
        val contract = requireNotNull(BlockCatalog.find("ui.confirm"))
        val inputs = blockPropertyTexts(contract, com.google.gson.JsonObject()) + ("resultVariable" to "choice")
        assertNull(scriptDialogArgumentError(contract.kind,inputs,emptySet(),setOf("choice")))
        assertNotNull(scriptDialogArgumentError(contract.kind,inputs,emptySet(),setOf("number")))
        val parsed = parseBlockArguments(contract,inputs,null,emptyMap(),emptyList())
        assertNull(parsed.second); assertEquals(60000, parsed.first!!.get("timeoutMs").asInt)
        assertEquals("choice", parsed.first!!.get("resultVariable").asString)
        assertTrue(FunctionCatalog.visualGroups().any { group -> group.entries.any { it.blockKind == "ui.confirm" } })
    }
    @Test fun layoutRequiresSavedPageAndAllowsDiscardingCloseResult() {
        val contract = requireNotNull(BlockCatalog.find("ui.dialog"))
        val inputs = blockPropertyTexts(contract, com.google.gson.JsonObject())
        assertNull(scriptDialogArgumentError(contract.kind, inputs,setOf("main"),emptySet()))
        assertNotNull(scriptDialogArgumentError(contract.kind, inputs,setOf("other"),emptySet()))
        assertNotNull(scriptDialogArgumentError(contract.kind, inputs + ("timeoutMs" to "0"),setOf("main"),emptySet()))
        val parsed = parseBlockArguments(contract,inputs,null,emptyMap(),emptyList())
        assertNull(parsed.second); assertFalse(parsed.first!!.has("resultVariable"))
        assertTrue(parsed.first!!.get("blockOutside").asBoolean)
        assertTrue(requireNotNull(BlockCatalog.find("ui.dialogclose")).properties.isEmpty())
    }
}

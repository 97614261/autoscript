package com.autoscript.studio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LuaSnippetGateTest {
    @Test
    fun `every catalog snippet passes`() {
        LegacyFunctionCatalog.luaGroups().flatMap { it.entries }.forEach { entry ->
            assertNull("${entry.title} 应当可插入", LuaSnippetGate.reject(entry.snippet))
        }
    }

    @Test
    fun `plain lua control flow passes`() {
        listOf("break\n", "return\n", "::label::\n", "goto label\n", "for i = 1, 3 do\n    \nend\n", "local x = 1\n")
            .forEach { assertNull(it, LuaSnippetGate.reject(it)) }
    }

    @Test
    fun `unknown namespaces are rejected with the offending calls listed`() {
        assertEquals(
            "Vision.findImage 不在当前脚本 API 契约里（尚未实现），已阻止插入",
            LuaSnippetGate.reject("Vision.findImage(\"a.png\", 0.9)\n"),
        )
        assertEquals(
            "Runtime.getLoopCount、Log.info 不在当前脚本 API 契约里（尚未实现），已阻止插入",
            LuaSnippetGate.reject("Runtime.getLoopCount()\nLog.info(\"x\")\nRuntime.getLoopCount()\n"),
        )
    }

    @Test
    fun `calls inside strings and comments do not count`() {
        assertNull(LuaSnippetGate.reject("-- Net.get(url) 以后再说\nlocal s = \"Log.info(1)\"\nTask.sleep(10)\n"))
        assertNull(LuaSnippetGate.reject("--[[ Vision.findImage(x) ]]\nInput.tap(1, 2)\n"))
    }
}

package com.autoscript.studio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LuaSnippetGateTest {
    private fun snippet(name: String): String = LegacyFunctionCatalog.luaGroups()
        .flatMap { it.entries }
        .single { it.snippet.startsWith("$name(") }
        .snippet

    @Test
    fun `every catalog snippet passes`() {
        LegacyFunctionCatalog.luaGroups().flatMap { it.entries }.forEach { entry ->
            assertNull("${entry.title} 应当可插入", LuaSnippetGate.reject(entry.snippet))
        }
    }

    /** 高频“快速插入”项必须是可直接执行的字面量调用，不能写入未定义占位符。 */
    @Test
    fun `common quick inserts use runnable literal defaults`() {
        assertEquals("Task.sleep(1000)\n", snippet("Task.sleep"))
        assertEquals("Input.tap(0, 0)\n", snippet("Input.tap"))
        assertEquals("Input.swipe(0, 0, 100, 100, 300)\n", snippet("Input.swipe"))
        assertEquals("Log.info(\"message\")\n", snippet("Log.info"))
        assertNull(LuaSnippetGate.reject("System.elapsedRealtimeMillis()\n"))
    }

    @Test
    fun `plain lua control flow passes`() {
        listOf("break\n", "return\n", "::label::\n", "goto label\n", "for i = 1, 3 do\n    \nend\n", "local x = 1\n")
            .forEach { assertNull(it, LuaSnippetGate.reject(it)) }
    }

    @Test
    fun `control flow templates are complete lua skeletons`() {
        val entries = LegacyFunctionCatalog.luaGroups().flatMap { it.entries }.associateBy { it.title }
        assertEquals(
            "if condition then\n    -- 条件成立时执行\nend\n",
            entries.getValue("条件判断").snippet,
        )
        assertEquals(
            "for index = 1, 10 do\n    -- 每次循环执行\nend\n",
            entries.getValue("计数循环").snippet,
        )
        assertEquals(
            "repeat\n    -- 至少执行一次\nuntil condition\n",
            entries.getValue("重复直到").snippet,
        )
    }

    @Test
    fun `unknown namespaces are rejected with the offending calls listed`() {
        assertEquals(
            "Vision.findImage 不在当前脚本 API 契约里（尚未实现），已阻止插入",
            LuaSnippetGate.reject("Vision.findImage(\"a.png\", 0.9)\n"),
        )
        assertEquals(
            "Runtime.getLoopCount 不在当前脚本 API 契约里（尚未实现），已阻止插入",
            LuaSnippetGate.reject("Runtime.getLoopCount()\nLog.info(\"x\")\nRuntime.getLoopCount()\n"),
        )
    }

    @Test
    fun `unknown functions inside a known namespace are rejected`() {
        assertEquals(
            "Screen.notImplemented 不在当前脚本 API 契约里（尚未实现），已阻止插入",
            LuaSnippetGate.reject("Screen.notImplemented()\n"),
        )
    }

    @Test
    fun `calls inside strings and comments do not count`() {
        assertNull(LuaSnippetGate.reject("-- Net.get(url) 以后再说\nlocal s = \"Log.info(1)\"\nTask.sleep(10)\n"))
        assertNull(LuaSnippetGate.reject("--[[ Vision.findImage(x) ]]\nInput.tap(1, 2)\n"))
    }
}

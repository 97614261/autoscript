package com.autoscript.studio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LuaSnippetGateTest {
    @Test
    fun `generated contract calls remain whitelisted without a Lua insertion catalogue`() {
        com.autoscript.studio.generated.GeneratedFunctionDocumentation.all.forEach { doc ->
            assertNull(doc.name, LuaSnippetGate.reject("${doc.name}()\n"))
        }
        listOf("Task.sleep(1000)\n", "Input.tap(0, 0)\n", "Input.swipe(0, 0, 100, 100, 300)\n",
            "Log.info(\"message\")\n", "System.elapsedRealtimeMillis()\n",
            "Input.pointerDown(12, 34)\nInput.pointerMove(20, 40)\nInput.pointerUp()\n")
            .forEach { assertNull(it, LuaSnippetGate.reject(it)) }
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

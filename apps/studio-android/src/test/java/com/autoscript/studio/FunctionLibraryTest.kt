package com.autoscript.studio

import com.autoscript.runtime.api.generated.GeneratedApiContracts
import com.autoscript.studio.generated.GeneratedFunctionDocumentation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FunctionLibraryTest {
    private val lua = FunctionCatalog.luaGroups().flatMap { it.entries }

    @Test
    fun `every real api appears once with schema documentation and capability`() {
        val docs = GeneratedFunctionDocumentation.all
        assertEquals(GeneratedApiContracts.all.map { it.name }.toSet(), docs.map { it.name }.toSet())
        docs.forEach { doc ->
            val entry = lua.single { it.apiName == doc.name }
            assertEquals(doc.summary, entry.detail)
            assertEquals(setOf(doc.capability), entry.requiredCapabilities)
            assertEquals(doc.since, entry.since)
            assertEquals(doc.parameters.map { it.name }, entry.parameters)
            assertEquals(doc.parameters.map { it.required }, entry.parameterInfo.map { it.required })
            assertEquals(doc.parameters.map { it.summary }, entry.parameterInfo.map { it.description })
            assertTrue(entry.result.contains(doc.returnSummary))
            assertFalse("需要中文名称：${doc.name}", entry.title.contains('.'))
            assertNull("误拦截：${doc.name}", LuaSnippetGate.reject(entry.snippet))
        }
    }

    @Test
    fun `all blocks remain addressable and metadata matches actual properties`() {
        val entries = FunctionCatalog.visualGroups().flatMap { it.entries }
        assertEquals(BlockCatalog.all.map { it.kind }.toSet(), entries.map { it.blockKind }.toSet())
        assertEquals(entries.size, entries.map { it.blockKind }.distinct().size)
        BlockCatalog.all.forEach { block ->
            val entry = entries.single { it.blockKind == block.kind }
            assertEquals(block.kind, FunctionCatalog.blockKindOf(entry.snippet))
            assertEquals(block.properties.map { it.label }, entry.parameterInfo.map { it.name })
            assertEquals(block.properties.map { it.required }, entry.parameterInfo.map { it.required })
            assertEquals(block.requiredCapabilities, entry.requiredCapabilities)
            assertTrue(entry.result.isNotEmpty())
        }
        assertTrue(FunctionCatalog.visualGroups().single { it.label == "按键" }.entries.all { it.blockKind!!.startsWith("input.") })
    }

    @Test
    fun `search supports multiple terms english aliases descriptions and parameter text`() {
        val groups = FunctionCatalog.luaGroups()
        assertEquals("Screen.findGray", FunctionCatalog.search(groups, "灰度 归一化").single().second.apiName)
        assertEquals("Timer.every", FunctionCatalog.search(groups, "TIMER.EVERY").first().second.apiName)
        assertEquals("Ocr.alphanumeric", FunctionCatalog.search(groups, "最低逐字置信度").single().second.apiName)
        assertTrue(FunctionCatalog.search(groups, "nil").any { it.second.apiName == "Screen.findImage" })
        assertTrue(FunctionCatalog.search(groups, "绝对不存在的函数").isEmpty())
        assertEquals(groups.sumOf { it.entries.size }, FunctionCatalog.search(groups, "  ").size)
        assertTrue(FunctionCatalog.search(groups, "", "按键").all { it.first == "按键" })
    }

    @Test
    fun `new APIs are insertable but fictitious APIs are still blocked`() {
        listOf("Task.spawn", "Task.cancel", "Timer.every", "Timer.cancel", "Screen.crop", "Screen.findImages",
            "Screen.findGray", "Ocr.alphanumeric", "Input.tapScreen", "Input.pointerDownScreen").forEach { name ->
            assertNull(LuaSnippetGate.reject(lua.single { it.apiName == name }.snippet))
        }
        assertTrue(LuaSnippetGate.reject("Screen.notImplemented()\n")!!.contains("尚未实现"))
    }

    @Test
    fun `visual outputs include frame match status and OCR text not just coordinates`() {
        val entries = FunctionCatalog.visualGroups().flatMap { it.entries }.associateBy { it.blockKind }
        assertTrue(entries.getValue("screen.capture").result.contains("帧变量"))
        assertTrue(entries.getValue("vision.findimage").result.contains("找到变量"))
        assertTrue(entries.getValue("vision.findimage").result.contains("命中模板变量"))
        assertTrue(entries.getValue("ocr.glyph").result.contains("文本变量"))
        assertTrue(entries.getValue("ocr.glyph").result.contains("得分变量"))
        assertTrue(entries.getValue("variable.calculate").result.contains("目标变量"))
    }

    @Test
    fun `examples retain returned handles and disclose prerequisites and resource paths`() {
        assertTrue(lua.single { it.apiName == "Screen.capture" }.snippet.startsWith("local captureId = "))
        assertTrue(lua.single { it.apiName == "Screen.cache" }.snippet.startsWith("local frame = "))
        assertTrue(lua.single { it.apiName == "Screen.loadImage" }.notes.contains("实际文件"))
        assertTrue(lua.single { it.apiName == "Screen.findImage" }.notes.contains("frame、template"))
        assertTrue(lua.single { it.apiName == "Screen.findImage" }.result.contains("nil"))
        assertFalse(lua.single { it.apiName == "Screen.findImage" }.parameterInfo.last().required)
        assertEquals("Input.tap(0, 0)\n", lua.single { it.apiName == "Input.tap" }.snippet)
    }

    @Test
    fun `dialog is compact on tall screens and bounded on landscape screens`() {
        assertEquals(435, functionLibraryHeightDp(1000, 48))
        assertEquals(303, functionLibraryHeightDp(1000, 2))
        assertEquals(312, functionLibraryHeightDp(360, 48))
        assertEquals(435, functionLibraryHeightDp(1000, 10000))
    }

    @Test fun `page parameter reader is discoverable in both libraries and retains real api`() {
        val visual = FunctionCatalog.search(FunctionCatalog.visualGroups(), "读取页面参数", "界面").single().second
        assertEquals("ui.get", visual.blockKind)
        assertEquals("ui.get", FunctionCatalog.blockKindOf(visual.snippet))
        val entry = FunctionCatalog.search(FunctionCatalog.luaGroups(), "读取页面参数", "界面").single().second
        assertEquals("UI.getValue", entry.apiName)
        assertTrue(entry.snippet.contains("UI.getValue(\"control1\")"))
        assertTrue(entry.notes.contains("RunnerConfig"))
        assertTrue(FunctionCatalog.search(FunctionCatalog.luaGroups(), "读取控件值").any { it.second.apiName == "UI.getValue" })
    }
}

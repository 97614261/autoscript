package com.autoscript.studio

import com.autoscript.runtime.api.generated.GeneratedApiContracts
import com.autoscript.studio.generated.GeneratedFunctionDocumentation
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class FunctionLibraryTest {
    private val visualGroups = FunctionCatalog.visualGroups()
    private val visual = visualGroups.flatMap { it.entries }
    private val apiGroups = FunctionCatalog.apiGroups()
    private val references = apiGroups.flatMap { it.entries }

    @Test fun everyRealApiAppearsOnceAsReadOnlyGeneratedDocumentation() {
        val docs = GeneratedFunctionDocumentation.all
        assertEquals(GeneratedApiContracts.all.map { it.name }.toSet(), docs.map { it.name }.toSet())
        assertEquals(docs.size, references.size)
        docs.forEach { doc ->
            val entry = references.single { it.apiName == doc.name }
            assertEquals(doc.summary, entry.detail)
            assertEquals(setOf(doc.capability), entry.requiredCapabilities)
            assertEquals(doc.since, entry.since)
            assertEquals(doc.parameters.map { it.name }, entry.parameters)
            assertEquals(doc.parameters.map { it.required }, entry.parameterInfo.map { it.required })
            assertEquals(doc.parameters.map { it.summary }, entry.parameterInfo.map { it.description })
            assertTrue(entry.result.contains(doc.returnSummary))
            assertFalse(doc.name, entry.title.contains('.'))
            assertFalse(entry.canInsert)
            assertEquals("", entry.snippet)
            assertNull(entry.blockKind)
        }
    }

    @Test fun allBlocksRemainAddressableOnceWithTheirActualMetadata() {
        assertEquals(BlockCatalog.all.size, visual.size)
        assertEquals(BlockCatalog.all.map { it.kind }.toSet(), visual.map { it.blockKind }.toSet())
        assertEquals(visual.size, visual.map { it.blockKind }.distinct().size)
        BlockCatalog.all.forEach { block ->
            val entry = visual.single { it.blockKind == block.kind }
            assertTrue(entry.canInsert)
            assertEquals(block.kind, FunctionCatalog.blockKindOf(entry.snippet))
            assertEquals(block.properties.map { it.label }, entry.parameterInfo.map { it.name })
            assertEquals(block.properties.map { it.required }, entry.parameterInfo.map { it.required })
            assertEquals(block.requiredCapabilities, entry.requiredCapabilities)
            assertTrue(entry.result.isNotEmpty())
        }
    }

    @Test fun groupsArePurposeSpecificAndOrderedWithoutCatchAllBuckets() {
        val expected = mapOf("text." to "文本", "data." to "集合", "date." to "时间",
            "audio." to "音频", "floating." to "浮窗", "legacy." to "旧版兼容",
            "clipboard." to "系统", "app." to "系统", "input." to "按键", "files." to "文件")
        expected.forEach { (prefix, label) ->
            val kinds = BlockCatalog.all.filter { it.kind.startsWith(prefix) }.map { it.kind }.toSet()
            assertTrue(kinds.isNotEmpty())
            assertTrue(visualGroups.single { it.label == label }.entries.map { it.blockKind }.containsAll(kinds))
        }
        listOf(visualGroups, apiGroups).forEach { groups ->
            assertEquals(groups.map { it.label }.size, groups.map { it.label }.distinct().size)
            assertTrue(groups.all { it.label in FUNCTION_GROUP_ORDER && it.entries.isNotEmpty() })
            assertEquals(groups.map { FUNCTION_GROUP_ORDER.indexOf(it.label) }.sorted(),
                groups.map { FUNCTION_GROUP_ORDER.indexOf(it.label) })
        }
    }

    @Test fun apiSearchFindsCorrectVisualKindsWithoutOldLuaSnippets() {
        mapOf("Screen.findGray" to "vision.findgray", "Timer.every" to "timer.every",
            "Input.tapScreen" to "input.tap", "Files.read" to "files.read",
            "Audio.play" to "audio.play", "UI.getValue" to "ui.get",
            "Screen.findImages" to "vision.findimage", "Screen.cache" to "screen.capture",
            "Text.repeatText" to "text.repeat", "Legacy.duoDianZhaoSe" to "legacy.duodianzhaose"
        ).forEach { (api, kind) ->
            assertEquals(kind, FunctionCatalog.search(visualGroups, api).first().second.blockKind)
        }
        assertTrue(FunctionCatalog.search(visualGroups, "绝对不存在的函数").isEmpty())
        assertEquals(visual.size, FunctionCatalog.search(visualGroups, "  ").size)
        assertTrue(FunctionCatalog.search(visualGroups, "", "文本").all { it.first == "文本" })
        assertEquals("Screen.findGray", FunctionCatalog.search(apiGroups, "灰度 归一化").single().second.apiName)
        assertEquals("Timer.every", FunctionCatalog.search(apiGroups, "TIMER.EVERY").first().second.apiName)
        assertEquals("Ocr.alphanumeric", FunctionCatalog.search(apiGroups, "最低逐字置信度").single().second.apiName)
    }

    @Test fun everyUtilityApiIsSearchableInItsVisualBlock() {
        utilityApiKinds.forEach { (api, kind) ->
            assertEquals(kind, FunctionCatalog.search(visualGroups, api).first().second.blockKind)
        }
    }

    @Test fun compatibilityBlocksArePreservedButIsolatedFromModernImageTools() {
        val legacy = visualGroups.single { it.label == "旧版兼容" }.entries
        assertEquals(BlockCatalog.all.filter { it.kind.startsWith("legacy.") }.map { it.kind }.toSet(),
            legacy.map { it.blockKind }.toSet())
        assertTrue(visualGroups.single { it.label == "图像" }.entries.none { it.blockKind!!.startsWith("legacy.") })
        assertTrue(legacy.all { it.canInsert && it.requiredCapabilities.isNotEmpty() })
    }

    @Test fun lowLevelApiContractsAreNotFictitiousInsertableBlocks() {
        listOf("UI.waitEvent", "Ocr.loadDictionary", "Ocr.releaseDictionary", "Input.resolvePoint").forEach { api ->
            val entry = references.single { it.apiName == api }
            assertFalse(entry.canInsert)
            assertEquals("", entry.snippet)
            assertNull(FunctionCatalog.blockKindOf(entry.snippet))
            assertTrue(FunctionCatalog.search(visualGroups, api).isEmpty())
        }
    }

    @Test fun outputsIncludeFrameMatchStatusAndOcrText() {
        val entries = visual.associateBy { it.blockKind }
        assertTrue(entries.getValue("screen.capture").result.contains("帧变量"))
        assertTrue(entries.getValue("vision.findimage").result.contains("找到变量"))
        assertTrue(entries.getValue("vision.findimage").result.contains("命中模板变量"))
        assertTrue(entries.getValue("ocr.glyph").result.contains("文本变量"))
        assertTrue(entries.getValue("ocr.glyph").result.contains("得分变量"))
        assertTrue(entries.getValue("variable.calculate").result.contains("目标变量"))
    }

    @Test fun fileSectionsAreSearchableAndDoNotMisstateBinaryLimits() {
        val files = visualGroups.single { it.label == "文件" }
        assertEquals(setOf("文件管理", "目录", "文本文件", "键值存储", "编码与压缩"), files.entries.map { it.section }.toSet())
        assertTrue(FunctionCatalog.search(visualGroups, "键值存储", "文件").all { it.second.blockKind!!.startsWith("files.kv") })
        assertTrue(files.entries.all { it.notes.contains("文本单文件") && it.notes.contains("各自契约限额") })
        assertTrue(files.entries.single { it.blockKind == "files.zip" }.detail.contains("MiB"))
    }

    @Test fun headingsRetainCategoryWhenShowingAllFunctions() {
        assertEquals("文件 · 文本文件", librarySectionHeading("文件", "文本文件", null))
        assertEquals("文本文件", librarySectionHeading("文件", "文本文件", "文件"))
        assertEquals("循环", librarySectionHeading("循环", "", null))
        assertEquals("", librarySectionHeading("循环", "", "循环"))
    }

    @Test fun dialogIsCompactAndBoundedInLandscape() {
        assertEquals(379, functionLibraryHeightDp(1000, 48))
        assertEquals(271, functionLibraryHeightDp(1000, 2))
        assertEquals(312, functionLibraryHeightDp(360, 48))
        assertEquals(379, functionLibraryHeightDp(1000, 10000))
    }

    @Test fun pageParameterReaderKeepsGeneratedDocsAndVisualConfiguration() {
        val entry = FunctionCatalog.search(visualGroups, "读取页面参数", "界面")
            .single { it.second.blockKind == "ui.get" }.second
        assertEquals("ui.get", FunctionCatalog.blockKindOf(entry.snippet))
        assertTrue(entry.notes.contains("RunnerConfig"))
        assertTrue(FunctionCatalog.search(visualGroups, "读取控件值").any { it.second.blockKind == "ui.get" })
        assertEquals("UI.getValue", FunctionCatalog.search(apiGroups, "读取页面参数", "界面").single().second.apiName)
    }

    @Test fun dialogsAreDebugFunctionsWhileWidgetsStayInInterface() {
        val dialogs = SCRIPT_DIALOG_KINDS.toSet()
        assertEquals(setOf("ui.confirm", "ui.dialog", "ui.dialogclose"), dialogs)
        val debug = visualGroups.single { it.label == "调试" }.entries
        assertTrue(debug.map { it.blockKind }.containsAll(dialogs))
        assertTrue(debug.filter { it.blockKind in dialogs }.all { it.section == "对话框" })
        assertEquals(setOf("ui.get", "ui.set", "ui.command"),
            visualGroups.single { it.label == "界面" }.entries.map { it.blockKind }.toSet())
        listOf("UI.confirm", "UI.showDialogAndWait", "UI.closeDialog").forEach { api ->
            assertEquals("调试", FunctionCatalog.search(apiGroups, api).first().first)
            assertEquals("调试", FunctionCatalog.search(visualGroups, api).first().first)
        }
        assertEquals("界面", FunctionCatalog.search(apiGroups, "UI.waitEvent").first().first)
    }

    @Test fun imageProcessingAndClockReadingUseTheirActualPurpose() {
        listOf("screen.crop", "screen.transform", "screen.savepng").forEach { kind ->
            assertEquals("图像", FunctionCatalog.search(visualGroups, kind).first().first)
            assertEquals("图像处理", visual.single { it.blockKind == kind }.section)
        }
        listOf("Screen.crop", "Screen.transform", "Screen.savePng").forEach { api ->
            assertEquals("图像", FunctionCatalog.search(apiGroups, api).first().first)
        }
        listOf("system.time", "system.elapsed", "System.getTimeMillis", "System.elapsedRealtimeMillis").forEach { name ->
            assertEquals("时间", FunctionCatalog.search(visualGroups, name).first().first)
        }
        assertEquals("系统", FunctionCatalog.search(visualGroups, "system.screensize").first().first)
        assertEquals("屏幕", FunctionCatalog.search(visualGroups, "screen.capture").first().first)
        assertEquals("时间", FunctionCatalog.search(apiGroups, "System.getTimeMillis").first().first)
        assertEquals("时间", FunctionCatalog.search(apiGroups, "System.elapsedRealtimeMillis").first().first)
    }

    @Test fun imageLoadingIsNotMisrepresentedAsTemplateMatching() {
        assertEquals(listOf("Screen.loadImage"), FunctionApiReference.aliasesForKind("screen.loadimage"))
        assertFalse(FunctionApiReference.aliasesForKind("vision.findimage").contains("Screen.loadImage"))
        assertEquals("图像", FunctionCatalog.search(visualGroups, "screen.loadimage").first().first)
        assertEquals("图片读取", visual.single { it.blockKind == "screen.loadimage" }.section)
    }

    @Test fun variableLoopAndCallSectionsFollowTheActualOperation() {
        assertEquals("赋值与计算", functionSection("variable.calculate"))
        assertEquals("循环结构", functionSection("control.repeat"))
        assertEquals("循环指标与退出", functionSection("control.loopmetric"))
        assertEquals("调用参数", functionSection("flow.argument.set"))
        assertEquals("返回参数与状态", functionSection("flow.return.get"))
        assertEquals("读取与查询", functionSection("data.getjson"))
        assertEquals("集合修改", functionSection("data.append"))
    }

    @Test fun debugDialogShortcutsOnlyCreateSafeVisualInsertionHints() {
        SCRIPT_DIALOG_KINDS.forEach { kind ->
            val hint = requireNotNull(scriptDialogInsertionHint(kind))
            assertEquals(kind, FunctionCatalog.blockKindOf(hint))
            assertEquals("--@autoscript-block:$kind\n", hint)
            assertNotNull(BlockCatalog.find(kind))
        }
        assertNull(scriptDialogInsertionHint("UI.confirm"))
        assertNull(scriptDialogInsertionHint("ui.get"))
        assertNull(scriptDialogInsertionHint("ui.confirm\nTask.stop()"))
    }

    @Test fun categoryTabsUseCompactCenteredRowsWithoutClippingLargeText() {
        assertEquals(28, FUNCTION_LIBRARY_CATEGORY_MIN_HEIGHT_DP)
        assertEquals(76, FUNCTION_LIBRARY_CATEGORY_WIDTH_DP)
        val source = File("src/main/java/com/autoscript/studio/editor/FunctionLibraryDialog.kt").readText()
        assertTrue(source.contains("width(FUNCTION_LIBRARY_CATEGORY_WIDTH_DP.dp)"))
        val category = source.substringAfter("private fun LibraryCategory(").substringBefore("private fun LibraryFunctionRow(")
        assertTrue(category.contains("heightIn(min = FUNCTION_LIBRARY_CATEGORY_MIN_HEIGHT_DP.dp)"))
        assertTrue(category.contains("contentAlignment = Alignment.Center"))
        assertTrue(category.contains("fontSize = 11.sp, lineHeight = 14.sp"))
        assertTrue(category.contains("textAlign = TextAlign.Center"))
        assertTrue(category.contains("maxLines = 2"))
        assertTrue(category.contains("clickable(onClick = onClick)"))
        assertTrue(category.contains("Text(\"\$label \$count\""))
    }
}
